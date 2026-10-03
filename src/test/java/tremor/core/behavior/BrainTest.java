package tremor.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tremor.core.behavior.Stage.ALERT;
import static tremor.core.behavior.Stage.AWAKENING;
import static tremor.core.behavior.Stage.DORMANT;
import static tremor.core.behavior.Stage.HUNTING;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;
import tremor.core.math.Vec3;

class BrainTest {
    /** Exact in binary, so sums of ticks hit the timers exactly. */
    private static final double DT = 0.25;

    private static final Vec3 HOME = new Vec3(0, 64, 0);
    private static final Vec3 S1 = new Vec3(10, 64, 0);
    private static final Vec3 S2 = new Vec3(-6, 64, 12);
    private static final Vec3 S3 = new Vec3(3, 60, -9);
    private static final Vec3 A = new Vec3(20, 64, 20);
    private static final Vec3 B = new Vec3(-25, 64, 5);
    private static final Vec3 W1 = new Vec3(30, 64, -18);
    private static final Vec3 P1 = new Vec3(14, 64, 3);
    private static final Vec3 P2 = new Vec3(7, 64, -5);
    private static final Vec3 P3 = new Vec3(12, 64, 6);
    private static final Vec3 P4 = new Vec3(5, 64, 2);

    /**
     * SPEC anger numbers; react to DORMANT sounds from 0.35, freeze 1 s, lose interest after 4 s, search radius 8 for
     * 5 s, the given mean wander pause, keep 24 blocks from the players while DORMANT (and AWAKENING), search radius 3
     * while AWAKENING; ALERT and HUNTING wandering random ({@code wanderKeepAway} off).
     */
    private static BehaviorParams params(double wanderPause) {
        return params(wanderPause, 24, false);
    }

    /** {@link #params(double)} with the given {@code minWanderDistance} and {@code wanderKeepAway}. */
    private static BehaviorParams params(double wanderPause, double minWanderDistance, boolean wanderKeepAway) {
        return new BehaviorParams(25, 60, 100, 3, 0.5, 20, 3, 0.35, 1.0, 4.0, 8, 5.0, wanderPause, minWanderDistance, 3,
                wanderKeepAway);
    }

    /** Scripted world: answers from queues (a null entry or an empty queue = nothing found), records questions. */
    private static final class FakeWorld implements BrainWorld {
        final LinkedList<Vec3> wanderAnswers = new LinkedList<>();
        final LinkedList<Vec3> searchAnswers = new LinkedList<>();
        final List<Vec3> wanderFrom = new ArrayList<>();
        final List<Double> wanderMinDistance = new ArrayList<>();
        final List<Vec3> searchCenter = new ArrayList<>();
        final List<Double> searchRadius = new ArrayList<>();

        FakeWorld wander(Vec3... answers) {
            wanderAnswers.addAll(Arrays.asList(answers));
            return this;
        }

        FakeWorld search(Vec3... answers) {
            searchAnswers.addAll(Arrays.asList(answers));
            return this;
        }

        @Override
        public Vec3 wanderTarget(Vec3 from, double minDistanceToPlayer, RandomGenerator random) {
            assertNotNull(random);
            wanderFrom.add(from);
            wanderMinDistance.add(minDistanceToPlayer);
            return wanderAnswers.isEmpty() ? null : wanderAnswers.removeFirst();
        }

        @Override
        public Vec3 searchTarget(Vec3 center, double radius, RandomGenerator random) {
            assertNotNull(random);
            searchCenter.add(center);
            searchRadius.add(radius);
            return searchAnswers.isEmpty() ? null : searchAnswers.removeFirst();
        }
    }

    /** World that answers with points drawn from the brain's random generator (for the determinism test). */
    private static final class RandomWorld implements BrainWorld {
        @Override
        public Vec3 wanderTarget(Vec3 from, double minDistanceToPlayer, RandomGenerator random) {
            if (random.nextInt(5) == 0) {
                return null;
            }
            return from.add(random.nextDouble(-32, 32), 0, random.nextDouble(-32, 32));
        }

        @Override
        public Vec3 searchTarget(Vec3 center, double radius, RandomGenerator random) {
            return center.add(random.nextDouble(-radius, radius), 0, random.nextDouble(-radius, radius));
        }
    }

    private FakeWorld world = new FakeWorld();
    private Vec3 here = HOME;
    private Brain brain;

    private Decision tick(Stage stage, boolean idle) {
        return brain.tick(DT, stage, here, idle, world);
    }

