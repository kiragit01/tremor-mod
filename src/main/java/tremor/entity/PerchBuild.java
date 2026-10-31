package tremor.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import tremor.world.TremorTags;

/**
 * The ground raised around a pillar the entity cannot climb (the user's design: after stalactites that missed, or
 * without a ceiling, it builds the pillar up out of the ground around): every open cell of the 3x3 around the column
 * the player was heard on, from the foot up to the block under the feet, is filled with a block of the ground around,
 * so the pillar becomes wide enough to climb ({@code movement.minGroundWidth} 3); a trunk around it leans a block aside
 * now and then from layer to layer, and around it a rough heap rises out to {@value #HEAP_RING} blocks, its columns of
 * random heights, lower the farther out, now and then one nearly as high as the core (the user: a neat 3x3 looked
 * static, and the first heap still too even). Each block sinks into the ground where it is taken (a block display going
 * down, the block gone) and rises out of the block below where it goes (a display coming up, then the block): any open
 * cell with ground under it, at random among the lowest {@value #RAGGED} + 1 layers of those (a cell of the core first,
 * mostly), one every {@value #MIN_INTERVAL} to {@value #MAX_INTERVAL} ticks. The player building higher raises it
 * ({@link #raise}). Going aside along a bridge out of the top, the player is followed on their heels ({@link #walkTo},
 * the user's idea): once the core is up, the walkway along their trail is filled where broken and widened a block each
 * side, from the heap on (a block slides out of the one before it), so the entity crawls after them; heard too far from
 * where they were before, it builds anew under them ({@link #retarget}), what is built stays. Idle with nothing left,
 * it waits for more until it is {@link #finish}ed. Taken from the top of the ground {@value #SOURCE_MIN} to
 * {@value #SOURCE_MAX} blocks around the column, scattered over that disc (at random, the nearer the likelier: the user
 * saw a square pit), the next layer once the top one is taken; its top found from {@value #SOURCE_HEIGHT} over the foot
 * down (a build on a heap built before takes from the ground far below): a whole block of the skin with ground under
 * it, never one a crater keeps (a block entity, {@code #tremor:protected}, unbreakable, magma, a fluid, the spawn
 * protection, outside the world border); a plant on it goes. The block keeps its state: an ore goes into the pillar,
 * nothing is lost. The displays carry the tag of devoured mobs: left by a server stopping mid-way, they do not come
 * back.
 */
final class PerchBuild {
    /** Ticks between two blocks set off, at random. */
    static final int MIN_INTERVAL = 1;
    static final int MAX_INTERVAL = 4;
    /** The next cell is any of those this many layers over the lowest one ready... */
    static final int RAGGED = 2;
    /** ...a cell of the core at this chance, if one is ready. */
    static final double CORE_FIRST = 0.6;
    /** The heap around the core reaches this far out (Chebyshev, blocks), its columns lower the farther out... */
    static final int HEAP_RING = 3;
    /** ...but at this chance one next to the trunk rises nearly as high as the core. */
    static final double SPIKE_CHANCE = 0.15;
    /** The trunk leans a block aside from one layer to the next at this chance (a block at most from the core). */
    static final double LEAN_CHANCE = 0.45;
    /** ...and at most this far from the core. */
    static final int LEAN_MAX = 2;
    /** The heap around the trunk is at most this high, however tall the pillar (the trunk goes all the way up). */
    static final int HEAP_MAX = 10;
    /** One more block is set off at a time for every this many cells of the plan (a tall pillar is built faster). */
    static final int CELLS_PER_BLOCK = 100;
    /**
     * Under each cell of the walkway hangs a block at this chance, one stands on it at the next (the user: harder to
     * walk on); under and over each cell beside it at the last two.
     */
    static final double HANG_CHANCE = 0.4;
    static final double OVER_CHANCE = 0.3;
    static final double SIDE_UNDER_CHANCE = 0.3;
    static final double SIDE_OVER_CHANCE = 0.3;
    /** A column of the plan goes down this far under the foot where the ground dips. */
    static final int DIP = 3;
    /** Heard farther than this (blocks, across) from where they were heard before, the player is built for anew. */
    static final int MAX_STRIDE = 16;
    /** The ground is taken at random, the nearer the likelier: the share into the nearest-first list, to this power. */
    static final double SOURCE_SKEW = 1.6;
    /** Ticks a block sinks where it is taken... */
    static final int SINK_TICKS = 8;
    /** ...and rises where it goes. */
    static final int RISE_TICKS = 8;
    /** Blocks are taken this far around the column (blocks, across): a free ring around the heap stays. */
    static final double SOURCE_MIN = HEAP_RING + 1.5;
    static final double SOURCE_MAX = 14;
    /** The ground is taken from its top at most this far over the foot, down to {@value #SOURCE_DEPTH} under it. */
    static final int SOURCE_HEIGHT = 3;
    static final int SOURCE_DEPTH = 48;
    /** How far a display goes down: into the block below, not flush with its top (no flicker of two faces). */
    private static final float DOWN = -0.98f;

