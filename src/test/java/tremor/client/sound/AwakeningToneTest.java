package tremor.client.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AwakeningToneTest {
    private static final double TICK = 1 / AwakeningTone.TICKS_PER_SECOND;

    @Test
    void tensionFollowsTheBuildUpThenStaysFull() {
        assertEquals(0, AwakeningTone.tension(false, 0));
        assertEquals(0.4, AwakeningTone.tension(false, 0.4), 1e-12);
        assertEquals(1, AwakeningTone.tension(false, 1));
        assertEquals(1, AwakeningTone.tension(false, 3), "clamped");
        assertEquals(0, AwakeningTone.tension(false, Double.NaN));
        assertEquals(1, AwakeningTone.tension(true, 0), "swallowing: full whatever its own progress");
        assertEquals(1, AwakeningTone.tension(true, 0.5));
    }

    @Test
    void heartbeatQuickensFromAboutOneASecondToTwoAndAHalf() {
        assertEquals(22, AwakeningTone.beatTicks(0), "1.1 s");
        assertEquals(9, AwakeningTone.beatTicks(1), "0.45 s");
        assertEquals(22, AwakeningTone.beatTicks(-1));
        assertEquals(9, AwakeningTone.beatTicks(2));
        // The rate rises linearly: half way it is the mean of the two rates.
        double halfRate = (1 / AwakeningTone.SLOWEST_BEAT_SECONDS + 1 / AwakeningTone.FASTEST_BEAT_SECONDS) / 2;
        assertEquals(Math.round(AwakeningTone.TICKS_PER_SECOND / halfRate), AwakeningTone.beatTicks(0.5));
        int previous = Integer.MAX_VALUE;
        for (int i = 0; i <= 100; i++) {
            int ticks = AwakeningTone.beatTicks(i / 100.0);
            assertTrue(ticks <= previous, "never slows down as the tension grows");
            previous = ticks;
        }
    }

    @Test
    void heartbeatGrowsLouderAndHigher() {
        assertEquals(0.8 * AwakeningTone.QUIETEST_BEAT, AwakeningTone.beatVolume(0.8, 0), 1e-12);
        assertEquals(0.8, AwakeningTone.beatVolume(0.8, 1), 1e-12);
        assertEquals(0.8 * (AwakeningTone.QUIETEST_BEAT + 1) / 2, AwakeningTone.beatVolume(0.8, 0.5), 1e-12);
        assertEquals(0, AwakeningTone.beatVolume(0, 1), "off");
        assertEquals(1, AwakeningTone.beatPitch(0));
        assertEquals(1 + AwakeningTone.BEAT_PITCH_RISE, AwakeningTone.beatPitch(1), 1e-12);
    }

    @Test
    void humSwellsAndRises() {
        assertEquals(0.7 * AwakeningTone.QUIETEST_HUM, AwakeningTone.humVolume(0.7, 0), 1e-12);
        assertEquals(0.7, AwakeningTone.humVolume(0.7, 1), 1e-12);
        assertEquals(0.7, AwakeningTone.humVolume(0.7, 5), 1e-12);
        assertEquals(1, AwakeningTone.humPitch(0));
        assertEquals(1 + AwakeningTone.HUM_PITCH_RISE, AwakeningTone.humPitch(1), 1e-12);
    }

    @Test
    void humComesUpSlowlyAndDiesQuickly() {
        double up = 0;
        double down = 1;
        for (int tick = 0; tick < 20; tick++) {
            up = AwakeningTone.humApproach(up, 1, TICK);
            down = AwakeningTone.humApproach(down, 0, TICK);
        }
        // One second: the swell is two thirds of the way (one attack time constant), the release all but done.
        assertEquals(1 - Math.exp(-1 / AwakeningTone.HUM_ATTACK_SECONDS), up, 1e-9);
        assertEquals(Math.exp(-1 / AwakeningTone.HUM_RELEASE_SECONDS), down, 1e-9);
        assertTrue(down < 0.05);
        assertEquals(0.5, AwakeningTone.humApproach(0.5, 0.5, TICK), "at the target it stays");
    }

    @Test
    void silenceTakesThreeSecondsEachWay() {
        int ticks = (int) Math.round(AwakeningTone.SILENCE_FADE_SECONDS * AwakeningTone.TICKS_PER_SECOND);
        double depth = 0;
        for (int tick = 1; tick <= ticks; tick++) {
            depth = AwakeningTone.silenceDepth(depth, true, TICK);
            assertEquals((double) tick / ticks, depth, 1e-9);
        }
        assertEquals(1, AwakeningTone.silenceDepth(depth, true, TICK), "stays at full depth");
        for (int tick = 1; tick <= ticks; tick++) {
            depth = AwakeningTone.silenceDepth(depth, false, TICK);
        }
        assertEquals(0, depth, 1e-9);
        assertEquals(0, AwakeningTone.silenceDepth(0, false, TICK), "never below 0");
    }

    @Test
    void silenceFallsEvenlyInDecibelsToTheFloor() {
        assertEquals(1, AwakeningTone.silenceGain(0, 0.05));
        assertEquals(0.05, AwakeningTone.silenceGain(1, 0.05), 1e-12);
        assertEquals(0.05, AwakeningTone.silenceGain(2, 0.05), 1e-12);
        // Half way in depth is half way in decibels: the geometric mean.
        assertEquals(Math.sqrt(0.05), AwakeningTone.silenceGain(0.5, 0.05), 1e-12);
        // Gentle at both ends (smoothstep): the first and last steps are much smaller than the middle ones.
        double step = 1.0 / 60;
        double first = 1 - AwakeningTone.silenceGain(step, 0.05);
        double middle = AwakeningTone.silenceGain(0.5 - step / 2, 0.05)
                - AwakeningTone.silenceGain(0.5 + step / 2, 0.05);
        assertTrue(first < middle / 5, first + " vs " + middle);
        double previous = 1;
        for (int i = 1; i <= 100; i++) {
            double g = AwakeningTone.silenceGain(i / 100.0, 0.05);
            assertTrue(g <= previous, "falls all the way");
            previous = g;
        }
    }

    @Test
    void silenceFloorEnds() {
        assertEquals(0, AwakeningTone.silenceGain(1, 0), "complete silence at the end");
        double nearEnd = AwakeningTone.silenceGain(1 - 1e-6, 0);
        assertTrue(nearEnd > 0 && nearEnd < 2 * AwakeningTone.QUIETEST, "from a gain nobody hears: " + nearEnd);
        assertEquals(Math.sqrt(AwakeningTone.QUIETEST), AwakeningTone.silenceGain(0.5, 0), 1e-12);
        assertEquals(1, AwakeningTone.silenceGain(0.5, 1), "a floor of 1 is no silence");
        assertEquals(1, AwakeningTone.silenceGain(1, 1));
        assertEquals(1, AwakeningTone.silenceGain(1, 1.5));
        assertEquals(1, AwakeningTone.silenceGain(Double.NaN, 0.05), "NaN depth counts as none");
    }
}
