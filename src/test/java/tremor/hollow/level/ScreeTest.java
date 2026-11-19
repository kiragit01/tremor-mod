package tremor.hollow.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;

/**
 * No pit is left to be stuck in: after the scree every place a player walks or falls to from the start walks back to
 * it, checked here with a walk of its own (the planning's, falls of any height added), and flat ground gets nothing.
 */
class ScreeTest {
    private static final VoxelGrid.Region ALL = (x, y, z) -> x * x + z * z <= 24 * 24;
    private static final long PLAYER = CellKey.of(0, 0, 0);

    @Test
    void aRavineGetsAHeapToClimbOutBy() {
        VoxelGrid grid = field();
        open(grid, 3, -12, -32, 4, -1, 32);
        assertTrue(stuck(grid) > 0, "the ravine is a trap before");
        Scree.Result result = Scree.lay(grid, ALL, PLAYER, List.of(), Set.of(), 5);
        assertEquals(0, result.left());
        assertTrue(result.heaps() >= 1, "a heap: " + result);
        assertEquals(0, stuck(grid));
    }

    @Test
    void aShaftAndACaveUnderAHoleLeaveNoTrap() {
        for (long seed = 0; seed < 10; seed++) {
            VoxelGrid grid = field();
            open(grid, 6, -7, 6, 6, -1, 6); // a shaft a block across
            open(grid, -12, -10, -4, -4, -6, 4); // a cave...
            open(grid, -8, -5, 0, -8, -1, 0); // ...and the hole down into it
            Scree.Result result = Scree.lay(grid, ALL, PLAYER, List.of(), Set.of(), seed);
            assertEquals(0, result.left(), "seed " + seed + ": " + result);
            assertEquals(0, stuck(grid), "seed " + seed);
        }
    }

    @Test
    void flatGroundAndKeptCellsAreLeftAlone() {
        VoxelGrid grid = field();
        assertTrue(Scree.lay(grid, ALL, PLAYER, List.of(), Set.of(), 1).fill().isEmpty());
        VoxelGrid pit = field();
        open(pit, 5, -6, -2, 9, -1, 2);
        Set<Long> keep = Set.of(CellKey.of(5, -6, 0));
        Scree.Result result = Scree.lay(pit, ALL, PLAYER, List.of(), keep, 1);
        assertTrue(!result.fill().isEmpty() && !result.fill().contains(CellKey.of(5, -6, 0)), result.toString());
    }

    /** Cells walked or fallen to from the player that do not walk back to the player. */
    private static int stuck(VoxelGrid grid) {
        boolean[] back = WalkBack.to(grid, List.of(PLAYER));
        boolean[] seen = new boolean[grid.volume()];
        ArrayDeque<Long> queue = new ArrayDeque<>(List.of(PLAYER));
        seen[grid.index(0, 0, 0)] = true;
        int stuck = 0;
        while (!queue.isEmpty()) {
            long cell = queue.poll();
            int x = CellKey.x(cell);
            int y = CellKey.y(cell);
            int z = CellKey.z(cell);
            stuck += back[grid.index(x, y, z)] ? 0 : 1;
            for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int ny = Scree.fall(grid, x, y, z, x + d[0], z + d[1]);
                if (ny != Integer.MIN_VALUE && !seen[grid.index(x + d[0], ny, z + d[1])]) {
                    seen[grid.index(x + d[0], ny, z + d[1])] = true;
                    queue.add(CellKey.of(x + d[0], ny, z + d[1]));
                }
            }
        }
        return stuck;
    }

    /** Rock, open from y 0 up: the player stands at the origin. */
    private static VoxelGrid field() {
        VoxelGrid grid = new VoxelGrid(-32, -20, -32, 32, 12, 32);
        open(grid, -32, 0, -32, 32, 12, 32);
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
}
