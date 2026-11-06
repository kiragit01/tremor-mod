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
    static final int MIN_OPEN = 2;
    static final int SPAWN_OPEN = 3;
    /** Hanging still it is this high (a share of its full height), breathing; creeping a little more. */
    static final double HANG_SHARE = 0.3;
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
    private static int next;

    private enum State { HANG, CREEP, CRACKLE, RECOVER, SINK }

    /** A stalactite falling: its pieces from the top down; the last one is the tip, which strikes. */
    private record Stalactite(List<FallingBlockEntity> pieces, double fromY) {
        FallingBlockEntity tip() {
            return pieces.get(pieces.size() - 1);
        }
    }

    /** A stalactite to drop a few ticks later (the ones beside the first). */
    private record Due(BlockPos at, int length, long when) {
    }

    private StalactiteCrawlers() {
    }

    // ---- events ----

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
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
                c.kill(level, event.getState(), event.getPlayer());
            }
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        CRAWLERS.clear();
        FALLING.clear();
        DUE.clear();
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
            if (state.isAir()) {
                open++;
                continue;
            }
            BlockPos under = pos.below();
            if (open >= SPAWN_OPEN && (state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(Blocks.DRIPSTONE_BLOCK))
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
        List<Creature> creatures = CRAWLERS.remove(level);
        if (creatures == null) {
            return 0;
        }
        for (Creature c : creatures) {
            PacketDistributor.sendToPlayersInDimension(level, TremorStatePayload.absent(c.instance, level.getGameTime()));
        }
        return creatures.size();
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
        int instance = TremorStatePayload.CRAWLER_BASE - next;
        next = (next + 1) % TremorStatePayload.CRAWLER_RANGE;
        return instance;
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

        private boolean ceiling(long node) {
            int x = VoxelPos.x(node), y = VoxelPos.y(node), z = VoxelPos.z(node);
            return graph.normal(x, y, z).y() <= CEILING_NORMAL && !lit(level, new BlockPos(x, y - 1, z));
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
                    amplitude = full * HANG_SHARE;
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
                        play(level, at, SoundEvents.DRIPSTONE_BLOCK_STEP, 0.5f, 0.5f + random.nextFloat() * 0.2f);
                    }
                    if (there || crawler.arrived()) {
                        there = false;
                        state = State.CRACKLE;
                        ticks = 0;
                        crackleTicks = config.crackleTicks.get() + random.nextInt(5);
                        play(level, dropAt, SoundEvents.POINTED_DRIPSTONE_FALL, 1.2f, 0.6f + random.nextFloat() * 0.2f);
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
                        int count = Math.min(MAX_AT_ONCE, ++attempts);
                        drop(level, dropAt, random, count);
                        Tremor.LOGGER.info("Stalactite crawler #{} in {} drops {} stalactites over {} {} {}", instance,
                                level.dimension().location(), count, dropAt.getX(), dropAt.getY(), dropAt.getZ());
                        state = State.RECOVER;
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
            int x = (int) Math.floor(sound.x()), z = (int) Math.floor(sound.z()), ground = (int) Math.floor(sound.y());
            if (dropAt != null && state == State.CREEP
                    && Math.hypot(dropAt.getX() - x, dropAt.getZ() - z) <= RETARGET_DISTANCE) {
                return true; // already on the way there
            }
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
            Tremor.LOGGER.info("Stalactite crawler #{} in {} sinks into the rock ({})", instance,
                    level.dimension().location(), why);
        }

        void kill(ServerLevel level, BlockState block, Player by) {
            Vec3 p = crawler.position();
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, block), p.x(), p.y() - 0.5, p.z(), 40,
                    0.6, 0.3, 0.6, 0.15);
            play(level, BlockPos.containing(p.x(), p.y(), p.z()), SoundEvents.POINTED_DRIPSTONE_BREAK, 1.2f, 0.5f);
            sink(level, "killed by " + (by == null ? "something" : by.getGameProfile().getName()));
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
                    TremorStatePayload.NO_RIPPLE, this.state == State.HANG ? 1 : 0, null);
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
     * Drops {@code count} stalactites: one from {@code at}, the others around it within {@value #EXTRA_RADIUS} blocks
     * a moment later each, where the ceiling lets them: each 1 to 3 pieces long, never two alike.
     */
    private static void drop(ServerLevel level, BlockPos at, RandomSource random, int count) {
        dropOne(level, at, 1 + random.nextInt(3));
        for (int i = 1; i < count; i++) {
            BlockPos side = at.offset(random.nextInt(2 * EXTRA_RADIUS + 1) - EXTRA_RADIUS, 0,
                    random.nextInt(2 * EXTRA_RADIUS + 1) - EXTRA_RADIUS);
            for (int dy : new int[]{0, 1, -1}) {
                BlockPos from = side.above(dy);
                if (!from.equals(at) && level.getBlockState(from).isAir()
                        && !level.getBlockState(from.above()).getCollisionShape(level, from.above()).isEmpty()) {
                    DUE.computeIfAbsent(level, l -> new ArrayList<>()).add(new Due(from, 1 + random.nextInt(3),
                            level.getGameTime() + 2 + random.nextInt(9)));
                    break;
                }
            }
        }
    }

    private static void dropOne(ServerLevel level, BlockPos at, int length) {
        int pieces = 0;
        while (pieces < length && level.getBlockState(at.below(pieces)).isAir()) {
            pieces++;
        }
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
        FALLING.computeIfAbsent(level, l -> new ArrayList<>()).add(new Stalactite(falling, at.getY()));
    }

    /** Drops the ones due; strikes what a tip falls on (once, and it breaks); breaks where it lands. */
    private static void tickStalactites(ServerLevel level) {
        List<Due> due = DUE.get(level);
        if (due != null) {
            long now = level.getGameTime();
            due.removeIf(d -> {
                if (now < d.when()) {
                    return false;
                }
                dropOne(level, d.at(), d.length());
                return true;
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
            shatter(level, tip);
            smash(level, tip.blockPosition());
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
