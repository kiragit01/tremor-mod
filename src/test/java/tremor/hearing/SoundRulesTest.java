package tremor.hearing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tremor.core.hearing.Hearing;
import tremor.core.hearing.HearingParams;
import tremor.core.math.Vec3;
import tremor.core.testing.ArrayVoxelGrid;

class SoundRulesTest {
    @Test
    void slowItemsAreSilent() {
        assertEquals(0, SoundRules.itemLanding(3, 0));
        assertEquals(0, SoundRules.itemLanding(3, SoundRules.MIN_ITEM_SPEED));
        assertEquals(0, SoundRules.itemLanding(3, -0.5)); // moving up
        assertEquals(0, SoundRules.itemLanding(0, 1)); // disabled
    }

    @Test
    void itemLoudnessGrowsWithImpactSpeedUpToTheBonus() {
        double justAbove = SoundRules.itemLanding(3, SoundRules.MIN_ITEM_SPEED + 1e-6);
        assertEquals(3, justAbove, 1e-4);
        double half = SoundRules.itemLanding(3, SoundRules.MIN_ITEM_SPEED + SoundRules.ITEM_SPEED_RANGE / 2);
        assertEquals(3 + SoundRules.ITEM_SPEED_BONUS / 2, half, 1e-9);
        assertEquals(3 + SoundRules.ITEM_SPEED_BONUS, SoundRules.itemLanding(3, 5), 1e-9);
    }

    @Test
    void fallIsBasePlusHeight() {
        assertEquals(17.5, SoundRules.fall(10, 7.5), 1e-9);
        assertEquals(0, SoundRules.fall(0, 20));
    }

    @Test
    void stepsDownAreNotLandings() {
        assertFalse(SoundRules.isLanding(0.5)); // down a stair or a slab
        assertFalse(SoundRules.isLanding(0.25)); // a jump up onto a block
        assertFalse(SoundRules.isLanding(0.75)); // a jump up onto a slab
        assertFalse(SoundRules.isLanding(0.5f + 0.0625f)); // off a slab onto a carpet
        assertFalse(SoundRules.isLanding(0));
        assertFalse(SoundRules.isLanding(Double.NaN));
    }

    @Test
    void jumpsAndDropsOfABlockAreLandings() {
        assertTrue(SoundRules.isLanding(1.2522)); // a jump on level ground
        assertTrue(SoundRules.isLanding(1)); // off a full block
        assertTrue(SoundRules.isLanding(0.99999994f)); // the same, summed per tick in floats
        assertTrue(SoundRules.isLanding(2.5));
    }

    @Test
    void sneakingSilencesStepsAndLandings() {
        assertEquals(0, SoundRules.playerMovement(2, false, true, false, 4));
        assertEquals(0, SoundRules.playerMovement(5, true, true, false, 4));
        assertEquals(0, SoundRules.playerMovement(2, false, true, true, 4)); // shift wins over sprint
    }

    @Test
    void sprintingRaisesStepsButNotLandings() {
        assertEquals(2, SoundRules.playerMovement(2, false, false, false, 4));
        assertEquals(4, SoundRules.playerMovement(2, false, false, true, 4));
        assertEquals(5, SoundRules.playerMovement(5, true, false, true, 4));
        assertEquals(5, SoundRules.playerMovement(5, true, false, false, 4));
    }

    @Test
    void fallsHurtBeyondTheSafeDistanceUnlessCushioned() {
        assertFalse(SoundRules.isDamagingFall(3, 3, 1));
        assertTrue(SoundRules.isDamagingFall(3.1, 3, 1));
        assertFalse(SoundRules.isDamagingFall(6, 6, 1)); // a horse
        assertTrue(SoundRules.isDamagingFall(7, 6, 1));
        assertTrue(SoundRules.isDamagingFall(10, 3, 0.2)); // hay bale
        assertFalse(SoundRules.isDamagingFall(10, 3, 0)); // slime block
    }

    @Test
    void leavesUnderTheFeetRustleLouderThanStone() {
        // SPEC 7.2: wool 0.1, earth 1.0, stone 1.2; leaves rustle at the rustling factor (1.5 by default).
        assertEquals(1.5, SoundRules.footing(0.1, true, 1.5));
        assertEquals(1.2, SoundRules.footing(1.2, false, 1.5));
        assertEquals(0.1, SoundRules.footing(0.1, false, 1.5)); // wool, or leaves under a carpet
        assertTrue(SoundRules.footing(0.1, true, 1.5) > SoundRules.footing(1.2, false, 1.5));
        assertEquals(0, SoundRules.footing(0.1, true, 0)); // rustling switched off: silent leaves
    }

    @Test
    void aStepOnLeavesIsHeardBetterThanOnStoneButLeavesOnTheWayStillDamp() {
        // Stone below y = 0; a step 12 blocks from the entity, both in the stone.
        float stone = 1.2f, leaves = 0.1f;
        ArrayVoxelGrid ground = ArrayVoxelGrid.flatFloor(0);
        for (int x = ground.minX; x <= ground.maxX; x++) {
            for (int y = ground.minY; y <= ground.maxY; y++) {
                for (int z = ground.minZ; z <= ground.maxZ; z++) {
                    ground.setConductivity(x, y, z, ground.isSolid(x, y, z) ? stone : 0.05f);
                }
            }
        }
        HearingParams params = HearingParams.defaults();
        Vec3 source = Vec3.voxelCenter(0, -1, 0), listener = Vec3.voxelCenter(12, -1, 0);
        double onStone = Hearing.perceived(ground, source, listener, 2, SoundRules.footing(stone, false, 1.5), params);
        // The same step on a block of leaves set into the stone: the leaves it rustles in do not damp it.
        ground.setConductivity(0, -1, 0, leaves);
        Hearing.Foliage foliage = (x, y, z) -> x == 0 && y == -1 && z == 0;
        double onLeaves = Hearing.perceived(ground, source, listener, 2, SoundRules.footing(leaves, true, 1.5),
                foliage, params);
        assertTrue(onLeaves > 1.2 * onStone, onLeaves + " vs " + onStone); // about 1.5 / 1.2 times
        // A layer of leaves between them (their conductivity on the way is the insulating one) damps the step.
        ground.setConductivity(0, -1, 0, stone);
        for (int x = 4; x <= 8; x++) {
            ground.setConductivity(x, -1, 0, leaves);
        }
        double throughLeaves = Hearing.perceived(ground, source, listener, 2, SoundRules.footing(stone, false, 1.5),
                params);
        assertTrue(throughLeaves < onStone / 2, throughLeaves + " vs " + onStone);
    }

    @Test
    void idleEntityFollowsAnyHeardSound() {
        assertTrue(SoundRules.mayRetarget(false, 0, 10, 0.1, 5));
    }

    @Test
    void followedSoundIsReplacedAfterTheCooldownOrByAMuchLouderOne() {
        assertFalse(SoundRules.mayRetarget(true, 9, 10, 0.2, 0.2));
        assertTrue(SoundRules.mayRetarget(true, 10, 10, 0.05, 0.2));
        assertFalse(SoundRules.mayRetarget(true, 3, 10, 0.29, 0.2));
        assertTrue(SoundRules.mayRetarget(true, 3, 10, 0.31, 0.2));
    }
}
