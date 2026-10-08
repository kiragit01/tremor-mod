package tremor.hollow.level;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import tremor.block.MireBlock;
import tremor.block.TremorBlocks;

/**
 * The soft ground under the player (SPEC 9, "Затягивание") made real: as the player stands still
 * ({@link SinkTracker}), the ground the player stands on turns into {@code tremor:mire} and gives way level by level,
 * then the blocks under it, so the player sinks into the ground. Any ground does: a full block, a slab, a path,
 * farmland, soul sand, mud (it starts as low as the block was), the top of a fence or a wall (it sinks to a block's
 * height); a thin covering lower than {@value #THIN} of a block (a carpet, a few layers of snow) is pushed into the
 * ground (taken away) and the block under it softens. Each changed block remembers what it was and where the ground's
 * top was; once the player has been off it for {@code recoverTicks} it turns back into that block.
 * <p>
 * Some blocks never soften: one with a block entity (a prop of the copy, which would lose it), the node, and what is
 * not solid (air, a fluid). Where the softening reaches one under the ground (a floor one block thick over a cave, the
 * bottom of the copy), the block above it keeps its last eighth, so the player is never dropped through.
 * <p>
 * How deep the player is pulled in ({@link #sink}) is how far the softening got ({@link SinkTracker#depth}), or how far
 * the feet are below the ground's top if that is more (a player who stepped into a hole), against the eye height of a
 * standing player whatever the pose (crouching or crawling does not pull the player in sooner): {@code 1} (the eyes
 * under the ground) is the defeat. So standing still for the same time is the defeat on any ground, also where it
 * holds. A creature that flies or swims is not pulled.
 */
final class Mire {
    /**
     * Blocks under the player that may soften: the one stood on and the two under it (the eyes get under the ground
     * from a slab too).
     */
    private static final int LAYERS = 3;
    /** A block stood on lower than this (of a block) is a thin covering taken away, not ground. */
    private static final double THIN = 0.5;
    /** Soft blocks are looked at for setting again this often (ticks). */
    private static final int RECOVER_EVERY = 5;
    private static final double EPSILON = 1e-3;

    private final SinkTracker tracker;
    private final int recoverTicks;
    private final Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<>();
    private double sink;

    /**
     * A changed block: what it was, which column it is of (its top block, the top of the ground and how much of the
     * top block was missing then), whether it is a covering taken away (air now) or soft ground (mire), the softness.
     */
    private static final class Cell {
        final BlockState original;
        final int top;
        final double surface;
        final double missing;
        final boolean covering;
        int softness = -1;
        long touched;

        Cell(BlockState original, int top, double surface, double missing, boolean covering) {
            this.original = original;
            this.top = top;
            this.surface = surface;
            this.missing = missing;
            this.covering = covering;
        }
    }

    Mire(SinkTracker.Params params, int recoverTicks) {
        tracker = new SinkTracker(params);
        this.recoverTicks = recoverTicks;
    }

