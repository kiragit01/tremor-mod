package tremor.core.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RippleTest {
    private static final RippleParams P = RippleParams.defaults();

    private static double envelope(double age) {
        return P.amplitude() * (1 - age / P.duration());
    }

    /** Height of the first crest, a quarter wavelength behind the front, at {@code age}. */
    private static double firstCrest(RippleParams p, double age) {
        return Ripple.height(p, p.speed() * age - p.wavelength() / 4, age);
    }

    @Test
    void defaultsAndValidation() {
        assertEquals(new RippleParams(0.35, 5, 3, 2, 3), P);
        assertEquals(15, Ripple.maxRadius(P), 1e-12);
        assertEquals(new RippleParams(0.1, 5, 3, 2, 3), P.withAmplitude(0.1));
        assertThrows(IllegalArgumentException.class, () -> P.withAmplitude(-0.1));
        assertThrows(IllegalArgumentException.class, () -> new RippleParams(-0.1, 5, 3, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> new RippleParams(Double.NaN, 5, 3, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> new RippleParams(0.35, 0, 3, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> new RippleParams(0.35, 5, 0, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> new RippleParams(0.35, 5, 3, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> new RippleParams(0.35, 5, 3, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new RippleParams(0.35, Double.POSITIVE_INFINITY, 3, 2, 3));
    }

    @Test
    void activeDuringTheDuration() {
        assertFalse(Ripple.active(P, -0.01));
        assertTrue(Ripple.active(P, 0));
        assertTrue(Ripple.active(P, 2.99));
        assertFalse(Ripple.active(P, 3));
        assertFalse(Ripple.active(P, Double.NaN));
        assertEquals(0, Ripple.height(P, 3, -0.1));
        assertEquals(0, Ripple.height(P, 3, 3));
        assertEquals(0, Ripple.height(P, 3, 10));
    }

    @Test
    void zeroAheadOfTheFrontAndBehindTheTrain() {
        double age = 1.2; // front at 6, train back to 6 - 6 = 0
        assertEquals(0, Ripple.height(P, 6, age));
        assertEquals(0, Ripple.height(P, 6.01, age));
        assertEquals(0, Ripple.height(P, 20, age));
        age = 2; // front at 10, train back to 4
        assertEquals(0, Ripple.height(P, 4, age));
        assertEquals(0, Ripple.height(P, 3.99, age));
        assertEquals(0, Ripple.height(P, 3, age));
        assertNotEquals(0, Ripple.height(P, 4.1, age));
        assertNotEquals(0, Ripple.height(P, 9.9, age));
        // Never beyond the farthest radius.
        for (double t = 0; t < 3; t += 0.01) {
            assertEquals(0, Ripple.height(P, Ripple.maxRadius(P), t));
        }
    }

    @Test
    void firstCrestJustBehindTheFrontAndTheWaveShape() {
        double age = 1.2, front = 6;
        assertEquals(envelope(age), Ripple.height(P, front - P.wavelength() / 4, age), 1e-12, "crest");
        assertEquals(-envelope(age), Ripple.height(P, front - 3 * P.wavelength() / 4, age), 1e-12, "trough");
        assertEquals(envelope(age), Ripple.height(P, front - 5 * P.wavelength() / 4, age), 1e-12, "second crest");
        assertEquals(0, Ripple.height(P, front - P.wavelength() / 2, age), 1e-12, "node");
        // The rings run outward at the speed: the first crest moves with the front.
        for (double t = 0.6; t < 2.95; t += 0.1) {
            assertEquals(envelope(t), firstCrest(P, t), 1e-12, "age " + t);
        }
    }

    @Test
    void envelopeFadesLinearlyToZero() {
        double previous = Double.POSITIVE_INFINITY;
        for (double age = 0.6; age < 3; age += 0.05) {
            double crest = firstCrest(P, age);
            assertTrue(crest < previous, "fading at " + age);
            assertEquals(envelope(age), crest, 1e-12);
            previous = crest;
        }
        assertEquals(P.amplitude() / 2, firstCrest(P, P.duration() / 2), 1e-12, "half height halfway");
        // Continuous at the end: the last crests are as good as flat.
        assertTrue(firstCrest(P, P.duration() - 1e-6) < 1e-6);
    }

    @Test
    void defaultRingsStayVisibleFarOut() {
        // SPEC 8 "мелкая рябь", but visible: the crests stay above the render threshold until 6/7 of the duration...
        double fading = P.duration() * 6 / 7;
        assertTrue(firstCrest(P, fading - 1e-3) > BumpShape.RENDER_THRESHOLD);
        assertTrue(P.speed() * fading - P.wavelength() / 4 > 12, "...when the first crest is 12 blocks out");
        for (double d = 0; d < Ripple.maxRadius(P); d += 0.05) {
            assertTrue(Ripple.height(P, d, fading + 1e-3) < BumpShape.RENDER_THRESHOLD, "then gone, at " + d);
        }
        // ...and they stay small.
        for (double t = 0; t < P.duration(); t += 0.02) {
            for (double d = 0; d < Ripple.maxRadius(P); d += 0.1) {
                assertTrue(Math.abs(Ripple.height(P, d, t)) <= 0.35);
            }
        }
    }

    @Test
    void continuousAtTheFrontAndAtTheTail() {
        double age = 2, front = 10;
        double tail = front - P.waves() * P.wavelength();
        for (double eps : new double[]{1e-3, 1e-5, 1e-7}) {
            assertEquals(0, Ripple.height(P, front - eps, age), 3 * eps);
            assertEquals(0, Ripple.height(P, front + eps, age));
            assertEquals(0, Ripple.height(P, tail + eps, age), 3 * eps);
            assertEquals(0, Ripple.height(P, tail - eps, age));
        }
        // No jumps anywhere along a fine sweep (the steepest slope is that of the sine at full height).
        double step = 1e-3, maxSlope = 2 * Math.PI / P.wavelength() * P.amplitude() * 1.01;
        double last = Ripple.height(P, 0, age);
        for (int i = 1; i < 20_000; i++) {
            double d = i * step;
            double h = Ripple.height(P, d, age);
            assertTrue(Math.abs(h - last) <= maxSlope * step, "jump at " + d);
            last = h;
        }
    }

    @Test
    void stillGroundUnderTheBump() {
        // Early on the train covers the centre: zero within 1 block, rising smoothly to full height at 2 blocks.
        double age = 0.65; // front at 3.25, train back to -2.75
        assertEquals(0, Ripple.height(P, 0, age));
        assertEquals(0, Ripple.height(P, 0.5, age));
        assertEquals(0, Ripple.height(P, Ripple.HOLE_RADIUS, age));
        assertEquals(envelope(age) * Math.sin(2 * Math.PI * 1.25 / 3), Ripple.height(P, Ripple.HOLE_EDGE, age), 1e-12);
        assertEquals(0.5 * envelope(age) * Math.sin(2 * Math.PI * 1.75 / 3), Ripple.height(P, 1.5, age), 1e-12);
        // Smoothstep: a zero slope where it starts.
        assertEquals(0, Ripple.height(P, 1.001, age), 1e-5);
    }

    @Test
    void zeroAmplitudeDisables() {
        RippleParams off = P.withAmplitude(0);
        for (double d = 0; d < 16; d += 0.25) {
            assertEquals(0, Ripple.height(off, d, 1.2), 0);
        }
    }

    @Test
    void visibilityFollowsTheBump() {
        assertEquals(1, Ripple.visibility(2, 2));
        assertEquals(0.5, Ripple.visibility(1, 2), 1e-12);
        assertEquals(0.85, Ripple.visibility(1.7, 2), 1e-12);
        // Never above the configured height around a bump raised above its full size (a hunter's).
        assertEquals(1, Ripple.visibility(2.5, 2));
        // Gone with the bump: sunk in a dive, inverted, or without a shape.
        assertEquals(0, Ripple.visibility(0, 2));
        assertEquals(0, Ripple.visibility(-1, 2));
        assertEquals(0, Ripple.visibility(1, 0));
        assertEquals(0, Ripple.visibility(1, -2));
        assertEquals(0, Ripple.visibility(Double.NaN, 2));
        assertEquals(0, Ripple.visibility(1, Double.NaN));
        // Scaling the amplitude scales the whole ripple.
        double v = Ripple.visibility(0.5, 2);
        RippleParams faded = P.withAmplitude(P.amplitude() * v);
        for (double d = 0; d < 15; d += 0.37) {
            assertEquals(v * Ripple.height(P, d, 1.3), Ripple.height(faded, d, 1.3), 1e-12);
        }
    }
}
