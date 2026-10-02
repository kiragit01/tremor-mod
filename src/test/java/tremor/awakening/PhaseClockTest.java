package tremor.awakening;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PhaseClockTest {
    @Test
    void runsForItsLengthFromTheStart() {
        PhaseClock clock = new PhaseClock(1000, 600);
        assertEquals(0, clock.elapsed(1000));
        assertEquals(600, clock.remaining(1000));
        assertFalse(clock.over(1000));
        assertEquals(599, clock.elapsed(1599));
        assertEquals(1, clock.remaining(1599));
        assertFalse(clock.over(1599));
        assertEquals(0, clock.remaining(1600));
        assertTrue(clock.over(1600));
        assertTrue(clock.over(5000));
        assertEquals(0, clock.remaining(5000));
    }

    @Test
    void beforeTheStartNothingHasPassed() {
        PhaseClock clock = new PhaseClock(1000, 50);
        assertEquals(0, clock.elapsed(900));
        assertEquals(50, clock.remaining(900));
        assertFalse(clock.over(900));
    }

    @Test
    void anOpenEndedPhaseIsNeverOver() {
        PhaseClock clock = new PhaseClock(1000, 0);
        assertFalse(clock.over(1000));
        assertFalse(clock.over(Long.MAX_VALUE));
        assertEquals(0, clock.remaining(2000));
        assertEquals(1000, clock.elapsed(2000));
    }

    @Test
    void aNegativeLengthIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PhaseClock(0, -1));
    }
}