    private void assertStays(int ticks, Stage stage, boolean idle, String reason) {
        for (int i = 0; i < ticks; i++) {
            assertStay(tick(stage, idle), reason);
        }
    }

    /** Ticks (idle) until the brain says something else than STAY; returns the number of ticks, that one included. */
    private int ticksUntilGo(Stage stage, Vec3 expectedTarget, String expectedReason) {
        for (int n = 1; n <= 200; n++) {
            Decision d = tick(stage, true);
            if (d.action() != Decision.Action.STAY) {
                assertGo(d, expectedTarget, expectedReason);
                return n;
            }
        }
        throw new AssertionError("no GO in 200 ticks");
    }

    private static void assertGo(Decision d, Vec3 target, String reason) {
        assertEquals(Decision.Action.GO, d.action(), d.toString());
        assertEquals(target, d.target(), d.toString());
        assertEquals(reason, d.reason(), d.toString());
        assertNull(d.facing());
    }

    private static void assertStay(Decision d, String reason) {
        assertEquals(Decision.Action.STAY, d.action(), d.toString());
        assertEquals(reason, d.reason(), d.toString());
    }

    private static void assertFreeze(Decision d, Vec3 facing) {
        assertEquals(Decision.Action.FREEZE, d.action(), d.toString());
        assertEquals(facing, d.facing(), d.toString());
        assertEquals("freeze", d.reason());
        assertNull(d.target());
    }

    /** ALERT: hears {@code sound}, freezes for the full second, and gets the creep GO (5 ticks, ends at t = 1 s). */
    private void freezeThenCreep(Vec3 sound) {
        brain.hear(sound, 0.2);
        for (int i = 0; i < 4; i++) {
            assertFreeze(tick(ALERT, true), sound);
        }
        assertGo(tick(ALERT, true), sound, "creep");
    }

    // ---------------------------------------------------------------- DORMANT

    @Test
    void dormantWandersAfterARandomizedPause() {
        brain = new Brain(params(2), 7);
        world.wander(A, B);
        double waited = ticksUntilGo(DORMANT, A, "wander") * DT;
        assertTrue(waited >= 1 && waited < 3 + DT, "first pause " + waited);
        assertEquals(List.of(HOME), world.wanderFrom);
        assertEquals(List.of(24.0), world.wanderMinDistance, "DORMANT keeps minWanderDistance from the player");
        assertEquals("dormant: wandering", brain.describe());

        assertStays(40, DORMANT, false, "wander"); // GO is emitted once, not on every tick of the leg
        assertEquals(1, world.wanderFrom.size());

        here = A;
        // The arrival tick starts the pause; it runs on the following ticks.
        waited = (ticksUntilGo(DORMANT, B, "wander") - 1) * DT;
        assertTrue(waited >= 1 && waited < 3 + DT, "second pause " + waited);
        assertEquals(List.of(HOME, A), world.wanderFrom);
    }

    @Test
    void wanderPauseIsRandomizedAroundTheMean() {
        Set<Integer> seen = new HashSet<>();
        for (long seed = 0; seed < 30; seed++) {
            world = new FakeWorld().wander(A);
            brain = new Brain(params(2), seed);
            int ticks = ticksUntilGo(DORMANT, A, "wander");
            double waited = ticks * DT;
            assertTrue(waited >= 1 && waited < 3 + DT, "seed " + seed + ": " + waited);
            seen.add(ticks);
        }
        assertTrue(seen.size() >= 3, "pauses " + seen);
    }

    @Test
    void wanderPauseRunsOnlyWhileIdle() {
        brain = new Brain(params(2), 3);
        world.wander(A);
        assertStays(100, DORMANT, false, "rest"); // the body still follows an older route
        assertTrue(world.wanderFrom.isEmpty());
        assertTrue(brain.describe().startsWith("dormant: wandering, pause "), brain.describe());
        assertTrue(ticksUntilGo(DORMANT, A, "wander") * DT >= 1);
    }

    @Test
    void noWanderTargetMeansAnotherPause() {
        brain = new Brain(params(2), 5);
        world.wander((Vec3) null, A);
        int ticks = 0;
        while (world.wanderFrom.isEmpty()) {
            assertStay(tick(DORMANT, true), "rest");
            assertTrue(++ticks < 100);
        }
        double retry = ticksUntilGo(DORMANT, A, "wander") * DT;
        assertTrue(retry >= 1 && retry < 3 + DT, "retry after " + retry);
        assertEquals(2, world.wanderFrom.size());
    }