    /** One tick with the player; returns how deep the player is pulled in ({@link #sink}). */
    double tick(ServerLevel level, ServerPlayer player, EventLevel owner, long tick) {
        AABB body = player.getBoundingBox();
        int minX = Mth.floor(body.minX + EPSILON);
        int maxX = Mth.floor(body.maxX - EPSILON);
        int minZ = Mth.floor(body.minZ + EPSILON);
        int maxZ = Mth.floor(body.maxZ - EPSILON);
        double feet = player.getY();
        int groundY = Mth.floor(feet - EPSILON);
        boolean grounded = player.onGround() && !player.getAbilities().flying && !player.isSpectator()
                && !player.isInWater();
        tracker.update(player.getX(), player.getZ(), grounded);
        double depth = tracker.depth();
        double eye = player.getEyeHeight(Pose.STANDING);
        double deepest = SinkTracker.sink(depth, 0, eye);
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                if (depth > 0) {
                    soften(level, owner, x, z, groundY, feet, depth, tick);
                }
                for (int y = groundY; y <= groundY + 1; y++) {
                    Cell cell = cells.get(CellKey.of(x, y, z));
                    if (cell != null && body.intersects(x, y, z, x + 1, y + 1, z + 1)) {
                        cell.touched = tick;
                        deepest = Math.max(deepest, SinkTracker.sink(cell.surface, feet, eye));
                    }
                }
            }
        }
        if (tick % RECOVER_EVERY == 0) {
            recover(level, body, tick);
        }
        sink = deepest;
        return sink;
    }

    /** How deep the player was pulled in on the last tick: 0 (feet at the ground's top) .. 1 (eyes under it). */
    double sink() {
        return sink;
    }

    SinkTracker tracker() {
        return tracker;
    }

    /** Blocks changed now. */
    int size() {
        return cells.size();
    }

    /**
     * Softens the column {@code x, z} under the player (feet at {@code feet}, on the block at {@code groundY}) to
     * {@code depth}: the top block of the ground (the one stood on, or the one under a covering, which goes; its top
     * is the ground's top, unless the column is soft already and remembers it), then the ones under it. A block that
     * cannot soften stops the column there, and the one above it keeps its last eighth.
     */
    private void soften(ServerLevel level, EventLevel owner, int x, int z, int groundY, double feet, double depth,
                        long tick) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Cell known = cells.get(CellKey.of(x, groundY, z));
        if (known == null) {
            // Standing on the hard block under a block that gave way completely, or on one under a covering taken.
            known = cells.get(CellKey.of(x, groundY + 1, z));
        }
        int top;
        double surface;
        double missing;
        BlockState covering = null;
        if (known != null) {
            top = known.top;
            surface = known.surface;
            missing = known.missing;
        } else {
            pos.set(x, groundY, z);
            BlockState state = level.getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (shape.isEmpty()) {
                // On top of a block higher than one (a fence, a wall)? It sinks to a block's height first.
                pos.set(x, groundY - 1, z);
                if (height(level, pos) <= 1) {
                    return;
                }
                top = groundY - 1;
                surface = groundY;
                missing = 0;
            } else {
                // The top under the feet (the lower step of stairs the player stands on, say).
                double height = Math.min(Math.min(1, shape.max(Direction.Axis.Y)), feet - groundY);
                if (height >= THIN) {
                    top = groundY;
                    surface = groundY + height;
                    missing = 1 - height;
                } else {
                    // A thin covering: the block under it is the ground, if it is not thin as well.
                    pos.set(x, groundY - 1, z);
                    double under = Math.min(1, height(level, pos));
                    if (under < THIN) {
                        return;
                    }
                    covering = state;
                    top = groundY - 1;
                    surface = top + under;
                    missing = 1 - under;
                }
            }
        }
        int[] want = new int[LAYERS];
        int deepest = -1;
        boolean held = false;
        for (int layer = 0; layer < LAYERS; layer++) {
            int softness = SinkTracker.softness(depth, missing, layer);
            if (softness < 0 && (layer == 0 || want[layer - 1] < SinkTracker.MAX_SOFTNESS)) {
                break;
            }
            // Once the block above is gone entirely, this one is reached, even if the softening is not into it yet.
            softness = Math.max(0, softness);
            pos.set(x, top - layer, z);
            long key = pos.asLong();
            Cell cell = cells.get(key);
            if (cell == null ? !Materials.softenable(level, pos, level.getBlockState(pos)) || owner.isNode(pos)
                    : !level.getBlockState(pos).is(TremorBlocks.MIRE.get())) {
                if (cell != null) {
                    // Something else changed it meanwhile: it is not ours any more.
                    cells.remove(key);
                }
                held = true;
                break;
            }
            want[layer] = softness;
            deepest = layer;
        }
        if (deepest < 0) {
            return;
        }
        if (held) {
            want[deepest] = SinkTracker.held(want[deepest]);
        }
        pos.set(x, top + 1, z);
        if (covering != null && !cells.containsKey(pos.asLong())) {
            Cell cell = new Cell(covering, top, surface, missing, true);
            cell.softness = SinkTracker.MAX_SOFTNESS;
            cells.put(pos.asLong(), cell);
            level.setBlock(pos, Materials.AIR, Materials.FLAGS);
        }
        Cell above = cells.get(pos.asLong());
        if (above != null && above.covering) {
            above.touched = tick;
        }
        for (int layer = 0; layer <= deepest; layer++) {
            pos.set(x, top - layer, z);
            long key = pos.asLong();
            Cell cell = cells.get(key);
            if (cell == null) {
                cell = new Cell(level.getBlockState(pos), top, surface, missing, false);
                cells.put(key, cell);
            }
            cell.touched = tick;
            if (want[layer] > cell.softness) {
                cell.softness = want[layer];
                level.setBlock(pos, TremorBlocks.MIRE.get().defaultBlockState().setValue(MireBlock.SOFTNESS,
                        want[layer]), Materials.FLAGS);
            }
        }
    }

    /** How high the collision of the block at {@code pos} reaches (blocks; 0 if it has none). */
    private static double height(ServerLevel level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        return shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
    }

    /** Turns back what the player has been off for a while (and is not in). */
    private void recover(ServerLevel level, AABB body, long tick) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (ObjectIterator<Long2ObjectMap.Entry<Cell>> it = cells.long2ObjectEntrySet().fastIterator();
             it.hasNext(); ) {
            Long2ObjectMap.Entry<Cell> entry = it.next();
            Cell cell = entry.getValue();
            pos.set(entry.getLongKey());
            if (tick - cell.touched < recoverTicks || body.intersects(pos.getX(), pos.getY(), pos.getZ(),
                    pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1)) {
                continue;
            }
            BlockState now = level.getBlockState(pos);
            if (cell.covering ? now.isAir() : now.is(TremorBlocks.MIRE.get())) {
                level.setBlock(pos, cell.original, Materials.FLAGS);
            }
            it.remove();
        }
    }
}
