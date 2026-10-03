package tremor.awakening;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
 * nothing is left hanging over it ({@link Job#plan}). A column carved through gets its rubble
 * ({@link CraterShape#rubbleAt}: gravel, some tuff and cobbled deepslate); what is filled in to close the crater (a
 * cavity beside it, the blocks of a fluid that touch it) is the ground around it (stone or deepslate if there is none),
 * and a creature in such a block is moved out first ({@link Job#rescue}). Event handlers are registered by
 * {@link tremor.Tremor}; server thread only.
 * <p>
 * Each crater holds a ticket on the chunks it touches (and the blocks around it) until it is done ({@link RealArea}),
 * and waits for them to load; then it changes at most {@code awakening.craterBlocksPerTick} blocks per tick, and all
 * craters together take at most {@code awakening.craterBudgetMillis} of server time per tick (SPEC 16). A crater that
 * is no longer wanted (its defeat was called off) is not dug, or no further.
 * <p>
 * What is kept ({@link CraterRules.Cell#KEEP}): blocks with a block entity (the caches of an earlier crater too), of
 * {@code #tremor:protected}, that cannot be broken (a destroy speed below 0) or that burn (magma), and every block of
 * the spawn protection (the overworld around the world spawn, a square of the server's spawn protection radius:
 * {@code spawn-protection} of server.properties, 16 blocks in single player and on LAN) whoever the player is. Carved
 * blocks drop nothing, and their neighbours are not updated: nothing next to the crater pops off, falls or flows because
 * of it; only a plant next to a carved block that cannot stay without it drops as usual, and the leaves of a tree whose
 * trunk was carved wither as after a felling. Entities standing over the crater fall in. Not saved: a server that stops
 * in the middle leaves the crater as far as it got, closed like a finished one ({@link CraterRules}).
 */
public final class Craters {
    /** Keeps the chunks of a crater loaded while it is dug; one ticket per crater (its id). */
    private static final TicketType<Integer> TICKET = TicketType.create(Tremor.MODID + "_crater", Integer::compare);
    /** A crater whose chunks are not loaded after this many ticks is given up (none is dug). */
    private static final int LOAD_TIMEOUT_TICKS = 200;
    /** Carves a block without updating its neighbours (the clients still get it). */
    private static final int DIG_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
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
    /** Id of the next crater; ids are never reused while the server runs. */
    private static int nextId = 1;

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

    /** The craters being dug in {@code level}, a line each (for {@code /tremor info}); null if there are none. */
    public static String describe(ServerLevel level) {
        StringBuilder text = new StringBuilder();
        for (Job job : JOBS) {
            if (job.level == level) {
                text.append(text.isEmpty() ? "" : "\n").append(job.describe());
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (JOBS.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        long budget = (long) (TremorConfig.COMMON.awakening.craterBudgetMillis.get() * 1e6);
        long now = event.getServer().getTickCount();
        for (Job job : List.copyOf(JOBS)) {
            boolean over;
            try {
                over = job.step(start, budget, now);
            } catch (RuntimeException e) {
                Tremor.LOGGER.error("Crater #{} failed; it stays as far as it got", job.id, e);
                over = true;
            }
            if (over) {
                JOBS.remove(job);
                try {
                    job.finish();
                } catch (RuntimeException e) {
                    Tremor.LOGGER.error("Crater #{}: what was to follow it failed", job.id, e);
                }
            }
        }
    }

    /** The levels and their tickets are gone; a crater still being dug stays as far as it got. */
    public static void onServerStopped(ServerStoppedEvent event) {
        JOBS.clear();
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
        /** Null until the chunks are loaded. */
        CraterRules.Dig dig;
        long digStartTick;
        int carved;
        int sealed;
        int rubble;
        /** Server time the dig took (nanoseconds), in all and in its worst tick; the lighting that follows is not in it. */
        long nanos;
        long worstNanos;
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
         * Digs within the budget of this tick (all craters began at {@code start}, {@code budget} nanoseconds); true once
         * it is done, given up or called off.
         */
        boolean step(long start, long budget, long now) {
            if (!wanted.getAsBoolean()) {
                calledOff = true;
                return true;
            }
            if (dig == null) {
                if (!area.ready()) {
                    if (now - startTick > LOAD_TIMEOUT_TICKS) {
                        Tremor.LOGGER.warn("Crater #{}: its chunks did not load in {} ticks, given up", id,
                                LOAD_TIMEOUT_TICKS);
                        return true;
                    }
                    return false;
                }
                dig = plan();
                digStartTick = now;
                sound(TremorSounds.RUMBLE.get(), centre(), 0.6f);
            }
            long begin = System.nanoTime();
            int limit = TremorConfig.COMMON.awakening.craterBlocksPerTick.get();
            changes = 0;
            seen = 0;
            shown.clear();
            shownStates.clear();
            boolean over = false;
            do {
                if (!dig.step(this::cell, this)) {
                    over = true;
                    break;
                }
            } while (changes < limit && System.nanoTime() - start < budget);
            show(now);
            long took = System.nanoTime() - begin;
            nanos += took;
            worstNanos = Math.max(worstNanos, took);
            return over;
        }

        /** The server time of the dig so far, for the log and {@code /tremor info}. */
        private String cost() {
            return String.format(Locale.ROOT, "%.1f ms of server time, worst tick %.2f ms", nanos / 1e6,
                    worstNanos / 1e6);
        }

        /**
         * The columns of the shape at the swallow point, each carved from its top ({@link CraterRules#carveTop}). A
         * crater at the surface ({@link #atSurface}) reaches {@value #REACH_UP} blocks up: it takes all that stands over
         * it up to the top of the terrain there (a hill, a tree, a house), so nothing is left hanging over it, and only
         * terrain higher still (a cliff) is cut under. One under a thick roof (deep in a cave or a mine) reaches as far
         * up as it goes down (a thinner roof over it, another cave) and is carved under the rest: the ground there
         * caves in under the roof, never up to the sky. Where the highest block of a column is within the reach, it is
         * the top: the world's height map says so at once.
         */
        private CraterRules.Dig plan() {
            List<CraterRules.Column> columns = new ArrayList<>();
            int reachUp = atSurface() ? REACH_UP : shape.depth();
            for (CraterShape.Column column : shape.columns()) {
                int x = top.getX() + column.dx();
                int z = top.getZ() + column.dz();
                int highest = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                int carveTop = highest < top.getY() ? top.getY() : highest <= top.getY() + reachUp ? highest
                        : CraterRules.carveTop(this::cell, x, z, top.getY(), reachUp);
                columns.add(new CraterRules.Column(x, z, carveTop, top.getY() - column.depth()));
            }
            return new CraterRules.Dig(top.getY(), columns);
        }

        /**
         * Whether the swallow point lies at the surface: over it there are only leaves, or the highest of the rest (a
         * roof, the top of the ground over a shallow cave, the surface of water) is within the crater's depth over it.
         */
        private boolean atSurface() {
            int roof = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, top.getX(), top.getZ()) - 1;
            return roof <= top.getY() + shape.depth();
        }

        /** Done (or given up): the ticket goes, the ground cracks, and {@code done} is told what is left. */
        void finish() {
            area.release();
            int blocks = carved + sealed + rubble;
            if (blocks > 0) {
                sound(TremorSounds.CRACK.get(), centre(), 0.8f);
            }
            if (calledOff) {
                Tremor.LOGGER.info("Crater #{} at {} called off: {} blocks carved", id, top.toShortString(), carved);
                return;
            }
            bottoms.sort(Comparator.comparingInt(b -> (b.x() - top.getX()) * (b.x() - top.getX())
                    + (b.z() - top.getZ()) * (b.z() - top.getZ())));
            Crater crater = new Crater(id, level, top, shape.depth() * 31L ^ top.asLong() ^ level.getSeed(), bottoms);
            Vec3 bottom = crater.bottom();
            Tremor.LOGGER.info(String.format(Locale.ROOT, "Crater #%d at %s: %d blocks carved, %d filled in, %d of "
                            + "rubble in %d ticks (%s); %d columns on the bottom, %s", id, top.toShortString(),
                    carved, sealed, rubble, level.getServer().getTickCount() - startTick, cost(), bottoms.size(),
                    bottom == null ? "no bottom" : String.format(Locale.ROOT, "bottom %.1f %.1f %.1f", bottom.x,
                            bottom.y, bottom.z)));
            done.accept(crater);
        }

        String describe() {
            String head = String.format(Locale.ROOT, "Crater #%d (%s) at %s: radius %d, depth %d", id, why,
                    top.toShortString(), shape.radius(), shape.depth());
            if (dig == null) {
                return head + "; waiting for its chunks";
            }
            int layers = Math.max(1, dig.highest() - dig.lowest() + 1);
            return head + String.format(Locale.ROOT, "; layer %d (%d to %d, %.0f%% dug), %d blocks carved, %d filled "
                            + "in, %d of rubble, %d ticks, %s", dig.layer(), dig.highest(), dig.lowest(),
                    100.0 * (dig.highest() - dig.layer()) / layers, carved, sealed, rubble,
                    level.getServer().getTickCount() - digStartTick, cost());
        }

        // ---- the works ----

        @Override
        public void carve(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            // The trunk of a tree: its leaves around are told, so they wither as after a felling.
            int flags = y > top.getY() && state.is(BlockTags.LOGS) ? Block.UPDATE_ALL : DIG_FLAGS;
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
            carved++;
            changes++;
            remember(pos, state);
            for (Direction side : SIDES) {
                BlockPos at = pos.relative(side);
                BlockState beside = level.getBlockState(at);
                if (cell(at.getX(), at.getY(), at.getZ()) == CraterRules.Cell.PLANT && !beside.canSurvive(level, at)) {
                    Block.dropResources(beside, level, at);
                    level.setBlock(at, Blocks.AIR.defaultBlockState(), DIG_FLAGS);
                }
            }
        }

        @Override
        public void seal(int x, int y, int z, int fromX, int fromY, int fromZ) {
            BlockPos pos = new BlockPos(x, y, z);
            rescue(pos, new BlockPos(fromX, fromY, fromZ));
            level.setBlock(pos, ground(pos), DIG_FLAGS);
            sealed++;
            changes++;
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
                level.setBlock(pos, rubble(pos.getX(), pos.getY(), pos.getZ(), top.asLong()), DIG_FLAGS);
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
