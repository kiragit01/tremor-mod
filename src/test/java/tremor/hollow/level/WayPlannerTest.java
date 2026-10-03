package tremor.hollow.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class WayPlannerTest {
    private static final VoxelGrid.Region ANYWHERE = (x, y, z) -> true;
    /**
     * The region of the game around a player at the origin: the copy is 32 blocks around and 24 above and below, the
     * planning keeps a quarter of its radius from the round edge and 3 blocks from its top and bottom.
     */
    private static final VoxelGrid.Region GAME = (x, y, z) -> square(x + 0.5) + square(z + 0.5) <= 24 * 24
            && Math.abs(y) <= 21;
    /** The defaults of the config. */
    private static final WayPlanner.Params DEFAULTS = new WayPlanner.Params(40, 60, 12, 3, 5);

    private static double square(double v) {
        return v * v;
    }

    /** Solid rock of the game's snapshot around a player at the origin: 32 blocks around, 20 below, 12 above. */
    private static VoxelGrid gameRock() {
        return new VoxelGrid(-32, -20, -32, 32, 12, 32);
    }

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

    /** A 1x2 tunnel along x through the player at the origin, widened into the cave of the game. */
    private static VoxelGrid widenedTunnel(long seed) {
        VoxelGrid grid = gameRock();
        open(grid, -32, 0, 0, 32, 1, 0);
        Widening.cave(grid, GAME, 0, 0, 0, 8, 6, seed).applyTo(grid);
        return grid;
    }

    /** Flat open ground: the player at the origin stands on it under the sky. */
    private static VoxelGrid field() {
        VoxelGrid grid = gameRock();
        open(grid, -32, 0, -32, 32, 12, 32);
        return grid;
    }

    private static boolean open(VoxelGrid grid, long cell) {
        return grid.open(CellKey.x(cell), CellKey.y(cell), CellKey.z(cell));
    }

    private static boolean allows(VoxelGrid.Region region, long cell) {
        return region.allows(CellKey.x(cell), CellKey.y(cell), CellKey.z(cell));
    }

    private static int distance(WayPlanner.Walk walk, long cell) {
        return walk.distance(CellKey.x(cell), CellKey.y(cell), CellKey.z(cell));
    }

    /**
     * What holds for every network planned for a player at the origin of {@code before}: what changes lies in the
     * region and never under the player's feet; the way is open and over no hole; the node is exactly
     * {@code plan.length()} steps away (the walk allows a block up and a drop of three, nothing else), and under the
     * ground; every dead end is walked to; and the network is safe ({@link #safe}). Returns the grid with the plan in
     * it.
     */
    private static VoxelGrid check(VoxelGrid before, VoxelGrid.Region region, WayPlanner.Plan plan, String what) {
        VoxelGrid grid = before.copy();
        plan.applyTo(grid);
        for (long cell : plan.carve()) {
            assertTrue(allows(region, cell), what + ": dug outside the region");
        }
        for (long cell : plan.fill()) {
            // A fluid next to what is dug is sealed, and a wall built, even a block out of the region.
            assertTrue(allows(region, cell) || nextTo(region, cell), what + ": filled outside the region");
            assertFalse(open(grid, cell), what);
        }
        assertTrue(allows(region, plan.node()), what + ": the node outside the region");
        assertFalse(plan.carve().contains(CellKey.of(0, -1, 0)), what + ": dug under the player's feet");
        for (long cell : plan.way()) {
            assertTrue(open(grid, cell), what + ": the way is not open at " + text(cell));
            long below = CellKey.above(cell, -1);
            assertTrue(!open(grid, below) || plan.way().contains(below), what + ": a hole under the way at "
                    + text(cell));
        }
        assertFalse(plan.way().contains(plan.node()));
        VoxelGrid reach = grid.copy();
        reach.carve(plan.node());
        WayPlanner.Walk walk = WayPlanner.distances(reach, 0, 0, 0, 400);
        assertEquals(plan.length(), distance(walk, plan.node()), what + ": steps to the node");
        for (long end : plan.branchEnds()) {
            assertTrue(distance(walk, end) > 0, what + ": no way to the dead end at " + text(end));
            assertFalse(plan.way().contains(end), what + ": the dead end at " + text(end) + " is on the way");
        }
        assertTrue(plan.covered(), what + ": the node is not under the ground");
        int x = CellKey.x(plan.node());
        int z = CellKey.z(plan.node());
        int top = CellKey.y(plan.node()) + 1;
        while (grid.open(x, top, z)) {
            top++;
        }
        for (int h = 0; h < WayPlanner.COVER; h++) {
            assertFalse(grid.open(x, top + h, z), what + ": no ground over the node");
        }
        safe(before, reach, plan, walk, what);
        return grid;
    }

    /**
     * The network ({@code after}: the plan in it, the node open) is no trap: every cell of it a player stands in and
     * walks to ({@code walk}: from the start) walks back to the start (the cells walked to in 16 steps on the grid as
     * it was, {@code before}); beside it, out of the open space of the start, nothing is open but the network itself
     * (a corridor through a cave, no shortcut, no chamber of the node open to a cave), and nowhere does a player in it
     * fall more than a block aside or step into lava ({@link WayPlanner#falls}).
     */
    private static void safe(VoxelGrid before, VoxelGrid after, WayPlanner.Plan plan, WayPlanner.Walk walk,
                             String what) {
        WayPlanner.Walk near = WayPlanner.distances(before, 0, 0, 0, 16);
        List<Long> start = new ArrayList<>();
        for (int i = 0; i < near.reached(); i++) {
            start.add(WalkBack.key(before, near.order()[i]));
        }
        boolean[] back = WalkBack.to(after, start);
        boolean[] air = startAir(before);
        Set<Long> network = plan.network();
        for (long cell : network) {
            int x = CellKey.x(cell);
            int y = CellKey.y(cell);
            int z = CellKey.z(cell);
            if (!air[after.index(x, y, z)]) {
                for (int side = 0; side < 5; side++) {
                    int nx = x + (side == 0 ? 1 : side == 1 ? -1 : 0);
                    int ny = y + (side == 4 ? 1 : 0);
                    int nz = z + (side == 2 ? 1 : side == 3 ? -1 : 0);
                    assertTrue(!after.open(nx, ny, nz) || network.contains(CellKey.of(nx, ny, nz))
                                    || air[after.index(nx, ny, nz)],
                            what + ": the network at " + text(cell) + " is open to " + nx + " " + ny + " " + nz);
                }
            }
            if (walk.distance(x, y, z) < 0 || near.distance(x, y, z) >= 0) {
                continue;
            }
            assertTrue(back[after.index(x, y, z)], what + ": no way back to the start from " + text(cell));
            for (int dir = 0; dir < 4; dir++) {
                int nx = x + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
                int nz = z + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
                if (network.contains(CellKey.of(nx, y, nz)) || network.contains(CellKey.of(nx, y + 1, nz))) {
                    continue;
                }
                assertFalse(WayPlanner.falls(after, nx, y, nz), what + ": a fall beside " + text(cell));
            }
        }
    }

    /** The open space of the start: the air over the cells walked to in {@link WayPlanner#START_STEPS} steps. */
    private static boolean[] startAir(VoxelGrid before) {
        WayPlanner.Walk walk = WayPlanner.distances(before, 0, 0, 0, WayPlanner.START_STEPS);
        boolean[] air = new boolean[before.volume()];
        for (int i = 0; i < walk.reached(); i++) {
            long cell = WalkBack.key(before, walk.order()[i]);
            for (int y = CellKey.y(cell); before.open(CellKey.x(cell), y, CellKey.z(cell)); y++) {
                air[before.index(CellKey.x(cell), y, CellKey.z(cell))] = true;
            }
        }
        return air;
    }

    /**
     * How far {@code node} lies from the nearest cell a player at the origin of {@code before} walks to in 16 steps
     * (the start cave), worked out here on its own.
     */
    private static double clearance(VoxelGrid before, long node) {
        WayPlanner.Walk walk = WayPlanner.distances(before, 0, 0, 0, 16);
        double nearest = Double.MAX_VALUE;
        for (int x = before.minX(); x <= before.maxX(); x++) {
            for (int y = before.minY(); y <= before.maxY(); y++) {
                for (int z = before.minZ(); z <= before.maxZ(); z++) {
                    if (walk.distance(x, y, z) >= 0) {
                        nearest = Math.min(nearest, Math.sqrt(square(x - CellKey.x(node))
                                + square(y - CellKey.y(node)) + square(z - CellKey.z(node))));
                    }
                }
            }
        }
        return nearest;
    }

    /** Whether a cell next to {@code cell} lies in the region. */
    private static boolean nextTo(VoxelGrid.Region region, long cell) {
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] face : faces) {
            if (region.allows(CellKey.x(cell) + face[0], CellKey.y(cell) + face[1], CellKey.z(cell) + face[2])) {
                return true;
            }
        }
        return false;
    }

    private static String text(long cell) {
        return CellKey.x(cell) + " " + CellKey.y(cell) + " " + CellKey.z(cell);
    }

    @Test
    void underTheGroundALongWayWindsToTheNodeAmongDeadEnds() {
        int fromStart = 0;
        int forks = 0;
        for (long seed = 0; seed < 20; seed++) {
            VoxelGrid grid = widenedTunnel(seed);
            WayPlanner.Plan plan = WayPlanner.plan(grid, GAME, 0, 0, 0, DEFAULTS, seed);
            String what = "seed " + seed;
            check(grid, GAME, plan, what);
            assertTrue(plan.length() >= 40 && plan.length() <= 60, what + ": length " + plan.length());
            assertTrue(plan.straight() >= 12, what + ": " + plan.straight() + " blocks straight");
            // The node is not right behind the wall of the start cave: some blocks of rock lie between.
            assertTrue(plan.clearance() >= WayPlanner.CLEARANCE, what + ": " + plan.clearance()
                    + " blocks from the start cave");
            assertTrue(clearance(grid, plan.node()) >= WayPlanner.CLEARANCE, what);
            assertFalse(plan.throat(), what + ": a throat underground");
            assertTrue(plan.branches() >= 3 && plan.branches() <= 5, what + ": " + plan.branches() + " dead ends");
            assertEquals(0, plan.decoys());
            assertTrue(plan.fromStart() <= 1);
            fromStart += plan.fromStart();
            for (long start : plan.branchStarts()) {
                forks += plan.way().contains(start) && Math.hypot(CellKey.x(start), CellKey.z(start)) > 8 ? 1 : 0;
            }
            // The dead end out of the start begins in the widened cave.
            if (plan.fromStart() == 1) {
                long start = plan.branchStarts().get(0);
                assertTrue(Math.hypot(CellKey.x(start), CellKey.z(start)) <= 10, what);
            }
        }
        assertTrue(fromStart >= 18, "a dead end out of the start cave only " + fromStart + " times of 20");
        assertTrue(forks >= 20, "dead ends forking off the way: " + forks);
    }

    @Test
    void onOpenGroundTheWayGoesDownAThroatAmongDecoys() {
        for (long seed = 0; seed < 20; seed++) {
            VoxelGrid grid = field();
            WayPlanner.Plan plan = WayPlanner.plan(grid, GAME, 0, 0, 0, DEFAULTS, seed);
            String what = "seed " + seed;
            check(grid, GAME, plan, what);
            assertTrue(plan.length() >= 40 && plan.length() <= 60, what + ": length " + plan.length());
            assertTrue(plan.straight() >= 12, what + ": " + plan.straight() + " blocks straight");
            assertTrue(plan.throat(), what + ": no throat");
            assertTrue(plan.clearance() >= WayPlanner.CLEARANCE, what + ": " + plan.clearance()
                    + " blocks from the ground the player walks on");
            assertTrue(CellKey.y(plan.node()) <= -WayPlanner.COVER - 2, what + ": the node is not deep");
            assertTrue(plan.decoys() >= 1 && plan.decoys() <= 2, what + ": " + plan.decoys() + " decoys");
            assertTrue(plan.branches() >= 3 && plan.branches() <= 5, what + ": " + plan.branches() + " dead ends");
            // The throat: the way breaks through the ground's top layer first some 4 to 8 blocks from the player.
            double mouth = Double.MAX_VALUE;
            for (long cell : plan.way()) {
                if (CellKey.y(cell) == -1 && plan.carve().contains(cell)) {
                    mouth = Math.min(mouth, Math.hypot(CellKey.x(cell), CellKey.z(cell)));
                }
            }
            assertTrue(mouth >= WayPlanner.MOUTH_NEAR - 1 && mouth <= WayPlanner.MOUTH_FAR + 1.5,
                    what + ": the mouth " + mouth + " blocks away");
            // The plan says where the throat starts: on the surface, where the walk from the player gets to it.
            double said = Math.hypot(CellKey.x(plan.mouth()), CellKey.z(plan.mouth()));
            assertTrue(said >= WayPlanner.MOUTH_NEAR && said <= WayPlanner.MOUTH_FAR, what + ": the mouth at " + said);
            assertEquals(0, CellKey.y(plan.mouth()), what + ": the mouth is not on the surface");
            assertTrue(plan.way().contains(plan.mouth()), what + ": the mouth is not on the way");
            // The decoys open the ground elsewhere: separate holes in its top layer.
            assertTrue(openings(plan.carve(), -1) >= 1 + plan.decoys(), what + ": " + openings(plan.carve(), -1)
                    + " openings for " + plan.decoys() + " decoys");
        }
    }

    /** Separate patches (touching sides or corners) of dug cells at the height {@code y}. */
    private static int openings(Set<Long> carve, int y) {
        Set<Long> left = new HashSet<>();
        for (long cell : carve) {
            if (CellKey.y(cell) == y) {
                left.add(cell);
            }
        }
        int patches = 0;
        while (!left.isEmpty()) {
            patches++;
            ArrayDeque<Long> queue = new ArrayDeque<>();
            long first = left.iterator().next();
            left.remove(first);
            queue.add(first);
            while (!queue.isEmpty()) {
                long cell = queue.poll();
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        long next = CellKey.of(CellKey.x(cell) + dx, y, CellKey.z(cell) + dz);
                        if (left.remove(next)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return patches;
    }

    @Test
    void theWayGoesThroughTheCavesOfTheCopyWithoutShortcuts() {
        int inRange = 0;
        for (long seed = 0; seed < 20; seed++) {
            VoxelGrid grid = widenedTunnel(seed);
            // Caves of the copy here and there, some deep, one right by the start cave.
            java.util.Random random = new java.util.Random(seed);
            for (int i = 0; i < 8; i++) {
                int cx = i == 0 ? 10 : random.nextInt(41) - 20;
                int cy = i == 0 ? 0 : random.nextInt(17) - 12;
                int cz = i == 0 ? 3 : random.nextInt(41) - 20;
                int r = 2 + random.nextInt(4);
                for (int y = cy - r; y <= cy + r; y++) {
                    for (int z = cz - r; z <= cz + r; z++) {
                        for (int x = cx - r; x <= cx + r; x++) {
                            if (square(x - cx) + square(1.5 * (y - cy)) + square(z - cz) <= r * r) {
                                grid.set(x, y, z, VoxelGrid.OPEN);
                            }
                        }
                    }
                }
            }
            WayPlanner.Plan plan = WayPlanner.plan(grid, GAME, 0, 0, 0, DEFAULTS, seed);
            check(grid, GAME, plan, "seed " + seed);
            inRange += plan.length() >= 40 && plan.length() <= 60 && plan.straight() >= 12
                    && plan.clearance() >= WayPlanner.CLEARANCE ? 1 : 0;
        }
        assertTrue(inRange >= 18, "in range only " + inRange + " times of 20");
    }

    /**
     * The widened tunnel with a big cave beside the start cave (13 by 25 blocks), from 8 blocks under the player's feet
     * to 2 over: a fall of 9 or more from the height of the player, onto lava or the rock.
     */
    private static VoxelGrid caveBeside(long seed, boolean lava) {
        VoxelGrid grid = widenedTunnel(seed);
        open(grid, 11, -8, -12, 23, 2, 12);
        if (lava) {
            for (int z = -12; z <= 12; z++) {
                for (int x = 11; x <= 23; x++) {
                    grid.set(x, -8, z, VoxelGrid.OPEN | VoxelGrid.LIQUID | VoxelGrid.HAZARD);
                }
            }
        }
        return grid;
    }

    @Test
    void throughACaveTheWaysAreWalledCorridorsNotBridges() {
        int crossings = 0;
        for (boolean lava : new boolean[] {false, true}) {
            for (long seed = 0; seed < 20; seed++) {
                VoxelGrid grid = caveBeside(seed, lava);
                WayPlanner.Plan plan = WayPlanner.plan(grid, GAME, 0, 0, 0, DEFAULTS, seed);
                String what = (lava ? "over lava" : "over a drop") + ", seed " + seed;
                // No side of the network open but to the network itself, no fall beside it, no way into the lava.
                VoxelGrid after = check(grid, GAME, plan, what);
                boolean crosses = false;
                for (long cell : plan.network()) {
                    int x = CellKey.x(cell);
                    int y = CellKey.y(cell);
                    int z = CellKey.z(cell);
                    if (x >= 11 && x <= 23 && Math.abs(z) <= 12 && y > -8 && grid.open(x, y - 1, z)
                            && after.standable(x, y, z)) {
                        // A floor laid over the cave: the corridor's sides are solid or the corridor's own.
                        crosses = true;
                        for (int dir = 0; dir < 4; dir++) {
                            int nx = x + (dir == 0 ? 1 : dir == 1 ? -1 : 0);
                            int nz = z + (dir == 2 ? 1 : dir == 3 ? -1 : 0);
                            assertTrue(!after.open(nx, y, nz) || plan.network().contains(CellKey.of(nx, y, nz)),
                                    what + ": open beside the corridor at " + text(cell));
                        }
                    }
                }
                crossings += crosses ? 1 : 0;
            }
        }
        assertTrue(crossings >= 8, "the ways crossed the cave " + crossings + " times of 40");
    }

    @Test
    void everyPlaceOfTheNetworkIsWalkedBackFrom() {
        // check() walks back to the start from every place of the network a player gets to. A trap was rare (a later
        // part of a worm dug out the floor of an earlier one, some 1 to 3 networks of 100): many networks.
        for (long seed = 20; seed < 100; seed++) {
            VoxelGrid field = field();
            check(field, GAME, WayPlanner.plan(field, GAME, 0, 0, 0, DEFAULTS, seed), "field, seed " + seed);
            VoxelGrid tunnel = widenedTunnel(seed);
            check(tunnel, GAME, WayPlanner.plan(tunnel, GAME, 0, 0, 0, DEFAULTS, seed), "tunnel, seed " + seed);
        }
    }

    @Test
    void theNodesChamberIsSealedFromTheCavesAroundAndNoneCutsTheWayShort() {
        int inRange = 0;
        for (long seed = 0; seed < 20; seed++) {
            // A ring of cave around the start cave, 12 to 16 blocks out, 8 blocks under the player's feet to 2 over.
            VoxelGrid grid = widenedTunnel(seed);
            for (int z = -32; z <= 32; z++) {
                for (int x = -32; x <= 32; x++) {
                    double d = Math.hypot(x, z);
                    if (d >= 12 && d <= 16) {
                        open(grid, x, -8, z, x, 2, z);
                    }
                }
            }
            WayPlanner.Plan plan = WayPlanner.plan(grid, GAME, 0, 0, 0, DEFAULTS, seed);
            String what = "seed " + seed;
            VoxelGrid after = check(grid, GAME, plan, what);
            // Around the node nothing is open but the chamber and the ways (check() tells the same of all the network).
            long node = plan.node();
            for (long cell : plan.network()) {
                if (Math.abs(CellKey.x(cell) - CellKey.x(node)) > 3 || Math.abs(CellKey.z(cell) - CellKey.z(node)) > 3
                        || Math.abs(CellKey.y(cell) - CellKey.y(node)) > 4) {
                    continue;
                }
                int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
                for (int[] face : faces) {
                    long next = CellKey.of(CellKey.x(cell) + face[0], CellKey.y(cell) + face[1],
                            CellKey.z(cell) + face[2]);
                    assertTrue(!open(after, next) || plan.network().contains(next) || next == node,
                            what + ": the chamber of the node opens at " + text(next));
                }
            }
            inRange += plan.length() >= 40 && plan.length() <= 60 ? 1 : 0;
        }
        assertTrue(inRange >= 19, "in range only " + inRange + " times of 20");
    }

    @Test
    void aStepAsideFallsOnlyWhereThePlayerCouldNotStepBack() {
        VoxelGrid grid = rock();
        // On the level, and a block down: a step back up.
        open(grid, 0, 0, 0, 0, 2, 0);
        assertFalse(WayPlanner.falls(grid, 0, 0, 0));
        open(grid, 2, -1, 0, 2, 2, 0);
        assertFalse(WayPlanner.falls(grid, 2, 0, 0));
        // A block up, or no room to step in: no fall.
        open(grid, 4, 1, 0, 4, 3, 0);
        assertFalse(WayPlanner.falls(grid, 4, 0, 0));
        open(grid, 6, 0, 0, 6, 0, 0);
        assertFalse(WayPlanner.falls(grid, 6, 0, 0), "a gap a block high");
        // Two blocks down, or deep: a fall.
        open(grid, 8, -2, 0, 8, 2, 0);
        assertTrue(WayPlanner.falls(grid, 8, 0, 0));
        open(grid, 10, -9, 0, 10, 2, 0);
        assertTrue(WayPlanner.falls(grid, 10, 0, 0));
        // Lava at the feet, at the head or a block down; magma underfoot.
        open(grid, 12, 0, 0, 12, 2, 0);
        grid.set(12, 0, 0, VoxelGrid.OPEN | VoxelGrid.LIQUID | VoxelGrid.HAZARD);
        assertTrue(WayPlanner.falls(grid, 12, 0, 0));
        open(grid, 14, 0, 0, 14, 2, 0);
        grid.set(14, 1, 0, VoxelGrid.OPEN | VoxelGrid.LIQUID | VoxelGrid.HAZARD);
        assertTrue(WayPlanner.falls(grid, 14, 0, 0));
        open(grid, 16, -1, 0, 16, 2, 0);
        grid.set(16, -1, 0, VoxelGrid.OPEN | VoxelGrid.LIQUID | VoxelGrid.HAZARD);
        assertTrue(WayPlanner.falls(grid, 16, 0, 0));
        open(grid, 18, 0, 0, 18, 2, 0);
        grid.set(18, -1, 0, VoxelGrid.HAZARD);
        assertTrue(WayPlanner.falls(grid, 18, 0, 0));
        // Deep water breaks the fall and is swum out of.
        open(grid, 20, -4, 0, 20, 2, 0);
        for (int y = -4; y <= -1; y++) {
            grid.set(20, y, 0, VoxelGrid.OPEN | VoxelGrid.LIQUID);
        }
        assertFalse(WayPlanner.falls(grid, 20, 0, 0));
    }

    /** Ground over {@code y < surface(x, z)} in the game's grid; the rest open. */
    private static VoxelGrid ground(java.util.function.IntBinaryOperator surface) {
        VoxelGrid grid = gameRock();
        for (int z = -32; z <= 32; z++) {
            for (int x = -32; x <= 32; x++) {
                for (int y = surface.applyAsInt(x, z); y <= 12; y++) {
                    grid.set(x, y, z, VoxelGrid.OPEN);
                }
            }
        }
        return grid;
    }

    @Test
    void hillsALakeshoreAndABigCaveGetTheirNetworkToo() {
        for (long seed = 0; seed < 10; seed++) {
            // Hills, the player on a slope between them.
            VoxelGrid hills = ground((x, z) -> (int) Math.round(4 * Math.sin(x / 7.0) * Math.cos(z / 9.0)));
            // A lake three deep beside the player.
            VoxelGrid shore = field();
            for (int z = -32; z <= 32; z++) {
                for (int x = 4; x <= 32; x++) {
                    for (int y = -3; y <= -1; y++) {
                        shore.set(x, y, z, VoxelGrid.OPEN | VoxelGrid.LIQUID);
                    }
                }
            }
            // A big cave deep down, roomy enough not to be widened.
            VoxelGrid cave = gameRock();
            for (int z = -12; z <= 12; z++) {
                for (int x = -12; x <= 12; x++) {
                    if (x * x + z * z <= 144) {
                        open(cave, x, 0, z, x, 8 - (x * x + z * z) / 30, z);
                    }
                }
            }
            for (VoxelGrid grid : new VoxelGrid[] {hills, shore, cave}) {
                WayPlanner.Plan plan = WayPlanner.plan(grid, GAME, 0, 0, 0, DEFAULTS, seed);
                String what = (grid == cave ? "cave" : grid == shore ? "shore" : "hills") + ", seed " + seed;
                check(grid, GAME, plan, what);
                assertTrue(plan.length() >= 40 && plan.length() <= 60 && plan.straight() >= 12, what);
                assertTrue(plan.clearance() >= WayPlanner.CLEARANCE, what + ": " + plan.clearance()
                        + " blocks from the start");
                assertTrue(plan.branches() >= 3, what);
                assertEquals(grid != cave, plan.throat(), what);
            }
        }
    }

    @Test
    void onASkyPlatformThereIsNoGroundButStillAWay() {
        // A small platform high over the bottom of the grid: nowhere to go under the ground.
        VoxelGrid grid = ground((x, z) -> x * x + z * z <= 8 ? 0 : -19);
        for (long seed = 0; seed < 5; seed++) {
            WayPlanner.Plan plan = WayPlanner.plan(grid, GAME, 0, 0, 0, DEFAULTS, seed);
            assertFalse(plan.covered());
            assertEquals(WayPlanner.TRIES, plan.tries(), "the best of all tries");
            VoxelGrid after = grid.copy();
            plan.applyTo(after);
            after.carve(plan.node());
            assertEquals(plan.length(), distance(WayPlanner.distances(after, 0, 0, 0, 400), plan.node()),
                    "a stair down through the air, with floors laid");
        }
    }

    @Test
    void theNetworkStaysInASmallRegion() {
        for (long seed = 0; seed < 10; seed++) {
            VoxelGrid grid = gameRock();
            open(grid, 0, 0, 0, 0, 1, 0);
            VoxelGrid.Region small = (x, y, z) -> Math.abs(x) <= 6 && Math.abs(z) <= 6 && Math.abs(y) <= 8;
            WayPlanner.Plan plan = WayPlanner.plan(grid, small, 0, 0, 0, DEFAULTS, seed);
            assertTrue(allows(small, plan.node()));
            for (long cell : plan.carve()) {
                assertTrue(allows(small, cell));
            }
            for (long cell : plan.fill()) {
                assertTrue(allows(small, cell));
            }
            VoxelGrid after = grid.copy();
            plan.applyTo(after);
            after.carve(plan.node());
            assertTrue(plan.length() > 0);
            assertEquals(plan.length(), distance(WayPlanner.distances(after, 0, 0, 0, 400), plan.node()));
        }
    }

    @Test
    void theSameSeedGivesTheSamePlan() {
        WayPlanner.Plan first = WayPlanner.plan(widenedTunnel(5), GAME, 0, 0, 0, DEFAULTS, 5);
        WayPlanner.Plan second = WayPlanner.plan(widenedTunnel(5), GAME, 0, 0, 0, DEFAULTS, 5);
        assertEquals(first.node(), second.node());
        assertEquals(first.carve(), second.carve());
        assertEquals(first.fill(), second.fill());
        assertEquals(first.way(), second.way());
        assertEquals(first.branchEnds(), second.branchEnds());
    }

    @Test
    void theNetworkIsSealedFromLavaAndWater() {
        for (long seed = 0; seed < 10; seed++) {
            VoxelGrid grid = gameRock();
            // Lava two blocks over the player's feet and two under, water over the upper lava.
            for (int z = -32; z <= 32; z++) {
                for (int x = -32; x <= 32; x++) {
                    grid.set(x, 2, z, VoxelGrid.OPEN | VoxelGrid.LIQUID | VoxelGrid.HAZARD);
                    grid.set(x, 3, z, VoxelGrid.OPEN | VoxelGrid.LIQUID);
                    grid.set(x, -2, z, VoxelGrid.OPEN | VoxelGrid.LIQUID | VoxelGrid.HAZARD);
                }
            }
            open(grid, 0, 0, 0, 0, 1, 0);
            WayPlanner.Plan plan = WayPlanner.plan(grid, GAME, 0, 0, 0, DEFAULTS, seed);
            VoxelGrid after = check(grid, GAME, plan, "seed " + seed);
            int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (long cell : plan.carve()) {
                for (int[] face : faces) {
                    int flags = after.flags(CellKey.x(cell) + face[0], CellKey.y(cell) + face[1],
                            CellKey.z(cell) + face[2]);
                    assertEquals(0, flags & (VoxelGrid.LIQUID | VoxelGrid.HAZARD), "a leak next to " + text(cell)
                            + ", seed " + seed);
                }
            }
        }
    }

    @Test
    void thePlanningIsCutIntoShortParts() {
        long worst = 0;
        long total = 0;
        int parts = 0;
        for (long seed = 0; seed < 6; seed++) {
            VoxelGrid grid = seed % 2 == 0 ? widenedTunnel(seed) : field();
            WayPlanner planner = new WayPlanner(grid, GAME, 0, 0, 0, DEFAULTS, seed);
            boolean done = false;
            while (!done) {
                long begin = System.nanoTime();
                done = planner.step();
                long spent = System.nanoTime() - begin;
                parts++;
                total += spent;
                // The first runs warm the JIT up.
                worst = seed > 1 ? Math.max(worst, spent) : worst;
            }
        }
        System.out.printf("Network planning on the game's grid: %d parts for 6 plans, %.1f ms in all, worst part "
                + "%.2f ms (after warm-up)%n", parts, total / 1e6, worst / 1e6);
        // A walk, at most TRIES tries of the way, three tries of each of at most 5 dead ends and 2 to make up for
        // ones that could not be dug, the end.
        assertTrue(parts <= 6 * (2 + WayPlanner.TRIES + 3 * (5 + 2)), "parts " + parts);
        assertTrue(worst < 100_000_000L, "a part took " + worst / 1e6 + " ms");
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
        for (long seed = 0; seed < 5; seed++) {
            WayPlanner.Plan plan = WayPlanner.plan(grid, ANYWHERE, 0, 1, 0, new WayPlanner.Params(4, 10, 0, 1, 2),
                    seed);
            assertTrue(CellKey.y(plan.node()) > grid.minY());
        }
    }

    @Test
    void openGroundIsUnderTheSkyOrNearly() {
        VoxelGrid grid = field();
        assertTrue(WayPlanner.nearSurface(grid, 0, 0, 0));
        // Under three blocks of leaves or a roof: still the surface.
        for (int y = 5; y < 8; y++) {
            grid.set(0, y, 0, 0);
        }
        assertTrue(WayPlanner.nearSurface(grid, 0, 0, 0));
        grid.set(0, 9, 0, 0);
        assertFalse(WayPlanner.nearSurface(grid, 0, 0, 0), "under four");
        // A cave: rock up to the top of the grid.
        assertFalse(WayPlanner.nearSurface(widenedTunnel(1), 0, 0, 0));
    }
}
