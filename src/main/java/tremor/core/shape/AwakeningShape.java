package tremor.core.shape;

import tremor.core.noise.PerlinNoise;

import java.util.Objects;

/**
 * The formulas of the ground of an Awakening zone in the real world (SPEC 9, phase 1), each a displacement along the
 * surface normal, summed by {@link AwakeningField}:
 * <ul>
 *     <li><b>breathing</b> ("вся поверхность в зоне медленно поднимается и опускается синусоидой"):
 *     {@code A·(0.5 - 0.5·cos(2πt/T))}, rising from the ground's own place and falling back to it, never below.
 *     {@code A} grows over the build-up ({@link #breathAmplitude}); it fades out towards the zone's edge
 *     ({@link #zoneFade});</li>
 *     <li><b>the swallow hill</b> ("под игроком поднимается холм"): a round mound
 *     {@code peak·exp(-d²/σ²)} around where it rises, the peak growing over the swallowing ({@link #hillPeak});</li>
 *     <li><b>step rings</b> ("каждый шаг игрока порождает кольцевую волну"): a {@link Ripple} per step, as high as the
 *     step is strong ({@link #stepRipple}).</li>
 * </ul>
 * When the Awakening ends in the real world everything settles together ({@link #release}). After a victory in the
 * hollow the same hill rises at the swallow point once more ({@link #emergeRise}), as high as in the last frame of the
 * swallowing, stands while the player is put into it, and settles to let the player out ({@link #emergeSettle}).
 * Pure math, thread-safe.
 */
public final class AwakeningShape {
    /**
     * Share of {@code awakening.emergeTicks} the hill of a victory (SPEC 9 "Победа") takes to rise; it settles over the
     * rest. In between it stands for as long as it takes to put the player into it.
     */
    public static final double EMERGE_RISE = 0.2;

    private AwakeningShape() {
    }

    /** {@code 3s² - 2s³} of {@code s} clamped to {@code [0, 1]}: 0 up to 0, 1 from 1 on, flat at both ends; NaN gives 0. */
    public static double smoothstep(double s) {
        if (!(s > 0)) {
            return 0;
        }
        if (s >= 1) {
            return 1;
        }
        return s * s * (3 - 2 * s);
    }

    /**
     * Height of a breath {@code progress} (0..1, clamped; NaN counts as 0) into the build-up: from
     * {@link AwakeningParams#breathStart} linearly to {@link AwakeningParams#breathEnd}.
     */
    public static double breathAmplitude(AwakeningParams p, double progress) {
        double t = progress > 0 ? Math.min(progress, 1) : 0;
        return p.breathStart() + (p.breathEnd() - p.breathStart()) * t;
    }

    /**
     * The breathing {@code timeSeconds} after it started: {@code amplitude·(0.5 - 0.5·cos(2π·t/period))}. Starts flat
     * at 0, peaks at {@code amplitude} half a period in, back at 0 after a whole one; 0 before the start (and for a
     * NaN time).
     */
    public static double breath(double amplitude, double period, double timeSeconds) {
        if (!(timeSeconds > 0)) {
            return 0;
        }
        return amplitude * (0.5 - 0.5 * Math.cos(2 * Math.PI * timeSeconds / period));
    }

    /** Size (blocks) of the swells the breathing ground heaves in ({@link #swell}). */
    public static final double SWELL_SCALE = 7.0;
    /** Least share of the breathing anywhere: the ground between the swells still rises a little. */
    public static final double SWELL_FLOOR = 0.3;

    /**
     * Share of the breathing at a point, {@code SWELL_FLOOR..1}: broad uneven swells about {@value #SWELL_SCALE}
     * blocks across, fixed in place, so that a whole flat zone does not just rise as one plate (which looks like
     * nothing moves) but heaves like a chest. From {@link PerlinNoise} over the point's position.
     */
    public static double swell(double x, double y, double z) {
        double n = PerlinNoise.noise(x / SWELL_SCALE, y / SWELL_SCALE, z / SWELL_SCALE);
        double s = smoothstep(0.5 + 0.9 * n);
        return SWELL_FLOOR + (1 - SWELL_FLOOR) * s;
    }

