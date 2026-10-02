package tremor.core.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AwakeningShapeTest {
    private static final AwakeningParams P = AwakeningParams.defaults();
    private static final double T = BumpShape.RENDER_THRESHOLD;
    /** Eye height of a standing player above the ground. */
    private static final double EYES = 1.62;

    @Test
    void defaultsAndValidation() {
        assertEquals(new AwakeningParams(5, 0.15, 0.4, 4, 24, 6, 3, 2.5, new RippleParams(0.2, 8, 3, 1, 1.5), 4, 1.5),
                P);
        assertThrows(IllegalArgumentException.class,
                () -> new AwakeningParams(0, 0.15, 0.4, 4, 24, 6, 3, 2.5, P.stepRipple(), 3, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new AwakeningParams(5, -0.1, 0.4, 4, 24, 6, 3, 2.5, P.stepRipple(), 3, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new AwakeningParams(5, 0.15, Double.NaN, 4, 24, 6, 3, 2.5, P.stepRipple(), 3, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new AwakeningParams(5, 0.15, 0.4, 0, 24, 6, 3, 2.5, P.stepRipple(), 3, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new AwakeningParams(5, 0.15, 0.4, 4, 0, 6, 3, 2.5, P.stepRipple(), 3, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new AwakeningParams(5, 0.15, 0.4, 4, 24, 6, 3, 0, P.stepRipple(), 3, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new AwakeningParams(5, 0.15, 0.4, 4, 24, 6, 3, 2.5, null, 3, 1.5));
        assertThrows(IllegalArgumentException.class,
                () -> new AwakeningParams(5, 0.15, 0.4, 4, 24, 6, 3, 2.5, P.stepRipple(), 3, 0));
    }

    @Test
    void smoothstep() {
        assertEquals(0, AwakeningShape.smoothstep(-1));
        assertEquals(0, AwakeningShape.smoothstep(0));
        assertEquals(0.5, AwakeningShape.smoothstep(0.5), 1e-12);
        assertEquals(1, AwakeningShape.smoothstep(1));
        assertEquals(1, AwakeningShape.smoothstep(7));
        assertEquals(0, AwakeningShape.smoothstep(Double.NaN));
        double last = 0;
        for (double s = 0; s <= 1; s += 0.01) {
            double v = AwakeningShape.smoothstep(s);
            assertTrue(v >= last, "monotonic at " + s);
            last = v;
        }
        // Flat at both ends.
        assertEquals(0, AwakeningShape.smoothstep(1e-4), 1e-7);
        assertEquals(1, AwakeningShape.smoothstep(1 - 1e-4), 1e-7);
    }

    @Test
    void breathRisesFromTheGroundAndFallsBackToIt() {
        double a = 0.3, period = 5;
        assertEquals(0, AwakeningShape.breath(a, period, 0));
        assertEquals(a, AwakeningShape.breath(a, period, period / 2), 1e-12, "peak half a period in");
        assertEquals(0, AwakeningShape.breath(a, period, period), 1e-12, "back in place after a whole one");
        assertEquals(a / 2, AwakeningShape.breath(a, period, period / 4), 1e-12);
        for (double t = 0; t < 4 * period; t += 0.01) {
            double h = AwakeningShape.breath(a, period, t);
            assertTrue(h >= 0 && h <= a + 1e-12, "between the ground and the peak at " + t);
            assertEquals(h, AwakeningShape.breath(a, period, t + period), 1e-9, "periodic at " + t);
        }
        // Flat before the start, and it starts without a jump.
        assertEquals(0, AwakeningShape.breath(a, period, -1));
        assertEquals(0, AwakeningShape.breath(a, period, Double.NaN));
        assertTrue(AwakeningShape.breath(a, period, 0.05) < 1e-3);
    }

    @Test
    void breathGrowsOverTheBuildUp() {
        assertEquals(P.breathStart(), AwakeningShape.breathAmplitude(P, 0));
        assertEquals(P.breathEnd(), AwakeningShape.breathAmplitude(P, 1), 1e-12);
        assertEquals((P.breathStart() + P.breathEnd()) / 2, AwakeningShape.breathAmplitude(P, 0.5), 1e-12);
        assertEquals(P.breathStart(), AwakeningShape.breathAmplitude(P, -0.5));
        assertEquals(P.breathEnd(), AwakeningShape.breathAmplitude(P, 1.5), 1e-12);
        assertEquals(P.breathStart(), AwakeningShape.breathAmplitude(P, Double.NaN));
        // Visible from the first breath on.
        assertTrue(P.breathStart() > T);
    }

    @Test
    void breathingFadesOutInsideTheEdgeAndAtTheVerticalReach() {
        double r = 30;
        assertEquals(1, AwakeningShape.zoneFade(P, r, 0, 0));
        assertEquals(1, AwakeningShape.zoneFade(P, r, r - P.edgeWidth(), 0));
        assertEquals(0.5, AwakeningShape.zoneFade(P, r, r - P.edgeWidth() / 2, 0), 1e-12);
        assertEquals(0, AwakeningShape.zoneFade(P, r, r, 0), "still at the edge the player has to cross");
        assertEquals(0, AwakeningShape.zoneFade(P, r, r + 10, 0));
        double last = 1;
        for (double d = 0; d <= r + 1; d += 0.05) {
            double f = AwakeningShape.zoneFade(P, r, d, 0);
            assertTrue(f <= last, "fading outward at " + d);
            last = f;
        }
        int reach = P.verticalReach();
        assertEquals(1, AwakeningShape.zoneFade(P, r, 0, reach - P.verticalFade()));
        assertEquals(1, AwakeningShape.zoneFade(P, r, 0, -(reach - P.verticalFade())));
        assertEquals(0.5, AwakeningShape.zoneFade(P, r, 0, reach - P.verticalFade() / 2), 1e-12);
        assertEquals(0, AwakeningShape.zoneFade(P, r, 0, reach));
        assertEquals(0, AwakeningShape.zoneFade(P, r, 0, -reach - 3));
        assertEquals(0.25, AwakeningShape.zoneFade(P, r, r - P.edgeWidth() / 2, reach - P.verticalFade() / 2), 1e-12);
    }

    @Test
    void hillRisesFasterAndFasterOverTheEyes() {
        assertEquals(0, AwakeningShape.hillPeak(P, 0));
        assertEquals(P.hillHeight() / 4, AwakeningShape.hillPeak(P, 0.5), 1e-12);
        assertEquals(P.hillHeight(), AwakeningShape.hillPeak(P, 1), 1e-12);
        assertEquals(0, AwakeningShape.hillPeak(P, -1));
        assertEquals(0, AwakeningShape.hillPeak(P, Double.NaN));
        assertEquals(P.hillHeight(), AwakeningShape.hillPeak(P, 2), 1e-12);
        // Over the eyes of the swallowed player at the end, but only in about the last quarter of the swallowing.
        assertTrue(P.hillHeight() > EYES);
        assertTrue(AwakeningShape.hillPeak(P, 0.7) < EYES);
        assertTrue(AwakeningShape.hillPeak(P, 0.8) > EYES);
    }

    @Test
    void hillIsARoundMound() {
        double peak = 2.5, sigma = 2;
        assertEquals(peak, AwakeningShape.hill(peak, sigma, 0));
        assertEquals(peak / Math.E, AwakeningShape.hill(peak, sigma, sigma * sigma), 1e-12);
        // About 10 blocks across at full height for the defaults.
        double edge = P.hillSigma() * Math.sqrt(Math.log(P.hillHeight() / T));
        assertTrue(edge > 4.5 && edge < 5.5, "radius " + edge);
    }

    @Test
    void stepRingsAreAsHighAsTheStepIsStrong() {
        RippleParams walk = AwakeningShape.stepRipple(P, 1);
        assertEquals(P.stepRipple(), walk);
        RippleParams sprint = AwakeningShape.stepRipple(P, 2.5);
        assertEquals(P.stepRipple().amplitude() * 2.5, sprint.amplitude(), 1e-12);
        assertEquals(walk.withAmplitude(sprint.amplitude()), sprint, "only the height changes");
        assertEquals(P.stepRipple().amplitude() * P.maxStrength(), AwakeningShape.stepRipple(P, 50).amplitude(),
                1e-12);
        assertEquals(0, AwakeningShape.stepRipple(P, -1).amplitude());
        assertEquals(0, AwakeningShape.stepRipple(P, Double.NaN).amplitude());
        // A walking step's crest stays visible for about 8 blocks; a stronger one further, never beyond the reach.
        assertTrue(visibleReach(walk) > 7.5 && visibleReach(walk) < 9, "walk " + visibleReach(walk));
        assertTrue(visibleReach(sprint) > visibleReach(walk) + 1);
        assertTrue(visibleReach(AwakeningShape.stepRipple(P, 50)) < Ripple.maxRadius(P.stepRipple()));
    }

    /** Farthest distance at which the leading crest of the ring is still at least the render threshold high. */
    private static double visibleReach(RippleParams p) {
        double reach = 0;
        for (double age = 0; age < p.duration(); age += 1e-3) {
            double crest = p.speed() * age - p.wavelength() / 4;
            if (Ripple.height(p, crest, age) >= T) {
                reach = crest;
            }
        }
        return reach;
    }

    @Test
    void releaseSettlesSmoothly() {
        assertEquals(1, AwakeningShape.release(P, 0));
        assertEquals(1, AwakeningShape.release(P, -1));
        assertEquals(1, AwakeningShape.release(P, Double.NaN));
        assertEquals(0.5, AwakeningShape.release(P, P.releaseSeconds() / 2), 1e-12);
        assertEquals(0, AwakeningShape.release(P, P.releaseSeconds()));
        assertEquals(0, AwakeningShape.release(P, 100));
        double last = 1;
        for (double t = 0; t <= P.releaseSeconds(); t += 0.01) {
            double r = AwakeningShape.release(P, t);
            assertTrue(r <= last, "settling at " + t);
            last = r;
        }
    }
}
