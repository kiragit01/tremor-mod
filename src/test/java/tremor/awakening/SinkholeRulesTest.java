package tremor.awakening;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tremor.awakening.SinkholeRules.Cell.AIR;
import static tremor.awakening.SinkholeRules.Cell.FALLING;
import static tremor.awakening.SinkholeRules.Cell.FLUID;
import static tremor.awakening.SinkholeRules.Cell.KEEP;
import static tremor.awakening.SinkholeRules.Cell.LOOSE;
import static tremor.awakening.SinkholeRules.Cell.PLANT;
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
    void whatRestsWhereThePlayerStoodGoesToo() {
        // A plant, a slab or a carpet at the top goes with the ground under it.
        Ground plant = new Ground().set(0, TOP, 0, PLANT);
        assertEquals(TOP - 2, plant.dig(2));
        assertEquals(List.of(64, 63, 62), plant.dug);
        Ground slab = new Ground().set(0, TOP, 0, LOOSE);
        assertEquals(TOP - 2, slab.dig(2));
        // A whole block at the top: dug like the ground.
        Ground block = new Ground().set(0, TOP, 0, SOLID);
        assertEquals(TOP - 2, block.dig(2));
        assertEquals(List.of(64, 63, 62), block.dug);
        // A tall plant or a crop reaching over the top goes first, from its top down.
        Ground tall = new Ground().set(0, TOP, 0, PLANT).set(0, TOP + 1, 0, PLANT);
        assertEquals(TOP - 1, tall.dig(1));
        assertEquals(List.of(65, 64, 63), tall.dug);
        // In a dip lower than the top: the flower on its ground goes, then the ground.
        Ground dip = new Ground().set(0, 63, 0, AIR).set(0, 62, 0, PLANT);
        assertEquals(TOP - 4, dip.dig(4));
        assertEquals(List.of(62, 61, 60), dip.dug);
    }

    @Test
    void nothingIsUndermined() {
        // Uphill: the ground over the top stays, so the column is not touched at all.
        Ground hill = new Ground().set(0, TOP, 0, SOLID).set(0, TOP + 1, 0, SOLID);
        assertEquals(TOP + 1, hill.dig(6));
        assertTrue(hill.dug.isEmpty());
        // Sand over the top block: it would fall in (and open a way for the water behind it).
        Ground sand = new Ground().set(0, TOP, 0, SOLID).set(0, TOP + 1, 0, FALLING);
        assertEquals(TOP + 1, sand.dig(6));
        assertTrue(sand.dug.isEmpty());
        // A kept block standing on the top block (a sign, a bell): it keeps its pillar.
        Ground sign = new Ground().set(0, TOP, 0, SOLID).set(0, TOP + 1, 0, KEEP);
        assertEquals(TOP + 1, sign.dig(6));
        assertTrue(sign.dug.isEmpty());
        // More on top than a tall plant: left alone.
        Ground stack = new Ground().set(0, TOP, 0, SOLID).set(0, TOP + 1, 0, PLANT).set(0, TOP + 2, 0, PLANT)
                .set(0, TOP + 3, 0, PLANT);
        assertEquals(TOP + 1, stack.dig(6));
        assertTrue(stack.dug.isEmpty());
    }

    @Test
    void aKeptBlockIsNeitherDugNorLaidBare() {
        // Buried below: the block on it stays.
        Ground ground = new Ground().set(0, 61, 0, KEEP);
        assertEquals(63, ground.dig(6));
        assertEquals(List.of(63), ground.dug);
        // Beside (a wall sign, a banner fixed to the block, the obsidian of a portal): the block stays.
        Ground beside = new Ground().set(1, 62, 0, KEEP);
        assertEquals(63, beside.dig(6));
        assertEquals(List.of(63), beside.dug);
        // Next to a plant at the top (a chest by the place): the plant may go, nothing can be fixed to it.
        Ground chest = new Ground().set(0, TOP, 0, PLANT).set(1, TOP, 0, KEEP);
        assertEquals(TOP - 2, chest.dig(2));
        // Next to a slab at the top: the slab stays, a sign may hang on it.
        Ground slab = new Ground().set(0, TOP, 0, LOOSE).set(1, TOP, 0, KEEP);
        assertEquals(TOP + 1, slab.dig(2));
        assertTrue(slab.dug.isEmpty());
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
        // Water next to a plant at the top: the plant stays too.
        Ground reed = new Ground().set(0, TOP, 0, PLANT).set(1, TOP, 0, FLUID);
        assertEquals(TOP + 1, reed.dig(6));
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
    void whatHangsIntoACaveIsNoFloor() {
        // Glow lichen (or a cobweb, a torch) under the ceiling of a cave, lava below: the ceiling stays whole.
        Ground lichen = new Ground().set(0, 58, 0, PLANT).set(0, 57, 0, AIR).set(0, 56, 0, AIR).set(0, 55, 0, FLUID);
        assertEquals(60, lichen.dig(6));
        assertEquals(List.of(63, 62, 61, 60), lichen.dug);
        // A fence or a slab under it: no floor either.
        Ground fence = new Ground().set(0, 60, 0, LOOSE);
        assertEquals(62, fence.dig(6));
        // Sand over a cavity would fall; over rock it holds.
        Ground sand = new Ground().set(0, 58, 0, FALLING).set(0, 57, 0, AIR);
        assertEquals(60, sand.dig(6));
        Ground held = new Ground().set(0, 58, 0, FALLING);
        assertEquals(58, held.dig(6));
        assertTrue(SinkholeRules.holds(new Ground().set(0, 58, 0, FALLING), 0, 58, 0));
        assertFalse(SinkholeRules.holds(sand, 0, 58, 0));
        assertFalse(SinkholeRules.holds(lichen, 0, 58, 0));
    }

    @Test
    void neverIntoACaveBeside() {
        // A cave beside the column from 61 down (rock over it): the column stops above it.
        Ground cave = new Ground().set(1, 61, 0, AIR).set(1, 60, 0, AIR);
        assertEquals(62, cave.dig(6));
        assertEquals(List.of(63, 62), cave.dug);
        // A torch on the cave's wall is no wall.
        Ground torch = new Ground().set(0, 61, 1, PLANT).set(0, 61, 2, AIR);
        assertEquals(62, torch.dig(6));
        // Nor is a fence or a slab.
        Ground fence = new Ground().set(-1, 62, 0, LOOSE);
        assertEquals(63, fence.dig(6));
        // Lower ground beside, open from the top (a dip, a slope): no cave, the column goes on.
        Ground dip = new Ground().set(1, 63, 0, AIR).set(1, 62, 0, PLANT);
        assertEquals(58, dip.dig(6));
        assertTrue(SinkholeRules.openFromTop(dip, 1, 62, 0, TOP));
        assertFalse(SinkholeRules.openFromTop(cave, 1, 60, 0, TOP));
        // ...unless a fluid lies in it.
        Ground puddle = new Ground().set(1, 63, 0, AIR).set(2, 63, 0, FLUID);
        assertEquals(TOP, puddle.dig(6));
        assertTrue(puddle.dug.isEmpty());
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
            Ground kept = new Ground().set(face[0], 60 + face[1], face[2], KEEP);
            assertTrue(SinkholeRules.touchesKeep(kept, 0, 60, 0));
        }
        Ground diagonal = new Ground().set(1, 61, 1, FLUID);
        assertFalse(SinkholeRules.touchesFluid(diagonal, 0, 60, 0));
    }
}
