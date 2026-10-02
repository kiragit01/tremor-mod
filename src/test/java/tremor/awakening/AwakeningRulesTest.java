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

import java.util.List;
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

    // ---- target ----

    @Test
    void theLastHeardPlayerIsTakenEvenIfAnotherIsNearer() {
        List<AwakeningRules.Candidate> players = List.of(new AwakeningRules.Candidate(A, 5, true),
                new AwakeningRules.Candidate(B, 40, true));
        assertEquals(B, AwakeningRules.chooseTarget(B, players, 64));
    }

    @Test
    void anUnfitLastHeardPlayerFallsBackToTheNearest() {
        List<AwakeningRules.Candidate> players = List.of(new AwakeningRules.Candidate(A, 20, true),
                new AwakeningRules.Candidate(B, 3, false), new AwakeningRules.Candidate(C, 10, true));
        // B is in creative mode, dead or in the hollow.
        assertEquals(C, AwakeningRules.chooseTarget(B, players, 64));
        // Out of hearing distance.
        List<AwakeningRules.Candidate> far = List.of(new AwakeningRules.Candidate(A, 20, true),
                new AwakeningRules.Candidate(B, 70, true));
        assertEquals(A, AwakeningRules.chooseTarget(B, far, 64));
        // Not in the level at all.
        assertEquals(A, AwakeningRules.chooseTarget(new UUID(9, 9), far, 64));
    }

    @Test
    void withoutALastHeardPlayerTheNearestFitOneIsTaken() {
        List<AwakeningRules.Candidate> players = List.of(new AwakeningRules.Candidate(A, 12, true),
                new AwakeningRules.Candidate(B, 8, true), new AwakeningRules.Candidate(C, 2, false));
        assertEquals(B, AwakeningRules.chooseTarget(null, players, 64));
    }

    @Test
    void equallyNearPlayersGoInListOrder() {
        List<AwakeningRules.Candidate> players = List.of(new AwakeningRules.Candidate(A, 8, true),
                new AwakeningRules.Candidate(B, 8, true));
        assertEquals(A, AwakeningRules.chooseTarget(null, players, 64));
    }

    @Test
    void theHearingDistanceItselfStillCounts() {
        assertEquals(A, AwakeningRules.chooseTarget(null, List.of(new AwakeningRules.Candidate(A, 64, true)), 64));
        assertNull(AwakeningRules.chooseTarget(A, List.of(new AwakeningRules.Candidate(A, 64.01, true)), 64));
        assertNull(AwakeningRules.chooseTarget(A, List.of(new AwakeningRules.Candidate(A, Double.NaN, true)), 64));
    }

    @Test
    void nobodyFitMeansNoTarget() {
        assertNull(AwakeningRules.chooseTarget(null, List.of(), 64));
        assertNull(AwakeningRules.chooseTarget(A, List.of(new AwakeningRules.Candidate(A, 1, false),
                new AwakeningRules.Candidate(B, 100, true)), 64));
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
}
