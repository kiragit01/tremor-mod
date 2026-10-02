package tremor.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DespawnClockTest {
    private static final double DT = 0.05;

    private static void ticks(DespawnClock clock, int n, boolean playerNear, boolean eventful) {
        for (int i = 0; i < n; i++) {
            clock.tick(DT, playerNear, eventful);
        }
    }

    @Test
    void farTimeCountsWhileNoPlayerIsNear() {
        DespawnClock clock = new DespawnClock();
        ticks(clock, 100, false, true);
        assertEquals(5, clock.farSeconds(), 1e-9);
        assertEquals(0, clock.quietSeconds());
        clock.tick(DT, true, true);
        assertEquals(0, clock.farSeconds(), "a player near resets it");
        ticks(clock, 20, false, true);
        assertEquals(1, clock.farSeconds(), 1e-9);
    }

    @Test
    void quietTimeCountsWhileNothingHappens() {
        DespawnClock clock = new DespawnClock();
        ticks(clock, 60, true, false);
        assertEquals(3, clock.quietSeconds(), 1e-9);
        assertEquals(0, clock.farSeconds());
        clock.tick(DT, true, true);
        assertEquals(0, clock.quietSeconds(), "an eventful tick resets it");
    }

    @Test
    void leavesOnEitherLimit() {
        DespawnClock far = new DespawnClock();
        ticks(far, 199, false, true);
        assertFalse(far.shouldLeave(10, 30));
        far.tick(DT, false, true);
        assertTrue(far.shouldLeave(10, 30), "10 s far, fed as 200 ticks of 0.05 s");
        assertFalse(far.shouldLeave(Double.POSITIVE_INFINITY, 30));

        DespawnClock quiet = new DespawnClock();
        ticks(quiet, 599, true, false);
        assertFalse(quiet.shouldLeave(10, 30));
        quiet.tick(DT, true, false);
        assertTrue(quiet.shouldLeave(10, 30));
        assertFalse(quiet.shouldLeave(10, Double.POSITIVE_INFINITY));
    }

    @Test
    void nonPositiveDtStillResets() {
        DespawnClock clock = new DespawnClock(7, 9);
        clock.tick(0, false, false);
        clock.tick(-1, false, false);
        clock.tick(Double.NaN, false, false);
        assertEquals(7, clock.farSeconds());
        assertEquals(9, clock.quietSeconds());
        clock.tick(0, true, true);
        assertEquals(0, clock.farSeconds());
        assertEquals(0, clock.quietSeconds());
    }

    @Test
    void restoreAndReset() {
        DespawnClock clock = new DespawnClock(120, 45);
        assertEquals(120, clock.farSeconds());
        assertEquals(45, clock.quietSeconds());
        assertTrue(clock.shouldLeave(100, 1000));
        clock.reset();
        assertEquals(0, clock.farSeconds());
        assertEquals(0, clock.quietSeconds());
        assertFalse(clock.shouldLeave(100, 1000));
        DespawnClock invalid = new DespawnClock(-3, Double.NaN);
        assertEquals(0, invalid.farSeconds());
        assertEquals(0, invalid.quietSeconds());
    }
}
