package tremor.hollow.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.IntBinaryOperator;

/**
 * The network of ways is no danger and no trap, on many copies full of caves, lava and open ground: beside the network
 * no drop of more than {@value WayPlanner#MAX_DROP} and no lava (a way through a cave is a walled corridor, not a
 * bridge); the node and every place of the network walk back to the player (to the start, where the start has a drop
 * the player cannot climb back up); every place a player walks to walks on to the node, but for the copy's own pits;
 * and the length the plan gives (which the status flags SHORT or LONG) is the true one.
 */
class WayPlannerSafetyTest {
    /** The region of the game around a player at the origin, as in {@link WayPlannerTest}. */
    private static final VoxelGrid.Region GAME = (x, y, z) -> square(x + 0.5) + square(z + 0.5) <= 24 * 24
            && Math.abs(y) <= 21;
    private static final WayPlanner.Params DEFAULTS = new WayPlanner.Params(40, 60, 12, 3, 5);
    private static final int LAVA = VoxelGrid.OPEN | VoxelGrid.LIQUID | VoxelGrid.HAZARD;
    private static final int WATER = VoxelGrid.OPEN | VoxelGrid.LIQUID;
    private static final long PLAYER = CellKey.of(0, 0, 0);
    private static final int SEEDS = 25;

    private static double square(double v) {
        return v * v;
    }

    @Test
    void throughCavesLavaAndOpenGroundTheNetworkIsNoDangerAndNoTrap() {
        int plans = 0;
        int inRange = 0;
        for (String scenario : new String[] {"field", "tunnel", "hills", "ravine", "lava", "start cave", "sky"}) {
            for (long seed = 0; seed < SEEDS; seed++) {
                VoxelGrid grid = copy(scenario, seed);
                WayPlanner.Plan plan = WayPlanner.plan(grid, GAME, 0, 0, 0, DEFAULTS, seed * 7 + 3);
                check(grid, plan, scenario + ", seed " + seed);
                plans++;
                inRange += plan.length() >= DEFAULTS.minLength() && plan.length() <= DEFAULTS.maxLength() ? 1 : 0;
            }
        }
        assertTrue(inRange >= plans * 95 / 100, "in range only " + inRange + " times of " + plans);
    }

    /** What holds for every plan (see the class comment); {@code before} is the copy as it was. */
    private static void check(VoxelGrid before, WayPlanner.Plan plan, String what) {
        VoxelGrid after = before.copy();
        plan.applyTo(after);
        after.carve(plan.node());
        WayPlanner.Walk walk = WayPlanner.distances(after, 0, 0, 0, Integer.MAX_VALUE);
        assertEquals(plan.length(), distance(walk, plan.node()), what + ": the length the status tells of");
        boolean[] onward = WalkBack.to(after, List.of(plan.node()));

        // The copy's own pits: walked to but not back from on the grid as it was.
        WayPlanner.Walk walkBefore = WayPlanner.distances(before, 0, 0, 0, Integer.MAX_VALUE);
        boolean[] homeBefore = WalkBack.to(before, List.of(PLAYER));
        // Back to the player; but where the start itself has such a pit (a drop of three into a cave of the hills,
        // say, that the way may well go on from), back to the start, the cells walked to in 16 steps.
        List<Long> start = new ArrayList<>();
        WayPlanner.Walk near = WayPlanner.distances(before, 0, 0, 0, 16);
        boolean pit = false;
        for (int i = 0; i < near.reached(); i++) {
            start.add(WalkBack.key(before, near.order()[i]));
            pit |= !homeBefore[near.order()[i]];
        }
        boolean[] home = WalkBack.to(after, pit ? start : List.of(PLAYER));
        String back = pit ? "the start" : "the player";
        assertTrue(home[index(after, plan.node())], what + ": no way back from the node to " + back);
        for (long end : plan.branchEnds()) {
            assertTrue(distance(walk, end) < 0 || home[index(after, end)],
                    what + ": no way back from the dead end at " + text(end) + " to " + back);
        }
        int stuck = 0;
        for (int i = 1; i < walk.reached(); i++) {
            int cell = walk.order()[i];
            if (walkBefore.distance()[cell] >= 0 && !homeBefore[cell]) {
                continue;
            }
            long key = WalkBack.key(after, cell);
            assertTrue(!plan.network().contains(key) || home[cell], what + ": no way back to " + back + " from "
                    + text(key));
            stuck += onward[cell] ? 0 : 1;
        }
        assertEquals(plan.stuck(), stuck, what + ": places without a way on to the node");
        assertEquals(0, stuck, what + ": places without a way on to the node");
        sides(before, after, plan, walk, what);
    }

