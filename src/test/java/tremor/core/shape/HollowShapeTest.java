package tremor.core.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class HollowShapeTest {
    private static final HollowParams P = HollowParams.defaults();
    private static final AwakeningParams BUILDUP = AwakeningParams.defaults();
    private static final double T = BumpShape.RENDER_THRESHOLD;

    @Test
    void defaultsAndValidation() {
        assertEquals(new HollowParams(2.6, 0.1, 0.22, 6, 12, 6, new RippleParams(0.3, 12, 4, 1, 5), 0.45, 20, 8, 6,
                24, 1.5), P);
        RippleParams ring = P.ring();
        assertThrows(IllegalArgumentException.class,
                () -> new HollowParams(0, 0.1, 0.22, 5, 12, 6, ring, 0.45, 20, 8, 6, 24, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new HollowParams(2.6, -0.1, 0.22, 5, 12, 6, ring, 0.45, 20, 8, 6, 24, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new HollowParams(2.6, 0.1, Double.NaN, 5, 12, 6, ring, 0.45, 20, 8, 6, 24, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new HollowParams(2.6, 0.1, 0.22, 0, 12, 6, ring, 0.45, 20, 8, 6, 24, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new HollowParams(2.6, 0.1, 0.22, 5, 12, 6, null, 0.45, 20, 8, 6, 24, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new HollowParams(2.6, 0.1, 0.22, 5, 12, 6, ring, 0.45, 0, 8, 6, 24, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new HollowParams(2.6, 0.1, 0.22, 5, 12, 6, ring, 0.45, 20, 8, -1, 24, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new HollowParams(2.6, 0.1, 0.22, 5, 12, 6, ring, 0.45, 20, 8, 6, 0, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new HollowParams(2.6, 0.1, 0.22, 5, 12, 6, ring, 0.45, 20, 8, 6, 24, 0));
    }

    @Test
    void theHeavingIsSubtlerThanTheBuildUpsBreathingButVisible() {
        assertTrue(P.breathStart() > T && P.breathStart() < BUILDUP.breathStart());
        assertTrue(P.breathEnd() > P.breathStart() && P.breathEnd() < BUILDUP.breathEnd());
    }

    @Test
    void closenessIsTheShareOfTheWayFromTheEdgeToTheMinimum() {
        // The defaults: the closing runs from the edge at 31 down to 6.
        assertEquals(0, HollowShape.closeness(31, 6, 31));
        assertEquals(0.5, HollowShape.closeness(31, 6, 18.5), 1e-12);
        assertEquals(1, HollowShape.closeness(31, 6, 6), "closed as far as it goes: all of it");
        assertEquals(0, HollowShape.closeness(31, 6, 40), "not below 0");
        assertEquals(1, HollowShape.closeness(31, 6, 3), "not above 1");
        // A small hollow still spans the whole range.
        assertEquals(0, HollowShape.closeness(7, 6, 7));
        assertEquals(0.5, HollowShape.closeness(7, 6, 6.5), 1e-12);
        assertEquals(1, HollowShape.closeness(7, 6, 6));
        // Nothing to close: as the server's schedule, closed from the start.
        assertEquals(1, HollowShape.closeness(7, 7, 7));
        assertEquals(1, HollowShape.closeness(7, 12, 7));
        assertEquals(0, HollowShape.closeness(Double.NaN, 6, 3));
        assertEquals(0, HollowShape.closeness(31, Double.NaN, 3));
        assertEquals(0, HollowShape.closeness(31, 6, Double.NaN));
    }

    @Test
    void theEffectsReachTheirClosedValuesOnceClosedAsFarAsItGoes() {
        double closed = HollowShape.closeness(31, 6, 6);
        assertEquals(P.breathEnd(), HollowShape.breathAmplitude(P, closed), 1e-12);
        assertEquals(P.ringEnd(), HollowShape.nodeRing(P, closed).amplitude(), 1e-12);
    }

    @Test
    void heavesAndRingsGrowAsTheHollowCloses() {
        assertEquals(P.breathStart(), HollowShape.breathAmplitude(P, 0), 1e-12);
        assertEquals(P.breathEnd(), HollowShape.breathAmplitude(P, 1), 1e-12);
        assertEquals((P.breathStart() + P.breathEnd()) / 2, HollowShape.breathAmplitude(P, 0.5), 1e-12);
        assertEquals(P.breathStart(), HollowShape.breathAmplitude(P, Double.NaN), 1e-12);
        assertEquals(P.breathEnd(), HollowShape.breathAmplitude(P, 3), 1e-12);

        assertEquals(P.ring(), HollowShape.nodeRing(P, 0));
        RippleParams closed = HollowShape.nodeRing(P, 1);
        assertEquals(P.ringEnd(), closed.amplitude(), 1e-12);
        assertEquals(P.ring().speed(), closed.speed());
        assertEquals(P.ring().duration(), closed.duration());
        assertEquals(P.ring().wavelength(), closed.wavelength());
        assertEquals(P.ring().amplitude(), HollowShape.nodeRing(P, -1).amplitude(), 1e-12);
    }

    @Test
    void aRingOfTheNodeCrossesMostOfTheCopyStillVisible() {
        // The crest a quarter wavelength behind the front, some 48 blocks out, is still drawn at the start.
        RippleParams ring = HollowShape.nodeRing(P, 0);
        double distance = 48;
        double age = (distance + ring.wavelength() / 4) / ring.speed();
        assertTrue(Ripple.height(ring, distance, age) >= T * 0.95, "crest " + Ripple.height(ring, distance, age));
        assertTrue(Ripple.maxRadius(ring) >= 2 * 30, "runs across a copy of radius 30");
    }

    @Test
    void theGroundHeavesOutOfStepInPlace() {
        Random random = new Random(7);
        double sumUp = 0;
        int samples = 0;
        for (int i = 0; i < 400; i++) {
            double x = random.nextDouble() * 200 - 100, y = random.nextDouble() * 40, z = random.nextDouble() * 200;
            for (double t = 0; t < P.breathPeriod(); t += P.breathPeriod() / 8) {
                double h = HollowShape.heave(P, t, x, y, z);
                assertTrue(h >= 0 && h <= 1, "share " + h);
                // periodic in time
                assertEquals(h, HollowShape.heave(P, t + P.breathPeriod(), x, y, z), 1e-9);
                sumUp += h;
                samples++;
            }
        }
        assertEquals(0.5, sumUp / samples, 0.05, "half up on average");
        // At one moment some patches are up while others are down: the ground walks.
        double low = 1, high = 0;
        for (int x = 0; x < 60; x++) {
            for (int z = 0; z < 60; z++) {
                double h = HollowShape.heave(P, 1.3, x + 0.5, 64.5, z + 0.5);
                low = Math.min(low, h);
                high = Math.max(high, h);
            }
        }
        assertTrue(high - low > 0.75, "out of step: " + low + " .. " + high);
        // ... but neighbours move nearly together, so the patches read as patches.
        double sumStep = 0, maxStep = 0;
        for (int i = 0; i < 2000; i++) {
            double x = random.nextDouble() * 200, z = random.nextDouble() * 200, t = random.nextDouble() * 10;
            double step = Math.abs(HollowShape.heave(P, t, x, 64.5, z) - HollowShape.heave(P, t, x + 1, 64.5, z));
            sumStep += step;
            maxStep = Math.max(maxStep, step);
        }
        assertTrue(sumStep / 2000 < 0.12, "mean step between neighbours " + sumStep / 2000);
        assertTrue(maxStep < 0.65, "largest step between neighbours " + maxStep);
        assertEquals(0, HollowShape.heave(P, Double.NaN, 1, 2, 3));
    }

    @Test
    void nearFadesOutTowardsTheRadius() {
        assertEquals(1, HollowShape.near(12, 6, 0));
        assertEquals(1, HollowShape.near(12, 6, 6));
        assertEquals(0.5, HollowShape.near(12, 6, 9), 1e-12);
        assertEquals(0, HollowShape.near(12, 6, 12));
        assertEquals(0, HollowShape.near(12, 6, 40));
        double last = 1;
        for (double d = 0; d <= 13; d += 0.25) {
            double s = HollowShape.near(12, 6, d);
            assertTrue(s <= last, "monotonic at " + d);
            last = s;
        }
    }

    @Test
    void theGroundSettlesAfterTheEnd() {
        assertEquals(1, HollowShape.release(P, -1));
        assertEquals(1, HollowShape.release(P, 0));
        assertEquals(1, HollowShape.release(P, Double.NaN));
        assertEquals(0.5, HollowShape.release(P, P.releaseSeconds() / 2), 1e-12);
        assertEquals(0, HollowShape.release(P, P.releaseSeconds()));
        assertEquals(0, HollowShape.release(P, 100));
    }
}
