package tremor.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import tremor.core.shape.RippleParams;
import tremor.network.TremorStatePayload;

class RippleTimerTest {
    @Test
    void aRippleRunsAsLongAsTheClientDrawsIt() {
        assertEquals(RippleParams.echo().duration() * 20, RippleTimer.RUN_TICKS, 1e-9);
    }

    @Test
    void aFreezeStartsARippleOnlyOnceTheLastHasRunOut() {
        RippleTimer timer = new RippleTimer();
        assertEquals(TremorStatePayload.NO_RIPPLE, timer.age(100));
        assertTrue(timer.freeze(100, false));
        // It draws in first: no ring yet, but running.
        assertEquals(TremorStatePayload.NO_RIPPLE, timer.age(100));
        assertTrue(timer.running(100));
        long ring = 100 + RippleTimer.CHARGE_TICKS;
        assertEquals(0, timer.age(ring));
        // Another freeze while it runs leaves it alone.
        assertFalse(timer.freeze(110, false));
        assertEquals(10, timer.age(ring + 10));
        long end = ring + RippleTimer.RUN_TICKS;
        assertFalse(timer.freeze(end - 1, false));
        assertTrue(timer.running(end - 1));
        assertFalse(timer.running(end));
        assertTrue(timer.freeze(end, false));
        assertEquals(0, timer.age(end + RippleTimer.CHARGE_TICKS));
    }

    @Test
    void noRippleStartsWhileDiving() {
        RippleTimer timer = new RippleTimer();
        assertFalse(timer.freeze(100, true));
        assertEquals(TremorStatePayload.NO_RIPPLE, timer.age(100));
        assertTrue(timer.freeze(101, false));
        // One that is running goes on.
        assertFalse(timer.freeze(105, true));
        assertEquals(4, timer.age(101 + RippleTimer.CHARGE_TICKS + 4));
    }

    @Test
    void underSteadyNoiseRipplesFollowEachOther() {
        RippleTimer timer = new RippleTimer();
        List<Long> starts = new ArrayList<>();
        // A walking player: a freeze at every step, about every 8 ticks.
        for (long t = 0; t < 400; t += 8) {
            if (timer.freeze(t, false)) {
                starts.add(t);
            }
            int age = timer.age(t);
            assertTrue(age == TremorStatePayload.NO_RIPPLE || age < RippleTimer.RUN_TICKS);
        }
        // Each ripple runs out, and the next step after that starts the next one.
        long interval = (RippleTimer.CHARGE_TICKS + RippleTimer.RUN_TICKS + 7) / 8 * 8;
        assertTrue(starts.size() >= 3, starts::toString);
        for (int i = 0; i < starts.size(); i++) {
            assertEquals(i * interval, starts.get(i), starts::toString);
        }
    }

    @Test
    void theAgeIsCappedAndNeverNegative() {
        RippleTimer timer = new RippleTimer();
        timer.freeze(1000, false);
        assertEquals(TremorStatePayload.NO_RIPPLE, timer.age(999));
        long ring = 1000 + RippleTimer.CHARGE_TICKS;
        assertEquals(TremorStatePayload.NO_RIPPLE - 1, timer.age(ring + TremorStatePayload.NO_RIPPLE - 1));
        assertEquals(TremorStatePayload.NO_RIPPLE, timer.age(ring + TremorStatePayload.NO_RIPPLE));
    }
}