    private final ServerLevel level;
    private int x, z, footY, topY;
    /** The heap's height, from the first top: it does not grow as the player builds higher. */
    private int heapHeight;
    /** The plan's seed, of the column: the same plan again after a raise, only taller. */
    private long seed;
    private final List<Move> moves = new ArrayList<>();
    /** Cells a block is on its way to. */
    private final Set<BlockPos> pending = new HashSet<>();
    /** Every cell to fill, the core's and the heap's; null until the first is chosen. */
    private List<BlockPos> plan;
    private final Set<BlockPos> core = new HashSet<>();
    private final Random random;
    private List<BlockPos> sources;
    /** Where the player was heard in order since they went aside ({@link #walkTo}). */
    private final List<BlockPos> trail = new ArrayList<>();
    /** The cells of the walkway along the trail from the core's top, and those beside it, in its order. */
    private List<BlockPos> walk = List.of();
    /** Each cell of the walkway or beside it, and the cell it slides out of. */
    private final Map<BlockPos, BlockPos> sides = new HashMap<>();
    private long nextAt;
    /** No more blocks set off: filled up, nothing left to take, or {@link #finish}ed ({@link #stopped}). */
    private boolean done;
    private boolean stopped;
    private int placed;

    private static final class Move {
        final BlockState state;
        final BlockPos from;
        final BlockPos to;
        /** The block it comes out of where it goes: the one under it, or the walkway's beside it. */
        final BlockPos support;
        int ticks;
        boolean landed;
        Display.BlockDisplay shown;

        Move(BlockState state, BlockPos from, BlockPos to, BlockPos support) {
            this.state = state;
            this.from = from;
            this.to = to;
            this.support = support;
        }
    }

    /** Around column {@code x, z}, from the layer over {@code footY} (the ground under the player) to {@code topY}. */
    PerchBuild(ServerLevel level, int x, int z, int footY, int topY) {
        this.level = level;
        this.x = x;
        this.z = z;
        this.footY = footY;
        this.topY = topY;
        this.heapHeight = Math.min(topY - footY, HEAP_MAX);
        this.seed = BlockPos.asLong(x, footY, z);
        this.random = new Random(seed);
    }

    int placed() {
        return placed;
    }

    /** Whether there is nothing to build now (all of it in place, or nothing left to take) until more comes. */
    boolean idle() {
        return done && moves.isEmpty();
    }

    /** Whether every cell of the core up to the top is filled: the pillar is wide enough to climb. */
    boolean coreFilled() {
        return plan != null && !core.isEmpty() && core.stream().noneMatch(p -> open(level.getBlockState(p)));
    }

