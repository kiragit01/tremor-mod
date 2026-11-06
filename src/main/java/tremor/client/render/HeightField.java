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
 * distance is measured in the plane of its rings ({@link #ringNormal}: level for a bump facing up or down mostly, the
 * bump's own tangent plane otherwise), so its rings stay rings on a floor (or a wall) whatever the height of a voxel
 * above it. Pure math, no game classes.
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

    /** A bump facing up (or down) at least this much has its rings run level. */
    static final double RING_LEVEL = 0.75;
    private static final Vec3 DOWN = new Vec3(0, -1, 0);

    /**
     * The normal of the plane the rings of a bump with {@code normal} run in: straight up (or down) for one facing up
     * (down) mostly, so a ring a tremor on a slope sends runs level and stays on the ground all the way out (the user:
     * it went off into another plane and vanished); the bump's own for a wall.
     */
    static Vec3 ringNormal(Vec3 normal) {
        return normal.y() >= RING_LEVEL ? Vec3.UNIT_Y : normal.y() <= -RING_LEVEL ? DOWN : normal;
    }

    /** Distance of {@code (x, y, z)} from the frame's centre within the plane of its rings ({@link #ringNormal}). */
    static double tangentDistance(BumpFrame frame, double x, double y, double z) {
        Vec3 c = frame.center(), n = ringNormal(frame.normal());
        double dx = x - c.x(), dy = y - c.y(), dz = z - c.z();
        double along = dx * n.x() + dy * n.y() + dz * n.z();
        return Math.sqrt(Math.max(0, dx * dx + dy * dy + dz * dz - along * along));
    }
}