    @Test
    void dormantIgnoresWeakSoundsButRemembersThem() {
        brain = new Brain(params(0), 1);
        world.wander(A);
        assertGo(tick(DORMANT, true), A, "wander"); // pause 0: the first leg at once
        brain.hear(S1, 0.2);
        assertStay(tick(DORMANT, false), "wander");
        assertEquals(S1, brain.lastHeard());
        assertEquals(0.2, brain.lastHeardLoudness());
        assertEquals(0, brain.secondsSinceHeard());
        assertStays(4, DORMANT, false, "wander");
        assertEquals(1.0, brain.secondsSinceHeard(), 1e-12);
        assertEquals(1, world.wanderFrom.size());
    }

    @Test
    void dormantInvestigatesStrongSoundsThenWandersAgain() {
        brain = new Brain(params(0), 1);
        world.wander(A, B);
        assertGo(tick(DORMANT, true), A, "wander");
        assertStays(3, DORMANT, false, "wander");
        brain.hear(S1, 0.35); // exactly dormantReactLoudness: goes
        assertGo(tick(DORMANT, false), S1, "investigate");
        assertEquals("dormant: investigating a sound", brain.describe());
        brain.hear(S2, 0.1); // weak: ignored on the way
        assertStays(6, DORMANT, false, "investigate");
        here = S1;
        assertGo(tick(DORMANT, true), B, "wander"); // arrived: back to wandering (pause 0 here)
        assertEquals(List.of(HOME, S1), world.wanderFrom);
    }

    @Test
    void dormantRestsAfterAnInvestigation() {
        brain = new Brain(params(2), 1);
        world.wander(A);
        brain.hear(S1, 0.8);
        assertGo(tick(DORMANT, true), S1, "investigate");
        assertStays(3, DORMANT, false, "investigate");
        here = S1;
        assertStay(tick(DORMANT, true), "rest");
        double waited = (ticksUntilGo(DORMANT, A, "wander")) * DT;
        assertTrue(waited >= 1 && waited < 3 + DT, "pause " + waited);
    }

    // ---------------------------------------------------------------- sounds

    @Test
    void loudestSoundOfTheTickWinsAndTiesGoToTheLatest() {
        brain = new Brain(params(100), 1);
        brain.hear(S1, 0.4);
        brain.hear(S2, 0.9);
        brain.hear(S3, 0.5);
        assertGo(tick(HUNTING, true), S2, "hunt");
        assertEquals(S2, brain.lastHeard());
        assertEquals(0.9, brain.lastHeardLoudness());
        brain.hear(S1, 0.5);
        brain.hear(S3, 0.5);
        assertGo(tick(HUNTING, false), S3, "hunt");
    }

    @Test
    void aSoundIsConsumedByTheNextTick() {
        brain = new Brain(params(100), 1);
        assertNull(brain.lastHeard());
        assertEquals(Double.POSITIVE_INFINITY, brain.secondsSinceHeard());
        brain.hear(S1, 0.3);
        assertGo(tick(HUNTING, true), S1, "hunt");
        assertStays(5, HUNTING, false, "hunt");
        assertEquals(1.25, brain.secondsSinceHeard(), 1e-12);
    }

    @Test
    void strongestSoundDecidesWhetherDormantReacts() {
        brain = new Brain(params(100), 1);
        brain.hear(S1, 0.5);
        brain.hear(S2, 0.1);
        assertGo(tick(DORMANT, true), S1, "investigate");
        brain.hear(S2, 0.1);
        brain.hear(S3, 0.2);
        assertStay(tick(DORMANT, false), "investigate");
        assertEquals(S3, brain.lastHeard());
    }

    // ---------------------------------------------------------------- ALERT

    @Test
    void alertFreezesFacingTheSoundThenCreepsThenListens() {
        brain = new Brain(params(100), 1);
        assertStays(4, ALERT, true, "rest");
        brain.hear(S1, 0.2);
        assertFreeze(tick(ALERT, true), S1); // t = 0
        assertFreeze(tick(ALERT, true), S1);
        assertFreeze(tick(ALERT, true), S1);
        assertEquals("alert: frozen 0.5 s", brain.describe());
        assertFreeze(tick(ALERT, true), S1);
        assertGo(tick(ALERT, true), S1, "creep"); // t = 1 s = alertFreezeSeconds
        assertEquals("alert: creeping to the sound", brain.describe());
        assertStays(3, ALERT, false, "creep");
        here = S1;
        assertStay(tick(ALERT, true), "listen"); // t = 2 s
        assertEquals("alert: listening, loses interest in 2.0 s", brain.describe());
        assertStays(3, ALERT, true, "listen");
        assertTrue(world.wanderFrom.isEmpty());
    }

