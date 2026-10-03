package tremor.hollow.level;

import tremor.core.noise.PerlinNoise;

/**
 * Smooth noise for the shapes of the level (the widened cave, the winding tunnel, the uneven closing front), seeded
 * per event: {@link PerlinNoise} with an offset drawn from the seed and a channel, so the shapes of two events differ
 * and the channels of one event do not follow each other. Plain Java.
 */
final class LevelNoise {
    private LevelNoise() {
    }

    /** Noise in [-1, 1] of {@code channel} for {@code seed} at the point. */
    static double at(long seed, int channel, double x, double y, double z) {
        long h = mix(seed * 0x9E3779B97F4A7C15L + channel);
        // Offsets in [0, 256) with fractional parts: the noise is 0 at integer points and repeats every 256.
        double ox = ((h >>> 8) & 0xFFFF) / 256.0;
        double oy = ((h >>> 24) & 0xFFFF) / 256.0;
        double oz = ((h >>> 40) & 0xFFFF) / 256.0;
        return PerlinNoise.noise(x + ox, y + oy, z + oz);
    }

    /** The finalizer of SplitMix64: a well spread hash of {@code z}. */
    static long mix(long z) {
        z = (z ^ (z >>> 33)) * 0xFF51AFD7ED558CCDL;
        z = (z ^ (z >>> 33)) * 0xC4CEB9FE1A85EC53L;
        return z ^ (z >>> 33);
    }
}
