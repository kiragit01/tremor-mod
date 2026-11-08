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
 * distance is measured in space from where it went out ({@link #ringDistance}), so its rings run on over the ground,
 * the walls and the steps they reach as one wave. Pure math, no game classes.
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

    /** The ripple alone at {@code (x, y, z)}: for ground only its ring took, beyond the bump's own. */
    double ripple(double x, double y, double z) {
        return ripple == null ? 0 : Ripple.height(ripple, ringDistance(ringFrame, x, y, z), rippleAge);
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
     * Distance of {@code (x, y, z)} from the frame's centre as its rings measure it: in space, from where they went
     * out, so they run on over every surface they reach as a wave would: across the ground, up a wall from its foot in
     * an arc, down from a wall onto the floor in a half ring, a little behind up a hill (the user: measured in a plane,
     * a wall's ring ran onto the floor as two stripes and a floor's climbed a wall as two blocks).
     */
    static double ringDistance(BumpFrame frame, double x, double y, double z) {
        Vec3 c = frame.center();
        double dx = x - c.x(), dy = y - c.y(), dz = z - c.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
