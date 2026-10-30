package tremor.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.minecraft.core.BlockPos;
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
 * so the pillar becomes wide enough to climb ({@code movement.minGroundWidth} 3); around that core a rough heap rises,
 * its columns of random heights up to {@value #HEAP_SHARE} of the pillar's, lower in the corners (the user: a neat
 * 3x3 looked static). Each block sinks into the ground where it is taken (a block display going down, the block gone)
 * and rises out of the block below where it goes (a display coming up, then the block): any open cell with ground
 * under it, at random among the lowest {@value #RAGGED} + 1 layers of those, one every {@value #MIN_INTERVAL} to
 * {@value #MAX_INTERVAL} ticks. Taken from the top of the ground {@value #MIN_RING} to {@value #MAX_RING} blocks around
 * the column (at random among the {@value #SOURCE_CHOICE} nearest left), at most
 * {@value #SOURCE_HEIGHT} above or below the foot: a whole block of the skin with ground under it, never one a crater
 * keeps (a block entity, {@code #tremor:protected}, unbreakable, magma, a fluid, the spawn protection, outside the
 * world border); a plant on it goes. The block keeps its state: an ore goes into the pillar, nothing is lost. The
 * displays carry the tag of devoured mobs: left by a server stopping mid-way, they do not come back.
 */
final class PerchBuild {
    /** Ticks between two blocks set off, at random. */
    static final int MIN_INTERVAL = 2;
    static final int MAX_INTERVAL = 5;
    /** The next cell is any of those this many layers over the lowest one ready. */
    static final int RAGGED = 1;
    /** The heap around the core reaches this far out (Chebyshev, blocks)... */
    static final int HEAP_RING = 2;
    /** ...and its columns up to this share of the pillar's height. */
    static final double HEAP_SHARE = 0.5;
    /** The ground is taken at random among this many of the nearest blocks left. */
    static final int SOURCE_CHOICE = 8;
    /** Ticks a block sinks where it is taken... */
    static final int SINK_TICKS = 8;
    /** ...and rises where it goes. */
    static final int RISE_TICKS = 8;
    /** Blocks are taken this far around the column (Chebyshev, blocks): a free ring around the heap stays. */
    static final int MIN_RING = HEAP_RING + 2;
    static final int MAX_RING = 9;
    /** The ground is taken at most this far above or below the foot. */
    static final int SOURCE_HEIGHT = 3;
    /** How far a display goes down: into the block below, not flush with its top (no flicker of two faces). */
    private static final float DOWN = -0.98f;

    private final ServerLevel level;
    private final int x, z, footY, topY;
    private final List<Move> moves = new ArrayList<>();
    /** Cells a block is on its way to. */
    private final Set<BlockPos> pending = new HashSet<>();
    /** Every cell to fill, the core's and the heap's; null until the first is chosen. */
    private List<BlockPos> plan;
    private final Set<BlockPos> core = new HashSet<>();
    private final Random random;
    private List<BlockPos> sources;
    private long nextAt;
    /** No more blocks set off: filled up, nothing left to take, or {@link #finish}ed. */
    private boolean done;
    private boolean filled;
    private int placed;

    private static final class Move {
        final BlockState state;
        final BlockPos from;
        final BlockPos to;
        int ticks;
        boolean landed;
        Display.BlockDisplay shown;

        Move(BlockState state, BlockPos from, BlockPos to) {
            this.state = state;
            this.from = from;
            this.to = to;
        }
    }

    /** Around column {@code x, z}, from the layer over {@code footY} (the ground the pillar stands on) to {@code topY}. */
    PerchBuild(ServerLevel level, int x, int z, int footY, int topY) {
        this.level = level;
        this.x = x;
        this.z = z;
        this.footY = footY;
        this.topY = topY;
        this.random = new Random(BlockPos.asLong(x, footY, z));
    }

    int placed() {
        return placed;
    }

    /** Whether every cell of the core up to the top is filled: the pillar is wide enough to climb. */
    boolean filled() {
        return filled;
    }

    /** No more blocks set off; those under way go on to their place ({@link #tick} until it is over). */
    void finish() {
        done = true;
    }

    /** True once it is over: filled up, or nothing left to take, and every block under way is in place. */
    boolean tick(long now) {
        for (Iterator<Move> it = moves.iterator(); it.hasNext(); ) {
            if (step(it.next())) {
                it.remove();
            }
        }
        if (!done && now >= nextAt) {
            nextAt = now + MIN_INTERVAL + random.nextInt(MAX_INTERVAL - MIN_INTERVAL + 1);
            BlockPos to = nextCell();
            if (to == null) {
                done = true;
                filled = core.stream().noneMatch(p -> open(level.getBlockState(p)));
            } else if (to != WAIT) {
                BlockPos from = nextSource();
                if (from == null) {
                    done = true;
                } else {
                    start(from, to);
                }
            }
        }
        return done && moves.isEmpty();
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
        done = true;
    }

    // ---- where to ----

    private static final BlockPos WAIT = new BlockPos(0, Integer.MIN_VALUE, 0);

    /**
     * The next cell to fill: open, with ground under it (no block on its way there), at random among the lowest
     * {@value #RAGGED} + 1 layers of those; {@link #WAIT} while none is ready but blocks are on their way, null once
     * nothing is left.
     */
    private BlockPos nextCell() {
        if (plan == null) {
            plan();
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
        return ready.get(random.nextInt(ready.size()));
    }

    /** The core, 3x3 up to the top, and the heap around it: columns of random heights, lower in the corners. */
    private void plan() {
        plan = new ArrayList<>();
        int height = topY - footY;
        for (int dz = -HEAP_RING; dz <= HEAP_RING; dz++) {
            for (int dx = -HEAP_RING; dx <= HEAP_RING; dx++) {
                int ring = Math.max(Math.abs(dx), Math.abs(dz));
                int top = height;
                if (ring > 1) {
                    int most = (int) Math.ceil(height * HEAP_SHARE) - (Math.abs(dx) == Math.abs(dz) ? 1 : 0);
                    top = random.nextInt(Math.max(0, most) + 1);
                }
                for (int h = 1; h <= top; h++) {
                    BlockPos p = new BlockPos(x + dx, footY + h, z + dz);
                    plan.add(p);
                    if (ring <= 1) {
                        core.add(p);
                    }
                }
            }
        }
    }

    private boolean fillable(BlockPos p) {
        BlockState state = level.getBlockState(p);
        return (state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty())
                && level.getEntitiesOfClass(LivingEntity.class, new AABB(p), e -> !e.isSpectator()).isEmpty();
    }

    // ---- where from ----

    private BlockPos nextSource() {
        if (sources == null) {
            sources = findSources();
        }
        while (!sources.isEmpty()) {
            BlockPos p = sources.remove(random.nextInt(Math.min(SOURCE_CHOICE, sources.size())));
            if (takeable(p)) {
                return p;
            }
        }
        return null;
    }

    /** The top of the ground in every column of the ring that may be taken, the nearest first. */
    private List<BlockPos> findSources() {
        List<BlockPos> found = new ArrayList<>();
        for (int dz = -MAX_RING; dz <= MAX_RING; dz++) {
            for (int dx = -MAX_RING; dx <= MAX_RING; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) < MIN_RING) {
                    continue;
                }
                for (int y = footY + SOURCE_HEIGHT; y >= footY - SOURCE_HEIGHT; y--) {
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

    /** A whole block of the skin with open space (or a plant) over it and ground under it, that a crater would carve. */
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
        int radius = level.dimension() == Level.OVERWORLD ? Math.max(0, level.getServer().getSpawnProtectionRadius()) : 0;
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
        Move m = new Move(state, from, to);
        m.shown = display(state, from, 0);
        pending.add(to);
        moves.add(m);
    }

    /** One tick of a block on its way; true once it is over. */
    private boolean step(Move m) {
        m.ticks++;
        if (m.ticks == 1) {
            slide(m.shown, DOWN, SINK_TICKS);
        } else if (m.ticks == SINK_TICKS + 1) {
            m.shown.discard();
            m.shown = display(m.state, m.to, DOWN);
        } else if (m.ticks == SINK_TICKS + 2) {
            slide(m.shown, 0, RISE_TICKS);
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

    /** A display of {@code state} at the block {@code at}, moved {@code dy} down or up. */
    private Display.BlockDisplay display(BlockState state, BlockPos at, float dy) {
        Display.BlockDisplay shown = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
        shown.setPos(at.getX(), at.getY(), at.getZ());
        CompoundTag tag = shown.saveWithoutId(new CompoundTag());
        tag.put("block_state", NbtUtils.writeBlockState(state));
        tag.put("transformation", transformation(dy));
        shown.load(tag);
        level.addFreshEntity(shown);
        shown.addTag(Devour.TAG); // after it joined: a display saved mid-way does not come back
        return shown;
    }

    /** Moves the display to {@code dy}, smoothly over {@code ticks} on the clients (as {@code /data merge} does). */
    private static void slide(Display.BlockDisplay shown, float dy, int ticks) {
        CompoundTag tag = shown.saveWithoutId(new CompoundTag());
        tag.put("transformation", transformation(dy));
        tag.putInt("interpolation_duration", ticks);
        tag.putInt("start_interpolation", 0);
        shown.load(tag);
    }

    private static CompoundTag transformation(float dy) {
        CompoundTag t = new CompoundTag();
        t.put("translation", floats(0, dy, 0));
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
