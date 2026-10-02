package tremor.client.render;

import tremor.core.shape.Ripple;
import tremor.core.shape.RippleParams;

/**
 * How much dust the ground ripple of an ALERT freeze (SPEC 8: "по земле вокруг идёт мелкая рябь") kicks up per game
 * tick, and where: along its front, the circle of radius {@code speed·age} around the bump centre in the tangent
 * plane of its normal ({@link HeightField#tangentDistance}). Raised copies of a block over the same block show only
 * as thin seams; the dust makes the rings read. {@link DeformationRenderer} spawns it. Pure math, no game classes.
 * <p>
 * The dust follows the height of the ripple: {@link #DENSITY} particles per tick per block of the front circle, times
 * the height of the leading crest, a quarter wavelength behind the front ({@link Ripple}): the ripple's amplitude
 * times its envelope, so it thins out as the ripple fades and is lower around a lower bump (the renderer folds
 * {@link Ripple#visibility} into the amplitude), and times the hole, so none rises under the bump itself.
 */
final class RippleDust {
    /** Particles per tick per block of the front circle when the leading crest is one block high. */
    static final double DENSITY = 1.5;
    /** Particles per tick at most, however long the circle and high the ripple. */
    static final int MAX_PER_TICK = 24;
    /**
     * A surface voxel is on the front if its centre is within this distance of the front circle: a band one block
     * wide, which holds about one voxel per block of the circle on flat ground.
     */
    static final double HALF_WIDTH = 0.5;

    private RippleDust() {
    }

    /** Radius of the front circle {@code ageSeconds} after the ripple started: {@code speed·age}. */
    static double front(RippleParams ripple, double ageSeconds) {
        return ripple.speed() * ageSeconds;
    }

    /**
     * Height of the leading crest, a quarter wavelength behind the front: {@code amplitude·(1 - age/duration)·hole};
     * 0 while it is still within {@link Ripple#HOLE_RADIUS} of the centre and outside the ripple's lifetime.
     */
    static double crest(RippleParams ripple, double ageSeconds) {
        return Ripple.height(ripple, front(ripple, ageSeconds) - ripple.wavelength() / 4, ageSeconds);
    }

    /** Particles per tick the ripple kicks up, uncapped: {@code DENSITY·2π·front·crest}. */
    static double rate(RippleParams ripple, double ageSeconds) {
        return DENSITY * 2 * Math.PI * front(ripple, ageSeconds) * crest(ripple, ageSeconds);
    }

    /**
     * Particles to spawn this tick: the {@link #rate}, capped at {@link #MAX_PER_TICK}, times {@code share}, rounded
     * down or up at random so that it is right on average and a thin ripple still kicks up a grain now and then.
     *
     * @param ripple  the ripple running now, as drawn (its amplitude includes {@link Ripple#visibility})
     * @param share   share of the dust the particle setting allows: 1 at all, 0.5 at decreased, 0 at minimal
     * @param uniform a random number in {@code [0, 1)}
     * @return at most {@link #MAX_PER_TICK}; 0 outside the ripple's lifetime (also for a NaN age)
     */
    static int count(RippleParams ripple, double ageSeconds, double share, double uniform) {
        double expected = Math.min(rate(ripple, ageSeconds), MAX_PER_TICK) * share;
        return expected > 0 ? (int) Math.floor(expected + uniform) : 0;
    }

    /**
     * Whether a surface voxel whose centre is {@code distance} from the bump centre (in the tangent plane) lies on
     * the front circle of radius {@code front}.
     */
    static boolean onFront(double distance, double front) {
        return Math.abs(distance - front) <= HALF_WIDTH;
    }
}
