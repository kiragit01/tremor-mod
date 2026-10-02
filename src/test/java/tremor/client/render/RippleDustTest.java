package tremor.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tremor.core.math.Vec3;
import tremor.core.shape.BumpFrame;
import tremor.core.shape.Ripple;
import tremor.core.shape.RippleParams;

class RippleDustTest {
    private static final RippleParams RIPPLE = RippleParams.defaults();
    private static final double[] UNIFORMS = {0, 0.25, 0.5, 0.75, 0.999999};
    /** Age at which the leading crest has left the hole under the bump entirely (it is a quarter wavelength back). */
    private static final double PAST_HOLE = (Ripple.HOLE_EDGE + RIPPLE.wavelength() / 4) / RIPPLE.speed();

    @Test
    void frontRunsOutAtTheSpeedOfTheRipple() {
        assertEquals(0, RippleDust.front(RIPPLE, 0));
        assertEquals(RIPPLE.speed() * 1.3, RippleDust.front(RIPPLE, 1.3), 1e-12);
    }

    @Test
    void noDustWhileTheLeadingCrestIsUnderTheBumpOrOutsideTheRipple() {
        double leaves = (Ripple.HOLE_RADIUS + RIPPLE.wavelength() / 4) / RIPPLE.speed();
        double[] ages = {Double.NaN, -0.5, 0, 0.5 * leaves, leaves, RIPPLE.duration(), RIPPLE.duration() + 1};
        for (double age : ages) {
            for (double u : UNIFORMS) {
                assertEquals(0, RippleDust.count(RIPPLE, age, 1, u), "age " + age + ", u " + u);
            }
        }
        assertTrue(RippleDust.rate(RIPPLE, leaves + 0.05) > 0, "dust once the crest is out from under the bump");
    }

    @Test
    void theLeadingCrestIsTheHighestPointOfTheRipple() {
        for (double age = PAST_HOLE; age < RIPPLE.duration(); age += 0.05) {
            double crest = RippleDust.crest(RIPPLE, age);
            assertEquals(RIPPLE.amplitude() * (1 - age / RIPPLE.duration()), crest, 1e-12, "age " + age);
            double highest = 0;
            for (double d = 0; d <= Ripple.maxRadius(RIPPLE); d += 0.01) {
                highest = Math.max(highest, Ripple.height(RIPPLE, d, age));
            }
            assertTrue(highest <= crest + 1e-12, "age " + age);
            assertEquals(crest, highest, 1e-3 * RIPPLE.amplitude(), "age " + age);
        }
    }

    @Test
    void rateIsTheFrontCircleTimesTheHeightOfTheLeadingCrest() {
        for (double age = PAST_HOLE; age < RIPPLE.duration(); age += 0.05) {
            double circle = 2 * Math.PI * RIPPLE.speed() * age;
            double crest = RIPPLE.amplitude() * (1 - age / RIPPLE.duration());
            assertEquals(RippleDust.DENSITY * circle * crest, RippleDust.rate(RIPPLE, age), 1e-9, "age " + age);
        }
    }

    @Test
    void dustFadesWithTheVisibilityOfTheRipple() {
        double age = 1.5;
        double full = RippleDust.rate(RIPPLE, age);
        for (double share : new double[] {0.1, 0.5, 0.9}) {
            RippleParams seen = RIPPLE.withAmplitude(RIPPLE.amplitude() * Ripple.visibility(2 * share, 2));
            assertEquals(full * share, RippleDust.rate(seen, age), 1e-9);
        }
        RippleParams dived = RIPPLE.withAmplitude(RIPPLE.amplitude() * Ripple.visibility(-0.3, 2));
        for (double u : UNIFORMS) {
            assertEquals(0, RippleDust.count(dived, age, 1, u));
        }
    }

    @Test
    void countRoundsTheRateAtRandomAndIsRightOnAverage() {
        double age = 1.2;
        double rate = RippleDust.rate(RIPPLE, age);
        assertTrue(rate > 1 && rate < RippleDust.MAX_PER_TICK, "rate " + rate);
        for (double share : new double[] {1, 0.5}) {
            double expected = rate * share;
            assertEquals(expected, average(RIPPLE, age, share), 2e-3, "share " + share);
            for (double u : UNIFORMS) {
                int count = RippleDust.count(RIPPLE, age, share, u);
                assertTrue(count == Math.floor(expected) || count == Math.ceil(expected), "count " + count);
            }
        }
        for (double u : UNIFORMS) {
            assertEquals(0, RippleDust.count(RIPPLE, age, 0, u), "minimal particles: none");
        }
    }

    @Test
    void neverMoreThanTheCapPerTick() {
        RippleParams high = RIPPLE.withAmplitude(1.0); // the highest the client config allows
        boolean capped = false;
        for (double age = 0; age < RIPPLE.duration(); age += 0.01) {
            for (double u : UNIFORMS) {
                int count = RippleDust.count(high, age, 1, u);
                assertTrue(count <= RippleDust.MAX_PER_TICK, "age " + age + ": " + count);
                capped |= count == RippleDust.MAX_PER_TICK;
                assertTrue(RippleDust.count(high, age, 0.5, u) <= RippleDust.MAX_PER_TICK / 2, "age " + age);
            }
        }
        assertTrue(capped, "a ripple one block high should reach the cap");
    }

    @Test
    void theDefaultRippleKicksUpAFewParticlesPerTickBelowTheCap() {
        double peak = 0;
        for (double age = 0; age < RIPPLE.duration(); age += 0.01) {
            peak = Math.max(peak, RippleDust.rate(RIPPLE, age));
        }
        assertTrue(peak >= 6 && peak < RippleDust.MAX_PER_TICK, "peak " + peak);
    }

    @Test
    void theFrontIsABandOneBlockWideAroundTheCircle() {
        assertTrue(RippleDust.onFront(7.5, 7.5));
        assertTrue(RippleDust.onFront(7.0, 7.5));
        assertTrue(RippleDust.onFront(8.0, 7.5));
        assertFalse(RippleDust.onFront(6.99, 7.5));
        assertFalse(RippleDust.onFront(8.01, 7.5));
        assertFalse(RippleDust.onFront(Double.NaN, 7.5));
    }

    @Test
    void onAFloorTheFrontHoldsAboutOneVoxelPerBlockOfTheCircle() {
        BumpFrame floor = BumpFrame.of(new Vec3(10.25, 64, -7.5), Vec3.UNIT_Y, Vec3.UNIT_X);
        for (double front : new double[] {3, 7.5, 12}) {
            int on = 0;
            for (int x = -10; x <= 30; x++) {
                for (int z = -28; z <= 12; z++) {
                    // the floor: the layer of voxels just under the bump centre
                    if (RippleDust.onFront(HeightField.tangentDistance(floor, x + 0.5, 63.5, z + 0.5), front)) {
                        on++;
                    }
                }
            }
            double circle = 2 * Math.PI * front;
            assertEquals(circle, on, 0.15 * circle, "front " + front);
        }
    }

    /** Mean count over evenly spread random numbers. */
    private static double average(RippleParams ripple, double age, double share) {
        int steps = 1000;
        double sum = 0;
        for (int i = 0; i < steps; i++) {
            sum += RippleDust.count(ripple, age, share, (i + 0.5) / steps);
        }
        return sum / steps;
    }
}
