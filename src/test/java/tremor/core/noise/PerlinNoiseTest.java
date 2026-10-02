package tremor.core.noise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class PerlinNoiseTest {
    /** Ken Perlin's reference implementation gives 0.13691995878400012 here; ours is that divided by the bound. */
    @Test
    void matchesReferenceImplementation() {
        double raw = 0.13691995878400012;
        double ours = PerlinNoise.noise(3.14, 42, 7);
        assertEquals(raw, ours * 1.0363539, 1e-12);
    }

    @Test
    void deterministic() {
        Random random = new Random(7);
        for (int i = 0; i < 1000; i++) {
            double x = random.nextDouble() * 100 - 50, y = random.nextDouble() * 100 - 50, z = random.nextDouble() * 100;
            assertEquals(PerlinNoise.noise(x, y, z), PerlinNoise.noise(x, y, z));
        }
    }

    @Test
    void zeroAtLatticePoints() {
        for (int x = -20; x <= 20; x += 3) {
            for (int y = -20; y <= 20; y += 4) {
                for (int z = -300; z <= 300; z += 37) {
                    assertEquals(0.0, PerlinNoise.noise(x, y, z), 0.0, "at " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void staysWithinUnitRange() {
        Random random = new Random(1234);
        double min = 0, max = 0;
        for (int i = 0; i < 2_000_000; i++) {
            double n = PerlinNoise.noise(random.nextDouble() * 512 - 256, random.nextDouble() * 512 - 256,
                    random.nextDouble() * 512 - 256);
            min = Math.min(min, n);
            max = Math.max(max, n);
        }
        assertTrue(min >= -1 && max <= 1, "range [" + min + ", " + max + "]");
        // Uses most of the range, i.e. the scaling is not overly conservative.
        assertTrue(min < -0.8 && max > 0.8, "range [" + min + ", " + max + "]");
    }

    @Test
    void continuous() {
        Random random = new Random(99);
        double step = 1e-4;
        for (int i = 0; i < 100_000; i++) {
            double x = random.nextDouble() * 64, y = random.nextDouble() * 64, z = random.nextDouble() * 64;
            double n = PerlinNoise.noise(x, y, z);
            // The gradient of improved noise is bounded by a small constant; this is a loose Lipschitz check.
            assertTrue(Math.abs(PerlinNoise.noise(x + step, y, z) - n) < 10 * step);
            assertTrue(Math.abs(PerlinNoise.noise(x, y + step, z) - n) < 10 * step);
            assertTrue(Math.abs(PerlinNoise.noise(x, y, z + step) - n) < 10 * step);
        }
    }

    @Test
    void continuousAcrossCellBoundaries() {
        for (int c = -5; c <= 5; c++) {
            double below = PerlinNoise.noise(c - 1e-9, 0.37, 0.61);
            double above = PerlinNoise.noise(c + 1e-9, 0.37, 0.61);
            assertEquals(below, above, 1e-7, "across x = " + c);
        }
    }

    @Test
    void notConstant() {
        Random random = new Random(5);
        double sum = 0, sumSq = 0;
        int count = 10_000;
        for (int i = 0; i < count; i++) {
            double n = PerlinNoise.noise(random.nextDouble() * 100, random.nextDouble() * 100, random.nextDouble() * 100);
            sum += n;
            sumSq += n * n;
        }
        double mean = sum / count;
        double variance = sumSq / count - mean * mean;
        assertTrue(variance > 0.01, "variance " + variance);
        assertTrue(Math.abs(mean) < 0.05, "mean " + mean);
    }

    @Test
    void periodicWith256() {
        assertEquals(PerlinNoise.noise(1.3, 2.7, -4.1), PerlinNoise.noise(1.3 + 256, 2.7 - 256, -4.1 + 512), 1e-9);
    }
}
