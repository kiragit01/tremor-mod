package tremor.core.deform;

import org.junit.jupiter.api.Test;
import tremor.core.math.Vec3;
import tremor.core.shape.BumpFrame;
import tremor.core.shape.BumpParams;
import tremor.core.shape.BumpShape;
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
    void aSlabOneBlockThickRisesAsGround() {
        // A bridge is drawn as plain ground (the user: the swelling round a lumpy bridge jerked from shape to shape).
        ArrayVoxelGrid g = new ArrayVoxelGrid(-12, -12, -12, 12, 12, 12, false).fill(-9, 0, -9, 9, 0, 9, true);
        BumpFrame frame = BumpFrame.of(new Vec3(0.5, 1.0, 0.5), Vec3.UNIT_Y, Vec3.UNIT_X);
        ThinSwell.Result result = ThinSwell.apply(g, frame, PARAMS, PARAMS.influenceRadius(), collect(g, frame));
        assertTrue(result.fills().isEmpty(), "no swelling round it");
        assertTrue(result.points().stream().anyMatch(p -> p.x() == 0 && p.y() == 0 && p.z() == 0
                && p.normal().equals(Vec3.UNIT_Y)), "its top rises straight up");
        assertTrue(result.points().stream().noneMatch(p -> p.y() != 0), "nothing under it or beside it moves");
    }

    @Test
    void onABridgeTheBumpIsDrawnUpright() {
        ArrayVoxelGrid slab = new ArrayVoxelGrid(-12, -12, -12, 12, 12, 12, false).fill(-9, 0, -9, 9, 0, 9, true);
        Vec3 aside = new Vec3(0.7, 0.7, 0).normalize();
        Vec3 n = ThinSwell.drawnNormal(slab, new Vec3(0.5, 1.0, 0.5), aside);
        assertEquals(0, n.x(), 1e-9);
        assertEquals(1, n.y(), 1e-9);
        Vec3 under = ThinSwell.drawnNormal(slab, new Vec3(0.5, 0.0, 0.5), new Vec3(0.3, -0.9, 0).normalize());
        assertEquals(-1, under.y(), 1e-9, "under it: straight down");
        ArrayVoxelGrid ground = ArrayVoxelGrid.flatFloor(0);
        assertEquals(aside, ThinSwell.drawnNormal(ground, new Vec3(0.5, 1.0, 0.5), aside), "thick ground: as it is");
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
        double full = ThinSwell.coverage(ThinSwell.SWELL * PARAMS.amplitude()
                * ThinSwell.falloff(frame, PARAMS.sigmaSide(), 0.5, 0.5, 0.5), above.distance());
        assertEquals(1.0, full, 1e-9, "the cell over the entity is filled at once");
        assertTrue(result.fills().stream().anyMatch(f -> f.lookY() == f.sourceY()), "the top of the ball is the top's");
    }

    /**
     * The user: on a thick floor that is one block thick in places (a hollow right under some of its blocks) the hill
     * had holes over those blocks, now and then, as the tremor crawled over them. While its middle was over one, the
     * two were taken for a thin structure to swell: they stopped rising, and the swelling that should stand in for
     * them was held back by the floor of the hollow, which could rise through the cells.
     */
    @Test
    void aHollowUnderAWideFloorLeavesNoHoleInTheHill() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        g.fill(0, -1, 0, 1, -1, 0, false); // a pocket one block high right under two blocks of the floor
        assertTrue(ThinSwell.thin(g, 0, 0, 0) && ThinSwell.thin(g, 1, 0, 0), "one block thick there");
        for (double x : new double[]{-2.5, 0.5, 1.5, 3.5}) { // the entity before, over each of the two, and past them
            BumpFrame frame = BumpFrame.of(new Vec3(x, 1.0, 0.5), Vec3.UNIT_Y, Vec3.UNIT_X);
            ThinSwell.Result result = ThinSwell.apply(g, frame, PARAMS, PARAMS.influenceRadius(), collect(g, frame));
            assertTrue(result.fills().isEmpty(), "no swelling with the entity at x " + x);
            for (int vx = 0; vx <= 1; vx++) {
                int at = vx;
                assertTrue(result.points().stream().anyMatch(p -> p.x() == at && p.y() == 0 && p.z() == 0
                        && p.normal().y() > 0.9), "the block at x " + at + " rises with the entity at x " + x);
            }
        }
    }

    /**
     * A floor ending in a lip one block thick over a drop: the lip faces the drop, not up, and stood still while the
     * floor behind it rose, a notch in the hill at its very edge.
     */
    @Test
    void aLipOverADropRisesWithTheFloorBehindIt() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        g.fill(4, -16, -20, 20, 16, 20, false); // the drop beyond x = 3
        g.fill(2, -16, -20, 3, -1, 20, false);  // the floor's last two blocks hang over it, one block thick
        BumpFrame frame = BumpFrame.of(new Vec3(0.5, 1.0, 0.5), Vec3.UNIT_Y, Vec3.UNIT_X);
        ThinSwell.Result result = ThinSwell.apply(g, frame, PARAMS, PARAMS.influenceRadius(), collect(g, frame));
        for (int x = 2; x <= 3; x++) {
            int at = x;
            assertTrue(result.points().stream().anyMatch(p -> p.x() == at && p.y() == 0 && p.z() == 0
                    && p.normal().equals(Vec3.UNIT_Y)), "the lip at x " + at + " rises straight up");
        }
        assertTrue(result.points().stream().noneMatch(p -> p.x() >= 2 && !p.normal().equals(Vec3.UNIT_Y)),
                "and goes no other way");
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
        for (SurfacePoint p : result.points()) {
            if (p.y() > 0) {
                // Its top, four blocks up, is within the band, but the hill has faded out there (BumpShape.BAND).
                assertEquals(4, p.y(), "nothing of the pillar's sides");
                assertTrue(BumpShape.height(PARAMS, frame, p.x() + 0.5, p.y() + 0.5, p.z() + 0.5) < BumpShape.RENDER_THRESHOLD);
            }
        }
    }

    @Test
    void sortedAsTheScanFindsThemThePointsComeOutAsFromApply() {
        // A big bump's collection spread over frames: each step of the scan, what it found is sorted at once.
        ArrayVoxelGrid pillar = ArrayVoxelGrid.flatFloor(0);
        pillar.fill(2, 1, 0, 3, 4, 1, true);
        ArrayVoxelGrid bridge = new ArrayVoxelGrid(-12, -12, -12, 12, 12, 12, false).fill(-9, 0, -9, 9, 0, 9, true);
        BumpFrame frame = BumpFrame.of(new Vec3(0.5, 1.0, 0.5), Vec3.UNIT_Y, Vec3.UNIT_X);
        SurfaceCollector.Options options = SurfaceCollector.Options.forParams(PARAMS);
        boolean swelled = false;
        for (ArrayVoxelGrid g : List.of(ridge(1), ridge(2), pillar, bridge, ArrayVoxelGrid.flatFloor(0))) {
            ThinSwell.Result expected = ThinSwell.apply(g, frame, PARAMS, options.radius(), collect(g, frame));
            SurfaceCollector.Scan scan = new SurfaceCollector.Scan(g, frame, options);
            ThinSwell.Sort sort = new ThinSwell.Sort(g, frame, PARAMS, options.radius());
            int sorted = 0;
            boolean done;
            do {
                done = scan.advance(5);
                for (; sorted < scan.points().size(); sorted++) {
                    sort.keep(scan.points().get(sorted));
                }
            } while (!done);
            assertEquals(expected, sort.result());
            swelled |= !expected.fills().isEmpty();
        }
        assertTrue(swelled, "a structure swelled among them");
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
        assertEquals(0, ThinSwell.coverage(0, 1));
        assertEquals(0, ThinSwell.coverage(-1, 1));
        assertEquals(1, ThinSwell.coverage(2, 1));
        assertEquals(0.5, ThinSwell.coverage(1.5, 2), 1e-9);
        assertEquals(1, ThinSwell.coverage(ThinSwell.HILL_SWELL * 3, 3), 1e-9, "a hill of 3 swallows a player whole");
    }

    private static boolean contains(List<ThinSwell.Fill> fills, int x, int y, int z) {
        return fills.stream().anyMatch(f -> f.x() == x && f.y() == y && f.z() == z);
    }

    private static ThinSwell.Fill find(List<ThinSwell.Fill> fills, int x, int y, int z) {
        return fills.stream().filter(f -> f.x() == x && f.y() == y && f.z() == z).findFirst().orElseThrow();
    }
}
