package tremor.client.awakening;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tremor.core.shape.AwakeningParams;
import tremor.core.shape.AwakeningShape;

/**
 * The hill a victor comes out of on a client. The defaults of the server: the victory at 1000, the rise of 16 ticks
 * from 1004 (risen at 1020, when the victor is moved out), the victor's client learns of it at 1021 in the dark, the
 * screen comes back from 1033 over 20 ticks, and the hill settles from 1053 over 64 ticks.
 */
class EmergeClockTest {
    private static final AwakeningParams P = AwakeningParams.defaults();
    private static final double FULL = AwakeningShape.hillPeak(P, 1);
    private static final int RING_TICKS = 31;
    private static final int CALL_OFF_TICKS = 30;
    private static final double TPS = 20;

    @Test
    void theVictorIsInTheFullHillWhileTheScreenIsDarkAndAsItComesBack() {
        EmergeClock clock = new EmergeClock(RING_TICKS, CALL_OFF_TICKS);
        clock.learnRise(1004, 16, true, 1021);
        assertTrue(clock.known());
        assertTrue(Double.isNaN(clock.riseStart()), "never seen rising");
        assertTrue(Double.isNaN(clock.movesFrom()));
        for (long t = 1021; t < 1033; t++) {
            clock.update(true, t);
            assertEquals(FULL, clock.peak(P, t + 0.5), 1e-12, "the last frame of the swallowing, at " + t);
            assertEquals(0, clock.speed(P, t, TPS), "standing");
        }
        // The server lifts the blackout and plans the settling for when the screen is clear.
        clock.learnSettle(1053, 64, true, 1033);
        for (long t = 1033; t < 1053; t++) {
            clock.update(true, t);
            assertTrue(clock.waiting());
            assertEquals(FULL, clock.peak(P, t + 0.5), 1e-12, "the screen comes back on the full hill at " + t);
        }
        clock.update(false, 1053);
        assertFalse(clock.waiting());
        assertEquals(1053, clock.settleStart());
        assertEquals(1053, clock.movesFrom(), "the victor hears it as it starts to settle");
        assertEquals(FULL, clock.peak(P, 1053), 1e-12);
        assertEquals(AwakeningShape.emergeSettle(P, 0.5), clock.peak(P, 1085), 1e-12, "half way down");
        assertTrue(clock.speed(P, 1085, TPS) < 0);
        assertEquals(AwakeningShape.emergeSettleRate(P, 0.5) * TPS / 64, clock.speed(P, 1085, TPS), 1e-12);
        // Runs on after the server ended the phase, to its own end.
        clock.callOff(1100);
        clock.update(true, 1116);
        assertTrue(clock.known(), "a dark screen or the end on the server does not stop it");
        clock.update(false, 1117);
        assertFalse(clock.known());
        assertEquals(0, clock.peak(P, 1117));
    }

    @Test
    void theVictorsHillDoesNotSinkBeforeTheScreenIsClear() {
        // A slow client: its screen is clear only at 1060, after the planned start.
        EmergeClock clock = new EmergeClock(RING_TICKS, CALL_OFF_TICKS);
        clock.learnRise(1004, 16, true, 1021);
        clock.learnSettle(1053, 64, true, 1033);
        for (long t = 1033; t < 1060; t++) {
            clock.update(true, t);
            assertEquals(FULL, clock.peak(P, t), 1e-12);
        }
        clock.update(false, 1060);
        assertEquals(1060, clock.settleStart());
        // Nor before the planned start if it is clear earlier.
        EmergeClock early = new EmergeClock(RING_TICKS, CALL_OFF_TICKS);
        early.learnRise(1004, 16, true, 1021);
        early.learnSettle(1053, 64, true, 1033);
        early.update(false, 1040);
        assertEquals(1053, early.settleStart());
        assertEquals(FULL, early.peak(P, 1050), 1e-12);
    }

    @Test
    void anOnlookerSeesItRiseStandAndSettle() {
        EmergeClock clock = new EmergeClock(RING_TICKS, CALL_OFF_TICKS);
        assertFalse(clock.known());
        assertEquals(0, clock.peak(P, 5));
        clock.learnRise(1004, 16, false, 1005);
        clock.update(false, 1005);
        assertEquals(1004, clock.riseStart());
        assertEquals(1004, clock.movesFrom(), "the rumble as it rises");
        assertEquals(0, clock.peak(P, 1000), "not before it starts");
        assertEquals(AwakeningShape.emergeRise(P, 0.5), clock.peak(P, 1012), 1e-12);
        assertTrue(clock.speed(P, 1012, TPS) > 0);
        for (long t = 1020; t < 1100; t += 10) {
            clock.update(false, t);
            assertEquals(FULL, clock.peak(P, t), 1e-12, "stands at " + t);
            assertEquals(0, clock.speed(P, t, TPS));
        }
        clock.learnSettle(1120, 64, false, 1100);
        assertFalse(clock.waiting(), "an onlooker does not wait for anybody's screen");
        assertEquals(FULL, clock.peak(P, 1110), 1e-12, "stands until the planned start");
        assertEquals(AwakeningShape.emergeSettle(P, 0.25), clock.peak(P, 1136), 1e-12);
        clock.update(false, 1183);
        assertTrue(clock.known());
        clock.update(false, 1184);
        assertFalse(clock.known());
    }

