package tremor.core.deform;

import tremor.core.math.Vec3;

/**
 * A surface voxel affected by a deformation.
 *
 * @param x      voxel min-corner x
 * @param y      voxel min-corner y
 * @param z      voxel min-corner z
 * @param normal smoothed unit surface normal of the voxel (SPEC 6.2)
 */
public record SurfacePoint(int x, int y, int z, Vec3 normal) {
    /** Centre of the voxel. */
    public Vec3 center() {
        return Vec3.voxelCenter(x, y, z);
    }
}
