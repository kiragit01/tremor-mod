package tremor.core.deform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import tremor.core.math.Vec3;
import tremor.core.shape.BumpFrame;
import tremor.core.shape.BumpParams;
import tremor.core.surface.SurfaceNormals;
import tremor.core.testing.ArrayVoxelGrid;

class SurfaceCollectorTest {
    private static final double EPS = 1e-12;
    private static final SurfaceCollector.Options DEFAULT_OPTIONS =
            SurfaceCollector.Options.forParams(BumpParams.defaults());
    private static final Comparator<SurfacePoint> YZX = Comparator.comparingInt(SurfacePoint::y)
            .thenComparingInt(SurfacePoint::z).thenComparingInt(SurfacePoint::x);

    /** Bump on top of the floor voxel (0, 0, 0), moving along +x. */
    private static final BumpFrame FLOOR_BUMP = BumpFrame.of(new Vec3(0.5, 1.0, 0.5), Vec3.UNIT_Y, Vec3.UNIT_X);

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(0, expected.distance(actual), EPS, () -> "expected " + expected + " but was " + actual);
    }

    private static boolean containsVoxel(List<SurfacePoint> points, int x, int y, int z) {
        return points.stream().anyMatch(p -> p.x() == x && p.y() == y && p.z() == z);
    }

    private static int discCount(double radius, double offsetAlongNormal) {
        // Voxel centres of one layer at distance offsetAlongNormal from the bump centre's tangent plane.
        int count = 0;
        int r = (int) Math.ceil(radius) + 1;
        for (int u = -r; u <= r; u++) {
            for (int v = -r; v <= r; v++) {
                if (u * u + v * v + offsetAlongNormal * offsetAlongNormal <= radius * radius) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void optionsForParams() {
        SurfaceCollector.Options options = SurfaceCollector.Options.forParams(BumpParams.defaults());
        assertEquals(BumpParams.defaults().influenceRadius(), options.radius());
        assertEquals(2.5, options.normalBand());
        assertEquals(-0.2, options.minNormalDot());
        assertThrows(IllegalArgumentException.class, () -> new SurfaceCollector.Options(-1, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceCollector.Options(Double.NaN, 2, 0));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceCollector.Options(5, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceCollector.Options(5, 2, Double.NaN));
    }

    @Test
    void flatFloorCollectsTheTopLayerDisc() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        List<SurfacePoint> points = SurfaceCollector.collect(g, FLOOR_BUMP, DEFAULT_OPTIONS);
        double radius = DEFAULT_OPTIONS.radius();
        // Voxel centres of the top layer are 0.5 below the bump centre; one per integer (x, z) offset.
        assertEquals(discCount(radius, 0.5), points.size());
        for (SurfacePoint p : points) {
            assertEquals(0, p.y(), "only the top layer: " + p);
            assertVec(Vec3.UNIT_Y, p.normal());
            assertTrue(p.center().distance(FLOOR_BUMP.center()) <= radius + EPS);
        }
        assertTrue(containsVoxel(points, 0, 0, 0));
        assertTrue(containsVoxel(points, 12, 0, 0));
        assertFalse(containsVoxel(points, 13, 0, 0));
    }

    @Test
    void caveCeilingAboveAFloorBumpIsNotCollected() {
        // Floor top at y = 0, four blocks of air (y = 1..4), ceiling from y = 5.
        ArrayVoxelGrid g = ArrayVoxelGrid.cave(0, 5);
        List<SurfacePoint> points = SurfaceCollector.collect(g, FLOOR_BUMP, DEFAULT_OPTIONS);
        assertFalse(points.isEmpty());
        for (SurfacePoint p : points) {
            assertEquals(0, p.y(), "only floor voxels: " + p);
            assertVec(Vec3.UNIT_Y, p.normal());
        }
        assertEquals(discCount(DEFAULT_OPTIONS.radius(), 0.5), points.size());
    }

    @Test
    void bandAndNormalFiltersEachExcludeTheCeiling() {
        // Low cave: ceiling centres are exactly 2.5 above the bump centre, i.e. inside the band.
        ArrayVoxelGrid low = ArrayVoxelGrid.cave(0, 3);
        assertTrue(SurfaceCollector.collect(low, FLOOR_BUMP, DEFAULT_OPTIONS).stream().allMatch(p -> p.y() == 0),
                "the normal filter alone removes the ceiling");

        ArrayVoxelGrid high = ArrayVoxelGrid.cave(0, 5);
        SurfaceCollector.Options noNormalFilter = new SurfaceCollector.Options(13, 2.5, -2);
        assertTrue(SurfaceCollector.collect(high, FLOOR_BUMP, noNormalFilter).stream().allMatch(p -> p.y() == 0),
                "the band alone removes the ceiling");

        SurfaceCollector.Options noFilters = new SurfaceCollector.Options(13, 100, -2);
        assertTrue(SurfaceCollector.collect(high, FLOOR_BUMP, noFilters).stream().anyMatch(p -> p.y() == 5),
                "without the filters the ceiling would bulge too");
    }

    @Test
    void wallBumpCollectsWallVoxels() {
        ArrayVoxelGrid g = ArrayVoxelGrid.wallFacingPlusX(0);
        BumpFrame frame = BumpFrame.of(new Vec3(1.0, 3.5, -2.5), Vec3.UNIT_X, new Vec3(0, 1, 0));
        List<SurfacePoint> points = SurfaceCollector.collect(g, frame, DEFAULT_OPTIONS);
        assertEquals(discCount(DEFAULT_OPTIONS.radius(), 0.5), points.size());
        for (SurfacePoint p : points) {
            assertEquals(0, p.x(), "only the wall face: " + p);
            assertVec(Vec3.UNIT_X, p.normal());
        }
    }

    @Test
    void cornerBumpCollectsFloorAndWall() {
        ArrayVoxelGrid g = ArrayVoxelGrid.floorWallCorner(0, 0);
        BumpFrame frame = BumpFrame.of(new Vec3(2.0, 1.0, 0.5), Vec3.UNIT_Y, Vec3.UNIT_X.negate());
        List<SurfacePoint> points = SurfaceCollector.collect(g, frame, DEFAULT_OPTIONS);
        assertTrue(points.stream().anyMatch(p -> p.x() == 0 && p.y() >= 1), "wall voxels near the floor");
        assertTrue(points.stream().anyMatch(p -> p.y() == 0 && p.x() >= 1), "floor voxels");
        for (SurfacePoint p : points) {
            assertEquals(SurfaceNormals.smoothNormal(g, p.x(), p.y(), p.z()), p.normal());
            assertEquals(1, p.normal().length(), EPS);
            assertTrue(Math.abs(p.center().sub(frame.center()).dot(frame.normal())) <= 2.5 + EPS, "band: " + p);
            assertTrue(p.normal().dot(frame.normal()) >= -0.2, "normal filter: " + p);
        }
    }

    @Test
    void deterministicAndSorted() {
        ArrayVoxelGrid g = ArrayVoxelGrid.room(-5, 1, -5, 5, 4, 5);
        BumpFrame frame = BumpFrame.of(new Vec3(-5.0, 2.3, 0.2), Vec3.UNIT_X, new Vec3(0, -1, 1));
        List<SurfacePoint> first = SurfaceCollector.collect(g, frame, DEFAULT_OPTIONS);
        List<SurfacePoint> second = SurfaceCollector.collect(g, frame, DEFAULT_OPTIONS);
        assertEquals(first, second);
        assertTrue(first.stream().map(SurfacePoint::y).distinct().count() > 1, "spans several layers");
        List<SurfacePoint> sorted = new ArrayList<>(first);
        sorted.sort(YZX);
        assertEquals(sorted, first);
        assertEquals(first.size(), first.stream().distinct().count(), "no duplicates");
    }

    @Test
    void matchesABruteForceScan() {
        ArrayVoxelGrid g = ArrayVoxelGrid.room(-4, 1, -6, 6, 5, 3);
        BumpFrame frame = BumpFrame.of(new Vec3(1.3, 1.0, -0.4), new Vec3(0.2, 1, -0.1), new Vec3(1, 0, 1));
        SurfaceCollector.Options options = new SurfaceCollector.Options(7.5, 2.0, 0.1);
        List<SurfacePoint> expected = new ArrayList<>();
        for (int y = -12; y <= 12; y++) {
            for (int z = -12; z <= 12; z++) {
                for (int x = -12; x <= 12; x++) {
                    Vec3 d = Vec3.voxelCenter(x, y, z).sub(frame.center());
                    Vec3 n = SurfaceNormals.smoothNormal(g, x, y, z);
                    if (d.length() <= options.radius() && Math.abs(d.dot(frame.normal())) <= options.normalBand()
                            && !n.isNearZero() && n.dot(frame.normal()) >= options.minNormalDot()) {
                        expected.add(new SurfacePoint(x, y, z, n));
                    }
                }
            }
        }
        assertFalse(expected.isEmpty());
        assertEquals(expected, SurfaceCollector.collect(g, frame, options));
    }

    @Test
    void centreBuriedDeepInRockCollectsNothing() {
        ArrayVoxelGrid floor = ArrayVoxelGrid.flatFloor(0);
        BumpFrame deep = BumpFrame.of(new Vec3(0.5, -30, 0.5), Vec3.UNIT_Y, Vec3.UNIT_X);
        assertTrue(SurfaceCollector.collect(floor, deep, DEFAULT_OPTIONS).isEmpty());

        ArrayVoxelGrid solid = new ArrayVoxelGrid(-4, -4, -4, 4, 4, 4, true).fill(-4, -4, -4, 4, 4, 4, true);
        BumpFrame inside = BumpFrame.of(new Vec3(0.5, 0.5, 0.5), Vec3.UNIT_Y, Vec3.UNIT_Z);
        assertTrue(SurfaceCollector.collect(solid, inside, DEFAULT_OPTIONS).isEmpty());
    }

    @Test
    void radiusZeroCollectsAtMostTheVoxelAtTheCentre() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        BumpFrame atVoxelCentre = BumpFrame.of(new Vec3(0.5, 0.5, 0.5), Vec3.UNIT_Y, Vec3.UNIT_X);
        List<SurfacePoint> points = SurfaceCollector.collect(g, atVoxelCentre, new SurfaceCollector.Options(0, 1, 0));
        assertEquals(1, points.size());
        assertTrue(containsVoxel(points, 0, 0, 0));
        assertVec(Vec3.UNIT_Y, points.get(0).normal());
    }
}
