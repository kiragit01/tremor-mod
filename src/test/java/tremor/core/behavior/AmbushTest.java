package tremor.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tremor.core.math.Vec3;

class AmbushTest {
    private static final Vec3 BUMP = new Vec3(0, 64, 0);

    @Test
    void onlyAlertOrHuntingOnAFloor() {
        assertTrue(Ambush.mayLurk(Stage.ALERT, Vec3.UNIT_Y));
        assertTrue(Ambush.mayLurk(Stage.HUNTING, new Vec3(0.3, 0.9, 0).normalize()));
        assertFalse(Ambush.mayLurk(Stage.DORMANT, Vec3.UNIT_Y));
        assertFalse(Ambush.mayLurk(Stage.AWAKENING, Vec3.UNIT_Y), "at the top it seeks instead");
        assertFalse(Ambush.mayLurk(Stage.HUNTING, Vec3.UNIT_X), "a wall");
        assertFalse(Ambush.mayLurk(Stage.HUNTING, new Vec3(0, -1, 0)), "a ceiling");
    }

    @Test
    void aPlayerComesNearerOrWalksClose() {
        assertTrue(Ambush.coming(BUMP, new Vec3(15, 64, 0), new Vec3(14, 64, 0), 20), "nearer by a block");
        assertFalse(Ambush.coming(BUMP, new Vec3(15, 64, 0), new Vec3(15.2, 64, 0.5), 20), "passing by, far");
        assertTrue(Ambush.coming(BUMP, new Vec3(8, 64, 0), new Vec3(8, 64, 1), 20), "walking within half");
        assertFalse(Ambush.coming(BUMP, new Vec3(8, 64, 0), new Vec3(8, 64, 0.1), 20), "standing");
        assertFalse(Ambush.coming(BUMP, new Vec3(26, 64, 0), new Vec3(21, 64, 0), 20), "beyond the sensing");
    }

    @Test
    void theViewIsACone() {
        Vec3 eye = new Vec3(10, 65.6, 0);
        assertTrue(Ambush.inView(eye, new Vec3(-1, 0, 0), BUMP), "looking at it");
        assertTrue(Ambush.inView(eye, new Vec3(-1, 0, 1).normalize(), BUMP), "45 degrees off");
        assertFalse(Ambush.inView(eye, new Vec3(0, 0, 1), BUMP), "looking aside");
        assertFalse(Ambush.inView(eye, new Vec3(1, 0, 0), BUMP), "its back to it");
    }

    @Test
    void steppedInWithinTheRadiusOnTheSameFloor() {
        assertTrue(Ambush.steppedIn(BUMP, new Vec3(2, 64.5, 0), 2.5));
        assertFalse(Ambush.steppedIn(BUMP, new Vec3(3, 64.5, 0), 2.5), "beside it");
        assertFalse(Ambush.steppedIn(BUMP, new Vec3(1, 68, 0), 2.5), "above it, on a ledge");
    }

    @Test
    void itFadesInAndOutOverOneAndAHalfSeconds() {
        assertEquals(0.5, Ambush.fade(0, true, 0.75), 1e-9);
        assertEquals(1, Ambush.fade(0.9, true, 0.5), 1e-9);
        assertEquals(0, Ambush.fade(0.2, false, 0.5), 1e-9);
    }

    @Test
    void itBreathesOnlyLyingInWait() {
        assertEquals(1, Ambush.breath(0.8, 0), 1e-9);
        assertEquals(1 + Ambush.BREATH, Ambush.breath(Ambush.BREATH_PERIOD / 4, 1), 1e-9);
        assertEquals(1 - Ambush.BREATH / 2, Ambush.breath(Ambush.BREATH_PERIOD * 3 / 4, 0.5), 1e-9);
    }
}
