package tremor.core.shape;

import tremor.core.noise.PerlinNoise;

/**
 * The formulas of the ground of the hollow around the player (SPEC 9 phase 2), each a displacement along the surface
 * normal or a share of one, summed by {@link HollowField}:
 * <ul>
 *     <li><b>heaving</b> ("пространство ходит ходуном"): every patch of the walls and the floor rises and falls,
 *     {@code A·(0.5 - 0.5·cos(2πt/T + φ(p)))}, out of step with its neighbours ({@link #heave}); {@code A} grows as
 *     the hollow closes ({@link #breathAmplitude});</li>
 *     <li><b>the node's rings</b> ("выдаёт себя ... рябью в такт пульсу"): a {@link Ripple} from the node on every
 *     beat, higher as the hollow closes ({@link #nodeRing});</li>
 * </ul>
 * both only around the player ({@link #near}). Pure math, thread-safe.
 */
public final class HollowShape {
    /**
     * How far out of step patches of ground heave: the phase of a patch is {@code PHASE_SPREAD·π·(1 + n)} for the
     * noise {@code n} there, which spans about half a turn over the noise's usual range: at any moment some patches
     * are up while others are down, and neighbouring blocks stay nearly in step.
     */
    public static final double PHASE_SPREAD = 1.0;

    private HollowShape() {
    }

    /**
     * How far the hollow has closed, 0..1: {@code 1 - closeRadius/boxRadius} clamped, 0 when it has not started to
     * close (or the radii make no sense), 1 when it has closed down to nothing.
     */
    public static double closeness(double boxRadius, double closeRadius) {
        if (!(boxRadius > 0)) {
            return 0;
        }
        double share = 1 - closeRadius / boxRadius;
        return share > 0 ? Math.min(share, 1) : 0;
    }

    /**
     * Height of a heave at {@code closeness} (0..1, clamped; NaN counts as 0): from {@link HollowParams#breathStart}
     * linearly to {@link HollowParams#breathEnd}.
     */
    public static double breathAmplitude(HollowParams p, double closeness) {
        return lerp(p.breathStart(), p.breathEnd(), share(closeness));
    }

    /**
     * The ring of a beat at {@code closeness} (0..1, clamped; NaN counts as 0): {@link HollowParams#ring} with its
     * amplitude growing linearly to {@link HollowParams#ringEnd}.
     */
    public static RippleParams nodeRing(HollowParams p, double closeness) {
        return p.ring().withAmplitude(lerp(p.ring().amplitude(), p.ringEnd(), share(closeness)));
    }

    /**
     * Share of the heave a point has {@code timeSeconds} into the hollow, 0..1:
     * {@code 0.5 - 0.5·cos(2π·t/period + φ)} with {@code φ = PHASE_SPREAD·π·(1 + noise(p/swellScale))}, so that
     * patches about {@link HollowParams#swellScale} across rise and fall out of step with each other, some up while
     * others are down: the ground walks instead of lifting as one plate. Fixed in place; 0 for a NaN time.
     */
    public static double heave(HollowParams p, double timeSeconds, double x, double y, double z) {
        if (Double.isNaN(timeSeconds)) {
            return 0;
        }
        double s = p.swellScale();
        double phase = PHASE_SPREAD * Math.PI * (1 + PerlinNoise.noise(x / s, y / s, z / s));
        return 0.5 - 0.5 * Math.cos(2 * Math.PI * timeSeconds / p.breathPeriod() + phase);
    }

    /**
     * Share of an effect drawn around the player at {@code distance} from them: {@code smoothstep((radius - d)/fade)},
     * 1 up to {@code radius - fade}, 0 from {@code radius} on.
     */
    public static double near(double radius, double fade, double distance) {
        return AwakeningShape.smoothstep((radius - distance) / fade);
    }

    /**
     * Share of its motion the ground keeps {@code secondsSinceEnd} after the player left the hollow (or the client
     * stopped hearing of it): {@code smoothstep(1 - t/releaseSeconds)}, 1 before.
     */
    public static double release(HollowParams p, double secondsSinceEnd) {
        if (!(secondsSinceEnd > 0)) {
            return 1;
        }
        return AwakeningShape.smoothstep(1 - secondsSinceEnd / p.releaseSeconds());
    }

    private static double share(double x) {
        return x > 0 ? Math.min(x, 1) : 0;
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }
}
