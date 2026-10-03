package tremor.client.awakening;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EmergeClockTest {
    private static final int RING_TICKS = 31;

    @Test
    void anOnlookersHillRunsOnTheServersClock() {
        EmergeClock clock = new EmergeClock(RING_TICKS);
        assertFalse(clock.known());
        assertEquals(0, clock.progress(5));
        clock.learn(1000, 80, false, 1001);
        clock.update(false, 1001);
        assertTrue(clock.known());
        assertFalse(clock.waiting());
        assertEquals(1000, clock.start());
        assertEquals(0.25, clock.progress(1020), 1e-12);
        assertEquals(0, clock.progress(990), "not before the start");
        clock.update(false, 1079);
        assertTrue(clock.known());
        clock.update(false, 1080);
        assertFalse(clock.known(), "over once it has run its length");
        assertEquals(0, clock.progress(1050));
    }

    @Test
    void theVictorsHillWaitsForTheScreenAndThenRunsItsWholeLength() {
        // Defaults: the phase starts at the victory (1000); the victor learns of it after the move (1021), in the
        // dark, and the screen is back half way through the fade in.
        EmergeClock clock = new EmergeClock(RING_TICKS);
        clock.learn(1000, 80, true, 1021);
        for (long t = 1021; t < 1041; t++) {
            clock.update(true, t);
            assertTrue(clock.waiting());
            assertEquals(0, clock.progress(t), "flat while dark");
        }
        clock.update(false, 1041);
        assertFalse(clock.waiting());
        assertEquals(1041, clock.start());
        assertEquals(0, clock.progress(1041));
        assertEquals(0.5, clock.progress(1081), 1e-12);
        // It runs on after the server's phase is over (1080), to its own end.
        clock.update(true, 1100);
        assertTrue(clock.known(), "a dark screen later does not stop it");
        clock.update(false, 1120);
        assertTrue(clock.known());
        clock.update(false, 1121);
        assertFalse(clock.known());
    }

    @Test
    void aHillSeenBeforeItsPhaseStartsRisesWithThePhase() {
        EmergeClock clock = new EmergeClock(RING_TICKS);
        clock.learn(1000, 80, true, 995);
        clock.update(false, 996);
        assertEquals(1000, clock.start());
    }

    @Test
    void aShortPhaseIsKeptWhileItsRingRuns() {
        EmergeClock clock = new EmergeClock(RING_TICKS);
        clock.learn(1000, 5, false, 1000);
        assertEquals(1, clock.progress(1005));
        clock.update(false, 1005 + 20);
        assertTrue(clock.known());
        clock.update(false, 1000 + RING_TICKS);
        assertFalse(clock.known());
    }

    @Test
    void aHillThatNeverGetsSeenIsDropped() {
        EmergeClock clock = new EmergeClock(RING_TICKS);
        clock.learn(1000, 80, true, 1000);
        clock.update(true, 1000 + EmergeClock.MAX_WAIT_TICKS);
        assertTrue(clock.waiting());
        clock.update(true, 1001 + EmergeClock.MAX_WAIT_TICKS);
        assertFalse(clock.known());
        assertFalse(clock.waiting());
        assertTrue(Double.isNaN(clock.start()));
    }

    @Test
    void anotherHillReplacesTheLastAndAnOpenEndedOneIsFlat() {
        EmergeClock clock = new EmergeClock(RING_TICKS);
        clock.learn(1000, 80, true, 1000);
        clock.learn(2000, 0, false, 2000);
        assertFalse(clock.waiting());
        assertEquals(2000, clock.start());
        assertEquals(0, clock.phaseTicks());
        assertEquals(0, clock.progress(2010), "open-ended: flat");
        clock.update(false, 2000 + RING_TICKS - 1);
        assertTrue(clock.known());
        clock.forget();
        assertFalse(clock.known());
        clock.update(false, 2100);
        assertFalse(clock.known());
    }
}
