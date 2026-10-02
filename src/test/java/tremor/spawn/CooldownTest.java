package tremor.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CooldownTest {
    @Test
    void runsForItsLengthFromTheStart() {
        assertEquals(600, Cooldown.remaining(1000, 1000, 600));
        assertEquals(1, Cooldown.remaining(1599, 1000, 600));
        assertEquals(0, Cooldown.remaining(1600, 1000, 600));
        assertEquals(0, Cooldown.remaining(100_000, 1000, 600));
        // Negative game times are times too.
        assertEquals(50, Cooldown.remaining(-50, -100, 100));
    }

    @Test
    void neverStartedOrOffMeansNoCooldown() {
        assertEquals(0, Cooldown.remaining(0, Long.MIN_VALUE, 600));
        assertEquals(0, Cooldown.remaining(Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE));
        assertEquals(0, Cooldown.remaining(1000, 1000, 0));
    }

    @Test
    void aStartInTheFutureIsIgnored() {
        assertEquals(0, Cooldown.remaining(1000, 1001, 600));
        assertEquals(0, Cooldown.remaining(0, Long.MAX_VALUE, 600));
    }

    @Test
    void anImpossiblyOldStartDoesNotOverflow() {
        assertEquals(0, Cooldown.remaining(Long.MAX_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE));
        assertEquals(0, Cooldown.remaining(10, Long.MIN_VALUE + 1, 600));
    }
}
