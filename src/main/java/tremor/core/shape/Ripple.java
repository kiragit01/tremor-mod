package tremor.core.shape;

import java.util.Objects;
import tremor.core.math.Vec3;

/**
 * Rings running outward over the ground from the bump when it freezes at a sound (SPEC 8 ALERT: "по земле вокруг
 * идёт мелкая рябь"). A displacement along the surface normal, added to the bump by the renderer, as a function of
 * the distance from the bump's centre and the age of the ripple:
 * <pre>
 * front = speed·age,  x = front - distance (how far behind the front)
 * height = amplitude · (1 - (age/duration)^fadePower) · hole(distance) · sin(2π·x / wavelength)   for 0 < x < waves·wavelength
 * </pre>
 * and 0 ahead of the front ({@code x <= 0}), behind the train ({@code x >= waves·wavelength}) and outside
 * {@code 0 <= age < duration}. The first crest runs a quarter wavelength behind the front. The train is a whole number
 * of wavelengths, so the height is continuous at both its ends; the envelope fades it linearly to 0 by the end of the
 * duration, so the rings keep most of their height while they run out from under the bump. {@code hole} keeps the
 * ground still where the bump itself stands: 0 within {@link #HOLE_RADIUS} of the centre, rising smoothly
 * (smoothstep) to 1 at {@link #HOLE_EDGE}.
 * <p>
 * The ripple belongs to the bump and fades with it ({@link #visibility}): it is lower around a lower bump and gone
 * while the bump is hidden in a dive (SPEC 5.4: "во время нырка бугор не виден").
 */
public final class Ripple {
    /** Within this distance of the centre the ripple is zero. */
    public static final double HOLE_RADIUS = 1.0;
    /** From this distance on the ripple has its full height. */
    public static final double HOLE_EDGE = 2.0;

    private Ripple() {
    }

    /** A bump facing up (or down) at least this much has its rings run level. */
    public static final double RING_LEVEL = 0.75;
    private static final Vec3 DOWN = new Vec3(0, -1, 0);

    /**
     * The normal of the plane the rings of a bump with {@code normal} run in: straight up (or down) for one facing up
     * (down) mostly, so a ring a tremor on a slope sends runs level and stays on the ground all the way out (the user:
     * it went off into another plane and vanished); the bump's own for a wall. The client draws them so, and the
     * server's echo goes by it.
     */
    public static Vec3 ringNormal(Vec3 normal) {
        return normal.y() >= RING_LEVEL ? Vec3.UNIT_Y : normal.y() <= -RING_LEVEL ? DOWN : normal;
    }

    /** Displacement at {@code distance} blocks from the centre, {@code ageSeconds} after the ripple started. */
    public static double height(RippleParams p, double distance, double ageSeconds) {
        Objects.requireNonNull(p, "p");
        if (!active(p, ageSeconds)) {
            return 0;
        }
        double behind = p.speed() * ageSeconds - distance;
        if (!(behind > 0 && behind < p.waves() * p.wavelength()) || distance <= HOLE_RADIUS) {
            return 0;
        }
        double fade = 1 - Math.pow(ageSeconds / p.duration(), p.fadePower());
        double s = Math.min(1, (distance - HOLE_RADIUS) / (HOLE_EDGE - HOLE_RADIUS));
        double hole = s * s * (3 - 2 * s);
        return p.amplitude() * fade * hole * Math.sin(2 * Math.PI * behind / p.wavelength());
    }

    /** Farthest distance the front reaches: {@code speed·duration}. The height is 0 beyond it. */
    public static double maxRadius(RippleParams p) {
        return p.speed() * p.duration();
    }

    /** Whether the ripple is still running: {@code 0 <= ageSeconds < duration}. */
    public static boolean active(RippleParams p, double ageSeconds) {
        return ageSeconds >= 0 && ageSeconds < p.duration();
    }

    /**
     * Share of its height the ripple shows around a bump that is {@code amplitude} high now and {@code full} high at
     * its full size (the shape's amplitude, before any stage factor): {@code amplitude/full} clamped to
     * {@code [0, 1]}. So it shrinks with the bump, is gone while the bump is sunk in a dive or inverted, and never
     * exceeds its own amplitude around a bump raised above its full size; 0 if {@code full <= 0} or a value is NaN.
     */
    public static double visibility(double amplitude, double full) {
        double share = amplitude / full;
        return full > 0 && share > 0 ? Math.min(share, 1) : 0;
    }
}
