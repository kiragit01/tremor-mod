package tremor.core.shape;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import tremor.core.math.Vec3;
import tremor.core.surface.SurfaceNormals;
import tremor.core.testing.ArrayVoxelGrid;

/**
 * A leap whose end has no normal (the review of 1.4.0: the middle of a floor one block thick, open over and under
 * alike; a hill drawn along none threw in the render thread of every client near).
 */
class LeapFacingTest {
    @Test
    void theMiddleOfAFloorOneBlockThickHasNoNormal() {
        ArrayVoxelGrid sheet = new ArrayVoxelGrid(-12, -12, -12, 12, 12, 12, false).fill(-9, 0, -9, 9, 0, 9, true);
        assertEquals(Vec3.ZERO, SurfaceNormals.smoothNormal(sheet, 0, 0, 0), "so a leap to it is told none");
    }

    @Test
    void anEndWithoutANormalFacesTheOtherEnd() {
        Vec3 from = new Vec3(0.5, 1, 0.5), to = new Vec3(0.5, 4, 0.5);
        assertEquals(Vec3.UNIT_Y, BumpFrame.normalOr(Vec3.ZERO, to.sub(from)));
        assertEquals(new Vec3(0, -1, 0), BumpFrame.normalOr(Vec3.ZERO, from.sub(to)));
        assertEquals(Vec3.UNIT_X, BumpFrame.normalOr(Vec3.UNIT_X, to.sub(from)), "one told is kept");
        assertEquals(Vec3.UNIT_Y, BumpFrame.normalOr(Vec3.ZERO, Vec3.ZERO), "no leap at all: up");
        assertDoesNotThrow(() -> new BumpFrame(to, BumpFrame.normalOr(Vec3.ZERO, from.sub(to)),
                from.sub(to)), "the hill at the far end can be drawn");
    }
}
