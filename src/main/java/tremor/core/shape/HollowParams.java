package tremor.core.shape;

/**
 * How the ground of the hollow moves around the player (SPEC 9 phase 2): the walls and the floor heave unevenly
 * ("пространство ходит ходуном"), less than the ground of the build-up, and on every beat of the node a ring runs out
 * from it ("выдаёт себя ... рябью в такт пульсу"). Both grow as the hollow closes. The formulas are in
 * {@link HollowShape}, one frame of it is a {@link HollowField}. Lengths in blocks, times in seconds.
 *
 * @param breathPeriod   duration of one heave of a patch of ground (up and back down)
 * @param breathStart    height of a heave when the hollow starts to close...
 * @param breathEnd      ...and when it has closed; linear in between ({@link HollowShape#closeness})
 * @param swellScale     size of the patches that heave out of step with each other
 * @param breathRadius   the ground heaves within this distance of the player...
 * @param breathFade     ...fading out over this distance before it
 * @param ring           the ring of a beat when the hollow starts to close
 * @param ringEnd        height of the ring when it has closed; linear in between
 * @param ringRadius     the rings are drawn within this distance of the player...
 * @param ringFade       ...fading out over this distance before it
 * @param follow         the region scanned for the ground is centred on the player again when the player is this far
 *                       from its centre
 * @param scanHeight     the region reaches this many whole blocks below and above its centre
 * @param releaseSeconds once the player is out of the hollow, the ground settles over this long
 */
public record HollowParams(double breathPeriod, double breathStart, double breathEnd, double swellScale,
                           double breathRadius, double breathFade, RippleParams ring, double ringEnd,
                           double ringRadius, double ringFade, double follow, int scanHeight,
                           double releaseSeconds) {
    /**
     * Patches of about 6 blocks heaving by 0.1 blocks, growing to 0.22 (the build-up breathes 0.15 to 0.4), every
     * 2.6 s, within 12 blocks of the player; rings of 0.3 blocks growing to 0.45, one crest 2 blocks wide (a
     * wavelength of 4) running out at 12 blocks/s for 5 s, so a ring crosses most of the copy and still stands above
     * {@link BumpShape#RENDER_THRESHOLD} some 48 blocks out from the node; drawn within 20 blocks of the player. The
     * region scanned follows the player every 6 blocks and reaches 24 blocks up and down; the ground settles over
     * 1.5 s.
     */
    private static final HollowParams DEFAULTS = new HollowParams(2.6, 0.1, 0.22, 6, 12, 6,
            new RippleParams(0.3, 12, 4, 1, 5), 0.45, 20, 8, 6, 24, 1.5);

    /** @throws IllegalArgumentException if a value is non-finite or out of range */
    public HollowParams {
        requirePositive("breathPeriod", breathPeriod);
        requireNonNegative("breathStart", breathStart);
        requireNonNegative("breathEnd", breathEnd);
        requirePositive("swellScale", swellScale);
        requirePositive("breathRadius", breathRadius);
        requirePositive("breathFade", breathFade);
        if (ring == null) {
            throw new IllegalArgumentException("ring must not be null");
        }
        requireNonNegative("ringEnd", ringEnd);
        requirePositive("ringRadius", ringRadius);
        requirePositive("ringFade", ringFade);
        requireNonNegative("follow", follow);
        if (scanHeight < 1) {
            throw new IllegalArgumentException("scanHeight must be >= 1: " + scanHeight);
        }
        requirePositive("releaseSeconds", releaseSeconds);
    }

    /** See {@link #DEFAULTS}. */
    public static HollowParams defaults() {
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
