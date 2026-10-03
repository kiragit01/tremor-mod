package tremor.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AngerMeterTest {
    /** SPEC numbers: 25 / 60 / 100, hysteresis 3, decay 0.5/s, x3 after 20 s of silence. */
    private static final BehaviorParams PARAMS = BehaviorParams.defaults();

    private static AngerMeter meter(double anger) {
        return new AngerMeter(PARAMS, anger, null);
    }

    @Test
    void risingStageIsTheHighestThresholdReached() {
        AngerMeter m = meter(0);
        assertEquals(Stage.DORMANT, m.stage());
        assertEquals(Stage.DORMANT, m.add(24.9));
        assertEquals(Stage.ALERT, m.add(0.1));
        assertEquals(25, m.anger(), 1e-9);
        assertEquals(Stage.ALERT, m.add(34.9));
        assertEquals(Stage.HUNTING, m.add(0.1));
        assertEquals(Stage.HUNTING, m.add(39.9));
        assertEquals(Stage.AWAKENING, m.add(0.1));
        assertEquals(100, m.anger(), 1e-9);
    }

    @Test
    void oneBigRiseSkipsStages() {
        AngerMeter m = meter(10);
        assertEquals(Stage.HUNTING, m.add(55));
        AngerMeter n = meter(0);
        assertEquals(Stage.AWAKENING, n.add(500));
    }

    @Test
    void thresholds() {
        AngerMeter m = meter(0);
        assertEquals(0, m.threshold(Stage.DORMANT));
        assertEquals(25, m.threshold(Stage.ALERT));
        assertEquals(60, m.threshold(Stage.HUNTING));
        assertEquals(100, m.threshold(Stage.AWAKENING));
    }

    @Test
    void noFlickerAroundAlertThreshold() {
        AngerMeter m = meter(25);
        assertEquals(Stage.ALERT, m.stage());
        for (int i = 0; i < 10; i++) {
            assertEquals(Stage.ALERT, m.add(-2.5), "down to 22.5"); // 22.5 >= 25 - 3
            assertEquals(Stage.ALERT, m.add(2.5), "back to 25");
        }
        assertEquals(Stage.ALERT, m.add(-3), "22 is still inside the band");
        assertEquals(Stage.DORMANT, m.add(-0.25), "21.75 leaves ALERT");
        // Back up: DORMANT until 25 is reached again.
        for (int i = 0; i < 10; i++) {
            assertEquals(Stage.DORMANT, m.add(3), "24.75");
            assertEquals(Stage.DORMANT, m.add(-3), "21.75");
        }
        assertEquals(Stage.DORMANT, m.add(3));
        assertEquals(Stage.ALERT, m.add(0.25));
    }

    @Test
    void noFlickerAroundHuntThreshold() {
        AngerMeter m = meter(60);
        assertEquals(Stage.HUNTING, m.stage());
        for (int i = 0; i < 10; i++) {
            assertEquals(Stage.HUNTING, m.add(-2.75));
            assertEquals(Stage.HUNTING, m.add(2.75));
        }
        assertEquals(Stage.HUNTING, m.add(-3), "57 is still inside the band");
        assertEquals(Stage.ALERT, m.add(-0.25));
        for (int i = 0; i < 10; i++) {
            assertEquals(Stage.ALERT, m.add(3), "59.75 is not enough to come back");
            assertEquals(Stage.ALERT, m.add(-3));
        }
        assertEquals(Stage.ALERT, m.add(3));
        assertEquals(Stage.HUNTING, m.add(0.25));
    }

    @Test
    void noFlickerAroundAwakenThreshold() {
        AngerMeter m = meter(100);
        assertEquals(Stage.AWAKENING, m.stage());
        for (int i = 0; i < 10; i++) {
            assertEquals(Stage.AWAKENING, m.add(-2.75));
            assertEquals(Stage.AWAKENING, m.add(5), "clamped back to 100");
            assertEquals(100, m.anger());
        }
        assertEquals(Stage.AWAKENING, m.add(-3), "97 is still inside the band");
        assertEquals(Stage.HUNTING, m.add(-0.25));
        assertEquals(Stage.HUNTING, m.add(3), "99.75");
        assertEquals(Stage.AWAKENING, m.add(0.25));
    }

    @Test
    void bigDropLeavesSeveralStagesStageByStage() {
        AngerMeter m = meter(100);
        assertEquals(Stage.DORMANT, m.add(-95));
        AngerMeter n = meter(100);
        assertEquals(Stage.ALERT, n.add(-50));
        // 23 is below HUNTING's band (57) but inside ALERT's (22): same as a slow decay would give.
        AngerMeter jump = meter(60);
        assertEquals(Stage.ALERT, jump.add(-37));
        AngerMeter slow = meter(60);
        for (int i = 0; i < 370; i++) {
            slow.add(-0.1);
        }
        assertEquals(23, slow.anger(), 1e-6);
        assertEquals(jump.stage(), slow.stage());
        AngerMeter deep = meter(60);
        assertEquals(Stage.DORMANT, deep.add(-39), "21 is below ALERT's band too");
    }

    @Test
    void clampsToTheScale() {
        AngerMeter m = meter(50);
        assertEquals(Stage.AWAKENING, m.add(1000));
        assertEquals(100, m.anger());
        assertEquals(Stage.DORMANT, m.add(-1000));
        assertEquals(0, m.anger());
        assertEquals(0, meter(-5).anger());
        assertEquals(100, meter(150).anger());
        assertEquals(Stage.AWAKENING, meter(150).stage());
        m.set(-3);
        assertEquals(0, m.anger());
        m.set(250);
        assertEquals(100, m.anger());
        assertEquals(Stage.AWAKENING, m.stage());
        assertEquals(Stage.AWAKENING, m.add(Double.NaN), "NaN is ignored");
        assertEquals(100, m.anger());
        assertEquals(0, meter(Double.NaN).anger());
    }

    @Test
    void decaysAtTheNormalRateWhileSoundsAreRecent() {
        AngerMeter m = meter(50);
        assertEquals(Stage.ALERT, m.tick(2, 0));
        assertEquals(49, m.anger(), 1e-9);
        m.tick(4, 19.99);
        assertEquals(47, m.anger(), 1e-9);
    }

    @Test
    void decaysFasterAfterALongSilence() {
        AngerMeter m = meter(50);
        m.tick(2, 20); // exactly quietAfterSeconds: quiet
        assertEquals(47, m.anger(), 1e-9);
        m.tick(1, Double.POSITIVE_INFINITY); // never heard anything
        assertEquals(45.5, m.anger(), 1e-9);
    }

    @Test
    void decayGoesThroughHysteresisAndStopsAtZero() {
        AngerMeter m = meter(25);
        // 0.5/s: 22 after 6 s, still ALERT; below after that.
        for (int i = 0; i < 24; i++) {
            assertEquals(Stage.ALERT, m.tick(0.25, 0));
        }
        assertEquals(22, m.anger());
        assertEquals(Stage.DORMANT, m.tick(0.25, 0));
        for (int i = 0; i < 1000; i++) {
            m.tick(0.1, 100);
        }
        assertEquals(0, m.anger());
        assertEquals(Stage.DORMANT, m.stage());
    }

    @Test
    void nonPositiveOrNaNDtDoesNothing() {
        AngerMeter m = meter(30);
        m.tick(0, 100);
        m.tick(-5, 100);
        m.tick(Double.NaN, 100);
        assertEquals(30, m.anger());
    }

    @Test
    void forceStageSetsTheThreshold() {
        AngerMeter m = meter(42);
        m.forceStage(Stage.HUNTING);
        assertEquals(60, m.anger());
        assertEquals(Stage.HUNTING, m.stage());
        m.forceStage(Stage.AWAKENING);
        assertEquals(100, m.anger());
        assertEquals(Stage.AWAKENING, m.stage());
        m.forceStage(Stage.ALERT);
        assertEquals(25, m.anger());
        assertEquals(Stage.ALERT, m.stage());
        assertEquals(Stage.ALERT, m.add(-1), "hysteresis applies after a forced stage");
        m.forceStage(Stage.DORMANT);
        assertEquals(0, m.anger());
        assertEquals(Stage.DORMANT, m.stage());
        assertThrows(NullPointerException.class, () -> m.forceStage(null));
    }

    @Test
    void setImpliesTheStageWithoutHysteresis() {
        AngerMeter m = meter(60);
        assertEquals(Stage.HUNTING, m.stage());
        m.set(58); // inside HUNTING's band, but set ignores it
        assertEquals(Stage.ALERT, m.stage());
        assertEquals(58, m.anger());
        m.set(24.99);
        assertEquals(Stage.DORMANT, m.stage());
        m.set(100);
        assertEquals(Stage.AWAKENING, m.stage());
        m.set(0);
        assertEquals(Stage.DORMANT, m.stage());
    }

    @Test
    void constructorReconcilesTheStoredStage() {
        assertEquals(Stage.ALERT, new AngerMeter(PARAMS, 23, Stage.ALERT).stage(), "inside the band: kept");
        assertEquals(Stage.HUNTING, new AngerMeter(PARAMS, 58, Stage.HUNTING).stage());
        assertEquals(Stage.DORMANT, new AngerMeter(PARAMS, 10, Stage.HUNTING).stage(), "far below: lowered");
        assertEquals(Stage.ALERT, new AngerMeter(PARAMS, 40, Stage.AWAKENING).stage());
        assertEquals(Stage.HUNTING, new AngerMeter(PARAMS, 70, Stage.DORMANT).stage(), "above: raised");
        assertEquals(Stage.DORMANT, new AngerMeter(PARAMS, 23, null).stage(), "null: from the anger");
        assertEquals(Stage.ALERT, new AngerMeter(PARAMS, 25, null).stage());
        assertThrows(NullPointerException.class, () -> new AngerMeter(null, 0, null));
    }

    @Test
    void zeroHysteresisSwitchesExactlyAtTheThresholds() {
        BehaviorParams p = new BehaviorParams(25, 60, 100, 0, 0.5, 20, 3, 0.35, 2.5, 12, 8, 20, 4, 24, 4, false);
        AngerMeter m = new AngerMeter(p, 25, null);
        assertEquals(Stage.ALERT, m.stage());
        assertEquals(Stage.DORMANT, m.add(-0.01));
        assertEquals(Stage.ALERT, m.add(0.01));
    }
}
