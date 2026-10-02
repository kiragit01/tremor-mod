package tremor.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import tremor.core.math.Vec3;
import tremor.core.shape.BumpFrame;
import tremor.core.shape.BumpParams;
import tremor.core.shape.BumpShape;
import tremor.core.shape.Ripple;
import tremor.core.shape.RippleParams;

class HeightFieldTest {
    private static final BumpParams BUMP = BumpParams.defaults();
    private static final RippleParams RIPPLE = RippleParams.defaults();
    private static final BumpFrame FLOOR = BumpFrame.of(new Vec3(10.25, 64, -7.5), Vec3.UNIT_Y, Vec3.UNIT_X);

    @Test
    void withoutRippleItIsExactlyTheBump() {
        Random random = new Random(31);
        HeightField plain = new HeightField(BUMP, FLOOR, 3.7, false, null, 0.4);
        HeightField jittered = new HeightField(BUMP, FLOOR, 3.7, true, null, 0.4);
        for (int i = 0; i < 2000; i++) {
            double x = 10.25 + random.nextGaussian() * 5, y = 64 + random.nextGaussian(), z = -7.5
                    + random.nextGaussian() * 5;
            assertEquals(BumpShape.height(BUMP, FLOOR, x, y, z), plain.at(x, y, z));
            assertEquals(BumpShape.height(BUMP, FLOOR, x, y, z, 3.7), jittered.at(x, y, z));
        }
    }

    @Test
    void rippleRingsOnAFloorDependOnlyOnTheHorizontalDistance() {
        double age = 0.8;
        HeightField field = new HeightField(BUMP, FLOOR, 0, false, RIPPLE, age);
        Random random = new Random(32);
        boolean raised = false;
        for (int i = 0; i < 2000; i++) {
            double r = random.nextDouble() * 8, angle = random.nextDouble() * 2 * Math.PI;
            double x = 10.25 + r * Math.cos(angle), z = -7.5 + r * Math.sin(angle);
            double y = 64 + (random.nextDouble() * 2 - 1) * 2.5; // anywhere in the collector's normal band
            double ripple = field.at(x, y, z) - BumpShape.height(BUMP, FLOOR, x, y, z);
            assertEquals(Ripple.height(RIPPLE, r, age), ripple, 1e-9, "at r=" + r + " y=" + y);
            raised |= ripple > BumpShape.RENDER_THRESHOLD;
        }
        assertTrue(raised, "the ripple should rise above the threshold somewhere at this age");
    }

    @Test
    void distanceIsMeasuredInTheTangentPlaneOfTheNormal() {
        BumpFrame wall = BumpFrame.of(new Vec3(0, 70, 0), Vec3.UNIT_X, Vec3.UNIT_Y);
        // Along the normal does not count; within the wall plane (y, z) it does.
        assertEquals(0, HeightField.tangentDistance(wall, 5, 70, 0), 1e-12);
        assertEquals(5, HeightField.tangentDistance(wall, -2, 73, 4), 1e-12);
        BumpFrame slope = BumpFrame.of(Vec3.ZERO, new Vec3(1, 1, 0), Vec3.UNIT_Z);
        Vec3 n = slope.normal(), side = slope.side();
        Vec3 p = n.scale(1.7).add(side.scale(3)).add(slope.forward().scale(-4));
        assertEquals(5, HeightField.tangentDistance(slope, p.x(), p.y(), p.z()), 1e-12);
    }
}
