package tremor.core.math;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

class Vec3Test {
    private static final double EPS = 1e-12;

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x(), actual.x(), EPS, () -> "x of " + actual);
        assertEquals(expected.y(), actual.y(), EPS, () -> "y of " + actual);
        assertEquals(expected.z(), actual.z(), EPS, () -> "z of " + actual);
    }

    @Test
    void basicArithmetic() {
        Vec3 a = new Vec3(1, 2, 3);
        Vec3 b = new Vec3(-4, 5, 0.5);
        assertVec(new Vec3(-3, 7, 3.5), a.add(b));
        assertVec(new Vec3(2, 4, 6), a.add(1, 2, 3));
        assertVec(new Vec3(5, -3, 2.5), a.sub(b));
        assertVec(new Vec3(2, 4, 6), a.scale(2));
        assertVec(new Vec3(-1, -2, -3), a.negate());
        assertEquals(-4 + 10 + 1.5, a.dot(b), EPS);
        assertEquals(14, a.lengthSquared(), EPS);
        assertEquals(Math.sqrt(14), a.length(), EPS);
        assertEquals(25 + 9 + 6.25, a.distanceSquared(b), EPS);
        assertEquals(Math.sqrt(40.25), a.distance(b), EPS);
        assertVec(new Vec3(-1.5, 3.5, 1.75), a.lerp(b, 0.5));
        assertVec(Vec3.UNIT_Z, Vec3.UNIT_X.cross(Vec3.UNIT_Y));
        assertVec(new Vec3(3.5, 4.5, -5.5), Vec3.voxelCenter(3, 4, -6));
    }

    @Test
    void crossIsPerpendicularToBothOperands() {
        Vec3 a = new Vec3(1, 2, 3);
        Vec3 b = new Vec3(-4, 5, 0.5);
        Vec3 c = a.cross(b);
        assertEquals(0, c.dot(a), EPS);
        assertEquals(0, c.dot(b), EPS);
    }

    @Test
    void normalize() {
        assertVec(new Vec3(0.6, 0, 0.8), new Vec3(3, 0, 4).normalize());
        assertEquals(1, new Vec3(-7, 1e-3, 123).normalize().length(), EPS);
        assertSame(Vec3.ZERO, Vec3.ZERO.normalize());
        assertSame(Vec3.ZERO, new Vec3(1e-12, 0, -1e-12).normalize());
        assertTrue(new Vec3(1e-12, 0, 0).isNearZero());
        assertTrue(!new Vec3(1e-3, 0, 0).isNearZero());
    }

    @Test
    void projectOnPlane() {
        assertVec(new Vec3(1, 0, 3), new Vec3(1, 2, 3).projectOnPlane(Vec3.UNIT_Y));
        Vec3 n = new Vec3(1, 1, 0).normalize();
        Vec3 p = new Vec3(2, -1, 5).projectOnPlane(n);
        assertEquals(0, p.dot(n), EPS);
        assertVec(new Vec3(1.5, -1.5, 5), p);
        assertVec(Vec3.ZERO, new Vec3(0, 3, 0).projectOnPlane(Vec3.UNIT_Y));
    }

    @Test
    void anyPerpendicularOfAxes() {
        for (Vec3 v : new Vec3[]{Vec3.UNIT_X, Vec3.UNIT_Y, Vec3.UNIT_Z,
                Vec3.UNIT_X.negate(), Vec3.UNIT_Y.negate(), Vec3.UNIT_Z.negate(), new Vec3(0, 5, 0)}) {
            Vec3 p = v.anyPerpendicular();
            assertEquals(1, p.length(), EPS, () -> "length for " + v);
            assertEquals(0, p.dot(v), EPS, () -> "dot for " + v);
        }
    }

    @Test
    void anyPerpendicularOfArbitraryVectors() {
        Random random = new Random(42);
        for (int i = 0; i < 10_000; i++) {
            Vec3 v = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian())
                    .scale(Math.pow(10, random.nextInt(7) - 3));
            Vec3 p = v.anyPerpendicular();
            assertEquals(1, p.length(), 1e-9, () -> "length for " + v);
            assertEquals(0, p.dot(v.normalize()), 1e-9, () -> "dot for " + v);
            assertEquals(p, v.anyPerpendicular(), "deterministic");
        }
    }
}
