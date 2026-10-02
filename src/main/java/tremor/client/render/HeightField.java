package tremor.client.render;

import tremor.core.math.Vec3;
import tremor.core.shape.BumpFrame;
import tremor.core.shape.BumpParams;
import tremor.core.shape.BumpShape;
import tremor.core.shape.Ripple;
import tremor.core.shape.RippleParams;

/**
 * The displacement along the surface normal that one frame draws: the bump (SPEC 6.1) plus, while one runs, the
 * ground ripple of an ALERT freeze (SPEC 8: "по земле вокруг идёт мелкая рябь") centred on the bump. The ripple's
 * distance is measured in the tangent plane of the bump normal, so its rings stay rings on a floor (or a wall)
 * whatever the height of a voxel above it. Pure math, no game classes.
 *
 * @param time      animation clock of the jitter, seconds
 * @param jitter    whether to include the jitter term
 * @param ripple    the ripple running now, null if none (then this is exactly the bump)
 * @param rippleAge seconds since the ripple started; ignored without a ripple
 */
record HeightField(BumpParams params, BumpFrame frame, double time, boolean jitter, RippleParams ripple,
                   double rippleAge) {
    double at(double x, double y, double z) {
        double h = jitter ? BumpShape.height(params, frame, x, y, z, time) : BumpShape.height(params, frame, x, y, z);
        return ripple == null ? h : h + Ripple.height(ripple, tangentDistance(frame, x, y, z), rippleAge);
    }

    /** Distance of {@code (x, y, z)} from the frame's centre within the tangent plane of its normal. */
    static double tangentDistance(BumpFrame frame, double x, double y, double z) {
        Vec3 c = frame.center(), n = frame.normal();
        double dx = x - c.x(), dy = y - c.y(), dz = z - c.z();
        double along = dx * n.x() + dy * n.y() + dz * n.z();
        return Math.sqrt(Math.max(0, dx * dx + dy * dy + dz * dz - along * along));
    }
}
