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
 * <p>
 * The rings of an Awakening and of the hollow (SPEC 9) kick up the same dust; in the hollow only on the part of a
 * front near the player ({@link #keep}). A hill rising or settling shakes dust off its flanks ({@link #heaveCount}).
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
    /** A voxel on a ring's front kicks up dust only where the ground shows at least this share of its rings. */
    static final double SHOWN_SHARE = 0.5;
    /**
     * Particles per tick per square block of a hill's flank (the disc of its radius) when its peak moves by one block
     * per second.
     */
    static final double HEAVE_DENSITY = 0.15;

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
     * Particles for the part of a front that is shown: {@code count·kept/total}, rounded down or up at random so that
     * it is right on average; {@code count} if all of it is kept (also for no front at all, which gets no dust anyway),
     * 0 if none of it is.
     *
     * @param kept    voxels of the front that are shown
     * @param total   voxels of the whole front
     * @param uniform a random number in {@code [0, 1)}
     */
    static int keep(int count, int kept, int total, double uniform) {
        if (kept >= total) {
            return count;
        }
        if (kept <= 0) {
            return 0;
        }
        return (int) Math.floor((double) count * kept / total + uniform);
    }

    /**
     * Particles per tick a hill of {@code radius} kicks up while its peak moves at {@code speed} blocks per second,
     * up or down, uncapped: {@code HEAVE_DENSITY·|speed|·π·radius²}.
     */
    static double heaveRate(double speed, double radius) {
        return HEAVE_DENSITY * Math.abs(speed) * Math.PI * radius * radius;
    }

    /**
     * Particles to spawn this tick off a rising or settling hill: the {@link #heaveRate}, capped at
     * {@link #MAX_PER_TICK}, times {@code share}, rounded down or up at random as {@link #count} does; 0 for a hill
     * that stands still (or a NaN speed).
     */
    static int heaveCount(double speed, double radius, double share, double uniform) {
        double expected = Math.min(heaveRate(speed, radius), MAX_PER_TICK) * share;
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