    /**
     * Share of the breathing at a point {@code horizontalDistance} from the centre of a zone of {@code radius}, and
     * {@code verticalOffset} above (or below) it: {@code smoothstep((radius - d)/edgeWidth)} times
     * {@code smoothstep((verticalReach - |dy|)/verticalFade)}. 1 well inside, 0 from the edge (and the vertical reach)
     * on.
     */
    public static double zoneFade(AwakeningParams p, double radius, double horizontalDistance, double verticalOffset) {
        double across = smoothstep((radius - horizontalDistance) / p.edgeWidth());
        if (across == 0) {
            return 0;
        }
        return across * smoothstep((p.verticalReach() - Math.abs(verticalOffset)) / p.verticalFade());
    }

    /**
     * Peak of the swallow hill {@code progress} (0..1, clamped; NaN counts as 0) into the swallowing:
     * {@code hillHeight·progress²}. It starts slowly and rises faster and faster, and is over the swallowed player's
     * eyes only in the last quarter or so.
     */
    public static double hillPeak(AwakeningParams p, double progress) {
        double t = progress > 0 ? Math.min(progress, 1) : 0;
        return p.hillHeight() * t * t;
    }

    /**
     * Peak of the hill a victor comes out of (SPEC 9 "Победа": "на месте поглощения в реальном мире вырастает холм,
     * игрок выходит из него, холм медленно оседает") {@code progress} (0..1, clamped; NaN counts as 0) into its rise at
     * the swallow point: it shoots up to {@link AwakeningParams#hillHeight}, fast at first and slowing to the top
     * ({@code 1 - (1 - s)²}). At the top it is the hill of the last frame of the swallowing ({@link #hillPeak} at 1):
     * the same mound, as high; it stands there until it settles ({@link #emergeSettle}).
     */
    public static double emergeRise(AwakeningParams p, double progress) {
        double s = 1 - (progress > 0 ? Math.min(progress, 1) : 0);
        return p.hillHeight() * (1 - s * s);
    }

    /**
     * How fast the peak of {@link #emergeRise} moves at {@code progress}, in heights per whole rise (divide by the
     * rise's length for blocks per second): positive while it rises, 0 before the start and from the top on.
     */
    public static double emergeRiseRate(AwakeningParams p, double progress) {
        if (!(progress > 0) || progress >= 1) {
            return 0;
        }
        return p.hillHeight() * 2 * (1 - progress);
    }

    /**
     * Peak of the hill a victor comes out of {@code progress} (0..1, clamped; NaN counts as 0) into its settling: from
     * the top ({@link AwakeningParams#hillHeight}) slowly back to the ground's own place ({@code 1 - smoothstep}), gone
     * at the end. Flat at both ends: it starts to sink from the standing hill without a jerk, and the ground comes to
     * rest softly. Over the eyes of a player standing in its middle for a little less than the first half.
     */
    public static double emergeSettle(AwakeningParams p, double progress) {
        return p.hillHeight() * (1 - smoothstep(progress));
    }

    /**
     * How fast the peak of {@link #emergeSettle} moves at {@code progress}, in heights per whole settling (divide by its
     * length for blocks per second): negative while it settles, 0 before the start and from the end on.
     */
    public static double emergeSettleRate(AwakeningParams p, double progress) {
        if (!(progress > 0) || progress >= 1) {
            return 0;
        }
        return -p.hillHeight() * 6 * progress * (1 - progress);
    }

    /** Height of a hill with the given {@code peak} at squared distance {@code distanceSq} from where it rises. */
    public static double hill(double peak, double sigma, double distanceSq) {
        return peak * Math.exp(-distanceSq / (sigma * sigma));
    }

    /**
     * The ring of a step of {@code strength} (1 for a walking step): {@link AwakeningParams#stepRipple} with its
     * amplitude times the strength clamped to {@code [0, maxStrength]} (NaN counts as 0). The speed and duration stay,
     * so a stronger ring is higher and stays visible further out, but never runs beyond
     * {@link Ripple#maxRadius} of the step ring.
     */
    public static RippleParams stepRipple(AwakeningParams p, double strength) {
        Objects.requireNonNull(p, "p");
        double s = strength > 0 ? Math.min(strength, p.maxStrength()) : 0;
        return p.stepRipple().withAmplitude(p.stepRipple().amplitude() * s);
    }

    /**
     * Share of its motion the ground keeps {@code secondsSinceEnd} after the Awakening ended in the real world:
     * {@code smoothstep(1 - t/releaseSeconds)}, from 1 at the end to 0 after the release time; 1 before the end.
     */
    public static double release(AwakeningParams p, double secondsSinceEnd) {
        if (!(secondsSinceEnd > 0)) {
            return 1;
        }
        return smoothstep(1 - secondsSinceEnd / p.releaseSeconds());
    }
}
