package tremor.core.deform;

import org.junit.jupiter.api.Test;
import tremor.core.math.Vec3;
import tremor.core.shape.BumpFrame;
import tremor.core.shape.BumpParams;
import tremor.core.testing.ArrayVoxelGrid;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinSwellTest {
    private static final BumpParams PARAMS = BumpParams.defaults();

    /** A floor at y = -4 and a ridge one block wide along x on it, its top at y = 0. */
    private static ArrayVoxelGrid ridge(int width) {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(-4);
        g.fill(-15, -3, 0, 15, 0, width - 1, true);
        return g;
    }

    private static List<SurfacePoint> collect(ArrayVoxelGrid g, BumpFrame frame) {
        return SurfaceCollector.collect(g, frame, SurfaceCollector.Options.forParams(PARAMS));
    }

    @Test
    void thinIsLessThanThreeAcrossXOrZ() {
        ArrayVoxelGrid g = ridge(1);
        assertTrue(ThinSwell.thin(g, 0, 0, 0));
        assertTrue(ThinSwell.thin(g, 0, -2, 0));
        assertFalse(ThinSwell.thin(g, 0, -4, 0), "the floor under it");
        assertFalse(ThinSwell.thin(g, 0, 1, 0), "open");
        assertTrue(ThinSwell.thin(ridge(2), 0, 0, 1));
        assertFalse(ThinSwell.thin(ridge(3), 0, 0, 1));
        assertFalse(ThinSwell.thin(ArrayVoxelGrid.flatFloor(0), 0, 0, 0));
        ArrayVoxelGrid slab = new ArrayVoxelGrid(-10, -10, -10, 10, 10, 10, false).fill(-8, 0, -8, 8, 0, 8, true);
        assertTrue(ThinSwell.thin(slab, 0, 0, 0), "a slab one block thick");
        assertFalse(ThinSwell.thin(slab.fill(-8, -1, -8, 8, -1, 8, true), 0, 0, 0), "two blocks thick");
    }

    @Test
    void aSlabOneBlockThickSwellsOverAndUnder() {
        ArrayVoxelGrid g = new ArrayVoxelGrid(-12, -12, -12, 12, 12, 12, false).fill(-9, 0, -9, 9, 0, 9, true);
        BumpFrame frame = BumpFrame.of(new Vec3(0.5, 1.0, 0.5), Vec3.UNIT_Y, Vec3.UNIT_X);
        ThinSwell.Result result = ThinSwell.apply(g, frame, PARAMS, PARAMS.influenceRadius(), collect(g, frame));
        assertTrue(contains(result.fills(), 0, 1, 0), "over it");
        assertTrue(contains(result.fills(), 0, -1, 0), "under it");
    }

    @Test
    void flatGroundIsLeftAsItIs() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        BumpFrame frame = BumpFrame.of(new Vec3(0.5, 1.0, 0.5), Vec3.UNIT_Y, Vec3.UNIT_X);
        List<SurfacePoint> points = collect(g, frame);
        ThinSwell.Result result = ThinSwell.apply(g, frame, PARAMS, PARAMS.influenceRadius(), points);
        assertEquals(points, result.points());
        assertTrue(result.fills().isEmpty());
    }

    @Test
    void aRidgeOneBlockWideSwellsAroundTheEntityOnIt() {
        ArrayVoxelGrid g = ridge(1);
        BumpFrame frame = BumpFrame.of(new Vec3(0.5, 1.0, 0.5), Vec3.UNIT_Y, Vec3.UNIT_X);
        List<SurfacePoint> points = collect(g, frame);
        assertFalse(points.stream().anyMatch(p -> p.z() == 0 && p.y() < 0),
                "the voxels under its top are not pushed up through it");
        ThinSwell.Result result = ThinSwell.apply(g, frame, PARAMS, PARAMS.influenceRadius(), points);
        assertFalse(result.points().stream().anyMatch(p -> p.z() == 0), "the ridge itself is not pushed out");
        assertTrue(contains(result.fills(), 0, 1, 0), "over its top");
        assertTrue(contains(result.fills(), 0, 0, 1) && contains(result.fills(), 0, 0, -1), "on both sides");
        for (ThinSwell.Fill f : result.fills()) {
            assertTrue(g.isOpen(f.x(), f.y(), f.z()));
            assertTrue(g.isSolid(f.sourceX(), f.sourceY(), f.sourceZ()) && f.sourceZ() == 0, "a voxel of the ridge");
        }
        ThinSwell.Fill above = find(result.fills(), 0, 1, 0);
        assertEquals(0, above.sourceY());
        assertEquals(1.0, above.distance(), 1e-9);
        assertEquals(-1, above.lookY(), "something swells over it: it looks like the block under the top");
        ThinSwell.Fill side = find(result.fills(), 0, 0, 1);
        assertEquals(0, side.sourceY());
        double full = ThinSwell.coverage(PARAMS.amplitude(), ThinSwell.falloff(frame, PARAMS.sigmaSide(), 0.5, 0.5,
                0.5), above.distance());
        assertEquals(1.0, full, 1e-9, "the cell over the entity is filled at once");
        assertTrue(result.fills().stream().anyMatch(f -> f.lookY() == f.sourceY()), "the top of the ball is the top's");
    }

    @Test
    void nearAThinStructureTheGroundRisesButNotItsSides() {
        // A pillar 2x2, 4 high, on a floor, the entity on the floor beside it.
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        g.fill(2, 1, 0, 3, 4, 1, true);
        BumpFrame frame = BumpFrame.of(new Vec3(0.5, 1.0, 0.5), Vec3.UNIT_Y, Vec3.UNIT_X);
        ThinSwell.Result result = ThinSwell.apply(g, frame, PARAMS, PARAMS.influenceRadius(), collect(g, frame));
        assertTrue(result.fills().isEmpty());
        assertTrue(result.points().stream().anyMatch(p -> p.y() == 0), "the floor");
        assertFalse(result.points().stream().anyMatch(p -> p.y() > 0), "nothing of the pillar's sides");
    }

    @Test
    void theSwellingBreathesOutOfStep() {
        double a = ThinSwell.breath(0.7, 0, 0, 0), b = ThinSwell.breath(0.7, 9, 0, 0);
        assertTrue(Math.abs(a - 1) <= ThinSwell.BREATH + 1e-9 && Math.abs(b - 1) <= ThinSwell.BREATH + 1e-9);
        assertTrue(Math.abs(a - b) > 1e-3, "two parts far apart breathe out of step");
        assertEquals(ThinSwell.breath(0.7, 0, 0, 0), ThinSwell.breath(0.7 + ThinSwell.BREATH_PERIOD, 0, 0, 0), 1e-9);
    }

    @Test
    void coverageGrowsWithTheSwelling() {
        assertEquals(0, ThinSwell.coverage(0, 1, 1));
        assertEquals(0, ThinSwell.coverage(-1, 1, 1));
        assertEquals(1, ThinSwell.coverage(2 / ThinSwell.SWELL, 1, 1));
        assertEquals(0.5, ThinSwell.coverage(1.5 / ThinSwell.SWELL, 1, 2), 1e-9);
        assertEquals(0, ThinSwell.coverage(10, 0, 1));
    }

    private static boolean contains(List<ThinSwell.Fill> fills, int x, int y, int z) {
        return fills.stream().anyMatch(f -> f.x() == x && f.y() == y && f.z() == z);
    }

    private static ThinSwell.Fill find(List<ThinSwell.Fill> fills, int x, int y, int z) {
        return fills.stream().filter(f -> f.x() == x && f.y() == y && f.z() == z).findFirst().orElseThrow();
    }
}