    @Test
    void aHillCalledOffSettlesAtOnceFromWhereItGotTo() {
        EmergeClock clock = new EmergeClock(RING_TICKS, CALL_OFF_TICKS);
        clock.learnRise(1004, 16, false, 1004);
        clock.callOff(1030);
        assertEquals(1030, clock.settleStart());
        assertEquals(FULL, clock.peak(P, 1030), 1e-12);
        assertEquals(0, clock.peak(P, 1030 + CALL_OFF_TICKS), 1e-12);
        clock.update(false, 1030 + CALL_OFF_TICKS);
        assertFalse(clock.known());
        // Called off half way up: it sinks from there, without a jump up.
        EmergeClock rising = new EmergeClock(RING_TICKS, CALL_OFF_TICKS);
        rising.learnRise(1004, 16, false, 1004);
        double half = rising.peak(P, 1012);
        rising.callOff(1012);
        assertEquals(half, rising.peak(P, 1012), 1e-12);
        assertTrue(rising.peak(P, 1020) < half);
        // The ring that burst out as it started to rise runs to its end first.
        EmergeClock ringing = new EmergeClock(100, CALL_OFF_TICKS);
        ringing.learnRise(1004, 16, false, 1004);
        ringing.callOff(1012);
        ringing.update(false, 1012 + CALL_OFF_TICKS);
        assertTrue(ringing.known());
        assertEquals(0, ringing.peak(P, 1012 + CALL_OFF_TICKS), 1e-12);
        ringing.update(false, 1004 + 100);
        assertFalse(ringing.known());
        // A settling known already goes on as it is.
        EmergeClock settling = new EmergeClock(RING_TICKS, CALL_OFF_TICKS);
        settling.learnRise(1004, 16, false, 1004);
        settling.learnSettle(1040, 64, false, 1030);
        settling.callOff(1050);
        assertEquals(1040, settling.settleStart());
    }

    @Test
    void aHillLearntOnlyAsItSettlesStandsUntilThen() {
        // A player who comes near (or logs back in) while it stands.
        EmergeClock clock = new EmergeClock(RING_TICKS, CALL_OFF_TICKS);
        clock.learnSettle(1060, 64, false, 1050);
        assertTrue(clock.known());
        assertEquals(FULL, clock.peak(P, 1055), 1e-12);
        assertEquals(1060, clock.movesFrom());
        assertEquals(AwakeningShape.emergeSettle(P, 0.5), clock.peak(P, 1092), 1e-12);
    }

    @Test
    void aHillThatStandsOrWaitsForeverSettlesByItself() {
        EmergeClock clock = new EmergeClock(RING_TICKS, CALL_OFF_TICKS);
        clock.learnRise(1004, 16, true, 1021);
        clock.update(true, 1021 + EmergeClock.MAX_WAIT_TICKS);
        assertTrue(Double.isNaN(clock.settleStart()));
        clock.update(true, 1022 + EmergeClock.MAX_WAIT_TICKS);
        assertEquals(1022 + EmergeClock.MAX_WAIT_TICKS, clock.settleStart());

        EmergeClock waiting = new EmergeClock(RING_TICKS, CALL_OFF_TICKS);
        waiting.learnRise(1004, 16, true, 1021);
        waiting.learnSettle(1053, 64, true, 1033);
        waiting.update(true, 1033 + EmergeClock.MAX_WAIT_TICKS);
        assertTrue(waiting.waiting());
        waiting.update(true, 1034 + EmergeClock.MAX_WAIT_TICKS);
        assertFalse(waiting.waiting());
        assertEquals(1034 + EmergeClock.MAX_WAIT_TICKS, waiting.settleStart());
    }

    @Test
    void anotherHillReplacesTheLast() {
        EmergeClock clock = new EmergeClock(RING_TICKS, CALL_OFF_TICKS);
        clock.learnRise(1004, 16, true, 1021);
        clock.learnSettle(1053, 64, true, 1033);
        clock.learnRise(2000, 16, false, 2000);
        assertFalse(clock.waiting());
        assertEquals(2000, clock.riseStart());
        assertTrue(Double.isNaN(clock.settleStart()));
        assertEquals(AwakeningShape.emergeRise(P, 0.5), clock.peak(P, 2008), 1e-12);
        clock.forget();
        assertFalse(clock.known());
        clock.update(false, 2100);
        assertFalse(clock.known());
        // A rise of no length stands at once.
        clock.learnRise(3000, 0, false, 3000);
        assertEquals(FULL, clock.peak(P, 3000), 1e-12);
    }
}
