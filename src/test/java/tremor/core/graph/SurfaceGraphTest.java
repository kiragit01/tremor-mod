package tremor.core.graph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tremor.core.VoxelView;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.surface.Surface;
import tremor.core.surface.SurfaceNormals;
import tremor.core.testing.ArrayVoxelGrid;
import tremor.core.voxel.VoxelCache;

class SurfaceGraphTest {
    private static final double SQRT2 = Math.sqrt(2), SQRT3 = Math.sqrt(3);

    record Edge(long to, double length, boolean dive) {
        @Override
        public String toString() {
            return "->" + VoxelPos.toString(to) + (dive ? " dive " : " ") + length;
        }
    }

    private static long p(int x, int y, int z) {
        return VoxelPos.pack(x, y, z);
    }

    private static Edge skin(int x, int y, int z, double length) {
        return new Edge(p(x, y, z), length, false);
    }

    private static Edge dive(int x, int y, int z, int length) {
        return new Edge(p(x, y, z), length, true);
    }

    private static List<Edge> edges(Graph graph, long node) {
        List<Edge> list = new ArrayList<>();
        graph.forEachEdge(node, (to, length, dive) -> list.add(new Edge(to, length, dive)));
        return list;
    }

    private static List<Edge> edges(Graph graph, int x, int y, int z) {
        return edges(graph, p(x, y, z));
    }

    /** A grid seen through a view that knows nothing about some voxels (they read as solid). */
    private static final class MaskedView implements VoxelView {
        final ArrayVoxelGrid grid;
        final Set<Long> unknown = new HashSet<>();

        MaskedView(ArrayVoxelGrid grid) {
            this.grid = grid;
        }

        MaskedView unknown(int x, int y, int z) {
            unknown.add(p(x, y, z));
            return this;
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return grid.isSolid(x, y, z) || unknown.contains(p(x, y, z));
        }

