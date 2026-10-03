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
    void theHillShootsUpThenSettlesSlowly() {
        assertEquals(0, AwakeningShape.emergeHill(P, 0));
        assertEquals(0, AwakeningShape.emergeHill(P, -1));
        assertEquals(0, AwakeningShape.emergeHill(P, Double.NaN));
        assertEquals(P.hillHeight(), AwakeningShape.emergeHill(P, R), 1e-12, "at the top after the rise");
        assertEquals(0, AwakeningShape.emergeHill(P, 1), 1e-12, "gone at the end");
        assertEquals(0, AwakeningShape.emergeHill(P, 2), 1e-12);
        // Quickly over the eyes: well before the rise is done.
        assertTrue(AwakeningShape.emergeHill(P, R / 2) > EYES);
        // The player is out (the hill below the eyes) only in the second half of the settling.
        double out = 0;
        for (double t = R; t <= 1; t += 0.001) {
            if (AwakeningShape.emergeHill(P, t) < EYES) {
                out = t;
                break;
            }
        }
        assertTrue(out > R + (1 - R) / 3 && out < R + 2 * (1 - R) / 3, "out at " + out);
        // Up, then down, never above its height.
        double last = 0;
        for (double t = 0; t <= R; t += 0.001) {
            double h = AwakeningShape.emergeHill(P, t);
            assertTrue(h >= last - 1e-12 && h <= P.hillHeight() + 1e-12, "rising at " + t);
            last = h;
        }
        last = AwakeningShape.emergeHill(P, R);
        for (double t = R; t <= 1; t += 0.001) {
            double h = AwakeningShape.emergeHill(P, t);
            assertTrue(h <= last + 1e-12, "settling at " + t);
            last = h;
        }
        // It settles slower than it rose.
        double riseSpeed = AwakeningShape.emergeHillRate(P, R / 2);
        double settleSpeed = AwakeningShape.emergeHillRate(P, R + (1 - R) / 2);
        assertTrue(riseSpeed > 2 * -settleSpeed, riseSpeed + " vs " + settleSpeed);
    }

    @Test
    void theRateIsTheSlopeOfTheHill() {
        double step = 1e-6;
        for (double t = 0.01; t < 1; t += 0.01) {
            if (Math.abs(t - R) < 2 * step) {
                continue;
            }
            double slope = (AwakeningShape.emergeHill(P, t + step) - AwakeningShape.emergeHill(P, t - step))
                    / (2 * step);
            assertEquals(slope, AwakeningShape.emergeHillRate(P, t), 1e-4, "at " + t);
            assertTrue(t < R ? AwakeningShape.emergeHillRate(P, t) > 0 : AwakeningShape.emergeHillRate(P, t) <= 0);
        }
        assertEquals(0, AwakeningShape.emergeHillRate(P, 0));
        assertEquals(0, AwakeningShape.emergeHillRate(P, R), 1e-12, "still at the top");
        assertEquals(0, AwakeningShape.emergeHillRate(P, 1));
        assertEquals(0, AwakeningShape.emergeHillRate(P, Double.NaN));
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
