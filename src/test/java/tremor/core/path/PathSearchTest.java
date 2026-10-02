package tremor.core.path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tremor.core.path.TestGraphs.p;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tremor.core.graph.Graph;
import tremor.core.math.VoxelPos;
import tremor.core.path.PathSearch.Status;
import tremor.core.path.TestGraphs.GridGraph;
import tremor.core.path.TestGraphs.MapGraph;
import tremor.core.path.TestGraphs.Recording;

class PathSearchTest {
    private static final double EPS = 1e-9;

    private static void assertSamePath(Path expected, Path actual) {
        assertArrayEquals(expected.nodes(), actual.nodes());
        assertArrayEquals(expected.dive(), actual.dive());
        assertEquals(expected.complete(), actual.complete());
    }

    private static long distanceSquared(long a, long b) {
        long dx = VoxelPos.x(a) - VoxelPos.x(b), dy = VoxelPos.y(a) - VoxelPos.y(b), dz = VoxelPos.z(a) - VoxelPos.z(b);
        return dx * dx + dy * dy + dz * dz;
    }

    /** Line of nodes {@code (x0..x1, 0, z)} joined by skin edges. */
    private static MapGraph line(MapGraph g, int x0, int x1, int z) {
        g.node(p(x0, 0, z));
        for (int x = x0; x < x1; x++) {
            g.edge(p(x, 0, z), p(x + 1, 0, z), false);
        }
        return g;
    }

    @Test
    void rejectsBadArguments() {
        MapGraph g = line(new MapGraph(), 0, 3, 0);
        assertThrows(IllegalArgumentException.class, () -> new PathSearch(g, p(0, 0, 0), p(3, 0, 0), 0, 2));
        assertThrows(IllegalArgumentException.class, () -> new PathSearch(g, p(0, 0, 0), p(3, 0, 0), 10, 0.99));
        assertThrows(IllegalArgumentException.class, () -> new PathSearch(g, p(0, 0, 0), p(3, 0, 0), 10, Double.NaN));
    }

    @Test
    void startNotANodeFails() {
        MapGraph g = line(new MapGraph(), 0, 3, 0);
        PathSearch search = new PathSearch(g, p(0, 1, 0), p(3, 0, 0), 100, 2);
        assertEquals(Status.FAILED, search.status());
        assertEquals(Status.FAILED, search.step(10));
        assertEquals(Status.FAILED, search.run());
        assertEquals(0, search.expanded());
        assertThrows(IllegalStateException.class, search::path);
    }

    @Test
    void pathIsUnavailableWhileRunning() {
        PathSearch search = new PathSearch(line(new MapGraph(), 0, 10, 0), p(0, 0, 0), p(10, 0, 0), 100, 2);
        assertEquals(Status.RUNNING, search.status());
        assertEquals(Status.RUNNING, search.step(2));
        assertThrows(IllegalStateException.class, search::path);
        assertEquals(p(0, 0, 0), search.start());
        assertEquals(p(10, 0, 0), search.goal());
    }

    @Test
    void startIsGoal() {
        PathSearch search = new PathSearch(line(new MapGraph(), 0, 3, 0), p(2, 0, 0), p(2, 0, 0), 1, 1);
        assertEquals(Status.FOUND, search.run());
        assertEquals(1, search.expanded());
        Path path = search.path();
        assertArrayEquals(new long[]{p(2, 0, 0)}, path.nodes());
        assertEquals(0, path.dive().length);
        assertTrue(path.complete());
    }

    @Test
    void straightLineExpandsOnlyTheLine() {
        // The heuristic is exact on a line: A* walks straight to the goal.
        PathSearch search = new PathSearch(line(new MapGraph(), -5, 20, 0), p(0, 0, 0), p(12, 0, 0), 100, 2);
        assertEquals(Status.FOUND, search.run());
        assertEquals(13, search.expanded());
        Path path = search.path();
        assertEquals(13, path.size());
        for (int i = 0; i < 13; i++) {
            assertEquals(p(i, 0, 0), path.node(i));
        }
        assertEquals(12, path.length(), EPS);
        assertSame(path, search.path());
    }

