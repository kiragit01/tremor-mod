package tremor.hollow.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SinkTrackerTest {
    private static final double EPS = 1e-9;

    /** Still after 60 ticks within 0.25 blocks; then 0.01 blocks per tick down to 1.75. */
    private static SinkTracker tracker() {
        return new SinkTracker(new SinkTracker.Params(60, 0.25, 0.01, 1.75));
    }

    @Test
    void theGroundSoftensOnlyAfterStandingStill() {
        SinkTracker tracker = tracker();
        tracker.update(0.5, 0.5, true);
        for (int i = 0; i < 59; i++) {
            tracker.update(0.5, 0.5, true);
        }
        assertFalse(tracker.still());
        assertEquals(0, tracker.depth(), EPS);
        tracker.update(0.5, 0.5, true);
        assertTrue(tracker.still());
        assertEquals(0.01, tracker.depth(), EPS);
        for (int i = 0; i < 500; i++) {
            tracker.update(0.5, 0.5, true);
        }
        assertEquals(1.75, tracker.depth(), EPS);
    }

    @Test
    void smallJittersAreStandingStillMovingOnStartsOver() {
        SinkTracker tracker = tracker();
        for (int i = 0; i < 100; i++) {
            // Within 0.25 of where the player stopped: pressing against a wall, turning around.
            tracker.update(0.5 + 0.2 * Math.sin(i), 0.5 + 0.1 * Math.cos(i), true);
        }
        assertEquals(0.40, tracker.depth(), 1e-6);
        tracker.update(0.9, 0.5, true);
        assertFalse(tracker.still());
        assertEquals(0, tracker.depth(), EPS);
        assertEquals(0, tracker.stillTicks());
    }

    @Test
    void nothingSoftensInTheAirOrOnHardGround() {
        SinkTracker tracker = tracker();
        for (int i = 0; i < 200; i++) {
            tracker.update(0, 0, false);
        }
        assertTrue(tracker.still());
        assertEquals(0, tracker.depth(), EPS);
        tracker.update(0, 0, true);
        assertEquals(0.01, tracker.depth(), EPS);
    }

    @Test
    void theSofteningGoesDownLayerByLayer() {
        assertEquals(-1, SinkTracker.softness(0, 0));
        assertEquals(0, SinkTracker.softness(0.05, 0));
        assertEquals(4, SinkTracker.softness(0.5, 0));
        assertEquals(SinkTracker.MAX_SOFTNESS, SinkTracker.softness(1, 0));
        assertEquals(-1, SinkTracker.softness(1, 1));
        assertEquals(SinkTracker.MAX_SOFTNESS, SinkTracker.softness(1.5, 0));
        assertEquals(4, SinkTracker.softness(1.5, 1));
        assertEquals(6, SinkTracker.softness(1.75, 1));
        assertEquals(-1, SinkTracker.softness(1.75, 2));
    }

    @Test
    void sinkIsHowDeepTheEyesAre() {
        assertEquals(0, SinkTracker.sink(64, 64, 1.62), EPS);
        assertEquals(0, SinkTracker.sink(64, 65, 1.62), EPS);
        assertEquals(0.5, SinkTracker.sink(64, 64 - 0.81, 1.62), EPS);
        assertEquals(1, SinkTracker.sink(64, 64 - 1.62, 1.62), EPS);
        // The deepest softening (1.75, softness 6 of the second block) is over the eyes of a standing player.
        double feet = 64 - 2 + (1 - 6 / 8.0);
        assertEquals(1, SinkTracker.sink(64, feet, 1.62), EPS);
    }
}
