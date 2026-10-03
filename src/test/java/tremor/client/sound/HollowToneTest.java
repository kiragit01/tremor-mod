package tremor.client.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HollowToneTest {
    @Test
    void theHeartbeatIsLouderNearTheNodeAndAsTheHollowCloses() {
        double base = 0.8;
        assertEquals(base, HollowTone.beatVolume(base, 1, 0, 1), 1e-12);
        assertEquals(base * HollowTone.QUIETEST_BEAT, HollowTone.beatVolume(base, 0, 2, 1), 1e-12);
        assertEquals(base * HollowTone.FAR_SHARE, HollowTone.beatVolume(base, 1, 200, 1), 1e-12, "heard far off");
        double last = Double.MAX_VALUE;
        for (double d = 0; d <= 60; d += 1) {
            double v = HollowTone.beatVolume(base, 0.5, d, 1);
            assertTrue(v <= last + 1e-12 && v > 0, "quieter further off at " + d);
            last = v;
        }
        assertTrue(HollowTone.beatVolume(base, 0.8, 20, 1) > HollowTone.beatVolume(base, 0.2, 20, 1));
        assertEquals(0, HollowTone.beatVolume(0, 1, 0, 1), "off");
        assertEquals(base * 0.25, HollowTone.beatVolume(base, 1, 0, HollowTone.muffle(1)), 1e-12, "muffled");
        assertEquals(1, HollowTone.beatFalloff(HollowTone.NEAR_NODE), 1e-12);
        assertEquals(1, HollowTone.beatFalloff(Double.NaN), 1e-12);
        assertEquals(HollowTone.FAR_SHARE, HollowTone.beatFalloff(500), 1e-12);
        assertEquals(HollowTone.QUIETEST_BEAT * base, HollowTone.beatVolume(base, Double.NaN, 0, 1), 1e-12);
    }

    @Test
    void theHeartbeatIsHeardFromAcrossTheHollowAtAFloor() {
        double base = 0.8;
        // From the far side of a copy (some 35-40 blocks straight, 40-60 steps of winding way) and beyond, at any
        // closeness: about a fifth of the configured volume at least.
        for (double d = 30; d <= 200; d += 0.5) {
            for (double closeness = 0; closeness <= 1; closeness += 0.25) {
                double v = HollowTone.beatVolume(base, closeness, d, 1);
                assertTrue(v >= base * 0.18 - 1e-12, "audible at " + d + ", closeness " + closeness + ": " + v);
            }
        }
        assertEquals(base * HollowTone.QUIETEST_BEAT * HollowTone.FAR_SHARE, HollowTone.beatVolume(base, 0, 300, 1),
                1e-12, "the floor");
        // ...yet it does not stop there: still plainly quieter at 40 than at 20, and at 20 than at 10.
        assertTrue(HollowTone.beatFalloff(35) > HollowTone.FAR_SHARE);
        assertTrue(HollowTone.beatFalloff(20) / HollowTone.beatFalloff(40) > 1.35);
        assertTrue(HollowTone.beatFalloff(10) / HollowTone.beatFalloff(20) > 1.35);
    }

    @Test
    void theHeartbeatGrowsPlainlyLouderCloserToTheNode() {
        double base = 0.8;
        for (double closeness : new double[] {0, 0.5, 1}) {
            double far = HollowTone.beatVolume(base, closeness, 40, 1);
            double near = HollowTone.beatVolume(base, closeness, 5, 1);
            // About 9 dB or more: twice as loud to the ear and then some.
            assertTrue(near / far > 2.8, "near/far at closeness " + closeness + ": " + near / far);
            double last = Double.MAX_VALUE;
            for (double d = 0; d <= 60; d += 0.25) {
                double v = HollowTone.beatVolume(base, closeness, d, 1);
                assertTrue(v <= last + 1e-12, "never louder further off at " + d);
                last = v;
            }
        }
    }

    @Test
    void aPassingRingThumpsFaintly() {
        double base = 0.8;
        assertEquals(base * HollowTone.THUMP_SHARE, HollowTone.thumpVolume(base, 1, 1), 1e-12);
        assertEquals(base * HollowTone.THUMP_SHARE, HollowTone.thumpVolume(base, 3, 1), 1e-12);
        assertEquals(base * HollowTone.THUMP_SHARE * 0.5, HollowTone.thumpVolume(base, 0.5, 1), 1e-12);
        assertEquals(0, HollowTone.thumpVolume(base, Double.NaN, 1));
        assertEquals(0, HollowTone.thumpVolume(0, 1, 1), "off with the heartbeat");
        assertEquals(base * HollowTone.THUMP_SHARE * 0.25, HollowTone.thumpVolume(base, 1, HollowTone.muffle(1)),
                1e-12, "muffled");
        // Fainter than the heartbeat near the node, so the heartbeat stays the sound to follow.
        assertTrue(HollowTone.thumpVolume(base, 1, 1) < HollowTone.beatVolume(base, 0, 8, 1));
    }

    @Test
    void theHeartbeatRisesALittleAsTheHollowCloses() {
        assertEquals(1, HollowTone.beatPitch(0), 1e-12);
        assertEquals(1 + HollowTone.BEAT_PITCH_RISE, HollowTone.beatPitch(1), 1e-12);
        assertEquals(1 + HollowTone.BEAT_PITCH_RISE, HollowTone.beatPitch(3), 1e-12);
    }

    @Test
    void thePullMufflesTheRest() {
        assertEquals(1, HollowTone.muffle(0), 1e-12);
        assertEquals(1 - HollowTone.MUFFLE_DEPTH, HollowTone.muffle(1), 1e-12);
        assertEquals(1, HollowTone.muffle(Double.NaN), 1e-12);
        assertTrue(HollowTone.muffle(0.3) > HollowTone.muffle(0.6));
    }

    @Test
    void theGroundSquelchesMoreOftenLouderAndDeeperTheDeeperThePlayerSinks() {
        assertFalse(HollowTone.pulling(0));
        assertFalse(HollowTone.pulling(HollowTone.PULL_SILENT));
        assertTrue(HollowTone.pulling(0.05));
        assertEquals(HollowTone.SLOWEST_PULL_TICKS, HollowTone.pullTicks(0));
        assertEquals(HollowTone.FASTEST_PULL_TICKS, HollowTone.pullTicks(1));
        assertTrue(HollowTone.pullTicks(0.5) < HollowTone.SLOWEST_PULL_TICKS
                && HollowTone.pullTicks(0.5) > HollowTone.FASTEST_PULL_TICKS);
        assertEquals(0.8 * HollowTone.QUIETEST_PULL, HollowTone.pullVolume(0.8, 0), 1e-12);
        assertEquals(0.8, HollowTone.pullVolume(0.8, 1), 1e-12);
        assertEquals(1, HollowTone.pullPitch(0), 1e-12);
        assertEquals(1 - HollowTone.PULL_PITCH_DROP, HollowTone.pullPitch(1), 1e-12);
        // The deepest squelch stays above the pitch the engine clamps to (0.5) for the sounds of the event (0.6).
        assertTrue(0.6 * HollowTone.pullPitch(1) >= 0.5);
    }
}