    @Test
    void optimalOnAnOpenGridPlane() {
        // 8-connected plane: the cheapest cost from (0,0) to (5,3) is 3·√2 + 2.
        GridGraph g = GridGraph.plane(Set.of());
        PathSearch search = new PathSearch(g, p(0, 0, 0), p(5, 0, 3), 4000, 2);
        assertEquals(Status.FOUND, search.run());
        assertEquals(3 * Math.sqrt(2) + 2, TestGraphs.cost(g, search.path(), 2), EPS);
        assertEquals(p(0, 0, 0), search.path().node(0));
        assertEquals(p(5, 0, 3), search.path().last());
    }

    @Test
    void goesAroundAWall() {
        // Wall x = 3, z in [-5, 5] on the plane: the detour around its end is the only way.
        Set<Long> blocked = new HashSet<>();
        for (int z = -5; z <= 5; z++) {
            blocked.add(p(3, 0, z));
        }
        GridGraph g = GridGraph.plane(blocked);
        PathSearch search = new PathSearch(g, p(0, 0, 0), p(6, 0, 0), 4000, 2);
        assertEquals(Status.FOUND, search.run());
        Path path = search.path();
        for (long n : path.nodes()) {
            assertFalse(blocked.contains(n));
        }
        // Compare with Dijkstra over the same plane, made explicit in a box big enough for the detour.
        MapGraph explicit = new MapGraph();
        for (int x = -12; x <= 12; x++) {
            for (int z = -12; z <= 12; z++) {
                long a = p(x, 0, z);
                if (blocked.contains(a)) {
                    continue;
                }
                explicit.node(a);
                int[][] forward = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};
                for (int[] d : forward) {
                    long b = p(x + d[0], 0, z + d[1]);
                    if (Math.abs(x + d[0]) <= 12 && Math.abs(z + d[1]) <= 12 && !blocked.contains(b)) {
                        explicit.edge(a, b, false);
                    }
                }
            }
        }
        double optimum = TestGraphs.dijkstra(explicit, p(0, 0, 0), 2).get(p(6, 0, 0));
        assertEquals(optimum, TestGraphs.cost(g, path, 2), EPS);
    }

    @Test
    void optimalOnRandomGraphsAgainstDijkstra() {
        Random random = new Random(12345);
        for (int trial = 0; trial < 60; trial++) {
            MapGraph g = randomGraph(random, 40 + random.nextInt(40), 3);
            List<Long> nodes = new ArrayList<>(g.nodes());
            double factor = trial % 3 == 0 ? 1 : 1 + 3 * random.nextDouble();
            long start = nodes.get(random.nextInt(nodes.size()));
            Map<Long, Double> best = TestGraphs.dijkstra(g, start, factor);
            for (int k = 0; k < 8; k++) {
                long goal = nodes.get(random.nextInt(nodes.size()));
                PathSearch search = new PathSearch(g, start, goal, 100_000, factor);
                Status status = search.run();
                Double optimum = best.get(goal);
                if (optimum == null) {
                    assertTrue(status == Status.PARTIAL || status == Status.FAILED, "unreachable goal: " + status);
                    continue;
                }
                assertEquals(Status.FOUND, status);
                Path path = search.path();
                assertTrue(path.complete());
                assertEquals(start, path.node(0));
                assertEquals(goal, path.last());
                assertEquals(optimum, TestGraphs.cost(g, path, factor), 1e-9, "trial " + trial);
            }
        }
    }

    /**
     * Random graph over distinct voxels in a small box: each node gets a few edges to random other nodes, with
     * lengths = distance between the centres (as the contract requires) and random dive flags.
     */
    private static MapGraph randomGraph(Random random, int nodeCount, int edgesPerNode) {
        MapGraph g = new MapGraph();
        List<Long> nodes = new ArrayList<>();
        Set<Long> used = new HashSet<>();
        while (nodes.size() < nodeCount) {
            long n = p(random.nextInt(12) - 6, random.nextInt(6) - 3, random.nextInt(12) - 6);
            if (used.add(n)) {
                nodes.add(n);
                g.node(n);
            }
        }
        for (long a : nodes) {
            for (int e = 0; e < edgesPerNode; e++) {
                long b = nodes.get(random.nextInt(nodes.size()));
                if (b != a && random.nextInt(3) > 0) {
                    g.edge(a, b, random.nextInt(4) == 0);
                }
            }
        }
        return g;
    }

    @Test
    void diveCostFactorChangesTheRoute() {
        // A 2-long dive straight through, or a 6-long skin detour around it.
        long a = p(0, 0, 0), b = p(2, 0, 0);
        MapGraph g = new MapGraph()
                .edge(a, b, true)
                .edge(a, p(0, 0, 1), false).edge(p(0, 0, 1), p(0, 0, 2), false).edge(p(0, 0, 2), p(1, 0, 2), false)
                .edge(p(1, 0, 2), p(2, 0, 2), false).edge(p(2, 0, 2), p(2, 0, 1), false).edge(p(2, 0, 1), b, false);

        PathSearch cheapDive = new PathSearch(g, a, b, 100, 2);
        assertEquals(Status.FOUND, cheapDive.run());
        assertArrayEquals(new long[]{a, b}, cheapDive.path().nodes());
        assertArrayEquals(new boolean[]{true}, cheapDive.path().dive());

        PathSearch dearDive = new PathSearch(g, a, b, 100, 3.5);
        assertEquals(Status.FOUND, dearDive.run());
        assertEquals(7, dearDive.path().size());
        assertArrayEquals(new boolean[6], dearDive.path().dive());
        assertEquals(6, dearDive.path().length(), EPS);
    }

    @Test
    void nodeLimitGivesAPartialPathToTheClosestExpandedNode() {
        // Plane with a long wall across the direct line: the search runs out of nodes before getting around it.
        Set<Long> blocked = new HashSet<>();
        for (int z = -40; z <= 40; z++) {
            blocked.add(p(5, 0, z));
        }
        Recording g = new Recording(GridGraph.plane(blocked));
        long start = p(0, 0, 0), goal = p(10, 0, 0);
        PathSearch search = new PathSearch(g, start, goal, 50, 2);
        assertEquals(Status.PARTIAL, search.run());
        assertEquals(50, search.expanded());
        assertEquals(50, g.expanded.size());

        Path path = search.path();
        assertFalse(path.complete());
        assertEquals(start, path.node(0));
        long last = path.last();
        assertTrue(g.expanded.contains(last));
        for (long n : g.expanded) {
            assertTrue(distanceSquared(last, goal) <= distanceSquared(n, goal));
        }
        assertEquals(p(4, 0, 0), last);
        assertEquals(4, TestGraphs.cost(g.graph, path, 2), EPS); // the straight line
    }

    @Test
    void exhaustedComponentGivesAPartialPathToItsClosestNode() {
        // Start component: line x 0..5 at z 0 plus a spur; goal on a separate line far away.
        MapGraph g = line(new MapGraph(), 0, 5, 0);
        g.edge(p(5, 0, 0), p(5, 0, 1), false);
        line(g, 10, 15, 0);
        Recording rec = new Recording(g);
        PathSearch search = new PathSearch(rec, p(0, 0, 0), p(12, 0, 0), 1000, 2);
        assertEquals(Status.PARTIAL, search.run());
        assertEquals(7, search.expanded()); // the whole start component
        Path path = search.path();
        assertFalse(path.complete());
        assertEquals(p(5, 0, 0), path.last());
        assertEquals(6, path.size());
    }

    @Test
    void goalThatIsNotANodeIsApproached() {
        MapGraph g = line(new MapGraph(), 0, 5, 0);
        PathSearch search = new PathSearch(g, p(0, 0, 0), p(5, 3, 0), 1000, 2);
        assertEquals(Status.PARTIAL, search.run());
        assertEquals(p(5, 0, 0), search.path().last());
    }

    @Test
    void failsWhenNothingIsCloserThanTheStart() {
        // The start is the end of its line nearest to the (unreachable) goal.
        MapGraph g = line(new MapGraph(), 0, 5, 0);
        line(g, 10, 15, 0);
        PathSearch search = new PathSearch(g, p(5, 0, 0), p(12, 0, 0), 1000, 2);
        assertEquals(Status.FAILED, search.run());
        assertEquals(6, search.expanded());
        assertThrows(IllegalStateException.class, search::path);

        // Same with the node limit: an isolated start with no edges at all.
        MapGraph single = new MapGraph().node(p(0, 0, 0));
        PathSearch lonely = new PathSearch(single, p(0, 0, 0), p(3, 0, 0), 1, 2);
        assertEquals(Status.FAILED, lonely.step(1));
        assertEquals(1, lonely.expanded());
    }

    @Test
    void stepExpandsWithinBudgetAndAtLeastOne() {
        GridGraph g = GridGraph.plane(Set.of());
        PathSearch search = new PathSearch(g, p(0, 0, 0), p(30, 0, 17), 4000, 2);
        assertEquals(Status.RUNNING, search.step(0));
        assertEquals(1, search.expanded());
        assertEquals(Status.RUNNING, search.step(-5));
        assertEquals(2, search.expanded());
        assertEquals(Status.RUNNING, search.step(10));
        assertEquals(12, search.expanded());
        Status status = search.run();
        assertEquals(Status.FOUND, status);
        int expanded = search.expanded();
        assertEquals(Status.FOUND, search.step(10));
        assertEquals(expanded, search.expanded());
    }

    @Test
    void incrementalStepsGiveTheSameResultAsRun() {
        Random random = new Random(777);
        int[] budgets = {1, 3, 17, 250};
        for (int trial = 0; trial < 40; trial++) {
            Graph g;
            long start, goal;
            int limit;
            if (trial % 2 == 0) {
                MapGraph mg = randomGraph(random, 60, 3);
                List<Long> nodes = new ArrayList<>(mg.nodes());
                g = mg;
                start = nodes.get(random.nextInt(nodes.size()));
                goal = nodes.get(random.nextInt(nodes.size()));
                limit = 5 + random.nextInt(60);
            } else {
                Set<Long> blocked = new HashSet<>();
                for (int i = 0; i < 120; i++) {
                    blocked.add(p(random.nextInt(30) - 15, 0, random.nextInt(30) - 15));
                }
                g = GridGraph.plane(blocked);
                start = p(-16, 0, -16);
                goal = p(random.nextInt(30) - 15, 0, random.nextInt(30) - 15);
                limit = 50 + random.nextInt(400);
            }
            double factor = 1 + random.nextDouble() * 3;
            PathSearch reference = new PathSearch(g, start, goal, limit, factor);
            Status expected = reference.run();
            for (int budget : budgets) {
                PathSearch search = new PathSearch(g, start, goal, limit, factor);
                int calls = 0;
                Status status;
                do {
                    int before = search.expanded();
                    status = search.step(budget);
                    assertTrue(search.expanded() - before <= budget);
                    assertTrue(search.expanded() > before);
                    calls++;
                } while (status == Status.RUNNING);
                assertEquals(expected, status, "trial " + trial);
                assertEquals(reference.expanded(), search.expanded());
                assertTrue(calls >= Math.ceilDiv(reference.expanded(), budget));
                if (expected != Status.FAILED) {
                    assertSamePath(reference.path(), search.path());
                }
            }
        }
    }

    @Test
    void deterministicTieBreaks() {
        // An open 3D grid has many equally cheap routes; the choice must not depend on anything but the input.
        GridGraph g = new GridGraph(n -> Math.abs(VoxelPos.y(n)) <= 3, false);
        Path first = null;
        for (int i = 0; i < 3; i++) {
            PathSearch search = new PathSearch(g, p(0, 0, 0), p(9, 3, -6), 4000, 1);
            assertEquals(Status.FOUND, search.run());
            if (first == null) {
                first = search.path();
            } else {
                assertSamePath(first, search.path());
            }
        }
    }

    @Test
    void largeSearchStopsAtTheLimit() {
        // Unbounded skin (two parallel planes) with an unreachable goal: exactly maxExpansions nodes are expanded.
        GridGraph g = new GridGraph(n -> VoxelPos.y(n) == 0 || VoxelPos.y(n) == 10, true);
        long goal = p(0, 10, 0);
        PathSearch search = new PathSearch(g, p(0, 0, 0), goal, 4000, 2);
        while (search.step(500) == Status.RUNNING) {
            assertTrue(search.expanded() % 500 == 0);
        }
        assertEquals(Status.FAILED, search.status()); // nothing on the floor is closer than right below the goal
        assertEquals(4000, search.expanded());

        PathSearch offset = new PathSearch(g, p(-200, 0, 0), goal, 4000, 2);
        assertEquals(Status.PARTIAL, offset.run());
        assertEquals(4000, offset.expanded());
        Path path = offset.path();
        assertEquals(0, VoxelPos.y(path.last()));
        assertTrue(VoxelPos.x(path.last()) > -200);
        TestGraphs.cost(g, path, 2);
    }
}
