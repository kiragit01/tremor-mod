package tremor.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tremor.core.math.VoxelPos;
import tremor.hearing.SoundRules;

class SoundPursuitTest {
    private static final int COOLDOWN = 10;

    @Test
    void withoutATargetAnySoundIsTakenUp() {
        SoundPursuit pursuit = new SoundPursuit();
        assertTrue(pursuit.mayRetarget(false, 0, COOLDOWN, 0.1));
        pursuit.retargeted(0, 0.5);
        // Not following anything (arrived, stopped): no cooldown.
        assertTrue(pursuit.mayRetarget(false, 1, COOLDOWN, 0.1));
        // Following: the cooldown, or a louder sound.
        assertFalse(pursuit.mayRetarget(true, 5, COOLDOWN, 0.5));
        assertTrue(pursuit.mayRetarget(true, 5, COOLDOWN, 0.5 * SoundRules.LOUDER));
        assertTrue(pursuit.mayRetarget(true, COOLDOWN, COOLDOWN, 0.1));
    }

    @Test
    void theCooldownRunsOnFromAGiveUp() {
        SoundPursuit pursuit = new SoundPursuit();
        pursuit.retargeted(100, 0.4);
        pursuit.gaveUp(130);
        // No target any more, but the next step right after the give-up is not taken up...
        assertFalse(pursuit.mayRetarget(false, 131, COOLDOWN, 0.4));
        assertFalse(pursuit.mayRetarget(false, 130 + COOLDOWN - 1, COOLDOWN, 0.4));
        // ...unless it is much louder, or the cooldown has run out since the give-up.
        assertTrue(pursuit.mayRetarget(false, 131, COOLDOWN, 0.4 * SoundRules.LOUDER));
        assertTrue(pursuit.mayRetarget(false, 130 + COOLDOWN, COOLDOWN, 0.1));
        // A new target ends the give-up: once that arrives, sounds are taken up at once again.
        pursuit.targetSet();
        assertTrue(pursuit.mayRetarget(false, 131, COOLDOWN, 0.1));
    }

    @Test
    void aFailedSearchKeepsNearbyGoalsFromTheSameStartBack() {
        long start = VoxelPos.pack(10, 64, 10);
        long goal = VoxelPos.pack(30, 64, 10);
        SoundPursuit pursuit = new SoundPursuit();
        assertFalse(pursuit.knownUnreachable(start, goal, 0));
        pursuit.searchFailed(start, goal, 1000);

        assertTrue(pursuit.knownUnreachable(start, goal, 1000));
        assertTrue(pursuit.knownUnreachable(start, goal, 1000 + SoundPursuit.UNREACHABLE_TICKS - 1));
        // The player walked a few blocks on: still the same unreachable place.
        assertTrue(pursuit.knownUnreachable(start, VoxelPos.pack(33, 64, 10), 1010));
        assertTrue(pursuit.knownUnreachable(start, VoxelPos.pack(28, 66, 11), 1010));
        assertTrue(pursuit.knownUnreachable(start, VoxelPos.offset(goal, 0, 0, SoundPursuit.UNREACHABLE_RADIUS), 1010));
        // Further away, from somewhere else, or after a while: search again.
        assertFalse(pursuit.knownUnreachable(start, VoxelPos.offset(goal, SoundPursuit.UNREACHABLE_RADIUS, 1, 0),
                1010));
        assertFalse(pursuit.knownUnreachable(VoxelPos.pack(11, 64, 10), goal, 1010));
        assertFalse(pursuit.knownUnreachable(start, goal, 1000 + SoundPursuit.UNREACHABLE_TICKS));
        assertFalse(pursuit.knownUnreachable(start, goal, 999)); // time went back (another save)
    }

    @Test
    void terrainChangesAndResetsForgetAFailure() {
        long start = VoxelPos.pack(0, 0, 0), goal = VoxelPos.pack(12, 0, 0);
        SoundPursuit pursuit = new SoundPursuit();
        pursuit.searchFailed(start, goal, 50);
        pursuit.forgetFailure();
        assertFalse(pursuit.knownUnreachable(start, goal, 51));

        pursuit.searchFailed(start, goal, 50);
        pursuit.retargeted(50, 1);
        pursuit.gaveUp(50);
        pursuit.reset();
        assertFalse(pursuit.knownUnreachable(start, goal, 51));
        assertTrue(pursuit.mayRetarget(false, 51, COOLDOWN, 0.1));
    }
}
