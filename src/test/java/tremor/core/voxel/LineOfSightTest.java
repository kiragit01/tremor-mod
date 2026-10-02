package tremor.core.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.SplittableRandom;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tremor.core.VoxelView;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.testing.ArrayVoxelGrid;

class LineOfSightTest {
    private static ArrayVoxelGrid open() {
        return new ArrayVoxelGrid(-10, -10, -10, 10, 10, 10, false);
    }

    private static Vec3 c(int x, int y, int z) {
        return Vec3.voxelCenter(x, y, z);
    }

    private static void assertClear(VoxelView view, Vec3 a, Vec3 b, boolean expected) {
        assertEquals(expected, LineOfSight.clear(view, a, b), a + " -> " + b);
        assertEquals(expected, LineOfSight.clear(view, b, a), b + " -> " + a);
    }

    /** A grid seen through a view that knows nothing about some voxels (they read as solid). */
    private record Masked(ArrayVoxelGrid grid, Set<Long> unknown) implements VoxelView {
        @Override
        public boolean isSolid(int x, int y, int z) {
            return grid.isSolid(x, y, z) || unknown.contains(VoxelPos.pack(x, y, z));
        }

        @Override
        public boolean isKnown(int x, int y, int z) {
            return !unknown.contains(VoxelPos.pack(x, y, z));
        }

        @Override
        public float conductivity(int x, int y, int z) {
            return 1;
        }

        @Override
        public boolean isProtected(int x, int y, int z) {
            return false;
        }
    }

    @Test
    void axisAlignedSegment() {
        ArrayVoxelGrid g = open();
        assertClear(g, c(0, 1, 0), c(5, 1, 0), true);
        g.set(3, 1, 0, true);
        assertClear(g, c(0, 1, 0), c(5, 1, 0), false);
        assertClear(g, c(0, 1, 0), c(2, 1, 0), true);
        // Neighbouring rows are not touched.
        assertClear(g, c(0, 2, 0), c(5, 2, 0), true);
        assertClear(g, c(0, 1, 1), c(5, 1, 1), true);
        assertClear(g, c(3, 1, -4), c(3, 1, 4), false);
        assertClear(g, c(3, -4, 0), c(3, 4, 0), false);
    }

    @Test
    void theEndVoxelsMayBeSolid() {
        ArrayVoxelGrid g = open();
        g.set(0, 1, 0, true).set(5, 1, 0, true);
        assertClear(g, c(0, 1, 0), c(5, 1, 0), true);
        assertClear(g, new Vec3(0.1, 1.9, 0.2), new Vec3(5.9, 1.1, 0.8), true);
        g.set(4, 1, 0, true);
        assertClear(g, c(0, 1, 0), c(5, 1, 0), false);
    }

    @Test
    void zeroLengthIsClearEvenInsideRock() {
        ArrayVoxelGrid g = open();
        g.set(2, 2, 2, true);
        assertTrue(LineOfSight.clear(g, c(2, 2, 2), c(2, 2, 2)));
        assertTrue(LineOfSight.clear(g, new Vec3(2, 2, 2), new Vec3(2, 2, 2)));
    }

    @Test
    void diagonalThroughAnEdgeTouchesBothSideVoxels() {
        // In the plane y = 0 the diagonal between voxel centres passes exactly through the edges x = z = 1, 2.
        Vec3 a = c(0, 0, 0), b = c(3, 0, 3);
        assertClear(open(), a, b, true);
        for (int[] side : new int[][]{{1, 0}, {0, 1}, {2, 1}, {1, 2}, {3, 2}, {2, 3}, {1, 1}, {2, 2}}) {
            ArrayVoxelGrid g = open();
            g.set(side[0], 0, side[1], true);
            assertClear(g, a, b, false);
        }
        // Squeezing exactly between two solid voxels that meet along the edge: blocked.
        ArrayVoxelGrid squeeze = open();
        squeeze.set(1, 0, 0, true).set(0, 0, 1, true);
        assertClear(squeeze, a, b, false);
        // Voxels the segment does not touch.
        for (int[] off : new int[][]{{2, 0}, {0, 2}, {3, 1}, {1, 3}, {3, 0}, {0, 3}}) {
            ArrayVoxelGrid g = open();
            g.set(off[0], 0, off[1], true);
            assertClear(g, a, b, true);
        }
        // Just off the diagonal only one side of the edge is touched.
        ArrayVoxelGrid g = open();
        g.set(1, 0, 0, true);
        assertClear(g, c(0, 0, 0), new Vec3(2.5, 0.5, 2.6), true);
        g.set(0, 0, 1, true);
        assertClear(g, c(0, 0, 0), new Vec3(2.5, 0.5, 2.6), false);
    }