        @Override
        public boolean isKnown(int x, int y, int z) {
            return !unknown.contains(p(x, y, z));
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

    /** Straightforward transcription of the rules in the SurfaceGraph Javadoc, without any memo or local tricks. */
    private static final class Reference {
        static final int[][] FACES = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
        final VoxelView view;
        final int maxDiveDepth;

        Reference(VoxelView view, int maxDiveDepth) {
            this.view = view;
            this.maxDiveDepth = maxDiveDepth;
        }

        boolean node(int x, int y, int z) {
            return Surface.isSurface(view, x, y, z);
        }

        boolean continuous(int ax, int ay, int az, int bx, int by, int bz) {
            for (int[] f : FACES) {
                int ux = ax + f[0], uy = ay + f[1], uz = az + f[2];
                if (!view.isOpen(ux, uy, uz)) {
                    continue;
                }
                for (int[] g : FACES) {
                    int vx = bx + g[0], vy = by + g[1], vz = bz + g[2];
                    if (view.isOpen(vx, vy, vz) && Math.abs(ux - vx) <= 1 && Math.abs(uy - vy) <= 1
                            && Math.abs(uz - vz) <= 1) {
                        return true;
                    }
                }
            }
            return false;
        }

        boolean skin(int ax, int ay, int az, int bx, int by, int bz) {
            int dx = bx - ax, dy = by - ay, dz = bz - az;
            if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != 1) {
                return false;
            }
            if (!node(ax, ay, az) || !node(bx, by, bz)) {
                return false;
            }
            int m = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
            if (m == 3) {
                // Two-step paths through any of the 6 voxels between a and b (face-mates of a or of b).
                for (int mask = 1; mask < 7; mask++) {
                    int cx = ax + ((mask & 1) != 0 ? dx : 0);
                    int cy = ay + ((mask & 2) != 0 ? dy : 0);
                    int cz = az + ((mask & 4) != 0 ? dz : 0);
                    if (node(cx, cy, cz) && skin(ax, ay, az, cx, cy, cz) && skin(cx, cy, cz, bx, by, bz)) {
                        return true;
                    }
                }
                return false;
            }
            if (!continuous(ax, ay, az, bx, by, bz)) {
                return false;
            }
            if (m == 2) {
                boolean c1, c2;
                if (dx == 0) {
                    c1 = view.isSolid(ax, ay + dy, az);
                    c2 = view.isSolid(ax, ay, az + dz);
                } else if (dy == 0) {
                    c1 = view.isSolid(ax + dx, ay, az);
                    c2 = view.isSolid(ax, ay, az + dz);
                } else {
                    c1 = view.isSolid(ax + dx, ay, az);
                    c2 = view.isSolid(ax, ay + dy, az);
                }
                return c1 || c2;
            }
            return true;
        }

        boolean dive(int ax, int ay, int az, int[] dir, int k) {
            int bx = ax + k * dir[0], by = ay + k * dir[1], bz = az + k * dir[2];
            if (k < 1 || k > maxDiveDepth || !node(ax, ay, az) || !node(bx, by, bz)) {
                return false;
            }
            if (!view.isOpen(ax - dir[0], ay - dir[1], az - dir[2])
                    || !view.isOpen(bx + dir[0], by + dir[1], bz + dir[2])) {
                return false;
            }
            for (int j = 1; j < k; j++) {
                int x = ax + j * dir[0], y = ay + j * dir[1], z = az + j * dir[2];
                if (!view.isSolid(x, y, z) || !view.isKnown(x, y, z)) {
                    return false;
                }
            }
            return !skin(ax, ay, az, bx, by, bz);
        }

        Set<Edge> edges(int x, int y, int z) {
            Set<Edge> set = new HashSet<>();
            if (!node(x, y, z)) {
                return set;
            }
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (skin(x, y, z, x + dx, y + dy, z + dz)) {
                            set.add(new Edge(p(x + dx, y + dy, z + dz), Math.sqrt(dx * dx + dy * dy + dz * dz), false));
                        }
                    }
                }
            }
            for (int[] dir : FACES) {
                for (int k = 1; k <= maxDiveDepth; k++) {
                    if (dive(x, y, z, dir, k)) {
                        set.add(new Edge(p(x + k * dir[0], y + k * dir[1], z + k * dir[2]), k, true));
                    }
                }
            }
            return set;
        }
    }

    /** Random rock with boxes of solid and air in it, so it has thick walls, slabs and thin features. */
    private static ArrayVoxelGrid randomGrid(Random random, int size) {
        int x0 = random.nextInt(20) - 10, y0 = random.nextInt(20) - 10, z0 = random.nextInt(20) - 10;
        ArrayVoxelGrid g = new ArrayVoxelGrid(x0, y0, z0, x0 + size - 1, y0 + size - 1, z0 + size - 1,
                ArrayVoxelGrid.Outside.values()[random.nextInt(3)]);
        double density = 0.25 + 0.5 * random.nextDouble();
        for (int y = g.minY; y <= g.maxY; y++) {
            for (int z = g.minZ; z <= g.maxZ; z++) {
                for (int x = g.minX; x <= g.maxX; x++) {
                    g.set(x, y, z, random.nextDouble() < density);
                }
            }
        }
        int boxes = random.nextInt(6);
        for (int i = 0; i < boxes; i++) {
            int ax = x0 + random.nextInt(size), ay = y0 + random.nextInt(size), az = z0 + random.nextInt(size);
            g.fill(ax, ay, az, ax + random.nextInt(7) - 3, ay + random.nextInt(7) - 3, az + random.nextInt(7) - 3,
                    random.nextBoolean());
        }
        return g;
    }

    private static MaskedView randomUnknowns(Random random, ArrayVoxelGrid g) {
        MaskedView view = new MaskedView(g);
        if (random.nextBoolean()) {
            int count = random.nextInt(30);
            for (int i = 0; i < count; i++) {
                view.unknown(g.minX + random.nextInt(g.maxX - g.minX + 1), g.minY + random.nextInt(g.maxY - g.minY + 1),
                        g.minZ + random.nextInt(g.maxZ - g.minZ + 1));
            }
        }
        return view;
    }

    // ---- scenes ----------------------------------------------------------------------------------------------------

    @Test
    void floorJoinsFaceAndDiagonalNeighboursInAFixedOrder() {
        SurfaceGraph graph = new SurfaceGraph(ArrayVoxelGrid.flatFloor(0), 4);
        assertTrue(graph.isNode(0, 0, 0));
        assertTrue(graph.isNode(p(0, 0, 0)));
        assertEquals(List.of(
                skin(-1, 0, -1, SQRT2), skin(0, 0, -1, 1), skin(1, 0, -1, SQRT2),
                skin(-1, 0, 0, 1), skin(1, 0, 0, 1),
                skin(-1, 0, 1, SQRT2), skin(0, 0, 1, 1), skin(1, 0, 1, SQRT2)), edges(graph, 0, 0, 0));
        // Buried rock and air are not nodes and have no edges.
        assertFalse(graph.isNode(0, -1, 0));
        assertFalse(graph.isNode(0, 1, 0));
        assertEquals(List.of(), edges(graph, 0, -1, 0));
        assertEquals(List.of(), edges(graph, 0, 1, 0));
    }

    @Test
    void concaveCornerJoinsFloorToWall() {
        SurfaceGraph graph = new SurfaceGraph(ArrayVoxelGrid.floorWallCorner(0, 0), 4);
        assertFalse(graph.isNode(0, 0, 0), "the voxel in the inner corner is buried");
        assertTrue(edges(graph, 1, 0, 0).contains(skin(0, 1, 0, SQRT2)));
        assertTrue(edges(graph, 0, 1, 0).contains(skin(1, 0, 0, SQRT2)));
        assertTrue(edges(graph, 0, 1, 0).contains(skin(0, 2, 0, 1)), "up the wall");
        assertTrue(edges(graph, 1, 0, 0).contains(skin(2, 0, 0, 1)), "along the floor");
        assertTrue(edges(graph, 1, 0, 0).stream().noneMatch(Edge::dive));
    }

    @Test
    void convexEdgesOverAStepAndACliff() {
        SurfaceGraph step = new SurfaceGraph(ArrayVoxelGrid.step(0, 0), 4);
        assertTrue(edges(step, 0, 0, 0).contains(skin(1, -1, 0, SQRT2)));
        assertTrue(edges(step, 1, -1, 0).contains(skin(0, 0, 0, SQRT2)));

        // Upper plateau for x <= 0 (top y = 0), lower floor for x > 0 (top y = -5): a 5 high cliff facing +x.
        ArrayVoxelGrid g = new ArrayVoxelGrid(-20, -16, -20, 20, 16, 20, ArrayVoxelGrid.Outside.EXTEND);
        g.fill(-20, -16, -20, 0, 0, 20, true);
        g.fill(1, -16, -20, 20, -5, 20, true);
        SurfaceGraph graph = new SurfaceGraph(g, 4);
        assertTrue(edges(graph, 0, 0, 0).contains(skin(0, -1, 0, 1)), "over the edge onto the cliff face");
        assertTrue(edges(graph, 0, -1, 0).contains(skin(0, 0, 0, 1)));
        for (int y = -1; y >= -3; y--) {
            assertTrue(edges(graph, 0, y, 0).contains(skin(0, y - 1, 0, 1)), "down the face at y=" + y);
        }
        assertTrue(edges(graph, 0, -4, 0).contains(skin(1, -5, 0, SQRT2)), "into the lower floor");
        assertFalse(graph.isNode(0, -5, 0), "foot of the cliff is buried");
        // Nothing jumps through the air from the plateau to the lower floor.
        for (int y = 0; y >= -4; y--) {
            for (Edge e : edges(graph, 0, y, 0)) {
                assertTrue(VoxelPos.x(e.to()) <= 0 || VoxelPos.y(e.to()) == -5 && y == -4,
                        "edge " + e + " from y=" + y);
            }
        }
    }

    @Test
    void aGapOfAirIsCrossedByALeapUpToTheSetLength() {
        // The ground (y <= 0) and a floating block two blocks over it (y = 3, gap y = 1..2).
        ArrayVoxelGrid g = new ArrayVoxelGrid(-6, -4, -6, 6, 8, 6, false);
        g.fill(-6, -4, -6, 6, 0, 6, true);
        g.set(0, 3, 0, true);
        SurfaceGraph graph = new SurfaceGraph(g, 4);
        assertFalse(edges(graph, 0, 0, 0).contains(dive(0, 3, 0, 3)), "no leaps before they are switched on");
        graph.setMaxLeap(1);
        assertFalse(edges(graph, 0, 0, 0).contains(dive(0, 3, 0, 3)), "a gap of 2 is too long for a leap of 1");
        graph.setMaxLeap(2);
        assertTrue(edges(graph, 0, 0, 0).contains(dive(0, 3, 0, 3)));
        assertTrue(edges(graph, 0, 3, 0).contains(dive(0, 0, 0, 3)), "leaps are symmetric");
        graph.setMaxLeap(5);
        for (Edge e : edges(graph, 1, 0, 1)) {
            assertTrue(!e.dive() || VoxelPos.y(e.to()) <= 0, "no leap off the column of the block: " + e);
        }
    }

    @Test
    void twoThickWallIsCrossedOnlyByADive() {
        ArrayVoxelGrid g = ArrayVoxelGrid.thinWallBetweenCaves(0, 8, 0, 2);
        SurfaceGraph graph = new SurfaceGraph(g, 4);
        for (int y = 1; y <= 7; y++) {
            for (int z = -2; z <= 2; z++) {
                assertTrue(graph.isNode(0, y, z) && graph.isNode(1, y, z));
                for (Edge e : edges(graph, 0, y, z)) {
                    assertTrue(VoxelPos.x(e.to()) <= 0 || e.dive(), "skin edge across the wall: " + e);
                }
                for (Edge e : edges(graph, 1, y, z)) {
                    assertTrue(VoxelPos.x(e.to()) >= 1 || e.dive(), "skin edge across the wall: " + e);
                }
                assertTrue(edges(graph, 0, y, z).contains(dive(1, y, z, 1)));
                assertTrue(edges(graph, 1, y, z).contains(dive(0, y, z, 1)));
            }
        }
        // The floor under the wall is buried, so the two caves do not touch on the skin.
        assertFalse(graph.isNode(0, 0, 0));
        assertFalse(graph.isNode(1, 0, 0));
    }

    @Test
    void divesThroughThickerWallsUpToTheMaximumDepth() {
        for (int thickness = 1; thickness <= 7; thickness++) {
            ArrayVoxelGrid g = ArrayVoxelGrid.thinWallBetweenCaves(0, 8, 0, thickness);
            for (int depth = 0; depth <= 6; depth++) {
                SurfaceGraph graph = new SurfaceGraph(g, depth);
                int far = thickness - 1;
                List<Edge> dives = edges(graph, 0, 4, 0).stream().filter(Edge::dive).toList();
                List<Edge> back = edges(graph, far, 4, 0).stream().filter(Edge::dive).toList();
                if (thickness >= 2 && far <= depth) {
                    assertEquals(List.of(dive(far, 4, 0, far)), dives, "thickness " + thickness + " depth " + depth);
                    assertEquals(List.of(dive(0, 4, 0, far)), back);
                } else {
                    assertEquals(List.of(), dives, "thickness " + thickness + " depth " + depth);
                    assertEquals(List.of(), back);
                }
            }
        }
    }

    @Test
    void slabOverACaveDivesDownByOne() {
        // Ground (top y = 0) two blocks thick over a cave: top and bottom layers are joined by a dive only.
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        g.fill(-10, -6, -10, 10, -2, 10, false);
        SurfaceGraph graph = new SurfaceGraph(g, 4);
        List<Edge> e = edges(graph, 0, 0, 0);
        assertTrue(e.contains(dive(0, -1, 0, 1)));
        assertTrue(e.stream().noneMatch(edge -> VoxelPos.y(edge.to()) < 0 && !edge.dive()));
        assertTrue(edges(graph, 0, -1, 0).contains(dive(0, 0, 0, 1)));
        // Down from the cave floor nothing surfaces within reach (the rock below is deep).
        assertTrue(edges(graph, 0, -7, 0).stream().noneMatch(Edge::dive));
    }

    @Test
    void neverDivesThroughOpenOrUnknownVoxels() {
        ArrayVoxelGrid g = ArrayVoxelGrid.thinWallBetweenCaves(0, 8, 0, 3);
        assertTrue(edges(new SurfaceGraph(g, 4), 0, 4, 0).contains(dive(2, 4, 0, 2)));

        // An unknown voxel inside the wall blocks that dive, but not the ones next to it.
        SurfaceGraph masked = new SurfaceGraph(new MaskedView(g).unknown(1, 4, 0), 4);
        assertTrue(edges(masked, 0, 4, 0).stream().noneMatch(Edge::dive));
        assertTrue(edges(masked, 2, 4, 0).stream().noneMatch(Edge::dive));
        assertTrue(edges(masked, 0, 5, 0).contains(dive(2, 5, 0, 2)));
        // An unknown far face is not a node, so nothing dives to it.
        SurfaceGraph unknownFar = new SurfaceGraph(new MaskedView(g).unknown(2, 4, 0), 4);
        assertFalse(unknownFar.isNode(2, 4, 0));
        assertTrue(edges(unknownFar, 0, 4, 0).stream().noneMatch(Edge::dive));

        // A pocket of air inside the wall: no dive through it (it is open space).
        ArrayVoxelGrid pocket = ArrayVoxelGrid.thinWallBetweenCaves(0, 8, 0, 3).set(1, 4, 0, false);
        SurfaceGraph graph = new SurfaceGraph(pocket, 4);
        assertTrue(edges(graph, 0, 4, 0).stream().noneMatch(Edge::dive));
        assertTrue(edges(graph, 2, 4, 0).stream().noneMatch(Edge::dive));

        // Floor to ceiling of a cave: open space all the way, never an edge.
        SurfaceGraph cave = new SurfaceGraph(ArrayVoxelGrid.cave(0, 3), 6);
        for (Edge e : edges(cave, 0, 0, 0)) {
            assertTrue(VoxelPos.y(e.to()) == 0 && !e.dive(), "floor edge " + e);
        }
        for (Edge e : edges(cave, 0, 3, 0)) {
            assertTrue(VoxelPos.y(e.to()) == 3 && !e.dive(), "ceiling edge " + e);
        }
    }

    @Test
    void noSkinEdgeBetweenTwoOpenCorners() {
        ArrayVoxelGrid g = new ArrayVoxelGrid(-4, -4, -4, 4, 4, 4, false);
        g.set(0, 0, 0, true).set(1, 1, 0, true);
        SurfaceGraph graph = new SurfaceGraph(g, 4);
        assertTrue(graph.isNode(0, 0, 0) && graph.isNode(1, 1, 0));
        assertEquals(List.of(), edges(graph, 0, 0, 0));
        assertEquals(List.of(), edges(graph, 1, 1, 0));
        // Filling one corner makes the move legal.
        g.set(1, 0, 0, true);
        graph = new SurfaceGraph(g, 4);
        assertTrue(edges(graph, 0, 0, 0).contains(skin(1, 1, 0, SQRT2)));
        assertTrue(edges(graph, 1, 1, 0).contains(skin(0, 0, 0, SQRT2)));

        // A 3D checkerboard has no edges at all: every move would pass between open voxels.
        ArrayVoxelGrid board = new ArrayVoxelGrid(-6, -6, -6, 6, 6, 6, false);
        for (int y = -6; y <= 6; y++) {
            for (int z = -6; z <= 6; z++) {
                for (int x = -6; x <= 6; x++) {
                    board.set(x, y, z, (x + y + z & 1) == 0);
                }
            }
        }
        SurfaceGraph boardGraph = new SurfaceGraph(board, 4);
        for (int y = -5; y <= 5; y++) {
            for (int z = -5; z <= 5; z++) {
                for (int x = -5; x <= 5; x++) {
                    assertEquals((x + y + z & 1) == 0, boardGraph.isNode(x, y, z));
                    assertEquals(List.of(), edges(boardGraph, x, y, z), x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void threeAxisDiagonalsOnlyShortcutTwoStepPaths() {
        // A block standing on a floor: floor (0,0,0) reaches its top corner (1,1,1) through (1,0,0) and (1,1,1)-(1,0,0)
        // is an edge move. Only a's face-mates lead there; the edge must still exist both ways.
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0).set(1, 1, 1, true);
        SurfaceGraph graph = new SurfaceGraph(g, 4);
        assertTrue(edges(graph, 0, 0, 0).contains(skin(1, 1, 1, SQRT3)));
        assertTrue(edges(graph, 1, 1, 1).contains(skin(0, 0, 0, SQRT3)));

        // Two voxels touching at a vertex in open space: no edge.
        ArrayVoxelGrid open = new ArrayVoxelGrid(-4, -4, -4, 4, 4, 4, false);
        open.set(0, 0, 0, true).set(1, 1, 1, true);
        assertEquals(List.of(), edges(new SurfaceGraph(open, 4), 0, 0, 0));
        // With one face-mate in between, its second step still passes between open corners: no edge.
        open.set(1, 0, 0, true);
        graph = new SurfaceGraph(open, 4);
        assertFalse(edges(graph, 0, 0, 0).contains(skin(1, 1, 1, SQRT3)));
        assertFalse(edges(graph, 1, 1, 1).contains(skin(0, 0, 0, SQRT3)));
        // Filling a corner of that step completes a two-step skin path.
        open.set(1, 1, 0, true);
        graph = new SurfaceGraph(open, 4);
        assertTrue(edges(graph, 0, 0, 0).contains(skin(1, 1, 1, SQRT3)));
        assertTrue(edges(graph, 1, 1, 1).contains(skin(0, 0, 0, SQRT3)));
    }

    // ---- properties on random grids --------------------------------------------------------------------------------

    @Test
    void matchesTheReferenceRules() {
        Random random = new Random(42);
        for (int round = 0; round < 120; round++) {
            ArrayVoxelGrid g = randomGrid(random, 8);
            MaskedView view = randomUnknowns(random, g);
            int depth = random.nextInt(6);
            SurfaceGraph graph = new SurfaceGraph(round % 2 == 0 ? view : new VoxelCache(view), depth);
            Reference reference = new Reference(view, depth);
            for (int y = g.minY - 1; y <= g.maxY + 1; y++) {
                for (int z = g.minZ - 1; z <= g.maxZ + 1; z++) {
                    for (int x = g.minX - 1; x <= g.maxX + 1; x++) {
                        String at = "round " + round + " at " + x + "," + y + "," + z;
                        assertEquals(reference.node(x, y, z), graph.isNode(x, y, z), at);
                        List<Edge> list = edges(graph, x, y, z);
                        assertEquals(reference.edges(x, y, z), new HashSet<>(list), at);
                        assertEquals(list.size(), new HashSet<>(list).size(), "duplicate edge " + at);
                    }
                }
            }
        }
    }

    @Test
    void edgesAreSymmetricAndJoinNodes() {
        Random random = new Random(7);
        for (int round = 0; round < 300; round++) {
            ArrayVoxelGrid g = randomGrid(random, 10);
            MaskedView view = randomUnknowns(random, g);
            SurfaceGraph graph = new SurfaceGraph(view, random.nextInt(7));
            for (int y = g.minY; y <= g.maxY; y++) {
                for (int z = g.minZ; z <= g.maxZ; z++) {
                    for (int x = g.minX; x <= g.maxX; x++) {
                        long a = p(x, y, z);
                        List<Edge> out = edges(graph, a);
                        if (!graph.isNode(a)) {
                            assertEquals(List.of(), out);
                            continue;
                        }
                        for (Edge e : out) {
                            String at = "round " + round + ": " + VoxelPos.toString(a) + " " + e;
                            assertTrue(graph.isNode(e.to()), at);
                            int bx = VoxelPos.x(e.to()), by = VoxelPos.y(e.to()), bz = VoxelPos.z(e.to());
                            assertTrue(Surface.isSurface(view, bx, by, bz), at);
                            assertTrue(edges(graph, e.to()).contains(new Edge(a, e.length(), e.dive())),
                                    "no way back: " + at);
                            assertEquals(VoxelPos.center(a).distance(VoxelPos.center(e.to())), e.length(), 1e-12, at);
                        }
                    }
                }
            }
        }
    }

    @Test
    void edgeOrderIsDeterministic() {
        Random random = new Random(3);
        for (int round = 0; round < 20; round++) {
            ArrayVoxelGrid g = randomGrid(random, 10);
            SurfaceGraph forward = new SurfaceGraph(g, 4), backward = new SurfaceGraph(new VoxelCache(g), 4);
            List<List<Edge>> first = new ArrayList<>(), second = new ArrayList<>();
            long origin = p(g.minX, g.minY, g.minZ);
            for (int i = 0; i < 1000; i++) {
                first.add(edges(forward, VoxelPos.offset(origin, i % 10, i / 10 % 10, i / 100)));
            }
            for (int i = 999; i >= 0; i--) {
                second.add(0, edges(backward, VoxelPos.offset(origin, i % 10, i / 10 % 10, i / 100)));
            }
            assertEquals(first, second);
            for (List<Edge> list : first) {
                // Skin edges first, then dives.
                for (int i = 1; i < list.size(); i++) {
                    assertTrue(list.get(i).dive() || !list.get(i - 1).dive(), list.toString());
                }
            }
        }
    }

    @Test
    void invalidationMatchesAFreshGraph() {
        Random random = new Random(1234);
        for (int variant = 0; variant < 4; variant++) {
            int depth = 1 + variant;
            ArrayVoxelGrid g = randomGrid(random, 18);
            VoxelCache cache = new VoxelCache(g);
            SurfaceGraph graph = new SurfaceGraph(variant % 2 == 0 ? cache : g, depth);
            int x0 = g.minX + 2, y0 = g.minY + 2, z0 = g.minZ + 2, x1 = g.maxX - 2, y1 = g.maxY - 2, z1 = g.maxZ - 2;
            assertSameGraph(new SurfaceGraph(g, depth), graph, x0, y0, z0, x1, y1, z1);
            for (int i = 0; i < 25; i++) {
                int x = g.minX + random.nextInt(18), y = g.minY + random.nextInt(18), z = g.minZ + random.nextInt(18);
                g.set(x, y, z, !g.isSolid(x, y, z));
                cache.invalidate(x, y, z);
                graph.invalidate(x, y, z);
                assertSameGraph(new SurfaceGraph(g, depth), graph, x0, y0, z0, x1, y1, z1);
            }
        }
    }

    @Test
    void invalidationReachesTheFarEndOfADive() {
        // A dive of the maximum length 4 through a 5-thick wall depends on the voxel 5 blocks away from its start.
        ArrayVoxelGrid g = ArrayVoxelGrid.thinWallBetweenCaves(0, 8, 0, 5);
        SurfaceGraph graph = new SurfaceGraph(g, 4);
        assertTrue(edges(graph, 0, 4, 0).contains(dive(4, 4, 0, 4)));
        g.set(5, 4, 0, true);
        graph.invalidate(5, 4, 0);
        assertTrue(edges(graph, 0, 4, 0).stream().noneMatch(Edge::dive));
        g.set(5, 4, 0, false);
        graph.invalidate(5, 4, 0);
        assertTrue(edges(graph, 0, 4, 0).contains(dive(4, 4, 0, 4)));
    }

    @Test
    void invalidateBoxMatchesAFreshGraph() {
        Random random = new Random(4321);
        for (int variant = 0; variant < 6; variant++) {
            int depth = variant;
            ArrayVoxelGrid g = randomGrid(random, 18);
            VoxelCache cache = new VoxelCache(g);
            SurfaceGraph graph = new SurfaceGraph(variant % 2 == 0 ? cache : g, depth);
            int x0 = g.minX + 2, y0 = g.minY + 2, z0 = g.minZ + 2, x1 = g.maxX - 2, y1 = g.maxY - 2, z1 = g.maxZ - 2;
            assertSameGraph(new SurfaceGraph(g, depth), graph, x0, y0, z0, x1, y1, z1);
            for (int i = 0; i < 12; i++) {
                // A box of random contents, bounds in any order.
                int ax = g.minX + random.nextInt(18), ay = g.minY + random.nextInt(18), az = g.minZ + random.nextInt(18);
                int bx = ax + random.nextInt(9) - 4, by = ay + random.nextInt(9) - 4, bz = az + random.nextInt(9) - 4;
                for (int y = Math.min(ay, by); y <= Math.max(ay, by); y++) {
                    for (int z = Math.min(az, bz); z <= Math.max(az, bz); z++) {
                        for (int x = Math.min(ax, bx); x <= Math.max(ax, bx); x++) {
                            if (g.contains(x, y, z)) {
                                g.set(x, y, z, random.nextInt(3) == 0 != g.isSolid(x, y, z));
                            }
                        }
                    }
                }
                cache.invalidateBox(ax, ay, az, bx, by, bz);
                graph.invalidateBox(ax, ay, az, bx, by, bz);
                assertSameGraph(new SurfaceGraph(g, depth), graph, x0, y0, z0, x1, y1, z1);
            }
            // A box larger than the memo itself: every memo section is visited instead of the box.
            g.fill(g.minX, g.minY, g.minZ, g.maxX, g.minY + 9, g.maxZ, true);
            cache.clear();
            graph.invalidateBox(Integer.MIN_VALUE, g.minY + 9, Integer.MIN_VALUE, Integer.MAX_VALUE,
                    Integer.MIN_VALUE, Integer.MAX_VALUE);
            assertSameGraph(new SurfaceGraph(g, depth), graph, x0, y0, z0, x1, y1, z1);
        }
    }

    /**
     * Unknown voxels in whole 16-wide chunk columns (they read as solid), which can be "loaded" later: what a level
     * looks like to the runtime when a chunk loads after its sections were cached.
     */
    private static final class ChunkedView implements VoxelView {
        final ArrayVoxelGrid grid;
        final Set<Long> unloaded = new HashSet<>();

        ChunkedView(ArrayVoxelGrid grid) {
            this.grid = grid;
        }

        boolean loaded(int x, int z) {
            return !unloaded.contains(p(x >> 4, 0, z >> 4));
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return !loaded(x, z) || grid.isSolid(x, y, z);
        }

        @Override
        public boolean isKnown(int x, int y, int z) {
            return loaded(x, z);
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

    /** Bounding box of the voxels a {@link VoxelCache#reload} reports. */
    private static final class Bounds implements VoxelCache.ChangeListener {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;

        @Override
        public void changed(int x, int y, int z) {
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            z0 = Math.min(z0, z);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
            z1 = Math.max(z1, z);
        }
    }

    /** Reads the cache section of the voxel again and invalidates the graph over whatever changed in it. */
    private static void reload(VoxelCache cache, SurfaceGraph graph, int x, int y, int z) {
        Bounds b = new Bounds();
        if (cache.reload(x, y, z, b) > 0) {
            graph.invalidateBox(b.x0, b.y0, b.z0, b.x1, b.y1, b.z1);
        }
    }

    @Test
    void reloadingASectionInvalidatesEverythingThatChangedInIt() {
        // A chunk column is cached as unknown, then loads without any block event; later a block changes elsewhere in
        // one of its sections. Reading that section again takes all of the loaded terrain at once, so the graph must
        // forget everything that depended on any voxel of it, not only the reported one.
        Random random = new Random(77);
        int oneWayWithPointInvalidation = 0;
        for (int round = 0; round < 8; round++) {
            ArrayVoxelGrid g = new ArrayVoxelGrid(-14, -6, -6, 29, 21, 21,
                    ArrayVoxelGrid.Outside.values()[random.nextInt(3)]);
            double density = 0.3 + 0.4 * random.nextDouble();
            for (int y = g.minY; y <= g.maxY; y++) {
                for (int z = g.minZ; z <= g.maxZ; z++) {
                    for (int x = g.minX; x <= g.maxX; x++) {
                        g.set(x, y, z, y < 4 + random.nextInt(3) || random.nextDouble() < density);
                    }
                }
            }
            ChunkedView view = new ChunkedView(g);
            view.unloaded.add(p(0, 0, 0));
            int depth = 1 + random.nextInt(8);
            VoxelCache cache = new VoxelCache(view), pointCache = new VoxelCache(view);
            SurfaceGraph graph = new SurfaceGraph(cache, depth), pointGraph = new SurfaceGraph(pointCache, depth);
            int x0 = -12, y0 = -4, z0 = -4, x1 = 27, y1 = 19, z1 = 19;
            assertSameGraph(new SurfaceGraph(view, depth), graph, x0, y0, z0, x1, y1, z1);
            assertSameGraph(new SurfaceGraph(view, depth), pointGraph, x0, y0, z0, x1, y1, z1);

            view.unloaded.clear();
            int cx = random.nextInt(16), cy = random.nextInt(16), cz = random.nextInt(16);
            g.set(cx, cy, cz, !g.isSolid(cx, cy, cz));
            // Old handling: drop the section, invalidate the graph around the reported voxel only.
            pointCache.invalidate(cx, cy, cz);
            pointGraph.invalidate(cx, cy, cz);
            oneWayWithPointInvalidation += oneWayEdges(pointGraph, x0, y0, z0, x1, y1, z1);

            reload(cache, graph, cx, cy, cz);
            // Only that section is read again; the column's other sections stay cached as unknown.
            assertSameGraph(new SurfaceGraph(cache, depth), graph, x0, y0, z0, x1, y1, z1);
            assertEquals(0, oneWayEdges(graph, x0, y0, z0, x1, y1, z1), "round " + round);

            // The chunk load itself: every cached section of the column is read again.
            for (int sy = -1; sy <= 1; sy++) {
                reload(cache, graph, 0, sy * 16, 0);
            }
            assertSameGraph(new SurfaceGraph(view, depth), graph, x0, y0, z0, x1, y1, z1);
            assertEquals(0, oneWayEdges(graph, x0, y0, z0, x1, y1, z1), "round " + round);
        }
        assertTrue(oneWayWithPointInvalidation > 0, "the scenes must reproduce the stale edges of point invalidation");
    }

    /** Number of edges in the box whose reverse edge is missing. */
    private static int oneWayEdges(SurfaceGraph graph, int x0, int y0, int z0, int x1, int y1, int z1) {
        int count = 0;
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    long a = p(x, y, z);
                    for (Edge e : edges(graph, a)) {
                        if (!edges(graph, e.to()).contains(new Edge(a, e.length(), e.dive()))) {
                            count++;
                        }
                    }
                }
            }
        }
        return count;
    }

    private static void assertSameGraph(SurfaceGraph expected, SurfaceGraph actual,
                                        int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    String at = x + "," + y + "," + z;
                    boolean node = expected.isNode(x, y, z);
                    assertEquals(node, actual.isNode(x, y, z), at);
                    assertEquals(edges(expected, x, y, z), edges(actual, x, y, z), at);
                    if (node || (x + y + z) % 5 == 0) {
                        assertEquals(expected.normal(x, y, z), actual.normal(x, y, z), at);
                    }
                }
            }
        }
    }

    // ---- normals and nearest node ----------------------------------------------------------------------------------

    @Test
    void normalIsTheSmoothedSurfaceNormal() {
        Random random = new Random(9);
        List<ArrayVoxelGrid> grids = new ArrayList<>(List.of(ArrayVoxelGrid.flatFloor(0), ArrayVoxelGrid.cave(0, 4),
                ArrayVoxelGrid.floorWallCorner(0, 0), ArrayVoxelGrid.room(-3, 1, -3, 3, 5, 3),
                ArrayVoxelGrid.step(0, 0), ArrayVoxelGrid.thinWallBetweenCaves(0, 6, 0, 1)));
        for (int i = 0; i < 6; i++) {
            grids.add(randomGrid(random, 12));
        }
        for (ArrayVoxelGrid g : grids) {
            SurfaceGraph graph = new SurfaceGraph(new VoxelCache(g), 4);
            for (int pass = 0; pass < 2; pass++) {
                for (int y = 7; y >= -7; y--) {
                    for (int x = -7; x <= 7; x++) {
                        for (int z = 7; z >= -7; z--) {
                            Vec3 expected = SurfaceNormals.smoothNormal(g, x, y, z);
                            Vec3 actual = graph.normal(x, y, z);
                            assertEquals(expected, actual, x + "," + y + "," + z);
                            if (!graph.isNode(x, y, z)) {
                                assertSame(Vec3.ZERO, actual);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void nearestNodeMatchesABruteForceSearch() {
        Random random = new Random(21);
        for (int round = 0; round < 40; round++) {
            ArrayVoxelGrid g = randomGrid(random, 12);
            SurfaceGraph graph = new SurfaceGraph(g, 4);
            for (int i = 0; i < 200; i++) {
                Vec3 point = new Vec3(g.minX + random.nextDouble() * 12, g.minY + random.nextDouble() * 12,
                        g.minZ + random.nextDouble() * 12);
                if (i % 4 == 0) {
                    // Exactly on voxel corners, edges or centres: many ties.
                    point = new Vec3(Math.floor(point.x() * 2) / 2, Math.floor(point.y() * 2) / 2,
                            Math.floor(point.z() * 2) / 2);
                }
                int radius = random.nextInt(4);
                assertEquals(VoxelPos.toString(bruteForceNearest(g, point, radius)),
                        VoxelPos.toString(graph.nearestNode(point, radius)), point + " r " + radius);
            }
        }
    }

    private static long bruteForceNearest(VoxelView view, Vec3 point, int radius) {
        int cx = (int) Math.floor(point.x()), cy = (int) Math.floor(point.y()), cz = (int) Math.floor(point.z());
        long best = SurfaceGraph.NO_NODE;
        double bestD2 = Double.POSITIVE_INFINITY;
        // y, z, x ascending with a strict comparison: ties go to the lowest y, then z, then x.
        for (int y = cy - radius; y <= cy + radius; y++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                for (int x = cx - radius; x <= cx + radius; x++) {
                    if (Surface.isSurface(view, x, y, z)) {
                        double d2 = Vec3.voxelCenter(x, y, z).distanceSquared(point);
                        if (d2 < bestD2) {
                            bestD2 = d2;
                            best = p(x, y, z);
                        }
                    }
                }
            }
        }
        return best;
    }

    @Test
    void nearestNodeExamples() {
        SurfaceGraph graph = new SurfaceGraph(ArrayVoxelGrid.flatFloor(0), 4);
        assertEquals(p(3, 0, -2), graph.nearestNode(new Vec3(3.7, 0.9, -1.2), 0));
        assertEquals(p(3, 0, -2), graph.nearestNode(new Vec3(3.7, 1.9, -1.2), 1));
        assertEquals(SurfaceGraph.NO_NODE, graph.nearestNode(new Vec3(3.7, 2.9, -1.2), 1));
        assertEquals(p(3, 0, -2), graph.nearestNode(new Vec3(3.7, 2.9, -1.2), 2));
        // Four floor voxels at the same distance: lowest z, then lowest x.
        assertEquals(p(0, 0, 0), graph.nearestNode(new Vec3(1, 1, 1), 1));
        assertEquals(SurfaceGraph.NO_NODE, new SurfaceGraph(new ArrayVoxelGrid(0, 0, 0, 1, 1, 1, false), 4)
                .nearestNode(new Vec3(0.5, 0.5, 0.5), 3));
    }

    // ---- misc ------------------------------------------------------------------------------------------------------

    @Test
    void clearForgetsMemoizedSections() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        SurfaceGraph graph = new SurfaceGraph(g, 4);
        assertSame(g, graph.view());
        assertEquals(4, graph.maxDiveDepth());
        edges(graph, 0, 0, 0);
        edges(graph, 20, 0, 0);
        assertEquals(2, graph.sectionCount());
        g.set(0, 1, 0, true);
        assertTrue(graph.isNode(0, 0, 0), "memoized");
        graph.clear();
        assertEquals(0, graph.sectionCount());
        assertFalse(graph.isNode(0, 0, 0));
    }

    @Test
    void rejectsUnsupportedDiveDepths() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        assertThrows(IllegalArgumentException.class, () -> new SurfaceGraph(g, -1));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceGraph(g, SurfaceGraph.MAX_DIVE_DEPTH + 1));
        assertEquals(SurfaceGraph.MAX_DIVE_DEPTH, new SurfaceGraph(g, SurfaceGraph.MAX_DIVE_DEPTH).maxDiveDepth());
    }

    @Test
    void expansionSmokeBenchmark() {
        // Not a timing assertion: expands a few thousand nodes of a cave system cold and warm, and reports the cost.
        ArrayVoxelGrid g = ArrayVoxelGrid.cave(-4, 6);
        Random random = new Random(1);
        for (int i = 0; i < 60; i++) {
            int x = random.nextInt(40) - 20, z = random.nextInt(40) - 20;
            g.fill(x, -4, z, x + random.nextInt(3), random.nextInt(10) - 4, z + random.nextInt(3), true);
        }
        SurfaceGraph graph = new SurfaceGraph(new VoxelCache(g), 4);
        long start = graph.nearestNode(new Vec3(0.5, -3.5, 0.5), 8);
        assertTrue(start != SurfaceGraph.NO_NODE);
        long[] counts = new long[2];
        for (int run = 0; run < 2; run++) {
            long t0 = System.nanoTime();
            int expanded = bfs(graph, start, 4000, counts);
            long t1 = System.nanoTime();
            System.out.printf("SurfaceGraph %s: %d nodes, %d edges, %.2f us/node%n", run == 0 ? "cold" : "warm",
                    expanded, counts[1], (t1 - t0) / 1000.0 / expanded);
            assertTrue(expanded >= 1000);
        }
    }

    private static int bfs(SurfaceGraph graph, long start, int limit, long[] counts) {
        Set<Long> seen = new HashSet<>();
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        int expanded = 0;
        counts[1] = 0;
        while (!queue.isEmpty() && expanded < limit) {
            long node = queue.poll();
            expanded++;
            graph.forEachEdge(node, (to, length, dive) -> {
                counts[1]++;
                if (seen.add(to)) {
                    queue.add(to);
                }
            });
        }
        return expanded;
    }
}
