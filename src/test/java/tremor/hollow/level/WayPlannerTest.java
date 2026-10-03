package tremor.hollow.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WayPlannerTest {
    private static final VoxelGrid.Region ANYWHERE = (x, y, z) -> true;

    private static VoxelGrid rock() {
        return new VoxelGrid(-30, -12, -30, 30, 12, 30);
    }

    private static void open(VoxelGrid grid, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    grid.set(x, y, z, VoxelGrid.OPEN);
                }
            }
        }
    }

    /** Whether the player can walk from the start to stand next to the node (a block aside, at most one down or up). */
    private static int stepsToNode(VoxelGrid grid, long node) {
        WayPlanner.Walk walk = WayPlanner.distances(grid, 0, 0, 0, 200);
        int best = -1;
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] side : sides) {
            for (int dy = -1; dy <= 1; dy++) {
                int d = walk.distance(CellKey.x(node) + side[0], CellKey.y(node) + dy, CellKey.z(node) + side[1]);
                if (d >= 0 && (best < 0 || d < best)) {
                    best = d;
                }
            }
        }
        return best;
    }

    @Test
    void inTheOpenTheNodeGoesWhereTheWalkGetsInRange() {
        VoxelGrid grid = rock();
        open(grid, -30, 0, -30, 30, 5, 30);
        WayPlanner.Plan plan = WayPlanner.plan(grid, ANYWHERE, 0, 0, 0, 14, 24, 1);
        assertFalse(plan.tunnel());
        assertTrue(plan.carve().isEmpty() && plan.fill().isEmpty());
        assertTrue(plan.length() >= 14 && plan.length() <= 24, "length " + plan.length());
        int x = CellKey.x(plan.node());
        int z = CellKey.z(plan.node());
        assertEquals(0, CellKey.y(plan.node()), "on the floor");
        assertEquals(plan.length(), Math.abs(x) + Math.abs(z), "a block aside per step on flat ground");
        // The way: feet and head from the start to the cell before the node, and the cell above the node, all open;
        // the node not on it.
        assertTrue(plan.way().contains(CellKey.of(0, 0, 0)) && plan.way().contains(CellKey.of(0, 1, 0)));
        assertEquals(2 * plan.length() + 1, plan.way().size());
        assertFalse(plan.way().contains(plan.node()));
        assertTrue(plan.way().contains(CellKey.above(plan.node(), 1)));
        for (long cell : plan.way()) {
            assertTrue(grid.open(CellKey.x(cell), CellKey.y(cell), CellKey.z(cell)));
        }
        plan.applyTo(grid);
        assertEquals(plan.length() - 1, stepsToNode(grid, plan.node()));
    }

    @Test
    void theWalkClimbsAndDrops() {
        VoxelGrid grid = rock();
        // A corridor along x with a step up at x = 3 and a drop of three at x = 6.
        open(grid, 0, 0, 0, 2, 2, 0);
        open(grid, 3, 1, 0, 5, 3, 0);
        open(grid, 6, -2, 0, 9, 3, 0);
        WayPlanner.Walk walk = WayPlanner.distances(grid, 0, 0, 0, 50);
        assertEquals(3, walk.distance(3, 1, 0));
        assertEquals(6, walk.distance(6, -2, 0));
        assertEquals(9, walk.distance(9, -2, 0));
        assertEquals(-1, walk.distance(5, 0, 0), "solid");
        // No way back up the drop.
        assertEquals(-1, WayPlanner.distances(grid, 9, -2, 0, 50).distance(0, 0, 0));
    }

    @Test
    void inRockATunnelIsDugToAChamber() {
        VoxelGrid grid = rock();
        open(grid, 0, 0, 0, 0, 1, 0);
        WayPlanner.Plan plan = WayPlanner.plan(grid, ANYWHERE, 0, 0, 0, 14, 24, 3);
        assertTrue(plan.tunnel());
        assertTrue(plan.length() >= 14 && plan.length() <= 25, "length " + plan.length());
        assertTrue(plan.way().containsAll(plan.carve()));
        assertFalse(plan.carve().contains(plan.node()));
        assertFalse(plan.carve().contains(CellKey.of(0, -1, 0)), "never under the player's feet");
        plan.applyTo(grid);
        int steps = stepsToNode(grid, plan.node());
        assertTrue(steps >= 0, "the node can be reached");
        // Two wide: every cell dug on a floor has a neighbour on the way aside at the same height.
        for (long cell : plan.carve()) {
            int x = CellKey.x(cell);
            int y = CellKey.y(cell);
            int z = CellKey.z(cell);
            if (grid.open(x, y - 1, z)) {
                continue;
            }
            assertTrue(plan.way().contains(CellKey.of(x + 1, y, z)) || plan.way().contains(CellKey.of(x - 1, y, z))
                    || plan.way().contains(CellKey.of(x, y, z + 1)) || plan.way().contains(CellKey.of(x, y, z - 1)),
                    "narrow at " + x + " " + y + " " + z);
        }
        for (long cell : plan.way()) {
            assertTrue(grid.open(CellKey.x(cell), CellKey.y(cell), CellKey.z(cell)));
        }
        for (long cell : plan.fill()) {
            assertFalse(grid.open(CellKey.x(cell), CellKey.y(cell), CellKey.z(cell)));
        }
    }

    @Test
    void theTunnelStaysInTheRegionAndEndsEarlyIfItMust() {
        VoxelGrid grid = rock();
        open(grid, 0, 0, 0, 0, 1, 0);
        VoxelGrid.Region small = (x, y, z) -> Math.abs(x) <= 5 && Math.abs(z) <= 5 && Math.abs(y) <= 5;
        WayPlanner.Plan plan = WayPlanner.plan(grid, small, 0, 0, 0, 14, 24, 9);
        assertTrue(plan.tunnel());
        assertNotEquals(CellKey.of(0, 0, 0), plan.node());
        assertTrue(small.allows(CellKey.x(plan.node()), CellKey.y(plan.node()), CellKey.z(plan.node())));
        for (long cell : plan.carve()) {
            assertTrue(small.allows(CellKey.x(cell), CellKey.y(cell), CellKey.z(cell)));
        }
        for (long cell : plan.fill()) {
            assertTrue(small.allows(CellKey.x(cell), CellKey.y(cell), CellKey.z(cell)));
        }
        plan.applyTo(grid);
        assertTrue(stepsToNode(grid, plan.node()) >= 0);
    }

    @Test
    void aShortOpenWalkGoesOnAsATunnel() {
        VoxelGrid grid = rock();
        // Five blocks of corridor: too short for the node.
        open(grid, 0, 0, 0, 5, 1, 0);
        WayPlanner.Plan plan = WayPlanner.plan(grid, ANYWHERE, 0, 0, 0, 14, 24, 11);
        assertTrue(plan.tunnel());
        assertTrue(plan.length() >= 14, "length " + plan.length());
        // The way starts with the corridor walked to its end.
        for (int x = 0; x <= 5; x++) {
            assertTrue(plan.way().contains(CellKey.of(x, 0, 0)), "corridor at " + x);
        }
        plan.applyTo(grid);
        assertTrue(stepsToNode(grid, plan.node()) >= 0);
    }

    @Test
    void theSameSeedGivesTheSamePlan() {
        VoxelGrid a = rock();
        VoxelGrid b = rock();
        open(a, 0, 0, 0, 0, 1, 0);
        open(b, 0, 0, 0, 0, 1, 0);
        WayPlanner.Plan first = WayPlanner.plan(a, ANYWHERE, 0, 0, 0, 14, 24, 5);
        WayPlanner.Plan second = WayPlanner.plan(b, ANYWHERE, 0, 0, 0, 14, 24, 5);
        assertEquals(first.node(), second.node());
        assertEquals(first.carve(), second.carve());
        assertEquals(first.way(), second.way());
    }

    @Test
    void aNodeReachedByADropKeepsItsApproachOpen() {
        VoxelGrid grid = rock();
        // A corridor along x, and a pit three deep at its end: the only cell 14 steps away is the bottom of the pit.
        open(grid, 0, 0, 0, 13, 1, 0);
        open(grid, 14, -3, 0, 14, 1, 0);
        WayPlanner.Plan plan = WayPlanner.plan(grid, ANYWHERE, 0, 0, 0, 14, 14, 1);
        assertFalse(plan.tunnel());
        assertEquals(CellKey.of(14, -3, 0), plan.node());
        // The column above the node, down which the player drops onto it, is on the way: nothing buries it.
        for (int y = -2; y <= 1; y++) {
            assertTrue(plan.way().contains(CellKey.of(14, y, 0)), "column at " + y);
        }
        assertFalse(plan.way().contains(plan.node()));
    }

    @Test
    void theNodeIsNeverUnderWater() {
        for (long seed = 0; seed < 40; seed++) {
            VoxelGrid grid = rock();
            open(grid, -30, 0, -30, 30, 5, 30);
            // A lake two deep over the half x > 2: the walk swims through it, the node is not put into it.
            for (int z = -30; z <= 30; z++) {
                for (int x = 3; x <= 30; x++) {
                    for (int y = 0; y <= 1; y++) {
                        grid.set(x, y, z, VoxelGrid.OPEN | VoxelGrid.LIQUID);
                    }
                }
            }
            WayPlanner.Plan plan = WayPlanner.plan(grid, ANYWHERE, 0, 0, 0, 14, 24, seed);
            int x = CellKey.x(plan.node());
            int y = CellKey.y(plan.node());
            int z = CellKey.z(plan.node());
            assertTrue(grid.dry(x, y, z) && grid.dry(x, y + 1, z), "node in the water at " + x + " " + z);
            // ...nor next to the end of the way in it: the player breaks it standing dry.
            boolean dryNeighbour = false;
            int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] side : sides) {
                long next = CellKey.of(x + side[0], y, z + side[1]);
                dryNeighbour |= plan.way().contains(next) && grid.dry(x + side[0], y, z + side[1]);
            }
            assertTrue(dryNeighbour, "seed " + seed);
        }
    }

    @Test
    void theWalkNeverDropsThroughLava() {
        VoxelGrid grid = rock();
        open(grid, 0, 0, 0, 2, 2, 0);
        open(grid, 3, -3, 0, 3, 2, 0);
        assertEquals(3, WayPlanner.distances(grid, 0, 0, 0, 50).distance(3, -3, 0));
        grid.set(3, -1, 0, VoxelGrid.OPEN | VoxelGrid.LIQUID | VoxelGrid.HAZARD);
        assertEquals(-1, WayPlanner.distances(grid, 0, 0, 0, 50).distance(3, -3, 0), "through the lava");
    }

    @Test
    void theWalkDoesNotJumpOntoAFence() {
        VoxelGrid grid = rock();
        open(grid, 0, 0, 0, 2, 2, 0);
        open(grid, 3, 1, 0, 6, 3, 0);
        // A plain block at x = 3 is a step up...
        assertEquals(3, WayPlanner.distances(grid, 0, 0, 0, 50).distance(3, 1, 0));
        // ...a fence there is too high to jump onto.
        grid.set(3, 0, 0, VoxelGrid.TALL);
        assertEquals(-1, WayPlanner.distances(grid, 0, 0, 0, 50).distance(3, 1, 0));
    }

    @Test
    void theNodeIsNeverOnTheBottomRowOfTheGrid() {
        // Ground falling away from the start down to the bottom of the grid and beyond: what is under the bottom row
        // is not known, so nobody stands there.
        VoxelGrid grid = new VoxelGrid(-30, -6, -30, 30, 6, 30);
        open(grid, -30, -6, -30, 30, 6, 30);
        for (int z = -30; z <= 30; z++) {
            for (int x = -30; x <= 30; x++) {
                int floor = Math.max(-7, 1 - (Math.abs(x) + Math.abs(z)) * 3 / 2);
                for (int y = -6; y < floor; y++) {
                    grid.set(x, y, z, 0);
                }
            }
        }
        WayPlanner.Walk walk = WayPlanner.distances(grid, 0, 1, 0, 50);
        int sizeX = 61;
        int sizeZ = 61;
        boolean deep = false;
        for (int i = 0; i < walk.reached(); i++) {
            int y = grid.minY() + walk.order()[i] / sizeX / sizeZ;
            assertTrue(y > grid.minY(), "stands on the bottom row");
            deep |= y == grid.minY() + 1;
        }
        assertTrue(deep, "the walk goes down as far as it may");
        WayPlanner.Plan plan = WayPlanner.plan(grid, ANYWHERE, 0, 1, 0, 4, 10, 3);
        assertTrue(CellKey.y(plan.node()) > grid.minY());
    }

    @Test
    void aTunnelIsSealedFromLavaAndWater() {
        for (long seed = 0; seed < 20; seed++) {
            VoxelGrid grid = rock();
            // Lava two blocks over the player's feet and two under, water over the upper lava.
            for (int z = -30; z <= 30; z++) {
                for (int x = -30; x <= 30; x++) {
                    grid.set(x, 2, z, VoxelGrid.OPEN | VoxelGrid.LIQUID | VoxelGrid.HAZARD);
                    grid.set(x, 3, z, VoxelGrid.OPEN | VoxelGrid.LIQUID);
                    grid.set(x, -2, z, VoxelGrid.OPEN | VoxelGrid.LIQUID | VoxelGrid.HAZARD);
                }
            }
            open(grid, 0, 0, 0, 0, 1, 0);
            WayPlanner.Plan plan = WayPlanner.plan(grid, ANYWHERE, 0, 0, 0, 14, 24, seed);
            assertTrue(plan.tunnel());
            plan.applyTo(grid);
            int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (long cell : plan.carve()) {
                for (int[] face : faces) {
                    int flags = grid.flags(CellKey.x(cell) + face[0], CellKey.y(cell) + face[1],
                            CellKey.z(cell) + face[2]);
                    assertEquals(0, flags & (VoxelGrid.LIQUID | VoxelGrid.HAZARD), "a leak next to "
                            + CellKey.x(cell) + " " + CellKey.y(cell) + " " + CellKey.z(cell) + ", seed " + seed);
                }
            }
            assertTrue(stepsToNode(grid, plan.node()) >= 0);
        }
    }
}
