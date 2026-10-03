package tremor.core.shape;

/**
 * How the ground of the hollow moves around the player (SPEC 9 phase 2): the walls and the floor heave unevenly
 * ("пространство ходит ходуном"), less than the ground of the build-up, and on every beat of the node a ring runs out
 * from it over the floor and the walls ("по волне видно, откуда она пришла"), its crest rising higher as it passes the
 * player. Both grow as the hollow closes. The hollow is dark (a black fog a few blocks deep), so all of it is drawn
 * only as far around the player as the player sees ({@link #withReach}). The formulas are in {@link HollowShape}, one
 * frame of it is a {@link HollowField}. Lengths in blocks, times in seconds.
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
 * @param crestBoost     right at the player a ring stands this share higher than elsewhere...
 * @param crestRadius    ...gaining less of it further out, none from this distance of the player on
 *                       ({@link HollowShape#crestShare})
 * @param follow         the region scanned for the ground is centred on the player again when the player is this far
 *                       from its centre
 * @param scanHeight     the region reaches this many whole blocks below and above its centre
 * @param releaseSeconds once the player is out of the hollow, the ground settles over this long
 */
public record HollowParams(double breathPeriod, double breathStart, double breathEnd, double swellScale,
                           double breathRadius, double breathFade, RippleParams ring, double ringEnd,
                           double ringRadius, double ringFade, double crestBoost, double crestRadius, double follow,
                           int scanHeight, double releaseSeconds) {
    /**
     * Patches of about 6 blocks heaving by 0.1 blocks, growing to 0.22 (the build-up breathes 0.15 to 0.4), every
     * 2.6 s; rings of 0.3 blocks growing to 0.45, one crest 2.5 blocks wide (a wavelength of 5) running out at
     * 10 blocks/s for 12 s, so a ring that reaches the player from the far side of the copy (some 35 blocks of
     * straight distance, 40-60 steps of winding way) still has 70% of its height ({@link HollowShape#ringStrength}),
     * and one 48 blocks out 60%; the crest stands up to 70% higher within 4 blocks of the player, so the wave is seen
     * coming from one side and passing. Both are drawn within 9 blocks of the player, fully within 6: the black fog of
     * the hollow hides the rest (about 5.5 blocks; the client draws as far as its fog reaches, {@link #withReach}).
     * The region scanned follows the player every 4 blocks and reaches 14 blocks up and down; the ground settles
     * over 1.5 s.
     */
    private static final HollowParams DEFAULTS = new HollowParams(2.6, 0.1, 0.22, 6, 9, 3,
            new RippleParams(0.3, 10, 5, 1, 12), 0.45, 9, 3, 0.7, 4, 4, 14, 1.5);

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
        requireNonNegative("crestBoost", crestBoost);
        requirePositive("crestRadius", crestRadius);
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

    /**
     * These params with the heaving and the rings drawn within {@code reach} of the player (each fading out over its
     * fade, at most the whole reach), and the region scanned tall enough for them wherever the player is within the
     * follow distance of its centre: {@code ceil(reach + follow) + 1} blocks up and down. The defaults are the reach
     * of 9.
     *
     * @throws IllegalArgumentException if {@code reach} is not finite and {@code > 0}
     */
    public HollowParams withReach(double reach) {
        requirePositive("reach", reach);
        int height = (int) Math.ceil(reach + follow) + 1;
        return new HollowParams(breathPeriod, breathStart, breathEnd, swellScale, reach, Math.min(breathFade, reach),
                ring, ringEnd, reach, Math.min(ringFade, reach), crestBoost, crestRadius, follow, height,
                releaseSeconds);
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
