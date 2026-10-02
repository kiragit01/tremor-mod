package tremor.hearing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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
