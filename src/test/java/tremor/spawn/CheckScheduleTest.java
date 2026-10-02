package tremor.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tremor.core.math.Vec3;

class CheckScheduleTest {
    private static final int INTERVAL = 600;
    private static final int GRACE = 1200;

    private static UUID player(int n) {
        return new UUID(0, n);
    }

    /** The ticks in [from, to) at which the player's check is due, polling every tick as the spawner does. */
    private static List<Long> dueTicks(CheckSchedule schedule, UUID player, long from, long to) {
        return dueTicks(schedule, player, from, to, INTERVAL, GRACE);
    }

    private static List<Long> dueTicks(CheckSchedule schedule, UUID player, long from, long to, int interval,
                                       int grace) {
        List<Long> ticks = new ArrayList<>();
        for (long t = from; t < to; t++) {
            if (schedule.due(player, t, interval, grace)) {
                ticks.add(t);
            }
        }
        return ticks;
    }

    /**
     * The ticks in [from, to) at which the checks of players 0..count-1 are due, polling them all every tick as the
     * spawner does.
     */
    private static Map<Integer, List<Long>> dueTicks(CheckSchedule schedule, int count, long from, long to,
                                                     int interval, int grace) {
        Map<Integer, List<Long>> due = new HashMap<>();
        for (long t = from; t < to; t++) {
            for (int n = 0; n < count; n++) {
                if (schedule.due(player(n), t, interval, grace)) {
                    due.computeIfAbsent(n, k -> new ArrayList<>()).add(t);
                }
            }
        }
        return due;
    }

    @Test
    void aLonePlayerIsFirstCheckedAfterTheGraceThenEveryInterval() {
        CheckSchedule schedule = new CheckSchedule();
        UUID p = player(1);
        assertEquals(List.of(1000L + GRACE, 1000L + GRACE + INTERVAL, 1000L + GRACE + 2 * INTERVAL),
                dueTicks(schedule, p, 1000, 1000 + GRACE + 2 * INTERVAL + 1));
        // A grace of 0 checks at the first sight.
        CheckSchedule eager = new CheckSchedule();
        assertTrue(eager.due(p, 50, INTERVAL, 0));
        assertFalse(eager.due(p, 51, INTERVAL, 0));
        assertTrue(eager.due(p, 50 + INTERVAL, INTERVAL, 0));
    }

    @Test
    void playersSeenOnTheSameTickAreSpreadEvenly() {
        CheckSchedule schedule = new CheckSchedule();
        for (int n = 0; n < 4; n++) {
            assertFalse(schedule.due(player(n), 0, INTERVAL, GRACE));
        }
        Map<UUID, List<Long>> due = new HashMap<>();
        for (long t = 1; t < GRACE + 2 * INTERVAL; t++) {
            for (int n = 0; n < 4; n++) {
                if (schedule.due(player(n), t, INTERVAL, GRACE)) {
                    due.computeIfAbsent(player(n), k -> new ArrayList<>()).add(t);
                }
            }
        }
        // The first at the end of the grace, each other one in the middle of the largest gap left (phases 0, 300,
        // then 450 on the tie between 0-300 and 300-600, then 150).
        assertEquals(List.of(1200L, 1800L), due.get(player(0)));
        assertEquals(List.of(1500L, 2100L), due.get(player(1)));
        assertEquals(List.of(1650L, 2250L), due.get(player(2)));
        assertEquals(List.of(1350L, 1950L), due.get(player(3)));
    }

    @Test
    void noTwoPlayersShareATickWhileThereAreFewerThanTicks() {
        CheckSchedule schedule = new CheckSchedule();
        int interval = 40;
        // Arrival ticks, several on the same tick.
        int[] arrivals = {0, 0, 0, 3, 7, 7, 15, 22, 22, 22, 31, 39, 40, 41, 77, 100};
        int arrived = 0;
        Set<Long> phases = new HashSet<>();
        Map<Integer, Long> firstDue = new HashMap<>();
        for (long t = 0; t < 400; t++) {
            while (arrived < arrivals.length && arrivals[arrived] == t) {
                arrived++;
            }
            for (int n = 0; n < arrived; n++) {
                if (schedule.due(player(n), t, interval, 0) && firstDue.putIfAbsent(n, t) == null) {
                    phases.add(Math.floorMod(t, (long) interval));
                }
            }
        }
        assertEquals(arrivals.length, firstDue.size());
        assertEquals(arrivals.length, phases.size(), "two players share a check tick");
        // Each one's first check comes within one interval of its arrival.
        for (int n = 0; n < arrivals.length; n++) {
            long first = firstDue.get(n);
            assertTrue(first >= arrivals[n] && first < arrivals[n] + interval, "player " + n + " first at " + first);
        }
    }

