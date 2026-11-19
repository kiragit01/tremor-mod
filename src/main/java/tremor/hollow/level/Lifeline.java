package tremor.hollow.level;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

/**
 * The player's way out through the closing (the owner's rule: the closing never cuts the last open way from the player
 * to an exit; being buried is the end of the time only): the shortest walk, through the copy as it is now, from the
 * player's feet to the way to the node or the escape tunnel. The closing and the moving walls leave its cells open
 * while it is the player's ({@link EventLevel#keepsOpen}), so a dead end the front passes keeps its way back. The walk
 * goes a block aside on the level, a block up with room to jump, down a drop of any height, and up through water; of
 * the walks as short, it takes one that winds at random (the closing's work is ragged, never a straight cut).
 * Plain Java: the copy is asked through {@link Cells}, cells are {@link CellKey}s.
 */
final class Lifeline {
    /** Cells looked at, at most: the way is not looked for farther off (the lifeline found before stays). */
    static final int MAX_CELLS = 6000;
    /** No cell (a key no copy reaches: x at minus 2^25). */
    private static final long NONE = Long.MIN_VALUE;
    private static final int[] DX = {1, 0, -1, 0};
    private static final int[] DZ = {0, 1, 0, -1};

    /** The copy as the walk sees it. */
    interface Cells {
        /** Whether a player passes through the cell unhurt: no collision, no lava or fire. */
        boolean passable(long cell);

        /** Whether the cell is ground to stand on: a collision, and nothing that hurts (magma, cactus). */
        boolean firm(long cell);

        /** Whether the cell holds water: swum up through. */
        boolean water(long cell);
    }

    private Lifeline() {
    }

    /**
     * The cells to keep open for a player with the feet at {@code feet}: the feet and head cells of each step to the
     * first cell {@code exit} accepts (as the feet or the head), the room for each jump, the column of each drop; empty
     * if the player is on the way already; null if no way is found within {@value #MAX_CELLS} cells.
     */
    static Set<Long> find(Cells cells, LongPredicate exit, long feet, int minY) {
        Set<Long> keep = new HashSet<>();
        long start = cells.passable(feet) ? feet : CellKey.above(feet, 1); // on a slab or a path the feet are in it
        while (CellKey.y(start) > minY && !cells.water(start) && cells.passable(CellKey.above(start, -1))) {
            keep.add(start); // in the air: the fall comes first
            start = CellKey.above(start, -1);
        }
        if (exit.test(start) || exit.test(CellKey.above(start, 1))) {
            return Set.of();
        }
        Map<Long, Long> parent = new HashMap<>(Map.of(start, start));
        ArrayDeque<Long> queue = new ArrayDeque<>(Set.of(start));
        long found = NONE;
        while (found == NONE && !queue.isEmpty() && parent.size() < MAX_CELLS) {
            long cell = queue.poll();
            int x = CellKey.x(cell);
            int y = CellKey.y(cell);
            int z = CellKey.z(cell);
            if (cells.water(cell) && cells.passable(CellKey.above(cell, 2))) {
                found = visit(parent, queue, exit, CellKey.above(cell, 1), cell);
            }
            // The sides in an order of the cell's own, so the way kept winds at random rather than in straight runs.
            long hash = cell * 0x9E3779B97F4A7C15L;
            int turn = (int) (hash >>> 62);
            int step = (hash >>> 61 & 1) == 0 ? 1 : 3;
            for (int i = 0; i < 4 && found == NONE; i++) {
                int dir = (turn + i * step) & 3;
                long side = CellKey.of(x + DX[dir], y, z + DZ[dir]);
                long sideUp = CellKey.above(side, 1);
                if (cells.passable(side) && cells.passable(sideUp)) {
                    long land = side;
                    while (CellKey.y(land) > minY && !cells.water(land) && cells.passable(CellKey.above(land, -1))) {
                        land = CellKey.above(land, -1);
                    }
                    if (cells.water(land) || cells.firm(CellKey.above(land, -1))) {
                        found = visit(parent, queue, exit, land, cell);
                    }
                } else if (cells.firm(side) && cells.passable(sideUp) && cells.passable(CellKey.above(side, 2))
                        && cells.passable(CellKey.above(cell, 2))) {
                    found = visit(parent, queue, exit, sideUp, cell);
                }
            }
        }
        if (found == NONE) {
            return null;
        }
        keep.add(start);
        keep.add(CellKey.above(start, 1));
        for (long to = found; to != start; to = parent.get(to)) {
            long from = parent.get(to);
            keep.add(to);
            keep.add(CellKey.above(to, 1));
            if (CellKey.x(to) == CellKey.x(from) && CellKey.z(to) == CellKey.z(from)) {
                continue; // swum up
            }
            if (CellKey.y(to) > CellKey.y(from)) {
                keep.add(CellKey.above(from, 2)); // the room to jump up
            } else {
                for (int y = CellKey.y(to); y <= CellKey.y(from) + 1; y++) {
                    keep.add(CellKey.of(CellKey.x(to), y, CellKey.z(to))); // the column dropped down
                }
            }
        }
        return keep;
    }

    /** Puts {@code next}, reached from {@code from}, on the walk if new: {@code next} if it is on the way, else none. */
    private static long visit(Map<Long, Long> parent, ArrayDeque<Long> queue, LongPredicate exit, long next,
                              long from) {
        if (parent.containsKey(next)) {
            return NONE;
        }
        parent.put(next, from);
        if (exit.test(next) || exit.test(CellKey.above(next, 1))) {
            return next;
        }
        queue.add(next);
        return NONE;
    }
}
