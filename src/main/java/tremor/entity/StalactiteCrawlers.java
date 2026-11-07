package tremor.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import tremor.Tremor;
import tremor.config.Difficulty;
import tremor.config.TremorConfig;
import tremor.core.VoxelView;
import tremor.core.behavior.Stage;
import tremor.core.graph.Graph;
import tremor.core.graph.SurfaceGraph;
import tremor.core.hearing.Hearing;
import tremor.core.hearing.HearingParams;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.motion.Crawler;
import tremor.core.motion.MotionParams;
import tremor.core.path.PathSearch;
import tremor.core.shape.BumpParams;
import tremor.hearing.Vibration;
import tremor.hollow.HollowDimension;
import tremor.network.TremorShapePayload;
import tremor.network.TremorStatePayload;
import tremor.world.LevelVoxelView;

/**
 * The stalactite crawler (1.4.0): a small creature of its own on the ceilings of dark caves, not a tremor. It hangs
 * there nearly flat, breathing; hearing a player (or a thing that lands) under it, it creeps along the ceiling to
 * over the sound, crackles for a moment (one who hears it may still jump away) and drops stalactites, one more each
 * time it tries (the user), ragged: they strike hard, knock away and break through a block or two where they land;
 * then it hangs there bulging for a while, easy to see. Light drives it off: it never goes onto a lit
 * ceiling, and where a light comes on it sinks into the rock; a lit mine is safe. Breaking the block it is in kills
 * it. Not saved: the natural spawn brings new ones near players underground.
 * <p>
 * The clients draw each as a small bump of its own, through the tremor's payloads under an instance of their own
 * range ({@link TremorStatePayload#crawler}), which the seismograph and the rustle leave out.
 */
public final class StalactiteCrawlers {
    /** A ceiling: a node whose smoothed normal points at least this much down. */
    static final double CEILING_NORMAL = -0.5;
    /** The highest ceiling over a sound it drops on (blocks over the block the source stands on). */
    static final int MAX_DROP = 20;
    /** Open blocks a ceiling needs under it, to drop from and to spawn on. */
    static final int MIN_OPEN = 4;
    static final int SPAWN_OPEN = 4;
    /** Hanging still it is this high (a share of its full height), breathing; creeping a little more. */
    static final double HANG_SHARE = 0.2;
    /** Hanging it breathes: down by this share of its height and back, once in this many ticks. */
    static final double HANG_BREATH = 0.3;
    static final int HANG_BREATH_TICKS = 50;
    static final double CREEP_SHARE = 0.45;
    /** Breaking a block this near its middle kills it (blocks). */
    static final double KILL_RADIUS = 1.6;
    /** A creep that has not arrived in this long is given up (ticks). */
    static final int GIVE_UP_TICKS = 600;
    /** A sound heard while it creeps takes it elsewhere at most this often (ticks), and only that far off (blocks). */
    static final int RETARGET_TICKS = 10;
    static final double RETARGET_DISTANCE = 1.5;
    /** It scrapes along the ceiling every this many ticks while it creeps. */
    static final int SCRAPE_TICKS = 14;
    /** The crackle's last snap comes this far into its sound (tools/sounds/synth.py), set on the drop by its pitch. */
    static final float CRACKLE_SNAP_SECONDS = 0.88f;
    /** Light is looked at every this many ticks. */
    static final int LIGHT_TICKS = 10;
    /** The natural spawn tries every this many ticks, this far from the player (blocks across). */
    static final int SPAWN_TICKS = 100;
    static final double SPAWN_MIN = 8;
    static final double SPAWN_MAX = 26;
    static final int SPAWN_ATTEMPTS = 10;
    /** At most this many near a player (the config) counts those within this far (blocks). */
    static final double NEAR_RADIUS = 48;
    /** No two this near each other (blocks). */
    static final double SPACING = 6;
    /**
     * Two going for the same sound keep this far apart (blocks): not one on top of another, but near enough to drop
     * together (the user).
     */
    static final double MIN_GAP = 2.5;
    /** It tries this many spots at most to get to over a sound. */
    static final int MAX_SPOTS = 4;
    /** With no player this near (blocks) for this long (ticks) it sinks away. */
    static final double LONELY_DISTANCE = 64;
    static final int LONELY_TICKS = 600;
    /** Dives through the rock cost this much more than crawling (it keeps to the ceiling it is on if it can). */
    static final double DIVE_COST = 4;
    static final double NODE_RADIUS = 2;
    static final double SUNK = 0.05;
    static final double TICK_SECONDS = 0.05;
    /** Every attempt of a crawler one more stalactite falls (the user: the third time three), at most this many... */
    static final int MAX_AT_ONCE = 5;
    /** ...within this far of the first (blocks). */
    static final int EXTRA_RADIUS = 3;
    /** A stalactite knocks what it strikes away this hard (as a knockback's strength). */
    static final double KNOCK = 1.2;
    /** Where one lands it breaks the block under it, and at this chance the one under that too. */
    static final double SMASH_DEEPER = 0.5;

    private static final Map<ServerLevel, List<Creature>> CRAWLERS = new HashMap<>();
    private static final Map<ServerLevel, List<Stalactite>> FALLING = new HashMap<>();
    private static final Map<ServerLevel, List<Due>> DUE = new HashMap<>();

