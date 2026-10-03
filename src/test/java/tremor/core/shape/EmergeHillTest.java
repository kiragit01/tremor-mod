package tremor.core.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import tremor.core.math.Vec3;

/** The hill a victor comes out of (SPEC 9 "Победа"), and the Awakening's field as ground to scan and dust. */
class EmergeHillTest {
    private static final AwakeningParams P = AwakeningParams.defaults();
    /** Eye height of a standing player above the ground. */
    private static final double EYES = 1.62;
    private static final double R = AwakeningShape.EMERGE_RISE;

    @Test
    void theHillRisesToTheHillThatSwallowedThePlayer() {
        assertEquals(0, AwakeningShape.emergeRise(P, 0));
        assertEquals(0, AwakeningShape.emergeRise(P, -1));
        assertEquals(0, AwakeningShape.emergeRise(P, Double.NaN));
        // At the top it is the last frame of the swallowing, and the settling starts from there: no jump either way.
        assertEquals(AwakeningShape.hillPeak(P, 1), AwakeningShape.emergeRise(P, 1), 1e-12);
        assertEquals(AwakeningShape.hillPeak(P, 1), AwakeningShape.emergeRise(P, 2), 1e-12);
        assertEquals(AwakeningShape.hillPeak(P, 1), AwakeningShape.emergeSettle(P, 0), 1e-12);
        assertEquals(AwakeningShape.hillPeak(P, 1), AwakeningShape.emergeSettle(P, -1), 1e-12);
        assertEquals(AwakeningShape.hillPeak(P, 1), AwakeningShape.emergeSettle(P, Double.NaN), 1e-12);
        // The same mound around the same point: the swallowed player's eyes are as deep in it.
        double swallowed = AwakeningShape.hill(AwakeningShape.hillPeak(P, 1), P.hillSigma(), 0.25);
        assertEquals(swallowed, AwakeningShape.hill(AwakeningShape.emergeRise(P, 1), P.hillSigma(), 0.25), 1e-12);
        assertTrue(swallowed > EYES);
        // Shoots up: over the eyes well before the rise is done; never above its height.
        assertTrue(AwakeningShape.emergeRise(P, 0.5) > EYES);
        double last = 0;
        for (double t = 0; t <= 1; t += 0.001) {
            double h = AwakeningShape.emergeRise(P, t);
            assertTrue(h >= last - 1e-12 && h <= P.hillHeight() + 1e-12, "rising at " + t);
            last = h;
        }
    }

    @Test
    void theHillSettlesSlowlyAndLetsThePlayerOutHalfWay() {
        assertEquals(0, AwakeningShape.emergeSettle(P, 1), 1e-12, "gone at the end");
        assertEquals(0, AwakeningShape.emergeSettle(P, 2), 1e-12);
        double last = P.hillHeight();
        double out = Double.NaN;
        for (double t = 0; t <= 1; t += 0.001) {
            double h = AwakeningShape.emergeSettle(P, t);
            assertTrue(h <= last + 1e-12, "settling at " + t);
            last = h;
            // Over the eyes where the renderer measures it under a player at the middle: the block under the feet.
            if (Double.isNaN(out) && AwakeningShape.hill(h, P.hillSigma(), 0.25) < EYES) {
                out = t;
            }
        }
        // The player's head comes out a little before half way: the victor sees it sink for a while first.
        assertTrue(out > 0.4 && out < 0.5, "out at " + out);
        // It settles slower than it rose (at the defaults, the settling takes four times the rise).
        double rise = AwakeningShape.emergeRiseRate(P, 0.5) / R;
        double settle = AwakeningShape.emergeSettleRate(P, 0.5) / (1 - R);
        assertTrue(rise > 2 * -settle, rise + " vs " + settle);
    }

    @Test
    void theRatesAreTheSlopesOfTheHill() {
        double step = 1e-6;
        for (double t = 0.01; t < 1; t += 0.01) {
            double rise = (AwakeningShape.emergeRise(P, t + step) - AwakeningShape.emergeRise(P, t - step)) / (2 * step);
            assertEquals(rise, AwakeningShape.emergeRiseRate(P, t), 1e-4, "rise at " + t);
            assertTrue(AwakeningShape.emergeRiseRate(P, t) > 0);
            double settle = (AwakeningShape.emergeSettle(P, t + step) - AwakeningShape.emergeSettle(P, t - step))
                    / (2 * step);
            assertEquals(settle, AwakeningShape.emergeSettleRate(P, t), 1e-4, "settle at " + t);
            assertTrue(AwakeningShape.emergeSettleRate(P, t) < 0);
        }
        for (double outside : new double[]{0, 1, -1, 2, Double.NaN}) {
            assertEquals(0, AwakeningShape.emergeRiseRate(P, outside), "rise at " + outside);
            assertEquals(0, AwakeningShape.emergeSettleRate(P, outside), "settle at " + outside);
        }
        assertEquals(0, AwakeningShape.emergeRiseRate(P, 1 - 1e-9), 1e-6, "slows to the top");
        assertEquals(0, AwakeningShape.emergeSettleRate(P, 1e-9), 1e-6, "starts to sink without a jerk");
    }

    @Test
    void theAwakeningsFieldAsGround() {
        Vec3 center = new Vec3(10.5, 64, 20.5), focus = new Vec3(14.5, 64, 22.5);
        AwakeningField zone = new AwakeningField(P, center, 30, 0.2, 0.4, focus, 1.5, List.of());
        assertEquals(center, zone.scanCenter());
        assertEquals(zone.reach(), zone.scanRadius());
        assertEquals(P.verticalReach(), zone.scanHeight());
        assertTrue(!zone.scanFollows());
        assertEquals(1, zone.ringShare(1e6, -40, 3));
        assertEquals(zone.peakBreath(11, 60, 25), zone.ahead(11, 60, 25));
        assertTrue(zone.heaves().isEmpty(), "the swallow hill shakes off no dust");

        AwakeningField rising = new AwakeningField(P, focus, 0, 0, 0, focus, 2, 3.5, List.of());
        assertEquals(List.of(new GroundField.Heave(focus, P.hillSigma(), 3.5)), rising.heaves());
        assertEquals(rising.at(focus.x() + 1, focus.y(), focus.z()),
                new AwakeningField(P, focus, 0, 0, 0, focus, 2, List.of()).at(focus.x() + 1, focus.y(), focus.z()));
        assertEquals(Ripple.maxRadius(P.stepRipple()), rising.scanRadius(), 1e-12, "the hill and its ring");
        assertThrows(IllegalArgumentException.class,
                () -> new AwakeningField(P, focus, 0, 0, 0, focus, 2, Double.NaN, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new GroundField.Heave(focus, -1, 0));
    }
}
