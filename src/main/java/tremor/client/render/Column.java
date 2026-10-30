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
 * cell a thin structure swells into around the entity ({@link ThinSwell}): one whole copy of the structure's voxel
 * nearest to it ({@link #source}), slid out along the ray from that voxel ({@link #normal}) as far as {@link #h} (0..1)
 * of the way into the cell.
 */
final class Column {
    final int x, y, z;
    final BlockPos pos;
    /** The voxel whose block the copies are of: the voxel itself, or the one a {@link #fill} cell looks like. */
    final BlockPos source;
    /** Whether this is a cell a thin structure swells into ({@link ThinSwell.Fill}) rather than a surface voxel. */
    final boolean fill;
    /** For a fill cell: the distance to its source (blocks), and the centre of the source. */
    final double fillDistance;
    final double sx, sy, sz;
    /**
     * For a fill cell: which way it is nudged across its ray while it slides (+1 or -1 along each axis), so that its
     * faces never lie in the plane of those of the copy it slides out of.
     */
    final int nudgeX, nudgeY, nudgeZ;
    /** Where a plant/snow layer riding on the voxel sits. */
    final BlockPos decoPos;
    final double cx, cy, cz;
    final Direction axis;
    /** Smoothed surface normal, refreshed by every collection. */
    Vec3 normal;
    /**
     * This frame: the way the column also fills, at a seam where the column behind it goes that way while it goes
     * along its own axis (a ledge under a hill: the top up, the face sideways); null if none.
     */
    Direction cross;
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
        this.nudgeX = this.nudgeY = this.nudgeZ = 0;
    }

    Column(ThinSwell.Fill f) {
        this.x = f.x();
        this.y = f.y();
        this.z = f.z();
        this.pos = new BlockPos(x, y, z);
        this.cx = x + 0.5;
        this.cy = y + 0.5;
        this.cz = z + 0.5;
        this.normal = new Vec3(x - f.sourceX(), y - f.sourceY(), z - f.sourceZ()).normalize();
        this.axis = Direction.getNearest(normal.x(), normal.y(), normal.z());
        this.decoPos = pos.relative(axis);
        this.source = new BlockPos(f.lookX(), f.lookY(), f.lookZ());
        this.fill = true;
        this.fillDistance = f.distance();
        this.sx = f.sourceX() + 0.5;
        this.sy = f.sourceY() + 0.5;
        this.sz = f.sourceZ() + 0.5;
        long hash = (x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L);
        this.nudgeX = axis.getAxis() == Direction.Axis.X ? 0 : ((hash & 1) == 0 ? 1 : -1);
        this.nudgeY = axis.getAxis() == Direction.Axis.Y ? 0 : ((hash & 2) == 0 ? 1 : -1);
        this.nudgeZ = axis.getAxis() == Direction.Axis.Z ? 0 : ((hash & 4) == 0 ? 1 : -1);
    }

    /** Whether it is the fill cell {@code f} as it is (same source, same look, same distance). */
    boolean is(ThinSwell.Fill f) {
        return fill && sx == f.sourceX() + 0.5 && sy == f.sourceY() + 0.5 && sz == f.sourceZ() + 0.5
                && source.getX() == f.lookX() && source.getY() == f.lookY() && source.getZ() == f.lookZ()
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