    @Test
    void newSoundDuringTheFreezeRestartsItAndTurns() {
        brain = new Brain(params(100), 1);
        brain.hear(S1, 0.2);
        assertFreeze(tick(ALERT, true), S1);
        assertFreeze(tick(ALERT, true), S1);
        brain.hear(S2, 0.15);
        for (int i = 0; i < 4; i++) {
            assertFreeze(tick(ALERT, true), S2);
        }
        assertGo(tick(ALERT, true), S2, "creep");
    }

    @Test
    void soundWhileCreepingFreezesAgain() {
        brain = new Brain(params(100), 1);
        freezeThenCreep(S1);
        assertStays(2, ALERT, false, "creep");
        brain.hear(S2, 0.2);
        assertFreeze(tick(ALERT, false), S2);
        assertFreeze(tick(ALERT, true), S2);
    }

    @Test
    void alertLosesInterestWhileListening() {
        brain = new Brain(params(0), 1);
        world.wander(W1);
        freezeThenCreep(S1); // t = 0 .. 1
        assertStay(tick(ALERT, false), "creep"); // 1.25
        here = S1;
        assertStay(tick(ALERT, true), "listen"); // 1.5
        assertStays(9, ALERT, true, "listen"); // 1.75 .. 3.75
        assertTrue(world.wanderFrom.isEmpty());
        assertGo(tick(ALERT, true), W1, "wander"); // 4.0 = alertLoseInterestSeconds; idle: pause (0 here), leg
        assertEquals(List.of(S1), world.wanderFrom);
        assertEquals(List.of(0.0), world.wanderMinDistance, "ALERT wandering is random (no wanderKeepAway)");
    }

    @Test
    void alertLosesInterestWhileCreepingAndWandersAtOnce() {
        brain = new Brain(params(100), 1); // a long pause: the leg must not wait for it
        world.wander(W1);
        freezeThenCreep(S1);
        assertStays(11, ALERT, false, "creep"); // 1.25 .. 3.75
        assertGo(tick(ALERT, false), W1, "wander"); // 4.0: the creep route is replaced
        assertEquals("alert: wandering", brain.describe());
        assertStays(3, ALERT, false, "wander");
        // Still ALERT: a sound while wandering freezes it again.
        brain.hear(S2, 0.12);
        assertFreeze(tick(ALERT, false), S2);
    }

    @Test
    void alertRestsAfterLosingInterestWhenNoWanderTarget() {
        brain = new Brain(params(100), 1);
        freezeThenCreep(S1);
        assertStays(11, ALERT, false, "creep");
        assertStay(tick(ALERT, false), "rest"); // asked, nothing: finishes its route, then pauses
        assertEquals(1, world.wanderFrom.size());
        assertStays(10, ALERT, true, "rest");
    }

    // ---------------------------------------------------------------- HUNTING

    @Test
    void huntingGoesForEachSound() {
        brain = new Brain(params(100), 1);
        brain.hear(S1, 0.3);
        assertGo(tick(HUNTING, true), S1, "hunt");
        assertEquals("hunting: going for the sound", brain.describe());
        assertStays(3, HUNTING, false, "hunt");
        brain.hear(S2, 0.2);
        assertGo(tick(HUNTING, false), S2, "hunt");
        brain.hear(S2, 0.2);
        assertGo(tick(HUNTING, false), S2, "hunt"); // a new decision is a GO even toward the same point
        assertStay(tick(HUNTING, false), "hunt");
    }

