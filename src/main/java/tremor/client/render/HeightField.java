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
 * @param ringFrame the bump's frame as the ripple went out: its rings stay centred there while the bump moves on, as
 *                  the server's echo does (the user: jumped the ring, found a second later all the same)
 */
record HeightField(BumpParams params, BumpFrame frame, double time, boolean jitter, RippleParams ripple,
                   double rippleAge, BumpFrame ringFrame) {
    /** A ripple centred on the bump as it is. */
    HeightField(BumpParams params, BumpFrame frame, double time, boolean jitter, RippleParams ripple,
                double rippleAge) {
        this(params, frame, time, jitter, ripple, rippleAge, frame);
    }

    double at(double x, double y, double z) {
        double h = jitter ? BumpShape.height(params, frame, x, y, z, time) : BumpShape.height(params, frame, x, y, z);
        return ripple == null ? h : h + Ripple.height(ripple, ringDistance(ringFrame, x, y, z), rippleAge);
    }

    /** A bump facing up (or down) at least this much has its rings run level ({@link Ripple#RING_LEVEL}). */
    static final double RING_LEVEL = Ripple.RING_LEVEL;

    /** The normal of the plane its rings run in ({@link Ripple#ringNormal}). */
    static Vec3 ringNormal(Vec3 normal) {
        return Ripple.ringNormal(normal);
    }

    /**
     * Distance of {@code (x, y, z)} from the frame's centre as its rings measure it: across the level plane for rings
     * running level ({@link #ringNormal} straight up or down), so a ring on the ground stays a ring whatever the height
     * of a voxel; in space for rings on a wall or a steep slope, so where they run over onto the floor at the wall's
     * foot they spread there as a half ring from under the strike (the user: in the wall's own plane the floor took
     * them as two straight stripes away from the wall).
     */
    static double ringDistance(BumpFrame frame, double x, double y, double z) {
        Vec3 c = frame.center(), n = ringNormal(frame.normal());
        double dx = x - c.x(), dy = y - c.y(), dz = z - c.z();
        double d2 = dx * dx + dy * dy + dz * dz;
        if (Math.abs(n.y()) < RING_LEVEL) {
            return Math.sqrt(d2);
        }
        double along = dx * n.x() + dy * n.y() + dz * n.z();
        return Math.sqrt(Math.max(0, d2 - along * along));
    }
}
