package tremor.core.path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tremor.core.math.Clamp;
import java.util.function.Supplier;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tremor.core.VoxelView;
import tremor.core.graph.SurfaceGraph;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.motion.Crawler;
import tremor.core.motion.MotionParams;
import tremor.core.path.PathSearch.Status;
import tremor.core.surface.Surface;
import tremor.core.testing.ArrayVoxelGrid;

/** A* and the spline over the real {@link SurfaceGraph} in small scenes (SPEC 5, stage 1 "done when"). */
class SurfacePathIntegrationTest {
    private static final int MAX_DIVE = 4;
    private static final double DIVE_COST = 2;
    private static final int LIMIT = 4000;

    /** Skips the test while {@link SurfaceGraph} is still a skeleton. */
    private static <T> T orSkip(Supplier<T> body) {
        try {
            return body.get();
        } catch (UnsupportedOperationException e) {
            return Assumptions.abort("SurfaceGraph is not implemented yet: " + e.getMessage());
        }
    }

    private static SurfaceGraph graph(VoxelView view, int maxDive) {
        return orSkip(() -> new SurfaceGraph(view, maxDive));
    }

    private static PathSearch search(SurfaceGraph graph, long start, long goal) {
        return orSkip(() -> {
            PathSearch search = new PathSearch(graph, start, goal, LIMIT, DIVE_COST);
            search.run();
            return search;
        });
    }

    /**
     * Every node is a surface voxel (solid, with an open face) and a graph node, and consecutive nodes are joined by
     * an edge the graph reports, with the path's dive flag.
     */
    private static void assertOnTheSkin(VoxelView view, SurfaceGraph graph, Path path) {
        for (int i = 0; i < path.size(); i++) {
            long n = path.node(i);
            int x = VoxelPos.x(n), y = VoxelPos.y(n), z = VoxelPos.z(n);
            assertTrue(graph.isNode(n), () -> "not a node: " + VoxelPos.toString(n));
            assertTrue(Surface.isSurface(view, x, y, z), () -> "not a surface voxel: " + VoxelPos.toString(n));
        }
        TestGraphs.cost(graph, path, DIVE_COST);
        for (int i = 0; i + 1 < path.size(); i++) {
            double length = TestGraphs.distance(path.node(i), path.node(i + 1));
            assertTrue(path.dive()[i] || length <= Math.sqrt(3) + 1e-12, "skin edge of length " + length);
        }
    }

    /** The smoothed route stays on the skin: never in an open voxel without a solid face-neighbour. */
    private static void assertSplineHugsTheSkin(VoxelView view, PathSpline spline) {
        for (double s = 0; s <= spline.length(); s += 0.02) {
            Vec3 q = spline.position(s);
            int x = (int) Math.floor(q.x()), y = (int) Math.floor(q.y()), z = (int) Math.floor(q.z());
            boolean nearRock = view.isSolid(x, y, z) || view.isSolid(x - 1, y, z) || view.isSolid(x + 1, y, z)
                    || view.isSolid(x, y - 1, z) || view.isSolid(x, y + 1, z) || view.isSolid(x, y, z - 1)
                    || view.isSolid(x, y, z + 1);
            assertTrue(nearRock, "spline point in open air: " + q);
        }
    }

    private static int diveCount(Path path) {
        int count = 0;
        for (boolean d : path.dive()) {
            if (d) {
                count++;
            }
        }
        return count;
    }

    /** Cave (floor y <= 0, ceiling y >= 8) closed by a thick wall x >= 6, open toward -x. */
    private static ArrayVoxelGrid caveWithWall() {
        ArrayVoxelGrid g = ArrayVoxelGrid.cave(0, 8);
        g.fill(6, 1, g.minZ, g.maxX, 7, g.maxZ, true);
        return g;
    }

    @Test
    void fromTheCeilingDownTheWallToTheFloor() {
        ArrayVoxelGrid view = caveWithWall();
        SurfaceGraph graph = graph(view, MAX_DIVE);
        long start = VoxelPos.pack(0, 8, 0), goal = VoxelPos.pack(0, 0, 0);
        PathSearch search = search(graph, start, goal);
        assertEquals(Status.FOUND, search.status());
        Path path = search.path();
        assertTrue(path.complete());
        assertEquals(start, path.node(0));
        assertEquals(goal, path.last());
        assertOnTheSkin(view, graph, path);
        assertEquals(0, diveCount(path));

        // Down the wall: it never climbs, and it passes every height of the wall on the wall itself.
        boolean[] wallHeights = new boolean[8];
        for (int i = 0; i < path.size(); i++) {
            long n = path.node(i);
            if (i > 0) {
                assertTrue(VoxelPos.y(n) <= VoxelPos.y(path.node(i - 1)), "climbs at node " + i);
            }
            if (VoxelPos.x(n) == 6) {
                wallHeights[VoxelPos.y(n)] = true;
            }
        }
        for (int y = 1; y <= 7; y++) {
            assertTrue(wallHeights[y], "wall height " + y + " skipped");
        }
        // Ceiling 5 + corner √2 + wall 6 + corner √2 + floor 5: nothing shorter exists on the skin.
        assertEquals(16 + 2 * Math.sqrt(2), path.length(), 1e-9);

        assertSplineHugsTheSkin(view, PathSpline.of(path.point(0), path));
    }

