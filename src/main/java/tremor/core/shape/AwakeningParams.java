package tremor.core.shape;

/**
 * How the ground of an Awakening zone moves in the first phase, in the real world (SPEC 9, "нарастание"): it breathes,
 * every step sends a ring over it, and a hill rises under the player it swallows. The formulas are in
 * {@link AwakeningShape}, one frame of it is an {@link AwakeningField}. Lengths in blocks, times in seconds.
 *
 * @param breathPeriod   duration of one breath (up and back down)
 * @param breathStart    height of a breath when the build-up starts...
 * @param breathEnd      ...and when it ends; it grows linearly in between and stays there while the player is
 *                       swallowed
 * @param edgeWidth      the breathing fades out over this distance inside the zone's edge, so it stops right at the
 *                       edge the player has to cross to escape
 * @param verticalReach  the zone's ground is looked for this far above and below its centre
 * @param verticalFade   the breathing fades out over this distance before the vertical reach
 * @param hillHeight     peak of the hill under the swallowed player at the end of the swallowing
 * @param hillSigma      spread of the hill: {@code peak·exp(-d²/σ²)} at distance {@code d} from where it rises
 * @param stepRipple     the ring of a walking step (strength 1); a step of strength {@code s} is {@code s} times as
 *                       high, see {@link AwakeningShape#stepRipple}
 * @param maxStrength    stronger steps count as this strong
 * @param releaseSeconds after an Awakening ended in the real world (escape), its ground settles over this long
 */
public record AwakeningParams(double breathPeriod, double breathStart, double breathEnd, double edgeWidth,
                              int verticalReach, double verticalFade, double hillHeight, double hillSigma,
                              RippleParams stepRipple, double maxStrength, double releaseSeconds) {
    /**
     * A slow breath (5 s) of 0.15 blocks growing to 0.4; a hill of 3 blocks (higher than the eyes) about 10 blocks
     * across; rings of 0.2 blocks per walking step, one crest each, running out at 8 blocks/s and gone within 1.5 s
     * (the crest of a walking step stays above {@link BumpShape#RENDER_THRESHOLD} for about 8 blocks); a sprinting
     * step (strength 2) rings twice as high, nothing higher than 4 times.
     */
    private static final AwakeningParams DEFAULTS = new AwakeningParams(5, 0.15, 0.4, 4, 24, 6, 3, 2.5,
            new RippleParams(0.2, 8, 3, 1, 1.5), 4, 1.5);

    /** @throws IllegalArgumentException if a value is non-finite or out of range */
    public AwakeningParams {
        requirePositive("breathPeriod", breathPeriod);
        requireNonNegative("breathStart", breathStart);
        requireNonNegative("breathEnd", breathEnd);
        requirePositive("edgeWidth", edgeWidth);
        if (verticalReach < 1) {
            throw new IllegalArgumentException("verticalReach must be >= 1: " + verticalReach);
        }
        requirePositive("verticalFade", verticalFade);
        requireNonNegative("hillHeight", hillHeight);
        requirePositive("hillSigma", hillSigma);
        if (stepRipple == null) {
            throw new IllegalArgumentException("stepRipple must not be null");
        }
        requireNonNegative("maxStrength", maxStrength);
        requirePositive("releaseSeconds", releaseSeconds);
    }

    /** See {@link #DEFAULTS}. */
    public static AwakeningParams defaults() {
        return DEFAULTS;
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
