package tremor.hollow.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClosingScheduleTest {
    private static final double EPS = 1e-9;

    /** Grace 100 ticks, edge 31, minimum 6, 0.1 blocks/s, +0.02 per loudness/s over 3 s, lures 80 / 160 / 4 blocks. */
    private static ClosingSchedule.Params params() {
        return new ClosingSchedule.Params(100, 31, 6, 0.1, 0.02, 3, 80, 160, 4, 30, 12);
    }

    private static void run(ClosingSchedule schedule, int ticks) {
        for (int i = 0; i < ticks; i++) {
            schedule.tick();
        }
    }

    @Test
    void standsStillDuringTheGraceThenClosesAtTheBaseSpeed() {
        ClosingSchedule schedule = new ClosingSchedule(params());
        run(schedule, 100);
        assertEquals(31, schedule.radius(), EPS);
        assertEquals(0, schedule.graceLeft());
        run(schedule, 200);
        assertEquals(31 - 0.1 * 10, schedule.radius(), 1e-6);
    }

    @Test
    void neverGoesBelowTheMinimumAndThePulseQuickens() {
        ClosingSchedule schedule = new ClosingSchedule(params());
        assertEquals(30, schedule.beatTicks());
        run(schedule, 100 + 20 * 300);
        assertEquals(6, schedule.radius(), EPS);
        assertEquals(1, schedule.closed(), EPS);
        assertEquals(12, schedule.beatTicks());
    }

    @Test
    void noiseSpeedsTheClosingUpAndFades() {
        ClosingSchedule quiet = new ClosingSchedule(params());
        ClosingSchedule noisy = new ClosingSchedule(params());
        run(quiet, 100);
        run(noisy, 100);
        // Steps of loudness 2 twice a second: the noise settles near 4 loudness per second.
        for (int i = 0; i < 600; i++) {
            if (i % 10 == 0) {
                noisy.noise(2);
            }
            quiet.tick();
            noisy.tick();
        }
        assertEquals(4, noisy.noiseLevel(), 0.5);
        assertTrue(noisy.radius() < quiet.radius() - 1.5, noisy.radius() + " vs " + quiet.radius());
        assertEquals(0.1 + 0.02 * noisy.noiseLevel(), noisy.speed(), EPS);
        // Silence: the noise fades with the time constant (3 s: e^-3 after 9 s).
        double before = noisy.noiseLevel();
        run(noisy, 180);
        assertEquals(before * Math.exp(-3), noisy.noiseLevel(), 1e-6);
    }

    @Test
    void sneakingMakesNoNoise() {
        ClosingSchedule schedule = new ClosingSchedule(params());
        schedule.noise(0);
        schedule.noise(-1);
        assertEquals(0, schedule.noiseLevel(), EPS);
    }

    @Test
    void aLurePausesTheClosingOnceInAWhile() {
        ClosingSchedule schedule = new ClosingSchedule(params());
        run(schedule, 200);
        double radius = schedule.radius();
        assertFalse(schedule.lure(3), "too close to the player");
        assertTrue(schedule.lure(10));
        assertEquals(80, schedule.pauseLeft());
        run(schedule, 80);
        assertEquals(radius, schedule.radius(), EPS);
        run(schedule, 20);
        assertTrue(schedule.radius() < radius);
        assertFalse(schedule.lure(10), "within the cooldown");
        run(schedule, 60);
        assertTrue(schedule.lure(10), "after the cooldown");
        assertEquals(2, schedule.lures());
    }

    @Test
    void aMinimumBeyondTheEdgeMeansClosedFromTheStart() {
        ClosingSchedule schedule = new ClosingSchedule(new ClosingSchedule.Params(0, 5, 8, 1, 0, 3, 0, 0, 0, 30, 12));
        run(schedule, 100);
        assertEquals(5, schedule.radius(), EPS);
        assertEquals(1, schedule.closed(), EPS);
    }
}
