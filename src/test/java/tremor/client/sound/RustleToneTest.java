package tremor.client.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tremor.core.behavior.Stage;
import tremor.core.shape.BumpShape;

class RustleToneTest {
    @Test
    void stageSetsLoudnessAndTone() {
        assertEquals(0.5, RustleTone.stageVolume(Stage.DORMANT));
        assertEquals(0.35, RustleTone.stageVolume(Stage.ALERT));
        assertEquals(1.0, RustleTone.stageVolume(Stage.HUNTING));
        assertEquals(1.0, RustleTone.stageVolume(Stage.AWAKENING));
        assertEquals(0.7, RustleTone.stagePitch(Stage.DORMANT));
        assertEquals(0.8, RustleTone.stagePitch(Stage.ALERT));
        assertEquals(1.0, RustleTone.stagePitch(Stage.HUNTING));
        assertEquals(1.1, RustleTone.stagePitch(Stage.AWAKENING));
    }

    @Test
    void volumeGrowsWithSpeedUpToFull() {
        assertEquals(0, RustleTone.volume(1, Stage.HUNTING, 0, 2));
        assertEquals(0.25, RustleTone.volume(1, Stage.HUNTING, 1, 2), 1e-12);
        assertEquals(1.0, RustleTone.volume(1, Stage.HUNTING, RustleTone.FULL_SPEED, 2), 1e-12);
        assertEquals(1.0, RustleTone.volume(1, Stage.HUNTING, 30, 2), 1e-12);
        assertEquals(2 * 0.5 * 0.5, RustleTone.volume(2, Stage.DORMANT, 2, 2), 1e-12);
        assertEquals(0, RustleTone.volume(1, Stage.HUNTING, Double.NaN, 2));
    }

    @Test
    void lowBumpFadesOut() {
        double full = RustleTone.volume(1, Stage.ALERT, 4, RustleTone.FULL_AMPLITUDE);
        assertEquals(0.35, full, 1e-12);
        assertEquals(full, RustleTone.volume(1, Stage.ALERT, 4, 3.0), 1e-12);
        double mid = (BumpShape.RENDER_THRESHOLD + RustleTone.FULL_AMPLITUDE) / 2;
        assertEquals(full / 2, RustleTone.volume(1, Stage.ALERT, 4, mid), 1e-12);
        assertEquals(0, RustleTone.volume(1, Stage.ALERT, 4, BumpShape.RENDER_THRESHOLD));
        assertEquals(0, RustleTone.volume(1, Stage.ALERT, 4, -1));
    }

    @Test
    void baseVolumeKeepsTheStagesApartUnderTheEngineClamp() {
        // The engine plays min(1, volume · category volume).
        assertEquals(1, RustleTone.base(1, 1));
        assertEquals(1, RustleTone.base(2, 1), "at 100% Hostile Creatures above 1 changes nothing");
        assertEquals(2, RustleTone.base(2, 0.5), 1e-12, "but makes up for a lower Hostile Creatures volume");
        assertEquals(1.5, RustleTone.base(1.5, 0.5), 1e-12);
        assertEquals(0.5, RustleTone.base(0.5, 0.25), 1e-12);
        assertEquals(2, RustleTone.base(2, 0), "muted: nothing plays anyway");
        // Whatever the setting, the stages keep their ratios: the loudest one never runs into the clamp.
        for (double configured : new double[]{0.3, 1, 1.4, 2}) {
            for (double category : new double[]{0.1, 0.5, 0.8, 1}) {
                double base = RustleTone.base(configured, category);
                double hunting = RustleTone.volume(base, Stage.HUNTING, 30, 2) * category;
                double dormant = RustleTone.volume(base, Stage.DORMANT, 30, 2) * category;
                assertTrue(hunting <= 1 + 1e-12, configured + " at " + category + ": " + hunting);
                assertEquals(RustleTone.stageVolume(Stage.DORMANT), Math.min(1, dormant) / Math.min(1, hunting), 1e-12);
            }
        }
    }

    @Test
    void hearingStartsWithinTheRangeAndStopsBeyondTheMargin() {
        double range = 20;
        assertTrue(RustleTone.inHearing(0, range, false));
        assertTrue(RustleTone.inHearing(19.9, range, false));
        assertFalse(RustleTone.inHearing(20, range, false), "the subtitle shows only strictly within the range");
        assertFalse(RustleTone.inHearing(22, range, false));
        // A playing one keeps going a little beyond, so it does not flicker at the edge.
        assertTrue(RustleTone.inHearing(20, range, true));
        assertTrue(RustleTone.inHearing(range + RustleTone.HEARING_MARGIN, range, true));
        assertFalse(RustleTone.inHearing(range + RustleTone.HEARING_MARGIN + 0.01, range, true));
        assertFalse(RustleTone.inHearing(Double.NaN, range, true));
        assertFalse(RustleTone.inHearing(Double.NaN, range, false));
    }

    @Test
    void pitchRisesALittleWithSpeed() {
        assertEquals(0.7, RustleTone.pitch(Stage.DORMANT, 0), 1e-12);
        assertEquals(0.7 + RustleTone.SPEED_PITCH / 2, RustleTone.pitch(Stage.DORMANT, 2), 1e-12);
        assertEquals(1.0 + RustleTone.SPEED_PITCH, RustleTone.pitch(Stage.HUNTING, 100), 1e-12);
    }

    @Test
    void easingApproachesWithoutJumpingOrOvershooting() {
        assertEquals(0.3, RustleTone.approach(0.3, 1, 0), 1e-15);
        assertEquals(1 - Math.exp(-1), RustleTone.approach(0, 1, RustleTone.EASING_SECONDS), 1e-12);
        double v = 0, step = 1.0 / 20;
        for (int i = 0; i < 40; i++) {
            double next = RustleTone.approach(v, 1, step);
            assertTrue(next > v && next < 1);
            assertTrue(next - v < 0.25, "one tick moves at most a quarter of the way");
            v = next;
        }
        assertTrue(v > 0.99, "after two seconds it is there: " + v);
    }
}
