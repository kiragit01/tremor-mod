package tremor.hollow.level;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Takes the {@link VoxelGrid} snapshot of a box of the hollow the planning works on, straight from the chunk sections,
 * a chunk column per {@link #step} (a box of the planning's size is some 140 thousand blocks, a column of it some 8
 * thousand), so the reading is spread over ticks. A block of a chunk that is not loaded reads as solid. Server thread
 * only.
 */
final class GridReader {
    private final ServerLevel level;
    private final VoxelGrid grid;
    /** The flags depend on the state only (a collision shape is empty or not, and as high, wherever the block is). */
    private final Reference2IntOpenHashMap<BlockState> known = new Reference2IntOpenHashMap<>();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final int minChunkX;
    private final int chunksX;
    private final int minChunkZ;
    private final int chunks;
    private int next;

    /** The reading of the box {@code min..max} (bounds inclusive) of {@code level}. */
    GridReader(ServerLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.level = level;
        grid = new VoxelGrid(minX, minY, minZ, maxX, maxY, maxZ);
        known.defaultReturnValue(-1);
        minChunkX = minX >> 4;
        minChunkZ = minZ >> 4;
        chunksX = (maxX >> 4) - minChunkX + 1;
        chunks = chunksX * ((maxZ >> 4) - minChunkZ + 1);
    }

    /** Reads the next chunk column of the box; true once all are read ({@link #grid}). */
    boolean step() {
        if (next >= chunks) {
            return true;
        }
        int cx = minChunkX + next % chunksX;
        int cz = minChunkZ + next / chunksX;
        next++;
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk != null) {
            int x0 = Math.max(grid.minX(), cx << 4);
            int x1 = Math.min(grid.maxX(), (cx << 4) + 15);
            int z0 = Math.max(grid.minZ(), cz << 4);
            int z1 = Math.min(grid.maxZ(), (cz << 4) + 15);
            for (int y = grid.minY(); y <= grid.maxY(); y++) {
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                        int flags = known.getInt(state);
                        if (flags < 0) {
                            flags = flags(level, pos.set(x, y, z), state);
                            known.put(state, flags);
                        }
                        grid.set(x, y, z, flags);
                    }
                }
            }
        }
        return next >= chunks;
    }

    /** The grid: complete once {@link #step} said so. */
    VoxelGrid grid() {
        return grid;
    }

    /** The {@link VoxelGrid} flags of {@code state} at {@code pos}. */
    static int flags(BlockGetter level, BlockPos pos, BlockState state) {
        int flags = 0;
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            flags |= VoxelGrid.OPEN;
        } else if (shape.max(Direction.Axis.Y) > 1) {
            flags |= VoxelGrid.TALL;
        }
        if (!state.getFluidState().isEmpty()) {
            flags |= VoxelGrid.LIQUID;
        }
        if (Materials.hazard(state)) {
            flags |= VoxelGrid.HAZARD;
        }
        return flags;
    }
}
