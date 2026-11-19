package tremor.hollow.level;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

/** The way out kept from a player in a dead end runs back along the dead end, over its step and drop, to the way. */
class LifelineTest {
    @Test
    void fromTheEndOfADeadEndBackToTheWay() {
        Set<Long> open = new HashSet<>();
        Set<Long> way = new HashSet<>();
        corridor(open, 0, 0, 0, 10, 0, 0); // the way, along x
        way.addAll(open);
        corridor(open, 5, 0, 1, 5, 0, 4); // the dead end off it, along z...
        corridor(open, 5, 1, 5, 5, 1, 7); // ...a block up a step...
        corridor(open, 5, 0, 8, 5, 0, 9); // ...and down again at its end
        open.add(CellKey.of(5, 2, 4)); // the room to jump up the step from either side
        open.add(CellKey.of(5, 2, 8));
        Set<Long> keep = Lifeline.find(cells(open), way::contains, CellKey.of(5, 0, 9), -10);
        assertNotNull(keep);
        for (int z = 1; z <= 9; z++) {
            int y = z >= 5 && z <= 7 ? 1 : 0;
            assertTrue(keep.contains(CellKey.of(5, y, z)) && keep.contains(CellKey.of(5, y + 1, z)), "z " + z);
        }
        assertTrue(keep.contains(CellKey.of(5, 2, 4)) && keep.contains(CellKey.of(5, 2, 8)), "the room to jump");
        for (long cell : keep) {
            assertTrue(open.contains(cell), "only open cells: " + CellKey.x(cell) + " " + CellKey.y(cell) + " "
                    + CellKey.z(cell));
        }
    }

    @Test
    void onTheWayNothingAndSealedInNoneFound() {
        Set<Long> open = new HashSet<>();
        corridor(open, 0, 0, 0, 10, 0, 0);
        Set<Long> way = new HashSet<>(open);
        assertTrue(Lifeline.find(cells(open), way::contains, CellKey.of(3, 0, 0), -10).isEmpty());
        corridor(open, 0, 0, 5, 4, 0, 5); // a pocket with no way to the corridor
        assertNull(Lifeline.find(cells(open), way::contains, CellKey.of(2, 0, 5), -10));
    }

    /** Feet and head cells along a straight run from one cell to the other (feet heights inclusive). */
    private static void corridor(Set<Long> open, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
                for (int y = Math.min(y0, y1); y <= Math.max(y0, y1) + 1; y++) {
                    open.add(CellKey.of(x, y, z));
                }
            }
        }
    }

    /** Solid rock everywhere but the open cells. */
    private static Lifeline.Cells cells(Set<Long> open) {
        return new Lifeline.Cells() {
            @Override
            public boolean passable(long cell) {
                return open.contains(cell);
            }

            @Override
            public boolean firm(long cell) {
                return !open.contains(cell);
            }

            @Override
            public boolean water(long cell) {
                return false;
            }
        };
    }
}