    @Test
    void huntThenSearchThenWander() {
        brain = new Brain(params(100), 1);
        world.search(P1, P2, P3, P4).wander(W1);
        brain.hear(S1, 0.3);
        assertGo(tick(HUNTING, true), S1, "hunt"); // t = 0
        assertStays(3, HUNTING, false, "hunt");
        here = S1;
        assertGo(tick(HUNTING, true), P1, "search"); // 1.0: nobody here, search around the sound
        assertEquals("hunting: searching, 4 s left", brain.describe());
        assertStays(3, HUNTING, false, "search");
        here = P1;
        assertGo(tick(HUNTING, true), P2, "search"); // 2.0
        assertEquals("hunting: searching, 3 s left", brain.describe());
        assertStays(3, HUNTING, false, "search");
        here = P2;
        assertGo(tick(HUNTING, true), P3, "search"); // 3.0
        assertStays(3, HUNTING, false, "search");
        here = P3;
        assertGo(tick(HUNTING, true), P4, "search"); // 4.0
        assertStays(3, HUNTING, false, "search");
        assertGo(tick(HUNTING, false), W1, "wander"); // 5.0 = huntSearchSeconds after the sound
        assertEquals(List.of(S1, S1, S1, S1), world.searchCenter);
        assertEquals(List.of(8.0, 8.0, 8.0, 8.0), world.searchRadius);
        assertEquals(List.of(P3), world.wanderFrom);
        assertEquals(List.of(0.0), world.wanderMinDistance, "HUNTING wandering is random (no wanderKeepAway)");
        assertEquals("hunting: wandering", brain.describe());
        here = W1;
        assertStays(10, HUNTING, true, "rest");
        assertEquals(4, world.searchCenter.size());
    }

    @Test
    void searchRetriesWhenNoPointIsFoundThenWanders() {
        brain = new Brain(params(0), 1);
        world.wander(W1);
        brain.hear(S1, 0.3);
        assertGo(tick(HUNTING, true), S1, "hunt"); // 0
        assertStays(3, HUNTING, false, "hunt");
        here = S1;
        assertStay(tick(HUNTING, true), "search"); // 1.0: asked, nothing
        assertEquals(1, world.searchCenter.size());
        assertStays(3, HUNTING, true, "search"); // waiting SEARCH_RETRY_SECONDS
        assertEquals(1, world.searchCenter.size());
        assertStay(tick(HUNTING, true), "search"); // 2.0: asked again
        assertEquals(2, world.searchCenter.size());
        assertStays(8, HUNTING, true, "search"); // 2.25 .. 4.0, asks at 3.0 and 4.0
        assertEquals(4, world.searchCenter.size());
        assertStays(3, HUNTING, true, "search");
        assertGo(tick(HUNTING, true), W1, "wander"); // 5.0: over; idle: pause (0 here), then the leg
        assertEquals(4, world.searchCenter.size());
    }

    @Test
    void huntArrivingAfterTheSearchWindowWandersWithoutSearching() {
        brain = new Brain(params(0), 1);
        world.search(P1).wander(W1);
        brain.hear(S1, 0.3);
        assertGo(tick(HUNTING, true), S1, "hunt");
        assertStays(20, HUNTING, false, "hunt"); // a long way: the hunt itself is never cut short
        here = S1;
        assertGo(tick(HUNTING, true), W1, "wander"); // 5.25 s after the sound
        assertTrue(world.searchCenter.isEmpty());
    }

    @Test
    void newSoundDuringTheSearchGoesForIt() {
        brain = new Brain(params(100), 1);
        world.search(P1, P2);
        brain.hear(S1, 0.3);
        assertGo(tick(HUNTING, true), S1, "hunt");
        here = S1;
        assertGo(tick(HUNTING, true), P1, "search");
        assertStays(2, HUNTING, false, "search");
        brain.hear(S2, 0.2);
        assertGo(tick(HUNTING, false), S2, "hunt");
        here = S2;
        assertGo(tick(HUNTING, true), P2, "search");
        assertEquals(List.of(S1, S2), world.searchCenter);
    }

    @Test
    void awakeningBehavesLikeHunting() {
        brain = new Brain(params(100), 1);
        world.search(P1, P2);
        brain.hear(S1, 0.3);
        assertGo(tick(AWAKENING, true), S1, "hunt");
        assertEquals("awakening: going for the sound", brain.describe());
        here = S1;
        assertGo(tick(AWAKENING, true), P1, "search");
        assertStay(tick(HUNTING, false), "search"); // AWAKENING <-> HUNTING changes nothing
        here = P1;
        assertGo(tick(HUNTING, true), P2, "search");
        // Seeking searches only seekSearchRadius around the sound (a player quietly farther is not found by chance).
        assertEquals(List.of(3.0, 8.0), world.searchRadius);
        assertEquals(List.of(S1, S1), world.searchCenter);
    }

