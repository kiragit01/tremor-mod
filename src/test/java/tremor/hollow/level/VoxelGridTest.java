package tremor.hollow.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Set;

class VoxelGridTest {
    private static final int LAVA = VoxelGrid.OPEN | VoxelGrid.LIQUID | VoxelGrid.HAZARD;
    private static final int WATER = VoxelGrid.OPEN | VoxelGrid.LIQUID;
    private static final int FIRE = VoxelGrid.OPEN | VoxelGrid.HAZARD;

    private static VoxelGrid grid() {
        return new VoxelGrid(0, 0, 0, 4, 4, 4);
    }

    @Test
    void nobodyStandsOnWhatTheGridDoesNotKnow() {
        VoxelGrid grid = grid();
        for (int y = 0; y <= 4; y++) {
            grid.set(2, y, 2, VoxelGrid.OPEN);
        }
        // Open down to the bottom row: what is under it is not known.
        assertFalse(grid.standable(2, 0, 2));
        // ...nor what is over the top row.
        assertFalse(grid.standable(2, 4, 2));
        grid.set(2, 0, 2, 0);
        assertTrue(grid.standable(2, 1, 2));
    }

    @Test
    void nobodyStandsOnAFenceOrInWhatHurts() {
        VoxelGrid grid = grid();
        grid.set(2, 2, 2, VoxelGrid.OPEN);
        grid.set(2, 3, 2, VoxelGrid.OPEN);
        assertTrue(grid.standable(2, 2, 2));
        grid.set(2, 1, 2, VoxelGrid.TALL);
        assertFalse(grid.standable(2, 2, 2), "on a fence");
        grid.set(2, 1, 2, 0);
        grid.set(2, 3, 2, FIRE);
        assertFalse(grid.standable(2, 2, 2), "fire at the head");
        grid.set(2, 3, 2, WATER);
        assertTrue(grid.standable(2, 2, 2), "in water");
    }

    @Test
    void passableAndDry() {
        VoxelGrid grid = grid();
        grid.set(1, 1, 1, VoxelGrid.OPEN);
        grid.set(2, 1, 1, WATER);
        grid.set(3, 1, 1, LAVA);
        assertTrue(grid.passable(1, 1, 1) && grid.passable(2, 1, 1));
        assertFalse(grid.passable(3, 1, 1) || grid.passable(0, 0, 0));
        assertTrue(grid.dry(1, 1, 1) && grid.dry(0, 0, 0));
        assertFalse(grid.dry(2, 1, 1) || grid.dry(3, 1, 1));
        // A waterlogged block: solid, wet.
        grid.set(1, 2, 1, VoxelGrid.LIQUID);
        assertFalse(grid.passable(1, 2, 1) || grid.dry(1, 2, 1));
    }

    @Test
    void aCopyChangesApart() {
        VoxelGrid grid = grid();
        grid.set(1, 1, 1, VoxelGrid.OPEN);
        VoxelGrid copy = grid.copy();
        assertEquals(grid.volume(), copy.volume());
        assertEquals(grid.maxY(), copy.maxY());
        assertTrue(copy.open(1, 1, 1));
        copy.set(2, 2, 2, WATER);
        grid.set(1, 1, 1, 0);
        assertFalse(grid.open(2, 2, 2));
        assertTrue(copy.open(1, 1, 1) && !copy.dry(2, 2, 2));
    }

    @Test
    void theInteriorHasAllItsNeighboursInTheGrid() {
        VoxelGrid grid = grid();
        assertTrue(grid.interior(1, 1, 1) && grid.interior(3, 3, 3));
        assertFalse(grid.interior(0, 2, 2) || grid.interior(2, 4, 2) || grid.interior(2, 2, 0));
    }

    @Test
    void whatLeaksIntoACarvedCellIsSealed() {
        VoxelGrid grid = grid();
        long carved = CellKey.of(2, 2, 2);
        grid.set(2, 3, 2, LAVA);
        grid.set(1, 2, 2, WATER);
        grid.set(3, 2, 2, FIRE);
        grid.set(2, 2, 3, VoxelGrid.LIQUID);
        grid.set(2, 1, 2, VoxelGrid.OPEN);
        grid.set(2, 2, 1, VoxelGrid.HAZARD);
        Set<Long> leaks = grid.leaksAround(Set.of(carved), Set.of());
        // Lava, water, fire and a waterlogged block are sealed; air and a solid magma block stay.
        assertEquals(Set.of(CellKey.of(2, 3, 2), CellKey.of(1, 2, 2), CellKey.of(3, 2, 2), CellKey.of(2, 2, 3)),
                leaks);
        // Neither what is carved too nor what is kept (the way) is sealed.
        assertEquals(Set.of(CellKey.of(3, 2, 2), CellKey.of(2, 2, 3)),
                grid.leaksAround(Set.of(carved, CellKey.of(2, 3, 2)), Set.of(CellKey.of(1, 2, 2))));
    }
}
