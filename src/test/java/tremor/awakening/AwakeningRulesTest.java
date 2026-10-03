package tremor.awakening;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tremor.awakening.AwakeningRules.Cell.FLOOR;
import static tremor.awakening.AwakeningRules.Cell.OPEN;
import static tremor.awakening.AwakeningRules.Cell.UNSAFE;

import org.junit.jupiter.api.Test;
import tremor.core.math.Vec3;
import tremor.core.shape.AwakeningShape;
import tremor.hollow.HollowOutcome;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;

class AwakeningRulesTest {
    private static final Vec3 CENTER = new Vec3(100.5, 64, -20.5);
    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);

    // ---- zone and escape ----

    @Test
    void theZoneIsACylinder() {
        assertTrue(AwakeningRules.inZone(CENTER, 30, CENTER));
        assertTrue(AwakeningRules.inZone(CENTER, 30, CENTER.add(29.9, 0, 0)));
        // Height does not matter: neither a tower nor a shaft gets the player out.
        assertTrue(AwakeningRules.inZone(CENTER, 30, CENTER.add(0, 200, 0)));
        assertTrue(AwakeningRules.inZone(CENTER, 30, CENTER.add(10, -80, -10)));
        assertFalse(AwakeningRules.inZone(CENTER, 30, CENTER.add(0, 0, -30.01)));
        assertFalse(AwakeningRules.inZone(CENTER, 30, CENTER.add(21.3, 0, 21.3)));
    }

    @Test
    void theEdgeIsStillInside() {
        assertTrue(AwakeningRules.inZone(CENTER, 30, CENTER.add(18, 5, 24))); // 3-4-5: exactly 30 away
        assertFalse(AwakeningRules.inZone(CENTER, 30, CENTER.add(18, 5, 24.001)));
    }

    @Test
    void horizontalDistanceIgnoresHeight() {
        assertEquals(5, AwakeningRules.horizontalDistance(CENTER, CENTER.add(3, 100, -4)), 1e-9);
        assertEquals(0, AwakeningRules.horizontalDistance(CENTER, CENTER.add(0, -7, 0)), 1e-9);
    }

    // ---- reach and target ----

    /** The centre of the bump: in the floor block under {@link #feet}. */
    private static final Vec3 BUMP = new Vec3(100.5, 63.5, -20.5);
    private static final double REACH = 8;

    /** Where a player stands {@code dx}, {@code dz} from the bump, on the floor it crawls in. */
    private static Vec3 feet(double dx, double dz) {
        return BUMP.add(dx, 0.5, dz);
    }

    private static AwakeningRules.Candidate player(UUID id, double dx, double dz, boolean eligible) {
        return new AwakeningRules.Candidate(id, feet(dx, dz), eligible);
    }

    @Test
    void theReachIsHorizontalWithItsEdgeInside() {
        assertTrue(AwakeningRules.reaches(BUMP, feet(0, 0), REACH));
        assertTrue(AwakeningRules.reaches(BUMP, feet(0, 8), REACH)); // exactly 8 away
        assertTrue(AwakeningRules.reaches(BUMP, feet(-6, 3), REACH));
        assertFalse(AwakeningRules.reaches(BUMP, feet(0, 8.01), REACH));
        assertFalse(AwakeningRules.reaches(BUMP, feet(-20, 3), REACH));
        assertFalse(AwakeningRules.reaches(BUMP, new Vec3(Double.NaN, 64, -20.5), REACH));
    }

    @Test
    void theReachHasALimitedHeight() {
        // On the ceiling of a tall room right above the player, or under the player's pillar: reached.
        assertTrue(AwakeningRules.reaches(BUMP.add(0, AwakeningRules.REACH_HEIGHT, 0), BUMP, REACH));
        assertTrue(AwakeningRules.reaches(BUMP.add(2, -AwakeningRules.REACH_HEIGHT, 0), BUMP, REACH));
        // In the ground over a deep tunnel, or at the foot of a tower: not yet.
        assertFalse(AwakeningRules.reaches(BUMP.add(0, AwakeningRules.REACH_HEIGHT + 0.01, 0), BUMP, REACH));
        assertFalse(AwakeningRules.reaches(BUMP.add(1, -12, 1), BUMP, REACH));
    }

    @Test
    void theLastHeardPlayerIsTakenEvenIfAnotherIsNearer() {
        List<AwakeningRules.Candidate> players = List.of(player(A, 1, 1, true), player(B, 6, -3, true));
        assertEquals(B, AwakeningRules.chooseTarget(B, BUMP, players, REACH));
    }

    @Test
    void anUnfitOrUnreachedLastHeardPlayerFallsBackToTheNearestReached() {
        List<AwakeningRules.Candidate> players = List.of(player(A, 7, 0, true), player(B, 1, 0, false),
                player(C, 0, -5, true));
        // B is in creative mode, dead or in an event of the hollow.
        assertEquals(C, AwakeningRules.chooseTarget(B, BUMP, players, REACH));
        // B was heard last, but the bump has not reached B yet.
        List<AwakeningRules.Candidate> far = List.of(player(A, 7, 0, true), player(B, 30, 0, true));
        assertEquals(A, AwakeningRules.chooseTarget(B, BUMP, far, REACH));
        // Not in the level at all.
        assertEquals(A, AwakeningRules.chooseTarget(new UUID(9, 9), BUMP, far, REACH));
    }

    @Test
    void withoutALastHeardPlayerTheNearestReachedOneIsTaken() {
        List<AwakeningRules.Candidate> players = List.of(player(A, 6, 0, true), player(B, 0, 4, true),
                player(C, 1, 0, false));
        assertEquals(B, AwakeningRules.chooseTarget(null, BUMP, players, REACH));
        // Nearest horizontally: a player right above it on a ledge is nearer than one 3 blocks aside.
        List<AwakeningRules.Candidate> ledge = List.of(player(A, 3, 0, true),
                new AwakeningRules.Candidate(B, BUMP.add(0.5, 4, 0), true));
        assertEquals(B, AwakeningRules.chooseTarget(null, BUMP, ledge, REACH));
    }

    @Test
    void equallyNearPlayersGoInListOrder() {
        List<AwakeningRules.Candidate> players = List.of(player(A, 3, 4, true), player(B, -5, 0, true));
        assertEquals(A, AwakeningRules.chooseTarget(null, BUMP, players, REACH));
    }

    @Test
    void nobodyReachedMeansNoTargetYet() {
        assertNull(AwakeningRules.chooseTarget(null, BUMP, List.of(), REACH));
        // Heard last and eligible, but 9 blocks away: the entity seeks on.
        assertNull(AwakeningRules.chooseTarget(A, BUMP, List.of(player(A, 9, 0, true)), REACH));
        assertNull(AwakeningRules.chooseTarget(A, BUMP, List.of(player(A, 1, 0, false), player(B, 0, 40, true)),
                REACH));
        assertNull(AwakeningRules.chooseTarget(null, BUMP, List.of(new AwakeningRules.Candidate(A,
                BUMP.add(0, 20, 0), true)), REACH));
    }

    // ---- darkness ----

    @Test
    void theDarknessCoversTheLastThird() {
        assertEquals(400, AwakeningRules.darknessStart(600));
        assertEquals(14, AwakeningRules.darknessStart(20));
        assertEquals(2, AwakeningRules.darknessStart(3));
        // Too short to have a last third.
        assertEquals(2, AwakeningRules.darknessStart(2));
        assertEquals(0, AwakeningRules.darknessStart(0));
    }

    // ---- ripples ----

    @Test
    void aWalkingStepOnOrdinaryGroundIsOne() {
        assertEquals(1, AwakeningRules.rippleStrength(2, 1, 2), 1e-6);
        assertEquals(1.2, AwakeningRules.rippleStrength(2, 1.2, 2), 1e-6); // stone
        assertEquals(2.4, AwakeningRules.rippleStrength(4, 1.2, 2), 1e-6); // a sprint on stone
        assertEquals(0.1, AwakeningRules.rippleStrength(2, 0.1, 2), 1e-6); // wool
    }

    @Test
    void silentStepsMakeNoRing() {
        assertEquals(0, AwakeningRules.rippleStrength(0, 1, 2)); // sneaking
        assertEquals(0, AwakeningRules.rippleStrength(5, 0, 2)); // in the air
        assertEquals(0, AwakeningRules.rippleStrength(Double.NaN, 1, 2));
        assertTrue(AwakeningRules.rippleStrength(2, 0.04, 2) < AwakeningRules.MIN_RIPPLE_STRENGTH);
    }

    @Test
    void ripplesAreCapped() {
        assertEquals(AwakeningRules.MAX_RIPPLE_STRENGTH, AwakeningRules.rippleStrength(40, 1.2, 2));
    }

    @Test
    void withoutHeardStepsTheDefaultStepIsTheMeasure() {
        assertEquals(1, AwakeningRules.rippleStrength(AwakeningRules.DEFAULT_WALKING_STEP, 1, 0), 1e-6);
        assertEquals(2.5, AwakeningRules.rippleStrength(5, 1, -1), 1e-6);
    }

    // ---- root ----

    @Test
    void aRootedPlayerStraysSidewaysOrUpward() {
        Vec3 anchor = new Vec3(10.5, 64, 10.5);
        assertFalse(AwakeningRules.strayed(anchor, anchor, 0.3, true));
        assertFalse(AwakeningRules.strayed(anchor, anchor.add(0.2, 0.2, -0.2), 0.3, true));
        assertTrue(AwakeningRules.strayed(anchor, anchor.add(0.25, 0, 0.25), 0.3, true));
        assertTrue(AwakeningRules.strayed(anchor, anchor.add(0, 0.31, 0), 0.3, true));
        // Sinking and falling onto a floor are not straying.
        assertFalse(AwakeningRules.strayed(anchor, anchor.add(0, -3, 0), 0.3, true));
    }

    @Test
    void aRootedPlayerThatMayNotDropStraysDownwardToo() {
        Vec3 anchor = new Vec3(10.5, 64, 10.5);
        assertFalse(AwakeningRules.strayed(anchor, anchor.add(0, -0.29, 0), 0.3, false));
        assertTrue(AwakeningRules.strayed(anchor, anchor.add(0, -0.31, 0), 0.3, false));
        assertTrue(AwakeningRules.strayed(anchor, anchor.add(0, -3, 0), 0.3, false));
        // Sideways and upward as before.
        assertTrue(AwakeningRules.strayed(anchor, anchor.add(0.4, 0, 0), 0.3, false));
        assertTrue(AwakeningRules.strayed(anchor, anchor.add(0, 0.31, 0), 0.3, false));
        assertFalse(AwakeningRules.strayed(anchor, anchor.add(0.1, 0.1, 0.1), 0.3, false));
    }

    // ---- dropping ----

    /** A column of cells from {@code top} downward; below the last one it is open. */
    private static IntFunction<AwakeningRules.Cell> column(int top, AwakeningRules.Cell... cells) {
        return y -> top - y >= 0 && top - y < cells.length ? cells[top - y] : AwakeningRules.Cell.OPEN;
    }

    @Test
    void aPlayerMayDropOntoAFloor() {
        // Standing on it: the floor is right below the feet.
        assertTrue(AwakeningRules.mayDrop(column(64, OPEN, FLOOR), 64, -64));
        // In the feet block itself (a slab, a snow layer).
        assertTrue(AwakeningRules.mayDrop(column(64, FLOOR), 64, -64));
        // Far below, through plants and air.
        assertTrue(AwakeningRules.mayDrop(column(200, OPEN, OPEN, OPEN, FLOOR), 200, -64));
        assertTrue(AwakeningRules.mayDrop(y -> y == -64 ? FLOOR : OPEN, 300, -64));
    }

    @Test
    void notIntoAFluidOrFire() {
        // Gliding over the sea, a lava lake or fire.
        assertFalse(AwakeningRules.mayDrop(column(100, OPEN, OPEN, UNSAFE, FLOOR), 100, -64));
        // Already in the water: held there, not sinking.
        assertFalse(AwakeningRules.mayDrop(column(62, UNSAFE, UNSAFE, FLOOR), 62, -64));
        // A floor above the fluid is what counts.
        assertTrue(AwakeningRules.mayDrop(column(70, OPEN, FLOOR, UNSAFE), 70, -64));
    }

    @Test
    void notIntoTheVoid() {
        assertFalse(AwakeningRules.mayDrop(y -> OPEN, 80, 0));
        // The bottom of the level itself still counts.
        assertTrue(AwakeningRules.mayDrop(y -> y == 0 ? FLOOR : OPEN, 80, 0));
        assertFalse(AwakeningRules.mayDrop(y -> y == -1 ? FLOOR : OPEN, 80, 0));
        // Below the bottom already.
        assertFalse(AwakeningRules.mayDrop(y -> FLOOR, -70, -64));
    }

    // ---- darkness ----

    @Test
    void theDarknessIsRenewedBeforeItFades() {
        assertTrue(AwakeningRules.DARKNESS_RENEW_TICKS > AwakeningRules.DARKNESS_FADE_TICKS + 20);
        assertTrue(AwakeningRules.DARKNESS_TICKS > AwakeningRules.DARKNESS_RENEW_TICKS);
    }

    @Test
    void theDarknessGivenIsTakenAway() {
        long end = 1080;
        // Given at 1000 (80 ticks): 30 left at 1050.
        assertTrue(AwakeningRules.ownDarkness(30, end, 1050));
        assertTrue(AwakeningRules.ownDarkness(30 + AwakeningRules.DARKNESS_LEEWAY, end, 1050));
        // A shorter one lies within it.
        assertTrue(AwakeningRules.ownDarkness(5, end, 1050));
        assertTrue(AwakeningRules.ownDarkness(0, end, 1050));
    }

    @Test
    void aLongerDarknessIsSomebodyElses() {
        long end = 1080;
        // A warden's, given again meanwhile.
        assertFalse(AwakeningRules.ownDarkness(260, end, 1050));
        assertFalse(AwakeningRules.ownDarkness(31 + AwakeningRules.DARKNESS_LEEWAY, end, 1050));
        // An infinite one.
        assertFalse(AwakeningRules.ownDarkness(-1, end, 1050));
        // Long after the one given ended.
        assertFalse(AwakeningRules.ownDarkness(40, end, 2000));
    }

    // ---- end after the hollow ----

    @Test
    void anOutcomeEndsTheAwakeningAsItself() {
        assertEquals(Awakening.End.VICTORY, AwakeningRules.afterHollow(HollowOutcome.VICTORY, true));
        assertEquals(Awakening.End.EDGE_ESCAPED, AwakeningRules.afterHollow(HollowOutcome.EDGE_ESCAPE, true));
        assertEquals(Awakening.End.DEFEAT, AwakeningRules.afterHollow(HollowOutcome.DEFEAT, true));
        // Decided while the Awakening had not yet seen the move into the copy (the same tick).
        assertEquals(Awakening.End.DEFEAT, AwakeningRules.afterHollow(HollowOutcome.DEFEAT, false));
    }

    @Test
    void withoutAnOutcomeTheHollowIsOverOrTheSwallowingFailed() {
        assertEquals(Awakening.End.HOLLOW_OVER, AwakeningRules.afterHollow(null, true));
        assertEquals(Awakening.End.CANCELLED, AwakeningRules.afterHollow(null, false));
    }

    @Test
    void everyOutcomeHasItsOwnEnd() {
        Set<Awakening.End> ends = EnumSet.noneOf(Awakening.End.class);
        for (HollowOutcome outcome : HollowOutcome.values()) {
            ends.add(AwakeningRules.afterHollow(outcome, true));
        }
        assertEquals(HollowOutcome.values().length, ends.size());
        assertFalse(ends.contains(Awakening.End.HOLLOW_OVER));
        assertFalse(ends.contains(Awakening.End.CANCELLED));
    }

    // ---- victory and defeat ----

    @Test
    void theHillOfAVictoryIsHighestWhenTheVictorComesOut() {
        // Defaults: a fade of 20 ticks, a phase of 80 rising over its first 16: it starts 4 ticks after the victory.
        int delay = AwakeningRules.emergeDelay(20, 80);
        assertEquals(4, delay);
        assertEquals(20, delay + Math.round(AwakeningShape.EMERGE_RISE * 80));
        // A long fade: the hill waits for it.
        assertEquals(200 - 16, AwakeningRules.emergeDelay(200, 80));
        // A rise as long as the fade or longer: at once.
        assertEquals(0, AwakeningRules.emergeDelay(20, 100));
        assertEquals(0, AwakeningRules.emergeDelay(10, 600));
        assertEquals(0, AwakeningRules.emergeDelay(0, 1));
    }

    @Test
    void thePullTakesAllTheHealthAndAbsorption() {
        assertEquals(21, AwakeningRules.pullDamage(20, 0));
        assertEquals(37, AwakeningRules.pullDamage(20, 16));
        assertEquals(1.5f, AwakeningRules.pullDamage(0.5f, 0));
        // Never negative, always finite.
        assertEquals(1, AwakeningRules.pullDamage(-3, -1));
        assertTrue(Float.isFinite(AwakeningRules.pullDamage(1024, 2048)));
    }
}
