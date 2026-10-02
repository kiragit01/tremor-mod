package tremor.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Light of a surface voxel as the player sees it. A solid block has no light of its own, so a displaced copy takes
 * the light of the brightest open voxel next to the original (SPEC 6.3: "light from the original block").
 */
public final class SurfaceLight {
    private SurfaceLight() {
    }

    /**
     * The open face-neighbour of {@code pos} with the most light. Neighbours the light engine keeps dark (opaque
     * blocks, unloaded chunks) never win; if there is no usable neighbour, the one along {@code preferred}.
     */
    public static BlockPos brightestOpenNeighbour(Level level, LevelVoxelView view, BlockPos pos, Direction preferred) {
        BlockPos best = pos.relative(preferred);
        int bestLight = light(level, view, best);
        for (Direction dir : Direction.values()) {
            if (dir == preferred) {
                continue;
            }
            BlockPos n = pos.relative(dir);
            int l = light(level, view, n);
            if (l > bestLight) {
                bestLight = l;
                best = n;
            }
        }
        return best;
    }

    /** Sky + block light at an open, light-carrying position, or -1 if light can not be read there. */
    private static int light(Level level, LevelVoxelView view, BlockPos pos) {
        if (!view.isOpen(pos.getX(), pos.getY(), pos.getZ())) {
            return -1;
        }
        BlockState state = view.stateAt(pos.getX(), pos.getY(), pos.getZ());
        if (state != null && state.isSolidRender(level, pos)) {
            return -1;
        }
        return level.getBrightness(LightLayer.SKY, pos) + level.getBrightness(LightLayer.BLOCK, pos);
    }
}
