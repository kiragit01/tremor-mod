package tremor.awakening;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tremor.awakening.SinkholeRules.Cell.AIR;
import static tremor.awakening.SinkholeRules.Cell.FLUID;
import static tremor.awakening.SinkholeRules.Cell.KEEP;
import static tremor.awakening.SinkholeRules.Cell.SOLID;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class SinkholeRulesTest {
    /** Height the player stood at: the column is dug from here down. */
    private static final int TOP = 64;

    /** Flat ground: stone below {@link #TOP}, air from it up; single cells set on top of that. */
    private static final class Ground implements SinkholeRules.Cells {
        final Map<String, SinkholeRules.Cell> cells = new HashMap<>();
        final List<Integer> dug = new ArrayList<>();

        Ground set(int x, int y, int z, SinkholeRules.Cell cell) {
            cells.put(x + " " + y + " " + z, cell);
            return this;
        }

        @Override
        public SinkholeRules.Cell at(int x, int y, int z) {
            return cells.getOrDefault(x + " " + y + " " + z, y >= TOP ? AIR : SOLID);
        }

        int dig(int depth) {
            return SinkholeRules.digColumn(this, 0, 0, TOP, depth, y -> {
                dug.add(y);
                set(0, y, 0, AIR);
            });
        }
    }

    @Test
    void flatGroundIsDugTheWholeDepth() {
        Ground ground = new Ground();
        assertEquals(TOP - 6, ground.dig(6));
        assertEquals(List.of(63, 62, 61, 60, 59, 58), ground.dug);
    }

    @Test
    void aPlantWhereThePlayerStoodGoesToo() {
        // Anything that is not air at the top (grass, a carpet, snow) is dug like the ground under it.
        Ground ground = new Ground().set(0, TOP, 0, SOLID);
        assertEquals(TOP - 2, ground.dig(2));
        assertEquals(List.of(64, 63, 62), ground.dug);
    }

    @Test
    void aKeptBlockIsNeitherDugNorUndermined() {
        Ground ground = new Ground().set(0, 61, 0, KEEP);
        assertEquals(62, ground.dig(6));
        assertEquals(List.of(63, 62), ground.dug);
    }

    @Test
    void aKeptBlockAtTheTopStopsTheColumn() {
        Ground ground = new Ground().set(0, TOP, 0, KEEP);
        assertEquals(TOP + 1, ground.dig(6));
        assertTrue(ground.dug.isEmpty());
    }

    @Test
    void noFluidGetsIn() {
        // Water beside a block keeps it (and everything below) where it is.
        Ground beside = new Ground().set(1, 61, 0, FLUID);
        assertEquals(62, beside.dig(6));
        // Lava under a block: the floor stays over it.
        Ground under = new Ground().set(0, 59, 0, FLUID);
        assertEquals(61, under.dig(6));
        assertFalse(under.dug.contains(60));
        // A lake over the ground: nothing.
        Ground lake = new Ground().set(0, TOP, 0, FLUID);
        assertEquals(TOP + 1, lake.dig(6));
        assertTrue(lake.dug.isEmpty());
        // A pond right beside the top block: the column is not opened at all.
        Ground pond = new Ground().set(1, 63, 0, FLUID);
        assertEquals(TOP, pond.dig(6));
        assertTrue(pond.dug.isEmpty());
    }

    @Test
    void neverIntoACave() {
        // A cave from 58 down: the block over it stays as its roof, the bottom stays closed.
        Ground ground = new Ground().set(0, 58, 0, AIR).set(0, 57, 0, AIR);
        assertEquals(60, ground.dig(6));
        assertEquals(List.of(63, 62, 61, 60), ground.dug);
        assertEquals(SOLID, ground.at(0, 59, 0));
        // Right under the bottom: the floor stays.
        Ground deep = new Ground().set(0, 57, 0, AIR);
        assertEquals(59, deep.dig(6));
        assertEquals(SOLID, deep.at(0, 58, 0));
    }

    @Test
    void airAboveTheGroundIsPassedOver() {
        // The player stood on a ledge 2 above the ground: the column starts at the ground.
        Ground ground = new Ground().set(0, 63, 0, AIR).set(0, 62, 0, AIR);
        assertEquals(TOP - 4, ground.dig(4));
        assertEquals(List.of(61, 60), ground.dug);
        // In the air with nothing in reach: nothing dug, the lowest air is the open end.
        Ground sky = new Ground();
        for (int y = 50; y < TOP; y++) {
            sky.set(0, y, 0, AIR);
        }
        assertEquals(TOP - 6, sky.dig(6));
        assertTrue(sky.dug.isEmpty());
    }

    @Test
    void touchesFluidLooksAtAllSixFaces() {
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] face : faces) {
            Ground ground = new Ground().set(face[0], 60 + face[1], face[2], FLUID);
            assertTrue(SinkholeRules.touchesFluid(ground, 0, 60, 0));
        }
        Ground diagonal = new Ground().set(1, 61, 1, FLUID);
        assertFalse(SinkholeRules.touchesFluid(diagonal, 0, 60, 0));
    }
}
