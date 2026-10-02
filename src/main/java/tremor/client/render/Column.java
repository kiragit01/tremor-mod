package tremor.client.render;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import tremor.core.math.Vec3;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * One deformed surface voxel of {@link DeformationRenderer}: where it is and, once baked, its geometry per render
 * layer. The same column serves the bump and the Awakening zone when both cover it ({@link ZoneColumns}).
 */
final class Column {
    final int x, y, z;
    final BlockPos pos;
    /** Where a plant/snow layer riding on the voxel sits. */
    final BlockPos decoPos;
    final double cx, cy, cz;
    final Direction axis;
    /** Smoothed surface normal, refreshed by every collection. */
    Vec3 normal;
    BlockState state;
    BlockState decoState;
    BlockPos lightPos;
    int skyLight;
    int blockLight;
    /** Block-local model of the voxel itself and of a plant/snow layer sitting on it (offset by {@link #axis}). */
    final Map<RenderType, VertexList> meshes = new IdentityHashMap<>();
    final Map<RenderType, VertexList> decoMeshes = new IdentityHashMap<>();
    /** Whether the meshes were baked; columns start unbaked and are baked once they rise above the threshold. */
    boolean baked;
    long lastUsed;
    /** Frame number {@link #h}, {@link #inBump} and {@link #inZone} were last set for. */
    int stamp;
    /** Whether the height of the bump and that of the Awakening zone count for the column in that frame. */
    boolean inBump, inZone;
    double h;
    /** h at the 8 corners of the voxel, for the warp style. Index: x + 2y + 4z. */
    final double[] corners = new double[8];
    double distanceSq;

    Column(int x, int y, int z, Direction axis, Vec3 normal) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.pos = new BlockPos(x, y, z);
        this.decoPos = pos.relative(axis);
        this.cx = x + 0.5;
        this.cy = y + 0.5;
        this.cz = z + 0.5;
        this.axis = axis;
        this.normal = normal;
    }

    boolean hasGeometry() {
        return !meshes.isEmpty() || !decoMeshes.isEmpty();
    }

    /** Copies drawn: the stack filling the gap down to the original, plus the decoration. */
    int cost() {
        return (meshes.isEmpty() ? 0 : (int) Math.ceil(h)) + (decoMeshes.isEmpty() ? 0 : 1);
    }
}
