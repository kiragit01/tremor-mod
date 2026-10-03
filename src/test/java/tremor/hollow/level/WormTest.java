package tremor.hollow.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

class WormTest {
    private static final VoxelGrid.Region ANYWHERE = (x, y, z) -> true;

    private static VoxelGrid rock() {
        return new VoxelGrid(-30, -16, -30, 30, 10, 30);
    }

    /** Flat open ground: solid below 0, open from 0 up. */
    private static VoxelGrid field() {
        VoxelGrid grid = rock();
        open(grid, -30, 0, -30, 30, 10, 30);
        return grid;
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

    /** A step of the path: a block aside, at most a block up or down. Returns its change of height. */
    private static int step(long from, long to) {
        assertEquals(1, Math.abs(CellKey.x(to) - CellKey.x(from)) + Math.abs(CellKey.z(to) - CellKey.z(from)),
                "not a block aside");
        int dy = CellKey.y(to) - CellKey.y(from);
        assertTrue(Math.abs(dy) <= 1, "a step of " + dy);
        return dy;
    }

    @Test
    void theWayToTheNodeGoesDownAWalkableThroatUntilItIsDeep() {
        for (long seed = 0; seed < 20; seed++) {
            VoxelGrid grid = field();
            Worm worm = new Worm(grid, ANYWHERE, Set.of(), Set.of(), seed, new Random(seed));
            Worm.Dig dig = worm.dig(0, 0, 0, 0, 30, Worm.Slope.DEEP, 0);
            List<Long> path = dig.path();
            assertEquals(30, dig.steps());
            int lastDrop = -Worm.THROAT_STEPS;
            for (int i = 1; i < path.size(); i++) {
                if (step(path.get(i - 1), path.get(i)) < 0) {
                    assertTrue(i - lastDrop >= Worm.THROAT_STEPS, "too steep at step " + i + ", seed " + seed);
                    lastDrop = i;
                }
            }
            assertEquals(-1, CellKey.y(path.get(1)), "the throat starts at once");
            long end = dig.end();
            assertTrue(worm.roof(CellKey.x(end), CellKey.y(end), CellKey.z(end), dig.cells()) > WayPlanner.COVER,
                    "not deep at the end, seed " + seed);
            // Dug out, it is walked from its start to its end.
            for (long cell : dig.cells()) {
                grid.carve(cell);
            }
            int steps = WayPlanner.distances(grid, 0, 0, 0, 200).distance(CellKey.x(end), CellKey.y(end),
                    CellKey.z(end));
            assertTrue(steps > 0 && steps <= dig.steps(), "seed " + seed + ": " + steps + " steps");
        }
    }

    @Test
    void aDecoyThroatGoesDownUntilItHasARoof() {
        for (long seed = 0; seed < 10; seed++) {
            VoxelGrid grid = field();
            Worm worm = new Worm(grid, ANYWHERE, Set.of(), Set.of(), seed, new Random(seed));
            Worm.Dig dig = worm.dig(0, 0, 0, 1, 16, Worm.Slope.ROOFED, 0);
            long end = dig.end();
            assertTrue(worm.roof(CellKey.x(end), CellKey.y(end), CellKey.z(end), dig.cells()) >= Worm.ROOF);
            for (long cell : dig.path()) {
                assertTrue(CellKey.y(cell) <= 0, "up out of the ground, seed " + seed);
            }
        }
    }

    @Test
    void aDeadEndKeepsAwayFromTheWaysOnceOffItsFork() {
        for (long seed = 0; seed < 20; seed++) {
            VoxelGrid grid = rock();
            // The way: a tunnel two wide and three high along x.
            Set<Long> way = new HashSet<>();
            for (int x = -25; x <= 25; x++) {
                for (int z = 0; z <= 1; z++) {
                    for (int y = 0; y <= 2; y++) {
                        grid.set(x, y, z, VoxelGrid.OPEN);
                        way.add(CellKey.of(x, y, z));
                    }
                }
            }
            int grace = 3;
            Worm worm = new Worm(grid, ANYWHERE, way, way, seed, new Random(seed));
            Worm.Dig dig = worm.dig(5, 0, 1, Math.PI / 2, 20, Worm.Slope.LEVEL, grace);
            assertTrue(dig.steps() >= 8, "stuck, seed " + seed);
            for (long cell : dig.cells()) {
                int x = CellKey.x(cell);
                int y = CellKey.y(cell);
                int z = CellKey.z(cell);
                assertFalse(way.contains(CellKey.above(cell, 1)) && !way.contains(cell),
                        "dug the floor of the way, seed " + seed);
                // The first steps (and the side of their sections) may run by the way, the rest keep a block away.
                if (Math.max(Math.abs(x - 5), Math.abs(z - 1)) <= grace + 1) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            assertFalse(way.contains(CellKey.of(x + dx, y + dy, z + dz)),
                                    "next to the way at " + x + " " + y + " " + z + ", seed " + seed);
                        }
                    }
                }
            }
            // A dead end never climbs above where it forked off.
            for (long cell : dig.path()) {
                assertTrue(CellKey.y(cell) <= 0, "above the fork, seed " + seed);
            }
        }
    }

    @Test
    void aWormStaysInTheRegionAndEndsEarlyIfItMust() {
        VoxelGrid grid = rock();
        open(grid, 0, 0, 0, 0, 1, 0);
        VoxelGrid.Region small = (x, y, z) -> Math.abs(x) <= 2 && Math.abs(z) <= 2 && Math.abs(y) <= 4;
        Worm worm = new Worm(grid, small, Set.of(), Set.of(), 3, new Random(3));
        Worm.Dig dig = worm.dig(0, 0, 0, 0, 60, Worm.Slope.LEVEL, 0);
        assertTrue(dig.steps() < 60, "a worm of 60 steps in a box of 5");
        for (long cell : dig.cells()) {
            assertTrue(small.allows(CellKey.x(cell), CellKey.y(cell), CellKey.z(cell)));
        }
    }

    @Test
    void aWormAndItsChamberAreWalkedBackFromEverywhere() {
        // A later section never digs out the floor of an earlier one, nor does the chamber at its end: no drop of two
        // or three that the walk cannot climb back up (rare, so many worms: a way to the node dug out its own floor
        // in some 1 of 60 before).
        for (long seed = 0; seed < 1000; seed++) {
            boolean open = seed % 2 == 0;
            VoxelGrid grid = open ? field() : rock();
            if (!open) {
                open(grid, 0, 0, 0, 0, 1, 0);
            }
            Random random = new Random(seed);
            Worm worm = new Worm(grid, ANYWHERE, Set.of(), Set.of(), seed, random);
            Worm.Slope slope = open ? (seed % 4 == 0 ? Worm.Slope.DEEP : Worm.Slope.ROOFED) : Worm.Slope.LEVEL;
            Worm.Dig dig = worm.dig(0, 0, 0, random.nextDouble() * 2 * Math.PI, open ? 40 : 20, slope, 0);
            long end = dig.end();
            Set<Long> cells = new HashSet<>(dig.cells());
            cells.addAll(worm.chamber(CellKey.x(end), CellKey.y(end), CellKey.z(end), 2.6, 3.6, false, dig.cells()));
            for (long cell : cells) {
                grid.carve(cell);
            }
            WayPlanner.Walk walk = WayPlanner.distances(grid, 0, 0, 0, 200);
            boolean[] back = WalkBack.to(grid, List.of(CellKey.of(0, 0, 0)));
            for (long cell : cells) {
                int x = CellKey.x(cell);
                int y = CellKey.y(cell);
                int z = CellKey.z(cell);
                assertTrue(walk.distance(x, y, z) < 0 || back[grid.index(x, y, z)],
                        slope + ", seed " + seed + ": no way back from " + x + " " + y + " " + z);
            }
        }
    }

    @Test
    void theChamberIsADomeOnTheFloor() {
        Worm worm = new Worm(rock(), ANYWHERE, Set.of(), Set.of(), 1, new Random(1));
        Set<Long> chamber = worm.chamber(0, 0, 0, 2.6, 3.6, false, Set.of());
        assertTrue(chamber.contains(CellKey.of(0, 0, 0)) && chamber.contains(CellKey.of(0, 3, 0)));
        assertTrue(chamber.contains(CellKey.of(2, 0, 0)) && chamber.contains(CellKey.of(-2, 0, 1)));
        assertFalse(chamber.contains(CellKey.of(0, 4, 0)) || chamber.contains(CellKey.of(3, 0, 0))
                || chamber.contains(CellKey.of(0, -1, 0)));
        assertFalse(chamber.contains(CellKey.of(2, 3, 0)), "a dome, not a box");
    }
}