    @Test
    void seekingWithoutASoundWandersAsFarFromThePlayerAsDormant() {
        // SPEC 8: a player who keeps quiet at the peak is not found by chance.
        brain = new Brain(params(0), 1);
        world.wander(A).search(P1).wander(W1);
        assertGo(tick(AWAKENING, true), A, "wander"); // nothing heard yet
        here = A;
        brain.hear(S1, 0.3);
        assertGo(tick(AWAKENING, true), S1, "hunt");
        here = S1;
        assertGo(tick(AWAKENING, true), P1, "search");
        assertStays(18, AWAKENING, false, "search");
        assertGo(tick(AWAKENING, false), W1, "wander"); // 5.0 = huntSearchSeconds after the sound
        assertEquals(List.of(24.0, 24.0), world.wanderMinDistance);
        assertEquals(List.of(3.0), world.searchRadius);
        assertEquals("awakening: wandering", brain.describe());
    }

    @Test
    void wanderDistanceByStageAndTheKeepAwaySwitch() {
        // SPEC 5.6: DORMANT and AWAKENING keep minWanderDistance; ALERT and HUNTING wander at random (as a warden
        // roams) unless wanderKeepAway keeps them away too.
        for (boolean keepAway : new boolean[]{false, true}) {
            for (Stage stage : Stage.values()) {
                world = new FakeWorld().wander(A);
                brain = new Brain(params(0, 24, keepAway), 1);
                assertGo(tick(stage, true), A, "wander");
                boolean kept = keepAway || stage == DORMANT || stage == AWAKENING;
                assertEquals(List.of(kept ? 24.0 : 0.0), world.wanderMinDistance, stage + ", switch " + keepAway);
            }
        }
        // A minWanderDistance of 0 is passed on as it is, switch or not.
        world = new FakeWorld().wander(A);
        brain = new Brain(params(0, 0, true), 1);
        assertGo(tick(HUNTING, true), A, "wander");
        assertEquals(List.of(0.0), world.wanderMinDistance);
    }

    @Test
    void calmedDownFromSeekingItWandersAsTheSwitchSays() {
        // The seeking ran out (AWAKENING -> HUNTING) with nothing heard, then the anger fell to ALERT and DORMANT:
        // the roam goes on with each stage's distance (the review saw a calmed-down entity roam to a hidden player).
        for (boolean keepAway : new boolean[]{false, true}) {
            world = new FakeWorld().wander(A, B, W1, S1);
            here = HOME;
            brain = new Brain(params(0, 24, keepAway), 1);
            assertGo(tick(AWAKENING, true), A, "wander");
            assertStay(tick(HUNTING, false), "wander"); // the leg under way goes on
            here = A;
            assertGo(tick(HUNTING, true), B, "wander");
            assertEquals("hunting: wandering", brain.describe());
            here = B;
            assertGo(tick(ALERT, true), W1, "wander");
            here = W1;
            assertGo(tick(DORMANT, true), S1, "wander");
            double hunting = keepAway ? 24 : 0;
            assertEquals(List.of(24.0, hunting, hunting, 24.0), world.wanderMinDistance, "switch " + keepAway);
            assertTrue(world.searchCenter.isEmpty(), "no search without a sound");
        }
    }

    @Test
    void theSwitchLeavesSoundsAndSearchesAlone() {
        // Going for a sound and searching around it are no wandering: nothing asks for a wander leg.
        brain = new Brain(params(100, 24, true), 1);
        world.search(P1);
        brain.hear(S1, 0.3);
        assertGo(tick(HUNTING, true), S1, "hunt");
        here = S1;
        assertGo(tick(HUNTING, true), P1, "search");
        assertEquals(List.of(8.0), world.searchRadius);
        assertTrue(world.wanderMinDistance.isEmpty());
    }

    // ---------------------------------------------------------------- stage changes

    @Test
    void frozenAlertBecomingHuntingGoesStraightForTheSound() {
        brain = new Brain(params(100), 1);
        brain.hear(S1, 0.2);
        assertFreeze(tick(ALERT, true), S1);
        assertFreeze(tick(ALERT, true), S1);
        assertGo(tick(HUNTING, true), S1, "hunt");
        assertStays(2, HUNTING, false, "hunt");
    }

    @Test
    void creepingAlertBecomingHuntingKeepsItsRoute() {
        brain = new Brain(params(100), 1);
        freezeThenCreep(S1);
        assertStay(tick(ALERT, false), "creep");
        assertStay(tick(HUNTING, false), "hunt"); // same target: no new GO
        assertEquals("hunting: going for the sound", brain.describe());
    }

