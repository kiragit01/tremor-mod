package tremor.core.shape;

import tremor.core.math.Vec3;
import tremor.core.noise.PerlinNoise;

/**
 * Displacement field of the bump along the surface normal (SPEC 6.1).
 * <p>
 * With {@code d = p - c}, {@code a = d·f} (along the motion) and {@code b = |d - a·f - (d·n)·n|} (across it, in the
 * tangent plane):
 * <pre>
 * h(p) = A·exp(-(a²/σa² + b²/σside²))                  main bump, σa = a >= 0 ? σfront : σback
 *      - k·A·exp(-((a + L)²/σt² + b²/σside²))           trailing dip
 *      + ε·A·noise(p, t)                                 jitter
 * </pre>
 * The height is independent of {@code d·n}, i.e. of where along the normal the sample point lies.
 * All methods are allocation-free and thread-safe.
 */
public final class BumpShape {
    /** Points with {@code |h|} below this are not rendered (SPEC 6.1). */
    public static final double RENDER_THRESHOLD = 0.05;
    /** Spatial frequency of the jitter noise, per block. */
    public static final double JITTER_FREQUENCY = 0.55;
    /** Drift of the jitter noise along its y axis, per second. */
    public static final double JITTER_SPEED = 1.7;
    /**
     * Period of the jitter in time, seconds: time moves the noise's y by {@code t·}{@link #JITTER_SPEED} and
     * {@link PerlinNoise} repeats every 256 lattice units, so the shape at {@code t} and {@code t + period} is the
     * same. An animation clock may be taken modulo this to keep it small (it is sent as a float).
     */
    public static final double JITTER_PERIOD_SECONDS = 256.0 / JITTER_SPEED;

    private BumpShape() {
    }

    /** Deterministic part of the shape (main bump minus trailing dip) at world point {@code (px, py, pz)}. */
    public static double height(BumpParams params, BumpFrame frame, double px, double py, double pz) {
        return evaluate(params, frame, px, py, pz, Double.NaN);
    }

    /**
     * Full shape including the jitter term {@code ε·A·noise(p·F, p.y·F + t·S, p.z·F)·envelope(p)} with
     * {@code F = }{@link #JITTER_FREQUENCY} and {@code S = }{@link #JITTER_SPEED}.
     * <p>
     * Deviation from the literal SPEC formula: the jitter is faded by the envelope of the bump and its trail
     * (the larger of the two Gaussians, so {@code 0..1}). Unfaded, it would make the whole influence disc tremble
     * by up to {@code ε·A} and end in a visible hard edge at its radius.
     *
     * @param timeSeconds animation time in seconds
     */
    public static double height(BumpParams params, BumpFrame frame, double px, double py, double pz,
                                double timeSeconds) {
        return evaluate(params, frame, px, py, pz, timeSeconds);
    }

    /** Shared evaluation; {@code timeSeconds = NaN} skips the jitter term. */
    private static double evaluate(BumpParams params, BumpFrame frame, double px, double py, double pz,
                                   double timeSeconds) {
        Vec3 c = frame.center(), n = frame.normal(), f = frame.forward();
        double dx = px - c.x(), dy = py - c.y(), dz = pz - c.z();
        double a = dx * f.x() + dy * f.y() + dz * f.z();
        double along = dx * n.x() + dy * n.y() + dz * n.z();
        double rx = dx - a * f.x() - along * n.x();
        double ry = dy - a * f.y() - along * n.y();
        double rz = dz - a * f.z() - along * n.z();
        double b2 = rx * rx + ry * ry + rz * rz;

        double amplitude = params.amplitude();
        double sigmaSide = params.sigmaSide();
        double side = b2 / (sigmaSide * sigmaSide);
        double sigmaA = a >= 0 ? params.sigmaFront() : params.sigmaBack();
        double bump = Math.exp(-(a * a / (sigmaA * sigmaA) + side));
        double h = amplitude * bump;

        double trail = 0;
        double trailDepth = params.trailDepth();
        if (trailDepth > 0) {
            double t = a + params.trailLag();
            double trailSigma = params.trailSigma();
            trail = Math.exp(-(t * t / (trailSigma * trailSigma) + side));
            h -= trailDepth * amplitude * trail;
        }

        double jitter = params.jitter();
        if (jitter == 0 || Double.isNaN(timeSeconds)) {
            return h;
        }
        double noise = PerlinNoise.noise(px * JITTER_FREQUENCY,
                py * JITTER_FREQUENCY + timeSeconds * JITTER_SPEED,
                pz * JITTER_FREQUENCY);
        return h + jitter * amplitude * noise * Math.max(bump, trail);
    }
}
