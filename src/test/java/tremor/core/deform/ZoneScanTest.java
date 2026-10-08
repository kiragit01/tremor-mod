package tremor.core.deform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tremor.core.VoxelView;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.surface.SurfaceNormals;
import tremor.core.testing.ArrayVoxelGrid;

class ZoneScanTest {
    private static final double EPS = 1e-12;

    private static List<SurfacePoint> scan(VoxelView view, Vec3 center, double radius, int below, int above) {
        ZoneScan scan = new ZoneScan(view, center, radius, below, above);
        assertTrue(scan.advance(Integer.MAX_VALUE));
        assertTrue(scan.done());
        return scan.points();
    }

    private static Set<Long> voxels(List<SurfacePoint> points) {
        Set<Long> set = new HashSet<>();
        for (SurfacePoint p : points) {
            assertTrue(set.add(VoxelPos.pack(p.x(), p.y(), p.z())), "reported twice: " + p);
        }
        return set;
    }

    /** Voxel columns (x, z) whose centres lie within {@code radius} of {@code (cx, cz)}. */
    private static int discCount(double cx, double cz, double radius) {
        int count = 0, r = (int) Math.ceil(radius) + 1;
        for (int x = (int) Math.floor(cx) - r; x <= cx + r; x++) {
            for (int z = (int) Math.floor(cz) - r; z <= cz + r; z++) {
                double dx = x + 0.5 - cx, dz = z + 0.5 - cz;
                if (dx * dx + dz * dz <= radius * radius) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void validation() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        assertThrows(IllegalArgumentException.class, () -> new ZoneScan(g, Vec3.ZERO, -1, 4, 4));
        assertThrows(IllegalArgumentException.class, () -> new ZoneScan(g, Vec3.ZERO, Double.NaN, 4, 4));
        assertThrows(IllegalArgumentException.class, () -> new ZoneScan(g, Vec3.ZERO, 5, -1, 4));
        assertThrows(IllegalArgumentException.class, () -> new ZoneScan(g, Vec3.ZERO, 600, 4, 4));
    }

    @Test
    void flatFloorGivesTheTopLayerDisc() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        Vec3 center = new Vec3(0.3, 1.0, 0.8);
        double radius = 12.5;
        List<SurfacePoint> points = scan(g, center, radius, 8, 8);
        assertEquals(discCount(center.x(), center.z(), radius), points.size());
        for (SurfacePoint p : points) {
            assertEquals(0, p.y(), "only the top layer: " + p);
            assertEquals(0, Vec3.UNIT_Y.distance(p.normal()), EPS);
            double dx = p.x() + 0.5 - center.x(), dz = p.z() + 0.5 - center.z();
            assertTrue(dx * dx + dz * dz <= radius * radius, "in the cylinder: " + p);
        }
        voxels(points);
        // The flood runs outward from the centre: the first point is right under it.
        assertEquals(0, points.get(0).x());
        assertEquals(0, points.get(0).z());
    }

    @Test
    void startsAboveTheCentreWhenItIsInsideTheGround() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        Vec3 inside = new Vec3(0.5, -2.5, 0.5);
        assertEquals(voxels(scan(g, new Vec3(0.5, 1, 0.5), 9, 8, 8)), voxels(scan(g, inside, 9, 8, 8)));
        // But not beyond the reach above.
        assertTrue(scan(g, inside, 9, 8, 2).isEmpty());
        // A centre high in the air floods down to the ground.
        assertEquals(discCount(0.5, 0.5, 9), scan(g, new Vec3(0.5, 7.2, 0.5), 9, 8, 8).size());
    }

    @Test
    void caveFloorWallsAndCeilingAlike() {
        // A room 9 x 5 x 9 in solid rock: everything but its edges and corners is surface.
        ArrayVoxelGrid g = ArrayVoxelGrid.room(-4, 1, -4, 4, 5, 4);
        List<SurfacePoint> points = scan(g, new Vec3(0.5, 1, 0.5), 20, 10, 10);
        int w = 9, h = 5, d = 9;
        assertEquals(2 * w * d + 2 * h * d + 2 * h * w, points.size());
        Set<Long> set = voxels(points);
        assertTrue(set.contains(VoxelPos.pack(0, 0, 0)), "floor");
        assertTrue(set.contains(VoxelPos.pack(0, 6, 0)), "ceiling");
        assertTrue(set.contains(VoxelPos.pack(-5, 3, 0)), "wall");
        for (SurfacePoint p : points) {
            assertEquals(1, p.normal().length(), 1e-9);
            assertEquals(SurfaceNormals.smoothNormal(g, p.x(), p.y(), p.z()), p.normal(), "as SPEC 6.2: " + p);
        }
    }

    @Test
    void openCaveWithoutWallsStillReachesTheCeiling() {
        // Floor and ceiling 6 apart, no walls anywhere near: the open space between them connects them.
        ArrayVoxelGrid g = ArrayVoxelGrid.cave(0, 7);
        List<SurfacePoint> points = scan(g, new Vec3(0.5, 1, 0.5), 10, 10, 10);
        int disc = discCount(0.5, 0.5, 10);
        assertEquals(2 * disc, points.size());
        assertEquals(disc, points.stream().filter(p -> p.y() == 0 && p.normal().y() > 0.99).count());
        assertEquals(disc, points.stream().filter(p -> p.y() == 7 && p.normal().y() < -0.99).count());
    }

