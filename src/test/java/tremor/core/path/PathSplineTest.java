package tremor.core.path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tremor.core.path.TestGraphs.p;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;

class PathSplineTest {
    private static final double EPS = 1e-9;

    private static void assertVec(Vec3 expected, Vec3 actual, double eps) {
        assertEquals(0, expected.distance(actual), eps, () -> "expected " + expected + " but was " + actual);
    }

    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z());
    }

    private static Path path(boolean[] dive, long... nodes) {
        return new Path(nodes, dive, true);
    }

    private static Path path(long... nodes) {
        return path(new boolean[Math.max(0, nodes.length - 1)], nodes);
    }

    /** Smallest arc length whose {@link PathSpline#segment} is at least {@code edge}, by bisection. */
    private static double edgeStart(PathSpline spline, int edge) {
        double lo = 0, hi = spline.length();
        for (int i = 0; i < 200; i++) {
            double mid = 0.5 * (lo + hi);
            if (spline.segment(mid) >= edge) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return hi;
    }

    /** Floor, up a wall, along the ceiling, with a diagonal and a 3D step: the shapes a surface path has. */
    private static Path voxelPath() {
        return path(new boolean[]{false, false, false, false, false, true, false, false, false, false},
                p(0, 0, 0), p(1, 0, 0), p(2, 0, 1), p(3, 0, 1), p(4, 1, 1), p(4, 2, 1), p(4, 5, 1), p(5, 6, 2),
                p(6, 6, 2), p(7, 6, 3), p(8, 7, 4));
    }

    /**
     * Random path like a search returns: 26-neighbour steps and occasional longer axis-aligned dives, never revisiting
     * a voxel and never turning straight back (that would be a cusp, which a real path does not have).
     */
    private static Path randomWalk(Random random, int size) {
        long[] nodes = new long[size];
        boolean[] dive = new boolean[size - 1];
        Set<Long> visited = new HashSet<>();
        int x = 0, y = 0, z = 0, px = 0, py = 0, pz = 0;
        nodes[0] = p(x, y, z);
        visited.add(nodes[0]);
        for (int i = 1; i < size; i++) {
            int dx, dy, dz;
            boolean isDive;
            do {
                isDive = random.nextInt(8) == 0;
                if (isDive) {
                    int axis = random.nextInt(3), k = (2 + random.nextInt(3)) * (random.nextBoolean() ? 1 : -1);
                    dx = axis == 0 ? k : 0;
                    dy = axis == 1 ? k : 0;
                    dz = axis == 2 ? k : 0;
                } else {
                    dx = random.nextInt(3) - 1;
                    dy = random.nextInt(3) - 1;
                    dz = random.nextInt(3) - 1;
                }
            } while (dx == 0 && dy == 0 && dz == 0 || visited.contains(p(x + dx, y + dy, z + dz))
                    || antiparallel(px, py, pz, dx, dy, dz));
            x += dx;
            y += dy;
            z += dz;
            px = dx;
            py = dy;
            pz = dz;
            nodes[i] = p(x, y, z);
            visited.add(nodes[i]);
            dive[i - 1] = isDive;
        }
        return new Path(nodes, dive, true);
    }

    private static boolean antiparallel(int ax, int ay, int az, int bx, int by, int bz) {
        double dot = ax * bx + ay * by + az * bz;
        double lengths = Math.sqrt((double) (ax * ax + ay * ay + az * az) * (bx * bx + by * by + bz * bz));
        return lengths > 0 && dot <= -lengths * (1 - 1e-12);
    }

    @Test
    void singleNodeAtTheStartHasLengthZero() {
        Vec3 start = VoxelPos.center(p(0, 0, 0));
        PathSpline spline = PathSpline.of(start, path(p(0, 0, 0)));
        assertEquals(0, spline.length());
        assertSame(start, spline.position(0));
        assertSame(start, spline.position(5));
        assertSame(Vec3.ZERO, spline.tangent(0));
        assertSame(Vec3.ZERO, spline.startChord());
        assertEquals(0, spline.segment(0));
        assertFalse(spline.isDive(0));
        assertEquals(Double.POSITIVE_INFINITY, spline.nextDiveStart(0));
    }

    @Test
    void singleNodeElsewhereGivesALeadInToIt() {
        // The goal is the node nearest to the crawler, which is not exactly at its centre: it still has to get there.
        Vec3 start = new Vec3(0.3, 0.7, 0.1);
        Vec3 center = VoxelPos.center(p(0, 0, 0));
        PathSpline spline = PathSpline.of(start, path(p(0, 0, 0)));
        assertEquals(start.distance(center), spline.length(), 1e-9);
        Vec3 dir = center.sub(start).normalize();
        for (double s = 0; s <= spline.length(); s += 0.05) {
            assertVec(start.add(dir.scale(s)), spline.position(s), 1e-9);
            assertVec(dir, spline.tangent(s), 1e-9);
            assertEquals(-1, spline.segment(s));
            assertFalse(spline.isDive(s));
        }
        assertVec(center, spline.position(spline.length()), 1e-9);
        assertVec(center.sub(start), spline.startChord(), 1e-12);
        assertEquals(Double.POSITIVE_INFINITY, spline.nextDiveStart(0));
    }

    @Test
    void diveLeadInKeepsNodeZeroAndStaysADive() {
        // Replan mid-dive: the crawler is inside the rock between (1, 0, 0) and the exit (4, 0, 0); the route starts at
        // the exit, then a skin edge, a dive and a skin edge.
        Vec3 start = new Vec3(2.2, 0.5, 0.5);
        Path path = path(new boolean[]{false, true, false}, p(4, 0, 0), p(5, 0, 0), p(8, 0, 0), p(9, 0, 0));
        PathSpline spline = PathSpline.of(start, Vec3.UNIT_X, path, true);
        assertVec(start, spline.position(0), EPS);
        double exit = edgeStart(spline, 0);
        assertEquals(2.3, exit, 1e-6);
        assertVec(path.point(0), spline.position(exit), 1e-9);
        assertEquals(7.3, spline.length(), 1e-6);
        for (double s = 0; s <= spline.length(); s += 0.01) {
            int expected = s < exit ? -1 : s < exit + 1 ? 0 : s < exit + 4 ? 1 : 2;
            if (Math.abs(s - exit) > 1e-6 && Math.abs(s - exit - 1) > 1e-6 && Math.abs(s - exit - 4) > 1e-6) {
                assertEquals(expected, spline.segment(s), "at " + s);
                assertEquals(expected == -1 || expected == 1, spline.isDive(s), "at " + s);
            }
        }
        assertEquals(0, spline.nextDiveStart(0));
        assertEquals(1.5, spline.nextDiveStart(1.5));
        assertEquals(exit + 1, spline.nextDiveStart(exit), 1e-6);
        assertEquals(Double.POSITIVE_INFINITY, spline.nextDiveStart(exit + 4.5));
        assertVec(path.point(0).sub(start), spline.startChord(), 1e-12);

        // Without the dive flag node 0 is replaced by the start: start -> (5, 0, 0) is edge 0, on the skin.
        PathSpline replaced = PathSpline.of(start, path);
        assertEquals(0, replaced.segment(0));
        assertFalse(replaced.isDive(0));
        assertVec(path.point(1).sub(start), replaced.startChord(), 1e-12);

        // A dive start at node 0 itself has nothing left to cross.
        PathSpline atExit = PathSpline.of(path.point(0), Vec3.ZERO, path, true);
        assertEquals(0, atExit.segment(0));
        assertFalse(atExit.isDive(0));
        assertEquals(5, atExit.length(), 1e-6);
    }

    @Test
    void startDirectionSetsTheInitialTangent() {
        Path path = voxelPath();
        Vec3 start = new Vec3(0.7, 0.4, 0.5);
        Vec3 natural = PathSpline.of(start, path).tangent(0);
        for (Vec3 direction : new Vec3[]{new Vec3(1, 0, 0.8), new Vec3(0.3, 0.2, 1), new Vec3(-1, 0, 0.1)}) {
            PathSpline spline = PathSpline.of(start, direction.scale(3), path, false);
            assertVec(start, spline.position(0), EPS);
            assertVec(direction.normalize(), spline.tangent(0), 1e-9);
            assertVec(path.point(path.size() - 1), spline.position(spline.length()), EPS);
            for (int i = 1; i < path.size() - 1; i++) {
                double s = edgeStart(spline, i);
                assertVec(path.point(i), spline.position(s), 1e-9);
                assertTrue(spline.tangent(s - 1e-7).distance(spline.tangent(s + 1e-7)) < 1e-4, "tangent jump at " + i);
            }
            for (double s = 0; s <= spline.length(); s += 0.01) {
                assertTrue(finite(spline.position(s)));
                assertEquals(1, spline.tangent(s).length(), 1e-9);
            }
        }
        // ZERO is the natural start.
        assertVec(natural, PathSpline.of(start, Vec3.ZERO, path, false).tangent(0), 1e-12);
    }

    @Test
    void nextDiveStart() {
        Path path = voxelPath();
        PathSpline spline = PathSpline.of(path.point(0), path);
        double diveStart = edgeStart(spline, 5), diveEnd = edgeStart(spline, 6);
        assertEquals(diveStart, spline.nextDiveStart(0), 1e-6);
        assertEquals(diveStart, spline.nextDiveStart(-1), 1e-6);
        assertEquals(diveStart, spline.nextDiveStart(diveStart - 0.3), 1e-6);
        assertEquals(diveStart + 1, spline.nextDiveStart(diveStart + 1));
        assertEquals(Double.POSITIVE_INFINITY, spline.nextDiveStart(diveEnd + 1e-6));
        assertEquals(Double.POSITIVE_INFINITY, spline.nextDiveStart(spline.length()));
        assertEquals(Double.POSITIVE_INFINITY, PathSpline.of(path.point(0), straightPath()).nextDiveStart(0));
        for (double s = 0; s < spline.length(); s += 0.01) {
            double next = spline.nextDiveStart(s);
            assertTrue(next >= s);
            if (Double.isFinite(next)) {
                assertTrue(spline.isDive(next + 1e-9), "at " + s);
            }
            assertEquals(spline.isDive(s), next == s, "at " + s);
        }
    }

    private static Path straightPath() {
        return path(p(0, 0, 0), p(1, 0, 0), p(2, 0, 0));
    }

    @Test
    void allPointsEqualHasLengthZero() {
        Vec3 start = VoxelPos.center(p(3, 1, 2));
        PathSpline spline = PathSpline.of(start, path(new boolean[]{true, false}, p(3, 1, 2), p(3, 1, 2), p(3, 1, 2)));
        assertEquals(0, spline.length());
        assertSame(start, spline.position(0.5));
        assertSame(Vec3.ZERO, spline.tangent(0));
    }

    @Test
    void twoNodesGiveAStraightLine() {
        Vec3 start = new Vec3(0.5, 0.5, 0.5);
        PathSpline spline = PathSpline.of(start, path(p(0, 0, 0), p(3, 4, 0)));
        assertEquals(5, spline.length(), 1e-9);
        Vec3 dir = new Vec3(0.6, 0.8, 0);
        for (double s = 0; s <= 5; s += 0.125) {
            assertVec(start.add(dir.scale(s)), spline.position(s), 1e-9);
            assertVec(dir, spline.tangent(s), 1e-9);
        }
    }

    @Test
    void startReplacesTheFirstNode() {
        Vec3 start = new Vec3(0.9, 0.2, 0.5);
        Path path = path(p(0, 0, 0), p(1, 0, 0), p(2, 0, 0));
        PathSpline spline = PathSpline.of(start, path);
        assertSame(path, spline.path());
        assertVec(start, spline.position(0), EPS);
        assertVec(VoxelPos.center(p(2, 0, 0)), spline.position(spline.length()), EPS);
        // Clamped outside [0, length].
        assertVec(spline.position(0), spline.position(-3), EPS);
        assertVec(spline.position(spline.length()), spline.position(spline.length() + 3), EPS);
    }

    @Test
    void passesThroughEveryControlPoint() {
        Path path = voxelPath();
        PathSpline spline = PathSpline.of(path.point(0), path);
        assertVec(path.point(0), spline.position(0), EPS);
        assertVec(path.point(path.size() - 1), spline.position(spline.length()), EPS);
        for (int i = 1; i < path.size() - 1; i++) {
            assertVec(path.point(i), spline.position(edgeStart(spline, i)), 1e-9);
        }
        assertTrue(spline.length() >= path.length() - EPS, "a curve through the points is at least the polyline");
    }

    @Test
    void segmentsRunThroughTheEdgesInOrderAndCarryTheDiveFlags() {
        Path path = voxelPath();
        PathSpline spline = PathSpline.of(path.point(0), path);
        int previous = 0;
        boolean[] seen = new boolean[path.size() - 1];
        for (double s = 0; s <= spline.length(); s += 0.01) {
            int segment = spline.segment(s);
            assertTrue(segment >= previous && segment <= previous + 1, "segment " + segment + " after " + previous);
            previous = segment;
            seen[segment] = true;
            assertEquals(path.dive()[segment], spline.isDive(s));
        }
        for (boolean b : seen) {
            assertTrue(b);
        }
        assertEquals(path.size() - 2, spline.segment(spline.length()));
        assertTrue(spline.isDive(0.5 * (edgeStart(spline, 5) + edgeStart(spline, 6))));
        assertFalse(spline.isDive(edgeStart(spline, 6))); // a control point belongs to the edge starting there
    }

    @Test
    void tangentIsContinuousAtControlPoints() {
        Random random = new Random(42);
        for (int trial = 0; trial < 20; trial++) {
            Path path = trial == 0 ? voxelPath() : randomWalk(random, 12);
            PathSpline spline = PathSpline.of(path.point(0), path);
            for (int i = 1; i < path.size() - 1; i++) {
                double s = edgeStart(spline, i);
                Vec3 before = spline.tangent(s - 1e-7);
                Vec3 after = spline.tangent(s + 1e-7);
                assertEquals(1, before.length(), 1e-9);
                assertTrue(before.distance(after) < 1e-4, "tangent jump " + before + " -> " + after + " at " + i);
            }
        }
    }

    @Test
    void tangentIsTheDirectionOfTravel() {
        Path path = voxelPath();
        PathSpline spline = PathSpline.of(path.point(0), path);
        double h = 1e-5;
        for (double s = h; s < spline.length() - h; s += 0.05) {
            Vec3 numeric = spline.position(s + h).sub(spline.position(s - h)).scale(1 / (2 * h));
            assertVec(spline.tangent(s), numeric, 1e-4);
        }
    }

    @Test
    void parametrizedByArcLength() {
        Random random = new Random(7);
        for (int trial = 0; trial < 20; trial++) {
            Path path = trial == 0 ? voxelPath() : randomWalk(random, 15);
            PathSpline spline = PathSpline.of(path.point(0), path);
            double length = spline.length();
            // Close points: chord = arc length difference.
            double h = 1e-3;
            for (double s = 0; s + h <= length; s += 0.013) {
                double chord = spline.position(s).distance(spline.position(s + h));
                assertTrue(chord <= h * (1 + 1e-6), "chord " + chord + " > " + h);
                assertTrue(chord >= h * (1 - 1e-3), "chord " + chord + " << " + h + " at " + s);
            }
            // The total agrees with a dense polyline.
            int n = 50_000;
            double polyline = 0;
            Vec3 prev = spline.position(0);
            for (int k = 1; k <= n; k++) {
                Vec3 next = spline.position(length * k / n);
                polyline += prev.distance(next);
                prev = next;
            }
            assertEquals(length, polyline, length * 1e-6);
        }
    }

    @Test
    void staysCloseToThePolyline() {
        // Centripetal Catmull-Rom does not overshoot corners: every point is within half a block of the voxel path.
        Path path = voxelPath();
        PathSpline spline = PathSpline.of(path.point(0), path);
        for (double s = 0; s <= spline.length(); s += 0.02) {
            Vec3 q = spline.position(s);
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i + 1 < path.size(); i++) {
                best = Math.min(best, distanceToSegment(q, path.point(i), path.point(i + 1)));
            }
            assertTrue(best < 0.5, "point " + q + " is " + best + " from the polyline");
        }
    }

    private static double distanceToSegment(Vec3 q, Vec3 a, Vec3 b) {
        Vec3 ab = b.sub(a);
        double t = Math.clamp(q.sub(a).dot(ab) / ab.lengthSquared(), 0, 1);
        return q.distance(a.add(ab.scale(t)));
    }

    @Test
    void duplicateStartIsSkipped() {
        // The crawler stands exactly on the centre of node 1: edge 0 has no length.
        Path path = path(new boolean[]{true, false, false}, p(0, 0, 0), p(1, 0, 0), p(2, 0, 0), p(2, 1, 0));
        PathSpline spline = PathSpline.of(VoxelPos.center(p(1, 0, 0)), path);
        PathSpline reference = PathSpline.of(VoxelPos.center(p(1, 0, 0)),
                path(p(1, 0, 0), p(2, 0, 0), p(2, 1, 0)));
        assertEquals(reference.length(), spline.length(), EPS);
        for (double s = 0; s <= spline.length(); s += 0.05) {
            assertVec(reference.position(s), spline.position(s), EPS);
            assertTrue(spline.segment(s) >= 1);
            assertFalse(spline.isDive(s));
        }
    }

    @Test
    void duplicateConsecutiveNodesAreRobust() {
        Path path = path(new boolean[]{false, true, false, false}, p(0, 0, 0), p(1, 0, 0), p(1, 0, 0), p(2, 0, 0),
                p(2, 0, 0));
        PathSpline spline = PathSpline.of(path.point(0), path);
        assertEquals(2, spline.length(), 1e-9);
        for (double s = 0; s <= spline.length(); s += 0.05) {
            Vec3 q = spline.position(s);
            assertTrue(finite(q));
            assertVec(new Vec3(0.5 + s, 0.5, 0.5), q, 1e-9);
            assertVec(Vec3.UNIT_X, spline.tangent(s), 1e-9);
            int segment = spline.segment(s);
            assertTrue(segment == 0 || segment == 2, "zero-length edges are never returned: " + segment);
            assertFalse(spline.isDive(s));
        }
        assertEquals(2, spline.segment(spline.length()));
    }

    @Test
    void pathTurningBackIsRobust() {
        Path path = path(p(0, 0, 0), p(1, 0, 0), p(0, 0, 0), p(0, 0, 1));
        PathSpline spline = PathSpline.of(path.point(0), path);
        for (double s = 0; s <= spline.length(); s += 0.01) {
            assertTrue(finite(spline.position(s)));
            assertEquals(1, spline.tangent(s).length(), 1e-9);
        }
        assertVec(path.point(1), spline.position(edgeStart(spline, 1)), 1e-9);
    }

    @Test
    void longDiveEdge() {
        Path path = path(new boolean[]{false, true, false}, p(0, 0, 0), p(1, 0, 0), p(5, 0, 0), p(6, 1, 0));
        PathSpline spline = PathSpline.of(path.point(0), path);
        double diveStart = edgeStart(spline, 1), diveEnd = edgeStart(spline, 2);
        assertEquals(4, diveEnd - diveStart, 0.05);
        for (double s = 0; s <= spline.length(); s += 0.01) {
            assertEquals(s >= diveStart && s < diveEnd, spline.isDive(s), "at " + s);
        }
    }
}