    private enum State { HANG, CREEP, CRACKLE, RECOVER, SINK }

    /** A stalactite falling: its pieces from the top down; the last one is the tip, which strikes. */
    private record Stalactite(List<FallingBlockEntity> pieces, double fromY) {
        FallingBlockEntity tip() {
            return pieces.get(pieces.size() - 1);
        }
    }

    /**
     * A stalactite to drop at {@code when}, {@code length} pieces long; from {@code growFrom} on a column of fake blocks
     * (the clients' bump {@code phantom}) grows down from the ceiling over it to that length: it is drawn out of the
     * ceiling, not out of nothing (the user).
     */
    private record Due(BlockPos at, int length, long when, long growFrom, int phantom) {
    }

    /** The columns grow this long before their stalactite comes off (ticks): about a second... */
    static final int GROW_TICKS = 20;
    /** ...and draw back into the ceiling over this long after (the user: not gone at once). */
    static final int SHRINK_TICKS = 12;
    /** The mounds drawing back: each due that came off, and when. */
    private static final Map<ServerLevel, List<Due>> SHRINKING = new HashMap<>();

    private StalactiteCrawlers() {
    }

    // ---- events ----

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (tremor.TremorSwitch.off(level)) {
            return; // the mod is switched off (doTremor): the switch took them away
        }
        tickStalactites(level);
        List<Creature> creatures = CRAWLERS.get(level);
        if (creatures != null) {
            creatures.removeIf(c -> c.tick(level));
        }
        if (level.getGameTime() % SPAWN_TICKS == 0) {
            spawnNaturally(level);
        }
    }

    /** A block broken in a crawler kills it. */
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        List<Creature> creatures = CRAWLERS.get(level);
        if (creatures == null) {
            return;
        }
        BlockPos pos = event.getPos();
        for (Creature c : creatures) {
            Vec3 p = c.crawler.position();
            if (c.state != State.SINK && Math.sqrt(pos.distToCenterSqr(p.x(), p.y(), p.z())) <= KILL_RADIUS) {
                c.kill(level, event.getState(), event.getPlayer() == null ? "something"
                        : event.getPlayer().getGameProfile().getName());
            }
        }
    }

    /**
     * A resonance arrow's pulse at {@code at} ({@link ResonanceArrows}): every crawler within {@code radius} dissolves.
     * Whether it struck any.
     */
    public static boolean resonate(ServerLevel level, Vec3 at, double radius) {
        List<Creature> creatures = CRAWLERS.get(level);
        boolean struck = false;
        if (creatures != null) {
            for (Creature c : creatures) {
                Vec3 p = c.crawler.position();
                if (c.state != State.SINK && p.distance(at) <= radius) {
                    c.kill(level, level.getBlockState(BlockPos.containing(p.x(), p.y(), p.z())), "a resonance arrow");
                    struck = true;
                }
            }
        }
        return struck;
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        CRAWLERS.clear();
        FALLING.clear();
        DUE.clear();
        SHRINKING.clear();
    }

    // ---- hearing ----

    /** Whether any crawler of {@code level} listens. */
    public static boolean listens(ServerLevel level) {
        List<Creature> creatures = CRAWLERS.get(level);
        return creatures != null && !creatures.isEmpty();
    }

    /** Whether a source at {@code pos} is within the hearing range of a crawler of {@code level}. */
    public static boolean wants(ServerLevel level, net.minecraft.world.phys.Vec3 pos) {
        List<Creature> creatures = CRAWLERS.get(level);
        if (creatures == null) {
            return false;
        }
        double range = TremorConfig.COMMON.stalactites.hearingRange.get();
        for (Creature c : creatures) {
            Vec3 p = c.crawler.position();
            if (pos.distanceToSqr(p.x(), p.y(), p.z()) <= range * range) {
                return true;
            }
        }
        return false;
    }

    /**
     * A vibration in {@code level}: each crawler within its hearing range hears it over the live level as a tremor
     * would ({@link Hearing#perceived}), {@code hearingFactor} times better; the loudest it heard since its last tick
     * is what it goes for. Mobs it lets be.
     */
    public static void vibration(ServerLevel level, Vibration vibration) {
        List<Creature> creatures = CRAWLERS.get(level);
        if (creatures == null || creatures.isEmpty() || "mob".equals(vibration.note())) {
            return;
        }
        TremorConfig.Stalactites config = TremorConfig.COMMON.stalactites;
        HearingParams params = TremorConfig.COMMON.hearingParams();
        double range = config.hearingRange.get();
        LevelVoxelView view = null;
        for (Creature c : creatures) {
            Vec3 at = c.crawler.position();
            if (!c.listening() || vibration.source().distance(at) > range) {
                continue;
            }
            if (view == null) {
                view = new LevelVoxelView(level);
            }
            double perceived = Hearing.perceived(view, vibration.source(), at, vibration.loudness(),
                    vibration.footing(), vibration.foliage(view), params) * config.hearingFactor.get();
            if (perceived >= params.threshold() && perceived > c.soundLoudness) {
                c.sound = vibration.source();
                c.soundLoudness = perceived;
            }
        }
    }

    // ---- spawning ----

    /**
     * For each player underground (no sky light at the eyes) with fewer than {@code maxNearPlayer} crawlers near, by
     * chance one comes on a dark ceiling of natural stone {@value #SPAWN_MIN} to {@value #SPAWN_MAX} blocks off.
     */
    private static void spawnNaturally(ServerLevel level) {
        TremorConfig.Stalactites config = TremorConfig.COMMON.stalactites;
        if (!config.enabled.get() || !level.dimensionType().hasSkyLight() || HollowDimension.is(level)) {
            return;
        }
        RandomSource random = level.getRandom();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || level.getBrightness(LightLayer.SKY, BlockPos.containing(player.getEyePosition())) > 0
                    || near(level, player.getX(), player.getY(), player.getZ(), NEAR_RADIUS) >= config.maxNearPlayer.get()
                    || random.nextDouble() >= Difficulty.current().spawnChance(config.spawnChance.get())) {
                continue;
            }
            for (int i = 0; i < SPAWN_ATTEMPTS; i++) {
                BlockPos spot = spawnSpot(level, player, random);
                if (spot != null && spawn(level, spot, "natural, near " + player.getGameProfile().getName()) != null) {
                    break;
                }
            }
        }
    }

    /** A ceiling block of natural stone over at least {@value #SPAWN_OPEN} blocks of air, dark, in a cave; or null. */
    private static BlockPos spawnSpot(ServerLevel level, ServerPlayer player, RandomSource random) {
        double angle = random.nextDouble() * Math.PI * 2, d = SPAWN_MIN + random.nextDouble() * (SPAWN_MAX - SPAWN_MIN);
        int x = (int) Math.floor(player.getX() + Math.cos(angle) * d);
        int z = (int) Math.floor(player.getZ() + Math.sin(angle) * d);
        int from = (int) Math.floor(player.getY()) - 8;
        int open = 0;
        for (int y = from; y <= from + 28; y++) {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isLoaded(pos)) {
                return null;
            }
            BlockState state = level.getBlockState(pos);
            if (state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty()) {
                open++; // air, or what hangs in it (the vines and the blossoms of a lush cave)
                continue;
            }
            BlockPos under = pos.below();
            if (open >= SPAWN_OPEN && (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Blocks.DRIPSTONE_BLOCK)
                    || state.is(Blocks.MOSS_BLOCK)) // the moss of the green caves too (the user)
                    && level.getBrightness(LightLayer.SKY, under) == 0 && !lit(level, under)) {
                return pos;
            }
            open = 0;
        }
        return null;
    }

    /**
     * A crawler on the ceiling at {@code requested}, or on the first ceiling over it (within {@value #MAX_DROP}
     * blocks); null if there is none there (no dark ceiling node, or another crawler within {@value #SPACING}).
     */
    public static Integer spawn(ServerLevel level, BlockPos requested, String why) {
        if (tremor.TremorSwitch.off(level)) {
            return null; // the mod is switched off (doTremor)
        }
        SurfaceGraph graph = graph(level);
        Ceilings ceilings = new Ceilings(graph, level);
        VoxelView view = graph.view();
        long node = SurfaceGraph.NO_NODE;
        for (int y = requested.getY(); y <= requested.getY() + MAX_DROP; y++) {
            if (!view.isKnown(requested.getX(), y, requested.getZ())) {
                break;
            }
            if (view.isSolid(requested.getX(), y, requested.getZ())) {
                long n = VoxelPos.pack(requested.getX(), y, requested.getZ());
                if (ceilings.isNode(n)) {
                    node = n;
                }
                break;
            }
        }
        if (node == SurfaceGraph.NO_NODE) {
            return null;
        }
        Vec3 at = VoxelPos.center(node);
        if (near(level, at.x(), at.y(), at.z(), SPACING) > 0) {
            return null;
        }
        Creature c = new Creature(next(), level.getRandom(), graph, ceilings, node);
        CRAWLERS.computeIfAbsent(level, l -> new ArrayList<>()).add(c);
        Tremor.LOGGER.info("Stalactite crawler #{} in {} comes on the ceiling at {} {} {} ({})", c.instance,
                level.dimension().location(), VoxelPos.x(node), VoxelPos.y(node), VoxelPos.z(node), why);
        return c.instance;
    }

    /** Removes every crawler of {@code level}; how many there were. */
    public static int clear(ServerLevel level) {
        List<Due> due = DUE.remove(level);
        List<Due> shrinking = SHRINKING.remove(level);
        if (shrinking != null) {
            if (due == null) {
                due = new ArrayList<>();
            }
            due.addAll(shrinking);
        }
        if (due != null) {
            for (Due d : due) {
                if (d.phantom() != 0) {
                    PacketDistributor.sendToPlayersInDimension(level, TremorStatePayload.absent(d.phantom(),
                            level.getGameTime()));
                }
            }
        }
        List<Stalactite> falling = FALLING.remove(level);
        if (falling != null) {
            for (Stalactite s : falling) {
                s.pieces().forEach(FallingBlockEntity::discard); // none lands (and smashes) later (the review)
            }
        }
        List<Creature> creatures = CRAWLERS.remove(level);
        if (creatures == null) {
            return 0;
        }
        for (Creature c : creatures) {
            PacketDistributor.sendToPlayersInDimension(level, TremorStatePayload.absent(c.instance, level.getGameTime()));
        }
        return creatures.size();
    }

    /**
     * The spots over ({@code x}, {@code z}) it may go for, nearest first: that one if no other crawler's spot
     * ({@link #spot}) is within {@value #MIN_GAP}, then those around it {@value #MIN_GAP} and one more block out by a
     * turn of its own that are as clear.
     */
    private static List<int[]> spots(ServerLevel level, Creature self, int x, int z) {
        List<Creature> all = CRAWLERS.get(level);
        List<int[]> spots = new ArrayList<>();
        if (clear(all, self, x + 0.5, z + 0.5)) {
            spots.add(new int[]{x, z});
        }
        double turn = self.random.nextDouble() * Math.PI * 2;
        for (double r = MIN_GAP; r <= MIN_GAP + 1; r += 1) {
            for (int k = 0; k < 8; k++) {
                double a = turn + Math.PI * 2 * k / 8;
                int cx = (int) Math.floor(x + 0.5 + r * Math.cos(a)), cz = (int) Math.floor(z + 0.5 + r * Math.sin(a));
                if (clear(all, self, cx + 0.5, cz + 0.5)) {
                    spots.add(new int[]{cx, cz});
                }
            }
        }
        return spots;
    }

    /** Whether no crawler but {@code self} has its spot within {@value #MIN_GAP} of ({@code x}, {@code z}). */
    private static boolean clear(List<Creature> all, Creature self, double x, double z) {
        if (all == null) {
            return true;
        }
        for (Creature c : all) {
            if (c != self && c.state != State.SINK) {
                double[] s = spot(c);
                if (Math.hypot(s[0] - x, s[1] - z) < MIN_GAP) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Where a crawler is going to be: where it drops from while it creeps or crackles, else where it hangs. */
    private static double[] spot(Creature c) {
        if (c.dropAt != null && (c.state == State.CREEP || c.state == State.CRACKLE)) {
            return new double[]{c.dropAt.getX() + 0.5, c.dropAt.getZ() + 0.5};
        }
        Vec3 p = c.crawler.position();
        return new double[]{p.x(), p.z()};
    }

    /** How many crawlers of {@code level} are within {@code radius} of the point. */
    public static int near(ServerLevel level, double x, double y, double z, double radius) {
        List<Creature> creatures = CRAWLERS.get(level);
        int count = 0;
        if (creatures != null) {
            for (Creature c : creatures) {
                Vec3 p = c.crawler.position();
                double dx = p.x() - x, dy = p.y() - y, dz = p.z() - z;
                if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                    count++;
                }
            }
        }
        return count;
    }

    private static int next() {
        return MinorIds.next();
    }

    private static SurfaceGraph graph(ServerLevel level) {
        return new SurfaceGraph(new LevelVoxelView(level), TremorConfig.COMMON.maxDiveDepth.get(),
                TremorConfig.COMMON.minGroundWidth.get(), true);
    }

    /** Whether light at {@code pos} keeps a crawler off: at least {@code lightLevel}. */
    static boolean lit(ServerLevel level, BlockPos pos) {
        return level.getMaxLocalRawBrightness(pos) >= TremorConfig.COMMON.stalactites.lightLevel.get();
    }

    /** The surface graph of the level, its nodes only the dark ceilings. */
    private record Ceilings(SurfaceGraph graph, ServerLevel level) implements Graph {
        @Override
        public boolean isNode(long node) {
            return graph.isNode(node) && ceiling(node);
        }

        @Override
        public void forEachEdge(long node, EdgeConsumer consumer) {
            graph.forEachEdge(node, (to, length, dive) -> {
                if (ceiling(to)) {
                    consumer.accept(to, length, dive);
                }
            });
        }

        /**
         * A dark ceiling with {@value #MIN_OPEN} open blocks under it at least: none in a low tunnel, where it would
         * hang before a player's eyes and have no room to drop (the user).
         */
        private boolean ceiling(long node) {
            int x = VoxelPos.x(node), y = VoxelPos.y(node), z = VoxelPos.z(node);
            if (graph.normal(x, y, z).y() > CEILING_NORMAL || lit(level, new BlockPos(x, y - 1, z))) {
                return false;
            }
            VoxelView view = graph.view();
            for (int d = 1; d <= MIN_OPEN; d++) {
                if (!view.isOpen(x, y - d, z)) {
                    return false;
                }
            }
            return true;
        }
    }

    // ---- one crawler ----

    private static final class Creature {
        final int instance;
        final BumpParams shape;
        final RandomSource random;
        SurfaceGraph graph;
        Ceilings ceilings;
        final Crawler crawler;
        State state = State.HANG;
        int ticks;
        int lonely;
        long lastSearch = Long.MIN_VALUE / 2; // (now - MIN_VALUE overflows)
        /** The loudest sound heard since the last tick (where it enters the ground), or null; and how loud. */
        Vec3 sound;
        double soundLoudness;
        /** Where it drops from: the open block under the ceiling over the sound it goes for. */
        BlockPos dropAt;
        /** It is over the sound already (no way to crawl). */
        boolean there;
        int crackleTicks;
        /** Its attempts so far: each drops one more stalactite. */
        int attempts;
        /** The players that have its shape (within the sync range at the last sync). */
        Set<UUID> told = new HashSet<>();

        Creature(int instance, RandomSource random, SurfaceGraph graph, Ceilings ceilings, long node) {
            this.instance = instance;
            this.random = random;
            this.graph = graph;
            this.ceilings = ceilings;
            // Each its own: a little bigger or smaller, lopsided, and lumpy (a strong jitter: the edge ragged, writhing).
            BumpParams d = BumpParams.defaults().scaled(0.4 + random.nextDouble() * 0.2);
            shape = new BumpParams(1.1 + random.nextDouble() * 0.4, d.sigmaFront() * vary(random),
                    d.sigmaBack() * vary(random), d.sigmaSide() * vary(random), d.trailLag(), d.trailSigma(),
                    d.trailDepth(), 0.35 + random.nextDouble() * 0.2);
            int x = VoxelPos.x(node), y = VoxelPos.y(node), z = VoxelPos.z(node);
            crawler = new Crawler(VoxelPos.center(node), graph.normal(x, y, z), Vec3.ZERO, 0,
                    random.nextDouble() * Math.PI * 2);
        }

        private static double vary(RandomSource random) {
            return 0.8 + random.nextDouble() * 0.4;
        }

        boolean listening() {
            return state == State.HANG || state == State.CREEP;
        }

        /** One tick; true once it is gone. */
        boolean tick(ServerLevel level) {
            long now = level.getGameTime();
            ticks++;
            Vec3 p = crawler.position();
            BlockPos at = BlockPos.containing(p.x(), p.y(), p.z());
            if (!level.isLoaded(at)) {
                gone(level, now);
                return true;
            }
            Player nearest = level.getNearestPlayer(p.x(), p.y(), p.z(), LONELY_DISTANCE, false);
            lonely = nearest == null ? lonely + 1 : 0;
            if (state != State.SINK && (lonely > LONELY_TICKS || (now + instance) % LIGHT_TICKS == 0
                    && lit(level, at.below()))) {
                sink(level, lonely > LONELY_TICKS ? "no player near" : "light");
            }
            TremorConfig.Stalactites config = TremorConfig.COMMON.stalactites;
            double full = shape.amplitude();
            double amplitude = 0;
            switch (state) {
                case HANG -> {
                    // A small knot breathing slowly (the user: lying flat and wide it looked like a tremor).
                    amplitude = full * HANG_SHARE * (1 - HANG_BREATH
                            + HANG_BREATH * Math.sin(2 * Math.PI * now / HANG_BREATH_TICKS + instance));
                    if (sound != null && now - lastSearch >= RETARGET_TICKS && go(level, sound)) {
                        state = State.CREEP;
                        ticks = 0;
                    }
                }
                case CREEP -> {
                    amplitude = full * CREEP_SHARE;
                    if (sound != null && now - lastSearch >= RETARGET_TICKS) {
                        go(level, sound); // out of reach: it keeps on to the last one
                    }
                    if (crawler.speed() > 0.1 && ticks % SCRAPE_TICKS == 0) {
                        play(level, at, tremor.sound.TremorSounds.CRAWLER_CREEP.get(), 0.38f, 0.85f + random.nextFloat() * 0.3f);
                    }
                    if (there || crawler.arrived()) {
                        there = false;
                        state = State.CRACKLE;
                        ticks = 0;
                        crackleTicks = config.crackleTicks.get() + random.nextInt(5);
                        // Its last snap as the stalactites come off (the user: the first drop had no sound).
                        play(level, dropAt, tremor.sound.TremorSounds.CRAWLER_CRACKLE.get(), 1.1f,
                                Mth.clamp(CRACKLE_SNAP_SECONDS * 20f / crackleTicks, 0.7f, 1.4f));
                        int count = Math.min(MAX_AT_ONCE, ++attempts);
                        drop(level, dropAt, random, count, level.getGameTime() + crackleTicks);
                        Tremor.LOGGER.info("Stalactite crawler #{} in {} drops {} stalactites over {} {} {}", instance,
                                level.dimension().location(), count, dropAt.getX(), dropAt.getY(), dropAt.getZ());
                    } else if (ticks > GIVE_UP_TICKS) {
                        crawler.stop();
                        state = State.HANG;
                    }
                }
                case CRACKLE -> {
                    amplitude = full;
                    if (ticks % 3 == 0) {
                        level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST,
                                        level.getBlockState(dropAt.above())), dropAt.getX() + 0.5, dropAt.getY() + 0.9,
                                dropAt.getZ() + 0.5, 5, 0.5, 0.05, 0.5, 0);
                    }
                    if (ticks >= crackleTicks) {
                        state = State.RECOVER; // its stalactites come off as their columns are grown
                        ticks = 0;
                    }
                }
                case RECOVER -> {
                    amplitude = full;
                    if (ticks >= config.cooldownSeconds.get() * 20) {
                        state = State.HANG;
                        ticks = 0;
                    }
                }
                case SINK -> amplitude = 0;
            }
            sound = null;
            soundLoudness = 0;
            double speed = config.speed.get() * Difficulty.current().speed();
            crawler.tick(TICK_SECONDS, new MotionParams(speed, speed * 2, 0.3, amplitude,
                    state == State.SINK ? 0.15 : 0.4), position -> {
                long node = graph.nearestNode(position, (int) NODE_RADIUS);
                return node == SurfaceGraph.NO_NODE ? Vec3.ZERO
                        : graph.normal(VoxelPos.x(node), VoxelPos.y(node), VoxelPos.z(node));
            });
            if (state == State.SINK && Math.abs(crawler.amplitude()) < SUNK) {
                gone(level, now);
                return true;
            }
            if (ticks % TremorConfig.COMMON.syncInterval.get() == 0 || ticks == 1) {
                sync(level, now);
            }
            return false;
        }

        /**
         * Sets off for over {@code sound}: the first ceiling up from where it enters the ground, a dark one with at
         * least {@value #MIN_OPEN} open blocks under it, reached along dark ceilings; false if there is none.
         */
        private boolean go(ServerLevel level, Vec3 sound) {
            int sx = (int) Math.floor(sound.x()), sz = (int) Math.floor(sound.z()), ground = (int) Math.floor(sound.y());
            if (dropAt != null && state == State.CREEP
                    && Math.hypot(dropAt.getX() - sx, dropAt.getZ() - sz) <= MIN_GAP + 1 + RETARGET_DISTANCE
                    && clear(CRAWLERS.get(level), this, dropAt.getX() + 0.5, dropAt.getZ() + 0.5)) {
                return true; // already on the way there, or beside it apart from another
            }
            // Over the sound, or beside it apart from the others: the first spot it can get to of a few.
            List<int[]> spots = spots(level, this, sx, sz);
            for (int i = 0; i < Math.min(spots.size(), MAX_SPOTS); i++) {
                if (goTo(level, spots.get(i)[0], spots.get(i)[1], ground)) {
                    return true;
                }
            }
            return false;
        }

        /** Sets off along the ceiling to over ({@code x}, {@code z}) above the ground at {@code ground}. */
        private boolean goTo(ServerLevel level, int x, int z, int ground) {
            lastSearch = level.getGameTime();
            graph = graph(level);
            ceilings = new Ceilings(graph, level);
            VoxelView view = graph.view();
            long goal = SurfaceGraph.NO_NODE;
            for (int y = ground + 1; y <= ground + MAX_DROP; y++) {
                if (!view.isKnown(x, y, z)) {
                    return false;
                }
                if (view.isSolid(x, y, z)) {
                    if (y - 1 - ground >= MIN_OPEN && ceilings.isNode(VoxelPos.pack(x, y, z))) {
                        goal = VoxelPos.pack(x, y, z);
                    }
                    break;
                }
            }
            BlockPos drop = goal == SurfaceGraph.NO_NODE ? null : new BlockPos(x, VoxelPos.y(goal) - 1, z);
            long start = graph.nearestNode(crawler.position(), (int) NODE_RADIUS);
            if (drop == null || !level.getBlockState(drop).isAir() || start == SurfaceGraph.NO_NODE) {
                return false;
            }
            if (start == goal) {
                crawler.stop();
                dropAt = drop;
                there = true;
                ticks = 0;
                return true;
            }
            PathSearch search = new PathSearch(ceilings, start, goal, TremorConfig.COMMON.pathMaxNodes.get(),
                    DIVE_COST);
            if (search.run() != PathSearch.Status.FOUND) {
                return false;
            }
            crawler.follow(search.path());
            dropAt = drop;
            there = false;
            ticks = 0;
            return true;
        }

        private void sink(ServerLevel level, String why) {
            state = State.SINK;
            crawler.stop();
            play(level, BlockPos.containing(crawler.position().x(), crawler.position().y(), crawler.position().z()),
                    tremor.sound.TremorSounds.CRAWLER_SINK.get(), 0.6f, 0.9f + level.getRandom().nextFloat() * 0.2f);
            Tremor.LOGGER.info("Stalactite crawler #{} in {} sinks into the rock ({})", instance,
                    level.dimension().location(), why);
        }

        void kill(ServerLevel level, BlockState block, String by) {
            Vec3 p = crawler.position();
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, block), p.x(), p.y() - 0.5, p.z(), 40,
                    0.6, 0.3, 0.6, 0.15);
            play(level, BlockPos.containing(p.x(), p.y(), p.z()), SoundEvents.POINTED_DRIPSTONE_BREAK, 1.2f, 0.5f);
            sink(level, "killed by " + by);
        }

        private void gone(ServerLevel level, long now) {
            PacketDistributor.sendToPlayersInDimension(level, TremorStatePayload.absent(instance, now));
        }

        /** Its state to the players within the sync range; its shape first to those that do not have it yet. */
        private void sync(ServerLevel level, long now) {
            Crawler c = crawler;
            Vec3 p = c.position();
            TremorStatePayload state = new TremorStatePayload(instance, true, now, p, c.normal(), c.forward(),
                    c.velocity(), (float) c.amplitude(), (float) TremorEntity.wrapPhase(c.phase()), Stage.HUNTING,
                    TremorStatePayload.NO_RIPPLE, 0, null); // never the wide flat look of a tremor lying in wait
            double range = TremorConfig.COMMON.syncRange.get();
            Set<UUID> inRange = new HashSet<>();
            for (ServerPlayer player : level.players()) {
                if (player.distanceToSqr(p.x(), p.y(), p.z()) <= range * range) {
                    if (!told.contains(player.getUUID())) {
                        PacketDistributor.sendToPlayer(player, new TremorShapePayload(instance, shape));
                    }
                    PacketDistributor.sendToPlayer(player, state);
                    inRange.add(player.getUUID());
                }
            }
            told = inRange;
        }
    }

    // ---- the stalactites ----

    /**
     * Readies {@code count} stalactites: one from {@code at} at {@code when}, the others around it within
     * {@value #EXTRA_RADIUS} blocks a moment later each, where the ceiling lets them: each 1 to 3 pieces long, never two
     * alike, each drawn out of the ceiling first ({@link Due}).
     */
    private static void drop(ServerLevel level, BlockPos at, RandomSource random, int count, long when) {
        due(level, at, 1 + random.nextInt(3), when, false); // under it its own bump hangs already
        for (int i = 1; i < count; i++) {
            BlockPos side = at.offset(random.nextInt(2 * EXTRA_RADIUS + 1) - EXTRA_RADIUS, 0,
                    random.nextInt(2 * EXTRA_RADIUS + 1) - EXTRA_RADIUS);
            for (int dy : new int[]{0, 1, -1}) {
                BlockPos from = side.above(dy);
                if (!from.equals(at) && level.getBlockState(from).isAir()
                        && !level.getBlockState(from.above()).getCollisionShape(level, from.above()).isEmpty()) {
                    due(level, from, 1 + random.nextInt(3), when + random.nextInt(9), true);
                    break;
                }
            }
        }
    }

    /**
     * A stalactite due at {@code when}; with a {@code column} growing for {@value #GROW_TICKS} ticks before (not the one
     * under the crawler: its bump and the column made a long tongue, the user).
     */
    private static void due(ServerLevel level, BlockPos at, int length, long when, boolean column) {
        int phantom = column ? MinorIds.next() : 0;
        long growFrom = column ? Math.max(level.getGameTime(), when - GROW_TICKS) : when;
        DUE.computeIfAbsent(level, l -> new ArrayList<>()).add(new Due(at, length, when, growFrom, phantom));
        if (!column) {
            return;
        }
        level.playSound(null, at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5, tremor.sound.TremorSounds.CRAWLER_GRIND.get(),
                SoundSource.HOSTILE, 0.9f, 0.85f + level.getRandom().nextFloat() * 0.3f); // rock drawn out of the ceiling
        // A small pointed mound hanging from the ceiling, some 3x3, rough as the crawler (the user), longer for a
        // longer stalactite.
        PacketDistributor.sendToPlayersInDimension(level, new TremorShapePayload(phantom,
                new BumpParams(growth(length), 0.85, 0.85, 0.85, 0, 1, 0, 0.3)));
    }

    /** How far the mound over a stalactite {@code length} pieces long hangs down at its point (blocks). */
    private static double growth(int length) {
        return 0.9 + 0.3 * length;
    }

    /** The mound over a due stalactite, as far grown as it is by now. */
    private static void growColumn(ServerLevel level, Due d, long now) {
        double share = Math.min(1, (now - d.growFrom() + 1) / (double) Math.max(1, d.when() - d.growFrom()));
        sendMound(level, d, now, growth(d.length()) * share);
    }

    /** The mound over {@code d}, {@code height} blocks at its point, to the players within the sync range. */
    private static void sendMound(ServerLevel level, Due d, long now, double height) {
        Vec3 ceiling = new Vec3(d.at().getX() + 0.5, d.at().getY() + 1.5, d.at().getZ() + 0.5);
        TremorStatePayload state = new TremorStatePayload(d.phantom(), true, now, ceiling, new Vec3(0, -1, 0),
                Vec3.UNIT_X, Vec3.ZERO, (float) height, 0, Stage.HUNTING, TremorStatePayload.NO_RIPPLE,
                0, null);
        double range = TremorConfig.COMMON.syncRange.get();
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(ceiling.x(), ceiling.y(), ceiling.z()) <= range * range) {
                PacketDistributor.sendToPlayer(player, state);
            }
        }
    }

    private static void dropOne(ServerLevel level, BlockPos at, int length) {
        int open = 0;
        while (open < MAX_DROP && level.getBlockState(at.below(open)).isAir()) {
            open++;
        }
        // Its tip starts 3 blocks over the floor at least, never in a standing player's head (the review).
        int pieces = Math.min(length, Math.max(1, open - 3));
        pieces = Math.min(pieces, open);
        if (pieces == 0) {
            return;
        }
        List<FallingBlockEntity> falling = new ArrayList<>();
        for (int i = 0; i < pieces; i++) {
            int fromTip = pieces - 1 - i;
            DripstoneThickness thickness = fromTip == 0 ? DripstoneThickness.TIP
                    : fromTip == 1 ? DripstoneThickness.FRUSTUM : DripstoneThickness.BASE;
            FallingBlockEntity piece = FallingBlockEntity.fall(level, at.below(i), Blocks.POINTED_DRIPSTONE
                    .defaultBlockState().setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.DOWN)
                    .setValue(PointedDripstoneBlock.THICKNESS, thickness));
            piece.dropItem = false;
            falling.add(piece);
        }
        play(level, at, SoundEvents.POINTED_DRIPSTONE_FALL, 1.2f, 0.7f + level.getRandom().nextFloat() * 0.3f);
        FALLING.computeIfAbsent(level, l -> new ArrayList<>()).add(new Stalactite(falling, at.getY()));
    }

    /** Drops the ones due; strikes what a tip falls on (once, and it breaks); breaks where it lands. */
    private static void tickStalactites(ServerLevel level) {
        List<Due> due = DUE.get(level);
        if (due != null) {
            long now = level.getGameTime();
            due.removeIf(d -> {
                if (now < d.when()) {
                    if (d.phantom() != 0 && now >= d.growFrom() && (now - d.growFrom()) % 2 == 0) {
                        growColumn(level, d, now);
                    }
                    return false;
                }
                if (d.phantom() != 0) {
                    SHRINKING.computeIfAbsent(level, l -> new ArrayList<>()).add(d); // its mound draws back
                }
                dropOne(level, d.at(), d.length()); // it comes off where its column hung
                return true;
            });
        }
        List<Due> shrinking = SHRINKING.get(level);
        if (shrinking != null) {
            long now = level.getGameTime();
            shrinking.removeIf(d -> {
                long since = now - d.when();
                if (since >= SHRINK_TICKS) {
                    PacketDistributor.sendToPlayersInDimension(level, TremorStatePayload.absent(d.phantom(), now));
                    return true;
                }
                if (since % 2 == 0) {
                    sendMound(level, d, now, growth(d.length()) * (1 - since / (double) SHRINK_TICKS));
                }
                return false;
            });
        }
        List<Stalactite> falling = FALLING.get(level);
        if (falling != null) {
            falling.removeIf(s -> tickStalactite(level, s));
        }
    }

    /** One tick of a falling stalactite; true once it is over. */
    private static boolean tickStalactite(ServerLevel level, Stalactite s) {
        FallingBlockEntity tip = s.tip();
        if (tip.isRemoved()) {
            // Landed (it discards itself), not unloaded with its chunk: no block read or broken where nothing is loaded.
            if (tip.getRemovalReason() == net.minecraft.world.entity.Entity.RemovalReason.DISCARDED
                    && level.isLoaded(tip.blockPosition())) {
                shatter(level, tip);
                smash(level, tip.blockPosition());
            }
            return true;
        }
        List<LivingEntity> struck = level.getEntitiesOfClass(LivingEntity.class, tip.getBoundingBox(),
                e -> e.isAlive() && !e.isSpectator() && !(e instanceof Player p && p.getAbilities().invulnerable));
        if (struck.isEmpty()) {
            return false;
        }
        float damage = (float) Difficulty.current().damage(TremorConfig.COMMON.stalactites.damage.get());
        for (LivingEntity e : struck) {
            if (damage > 0) {
                e.hurt(level.damageSources().fallingStalactite(tip), damage);
            }
            // Knocked away from where it fell (the user: harder); a player is told at once.
            double dx = tip.getX() - e.getX(), dz = tip.getZ() - e.getZ();
            if (dx * dx + dz * dz < 1e-4) {
                double a = level.getRandom().nextDouble() * Math.PI * 2;
                dx = Math.cos(a);
                dz = Math.sin(a);
            }
            e.knockback(KNOCK, dx, dz);
            e.hurtMarked = true;
            Tremor.LOGGER.info("A crawler's stalactite strikes {} ({} damage, fell {} blocks)", e.getName().getString(),
                    damage, String.format(java.util.Locale.ROOT, "%.1f", s.fromY() - tip.getY()));
        }
        for (FallingBlockEntity piece : s.pieces()) {
            piece.discard();
        }
        shatter(level, tip);
        return true;
    }

    /**
     * Where one lands it breaks through the block under it, and at {@value #SMASH_DEEPER} the one under that (the
     * user: through a block or two), dropping them as a pickaxe would; never one that cannot be broken, a fluid or one
     * of {@code #tremor:protected}.
     */
    /** Whether {@code p} lies in the spawn protection the config spares ({@code awakening.spawnProtection}). */
    private static boolean spawnProtected(net.minecraft.world.level.Level level, BlockPos p) {
        int radius = tremor.config.TremorConfig.spawnProtection(level);
        BlockPos spawn = level.getSharedSpawnPos();
        return radius > 0 && Math.max(Math.abs(p.getX() - spawn.getX()), Math.abs(p.getZ() - spawn.getZ())) <= radius;
    }

    private static void smash(ServerLevel level, BlockPos landed) {
        BlockPos p = landed.below();
        for (int i = 0; i < 2; i++, p = p.below()) {
            BlockState state = level.getBlockState(p);
            if (state.isAir() || !state.getFluidState().isEmpty() || state.getDestroySpeed(level, p) < 0
                    || state.is(tremor.world.TremorTags.PROTECTED) || !level.getWorldBorder().isWithinBounds(p)
                    || spawnProtected(level, p)) {
                return;
            }
            level.destroyBlock(p, true);
            if (level.getRandom().nextDouble() >= SMASH_DEEPER) {
                return;
            }
        }
    }

    private static void shatter(ServerLevel level, FallingBlockEntity tip) {
        play(level, tip.blockPosition(), SoundEvents.POINTED_DRIPSTONE_LAND, 1, 0.9f + level.getRandom().nextFloat() * 0.2f);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, tip.getBlockState()), tip.getX(),
                tip.getY() + 0.5, tip.getZ(), 20, 0.25, 0.25, 0.25, 0.1);
    }

    private static void play(ServerLevel level, BlockPos at, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, at, sound, SoundSource.HOSTILE, volume, pitch);
    }
}