    @Test
    void sealedCavesAreLeftOut() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        g.fill(-3, -8, -3, 3, -6, 3, false); // a sealed room right under the zone
        List<SurfacePoint> points = scan(g, new Vec3(0.5, 1, 0.5), 10, 16, 8);
        assertEquals(discCount(0.5, 0.5, 10), points.size());
        assertTrue(points.stream().allMatch(p -> p.y() == 0));
        // Opened to the surface by a shaft, its ground is part of the zone.
        g.fill(0, -5, 0, 0, 0, 0, false);
        List<SurfacePoint> opened = scan(g, new Vec3(2.5, 1, 2.5), 10, 16, 8);
        assertTrue(opened.stream().anyMatch(p -> p.y() == -9), "the room's floor");
        assertTrue(opened.stream().anyMatch(p -> p.y() == -5 && p.x() == 1), "the room's ceiling");
    }

    @Test
    void verticalReachCutsOffWhatIsBeyond() {
        ArrayVoxelGrid g = ArrayVoxelGrid.cave(0, 7);
        List<SurfacePoint> points = scan(g, new Vec3(0.5, 1, 0.5), 6, 4, 5);
        // The centre's voxel is y = 1: the cylinder spans y = -3..6, the ceiling (y = 7) is beyond it.
        assertTrue(points.stream().allMatch(p -> p.y() == 0));
        assertEquals(discCount(0.5, 0.5, 6), points.size());
    }

    @Test
    void bothLevelsOfAStep() {
        ArrayVoxelGrid g = ArrayVoxelGrid.step(1, 0);
        List<SurfacePoint> points = scan(g, new Vec3(-3.5, 2, 0.5), 8, 6, 6);
        Set<Long> set = voxels(points);
        assertTrue(set.contains(VoxelPos.pack(-3, 1, 0)), "upper level");
        assertTrue(set.contains(VoxelPos.pack(3, 0, 0)), "lower level");
        assertTrue(set.contains(VoxelPos.pack(0, 1, 0)), "the edge");
    }

    @Test
    void unknownVoxelsAreNeverSurface() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        VoxelView halfLoaded = new VoxelView() {
            @Override
            public boolean isSolid(int x, int y, int z) {
                return x > 3 || g.isSolid(x, y, z); // unknown reads as solid
            }

            @Override
            public boolean isKnown(int x, int y, int z) {
                return x <= 3;
            }

            @Override
            public float conductivity(int x, int y, int z) {
                return 1;
            }

            @Override
            public boolean isProtected(int x, int y, int z) {
                return false;
            }
        };
        List<SurfacePoint> points = scan(halfLoaded, new Vec3(0.5, 1, 0.5), 8, 4, 4);
        assertFalse(points.isEmpty());
        assertTrue(points.stream().allMatch(p -> p.x() <= 3));
    }

    @Test
    void nothingWithoutOpenSpace() {
        ArrayVoxelGrid solid = new ArrayVoxelGrid(-4, -4, -4, 4, 4, 4, true).fill(-4, -4, -4, 4, 4, 4, true);
        assertTrue(scan(solid, new Vec3(0.5, 0.5, 0.5), 3, 2, 2).isEmpty());
        // A radius too small to hold the centre's own column.
        ZoneScan tiny = new ZoneScan(ArrayVoxelGrid.flatFloor(0), new Vec3(0, 1, 0), 0.5, 2, 2);
        assertTrue(tiny.advance(1));
        assertTrue(tiny.points().isEmpty());
    }

    @Test
    void stepByStepGivesTheSameAsAllAtOnce() {
        ArrayVoxelGrid g = ArrayVoxelGrid.room(-6, 1, -6, 6, 4, 6);
        g.fill(-2, 1, -2, -1, 4, 2, true); // a pillar
        Vec3 center = new Vec3(3.5, 1, 3.5);
        List<SurfacePoint> once = scan(g, center, 15, 8, 8);
        ZoneScan steps = new ZoneScan(g, center, 15, 8, 8);
        int calls = 0;
        while (!steps.advance(7)) {
            calls++;
            assertFalse(steps.done());
        }
        assertTrue(calls > 10, "it really went in steps");
        assertEquals(once, steps.points());
        assertTrue(steps.advance(7), "stays done");
        assertEquals(once, steps.points());
    }

    @Test
    void boxIsTheCylinder() {
        ZoneScan scan = new ZoneScan(ArrayVoxelGrid.flatFloor(0), new Vec3(10.25, 64.7, -3.5), 5, 3, 7);
        // Voxel x is in it if x + 0.5 lies within 5 of 10.25.
        assertEquals(5, scan.minX());
        assertEquals(14, scan.maxX());
        assertEquals(-9, scan.minZ());
        assertEquals(1, scan.maxZ());
        assertEquals(61, scan.minY());
        assertEquals(71, scan.maxY());
    }
}
