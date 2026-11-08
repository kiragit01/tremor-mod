package tremor.client;

/**
 * How a short notice moves (the user: a small aside, never abrupt, alive): it drops into place with a little spring
 * past it and a pop, breathes a pixel or so up and down, and goes with a little lift before it falls away gathering
 * speed, fading on the way. The marks told under the hollow's time bar, the marks a charm takes off. Ticks; pure math.
 */
public final class NoticeMotion {
    /** It comes in over this many ticks, and goes over the last this many. */
    public static final double IN = 7;
    public static final double OUT = 10;
    /** It drops this far into place (pixels), and is this much bigger at first. */
    static final double DROP = 3;
    static final double POP = 0.12;
    /** It breathes this far (pixels) over this many ticks. */
    static final double BREATH = 1.3;
    static final double BREATH_TICKS = 50;

    /**
     * Where a notice is: its opacity (0..1), how far down from its place (pixels), and its size (1 as set).
     */
    public record Pose(double alpha, double dy, double scale) {
    }

    private NoticeMotion() {
    }

    /** At {@code t} ticks into a notice {@code length} ticks long; {@code clock} times its breath. */
    public static Pose at(double t, double length, double clock) {
        double in = clamp(t / IN), out = clamp((t - length + OUT) / OUT);
        double alpha = Math.min(in * 1.5, 1) * (1 - clamp((out - 0.35) / 0.65));
        double breath = BREATH * Math.sin(2 * Math.PI * clock / BREATH_TICKS);
        // In it drops into place with a little spring past it; out the lift and the fall: 12t² - 7t.
        double dy = -DROP * (1 - backOut(in)) + 12 * out * out - 7 * out + breath;
        return new Pose(alpha, dy, 1 + POP * (1 - in) * (1 - in));
    }

    /** Eases out past the end and back (a spring): 0 at 0, 1 at 1, a little over 1 between. */
    public static double backOut(double t) {
        return backOut(t, 1.70158);
    }

    /** {@link #backOut(double)} with an overshoot of {@code c} (0: none). */
    public static double backOut(double t, double c) {
        double u = t - 1;
        return 1 + (c + 1) * u * u * u + c * u * u;
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }
}
