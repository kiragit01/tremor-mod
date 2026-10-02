package tremor.spawn;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import tremor.Tremor;
import tremor.awakening.AwakeningManager;
import tremor.config.TremorConfig;
import tremor.core.behavior.SpawnRules;
import tremor.core.behavior.SurfacePicker;
import tremor.core.graph.SurfaceGraph;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.entity.TremorEntity;
import tremor.entity.TremorManager;
import tremor.entity.TremorRuntime;
import tremor.entity.TremorSavedData;
import tremor.world.LevelVoxelView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Natural spawn of the entity (SPEC 11). Event handlers are registered on the game bus by {@link tremor.Tremor};
 * server thread only.
 * <p>
 * In each level of the configured dimensions every player gets a spawn check every {@code checkIntervalSeconds},
 * the players spread over that time, the first check {@code graceSeconds} after the player entered the level
 * ({@link CheckSchedule}). A check rolls only for a living player in survival or adventure mode, outside peaceful
 * difficulty (unless allowed), in a level without an entity and without a running Awakening
 * ({@link AwakeningManager#runs}: it would take a new entity at once), past the cooldown after the last natural one
 * left ({@link Cooldown}) and past the long pause after the last Awakening ({@link #awakeningCooldown}). With the
 * probability {@link SpawnRules#chance} for the player's {@link #conditions} it spawns a natural entity on a surface
 * point {@code minDistance}..{@link #maxDistance} away that the player sees or will pass
 * ({@link SurfacePicker#spawnPoint}, the player's way from {@link Heading}), outside the {@link #protection} zones.
 * A point the player only sees must lie in the player's view distance: the server keeps chunks loaded beyond it,
 * which the client does not draw. The point search runs only after a successful roll; it reads only loaded chunks,
 * through a fresh {@link SurfaceGraph} that is dropped afterwards.
 * <p>
 * Spawns are logged at INFO, failed checks (and why) at DEBUG, a too narrow distance band ({@link #maxDistance}) as a
 * warning.
 */
public final class NaturalSpawner {
    /** Light level below which the player is in the dark (SPEC 11 "темнота"). */
    private static final int DARK_LIGHT = 7;
    /** Height below which the player is deep (SPEC 11 "глубина"). */
    private static final int DEEP_BELOW_Y = 0;

    /**
     * Least width of the distance band, blocks: the candidates are voxel centres, so a band much narrower than a voxel
     * holds hardly any.
     */
    private static final double MIN_DISTANCE_BAND = 1;

    private static final int TICKS_PER_SECOND = SharedConstants.TICKS_PER_SECOND;

    private static final Map<ResourceKey<Level>, LevelChecks> LEVELS = new HashMap<>();
    /** The configured dimension list parsed last, and its ids. */
    private static List<? extends String> dimensionList;
    private static Set<ResourceLocation> dimensionIds = Set.of();
    /** The configured distances last warned about as a too narrow band, so that each such setting is logged once. */
    private static double warnedMin = Double.NaN, warnedMax = Double.NaN;

    /** The check schedule of a level, kept with the level so that it never outlives that instance. */
    private record LevelChecks(ServerLevel level, CheckSchedule schedule) {
    }

    /**
     * What a point search found (pick null if nothing), the way ahead, protection and view distance (chunks; 0 while
     * the player has no chunks) it used, how long it took.
     */
    private record Search(SurfacePicker.SpawnPick pick, Heading heading, SpawnProtection protection, int viewDistance,
                          double millis) {
    }

    /**
     * The result of {@link #spawnNow}.
     *
     * @param entity  the spawned entity, or null if none was
     * @param message what happened, for the player
     */
    public record Outcome(TremorEntity entity, String message) {
    }

    private NaturalSpawner() {
    }

    /** Not while the game is frozen ({@code /tick freeze}): the schedules wait. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.tickRateManager().runsNormally()) {
            return;
        }
        TremorConfig.Spawn config = TremorConfig.COMMON.spawn;
        List<ServerPlayer> players = level.players();
        if (!config.enabled.get() || players.isEmpty() || !allowed(level)) {
            // Forgets the players: whoever comes (back) gets a grace.
            LEVELS.remove(level.dimension());
            return;
        }
        LevelChecks checks = LEVELS.get(level.dimension());
        if (checks == null || checks.level() != level) {
            checks = new LevelChecks(level, new CheckSchedule());
            LEVELS.put(level.dimension(), checks);
        }
        CheckSchedule schedule = checks.schedule();
        long now = level.getGameTime();
        int interval = config.checkIntervalSeconds.get() * TICKS_PER_SECOND;
        int grace = config.graceSeconds.get() * TICKS_PER_SECOND;
        for (ServerPlayer player : players) {
            if (schedule.due(player.getUUID(), now, interval, grace)) {
                Vec3 feet = feet(player);
                check(level, player, feet, schedule.swapPosition(player.getUUID(), feet), now);
            }
        }
        if (schedule.size() > players.size()) {
            Set<UUID> present = new HashSet<>();
            for (ServerPlayer player : players) {
                present.add(player.getUUID());
            }
            schedule.retain(present);
        }
    }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LevelChecks checks = LEVELS.get(level.dimension());
            if (checks != null && checks.level() == level) {
                LEVELS.remove(level.dimension());
            }
        }
    }

    /**
     * {@code /tremor spawn natural}: the spawn point search for the player at once and, if it finds one, a natural
     * entity there. Ignores the chance, the cooldown, the game mode, the difficulty, the dimension list and an entity
     * already in the level (it is replaced, as by {@code /tremor spawn}); the distances, the protection zones and the
     * view distance apply.
     */
    public static Outcome spawnNow(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 feet = feet(player);
        LevelChecks checks = LEVELS.get(level.dimension());
        Vec3 previous = checks != null && checks.level() == level
                ? checks.schedule().lastPosition(player.getUUID()) : null;
        Search search = search(level, player, feet, previous == null ? null : feet.sub(previous));
        SpawnRules.Conditions conditions = conditions(level, player);
        String chance = String.format(Locale.ROOT, "chance per check here %.3f (%s)", chance(conditions),
                names(conditions));
        SurfacePicker.SpawnPick pick = search.pick();
        if (pick == null) {
            TremorConfig.Spawn config = TremorConfig.COMMON.spawn;
            return new Outcome(null, String.format(Locale.ROOT,
                    "No spawn point: none of %d candidates %.0f-%.0f blocks away is a surface you see%s "
                            + "(view distance %d chunks; way from %s; %d protected zones of %.0f blocks); %s; "
                            + "search %.2f ms",
                    config.attempts.get(), config.minDistance.get(), maxDistance(),
                    search.heading().direction() != null ? " or will pass" : "", search.viewDistance(),
                    search.heading().source().id(), search.protection().size(), search.protection().radius(), chance,
                    search.millis()));
        }
        BlockPos pos = blockPos(pick.node());
        TremorEntity entity = TremorManager.spawn(level, pos, true);
        if (entity == null) {
            return new Outcome(null, "Found " + pos.toShortString() + ", but it is no surface node any more");
        }
        return new Outcome(entity, String.format(Locale.ROOT,
                "Tremor #%d spawned naturally at %s, %.1f blocks away (%s; way from %s); %s; search %.2f ms",
                entity.instance(), pos.toShortString(), pick.position().distance(feet), seen(pick),
                search.heading().source().id(), chance, search.millis()));
    }

    /**
     * Ticks left of the natural spawn pause after the last Awakening of the level ended (SPEC 9: the entity went deep,
     * a long cooldown; {@code awakening.cooldownSeconds}); 0 if there is none.
     */
    public static long awakeningCooldown(ServerLevel level) {
        return Cooldown.remaining(level.getGameTime(), TremorSavedData.get(level).lastAwakeningEnd(),
                (long) TremorConfig.COMMON.awakening.cooldownSeconds.get() * TICKS_PER_SECOND);
    }

    /** One due check of a player at {@code now}; {@code previous} is where it was at the last one (or null). */
    private static void check(ServerLevel level, ServerPlayer player, Vec3 feet, Vec3 previous, long now) {
        String skipped = skipReason(level, player, now);
        if (skipped != null) {
            debug(level, player, skipped);
            return;
        }
        SpawnRules.Conditions conditions = conditions(level, player);
        double chance = chance(conditions);
        double roll = ThreadLocalRandom.current().nextDouble();
        if (roll >= chance) {
            debug(level, player, String.format(Locale.ROOT, "rolled %.3f, chance %.3f (%s)", roll, chance,
                    names(conditions)));
            return;
        }
        Search search = search(level, player, feet, previous == null ? null : feet.sub(previous));
        SurfacePicker.SpawnPick pick = search.pick();
        if (pick == null) {
            debug(level, player, String.format(Locale.ROOT,
                    "no spawn point (chance %.3f; view distance %d chunks; way from %s; %d protected zones), "
                            + "search %.2f ms", chance, search.viewDistance(), search.heading().source().id(),
                    search.protection().size(), search.millis()));
            return;
        }
        BlockPos pos = blockPos(pick.node());
        TremorEntity entity = TremorManager.spawn(level, pos, true);
        if (entity == null) {
            debug(level, player, "spawn point " + pos.toShortString() + " is no surface node any more");
            return;
        }
        Tremor.LOGGER.info(String.format(Locale.ROOT,
                "Tremor #%d spawned naturally in %s at %s, %.1f blocks from %s (%s; way from %s); chance %.3f (%s), "
                        + "search %.2f ms", entity.instance(), level.dimension().location(), pos.toShortString(),
                pick.position().distance(feet), player.getScoreboardName(), seen(pick),
                search.heading().source().id(), chance, names(conditions), search.millis()));
    }

    /** Why the player's check does not roll, or null if it does. */
    private static String skipReason(ServerLevel level, ServerPlayer player, long now) {
        if (!player.isAlive()) {
            return "dead";
        }
        if (player.isSpectator()) {
            return "spectator";
        }
        if (player.isCreative()) {
            return "creative mode";
        }
        TremorConfig.Spawn config = TremorConfig.COMMON.spawn;
        if (level.getDifficulty() == Difficulty.PEACEFUL && !config.allowPeaceful.get()) {
            return "peaceful difficulty";
        }
        TremorRuntime runtime = TremorManager.runtime(level);
        if (runtime != null && runtime.entity() != null) {
            return "tremor #" + runtime.entity().instance() + " is in the dimension";
        }
        if (AwakeningManager.runs(level)) {
            return "an Awakening runs in the dimension";
        }
        long left = Cooldown.remaining(now, TremorSavedData.get(level).lastNaturalDespawn(),
                (long) config.cooldownSeconds.get() * TICKS_PER_SECOND);
        if (left > 0) {
            return String.format(Locale.ROOT, "cooldown, %.0f s left", (double) left / TICKS_PER_SECOND);
        }
        long deep = awakeningCooldown(level);
        if (deep > 0) {
            return String.format(Locale.ROOT, "gone deep after an Awakening, %.0f s left",
                    (double) deep / TICKS_PER_SECOND);
        }
        return null;
    }

    /**
     * What holds for the player (SPEC 11: "Предпочтения: пещеры, темнота, глубина, ночь"), at the block of the eyes:
     * <ul>
     *   <li>cave: no sky visible ({@code canSeeSky}: full sky light) and below both the
     *   {@link Heightmap.Types#MOTION_BLOCKING_NO_LEAVES} surface (no leaves) and the
     *   {@link Heightmap.Types#OCEAN_FLOOR} (no fluids). Sky light alone counts a tree crown (leaves dim it) or water
     *   as a roof; the height maps alone a glass roof.</li>
     *   <li>dark: the light (block light or sky light, the sky darkened by time and weather) below
     *   {@value #DARK_LIGHT}, as for monster spawning;</li>
     *   <li>deep: feet below y = {@value #DEEP_BELOW_Y};</li>
     *   <li>night: {@link Level#isNight()} (a thunderstorm darkens the sky enough to count, as for beds).</li>
     * </ul>
     */
    private static SpawnRules.Conditions conditions(ServerLevel level, ServerPlayer player) {
        BlockPos eye = BlockPos.containing(player.getEyePosition());
        int roof = Math.min(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, eye.getX(), eye.getZ()),
                level.getHeight(Heightmap.Types.OCEAN_FLOOR, eye.getX(), eye.getZ()));
        boolean cave = !level.canSeeSky(eye) && eye.getY() < roof;
        boolean dark = level.getMaxLocalRawBrightness(eye) < DARK_LIGHT;
        boolean deep = player.getY() < DEEP_BELOW_Y;
        return new SpawnRules.Conditions(cave, dark, deep, level.isNight());
    }

    private static double chance(SpawnRules.Conditions conditions) {
        TremorConfig.Spawn config = TremorConfig.COMMON.spawn;
        return SpawnRules.chance(config.baseChance.get(), conditions, new SpawnRules.Multipliers(
                config.caveMultiplier.get(), config.darkMultiplier.get(), config.deepMultiplier.get(),
                config.nightMultiplier.get()));
    }

    /**
     * The spawn point search for the player at {@code feet} ({@link SurfacePicker#spawnPoint}), over a fresh graph
     * of the loaded terrain. The way ahead is the {@link Heading} from the motion of the last move packet, the
     * {@code displacement} since the last check (null if unknown) and the direction the player faces. Besides the
     * protection zones, a candidate outside the player's view distance ({@link #inView}) is allowed only if the
     * player {@link Heading#passes} it, and then it does not count as visible even if the server's line of sight
     * reaches it.
     */
    private static Search search(ServerLevel level, ServerPlayer player, Vec3 feet, Vec3 displacement) {
        long start = System.nanoTime();
        TremorConfig.Spawn config = TremorConfig.COMMON.spawn;
        Heading heading = Heading.of(vec(player.getKnownMovement()), displacement,
                vec(player.calculateViewVector(0, player.getYRot())));
        SpawnProtection protection = protection(level);
        ChunkTrackingView view = player.getChunkTrackingView();
        double halfWidth = config.routeHalfWidth.get();
        Predicate<Vec3> allowed = p -> protection.test(p) && (inView(view, p) || heading.passes(feet, p, halfWidth));
        SurfaceGraph graph = new SurfaceGraph(new LevelVoxelView(level), TremorConfig.COMMON.maxDiveDepth.get());
        SurfacePicker.SpawnPick pick = SurfacePicker.spawnPoint(graph, feet, vec(player.getEyePosition()),
                heading.direction(), config.minDistance.get(), maxDistance(), halfWidth, config.verticalRange.get(),
                allowed, ThreadLocalRandom.current(), config.attempts.get());
        if (pick != null && pick.visible() && !inView(view, pick.position())) {
            pick = new SurfacePicker.SpawnPick(pick.node(), pick.position(), false, pick.nearRoute());
        }
        int viewDistance = view instanceof ChunkTrackingView.Positioned positioned ? positioned.viewDistance() : 0;
        return new Search(pick, heading, protection, viewDistance, (System.nanoTime() - start) / 1e6);
    }

    /**
     * Whether {@code p} is in a chunk the player's client draws: within the view distance the server sends chunks to
     * it for (the client's render distance, at most the server's view distance), as the client's
     * {@link ChunkTrackingView#isInViewDistance} test. The server keeps chunks loaded beyond it.
     */
    private static boolean inView(ChunkTrackingView view, Vec3 p) {
        return view.isInViewDistance(SectionPos.blockToSectionCoord(p.x()), SectionPos.blockToSectionCoord(p.z()));
    }

    /**
     * The configured maximum distance, at least {@value #MIN_DISTANCE_BAND} more than the minimum; a smaller one is
     * logged as a warning, once per setting.
     */
    private static double maxDistance() {
        TremorConfig.Spawn config = TremorConfig.COMMON.spawn;
        double min = config.minDistance.get(), max = config.maxDistance.get();
        if (max >= min + MIN_DISTANCE_BAND) {
            return max;
        }
        if (min != warnedMin || max != warnedMax) {
            warnedMin = min;
            warnedMax = max;
            Tremor.LOGGER.warn("Config spawn.maxDistance {} is less than spawn.minDistance {} + {}: {} is used", max,
                    min, MIN_DISTANCE_BAND, min + MIN_DISTANCE_BAND);
        }
        return min + MIN_DISTANCE_BAND;
    }

    /**
     * The zones no natural spawn point may lie in (SPEC 11): around the world spawn point in the overworld (where a
     * player without a bed respawns), and around the respawn point (bed, respawn anchor) in this dimension of every
     * player online.
     */
    private static SpawnProtection protection(ServerLevel level) {
        SpawnProtection protection = new SpawnProtection(TremorConfig.COMMON.spawn.protectionRadius.get());
        if (protection.radius() > 0) {
            if (level.dimension().equals(Level.OVERWORLD)) {
                protect(protection, level.getSharedSpawnPos());
            }
            for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
                BlockPos respawn = player.getRespawnPosition();
                if (respawn != null && level.dimension().equals(player.getRespawnDimension())) {
                    protect(protection, respawn);
                }
            }
        }
        return protection;
    }

    private static void protect(SpawnProtection protection, BlockPos pos) {
        protection.add(pos.getX() + 0.5, pos.getZ() + 0.5);
    }

    /** Whether natural spawn is on in the level's dimension (the config list, parsed again when it changes). */
    private static boolean allowed(ServerLevel level) {
        List<? extends String> list = TremorConfig.COMMON.spawn.dimensions.get();
        if (list != dimensionList) {
            Set<ResourceLocation> ids = new HashSet<>();
            for (String id : list) {
                ResourceLocation location = ResourceLocation.tryParse(id);
                if (location != null) {
                    ids.add(location);
                }
            }
            dimensionIds = ids;
            dimensionList = list;
        }
        return dimensionIds.contains(level.dimension().location());
    }

    private static void debug(ServerLevel level, ServerPlayer player, String message) {
        if (Tremor.LOGGER.isDebugEnabled()) {
            Tremor.LOGGER.debug("No natural tremor spawn for {} in {}: {}", player.getScoreboardName(),
                    level.dimension().location(), message);
        }
    }

    /** {@code visible}, {@code near the way ahead}, or both. */
    private static String seen(SurfacePicker.SpawnPick pick) {
        return pick.visible() && pick.nearRoute() ? "visible, near the way ahead"
                : pick.visible() ? "visible" : "near the way ahead";
    }

    /** The conditions that hold, like {@code cave, dark}, or {@code none}. */
    private static String names(SpawnRules.Conditions c) {
        List<String> names = new ArrayList<>(4);
        if (c.cave()) {
            names.add("cave");
        }
        if (c.dark()) {
            names.add("dark");
        }
        if (c.deep()) {
            names.add("deep");
        }
        if (c.night()) {
            names.add("night");
        }
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    private static Vec3 feet(ServerPlayer player) {
        return vec(player.position());
    }

    private static Vec3 vec(net.minecraft.world.phys.Vec3 v) {
        return new Vec3(v.x, v.y, v.z);
    }

    private static BlockPos blockPos(long node) {
        return new BlockPos(VoxelPos.x(node), VoxelPos.y(node), VoxelPos.z(node));
    }
}
