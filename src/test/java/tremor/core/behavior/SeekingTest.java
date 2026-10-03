package tremor.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SeekingTest {
    private static final double TICK = 0.05;
    private static final double SEEK = 35;

    /** Ticks the clock until it runs out (at most {@code limit} ticks); returns the ticks it took, or -1. */
    private static int ticksUntilCalm(Seeking seeking, int limit) {
        for (int i = 1; i <= limit; i++) {
            if (seeking.tick(TICK, SEEK)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void enteringAwakeningStartsTheClockAndItRunsOutAfterTheSeekTime() {
        Seeking seeking = new Seeking();
        assertFalse(seeking.running());
        seeking.stage(Stage.HUNTING);
        assertFalse(seeking.running());
        seeking.stage(Stage.AWAKENING);
        assertTrue(seeking.running());
        assertEquals(SEEK, seeking.secondsLeft(SEEK), 1e-9);
        // 35 s are 700 ticks: sums of 0.05 must not miss the end by a tick.
        assertEquals(700, ticksUntilCalm(seeking, 1000));
        assertFalse(seeking.running());
        assertEquals(0, seeking.secondsLeft(SEEK));
        assertFalse(seeking.tick(TICK, SEEK), "it runs out once");
    }

    @Test
    void stayingInAwakeningKeepsTheClockRunning() {
        Seeking seeking = new Seeking();
        seeking.stage(Stage.AWAKENING);
        for (int i = 0; i < 200; i++) {
            assertFalse(seeking.tick(TICK, SEEK));
            seeking.stage(Stage.AWAKENING); // every settle while it seeks: no restart
        }
        assertEquals(10, seeking.seconds(), 1e-9);
        assertEquals(25, seeking.secondsLeft(SEEK), 1e-9);
    }

    @Test
    void leavingAwakeningStopsItAndComingBackStartsAfresh() {
        Seeking seeking = new Seeking();
        seeking.stage(Stage.AWAKENING);
        for (int i = 0; i < 300; i++) {
            seeking.tick(TICK, SEEK);
        }
        seeking.stage(Stage.HUNTING);
        assertFalse(seeking.running());
        assertEquals(0, seeking.seconds());
        assertFalse(seeking.tick(TICK, SEEK), "a stopped clock does not run out");
        seeking.stage(Stage.AWAKENING);
        assertEquals(700, ticksUntilCalm(seeking, 1000));
    }

    @Test
    void aCommandRestartsTheSeeking() {
        Seeking seeking = new Seeking();
        seeking.stage(Stage.AWAKENING);
        for (int i = 0; i < 600; i++) {
            seeking.tick(TICK, SEEK);
        }
        assertEquals(5, seeking.secondsLeft(SEEK), 1e-9);
        seeking.restart();
        assertEquals(SEEK, seeking.secondsLeft(SEEK), 1e-9);
        assertEquals(700, ticksUntilCalm(seeking, 1000));
    }

    @Test
    void aShorterSeekTimeEndsItSooner() {
        Seeking seeking = new Seeking();
        seeking.stage(Stage.AWAKENING);
        for (int i = 0; i < 100; i++) {
            assertFalse(seeking.tick(TICK, SEEK));
        }
        // The config lowered to 3 s meanwhile: it is over at once.
        assertTrue(seeking.tick(TICK, 3));
        Seeking none = new Seeking();
        none.stage(Stage.AWAKENING);
        assertTrue(none.tick(0, 0), "no seek time: it calms down on the first tick");
    }

    @Test
    void theCalmDownAngerIsWellInsideHunting() {
        BehaviorParams params = BehaviorParams.defaults();
        double calm = Seeking.calmAnger(params);
        assertEquals(80, calm, 1e-9);
        // The meter set there by the calm-down is HUNTING, also with the hysteresis band of AWAKENING.
        AngerMeter meter = new AngerMeter(params, 100, Stage.AWAKENING);
        meter.set(calm);
        assertEquals(Stage.HUNTING, meter.stage());
        // A loaded entity that was seeking: HUNTING at that anger.
        assertEquals(Stage.HUNTING, new AngerMeter(params, calm, Stage.HUNTING).stage());
        BehaviorParams narrow = new BehaviorParams(25, 90, 100, 3, 0.5, 20, 3, 0.35, 2.5, 12, 8, 20, 4, 24, 4, false);
        assertEquals(95, Seeking.calmAnger(narrow), 1e-9);
    }

    @Test
    void afterTheCalmDownTheAngerDecaysAsUsual() {
        BehaviorParams params = BehaviorParams.defaults();
        AngerMeter meter = new AngerMeter(params, Seeking.calmAnger(params), Stage.HUNTING);
        // Quiet: 0.5/s, three times that after 20 s without a sound (it heard nothing while seeking).
        Stage stage = meter.stage();
        int ticks = 0;
        while (stage == Stage.HUNTING && ticks < 10_000) {
            stage = meter.tick(TICK, 60);
            ticks++;
        }
        assertEquals(Stage.ALERT, stage);
        // 80 -> 57 (below 60 - 3) at 1.5 per second: about 15 s.
        assertEquals(15.3, ticks * TICK, 0.1);
    }
}
