package tremor.client.render;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import tremor.core.deform.ThinSwell;
import tremor.core.math.Vec3;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * One deformed surface voxel of {@link DeformationRenderer}: where it is and, once baked, its geometry per render
 * layer. The same column serves the bump and the Awakening zone when both cover it ({@link ZoneColumns}). Or an open
 * cell a thin structure swells into around the entity ({@link ThinSwell}): one copy of the structure's voxel nearest to
 * it ({@link #source}), grown out of the face toward that voxel along {@link #axis} as far as {@link #h} (0..1).
 */
final class Column {
    final int x, y, z;
    final BlockPos pos;
    /** The voxel whose block the copies are of: the voxel itself, or the source of a {@link #fill} cell. */
    final BlockPos source;
    /** Whether this is a cell a thin structure swells into ({@link ThinSwell.Fill}) rather than a surface voxel. */
    final boolean fill;
    /** For a fill cell: the distance to its source (blocks), and the centre of the source. */
    final double fillDistance;
    final double sx, sy, sz;
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
    /** Which of this frame's bumps cover it (while {@link #inBump}), by their place in the frame: bit 0 the first. */
    long bumps;
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
        this.source = pos;
        this.fill = false;
        this.fillDistance = 0;
        this.sx = cx;
        this.sy = cy;
        this.sz = cz;
    }

    Column(ThinSwell.Fill f) {
        this.x = f.x();
        this.y = f.y();
        this.z = f.z();
        this.pos = new BlockPos(x, y, z);
        this.axis = Direction.getNearest(f.growX(), f.growY(), f.growZ());
        this.decoPos = pos.relative(axis);
        this.cx = x + 0.5;
        this.cy = y + 0.5;
        this.cz = z + 0.5;
        this.normal = new Vec3(f.growX(), f.growY(), f.growZ());
        this.source = new BlockPos(f.sourceX(), f.sourceY(), f.sourceZ());
        this.fill = true;
        this.fillDistance = f.distance();
        this.sx = f.sourceX() + 0.5;
        this.sy = f.sourceY() + 0.5;
        this.sz = f.sourceZ() + 0.5;
    }

    /** Whether it is the fill cell {@code f} as it is (same source, same growth). */
    boolean is(ThinSwell.Fill f) {
        return fill && source.getX() == f.sourceX() && source.getY() == f.sourceY() && source.getZ() == f.sourceZ()
                && axis.getStepX() == f.growX() && axis.getStepY() == f.growY() && axis.getStepZ() == f.growZ()
                && fillDistance == f.distance();
    }

    boolean hasGeometry() {
        return !meshes.isEmpty() || !decoMeshes.isEmpty();
    }

    /** Copies drawn: the stack filling the gap down to the original (one for a fill cell), plus the decoration. */
    int cost() {
        return (meshes.isEmpty() ? 0 : fill ? 1 : (int) Math.ceil(h)) + (decoMeshes.isEmpty() ? 0 : 1);
    }
}
