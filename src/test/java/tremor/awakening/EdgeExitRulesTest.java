package tremor.awakening;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tremor.awakening.EdgeExitRules.Cell.DANGER;
import static tremor.awakening.EdgeExitRules.Cell.FLOOR;
import static tremor.awakening.EdgeExitRules.Cell.OPEN;
import static tremor.awakening.EdgeExitRules.Cell.SOLID;
import static tremor.awakening.EdgeExitRules.Cell.WATER;

import org.junit.jupiter.api.Test;
import tremor.hollow.HollowBox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class EdgeExitRulesTest {
    /** Height of the ground's top block: a player stands with the feet at {@code GROUND + 1}. */
    private static final int GROUND = 63;
    /** Where the player was swallowed: the blocks the feet and the head were in. */
    private static final List<EdgeExitRules.Spot> SWALLOW_POINT = List.of(new EdgeExitRules.Spot(0, 64, 0),
            new EdgeExitRules.Spot(0, 65, 0));
    /** The copied box (radius 32, 24 below and above) and a margin. */
    private static final HollowBox WITHIN = new HollowBox(-36, GROUND - 90, -36, 36, GROUND + 30, 36);

    /** Flat ground: rock up to {@link #GROUND}, open above; boxes of other cells set on top of that. */
    private static final class World implements EdgeExitRules.Cells {
        final Map<Long, EdgeExitRules.Cell> cells = new HashMap<>();

        World fill(int x0, int y0, int z0, int x1, int y1, int z1, EdgeExitRules.Cell cell) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        cells.put(key(x, y, z), cell);
                    }
                }
            }
            return this;
        }

        World set(int x, int y, int z, EdgeExitRules.Cell cell) {
            return fill(x, y, z, x, y, z, cell);
        }

        @Override
        public EdgeExitRules.Cell at(int x, int y, int z) {
            return cells.getOrDefault(key(x, y, z), y > GROUND ? OPEN : FLOOR);
        }

        private static long key(int x, int y, int z) {
            return ((long) x & 0xFFFFF) << 40 | ((long) y & 0xFFFFF) << 20 | ((long) z & 0xFFFFF);
        }
    }

    private static EdgeExitRules.Spot spot(int x, int y, int z) {
        return new EdgeExitRules.Spot(x, y, z);
    }

    @Test
    void onTheGroundTheExitIsWhereThePlayerIs() {
        World world = new World();
        assertEquals(spot(31, 64, 0), EdgeExitRules.choose(world, 31, 64, 0, WITHIN, SWALLOW_POINT));
    }

    @Test
    void inMidAirThePlayerIsPutWhereTheFallWouldLand() {
        // Up on the closing (24 above the ground): never dropped from there.
        World world = new World();
        assertEquals(spot(31, 64, 0), EdgeExitRules.choose(world, 31, 64 + 24, 0, WITHIN, SWALLOW_POINT));
        // Over a ravine 40 deep: its floor, though its edge 3 blocks aside is at the height reached.
        World ravine = new World().fill(29, GROUND - 39, -40, 40, GROUND, 40, OPEN);
        assertEquals(spot(31, GROUND - 39, 0), EdgeExitRules.choose(ravine, 31, 64, 0, WITHIN,
                List.of(spot(31, GROUND - 39, 2))));
    }

    @Test
    void inSolidRockThePlayerIsPutOnTopOfIt() {
        // Tunnelled through a hill 5 high in the copy: out on the hill.
        World hill = new World().fill(25, 64, -40, 40, 68, 40, FLOOR);
        assertEquals(spot(31, 69, 0), EdgeExitRules.choose(hill, 31, 64, 0, WITHIN, SWALLOW_POINT));
        // Deep in the rock: nothing within reach, the swallow point then.
        World deep = new World();
        assertNull(EdgeExitRules.choose(deep, 31, 40, 0, WITHIN, SWALLOW_POINT));
    }

    @Test
    void neverIntoWaterLavaOrFire() {
        // A lake where the player came out: the nearest dry spot on its shore.
        World lake = new World().fill(29, GROUND, -2, 40, GROUND, 2, WATER);
        EdgeExitRules.Spot shore = EdgeExitRules.choose(lake, 31, 64, 0, WITHIN, SWALLOW_POINT);
        assertEquals(spot(31, 64, 3), shore == null ? null : spot(31, shore.y(), Math.abs(shore.z())));
        // Lava under the place, fire beside it: neither is a floor or a place to be.
        World lava = new World().set(31, GROUND, 0, DANGER).set(31, 64, 1, DANGER);
        EdgeExitRules.Spot spot = EdgeExitRules.choose(lava, 31, 64, 0, WITHIN, SWALLOW_POINT);
        assertTrue(spot != null && !(spot.x() == 31 && spot.z() == 0) && !(spot.x() == 31 && spot.z() == 1));
        // Water up to the waist everywhere around: no spot at all.
        World flood = new World().fill(-40, 64, -40, 40, 64, 40, WATER);
        assertNull(EdgeExitRules.choose(flood, 31, 64, 0, WITHIN, List.of(spot(0, 65, 0))));
    }

    @Test
    void aSpotNeedsTwoOpenBlocksAndAFloor() {
        World world = new World().set(5, 65, 0, SOLID).set(6, GROUND, 0, SOLID);
        assertTrue(EdgeExitRules.standing(world, 4, 64, 0, WITHIN));
        // A crawl space: no.
        assertFalse(EdgeExitRules.standing(world, 5, 64, 0, WITHIN));
        // On a fence: no.
        assertFalse(EdgeExitRules.standing(world, 6, 64, 0, WITHIN));
        // Not at the bottom of the search.
        assertFalse(EdgeExitRules.standing(world, 4, WITHIN.minY(), 0, WITHIN));
    }

    @Test
    void neverIntoAPlaceTheRealWallsCloseOff() {
        // A vault around the place reached (walls, floor and roof): the copy is no way in, the swallow point it is.
        World vault = new World().fill(27, 64, -4, 35, 68, 4, FLOOR).fill(28, 64, -3, 34, 67, 3, OPEN);
        assertTrue(EdgeExitRules.standing(vault, 31, 64, 0, WITHIN));
        assertNull(EdgeExitRules.choose(vault, 31, 64, 0, WITHIN, SWALLOW_POINT));
        // With a door open (a gap of one block): a way, so the exit is in there.
        vault.set(27, 64, 0, OPEN);
        assertEquals(spot(31, 64, 0), EdgeExitRules.choose(vault, 31, 64, 0, WITHIN, SWALLOW_POINT));
        // The swallow point inside a sealed room: out there, back in the room.
        World room = new World().fill(-3, 64, -3, 3, 68, 3, FLOOR).fill(-2, 64, -2, 2, 67, 2, OPEN);
        assertNull(EdgeExitRules.choose(room, 31, 64, 0, WITHIN, SWALLOW_POINT));
    }

    @Test
    void aWayMayGoThroughWaterButNotLava() {
        // A wall across the whole box, reaching down into a moat 3 deep: the only way is under it, through the water.
        int top = WITHIN.maxY();
        World moat = new World().fill(14, GROUND - 2, -40, 16, GROUND, 40, WATER)
                .fill(15, GROUND - 1, -40, 15, top, 40, FLOOR);
        assertTrue(EdgeExitRules.connected(moat, spot(31, 64, 0), SWALLOW_POINT, WITHIN, EdgeExitRules.MAX_VISITS));
        World lava = new World().fill(14, GROUND - 2, -40, 16, GROUND, 40, DANGER)
                .fill(15, GROUND - 1, -40, 15, top, 40, FLOOR);
        assertFalse(EdgeExitRules.connected(lava, spot(31, 64, 0), SWALLOW_POINT, WITHIN, EdgeExitRules.MAX_VISITS));
    }

    @Test
    void theWayIsGivenUpAfterTheVisits() {
        World world = new World();
        assertTrue(EdgeExitRules.connected(world, spot(31, 64, 0), SWALLOW_POINT, WITHIN, 200));
        // Behind a wall up to the top of the search with a way round at one end: found, unless cut short.
        World wall = new World().fill(15, 64, WITHIN.minZ(), 15, WITHIN.maxY(), 9, FLOOR);
        assertTrue(EdgeExitRules.connected(wall, spot(31, 64, 0), SWALLOW_POINT, WITHIN, EdgeExitRules.MAX_VISITS));
        assertFalse(EdgeExitRules.connected(wall, spot(31, 64, 0), SWALLOW_POINT, WITHIN, 100));
        // A swallow point built over: no way to it.
        World built = new World().fill(0, 64, 0, 0, 65, 0, FLOOR);
        assertFalse(EdgeExitRules.connected(built, spot(31, 64, 0), SWALLOW_POINT, WITHIN, EdgeExitRules.MAX_VISITS));
    }

    @Test
    void theNearestSpotsComeFirst() {
        World world = new World();
        List<EdgeExitRules.Spot> spots = EdgeExitRules.spots(world, 31, 64, 0, WITHIN);
        assertEquals(EdgeExitRules.CANDIDATES, spots.size());
        assertEquals(spot(31, 64, 0), spots.get(0));
        // Outside the box: no column.
        assertNull(EdgeExitRules.inColumn(world, WITHIN.maxX() + 1, 64, 0, WITHIN));
        // Up out of a pillar of the player's in the real world (a block where the feet were).
        World pillar = new World().set(31, 64, 0, FLOOR);
        assertEquals(spot(31, 65, 0), EdgeExitRules.inColumn(pillar, 31, 64, 0, WITHIN));
    }

    @Test
    void neverInTheFootprintOfTheCrater() {
        World world = new World();
        EdgeExitRules.Avoid crater = new EdgeExitRules.Avoid(0, 0, 19);
        // Reached at the edge (31 out): the place itself, outside the footprint.
        assertEquals(spot(31, 64, 0), EdgeExitRules.choose(world, 31, 64, 0, WITHIN, SWALLOW_POINT, crater));
        // Reached at its rim: the spots inside are left out.
        EdgeExitRules.Spot rim = EdgeExitRules.choose(world, 18, 64, 2, WITHIN, SWALLOW_POINT, crater);
        assertTrue(rim != null && !crater.contains(rim.x(), rim.z()), String.valueOf(rim));
        // Fallen out of the bottom of the copy near the swallow point: out just beyond the footprint, that way.
        for (int[] at : new int[][]{{0, 0}, {5, 0}, {-3, 4}, {0, -10}}) {
            EdgeExitRules.Spot out = EdgeExitRules.choose(world, at[0], 64, at[1], WITHIN, SWALLOW_POINT, crater);
            assertTrue(out != null && !crater.contains(out.x(), out.z()), at[0] + " " + at[1] + ": " + out);
            assertTrue(Math.hypot(out.x(), out.z()) <= 19 + EdgeExitRules.AROUND + 2, "far: " + out);
        }
        EdgeExitRules.Spot west = EdgeExitRules.choose(world, -3, 64, 0, WITHIN, SWALLOW_POINT, crater);
        assertTrue(west != null && west.x() < -18, "not the way it was reached: " + west);
        // Water all along that side: round the footprint to a dry spot.
        World lake = new World().fill(20, GROUND, -40, 40, GROUND, 40, WATER);
        EdgeExitRules.Spot dry = EdgeExitRules.choose(lake, 25, 64, 0, WITHIN, SWALLOW_POINT, crater);
        assertTrue(dry != null && !crater.contains(dry.x(), dry.z()) && dry.x() < 20, String.valueOf(dry));
        // Nothing avoided: as before.
        assertEquals(spot(5, 64, 0), EdgeExitRules.choose(world, 5, 64, 0, WITHIN, SWALLOW_POINT, null));
        assertTrue(crater.contains(19, 0) && !crater.contains(19, 1) && !crater.contains(20, 0));
    }

    @Test
    void aClosedOffSwallowPointCostsLittle() {
        // The swallow point in a closed hut, the crater avoided: no spot anywhere around has a way, and the choice
        // finds that out by the flood of the hut, without searching the open land outside from every spot tried.
        World hut = new World().fill(-3, 64, -3, 3, 68, 3, FLOOR).fill(-2, 64, -2, 2, 67, 2, OPEN);
        int[] looks = {0};
        EdgeExitRules.Cells counted = (x, y, z) -> {
            looks[0]++;
            return hut.at(x, y, z);
        };
        EdgeExitRules.Avoid crater = new EdgeExitRules.Avoid(0, 0, 19);
        assertNull(EdgeExitRules.choose(counted, 31, 64, 0, WITHIN, SWALLOW_POINT, crater));
        // Only the columns tried for spots (each of the ring's directions, two heights) and the hut: no search.
        assertTrue(looks[0] < 20_000, "looked at " + looks[0] + " blocks");
        // Open from the swallow point: the same spots as a search from each of them would find.
        World open = new World().fill(-3, 64, -3, 3, 68, 3, FLOOR).fill(-2, 64, -2, 2, 67, 2, OPEN)
                .set(3, 64, 0, OPEN).set(3, 65, 0, OPEN);
        EdgeExitRules.Spot out = EdgeExitRules.choose(open, 31, 64, 0, WITHIN, SWALLOW_POINT, crater);
        assertEquals(spot(31, 64, 0), out);
        assertTrue(EdgeExitRules.connected(open, out, SWALLOW_POINT, WITHIN, EdgeExitRules.MAX_VISITS));
        // A spot in a closed vault while the swallow point is open: its own search ends at once, the next spot is out.
        World vault = new World().fill(27, 64, -4, 35, 68, 4, FLOOR).fill(28, 64, -3, 34, 67, 3, OPEN);
        EdgeExitRules.Spot beside = EdgeExitRules.choose(vault, 31, 64, 0, WITHIN, SWALLOW_POINT, crater);
        assertTrue(beside != null && (beside.x() < 27 || beside.x() > 35 || Math.abs(beside.z()) > 4),
                String.valueOf(beside));
    }
}
