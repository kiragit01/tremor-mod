package tremor.hollow.level;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import tremor.block.MireBlock;
import tremor.block.TremorBlocks;

/**
 * The soft ground under the player (SPEC 9, "Затягивание") made real: as the player stands still
 * ({@link SinkTracker}), the blocks the player stands on turn into {@code tremor:mire} and give way level by level,
 * then the blocks under them, so the player sinks into the ground. Each softened block remembers what it was and where
 * the ground's top was; once the player has been off it for {@code recoverTicks} it turns back into that block. How
 * deep the player is ({@link #sink}) is measured from that top to the player's feet, against the eye height:
 * {@code 1} (the eyes under the ground) is the defeat. Ground that cannot soften (a block entity, an unbreakable block,
 * the node, anything not a full block: a slab, a carpet) holds; a creature that flies or swims is not pulled.
 */
final class Mire {
    /** Blocks under the player that may soften: the one stood on and the one under it (over the eyes is enough). */
    private static final int LAYERS = 2;
    /** Soft blocks are looked at for setting again this often (ticks). */
    private static final int RECOVER_EVERY = 5;
    private static final double EPSILON = 1e-3;

    private final SinkTracker tracker;
    private final int recoverTicks;
    private final Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<>();
    private double sink;

    /** A softened block: what it was, the top of the ground of its column then, the softness now. */
    private static final class Cell {
        final BlockState original;
        final int surfaceY;
        int softness = -1;
        long touched;

        Cell(BlockState original, int surfaceY) {
            this.original = original;
            this.surfaceY = surfaceY;
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
        int groundY = Mth.floor(player.getY() - EPSILON);
        boolean grounded = player.onGround() && !player.getAbilities().flying && !player.isSpectator()
                && !player.isInWater() && softGround(level, owner, minX, maxX, minZ, maxZ, groundY);
        tracker.update(player.getX(), player.getZ(), grounded);
        double depth = tracker.depth();
        double deepest = 0;
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                if (depth > 0) {
                    soften(level, owner, x, z, groundY, depth, tick);
                }
                for (int y = groundY; y <= groundY + 1; y++) {
                    Cell cell = cells.get(CellKey.of(x, y, z));
                    if (cell != null && body.intersects(x, y, z, x + 1, y + 1, z + 1)) {
                        cell.touched = tick;
                        deepest = Math.max(deepest, SinkTracker.sink(cell.surfaceY, player.getY(),
                                player.getEyeHeight()));
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

    /** Blocks soft now. */
    int size() {
        return cells.size();
    }

    /** Whether some of the ground under the player's body can soften, or is soft already. */
    private boolean softGround(ServerLevel level, EventLevel owner, int minX, int maxX, int minZ, int maxZ,
                               int groundY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                pos.set(x, groundY, z);
                BlockState state = level.getBlockState(pos);
                if (state.is(TremorBlocks.MIRE) || Materials.softenable(level, pos, state) && !owner.isNode(pos)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Softens the column {@code x, z} under the player to {@code depth}: the block the player stands on (at
     * {@code groundY}; its top is the ground's top, unless the column is soft already and remembers it), then the one
     * under it. A block that cannot soften stops the column there.
     */
    private void soften(ServerLevel level, EventLevel owner, int x, int z, int groundY, double depth, long tick) {
        Cell known = cells.get(CellKey.of(x, groundY, z));
        if (known == null) {
            // Standing on the hard block under a block that gave way completely.
            known = cells.get(CellKey.of(x, groundY + 1, z));
        }
        int surfaceY = known != null ? known.surfaceY : groundY + 1;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int layer = 0; layer < LAYERS; layer++) {
            int softness = SinkTracker.softness(depth, layer);
            if (softness < 0) {
                return;
            }
            pos.set(x, surfaceY - 1 - layer, z);
            long key = pos.asLong();
            Cell cell = cells.get(key);
            if (cell == null) {
                BlockState state = level.getBlockState(pos);
                if (!Materials.softenable(level, pos, state) || owner.isNode(pos)) {
                    return;
                }
                cell = new Cell(state, surfaceY);
                cells.put(key, cell);
            } else if (!level.getBlockState(pos).is(TremorBlocks.MIRE)) {
                // Something else changed it meanwhile: it is not ours any more.
                cells.remove(key);
                return;
            }
            cell.touched = tick;
            if (softness > cell.softness) {
                cell.softness = softness;
                level.setBlock(pos, TremorBlocks.MIRE.get().defaultBlockState().setValue(MireBlock.SOFTNESS, softness),
                        Materials.FLAGS);
            }
        }
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
            if (level.getBlockState(pos).is(TremorBlocks.MIRE)) {
                level.setBlock(pos, cell.original, Materials.FLAGS);
            }
            it.remove();
        }
    }
}
