package tremor.awakening;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tremor.Tremor;
import tremor.config.TremorConfig;
import tremor.sound.TremorSounds;
import tremor.world.TremorTags;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Digs the craters of the Awakening into the real world (SPEC 9 "Исходы", 12: the one lasting change it makes there):
 * after a defeat and after an escape through the edge, at the place the player was swallowed, and where
 * {@code /tremor crater} asks for one. The funnel of a {@link CraterShape} of the configured size
 * ({@code awakening.craterRadius}, {@code awakening.craterDepth}) is carved as {@link CraterRules} allows, layer by layer
 * from the top down, so the ground caves in: with the crack and the rumble of the ground, and dust and crumbling
 * blocks over the layer being carved. A crater at the surface also takes all that stands over its funnel, up to the
 * top of the terrain there (trees, a hill, a house; {@value #REACH_UP} blocks over the swallow point at most), so
 * nothing is left hanging over it ({@link Job#carveTop}). A column carved through gets its rubble
 * ({@link CraterShape#rubbleAt}: gravel, some tuff and cobbled deepslate); what is filled in to close the crater (a
 * cavity beside it, the blocks of a fluid that touch it) is the ground around it (stone or deepslate if there is none),
 * and a creature in such a block is moved out first ({@link Job#rescue}). Event handlers are registered by
 * {@link tremor.Tremor}; server thread only.
 * <p>
 * Each crater holds a ticket on the chunks it touches (and the blocks around it) until it is done ({@link RealArea}),
 * and waits for them to load; then it is planned and dug a step at a time ({@link CraterRules.Digging}: a place of its
 * square, a block), at most {@code awakening.craterBlocksPerTick} blocks changed per tick, and all craters together
 * take {@code awakening.craterBudgetMillis} of server time per tick (SPEC 16): the clock is read before every step,
 * and a step is begun only if one as slow as the slowest of the tick so far still ends within the budget, so a tick
 * goes over it only by a step slower than those before it (the first steps on a JVM warming up, a GC pause); the next
 * tick goes on where it stopped. The server time of each crater (its worst tick, whether a GC pause fell in
 * it, the ticks over the budget) is logged once it is over and shown by {@code /tremor info}. A crater that is no
 * longer wanted (its defeat was called off) is not dug, or no further.
 * <p>
 * What is kept ({@link CraterRules.Cell#KEEP}): blocks with a block entity (the caches of an earlier crater too), of
 * {@code #tremor:protected}, that cannot be broken (a destroy speed below 0) or that burn (magma), and every block of
 * the spawn protection (the overworld around the world spawn, a square of the server's spawn protection radius:
 * {@code spawn-protection} of server.properties, 16 blocks in single player and on LAN) whoever the player is. Carved
 * blocks drop nothing, and their neighbours are not updated: nothing next to the crater pops off, falls or flows because
 * of it; only a plant next to a carved block that cannot stay without it drops as usual, and the leaves of a tree whose
 * trunk was carved wither as after a felling. Entities standing over the crater fall in, and mobs walking about do not
 * plan their paths again for each block changed ({@link Job#told}): they find out as they go. Not saved: a server that
 * stops in the middle leaves the crater as far as it got, closed like a finished one ({@link CraterRules}).
 */
public final class Craters {
    /** Keeps the chunks of a crater loaded while it is dug; one ticket per crater (its id). */
    private static final TicketType<Integer> TICKET = TicketType.create(Tremor.MODID + "_crater", Integer::compare);
    /** A crater whose chunks are not loaded after this many ticks is given up (none is dug). */
    private static final int LOAD_TIMEOUT_TICKS = 200;
    /** Carves a block without updating its neighbours; the clients are told apart ({@link Job#told}). */
    private static final int DIG_FLAGS = Block.UPDATE_KNOWN_SHAPE;
    /** How deep the shape updates around a felled trunk may go on ({@link Job#fell}), as {@code setBlock}'s. */
    private static final int SHAPE_RECURSION = 511;
    /** The exit of an escape through the edge keeps this many blocks beyond the farthest rim ({@link #footprint}). */
    static final int MARGIN = 4;
    /** A crater at the surface cuts at most this many blocks up into what stands over the swallow point. */
    static final int REACH_UP = 48;
    /** The ground rumbles under a crater being dug this often (ticks), and cracks this often. */
    private static final int RUMBLE_TICKS = 40;
    private static final int CRACK_TICKS = 10;
    /** At most this many carved blocks of a tick crumble visibly and audibly (the particles and sound of a break). */
    private static final int CRUMBLES_PER_TICK = 3;
    /** At most this many carved blocks of a tick raise dust. */
    private static final int DUST_PER_TICK = 6;
    private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private static final List<Job> JOBS = new ArrayList<>();
    /** What the last crater dug in each dimension did ({@code /tremor info}), until the server stops. */
    private static final Map<ResourceKey<Level>, String> LAST = new HashMap<>();
    /** Id of the next crater; ids are never reused while the server runs. */
    private static int nextId = 1;
    /** Picks the crater that goes first in a tick; one more each tick. */
    private static int turn;
    /**
     * The JVM's garbage collectors, from the first crater on (a pause of theirs in a crater's worst tick is said); none
     * before.
     */
    private static List<GarbageCollectorMXBean> collectors = List.of();

    private Craters() {
    }

    /** A column of a finished crater carved down to its floor (the block at {@code floor}), {@code rubble} layers on it. */
    public record Bottom(int x, int z, int floor, int rubble) {
        /** The block the feet of a player standing on the rubble there are in. */
        public int feet() {
            return floor + rubble + 1;
        }
    }

    /**
     * What a crater left once it was dug: its bottom, where a player who survived a defeat is put and the things of
     * one who did not are hidden ({@link CraterCaches}).
     */
    public static final class Crater {
        final int id;
        final ServerLevel level;
        /** The swallow point: the block the player's feet were in. */
        final BlockPos top;
        final long seed;
        /** The columns carved down to their floor, the nearest to the centre first. */
        final List<Bottom> bottoms;
        /** The bottoms that hold a cache, and where the caches are. */
        final Set<Bottom> cached = new HashSet<>();
        final List<BlockPos> caches = new ArrayList<>();

        Crater(int id, ServerLevel level, BlockPos top, long seed, List<Bottom> bottoms) {
            this.id = id;
            this.level = level;
            this.top = top;
            this.seed = seed;
            this.bottoms = List.copyOf(bottoms);
        }

        /**
         * Where a player put on the bottom stands: the middle of the block above the rubble of the column nearest the
         * centre; null if no column got down to its floor.
         */
        public Vec3 bottom() {
            if (bottoms.isEmpty()) {
                return null;
            }
            Bottom bottom = bottoms.get(0);
            return new Vec3(bottom.x() + 0.5, bottom.feet(), bottom.z() + 0.5);
        }
    }

    /**
     * Starts a crater of the configured size under {@code top} (the swallow point: its middle column goes down from
     * there; the caller checks that {@code awakening.craterRadius} is not 0) and returns its id. {@code why} says what
     * it is for (logs, {@code /tremor info}). Once it is dug, {@code done} is told what it left. While {@code wanted}
     * says no (asked before any digging, and on every tick of it) the crater is called off: it stays as far as it got,
     * and {@code done} is not told.
     */
    static int dig(ServerLevel level, BlockPos top, String why, BooleanSupplier wanted, Consumer<Crater> done) {
        TremorConfig.Awakening config = TremorConfig.COMMON.awakening;
        if (collectors.isEmpty()) {
            collectors = ManagementFactory.getGarbageCollectorMXBeans();
        }
        CraterShape shape = new CraterShape(Math.max(1, config.craterRadius.get()), config.craterDepth.get(),
                top.asLong() ^ level.getSeed());
        Job job = new Job(nextId++, level, top.immutable(), why, shape, wanted, done);
        JOBS.add(job);
        Tremor.LOGGER.info("Crater #{} ({}) opens at {} in {} (radius {}, depth {})", job.id, why, top.toShortString(),
                level.dimension().location(), shape.radius(), shape.depth());
        return job.id;
    }

    /**
     * How far from the swallow point (blocks, horizontally) a crater of {@code radius} blocks and what it changes
     * around it may reach, with a margin: the farthest rim and {@value #MARGIN} blocks. 0 for no crater.
     */
    public static int footprint(int radius) {
        return radius <= 0 ? 0 : CraterShape.reach(radius) + MARGIN;
    }

    /**
     * The craters being dug in {@code level}, a line each, and the last one dug there since the server started (for
     * {@code /tremor info}); null if there are none.
     */
    public static String describe(ServerLevel level) {
        StringBuilder text = new StringBuilder();
        for (Job job : JOBS) {
            if (job.level == level) {
                text.append(text.isEmpty() ? "" : "\n").append(job.describe());
            }
        }
        String last = LAST.get(level.dimension());
        if (last != null) {
            text.append(text.isEmpty() ? "" : "\n").append("Last dug: ").append(last);
        }
        return text.isEmpty() ? null : text.toString();
    }

    /**
     * The craters' part of this tick: within {@code awakening.craterBudgetMillis} for all of them, a different one
     * first each tick (with several, each moves on). What follows a crater once it is dug (its {@code done}) is timed
     * apart from it.
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (JOBS.isEmpty()) {
            return;
        }
        long budget = (long) (TremorConfig.COMMON.awakening.craterBudgetMillis.get() * 1e6);
        long deadline = System.nanoTime() + budget;
        long now = event.getServer().getTickCount();
        List<Job> jobs = List.copyOf(JOBS);
        int first = Math.floorMod(turn++, jobs.size());
        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get((first + i) % jobs.size());
            long begin = System.nanoTime();
            long collections = collections();
            boolean over;
            try {
                over = job.step(deadline, budget, now, i == 0);
            } catch (RuntimeException e) {
                Tremor.LOGGER.error("Crater #{} failed; it stays as far as it got", job.id, e);
                over = true;
            }
            Crater crater = null;
            if (over) {
                JOBS.remove(job);
                try {
                    crater = job.finish();
                } catch (RuntimeException e) {
                    Tremor.LOGGER.error("Crater #{}: finishing it failed", job.id, e);
                }
            }
            job.account(System.nanoTime() - begin, budget, collections() != collections);
            if (over) {
                job.report();
                if (crater != null) {
                    job.follow(crater);
                }
            }
        }
    }

    /** The levels and their tickets are gone; a crater still being dug stays as far as it got. */
    public static void onServerStopped(ServerStoppedEvent event) {
        JOBS.clear();
        LAST.clear();
    }

    /** How many collections the JVM's garbage collectors have made so far (each stops the server thread a while). */
    private static long collections() {
        long count = 0;
        for (GarbageCollectorMXBean collector : collectors) {
            count += Math.max(0, collector.getCollectionCount());
        }
        return count;
    }

    /** A block of the rubble on the bottom of a crater at {@code x, y, z}: mostly gravel, some tuff, cobbled deepslate. */
    static BlockState rubble(int x, int y, int z, long seed) {
        long mix = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
        mix = (mix ^ (mix >>> 31)) * 0xBF58476D1CE4E5B9L;
        int roll = (int) ((mix >>> 33) % 20);
        return (roll < 3 ? Blocks.TUFF : roll < 6 ? Blocks.COBBLED_DEEPSLATE : Blocks.GRAVEL).defaultBlockState();
    }

    /** Whether {@code state} is rubble of a crater ({@link #rubble}). */
    static boolean isRubble(BlockState state) {
        return state.is(Blocks.GRAVEL) || state.is(Blocks.TUFF) || state.is(Blocks.COBBLED_DEEPSLATE);
    }

    /** One crater being dug. */
    private static final class Job implements CraterRules.Works {
        final int id;
        final ServerLevel level;
        final BlockPos top;
        final String why;
        final CraterShape shape;
        final BooleanSupplier wanted;
        final Consumer<Crater> done;
        final long startTick;
        /** The chunks the crater touches, the blocks it looks at and fills in around it included. */
        final RealArea area;
        /** Chebyshev radius of the spawn protection around {@link #spawn}; 0 for none. */
        final int spawnRadius;
        final BlockPos spawn;
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        final List<Bottom> bottoms = new ArrayList<>();
        final CraterRules.Cells cells = this::cell;
        final CraterRules.Tops tops = this::carveTop;
        final BooleanSupplier more = this::more;
        /** Null until the chunks are loaded. */
        CraterRules.Digging digging;
        /** How far up from the swallow point the columns are carved ({@link #carveTop}). */
        int reachUp;
        long digStartTick;
        int carved;
        int sealed;
        int rubble;
        /**
         * Server time the crater took (nanoseconds), in all and in its worst tick (with its plan, its dust and sounds
         * and its finish; the lighting that follows and what follows it are not in it); whether a GC pause fell in that
         * tick, the worst of the ticks without one, and how many ticks took more than the budget.
         */
        long nanos;
        long worstNanos;
        boolean worstCollected;
        long worstCleanNanos;
        int overBudget;
        /**
         * This tick ({@link #more}): at most this many blocks changed; no step ends after this
         * ({@link System#nanoTime}) unless it is the first of the first crater; how many steps were asked for, when the
         * last was, and the slowest.
         */
        int limit;
        long stepDeadline;
        boolean first;
        int asked;
        long askedAt;
        long slowest;
        /** Server time this crater's dust and sounds took in its last tick: kept out of the steps of the next. */
        long showNanos;
        /** Blocks changed in this tick, and some of the carved ones (with what they were) to show. */
        int changes;
        int seen;
        final List<BlockPos> shown = new ArrayList<>();
        final List<BlockState> shownStates = new ArrayList<>();
        /** No longer wanted: given up without telling {@link #done}. */
        boolean calledOff;

        Job(int id, ServerLevel level, BlockPos top, String why, CraterShape shape, BooleanSupplier wanted,
            Consumer<Crater> done) {
            this.id = id;
            this.level = level;
            this.top = top;
            this.why = why;
            this.shape = shape;
            this.wanted = wanted;
            this.done = done;
            this.startTick = level.getServer().getTickCount();
            // The rules look a block beyond a rim column, and fill in there; one more for what they ask about it.
            int reach = shape.reach() + 2;
            area = new RealArea(level, TICKET, id, top.getX() - reach, top.getZ() - reach, top.getX() + reach,
                    top.getZ() + reach);
            // As vanilla has it, in single player too (the radius is 16 there).
            spawnRadius = level.dimension() == Level.OVERWORLD
                    ? Math.max(0, level.getServer().getSpawnProtectionRadius()) : 0;
            spawn = level.getSharedSpawnPos();
        }

        /**
         * This tick's part of the crater, until {@code deadline} ({@link System#nanoTime}; the tick's budget of all
         * craters is {@code budget} nanoseconds) at most: it waits for its chunks, then plans and digs a step at a time
         * ({@link CraterRules.Digging}: a place of its square, a block) while blocks ({@code craterBlocksPerTick}) and
         * time of this tick are left ({@link #more}: the clock read before every step), the time its dust and sounds
         * took in its last tick kept for them. The {@code first} crater of the tick takes a step at least, so that it
         * moves on however small the budget. True once it is over: dug (with time left in this tick to finish it, else
         * in a later one), given up or called off.
         */
        boolean step(long deadline, long budget, long now, boolean first) {
            if (!wanted.getAsBoolean()) {
                calledOff = true;
                return true;
            }
            if (digging == null) {
                if (!area.ready()) {
                    if (now - startTick > LOAD_TIMEOUT_TICKS) {
                        Tremor.LOGGER.warn("Crater #{}: its chunks did not load in {} ticks, given up", id,
                                LOAD_TIMEOUT_TICKS);
                        return true;
                    }
                    return false;
                }
                reachUp = atSurface() ? REACH_UP : shape.depth();
                digging = new CraterRules.Digging(new CraterRules.Plan(shape, top.getX(), top.getY(), top.getZ()));
                digStartTick = now;
                sound(TremorSounds.RUMBLE.get(), centre(), 0.6f);
            }
            limit = TremorConfig.COMMON.awakening.craterBlocksPerTick.get();
            stepDeadline = deadline - Math.min(showNanos, budget / 4);
            this.first = first;
            asked = 0;
            slowest = 0;
            changes = 0;
            seen = 0;
            shown.clear();
            shownStates.clear();
            boolean going = digging.run(cells, tops, this, more);
            long showing = System.nanoTime();
            show(now);
            showNanos = System.nanoTime() - showing;
            return !going && System.nanoTime() < deadline;
        }

        /**
         * Whether this tick's part takes another step (asked before each, so the time between two asks is a step): it
         * has blocks left, and time for one more step as slow as the slowest of this tick so far (a JVM warming up has
         * slow ones), or it is the first step of the first crater of the tick.
         */
        private boolean more() {
            long now = System.nanoTime();
            if (asked > 0) {
                slowest = Math.max(slowest, now - askedAt);
            }
            askedAt = now;
            if (changes >= limit) {
                return false;
            }
            return asked++ == 0 && first || now + slowest < stepDeadline;
        }

        /** A tick of the crater took {@code took} of server time (nanoseconds); {@code collected}: a GC pause in it. */
        void account(long took, long budget, boolean collected) {
            nanos += took;
            if (took > budget) {
                overBudget++;
            }
            if (took > worstNanos) {
                worstNanos = took;
                worstCollected = collected;
            }
            if (!collected) {
                worstCleanNanos = Math.max(worstCleanNanos, took);
            }
        }

        /** The server time of the crater so far, for the log and {@code /tremor info}. */
        private String cost() {
            String text = String.format(Locale.ROOT, "%.1f ms of server time, worst tick %.2f ms", nanos / 1e6,
                    worstNanos / 1e6);
            if (worstCollected) {
                text += String.format(Locale.ROOT, " with a GC pause in it (%.2f ms without one)",
                        worstCleanNanos / 1e6);
            }
            return overBudget == 0 ? text : text + String.format(Locale.ROOT, ", %d %s over the budget", overBudget,
                    overBudget == 1 ? "tick" : "ticks");
        }

        /**
         * The highest block of the column at {@code x, z} that is carved ({@link CraterRules.Tops}). A crater at the
         * surface ({@link #atSurface}) reaches {@value #REACH_UP} blocks up: it takes all that stands over it up to the
         * top of the terrain there (a hill, a tree, a house), so nothing is left hanging over it, and only terrain
         * higher still (a cliff) is cut under. One under a thick roof (deep in a cave or a mine) reaches as far up as
         * it goes down (a thinner roof over it, another cave) and is carved under the rest: the ground there caves in
         * under the roof, never up to the sky. Where the highest block of a column is within the reach, it is the top:
         * the world's height map says so at once; otherwise {@link CraterRules#carveTop} looks for it.
         */
        private int carveTop(int x, int z) {
            int highest = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            return highest < top.getY() ? top.getY() : highest <= top.getY() + reachUp ? highest
                    : CraterRules.carveTop(cells, x, z, top.getY(), reachUp);
        }

        /**
         * Whether the swallow point lies at the surface: over it there are only leaves, or the highest of the rest (a
         * roof, the top of the ground over a shallow cave, the surface of water) is within the crater's depth over it.
         */
        private boolean atSurface() {
            int roof = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, top.getX(), top.getZ()) - 1;
            return roof <= top.getY() + shape.depth();
        }

        /**
         * Over (dug, given up or called off): the ticket goes and the ground cracks. Returns what the crater left (for
         * {@link #follow}), or null if it was called off.
         */
        Crater finish() {
            area.release();
            int blocks = carved + sealed + rubble;
            if (blocks > 0) {
                sound(TremorSounds.CRACK.get(), centre(), 0.8f);
            }
            if (calledOff) {
                return null;
            }
            bottoms.sort(Comparator.comparingInt(b -> (b.x() - top.getX()) * (b.x() - top.getX())
                    + (b.z() - top.getZ()) * (b.z() - top.getZ())));
            return new Crater(id, level, top, shape.depth() * 31L ^ top.asLong() ^ level.getSeed(), bottoms);
        }

        /** Logs what the crater did once it is over, and keeps it for {@code /tremor info} ({@link #LAST}). */
        void report() {
            String text;
            if (calledOff) {
                text = String.format(Locale.ROOT, "Crater #%d at %s called off: %d blocks carved (%s)", id,
                        top.toShortString(), carved, cost());
            } else {
                Bottom bottom = bottoms.isEmpty() ? null : bottoms.get(0);
                text = String.format(Locale.ROOT, "Crater #%d at %s: %d blocks carved, %d filled in, %d of rubble in "
                                + "%d ticks (%s); %d columns on the bottom, %s", id, top.toShortString(), carved,
                        sealed, rubble, level.getServer().getTickCount() - startTick, cost(), bottoms.size(),
                        bottom == null ? "no bottom" : String.format(Locale.ROOT, "bottom %.1f %.1f %.1f",
                                bottom.x() + 0.5, (double) bottom.feet(), bottom.z() + 0.5));
            }
            Tremor.LOGGER.info(text);
            LAST.put(level.dimension(), text);
        }

        /**
         * Tells {@link #done} what the crater left: what follows it (the caches and the player of a defeat...), timed
         * apart from the crater's ticks.
         */
        void follow(Crater crater) {
            long begin = System.nanoTime();
            try {
                done.accept(crater);
            } catch (RuntimeException e) {
                Tremor.LOGGER.error("Crater #{}: what was to follow it failed", id, e);
            }
            Tremor.LOGGER.info(String.format(Locale.ROOT, "Crater #%d: what followed it (%s) took %.2f ms", id, why,
                    (System.nanoTime() - begin) / 1e6));
        }

        String describe() {
            String head = String.format(Locale.ROOT, "Crater #%d (%s) at %s: radius %d, depth %d", id, why,
                    top.toShortString(), shape.radius(), shape.depth());
            if (digging == null) {
                return head + "; waiting for its chunks";
            }
            long ticks = level.getServer().getTickCount() - digStartTick;
            CraterRules.Dig dig = digging.dig();
            if (dig == null) {
                CraterRules.Plan plan = digging.plan();
                return head + String.format(Locale.ROOT, "; planned %d of %d places, %d ticks, %s", plan.looked(),
                        plan.places(), ticks, cost());
            }
            int layers = Math.max(1, dig.highest() - dig.lowest() + 1);
            return head + String.format(Locale.ROOT, "; layer %d (%d to %d, %.0f%% dug), %d blocks carved, %d filled "
                            + "in, %d of rubble, %d ticks, %s", dig.layer(), dig.highest(), dig.lowest(),
                    100.0 * (dig.highest() - dig.layer()) / layers, carved, sealed, rubble, ticks, cost());
        }

        // ---- the works ----

        @Override
        public void carve(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (y > top.getY() && state.is(BlockTags.LOGS)) {
                fell(pos, state);
            } else {
                put(pos, Blocks.AIR.defaultBlockState());
            }
            carved++;
            changes++;
            remember(pos, state);
            for (Direction side : SIDES) {
                BlockPos at = pos.relative(side);
                BlockState beside = level.getBlockState(at);
                if (cell(at.getX(), at.getY(), at.getZ()) == CraterRules.Cell.PLANT && !beside.canSurvive(level, at)) {
                    Block.dropResources(beside, level, at);
                    put(at, Blocks.AIR.defaultBlockState());
                }
            }
        }

        @Override
        public void seal(int x, int y, int z, int fromX, int fromY, int fromZ) {
            BlockPos pos = new BlockPos(x, y, z);
            rescue(pos, new BlockPos(fromX, fromY, fromZ));
            put(pos, ground(pos));
            sealed++;
            changes++;
        }

        /** Puts {@code state} at {@code pos}, its neighbours not updated, the clients told ({@link #told}). */
        private void put(BlockPos pos, BlockState state) {
            if (level.setBlock(pos, state, DIG_FLAGS)) {
                told(pos);
            }
        }

        /**
         * Carves {@code trunk}, the block of a tree's trunk at {@code pos}, as {@code setBlock} with
         * {@link Block#UPDATE_ALL} does, but for the paths of the mobs ({@link #told}): the blocks around it are
         * updated (and the clients told of what that changes), so its leaves wither as after a felling.
         */
        private void fell(BlockPos pos, BlockState trunk) {
            BlockState air = Blocks.AIR.defaultBlockState();
            if (!level.setBlock(pos, air, Block.UPDATE_NEIGHBORS | Block.UPDATE_KNOWN_SHAPE)) {
                return;
            }
            told(pos);
            trunk.updateIndirectNeighbourShapes(level, pos, Block.UPDATE_CLIENTS, SHAPE_RECURSION);
            air.updateNeighbourShapes(level, pos, Block.UPDATE_CLIENTS, SHAPE_RECURSION);
            air.updateIndirectNeighbourShapes(level, pos, Block.UPDATE_CLIENTS, SHAPE_RECURSION);
        }

        /**
         * The block at {@code pos} was changed without {@link Block#UPDATE_CLIENTS}: the clients are told, and the path
         * finding forgets what it knew of the block, as {@code setBlock} with that flag does; but no mob plans its path
         * again on the spot, which vanilla does for each mob whose path ends near the block (for a villager in the
         * crater, 4 ms for one block): the mobs find out as they go, or fall in.
         */
        private void told(BlockPos pos) {
            level.getChunkSource().blockChanged(pos);
            level.getPathTypeCache().invalidate(pos);
        }

        /**
         * Gets the creatures out of the block at {@code pos} before it is filled in, or they would suffocate in it (a
         * pet sitting in a cave beside the crater, a villager in its cell, a fish in a pond); players are pushed out
         * of a block on their own. A creature goes into a block beside it of the same kind (water for one in water,
         * open air for one in a cavity) where it fits, the one on the far side from the crater first, else into the
         * block of the crater the filling closes off ({@code crater}: air by now).
         */
        private void rescue(BlockPos pos, BlockPos crater) {
            AABB filled = new AABB(pos);
            List<LivingEntity> inside = level.getEntitiesOfClass(LivingEntity.class, filled,
                    entity -> entity.isAlive() && !(entity instanceof Player));
            if (inside.isEmpty()) {
                return;
            }
            boolean fluid = !level.getFluidState(pos).isEmpty();
            BlockPos far = pos.offset(pos.getX() - crater.getX(), pos.getY() - crater.getY(), pos.getZ() - crater.getZ());
            List<BlockPos> ways = new ArrayList<>(List.of(far));
            for (Direction side : Direction.values()) {
                BlockPos beside = pos.relative(side);
                if (!beside.equals(far) && !beside.equals(crater)) {
                    ways.add(beside);
                }
            }
            for (LivingEntity entity : inside) {
                BlockPos to = crater;
                for (BlockPos way : ways) {
                    // Fits there, and not partly in the block filled in (a tall creature in a cavity two blocks high).
                    AABB there = entity.getBoundingBox().move(way.getX() + 0.5 - entity.getX(),
                            way.getY() - entity.getY(), way.getZ() + 0.5 - entity.getZ());
                    if (level.getFluidState(way).isEmpty() != fluid && !there.intersects(filled)
                            && level.noCollision(entity, there)) {
                        to = way;
                        break;
                    }
                }
                entity.stopRiding();
                entity.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
                Tremor.LOGGER.info("Crater #{}: {} moved out of {} to {}", id, entity, pos.toShortString(),
                        to.toShortString());
            }
        }

        @Override
        public void bottomed(CraterRules.Column column) {
            int layers = shape.rubbleAt(column.x - top.getX(), column.z - top.getZ());
            int placed = 0;
            for (; placed < layers; placed++) {
                BlockPos pos = new BlockPos(column.x, column.bottom + placed, column.z);
                if (!level.getBlockState(pos).isAir()) {
                    break;
                }
                put(pos, rubble(pos.getX(), pos.getY(), pos.getZ(), top.asLong()));
                rubble++;
                changes++;
            }
            bottoms.add(new Bottom(column.x, column.z, column.bottom - 1, placed));
        }

        /**
         * What fills in a block next to the crater: the ground around it (the first of the blocks beside it and under it
         * that is natural ground: stone, deepslate, dirt, sandstone for sand...), else stone (deepslate below 0).
         */
        private BlockState ground(BlockPos pos) {
            for (Direction side : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST,
                    Direction.EAST}) {
                BlockState near = level.getBlockState(pos.relative(side));
                if (near.is(BlockTags.BASE_STONE_OVERWORLD) || near.is(BlockTags.BASE_STONE_NETHER)
                        || near.is(BlockTags.TERRACOTTA) || near.is(Blocks.END_STONE) || near.is(Blocks.SANDSTONE)
                        || near.is(Blocks.RED_SANDSTONE)) {
                    return near.getBlock().defaultBlockState();
                }
                if (near.is(BlockTags.DIRT)) {
                    return Blocks.DIRT.defaultBlockState();
                }
                if (near.is(Blocks.RED_SAND)) {
                    return Blocks.RED_SANDSTONE.defaultBlockState();
                }
                if (near.is(BlockTags.SAND)) {
                    return Blocks.SANDSTONE.defaultBlockState();
                }
            }
            return (pos.getY() < 0 ? Blocks.DEEPSLATE : Blocks.STONE).defaultBlockState();
        }

        /** Keeps a few of this tick's carved blocks, picked evenly from all of them, to show crumbling. */
        private void remember(BlockPos pos, BlockState state) {
            int keep = Math.max(CRUMBLES_PER_TICK, DUST_PER_TICK);
            seen++;
            if (shown.size() < keep) {
                shown.add(pos);
                shownStates.add(state);
            } else {
                int slot = level.random.nextInt(seen);
                if (slot < keep) {
                    shown.set(slot, pos);
                    shownStates.set(slot, state);
                }
            }
        }

        /** This tick's crumbling, dust, cracks and rumble. */
        private void show(long now) {
            for (int i = 0; i < shown.size(); i++) {
                BlockPos pos = shown.get(i);
                BlockState state = shownStates.get(i);
                if (i < CRUMBLES_PER_TICK) {
                    level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(state));
                }
                if (i < DUST_PER_TICK) {
                    level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, state), pos.getX() + 0.5,
                            pos.getY() + 1.0, pos.getZ() + 0.5, 6, 0.6, 0.3, 0.6, 0);
                }
            }
            long ticks = now - digStartTick;
            if (ticks > 0 && ticks % CRACK_TICKS == 0 && !shown.isEmpty()) {
                BlockPos pos = shown.get(0);
                sound(TremorSounds.CRACK.get(), new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5),
                        0.7f + 0.3f * level.random.nextFloat());
            }
            if (ticks > 0 && ticks % RUMBLE_TICKS == 0) {
                sound(TremorSounds.RUMBLE.get(), centre(), 0.6f);
            }
        }

        private Vec3 centre() {
            return new Vec3(top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
        }

        private void sound(net.minecraft.sounds.SoundEvent sound, Vec3 at, float pitch) {
            level.playSound(null, at.x, at.y, at.z, sound, SoundSource.HOSTILE,
                    (float) TremorConfig.COMMON.transitionVolume.getAsDouble(), pitch);
        }

        CraterRules.Cell cell(int x, int y, int z) {
            cursor.set(x, y, z);
            if (level.isOutsideBuildHeight(y) || !level.getWorldBorder().isWithinBounds(cursor)
                    || spawnProtected(x, z)) {
                return CraterRules.Cell.KEEP;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) {
                return CraterRules.Cell.KEEP;
            }
            BlockState state = chunk.getBlockState(cursor);
            if (state.hasBlockEntity() || state.is(TremorTags.PROTECTED) || state.getDestroySpeed(level, cursor) < 0
                    || state.is(Blocks.MAGMA_BLOCK)) {
                return CraterRules.Cell.KEEP;
            }
            if (!state.getFluidState().isEmpty()) {
                return CraterRules.Cell.FLUID;
            }
            if (state.isAir()) {
                return CraterRules.Cell.AIR;
            }
            if (state.getCollisionShape(level, cursor).isEmpty()) {
                return CraterRules.Cell.PLANT;
            }
            if (!state.isCollisionShapeFullBlock(level, cursor)) {
                return CraterRules.Cell.LOOSE;
            }
            return state.getBlock() instanceof Fallable ? CraterRules.Cell.FALLING : CraterRules.Cell.SOLID;
        }

        /** Whether the column lies in the spawn protection (a square around the world spawn, as vanilla has it). */
        private boolean spawnProtected(int x, int z) {
            return spawnRadius > 0 && Math.max(Math.abs(x - spawn.getX()), Math.abs(z - spawn.getZ())) <= spawnRadius;
        }
    }
}
