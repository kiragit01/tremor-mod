package tremor.core.shape;

/**
 * The ground ripple of an ALERT freeze (SPEC 8: "по земле вокруг идёт мелкая рябь"), see {@link Ripple}. Lengths in
 * blocks, times in seconds.
 *
 * @param amplitude  peak displacement along the surface normal at the start ({@code 0} disables the ripple)
 * @param speed      how fast the rings run outward, blocks per second
 * @param wavelength distance between two crests
 * @param waves      number of whole wavelengths in the train behind the front
 * @param duration   lifetime; the rings fade out by its end
 */
public record RippleParams(double amplitude, double speed, double wavelength, int waves, double duration,
                           double fadePower) {
    /**
     * Small but plainly visible rings: the crests stay above {@link BumpShape#RENDER_THRESHOLD} for 6/7 of the
     * duration, until the first one is 12 blocks out (somewhat less around a bump below its full height,
     * {@link Ripple#visibility}), and are never more than about a third of a block high.
     */
    private static final RippleParams DEFAULTS = new RippleParams(0.35, 5, 3, 2, 3);

    /** @throws IllegalArgumentException if a value is non-finite or out of range */
    public RippleParams {
        if (!(amplitude >= 0) || !Double.isFinite(amplitude)) {
            throw new IllegalArgumentException("amplitude must be finite and >= 0: " + amplitude);
        }
        requirePositive("speed", speed);
        requirePositive("wavelength", wavelength);
        requirePositive("duration", duration);
        if (waves < 1) {
            throw new IllegalArgumentException("waves must be >= 1: " + waves);
        }
        requirePositive("fadePower", fadePower);
    }

    /** Fading linearly over its duration ({@code fadePower} 1). */
    public RippleParams(double amplitude, double speed, double wavelength, int waves, double duration) {
        this(amplitude, speed, wavelength, waves, duration, 1);
    }

    private static final RippleParams PULSE = new RippleParams(0.35, 5, 1.6, 2, 1.1);
    private static final RippleParams ECHO = new RippleParams(0.5, 6, 3, 1, 5.6, 3);

    /**
     * The ring a frozen tremor sends (its echo runs with it, the user: far and strong, so a player sees to jump it):
     * one crest 1.3 high, 8 blocks/s, out to some 33 blocks over 4.2 s, keeping its height nearly to the end.
     */
    public static RippleParams echo() {
        return ECHO;
    }

    /** A resonance arrow's pulse: 5 blocks/s, 1.6 blocks apart, 2 waves, 1.1 s (out to some 5 blocks). */
    public static RippleParams pulse() {
        return PULSE;
    }

    /** 0.35 blocks high, 5 blocks/s, 3 blocks apart, 2 waves, 3 s. */
    public static RippleParams defaults() {
        return DEFAULTS;
    }

    /** @throws IllegalArgumentException if {@code amplitude} is negative or non-finite */
    public RippleParams withAmplitude(double amplitude) {
        return new RippleParams(amplitude, speed, wavelength, waves, duration, fadePower);
    }

    private static void requirePositive(String name, double value) {
        if (!(value > 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite and > 0: " + value);
        }
    }
}
