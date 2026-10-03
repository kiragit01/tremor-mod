package tremor.client.hollow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SprintToggleTest {
    @Test
    void aToggleOnWhenTheLockTakesItIsOnAgainWhenItLetsGo() {
        SprintToggle toggle = new SprintToggle();
        toggle.held(true, false);
        for (int i = 0; i < 100; i++) {
            toggle.held(false, false);
        }
        assertTrue(toggle.letGo(true, false));
        // Only at the first tick out: the toggle is the player's again from then on.
        assertFalse(toggle.letGo(true, false));
    }

    @Test
    void aToggleOffStaysOff() {
        SprintToggle toggle = new SprintToggle();
        for (int i = 0; i < 10; i++) {
            toggle.held(false, false);
        }
        assertFalse(toggle.letGo(true, false));
        // Never held: nothing to give back.
        assertFalse(new SprintToggle().letGo(true, false));
    }

    @Test
    void everyPressInTheHollowTurnsItOver() {
        SprintToggle toggle = new SprintToggle();
        toggle.held(false, false);
        toggle.held(true, false);
        toggle.held(false, false);
        assertTrue(toggle.letGo(true, false), "pressed on once in the hollow");

        toggle.held(true, false);
        toggle.held(true, false);
        assertFalse(toggle.letGo(true, false), "on as taken, pressed off in the hollow");

        toggle.held(true, false);
        toggle.held(true, false);
        toggle.held(true, false);
        assertTrue(toggle.letGo(true, false), "on as taken, off, on again");
    }

    @Test
    void aDeathThereTurnsItOff() {
        SprintToggle toggle = new SprintToggle();
        toggle.held(true, false);
        toggle.held(false, true);
        assertFalse(toggle.letGo(true, false));
    }

    @Test
    void notGivenBackOutOfToggleModeNorWhenOnAlready() {
        SprintToggle toggle = new SprintToggle();
        toggle.held(true, false);
        assertFalse(toggle.letGo(false, false), "the sprint key is held, not toggled, now");

        toggle.held(true, false);
        assertFalse(toggle.letGo(true, true), "on already");
    }

    @Test
    void takenAgainItStartsOver() {
        SprintToggle toggle = new SprintToggle();
        toggle.held(true, false);
        assertTrue(toggle.letGo(true, false));
        // Into creative and back, or into the hollow again: only what the toggle is as the lock takes it counts.
        toggle.held(false, false);
        assertFalse(toggle.letGo(true, false));
        toggle.held(true, false);
        assertTrue(toggle.letGo(true, false));
    }
}