    /**
     * The player went aside to {@code target} (the block now under their feet): it joins their trail, the places they
     * were heard at in order from the core's top, and the walkway runs along it, straight from one place to the next
     * (a step up or down spread over the way). Once the core is up, every open cell of the walkway (a gap: the player
     * broke the bridge behind them, the user's case, or walked where it runs beside) is filled in its order, coming out
     * of the cell before it, and every open cell beside it, a block each side, coming out of it. False, changing
     * nothing, if the player got farther than {@value #MAX_STRIDE} blocks from where they were heard before (or higher
     * than they could walk).
     */
    boolean walkTo(BlockPos target) {
        BlockPos start = new BlockPos(x, topY, z);
        BlockPos last = trail.isEmpty() ? start : trail.get(trail.size() - 1);
        int across = Math.max(Math.abs(target.getX() - last.getX()), Math.abs(target.getZ() - last.getZ()));
        if (across > MAX_STRIDE || Math.abs(target.getY() - last.getY()) > across + 1) {
            return false;
        }
        trail.add(target);
        List<BlockPos> way = new ArrayList<>();
        BlockPos from = start;
        for (BlockPos to : trail) {
            line(from, to, way);
            from = to;
        }
        Set<BlockPos> on = new HashSet<>(way);
        on.add(start);
        List<BlockPos> order = new ArrayList<>();
        BlockPos before = start;
        for (BlockPos w : way) {
            sides.putIfAbsent(w, before);
            order.add(w);
            hang(w, w.below(), HANG_CHANCE, on, order);
            hang(w, w.above(), OVER_CHANCE, on, order);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos n = w.relative(d);
                if (!on.contains(n)) {
                    sides.putIfAbsent(n, w);
                    if (w.equals(sides.get(n))) {
                        order.add(n);
                        hang(n, n.below(), SIDE_UNDER_CHANCE, on, order);
                        hang(n, n.above(), SIDE_OVER_CHANCE, on, order);
                    }
                }
            }
            before = w;
        }
        walk = order;
        reopen();
        return true;
    }

    /**
     * A block at {@code cell}, coming out of {@code from}, at {@code chance} (the same for the same cell every time the
     * walkway is laid again): the walkway raggeder, hanging under it and standing on it and its sides (never where
     * somebody stands: {@link #fillable}).
     */
    private void hang(BlockPos from, BlockPos cell, double chance, Set<BlockPos> on, List<BlockPos> order) {
        if (on.contains(cell) || new Random(cell.asLong() ^ seed).nextDouble() >= chance) {
            return;
        }
        sides.putIfAbsent(cell, from);
        if (from.equals(sides.get(cell))) {
            order.add(cell);
        }
    }

    /** The cells from {@code a} (not included) straight to {@code b}, a step across at a time, the height spread. */
    private static void line(BlockPos a, BlockPos b, List<BlockPos> out) {
        int dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ(), steps = Math.abs(dx) + Math.abs(dz);
        int cx = a.getX(), cz = a.getZ();
        for (int k = 1; k <= steps; k++) {
            // Across along whichever axis is further behind its share of the way.
            boolean xBehind = (long) Math.abs(cx - a.getX()) * Math.abs(dz)
                    <= (long) Math.abs(cz - a.getZ()) * Math.abs(dx);
            if (cx != b.getX() && (xBehind || cz == b.getZ())) {
                cx += Integer.signum(dx);
            } else {
                cz += Integer.signum(dz);
            }
            int y = a.getY() + (int) Math.round((b.getY() - a.getY()) * (double) k / steps);
            BlockPos p = new BlockPos(cx, y, cz);
            if (!out.contains(p)) {
                out.add(p);
            }
        }
    }

    /**
     * The player built the pillar higher: the core goes up to {@code top} (the block now under the feet), and a build
     * that had filled up goes on.
     */
    void raise(int top) {
        if (top <= topY) {
            return;
        }
        topY = top;
        plan = null;
        reopen();
    }

    /**
     * The player went aside (a bridge out of the pillar): the build goes under them, around column {@code x, z} from
     * {@code foot} (the ground there) to {@code top}; what is built stays, the blocks under way go on to their place.
     */
    void retarget(int x, int z, int foot, int top) {
        if (x == this.x && z == this.z) {
            raise(top);
            return;
        }
        this.x = x;
        this.z = z;
        footY = foot;
        topY = top;
        heapHeight = Math.min(top - foot, HEAP_MAX);
        seed = BlockPos.asLong(x, foot, z);
        plan = null;
        sources = null;
        walk = List.of();
        trail.clear();
        reopen();
    }

    private void reopen() {
        if (!stopped) {
            done = false;
        }
    }

    /** No more blocks set off; those under way go on to their place ({@link #tick} until it is over). */
    void finish() {
        stopped = done = true;
    }

    /**
     * True once it is over: {@link #finish}ed and every block under way in place. With nothing left to fill (or to
     * take) it is idle: more may come ({@link #raise}, {@link #walkTo}, {@link #retarget}).
     */
    boolean tick(long now) {
        for (Iterator<Move> it = moves.iterator(); it.hasNext(); ) {
            if (step(it.next())) {
                it.remove();
            }
        }
        if (!done && now >= nextAt) {
            nextAt = now + MIN_INTERVAL + random.nextInt(MAX_INTERVAL - MIN_INTERVAL + 1);
            for (int k = plan == null ? 1 : 1 + plan.size() / CELLS_PER_BLOCK; k > 0 && !done; k--) {
                BlockPos to = nextCell();
                if (to == null) {
                    done = true;
                } else if (to == WAIT) {
                    break;
                } else {
                    BlockPos from = nextSource();
                    if (from == null) {
                        done = true;
                    } else {
                        start(from, to);
                    }
                }
            }
        }
        return stopped && moves.isEmpty();
    }

    /** Ends at once: every block under way is put in place (where it goes, or back where it was taken). */
    void discard() {
        for (Move m : moves) {
            if (m.shown != null) {
                m.shown.discard();
            }
            if (!m.landed) {
                if (fillable(m.to)) {
                    level.setBlock(m.to, m.state, Block.UPDATE_ALL);
                } else if (level.getBlockState(m.from).isAir()) {
                    level.setBlock(m.from, m.state, Block.UPDATE_ALL);
                }
            }
        }
        moves.clear();
        pending.clear();
        stopped = done = true;
    }

    // ---- where to ----

    private static final BlockPos WAIT = new BlockPos(0, Integer.MIN_VALUE, 0);

    /**
     * The next cell to fill: once the core is up, the first one of the walkway or beside it that is open, with the cell
     * it comes out of in place; otherwise one of the plan: open, with ground under it (no block on its way there), at
     * random among the lowest {@value #RAGGED} + 1 layers of those, one of the core at {@value #CORE_FIRST}.
     * {@link #WAIT} while none is ready but blocks are on their way, null once nothing is left.
     */
    private BlockPos nextCell() {
        if (plan == null) {
            plan();
        }
        if (!walk.isEmpty() && coreFilled()) {
            for (BlockPos p : walk) {
                BlockPos from = sides.get(p);
                if (!pending.contains(p) && !pending.contains(from) && !open(level.getBlockState(from))
                        && fillable(p)) {
                    return p;
                }
            }
        }
        List<BlockPos> ready = new ArrayList<>();
        int lowest = Integer.MAX_VALUE;
        for (BlockPos p : plan) {
            if (!pending.contains(p) && !pending.contains(p.below()) && !open(level.getBlockState(p.below()))
                    && fillable(p)) {
                ready.add(p);
                lowest = Math.min(lowest, p.getY());
            }
        }
        if (ready.isEmpty()) {
            return pending.isEmpty() ? null : WAIT;
        }
        int reach = lowest + RAGGED;
        ready.removeIf(p -> p.getY() > reach);
        if (random.nextDouble() < CORE_FIRST) {
            List<BlockPos> cores = ready.stream().filter(core::contains).toList();
            if (!cores.isEmpty()) {
                return cores.get(random.nextInt(cores.size()));
            }
        }
        return ready.get(random.nextInt(ready.size()));
    }

    /**
     * The cells to fill, from the column's seed: the heap (columns of random heights out to {@value #HEAP_RING},
     * lower the farther out, at {@value #SPIKE_CHANCE} one next to the trunk nearly as high as the core), the trunk
     * (a 3x3 a layer, a block aside from the one below at {@value #LEAN_CHANCE}, at most {@value #LEAN_MAX} from the
     * core), and
     * the core, 3x3 right under the player up to the top. Every column from the ground up: where the ground dips
     * under the foot, from as far as {@value #DIP} down.
     */
    private void plan() {
        Random r = new Random(seed);
        int height = topY - footY;
        Map<Long, Integer> tops = new HashMap<>();
        for (int dz = -HEAP_RING; dz <= HEAP_RING; dz++) {
            for (int dx = -HEAP_RING; dx <= HEAP_RING; dx++) {
                double spike = r.nextDouble(), share = r.nextDouble();
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                if (ring > 1) {
                    double most = heapHeight * (1 - (ring - 1) / (double) HEAP_RING);
                    int h = ring == 2 && spike < SPIKE_CHANCE ? heapHeight - 1 - (int) (share * 2)
                            : (int) Math.floor(share * (most + 1));
                    tops.merge(column(x + dx, z + dz), Math.min(h, height), Math::max);
                }
            }
        }
        int ox = 0, oz = 0;
        for (int h = 1; h <= height; h++) {
            double lean = r.nextDouble();
            int way = r.nextInt(4);
            if (lean < LEAN_CHANCE) {
                ox = Math.max(-LEAN_MAX, Math.min(LEAN_MAX, ox + (way == 0 ? 1 : way == 1 ? -1 : 0)));
                oz = Math.max(-LEAN_MAX, Math.min(LEAN_MAX, oz + (way == 2 ? 1 : way == 3 ? -1 : 0)));
            }
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    tops.merge(column(x + ox + dx, z + oz + dz), h, Math::max);
                    tops.merge(column(x + dx, z + dz), height, Math::max);
                }
            }
        }
        plan = new ArrayList<>();
        core.clear();
        for (Map.Entry<Long, Integer> e : tops.entrySet()) {
            int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
            boolean inCore = Math.abs(cx - x) <= 1 && Math.abs(cz - z) <= 1;
            int base = footY + 1;
            while (base > footY + 1 - DIP && open(level.getBlockState(new BlockPos(cx, base - 1, cz)))) {
                base--;
            }
            for (int y = base; y <= footY + e.getValue(); y++) {
                BlockPos p = new BlockPos(cx, y, cz);
                plan.add(p);
                if (inCore) {
                    core.add(p);
                }
            }
        }
    }

    private static long column(int x, int z) {
        return (long) x << 32 | z & 0xFFFFFFFFL;
    }

    private boolean fillable(BlockPos p) {
        BlockState state = level.getBlockState(p);
        return (state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty())
                && level.getEntitiesOfClass(LivingEntity.class, new AABB(p), e -> !e.isSpectator()).isEmpty();
    }

    // ---- where from ----

    /** The next block to take, or null if none is left (the next layer is looked for once the top one is gone). */
    private BlockPos nextSource() {
        for (int pass = 0; pass < 2; pass++) {
            if (sources == null || sources.isEmpty()) {
                sources = findSources();
            }
            while (!sources.isEmpty()) {
                BlockPos p = sources.remove((int) (sources.size() * Math.pow(random.nextDouble(), SOURCE_SKEW)));
                if (takeable(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    /** The top of the ground in every column of the disc that may be taken, the nearest first. */
    private List<BlockPos> findSources() {
        List<BlockPos> found = new ArrayList<>();
        int r = (int) Math.ceil(SOURCE_MAX);
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                double d = Math.hypot(dx, dz);
                if (d < SOURCE_MIN || d > SOURCE_MAX) {
                    continue;
                }
                for (int y = footY + SOURCE_HEIGHT; y >= footY - SOURCE_DEPTH; y--) {
                    BlockPos p = new BlockPos(x + dx, y, z + dz);
                    if (!open(level.getBlockState(p))) {
                        if (takeable(p)) {
                            found.add(p);
                        }
                        break;
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble((BlockPos p) -> Math.hypot(p.getX() - x, p.getZ() - z))
                .thenComparingDouble(p -> Math.atan2(p.getZ() - z, p.getX() - x)));
        return found;
    }

    private static boolean open(BlockState state) {
        return state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty();
    }

    /** A whole block of the skin with open space (or a plant) over it and ground under it, as a crater would carve. */
    private boolean takeable(BlockPos p) {
        if (level.isOutsideBuildHeight(p.getY()) || !level.getWorldBorder().isWithinBounds(p) || spawnProtected(p)
                || level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) == null) {
            return false;
        }
        BlockState state = level.getBlockState(p);
        if (state.hasBlockEntity() || state.is(TremorTags.PROTECTED) || state.getDestroySpeed(level, p) < 0
                || state.is(Blocks.MAGMA_BLOCK) || state.is(BlockTags.LEAVES) || !state.getFluidState().isEmpty()
                || state.getBlock() instanceof Fallable || !state.isCollisionShapeFullBlock(level, p)) {
            return false;
        }
        return open(level.getBlockState(p.above()))
                && level.getBlockState(p.below()).isCollisionShapeFullBlock(level, p.below());
    }

    private boolean spawnProtected(BlockPos p) {
        int radius = level.dimension() == Level.OVERWORLD
                ? Math.max(0, level.getServer().getSpawnProtectionRadius()) : 0;
        BlockPos spawn = level.getSharedSpawnPos();
        return radius > 0 && Math.max(Math.abs(p.getX() - spawn.getX()), Math.abs(p.getZ() - spawn.getZ())) <= radius;
    }

    // ---- a block on its way ----

    private void start(BlockPos from, BlockPos to) {
        BlockState state = level.getBlockState(from);
        if (!level.getBlockState(from.above()).isAir()) {
            level.removeBlock(from.above(), false); // a plant on it
        }
        level.setBlock(from, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        level.playSound(null, from, state.getSoundType().getBreakSound(), SoundSource.BLOCKS, 0.8f, 0.6f);
        Move m = new Move(state, from, to, sides.getOrDefault(to, to.below()));
        m.shown = display(state, from, 0, 0, 0);
        pending.add(to);
        moves.add(m);
    }

    /** One tick of a block on its way; true once it is over. */
    private boolean step(Move m) {
        m.ticks++;
        if (m.ticks == 1) {
            slide(m.shown, 0, DOWN, 0, SINK_TICKS);
        } else if (m.ticks == SINK_TICKS + 1) {
            m.shown.discard();
            m.shown = display(m.state, m.to, -DOWN * (m.support.getX() - m.to.getX()),
                    -DOWN * (m.support.getY() - m.to.getY()), -DOWN * (m.support.getZ() - m.to.getZ()));
        } else if (m.ticks == SINK_TICKS + 2) {
            slide(m.shown, 0, 0, 0, RISE_TICKS);
            level.playSound(null, m.to, m.state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.8f, 0.7f);
        } else if (m.ticks == SINK_TICKS + RISE_TICKS + 2) {
            m.landed = true;
            pending.remove(m.to);
            if (fillable(m.to)) {
                level.setBlock(m.to, m.state, Block.UPDATE_ALL);
                placed++;
            } else if (level.getBlockState(m.from).isAir()) {
                level.setBlock(m.from, m.state, Block.UPDATE_ALL); // somebody stands there: it goes back
            }
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, m.state), m.to.getX() + 0.5,
                    m.to.getY() + 0.1, m.to.getZ() + 0.5, 8, 0.4, 0.05, 0.4, 0);
        } else if (m.ticks > SINK_TICKS + RISE_TICKS + 2) {
            m.shown.discard();
            m.shown = null;
            return true;
        }
        return false;
    }

    /** A display of {@code state} at the block {@code at}, moved by {@code dx, dy, dz}. */
    private Display.BlockDisplay display(BlockState state, BlockPos at, float dx, float dy, float dz) {
        Display.BlockDisplay shown = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
        shown.setPos(at.getX(), at.getY(), at.getZ());
        CompoundTag tag = shown.saveWithoutId(new CompoundTag());
        tag.put("block_state", NbtUtils.writeBlockState(state));
        tag.put("transformation", transformation(dx, dy, dz));
        shown.load(tag);
        level.addFreshEntity(shown);
        shown.addTag(Devour.TAG); // after it joined: a display saved mid-way does not come back
        return shown;
    }

    /** Moves the display by {@code dx, dy, dz}, smoothly over {@code ticks} on the clients (as {@code /data merge}). */
    private static void slide(Display.BlockDisplay shown, float dx, float dy, float dz, int ticks) {
        CompoundTag tag = shown.saveWithoutId(new CompoundTag());
        tag.put("transformation", transformation(dx, dy, dz));
        tag.putInt("interpolation_duration", ticks);
        tag.putInt("start_interpolation", 0);
        shown.load(tag);
    }

    private static CompoundTag transformation(float dx, float dy, float dz) {
        CompoundTag t = new CompoundTag();
        t.put("translation", floats(dx, dy, dz));
        t.put("left_rotation", floats(0, 0, 0, 1));
        t.put("right_rotation", floats(0, 0, 0, 1));
        t.put("scale", floats(1, 1, 1));
        return t;
    }

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float v : values) {
            list.add(FloatTag.valueOf(v));
        }
        return list;
    }
}
