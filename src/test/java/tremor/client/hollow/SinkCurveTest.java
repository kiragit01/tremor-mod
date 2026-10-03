package tremor.client.hollow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SinkCurveTest {
    @Test
    void noDarknessWithoutAPull() {
        assertEquals(0, SinkCurve.edge(0));
        assertEquals(0, SinkCurve.edge(-1));
        assertEquals(0, SinkCurve.edge(Double.NaN));
        for (double r = 0; r <= 1.2; r += 0.05) {
            assertEquals(0, SinkCurve.shade(r, 0));
        }
    }

    @Test
    void theDarknessFadesInAtTheFirstPullThenDeepens() {
        assertTrue(SinkCurve.edge(SinkCurve.ONSET / 4) < SinkCurve.EDGE_START / 2, "fades in");
        assertEquals(SinkCurve.EDGE_END, SinkCurve.edge(1), 1e-12);
        assertEquals(SinkCurve.EDGE_END, SinkCurve.edge(5), 1e-12);
        double last = 0;
        for (double s = 0; s <= 1; s += 0.01) {
            double e = SinkCurve.edge(s);
            assertTrue(e >= last - 1e-12 && e <= 1, "rising at " + s);
            last = e;
        }
    }

    @Test
    void theViewNarrowsAsThePlayerSinks() {
        assertEquals(SinkCurve.CLEAR_START, SinkCurve.clear(0), 1e-12);
        assertEquals(SinkCurve.CLEAR_END, SinkCurve.clear(1), 1e-12);
        assertTrue(SinkCurve.clear(0.5) < SinkCurve.clear(0.2));
        assertEquals(SinkCurve.FULL_START, SinkCurve.full(0), 1e-12);
        assertEquals(SinkCurve.FULL_END, SinkCurve.full(1), 1e-12);
        assertTrue(SinkCurve.full(0.5) < SinkCurve.full(0.2));
        for (double sink : new double[] {0.1, 0.5, 1}) {
            double clear = SinkCurve.clear(sink);
            assertEquals(0, SinkCurve.shade(clear * 0.5, sink), "the middle stays clear at " + sink);
            assertEquals(0, SinkCurve.shade(clear, sink), 1e-12);
            assertEquals(SinkCurve.edge(sink), SinkCurve.shade(SinkCurve.full(sink), sink), 1e-12);
            assertEquals(SinkCurve.edge(sink), SinkCurve.shade(1, sink), 1e-12, "the corners at " + sink);
            assertEquals(SinkCurve.edge(sink), SinkCurve.shade(1.1, sink), 1e-12, "beyond the corners");
            double last = 0;
            for (double r = 0; r <= 1; r += 0.02) {
                double shade = SinkCurve.shade(r, sink);
                assertTrue(shade >= last - 1e-12, "darker outwards at " + r);
                last = shade;
            }
        }
        // Fully pulled in, most of the view is dark.
        assertTrue(SinkCurve.shade(0.5, 1) > 0.9);
        // At the first pull only the corners darken.
        assertEquals(0, SinkCurve.shade(0.6, 0.1));
        assertTrue(SinkCurve.shade(1, 0.1) > 0.4);
    }

    @Test
    void theShownSinkEasesTowardTheServers() {
        double dt = 0.05;
        double s = 0;
        for (int i = 0; i < 20; i++) {
            double next = SinkCurve.approach(s, 0.6, dt);
            assertTrue(next > s && next < 0.6, "approaching from below");
            s = next;
        }
        assertEquals(0.6, s, 0.6 * Math.exp(-1 / SinkCurve.EASE_SECONDS) + 1e-9, "close after a second");
        assertEquals(0, SinkCurve.approach(0, Double.NaN, 1));
        assertEquals(1, SinkCurve.approach(1, 7, dt), 1e-12, "the target is clamped");
        assertEquals(0.3, SinkCurve.approach(0.3, 0.3, dt), 1e-12);
    }
}
