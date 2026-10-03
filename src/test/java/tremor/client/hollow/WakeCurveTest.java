package tremor.client.hollow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tremor.core.shape.HollowParams;
import tremor.core.shape.Ripple;
import tremor.core.shape.RippleParams;

class WakeCurveTest {
    private static final RippleParams RING = HollowParams.defaults().ring();
    private static final double TICK = 0.05;

    @Test
    void theLeadingCrestRunsAQuarterWavelengthBehindTheFront() {
        assertEquals(-RING.wavelength() / 4, WakeCurve.crest(RING, 0), 1e-12);
        assertEquals(RING.speed() * 2 - RING.wavelength() / 4, WakeCurve.crest(RING, 2), 1e-12);
        // It is the highest point of the ring there.
        double age = 2.5, crest = WakeCurve.crest(RING, age);
        assertTrue(Ripple.height(RING, crest, age) >= Ripple.height(RING, crest + 0.3, age));
        assertTrue(Ripple.height(RING, crest, age) >= Ripple.height(RING, crest - 0.3, age));
    }

    @Test
    void aRingRunsUnderThePlayerOnceFromAcrossTheCopy() {
        for (double distance : new double[] {3, 12, 35, 48}) {
            int passes = 0;
            for (int tick = 0; tick * TICK < RING.duration() + 1; tick++) {
                double age = tick * TICK;
                if (WakeCurve.passes(RING, age - TICK, age, distance)) {
                    passes++;
                    assertTrue(WakeCurve.crest(RING, age) >= distance);
                    assertTrue(WakeCurve.crest(RING, age) - distance <= RING.speed() * TICK + 1e-9);
                }
            }
            assertEquals(1, passes, "passes at " + distance);
        }
        // Not once the ring has run out, nor before the beat.
        assertFalse(WakeCurve.passes(RING, RING.duration() - TICK, RING.duration(), Ripple.maxRadius(RING)));
        assertFalse(WakeCurve.passes(RING, -2 * TICK, -TICK, 0.1));
    }

    @Test
    void onlyTheCapOfTheCrestWithinSightShows() {
        double sight = 5.5;
        assertEquals(sight, WakeCurve.capRadius(sight, 20, 20), 1e-12, "running through the player: widest");
        assertEquals(Math.sqrt(sight * sight - 9), WakeCurve.capRadius(sight, 20, 17), 1e-12);
        assertEquals(WakeCurve.capRadius(sight, 20, 17), WakeCurve.capRadius(sight, 20, 23), 1e-12, "coming as going");
        assertEquals(0, WakeCurve.capRadius(sight, 20, 14.5), "not yet in sight");
        assertEquals(0, WakeCurve.capRadius(sight, 20, 26), "gone");
        assertEquals(0, WakeCurve.capRadius(sight, 2, -0.5), "not started");
        assertEquals(0, WakeCurve.capRadius(sight, Double.NaN, 3));
    }

    @Test
    void theDustFollowsTheRingAndTheSetting() {
        double sight = 5.5, rate = WakeCurve.FLOOR_DUST_PER_TICK;
        // Averaged over the random rounding: the expected count.
        assertEquals(rate, average(rate, 1, sight, sight, 1), 0.01);
        assertEquals(rate * 0.5, average(rate, 0.5, sight, sight, 1), 0.01, "a weaker ring");
        assertEquals(rate * 0.5, average(rate, 1, sight / 2, sight, 1), 0.01, "a narrow cap or a short arc");
        assertEquals(rate * 0.5, average(rate, 1, sight, sight, 0.5), 0.01, "decreased particles");
        assertEquals(WakeCurve.SURFACE_DUST_PER_TICK, average(WakeCurve.SURFACE_DUST_PER_TICK, 1, sight, sight, 1),
                0.01, "the ceilings and the walls");
        assertEquals(0, WakeCurve.dustCount(rate, 1, sight, sight, 0, 0.99), "minimal particles");
        assertEquals(0, WakeCurve.dustCount(rate, 1, 0, sight, 1, 0.99), "nothing in sight");
        assertEquals(0, WakeCurve.dustCount(rate, Double.NaN, sight, sight, 1, 0.99));
        assertTrue(WakeCurve.dustCount(rate, 3, 2 * sight, sight, 1, 0.99) <= rate, "capped");
        assertEquals(WakeCurve.PUFF, WakeCurve.puffCount(1, 1, 0), 1e-12);
        assertEquals(0, WakeCurve.puffCount(1, 0, 0.99));
        assertTrue(WakeCurve.puffCount(0.3, 1, 0.99) <= Math.ceil(WakeCurve.PUFF * 0.3));
    }

    @Test
    void onTheFloorTheCrestIsTheCircleWhereItsSphereCutsThePlaneOfTheFeet() {
        assertEquals(20, WakeCurve.floorRadius(20, 0), 1e-12, "level with the node");
        assertEquals(16, WakeCurve.floorRadius(20, -12), 1e-12, "the node 12 blocks above the feet");
        assertEquals(16, WakeCurve.floorRadius(20, 12), 1e-12, "...or below them");
        assertEquals(0, WakeCurve.floorRadius(10, 12), "the sphere does not reach the floor yet");
        assertEquals(0, WakeCurve.floorRadius(-1, 0), "not started");
        assertEquals(0, WakeCurve.floorRadius(Double.NaN, 0));
        // With the node 19 blocks below, the crest runs over the floor further out than at the height of the chest
        // (0.9 blocks higher): the floor's dust goes where the raised floor is, ahead of what lies above it.
        double crest = 30;
        assertTrue(WakeCurve.floorRadius(crest, 19) > WakeCurve.floorRadius(crest, 19.9));
    }

    @Test
    void onlyTheArcOfTheFloorCircleWithinSightShows() {
        double sight = 5.5;
        // Running through the player: the arc spans about the whole sight on either side.
        double half = WakeCurve.arcHalfAngle(26, 26, sight);
        assertEquals(sight, 2 * 26 * Math.sin(half / 2), 1e-9, "its ends are at the edge of the sight");
        assertTrue(Math.abs(26 * half - sight) < 0.05, "half its length is about the sight");
        // Narrower while it comes and goes, none out of sight.
        assertTrue(WakeCurve.arcHalfAngle(23, 26, sight) > 0 && WakeCurve.arcHalfAngle(23, 26, sight) < half);
        assertTrue(WakeCurve.arcHalfAngle(29, 26, sight) > 0 && 29 * WakeCurve.arcHalfAngle(29, 26, sight) < 26 * half);
        assertEquals(0, WakeCurve.arcHalfAngle(20, 26, sight));
        assertEquals(0, WakeCurve.arcHalfAngle(32, 26, sight));
        // The whole circle when it is small around a player near the node; right above or below it: all or none.
        assertEquals(Math.PI, WakeCurve.arcHalfAngle(2, 1, sight), 1e-12);
        assertEquals(Math.PI, WakeCurve.arcHalfAngle(4, 0, sight), 1e-12);
        assertEquals(0, WakeCurve.arcHalfAngle(8, 0, sight));
        assertEquals(0, WakeCurve.arcHalfAngle(0, 3, sight));
        assertEquals(0, WakeCurve.arcHalfAngle(3, Double.NaN, sight));
    }

    private static double average(double rate, double strength, double extent, double sight, double share) {
        int n = 1000, sum = 0;
        for (int i = 0; i < n; i++) {
            sum += WakeCurve.dustCount(rate, strength, extent, sight, share, (i + 0.5) / n);
        }
        return (double) sum / n;
    }
}
