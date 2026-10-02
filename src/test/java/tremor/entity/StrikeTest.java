package tremor.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tremor.core.behavior.ContactZone;
import tremor.core.math.Vec3;

class StrikeTest {
    private static final double EPS = 1e-9;

    @Test
    void onAFloorThePlayerIsShovedAwayFromTheBumpAndLifted() {
        Vec3 center = new Vec3(0.5, 63.5, 0.5);
        Vec3 push = ContactZone.push(center, Vec3.UNIT_Y, Vec3.UNIT_X, new Vec3(1.5, 64, 1.5));
        Vec3 v = Strike.velocity(push, Vec3.UNIT_Y, 0.9, 0.45, 0);
        assertVec(new Vec3(0.9 / Math.sqrt(2), 0.45, 0.9 / Math.sqrt(2)), v);
    }

    @Test
    void onAWallAPlayerAboveOrBelowIsShovedOffTheWall() {
        // Bump on a wall facing +x; the player stands right under it, against the wall.
        Vec3 normal = Vec3.UNIT_X;
        Vec3 center = new Vec3(0.5, 64.5, 0.5);
        Vec3 push = ContactZone.push(center, normal, Vec3.UNIT_Y, new Vec3(0.9, 63, 0.5));
        assertVec(new Vec3(0, -1, 0), push);
        assertVec(new Vec3(0.9, 0.45, 0), Strike.velocity(push, normal, 0.9, 0.45, 0));
    }

    @Test
    void theHorizontalSpeedIsAlwaysTheKnockback() {
        Vec3 normal = new Vec3(1, 1, 0).normalize();
        // A push perpendicular to that normal, still mostly horizontal.
        Vec3 push = new Vec3(-1, 1, 3).normalize();
        Vec3 v = Strike.velocity(push, normal, 0.6, 0.3, 0);
        assertEquals(0.6, Math.hypot(v.x(), v.z()), EPS);
        assertEquals(0.3, v.y(), EPS);
        assertEquals(-1 / Math.sqrt(10) * 0.6, v.x(), EPS);
    }

    @Test
    void knockbackResistanceScalesTheWholeThrow() {
        assertVec(new Vec3(0.54, 0.27, 0), Strike.velocity(Vec3.UNIT_X, Vec3.UNIT_Y, 0.9, 0.45, 0.4));
        assertEquals(0, Strike.velocity(Vec3.UNIT_X, Vec3.UNIT_Y, 0.9, 0.45, 1).lengthSquared());
        // Clamped to 0..1.
        assertEquals(0, Strike.velocity(Vec3.UNIT_X, Vec3.UNIT_Y, 0.9, 0.45, 1.5).lengthSquared());
        assertVec(new Vec3(0.9, 0.45, 0), Strike.velocity(Vec3.UNIT_X, Vec3.UNIT_Y, 0.9, 0.45, -0.5));
        assertVec(new Vec3(0.9, 0.45, 0), Strike.velocity(Vec3.UNIT_X, Vec3.UNIT_Y, 0.9, 0.45, Double.NaN));
    }

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), EPS, actual::toString);
        assertEquals(expected.y(), actual.y(), EPS, actual::toString);
        assertEquals(expected.z(), actual.z(), EPS, actual::toString);
    }
}