    @Test
    void listeningAlertBecomingHuntingSearches() {
        brain = new Brain(params(100), 1);
        world.search(P1);
        freezeThenCreep(S1);
        here = S1;
        assertStay(tick(ALERT, true), "listen");
        assertGo(tick(HUNTING, true), P1, "search");
        assertEquals(List.of(S1), world.searchCenter);
    }

    @Test
    void huntingDroppingToAlertCreepsOnThenListens() {
        brain = new Brain(params(100), 1);
        brain.hear(S1, 0.3);
        assertGo(tick(HUNTING, true), S1, "hunt");
        assertStay(tick(HUNTING, false), "hunt");
        assertStay(tick(ALERT, false), "creep"); // same target: no new GO
        assertEquals("alert: creeping to the sound", brain.describe());
        here = S1;
        assertStay(tick(ALERT, true), "listen");
    }

    @Test
    void searchingDroppingToAlertFinishesTheLegAndListens() {
        brain = new Brain(params(100), 1);
        world.search(P1, P2);
        brain.hear(S1, 0.3);
        assertGo(tick(HUNTING, true), S1, "hunt");
        here = S1;
        assertGo(tick(HUNTING, true), P1, "search");
        assertStay(tick(ALERT, false), "listen");
        here = P1;
        assertStays(4, ALERT, true, "listen");
        assertEquals(1, world.searchCenter.size());
    }

    @Test
    void droppingToDormantEndsTheSearch() {
        brain = new Brain(params(100), 1);
        world.search(P1, P2).wander(W1);
        brain.hear(S1, 0.3);
        assertGo(tick(HUNTING, true), S1, "hunt");
        here = S1;
        assertGo(tick(HUNTING, true), P1, "search");
        assertStay(tick(HUNTING, false), "search");
        assertGo(tick(DORMANT, false), W1, "wander"); // moving: the search leg is replaced at once
        assertEquals(List.of(24.0), world.wanderMinDistance);
        here = W1;
        assertStays(8, DORMANT, true, "rest");
        assertTrue(brain.describe().startsWith("dormant: wandering, pause "), brain.describe());
        assertEquals(1, world.searchCenter.size());
    }

    @Test
    void droppingToDormantWhileFrozenRests() {
        brain = new Brain(params(100), 1);
        brain.hear(S1, 0.2);
        assertFreeze(tick(ALERT, true), S1);
        assertStay(tick(DORMANT, true), "rest"); // idle: the pause comes first
        assertStays(8, DORMANT, true, "rest");
        assertTrue(world.wanderFrom.isEmpty());
    }

    @Test
    void droppingToDormantWhileListeningWandersAfterThePause() {
        brain = new Brain(params(0), 1);
        world.wander(W1);
        freezeThenCreep(S1);
        here = S1;
        assertStay(tick(ALERT, true), "listen");
        assertGo(tick(DORMANT, true), W1, "wander");
        assertEquals(List.of(24.0), world.wanderMinDistance);
    }

    @Test
    void dormantInvestigationBecomesAHunt() {
        brain = new Brain(params(100), 1);
        world.search(P1);
        brain.hear(S1, 0.5);
        assertGo(tick(DORMANT, true), S1, "investigate");
        assertStay(tick(HUNTING, false), "hunt");
        here = S1;
        assertGo(tick(HUNTING, true), P1, "search");
    }

    @Test
    void dormantInvestigationBecomesACreep() {
        brain = new Brain(params(100), 1);
        brain.hear(S1, 0.5);
        assertGo(tick(DORMANT, true), S1, "investigate");
        assertStay(tick(ALERT, false), "creep");
        here = S1;
        assertStay(tick(ALERT, true), "listen");
    }

    @Test
    void investigationTurningAlertCreepsToTheLastHeardPosition() {
        brain = new Brain(params(100), 1);
        brain.hear(S1, 0.5);
        assertGo(tick(DORMANT, true), S1, "investigate");
        brain.hear(S2, 0.1); // ignored while DORMANT, but it is the last heard position
        assertStay(tick(DORMANT, false), "investigate");
        assertGo(tick(ALERT, false), S2, "creep");
    }

    @Test
    void wanderingEntityBecomingHuntingGoesForARecentSound() {
        brain = new Brain(params(0), 1);
        world.wander(A);
        assertGo(tick(DORMANT, true), A, "wander");
        brain.hear(S1, 0.2);
        assertStay(tick(DORMANT, false), "wander");
        assertStays(3, DORMANT, false, "wander");
        assertGo(tick(HUNTING, false), S1, "hunt");
    }