    @Test
    void diagonalThroughACornerTouchesAllSixVoxelsAroundIt() {
        Vec3 a = c(0, 0, 0), b = c(2, 2, 2);
        assertClear(open(), a, b, true);
        int[][] around = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {1, 1, 0}, {1, 0, 1}, {0, 1, 1}, {1, 1, 1}};
        for (int[] v : around) {
            ArrayVoxelGrid g = open();
            g.set(v[0], v[1], v[2], true);
            assertClear(g, a, b, false);
        }
        for (int[] v : new int[][]{{2, 0, 0}, {0, 2, 0}, {2, 2, 0}, {2, 0, 1}}) {
            ArrayVoxelGrid g = open();
            g.set(v[0], v[1], v[2], true);
            assertClear(g, a, b, true);
        }
    }

    @Test
    void segmentInAFacePlaneTouchesBothSides() {
        ArrayVoxelGrid floor = new ArrayVoxelGrid(-10, -10, -10, 10, 10, 10, false);
        floor.fill(-10, -10, -10, 10, 0, 10, true);
        // Grazing the top of the floor (y = 1) is blocked; a hair above it is clear.
        assertClear(floor, new Vec3(0.5, 1, 0.5), new Vec3(6.5, 1, 0.5), false);
        assertClear(floor, new Vec3(0.5, 1.001, 0.5), new Vec3(6.5, 1.001, 0.5), true);
        // Along an edge of the grid (y = 1, z = 1): the four voxels around it.
        assertClear(floor, new Vec3(0.5, 1, 1), new Vec3(6.5, 1, 1), false);
        ArrayVoxelGrid g = open();
        g.set(3, 1, 0, true);
        assertClear(g, new Vec3(0.5, 2, 1), new Vec3(6.5, 2, 1), false);
        assertClear(g, new Vec3(0.5, 2.001, 1), new Vec3(6.5, 2.001, 1), true);
    }

    @Test
    void endPointOnAFaceExcludesBothVoxelsSharingIt() {
        ArrayVoxelGrid g = open();
        g.set(0, 0, 0, true).set(0, 1, 0, true);
        // The start point lies on the face between (0, 0, 0) and (0, 1, 0): both are end voxels.
        assertClear(g, new Vec3(0.5, 1, 0.5), new Vec3(4.5, 3.5, 0.5), true);
        g.set(1, 1, 0, true);
        assertClear(g, new Vec3(0.5, 1, 0.5), new Vec3(4.5, 3.5, 0.5), false);
    }

    @Test
    void unknownVoxelsBlock() {
        Set<Long> unknown = new HashSet<>();
        Masked view = new Masked(open(), unknown);
        assertClear(view, c(0, 0, 0), c(6, 2, 3), true);
        unknown.add(VoxelPos.pack(3, 1, 1));
        assertClear(view, c(0, 0, 0), c(6, 2, 3), false);
        // As an end voxel it does not matter.
        assertClear(view, c(0, 0, 0), c(3, 1, 1), true);
    }

    @Test
    void lengthLimit() {
        ArrayVoxelGrid g = new ArrayVoxelGrid(0, 0, 0, 0, 0, 0, false);
        Vec3 a = new Vec3(0.5, 0.5, 0.5);
        assertTrue(LineOfSight.clear(g, a, a.add(LineOfSight.MAX_LENGTH, 0, 0)));
        assertFalse(LineOfSight.clear(g, a, a.add(LineOfSight.MAX_LENGTH + 0.01, 0, 0)));
        assertTrue(LineOfSight.clear(g, a, a.add(150, 150, 120)));
        assertFalse(LineOfSight.clear(g, a, a.add(150, 150, 150)));
        assertFalse(LineOfSight.clear(g, a, new Vec3(Double.NaN, 0, 0)));
        assertFalse(LineOfSight.clear(g, a, new Vec3(Double.POSITIVE_INFINITY, 0, 0)));
    }

    /** Matches a brute-force test of every voxel's closed cube against the segment (slab method). */
    @Test
    void matchesBruteForceOnRandomGrids() {
        SplittableRandom random = new SplittableRandom(7);
        for (int scene = 0; scene < 40; scene++) {
            ArrayVoxelGrid g = new ArrayVoxelGrid(0, 0, 0, 7, 7, 7, false);
            for (int x = 0; x < 8; x++) {
                for (int y = 0; y < 8; y++) {
                    for (int z = 0; z < 8; z++) {
                        g.set(x, y, z, random.nextDouble() < 0.12);
                    }
                }
            }
            for (int i = 0; i < 300; i++) {
                Vec3 a, b;
                switch (i % 3) {
                    // Voxel centres: many segments through edges and corners.
                    case 0 -> {
                        a = c(random.nextInt(8), random.nextInt(8), random.nextInt(8));
                        b = c(random.nextInt(8), random.nextInt(8), random.nextInt(8));
                    }
                    // Integer and half-integer coordinates: segments in face planes, ends on faces and edges.
                    case 1 -> {
                        a = new Vec3(random.nextInt(17) / 2.0, random.nextInt(17) / 2.0, random.nextInt(17) / 2.0);
                        b = new Vec3(random.nextInt(17) / 2.0, random.nextInt(17) / 2.0, random.nextInt(17) / 2.0);
                    }
                    default -> {
                        a = new Vec3(random.nextDouble(8), random.nextDouble(8), random.nextDouble(8));
                        b = new Vec3(random.nextDouble(8), random.nextDouble(8), random.nextDouble(8));
                    }
                }
                assertEquals(bruteForce(g, a, b), LineOfSight.clear(g, a, b), a + " -> " + b);
            }
        }
    }

    private static boolean bruteForce(VoxelView view, Vec3 a, Vec3 b) {
        int x0 = (int) Math.floor(Math.min(a.x(), b.x())) - 1, x1 = (int) Math.floor(Math.max(a.x(), b.x())) + 1;
        int y0 = (int) Math.floor(Math.min(a.y(), b.y())) - 1, y1 = (int) Math.floor(Math.max(a.y(), b.y())) + 1;
        int z0 = (int) Math.floor(Math.min(a.z(), b.z())) - 1, z1 = (int) Math.floor(Math.max(a.z(), b.z())) + 1;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    boolean end = inside(x, y, z, a) || inside(x, y, z, b);
                    if (!end && (view.isSolid(x, y, z) || !view.isKnown(x, y, z)) && touches(x, y, z, a, b)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean inside(int x, int y, int z, Vec3 p) {
        return p.x() >= x && p.x() <= x + 1 && p.y() >= y && p.y() <= y + 1 && p.z() >= z && p.z() <= z + 1;
    }

    /** Closed segment against the closed unit cube of the voxel. */
    private static boolean touches(int x, int y, int z, Vec3 a, Vec3 b) {
        double[] start = {a.x(), a.y(), a.z()}, d = {b.x() - a.x(), b.y() - a.y(), b.z() - a.z()};
        int[] lo = {x, y, z};
        double tMin = 0, tMax = 1;
        for (int i = 0; i < 3; i++) {
            if (d[i] == 0) {
                if (start[i] < lo[i] || start[i] > lo[i] + 1) {
                    return false;
                }
                continue;
            }
            double t1 = (lo[i] - start[i]) / d[i], t2 = (lo[i] + 1 - start[i]) / d[i];
            tMin = Math.max(tMin, Math.min(t1, t2));
            tMax = Math.min(tMax, Math.max(t1, t2));
        }
        return tMin <= tMax;
    }
}
