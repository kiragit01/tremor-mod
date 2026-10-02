package tremor.core.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;
import tremor.core.math.Vec3;

class BumpFrameTest {
    private static final double EPS = 1e-12;

    private static void assertOrthonormal(BumpFrame frame) {
        assertEquals(1, frame.normal().length(), EPS, () -> "|n| of " + frame);
        assertEquals(1, frame.forward().length(), EPS, () -> "|f| of " + frame);
        assertEquals(0, frame.normal().dot(frame.forward()), EPS, () -> "n·f of " + frame);
        Vec3 side = frame.side();
        assertEquals(1, side.length(), EPS);
        assertEquals(0, side.dot(frame.normal()), EPS);
        assertEquals(0, side.dot(frame.forward()), EPS);
    }

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(0, expected.distance(actual), EPS, () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void normalizesAndProjects() {
        Vec3 c = new Vec3(1, 2, 3);
        BumpFrame frame = new BumpFrame(c, new Vec3(0, 5, 0), new Vec3(3, 7, 4));
        assertVec(c, frame.center());
        assertVec(Vec3.UNIT_Y, frame.normal());
        assertVec(new Vec3(0.6, 0, 0.8), frame.forward());
        assertOrthonormal(frame);
    }

    @Test
    void orthonormalForRandomInputs() {
        Random random = new Random(3);
        for (int i = 0; i < 10_000; i++) {
            Vec3 n = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian());
            Vec3 v = new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian())
                    .scale(Math.pow(10, random.nextInt(9) - 4));
            BumpFrame frame = BumpFrame.of(Vec3.ZERO, n, v);
            assertOrthonormal(frame);
            assertTrue(frame.forward().dot(v) >= 0, "forward keeps the direction of the tangential velocity");
        }
    }

    @Test
    void zeroVelocityFallsBackToPerpendicular() {
        BumpFrame frame = BumpFrame.of(Vec3.ZERO, Vec3.UNIT_Y, Vec3.ZERO);
        assertOrthonormal(frame);
        assertVec(Vec3.UNIT_Y.anyPerpendicular(), frame.forward());
    }

    @Test
    void velocityParallelToNormalFallsBackToPerpendicular() {
        Vec3 n = new Vec3(1, 2, -2).normalize();
        for (Vec3 v : new Vec3[]{n, n.scale(-3), n.scale(1e6), n.scale(1e-6)}) {
            BumpFrame frame = BumpFrame.of(Vec3.ZERO, n, v);
            assertOrthonormal(frame);
            assertVec(n.anyPerpendicular(), frame.forward());
        }
        BumpFrame wall = BumpFrame.of(Vec3.ZERO, Vec3.UNIT_X.negate(), new Vec3(5, 0, 0));
        assertOrthonormal(wall);
        assertVec(Vec3.UNIT_X.negate().anyPerpendicular(), wall.forward());
    }

    @Test
    void zeroNormalIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> BumpFrame.of(Vec3.ZERO, Vec3.ZERO, Vec3.UNIT_X));
        assertThrows(IllegalArgumentException.class, () -> new BumpFrame(Vec3.ZERO, new Vec3(1e-12, 0, 0), Vec3.UNIT_X));
    }

    @Test
    void storedFrameIsStable() {
        BumpFrame frame = BumpFrame.of(new Vec3(4, 5, 6), new Vec3(0, -2, 0), new Vec3(1, 1, 1));
        BumpFrame again = new BumpFrame(frame.center(), frame.normal(), frame.forward());
        assertVec(frame.normal(), again.normal());
        assertVec(frame.forward(), again.forward());
    }
}