    @Test
    void wanderingEntityBecomingHuntingWithoutARecentSoundKeepsWandering() {
        brain = new Brain(params(0), 1);
        world.wander(A);
        assertGo(tick(DORMANT, true), A, "wander");
        assertStay(tick(HUNTING, false), "wander"); // never heard anything
        brain.hear(S1, 0.2);
        assertStay(tick(DORMANT, false), "wander");
        assertStays(20, DORMANT, false, "wander"); // 5 s
        assertStay(tick(HUNTING, false), "wander"); // 5.25 s old: too old to hunt
        assertTrue(world.searchCenter.isEmpty());
    }

    @Test
    void wanderingEntityBecomingAlertKeepsWandering() {
        brain = new Brain(params(0), 1);
        world.wander(A);
        assertGo(tick(DORMANT, true), A, "wander");
        brain.hear(S1, 0.2);
        assertStay(tick(DORMANT, false), "wander");
        assertStays(3, ALERT, false, "wander");
    }

    // ---------------------------------------------------------------- misc

    @Test
    void nonPositiveDtDoesNotAdvanceTimers() {
        brain = new Brain(params(100), 1);
        brain.hear(S1, 0.2);
        assertFreeze(brain.tick(DT, ALERT, here, true, world), S1);
        for (int i = 0; i < 5; i++) {
            assertFreeze(brain.tick(0, ALERT, here, true, world), S1);
        }
        assertFreeze(brain.tick(-1, ALERT, here, true, world), S1);
        assertFreeze(brain.tick(Double.NaN, ALERT, here, true, world), S1);
        assertEquals(0, brain.secondsSinceHeard());
        for (int i = 0; i < 3; i++) {
            assertFreeze(tick(ALERT, true), S1);
        }
        assertGo(tick(ALERT, true), S1, "creep");
    }

    @Test
    void describeBeforeTheFirstTick() {
        brain = new Brain(params(2), 1);
        assertTrue(brain.describe().startsWith("dormant: wandering, pause "), brain.describe());
    }

    @Test
    void rejectsNulls() {
        brain = new Brain(params(2), 1);
        assertThrows(NullPointerException.class, () -> brain.hear(null, 1));
        assertThrows(NullPointerException.class, () -> brain.tick(DT, null, here, true, world));
        assertThrows(NullPointerException.class, () -> brain.tick(DT, DORMANT, null, true, world));
        assertThrows(NullPointerException.class, () -> brain.tick(DT, DORMANT, here, true, null));
        assertThrows(NullPointerException.class, () -> new Brain(null, 1));
    }

    // ---------------------------------------------------------------- determinism

    /** A scripted 100 s session: stages, sounds, a body that needs 30 ticks per leg. */
    private static List<String> session(long seed) {
        Brain b = new Brain(BehaviorParams.defaults(), seed);
        BrainWorld w = new RandomWorld();
        List<String> log = new ArrayList<>();
        Vec3 pos = HOME;
        Vec3 dest = null;
        int busy = 0;
        for (int t = 0; t < 2000; t++) {
            Stage stage = t < 500 ? DORMANT : t < 1000 ? ALERT : t < 1500 ? HUNTING : DORMANT;
            if (t % 97 == 13) {
                b.hear(new Vec3(t % 23, 64, -(t % 17)), 0.2 * (1 + t % 3));
                b.hear(new Vec3(-(t % 11), 64, t % 7), 0.25);
            }
            Decision d = b.tick(0.05, stage, pos, busy == 0, w);
            log.add(d + " | " + b.describe());
            switch (d.action()) {
                case GO -> {
                    dest = d.target();
                    busy = 30;
                }
                case FREEZE -> busy = 0;
                case STAY -> {
                    if (busy > 0 && --busy == 0) {
                        pos = dest;
                    }
                }
            }
        }
        return log;
    }

    @Test
    void sameSeedAndInputsGiveTheSameDecisions() {
        List<String> first = session(42);
        assertEquals(first, session(42));
        assertTrue(first.stream().anyMatch(s -> s.contains("reason=wander")), "wanders");
        assertTrue(first.stream().anyMatch(s -> s.contains("reason=freeze")), "freezes");
        assertTrue(first.stream().anyMatch(s -> s.contains("reason=search")), "searches");
        assertNotEquals(first, session(43), "the seed matters");
    }
}
