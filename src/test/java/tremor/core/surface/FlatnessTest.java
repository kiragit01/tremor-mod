package tremor.core.surface;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tremor.core.testing.ArrayVoxelGrid;

class FlatnessTest {
    @Test
    void flatGroundAndASingleStepAreFlat() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        assertTrue(Flatness.flat(g, 0, 0, 0, 2, 1, 0.75));
        g.set(2, 1, 0, true); // one block raised at the edge: still flat
        assertTrue(Flatness.flat(g, 0, 0, 0, 2, 1, 0.75));
    }

    @Test
    void aPitALedgeAndAWallAreNot() {
        ArrayVoxelGrid pit = ArrayVoxelGrid.flatFloor(0);
        pit.fill(-1, -2, -1, 1, 0, 1, false); // a pit 3x3, 3 deep: its floor at -3
        assertFalse(Flatness.flat(pit, 0, -3, 0, 2, 2, 0), "the bottom of a small pit");

        ArrayVoxelGrid ledge = ArrayVoxelGrid.flatFloor(0);
        ledge.fill(2, -6, -6, 8, 0, 6, false); // a drop from x 2 on
        assertFalse(Flatness.flat(ledge, 0, 0, 0, 2.5, 2, 0), "by a ledge's edge");
        assertTrue(Flatness.flat(ledge, -3, 0, 0, 2.5, 2, 0), "back from it");

        ArrayVoxelGrid wall = ArrayVoxelGrid.flatFloor(0);
        wall.fill(1, 1, -3, 1, 3, 3, true);
        assertFalse(Flatness.flat(wall, 0, 0, 0, 2, 2, 0), "against a wall");
    }

    @Test
    void halfOnAStepUpIsNot() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        g.fill(0, 1, -4, 4, 1, 4, true); // a step up of one from x 0 on: the middle on the lower side of its edge
        assertFalse(Flatness.flat(g, -1, 0, 0, 2, 1, 0.75), "half the ground a block up: not level for a listener");
        assertTrue(Flatness.flat(g, -1, 0, 0, 2, 2, 0), "but fine for an ambush");
        g.fill(2, 2, -4, 4, 2, 4, true); // and a second step up
        assertTrue(Flatness.flat(g, -1, 0, 0, 3, 2, 0), "steps of one or two are fine for an ambush (the user)");
    }
}
