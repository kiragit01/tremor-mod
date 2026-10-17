package tremor.core.shape;

/**
 * Shape parameters of the travelling bump (SPEC 6.1). Lengths are in blocks.
 *
 * @param amplitude  {@code A}, peak displacement along the surface normal (may be negative)
 * @param sigmaFront {@code σa} ahead of the centre ({@code a >= 0}); smaller than {@code sigmaBack} gives a steep front
 * @param sigmaBack  {@code σa} behind the centre ({@code a < 0})
 * @param sigmaSide  {@code σb}, half-width across the direction of motion
 * @param trailLag   {@code L}, how far behind the centre the trailing dip sits
 * @param trailSigma {@code σt}, length of the trailing dip
 * @param trailDepth {@code k}, depth of the trailing dip relative to {@code A} ({@code 0} disables it)
 * @param jitter     {@code ε}, noise amplitude relative to {@code A} ({@code 0} disables it)
 */
public record BumpParams(double amplitude, double sigmaFront, double sigmaBack, double sigmaSide,
                         double trailLag, double trailSigma, double trailDepth, double jitter) {

    private static final BumpParams DEFAULTS = new BumpParams(2.0, 1.6, 2.6, 2.2, 4.0, 3.0, 0.3, 0.06);

    /** @throws IllegalArgumentException if a value is non-finite or out of range */
    public BumpParams {
        if (!Double.isFinite(amplitude)) {
            throw new IllegalArgumentException("amplitude must be finite: " + amplitude);
        }
        requirePositive("sigmaFront", sigmaFront);
        requirePositive("sigmaBack", sigmaBack);
        requirePositive("sigmaSide", sigmaSide);
        requirePositive("trailSigma", trailSigma);
        requireNonNegative("trailLag", trailLag);
        requireNonNegative("trailDepth", trailDepth);
        requireNonNegative("jitter", jitter);
    }

    /** {@code A=2.0, σfront=1.6, σback=2.6, σside=2.2, L=4.0, σt=3.0, k=0.3, ε=0.06}. */
    public static BumpParams defaults() {
        return DEFAULTS;
    }

    /**
     * Distance from the centre beyond which the deterministic part of the shape is negligible:
     * {@code max(3·max(σfront, σback, σside), k > 0 ? L + 3·σt : 0)}.
     */
    public double influenceRadius() {
        double main = 3.0 * Math.max(sigmaFront, Math.max(sigmaBack, sigmaSide));
        double trail = trailDepth > 0 ? trailLag + 3.0 * trailSigma : 0.0;
        return Math.max(main, trail);
    }

    public BumpParams withAmplitude(double amplitude) {
        return new BumpParams(amplitude, sigmaFront, sigmaBack, sigmaSide, trailLag, trailSigma, trailDepth, jitter);
    }

    /**
     * Same shape stretched by {@code sizeFactor}: all lengths ({@code σfront, σback, σside, L, σt}) are multiplied,
     * amplitude and the relative factors {@code k, ε} are kept.
     *
     * @throws IllegalArgumentException if {@code sizeFactor} is not a finite positive number
     */
    public BumpParams scaled(double sizeFactor) {
        requirePositive("sizeFactor", sizeFactor);
        return new BumpParams(amplitude, sigmaFront * sizeFactor, sigmaBack * sizeFactor, sigmaSide * sizeFactor,
                trailLag * sizeFactor, trailSigma * sizeFactor, trailDepth, jitter);
    }

    /**
     * The shape lying in wait ({@link tremor.core.behavior.Ambush}), {@code share} (0..1, clamped) of the way there:
     * round (as long ahead and behind as it is wide) and {@code spread} times as wide as its widest, without the
     * trailing dip, which a bump standing still does not leave; the amplitude is kept. The same shape at 0.
     *
     * @throws IllegalArgumentException if {@code spread} is not a finite positive number
     */
    public BumpParams lurking(double share, double spread) {
        requirePositive("spread", spread);
        if (!(share > 0)) {
            return this;
        }
        double s = Math.min(1, share);
        double round = Math.max(sigmaFront, Math.max(sigmaBack, sigmaSide)) * spread;
        return new BumpParams(amplitude, sigmaFront + (round - sigmaFront) * s, sigmaBack + (round - sigmaBack) * s,
                sigmaSide + (round - sigmaSide) * s, trailLag, trailSigma, trailDepth * (1 - s), jitter);
    }

    private static void requirePositive(String name, double value) {
        if (!(value > 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite and > 0: " + value);
        }
    }

    private static void requireNonNegative(String name, double value) {
        if (!(value >= 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite and >= 0: " + value);
        }
    }
}
