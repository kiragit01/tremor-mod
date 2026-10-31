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

    @Test
    void anAimedThrowLandsWhereItAims() {
        Vec3 from = new Vec3(0.3, 208, 0.3);
        for (Vec3 to : new Vec3[] {new Vec3(3.5, 201, 1.5), new Vec3(-2, 201, 4), new Vec3(1, 205, 0)}) {
            Vec3 v = Strike.aimed(from, to, 0.45, 2, 0);
            double x = from.x(), y = from.y(), z = from.z(), vx = v.x(), vy = v.y(), vz = v.z();
            for (int tick = 0; tick < 400 && !(y <= to.y() && vy < 0); tick++) {
                x += vx;
                y += vy;
                z += vz;
                vx *= Strike.AIR_DRAG;
                vz *= Strike.AIR_DRAG;
                vy = (vy - Strike.GRAVITY) * Strike.AIR_DRAG_Y;
            }
            assertEquals(to.x(), x, 1e-9, "landing x for " + to);
            assertEquals(to.z(), z, 1e-9, "landing z for " + to);
        }
        assertEquals(0, Strike.aimed(from, new Vec3(0.3, 200, 0.3), 0.45, 2, 0).x(), 0);
        Vec3 far = Strike.aimed(from, new Vec3(100, 200, 0.3), 0.45, 0.5, 0);
        assertEquals(0.5, Math.hypot(far.x(), far.z()), 1e-9, "capped");
        // Aimed just past the foot of the pillar it stands on, it still gets 1.6 blocks out before it is back down.
        Vec3 near = Strike.aimed(from, new Vec3(1.3, 201, 0.3), 0.45, 2, 1.6);
        assertEquals(1.6, near.x() * Strike.drift(Strike.flightTicks(208, 208, 0.45)), 1e-9);
    }

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), EPS, actual::toString);
        assertEquals(expected.y(), actual.y(), EPS, actual::toString);
        assertEquals(expected.z(), actual.z(), EPS, actual::toString);
    }
}
