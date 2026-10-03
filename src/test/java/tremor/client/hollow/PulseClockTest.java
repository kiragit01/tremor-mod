package tremor.client.hollow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class PulseClockTest {
    /** Ticks at which the clock beats over {@code ticks} ticks of one tick each at the given pace. */
    private static List<Integer> beats(PulseClock clock, int ticks, int beatTicks) {
        List<Integer> at = new ArrayList<>();
        for (int t = 1; t <= ticks; t++) {
            int n = clock.advance(1, beatTicks);
            assertTrue(n <= 1);
            if (n == 1) {
                at.add(t);
            }
        }
        return at;
    }

    @Test
    void theFirstBeatComesSoonThenEvenly() {
        PulseClock clock = new PulseClock();
        clock.start(30);
        assertEquals(List.of(PulseClock.FIRST_BEAT_TICKS, PulseClock.FIRST_BEAT_TICKS + 30,
                PulseClock.FIRST_BEAT_TICKS + 60), beats(clock, 75, 30));
        // A beat shorter than the first wait: the first beat after a whole beat.
        clock.start(4);
        assertEquals(List.of(4, 8, 12), beats(clock, 12, 4));
    }

    @Test
    void aQuickerPaceBringsTheNextBeatNearerWithoutRestartingTheWait() {
        PulseClock clock = new PulseClock();
        clock.start(20);
        assertEquals(List.of(10), beats(clock, 10, 20));
        assertEquals(List.of(), beats(clock, 10, 20)); // half way to the next beat
        // At twice the pace the other half takes half as long.
        assertEquals(List.of(5, 15), beats(clock, 15, 10));
    }

    @Test
    void elapsedTimeCountsBeatsAndNothingForNoTime() {
        PulseClock clock = new PulseClock();
        clock.start(10);
        assertEquals(0, clock.advance(0, 10));
        assertEquals(0, clock.advance(-5, 10), "the clock went back");
        assertEquals(0, clock.phase(), 1e-12);
        assertEquals(3, clock.advance(30, 10));
        assertEquals(1, clock.advance(15, 10));
        assertEquals(0.5, clock.phase(), 1e-12);
        assertEquals(3, clock.advance(3, 0), "a pace below one tick counts as one");
        assertEquals(0.5, clock.phase(), 1e-12);
    }
}