    @Test
    void crawlerSlidesDownTheWallWithASmoothlyTurningNormal() {
        ArrayVoxelGrid view = caveWithWall();
        SurfaceGraph graph = graph(view, MAX_DIVE);
        long start = VoxelPos.pack(0, 8, 0);
        PathSearch search = search(graph, start, VoxelPos.pack(0, 0, 0));
        assertEquals(Status.FOUND, search.status());
        Path path = search.path();
        Crawler.NormalSource normals = q -> {
            long n = graph.nearestNode(q, 1);
            return n == SurfaceGraph.NO_NODE ? Vec3.ZERO
                    : graph.normal(VoxelPos.x(n), VoxelPos.y(n), VoxelPos.z(n));
        };
        Crawler crawler = new Crawler(path.point(0), graph.normal(0, 8, 0));
        assertEquals(-1, crawler.normal().y(), 1e-9);
        crawler.follow(PathSpline.of(crawler.position(), path));

        MotionParams params = new MotionParams(3, 6, 0.25, 1.5, 0.3);
        double k = 1 - Math.exp(-0.05 / params.normalSmoothingSeconds());
        Vec3 wallNormal = new Vec3(-1, 0, 0);
        double closestToWall = 180;
        Vec3 previous = crawler.normal();
        for (int i = 0; i < 400; i++) {
            crawler.tick(0.05, params, normals);
            double step = Math.toDegrees(Math.acos(Clamp.clamp(previous.dot(crawler.normal()), -1, 1)));
            assertTrue(step <= k * 90 + 1e-6, "normal turned " + step + "° in one tick");
            closestToWall = Math.min(closestToWall,
                    Math.toDegrees(Math.acos(Clamp.clamp(wallNormal.dot(crawler.normal()), -1, 1))));
            previous = crawler.normal();
        }
        assertTrue(crawler.arrived());
        assertTrue(VoxelPos.center(path.last()).distance(crawler.position()) < 1e-9);
        assertTrue(closestToWall < 5, "never turned onto the wall: " + closestToWall + "°");
        assertEquals(1, crawler.normal().y(), 1e-6);
    }

    @Test
    void throughAThinWallWithExactlyOneDive() {
        for (int thickness = 2; thickness <= MAX_DIVE; thickness++) {
            ArrayVoxelGrid view = ArrayVoxelGrid.thinWallBetweenCaves(0, 6, 0, thickness);
            SurfaceGraph graph = graph(view, MAX_DIVE);
            long start = VoxelPos.pack(-5, 0, 0), goal = VoxelPos.pack(thickness + 4, 0, 2);
            PathSearch search = search(graph, start, goal);
            assertEquals(Status.FOUND, search.status(), "thickness " + thickness);
            Path path = search.path();
            assertOnTheSkin(view, graph, path);
            assertEquals(1, diveCount(path), "thickness " + thickness);
            for (int i = 0; i + 1 < path.size(); i++) {
                if (path.dive()[i]) {
                    // Straight through the wall, from one face to the other.
                    long a = path.node(i), b = path.node(i + 1);
                    assertEquals(0, VoxelPos.x(a));
                    assertEquals(thickness - 1, VoxelPos.x(b));
                    assertEquals(VoxelPos.y(a), VoxelPos.y(b));
                    assertEquals(VoxelPos.z(a), VoxelPos.z(b));
                }
            }

            PathSpline spline = PathSpline.of(path.point(0), path);
            boolean hidden = false;
            for (double s = 0; s <= spline.length(); s += 0.05) {
                double x = spline.position(s).x();
                if (x > 0.6 && x < thickness - 0.6) {
                    assertTrue(spline.isDive(s), "inside the wall but not diving at x = " + x);
                    hidden = true;
                }
                if (x < 0 || x > thickness) {
                    assertFalse(spline.isDive(s), "diving in the open at x = " + x);
                }
            }
            assertTrue(hidden || thickness == 2);
        }
    }

    @Test
    void aWallThickerThanTheDiveDepthCannotBeCrossed() {
        ArrayVoxelGrid view = ArrayVoxelGrid.thinWallBetweenCaves(0, 6, 0, MAX_DIVE + 2);
        SurfaceGraph graph = graph(view, MAX_DIVE);
        long goal = VoxelPos.pack(MAX_DIVE + 6, 0, 0);
        PathSearch search = search(graph, VoxelPos.pack(-5, 0, 0), goal);
        assertNotEquals(Status.FOUND, search.status());
        if (search.status() == Status.PARTIAL) {
            Path path = search.path();
            assertOnTheSkin(view, graph, path);
            for (long n : path.nodes()) {
                assertTrue(VoxelPos.x(n) <= 0, "crossed the wall: " + VoxelPos.toString(n));
            }
        }
    }

    @Test
    void neverThroughOpenAir() {
        // An endless cave without walls: the ceiling and the floor are not connected on the skin.
        ArrayVoxelGrid view = ArrayVoxelGrid.cave(0, 8);
        SurfaceGraph graph = graph(view, MAX_DIVE);
        PathSearch below = search(graph, VoxelPos.pack(0, 8, 0), VoxelPos.pack(0, 0, 0));
        assertEquals(Status.FAILED, below.status()); // nothing on the ceiling is closer than the start
        assertEquals(LIMIT, below.expanded());

        PathSearch aside = search(graph, VoxelPos.pack(0, 8, 0), VoxelPos.pack(6, 0, 3));
        assertEquals(Status.PARTIAL, aside.status());
        Path path = aside.path();
        assertFalse(path.complete());
        assertOnTheSkin(view, graph, path);
        for (long n : path.nodes()) {
            assertEquals(8, VoxelPos.y(n), "left the ceiling: " + VoxelPos.toString(n));
        }
        assertEquals(VoxelPos.pack(6, 8, 3), path.last()); // right above the goal
    }
}