    @Test
    void firstCheckTakesTheMiddleOfTheLargestGapAroundTheInterval() {
        assertEquals(500, CheckSchedule.firstCheck(500, 100, new long[0]));
        // One scheduled at phase 10: the gap is the whole interval, its middle phase 60.
        assertEquals(560, CheckSchedule.firstCheck(500, 100, new long[]{710}));
        assertEquals(560, CheckSchedule.firstCheck(560, 100, new long[]{710}));
        assertEquals(660, CheckSchedule.firstCheck(561, 100, new long[]{710}));
        // Phases 10 and 30: the gap 30 -> 110 (wrapping) is the largest, middle 70.
        assertEquals(570, CheckSchedule.firstCheck(500, 100, new long[]{110, 230}));
        // Phases 0 and 50 tie: the wrapping gap 50 -> 100 wins, middle 75.
        assertEquals(575, CheckSchedule.firstCheck(500, 100, new long[]{600, 650}));
        // Phases 0, 50, 75: the gap 0 -> 50 is the largest, middle 25.
        assertEquals(525, CheckSchedule.firstCheck(500, 100, new long[]{600, 650, 675}));
        // Negative times, a shared phase (95): the middle of the rest is phase 45.
        assertEquals(-55, CheckSchedule.firstCheck(-90, 100, new long[]{-105, -5}));
        // Every tick taken: one is shared.
        assertEquals(3, CheckSchedule.firstCheck(3, 2, new long[]{4, 5}));
    }

    @Test
    void aShorterIntervalAppliesAtOnceKeepingTheOrderAndSpread() {
        CheckSchedule schedule = new CheckSchedule();
        // First checks at 1200 (player 0), 1500 (1), 1650 (2), 1350 (3), as above; the next ones 600 later.
        Map<Integer, List<Long>> due = dueTicks(schedule, 4, 0, 1700, INTERVAL, GRACE);
        assertEquals(List.of(1200L), due.get(0));
        assertEquals(List.of(1650L), due.get(2));
        // At 1700 the interval becomes 30: the waits 100, 400, 550, 250 become 5, 20, 27, 12.
        due = dueTicks(schedule, 4, 1700, 1760, 30, GRACE);
        assertEquals(List.of(1705L, 1735L), due.get(0));
        assertEquals(List.of(1720L, 1750L), due.get(1));
        assertEquals(List.of(1727L, 1757L), due.get(2));
        assertEquals(List.of(1712L, 1742L), due.get(3));
    }

    @Test
    void aLongerIntervalAppliesAtOnceToo() {
        CheckSchedule schedule = new CheckSchedule();
        // Player 0 at 0, 40, 80; player 1 opposite, at 20, 60.
        Map<Integer, List<Long>> due = dueTicks(schedule, 2, 0, 90, 40, 0);
        assertEquals(List.of(0L, 40L, 80L), due.get(0));
        assertEquals(List.of(20L, 60L), due.get(1));
        // At 90 the interval becomes 400: the waits 30 and 10 become 300 and 100.
        due = dueTicks(schedule, 2, 90, 700, 400, 0);
        assertEquals(List.of(390L), due.get(0));
        assertEquals(List.of(190L, 590L), due.get(1));
    }

    @Test
    void aPlayerInItsGraceKeepsItsFirstCheckWhenTheIntervalChanges() {
        CheckSchedule schedule = new CheckSchedule();
        UUID checked = player(0), waiting = player(1);
        assertTrue(schedule.due(checked, 0, INTERVAL, 0));
        // Phase 300, opposite the checked one, from the end of its grace on.
        assertFalse(schedule.due(waiting, 0, INTERVAL, GRACE));
        // At 10 the interval becomes 30: the checked one's wait of 590 becomes 29, the grace stays.
        assertEquals(List.of(39L, 69L), dueTicks(schedule, checked, 10, 90, 30, GRACE));
        assertEquals(List.of(1500L, 1530L), dueTicks(schedule, waiting, 90, 1531, 30, GRACE));
    }

    @Test
    void positionsAreSwappedAtEachCheck() {
        CheckSchedule schedule = new CheckSchedule();
        UUID p = player(1);
        assertThrows(IllegalArgumentException.class, () -> schedule.swapPosition(p, Vec3.ZERO));
        assertNull(schedule.lastPosition(p));
        assertTrue(schedule.due(p, 0, INTERVAL, 0));
        assertNull(schedule.swapPosition(p, new Vec3(1, 2, 3)));
        assertEquals(new Vec3(1, 2, 3), schedule.lastPosition(p));
        assertEquals(new Vec3(1, 2, 3), schedule.swapPosition(p, new Vec3(4, 5, 6)));
        assertEquals(new Vec3(4, 5, 6), schedule.lastPosition(p));
    }

    @Test
    void aPlayerWhoLeftIsANewcomerWhenBack() {
        CheckSchedule schedule = new CheckSchedule();
        UUID a = player(1), b = player(2);
        assertTrue(schedule.due(a, 0, INTERVAL, 0));
        schedule.swapPosition(a, new Vec3(1, 2, 3));
        // b: phase 300.
        assertFalse(schedule.due(b, 0, INTERVAL, 0));
        assertEquals(2, schedule.size());
        schedule.retain(Set.of(b));
        assertEquals(1, schedule.size());
        assertNull(schedule.lastPosition(a));
        // Back at tick 5000: a grace again, then the phase opposite b's.
        assertEquals(List.of(6600L), dueTicks(schedule, a, 5000, 5000 + GRACE + INTERVAL));
    }

    @Test
    void badArgumentsAreRejected() {
        CheckSchedule schedule = new CheckSchedule();
        assertThrows(IllegalArgumentException.class, () -> schedule.due(player(1), 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> schedule.due(player(1), 0, 10, -1));
    }
}
