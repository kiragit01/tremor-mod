package tremor.client.hollow;

import tremor.core.shape.AwakeningShape;

/**
 * How the pull of the soft ground in the hollow (SPEC 9 phase 2: "земля под ним размягчается — он медленно
 * проваливается") looks to the player being pulled in, as plain functions of how deep the ground has the player
 * ({@code sink}, 0 free .. 1 fully pulled in): the darkness closing in from the edges of the view ({@link #shade}),
 * and the easing of the sink the server sends a few times a second ({@link #approach}). No game classes.
 * <p>
 * The darkness is a radial shade over the screen, {@code r} measured from the middle of the screen (0) to its corners
 * (1) along ellipses of the screen's proportions: clear within {@link #clear}, darkening smoothly out to {@link #full}
 * and as dark as {@link #edge} from there on. As the player sinks, both close in and the edges go black: the view
 * narrows like a hole closing overhead.
 */
final class SinkCurve {
    /** Time constant (seconds) with which the shown sink follows the server's. */
    static final double EASE_SECONDS = 0.25;
    /** Sink below which the darkness fades in from nothing (so that it does not pop up at the first pull). */
    static final double ONSET = 0.05;
    /** Darkness at the corners when the pull starts... */
    static final double EDGE_START = 0.45;
    /** ...and when the player is fully pulled in. */
    static final double EDGE_END = 0.97;
    /** Radius of the clear middle when the pull starts... */
    static final double CLEAR_START = 0.75;
    /** ...and when the player is fully pulled in. */
    static final double CLEAR_END = 0.12;
    /** Radius from which on the darkness is as deep as at the corners when the pull starts (the corners)... */
    static final double FULL_START = 1.0;
    /** ...and when the player is fully pulled in. */
    static final double FULL_END = 0.55;

    private SinkCurve() {
    }

    /**
     * The shown sink after {@code dtSeconds}, eased exponentially toward {@code target} (clamped to 0..1; NaN counts
     * as 0) with {@link #EASE_SECONDS}.
     */
    static double approach(double current, double target, double dtSeconds) {
        double t = share(target);
        return t + (current - t) * Math.exp(-dtSeconds / EASE_SECONDS);
    }

    /**
     * Opacity of the darkness at the corners of the screen: 0 with no pull, rising from {@link #EDGE_START} to
     * {@link #EDGE_END} with the sink (faded in over the first {@link #ONSET}).
     */
    static double edge(double sink) {
        double s = share(sink);
        return lerp(EDGE_START, EDGE_END, s) * AwakeningShape.smoothstep(s / ONSET);
    }

    /**
     * Radius (0 middle .. 1 corners) within which the view stays clear: from {@link #CLEAR_START} to
     * {@link #CLEAR_END} with the sink.
     */
    static double clear(double sink) {
        return lerp(CLEAR_START, CLEAR_END, share(sink));
    }

    /**
     * Radius (0 middle .. 1 corners) from which on the darkness is as deep as {@link #edge}: from {@link #FULL_START}
     * to {@link #FULL_END} with the sink.
     */
    static double full(double sink) {
        return lerp(FULL_START, FULL_END, share(sink));
    }

    /**
     * Opacity of the darkness at radius {@code r} (0 middle .. 1 corners) of the screen: {@code edge(sink)} times the
     * smoothstep from {@link #clear} out to {@link #full}.
     */
    static double shade(double r, double sink) {
        double clear = clear(sink);
        return edge(sink) * AwakeningShape.smoothstep((r - clear) / (full(sink) - clear));
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    /** {@code x} clamped to {@code [0, 1]}; NaN counts as 0. */
    private static double share(double x) {
        return x > 0 ? Math.min(x, 1) : 0;
    }
}