    /**
     * Beside the network (but the walk over the ground to the mouth, which is the copy's own): no lava, no open side
     * over a drop of more than {@value WayPlanner#MAX_DROP} or onto lava; out of the open space of the start, nothing
     * open but the network itself.
     */
    private static void sides(VoxelGrid before, VoxelGrid after, WayPlanner.Plan plan, WayPlanner.Walk walk,
                              String what) {
        Set<Long> ground = new HashSet<>();
        WayPlanner.Walk near = WayPlanner.distances(before, 0, 0, 0, 16);
        for (int at = index(before, plan.mouth()); at >= 0; at = near.parent()[at]) {
            ground.add(WalkBack.key(before, at));
            ground.add(CellKey.above(WalkBack.key(before, at), 1));
        }
        boolean[] air = startAir(before);
        Set<Long> network = plan.network();
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};
        for (long cell : network) {
            if (ground.contains(cell)) {
                continue;
            }
            int x = CellKey.x(cell);
            int y = CellKey.y(cell);
            int z = CellKey.z(cell);
            // In the open space of the start a cell of the network that was open already may keep the lava the copy
            // had beside it (here lava over the air of the start cave, as no real copy has it: it would flow down,
            // and a fall or step into it is walled, WayPlanner.falls).
            boolean own = before.open(x, y, z) && air[before.index(x, y, z)];
            for (int[] face : faces) {
                int flags = after.flags(x + face[0], y + face[1], z + face[2]);
                assertTrue((flags & VoxelGrid.HAZARD) == 0 || (flags & (VoxelGrid.OPEN | VoxelGrid.LIQUID)) == 0
                                || own && before.flags(x + face[0], y + face[1], z + face[2]) == flags,
                        what + ": lava next to the network at " + text(cell));
            }
            if (!air[before.index(x, y, z)]) {
                for (int side = 0; side < 5; side++) {
                    int nx = x + faces[side][0];
                    int ny = y + faces[side][1];
                    int nz = z + faces[side][2];
                    long next = CellKey.of(nx, ny, nz);
                    assertTrue(!after.open(nx, ny, nz) || network.contains(next) || air[after.index(nx, ny, nz)],
                            what + ": the network at " + text(cell) + " is open to " + text(next));
                }
            }
            if (!after.standable(x, y, z) || walk.distance(x, y, z) < 0) {
                continue;
            }
            for (int side = 0; side < 4; side++) {
                int nx = x + faces[side][0];
                int nz = z + faces[side][2];
                if (!after.open(nx, y, nz) || !after.open(nx, y + 1, nz)
                        || network.contains(CellKey.of(nx, y, nz)) && network.contains(CellKey.of(nx, y + 1, nz))) {
                    continue;
                }
                int drop = 0;
                int h = y - 1;
                while (after.contains(nx, h, nz) && after.open(nx, h, nz) && after.dry(nx, h, nz)) {
                    drop++;
                    h--;
                }
                assertTrue(drop <= WayPlanner.MAX_DROP, what + ": a drop of " + drop + " beside " + text(cell));
                assertTrue(((after.flags(nx, y, nz) | after.flags(nx, y + 1, nz) | after.flags(nx, h, nz))
                        & VoxelGrid.HAZARD) == 0, what + ": lava beside " + text(cell));
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

    // ---- the copies ----

    /**
     * A copy of the game's size around a player at the origin: open ground, a widened tunnel or hills, with caves
     * (some floored with lava or water) and ravines; a ravine three blocks from the player; lava lakes under caves; a
     * big cave around the player with a pit and lava pools; a small platform high in the air.
     */
    private static VoxelGrid copy(String scenario, long seed) {
        Random random = new Random(seed * 1000003L + scenario.hashCode());
        VoxelGrid grid;
        switch (scenario) {
            case "field" -> {
                grid = field();
                caves(grid, random, 10, -2);
            }
            case "tunnel" -> {
                grid = rock();
                open(grid, -32, 0, 0, 32, 1, 0, VoxelGrid.OPEN);
                Widening.cave(grid, GAME, 0, 0, 0, 8, 6, seed).applyTo(grid);
                caves(grid, random, 10, 8);
            }
            case "hills" -> {
                grid = ground((x, z) -> (int) Math.round(4 * Math.sin(x / 7.0) * Math.cos(z / 9.0)));
                caves(grid, random, 10, -2);
            }
            case "ravine" -> {
                grid = field();
                open(grid, 3, -12, -32, 4, -1, 32, VoxelGrid.OPEN);
                if (seed % 2 == 0) {
                    open(grid, 3, -12, -32, 4, -12, 32, LAVA);
                }
                caves(grid, random, 5, -2);
            }
            case "lava" -> {
                grid = field();
                caves(grid, random, 6, -2);
                for (int z = -32; z <= 32; z++) {
                    for (int x = -32; x <= 32; x++) {
                        if (Math.sin(x / 5.0 + seed) * Math.cos(z / 6.0) > 0.3) {
                            open(grid, x, -12, z, x, -9, z, VoxelGrid.OPEN);
                        }
                        if (grid.open(x, -12, z)) {
                            grid.set(x, -12, z, LAVA);
                        }
                    }
                }
            }
            case "start cave" -> {
                grid = rock();
                for (int z = -14; z <= 14; z++) {
                    for (int x = -14; x <= 14; x++) {
                        if (x * x + z * z <= 196) {
                            open(grid, x, 0, z, x, 6 - (x * x + z * z) / 40, z, VoxelGrid.OPEN);
                        }
                    }
                }
                open(grid, 5, -10, -3, 8, -1, 3, VoxelGrid.OPEN);
                open(grid, 5, -10, -3, 8, -10, 3, LAVA);
                open(grid, -9, -1, -9, -6, -1, -6, LAVA);
                caves(grid, random, 6, 4);
            }
            case "sky" -> grid = ground((x, z) -> x * x + z * z <= 8 ? 0 : -19);
            default -> throw new IllegalArgumentException(scenario);
        }
        // The player stands where the copy was made.
        grid.set(0, -1, 0, 0);
        grid.set(0, 0, 0, VoxelGrid.OPEN);
        grid.set(0, 1, 0, VoxelGrid.OPEN);
        return grid;
    }

    /** {@code count} caves under {@code top}: blobs (some floored with lava or water) and deep, narrow ravines. */
    private static void caves(VoxelGrid grid, Random random, int count, int top) {
        for (int i = 0; i < count; i++) {
            int kind = random.nextInt(4);
            int cx = random.nextInt(49) - 24;
            int cz = random.nextInt(49) - 24;
            int cy = random.nextInt(top + 18) - 18;
            if (kind == 3) {
                double angle = random.nextDouble() * Math.PI;
                int length = 10 + random.nextInt(20);
                int depth = 6 + random.nextInt(10);
                for (int t = -length; t <= length; t++) {
                    int x = cx + (int) Math.round(t * Math.cos(angle));
                    int z = cz + (int) Math.round(t * Math.sin(angle));
                    open(grid, x, cy - depth, z, x + 1, cy + 2, z, VoxelGrid.OPEN);
                    if (random.nextBoolean()) {
                        grid.set(x, cy - depth, z, LAVA);
                    }
                }
                continue;
            }
            int r = 2 + random.nextInt(5);
            for (int y = cy - r; y <= cy + r; y++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    for (int x = cx - r; x <= cx + r; x++) {
                        if (square(x - cx) + square(1.5 * (y - cy)) + square(z - cz) <= r * r) {
                            grid.set(x, y, z, VoxelGrid.OPEN);
                        }
                    }
                }
            }
            if (kind == 1 || kind == 2) {
                int floor = cy - (int) (r / 1.5);
                for (int z = cz - r; z <= cz + r; z++) {
                    for (int x = cx - r; x <= cx + r; x++) {
                        if (grid.open(x, floor, z) && square(x - cx) + square(z - cz) <= r * r) {
                            grid.set(x, floor, z, kind == 1 ? LAVA : WATER);
                        }
                    }
                }
            }
        }
    }

    private static VoxelGrid rock() {
        return new VoxelGrid(-32, -20, -32, 32, 12, 32);
    }

    private static VoxelGrid field() {
        VoxelGrid grid = rock();
        open(grid, -32, 0, -32, 32, 12, 32, VoxelGrid.OPEN);
        return grid;
    }

    /** Ground under {@code surface(x, z)}, open from there up. */
    private static VoxelGrid ground(IntBinaryOperator surface) {
        VoxelGrid grid = rock();
        for (int z = -32; z <= 32; z++) {
            for (int x = -32; x <= 32; x++) {
                open(grid, x, surface.applyAsInt(x, z), z, x, 12, z, VoxelGrid.OPEN);
            }
        }
        return grid;
    }

    private static void open(VoxelGrid grid, int x0, int y0, int z0, int x1, int y1, int z1, int flags) {
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    grid.set(x, y, z, flags);
                }
            }
        }
    }

    private static int index(VoxelGrid grid, long cell) {
        return grid.index(CellKey.x(cell), CellKey.y(cell), CellKey.z(cell));
    }

    private static int distance(WayPlanner.Walk walk, long cell) {
        return walk.distance(CellKey.x(cell), CellKey.y(cell), CellKey.z(cell));
    }

    private static String text(long cell) {
        return CellKey.x(cell) + " " + CellKey.y(cell) + " " + CellKey.z(cell);
    }
}
