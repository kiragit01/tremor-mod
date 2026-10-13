package tremor.hollow.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class EscapeTunnelTest {
    /** A box of solid rock 65 x 20 x 65 around (0, 10, 0), with a pit under part of it. */
    private static VoxelGrid rock() {
        return new VoxelGrid(-32, 0, -32, 32, 19, 32);
    }

    @Test
    void runsFromTheEntranceToTheEdgeAtFeetAndHeadHeight() {
        for (long seed = 0; seed < 50; seed++) {
            EscapeTunnel tunnel = EscapeTunnel.plan(rock(), 0.5, 0.5, 10, 31, seed, Set.of());
            double nearest = Double.MAX_VALUE;
            double farthest = 0;
            for (long cell : tunnel.carve()) {
                int y = CellKey.y(cell);
                assertTrue(y == 10 || y == 11, "feet or head height");
                double r = Math.hypot(CellKey.x(cell) + 0.5 - 0.5, CellKey.z(cell) + 0.5 - 0.5);
                nearest = Math.min(nearest, r);
                farthest = Math.max(farthest, r);
            }
            assertTrue(nearest < EscapeTunnel.ENTRANCE + 2.5, "starts near the entrance: " + nearest);
            assertTrue(farthest >= 31, "reaches the edge: " + farthest);
        }
    }

    @Test
    void isOneWalkableLineOfCells() {
        EscapeTunnel tunnel = EscapeTunnel.plan(rock(), 0.5, 0.5, 10, 31, 7, Set.of());
        List<Long> feet = tunnel.carve().stream().filter(c -> CellKey.y(c) == 10).toList();
        for (int i = 1; i < feet.size(); i++) {
            int dx = Math.abs(CellKey.x(feet.get(i)) - CellKey.x(feet.get(i - 1)));
            int dz = Math.abs(CellKey.z(feet.get(i)) - CellKey.z(feet.get(i - 1)));
            assertTrue(dx + dz <= 1, "each step goes to a side neighbour, never across a corner");
        }
    }

    @Test
    void floorGoesOnlyWhereTheCopyHadNothingToStandOn() {
        VoxelGrid grid = rock();
        for (int x = -32; x <= 32; x++) {
            for (int z = -32; z <= 32; z++) {
                grid.set(x, 9, z, VoxelGrid.OPEN); // a pit right under the tunnel's height everywhere
            }
        }
        EscapeTunnel tunnel = EscapeTunnel.plan(grid, 0.5, 0.5, 10, 31, 3, Set.of());
        Set<Long> columns = new HashSet<>();
        for (long cell : tunnel.carve()) {
            columns.add(CellKey.of(CellKey.x(cell), 9, CellKey.z(cell)));
        }
        assertEquals(columns, tunnel.floor());
        assertTrue(EscapeTunnel.plan(rock(), 0.5, 0.5, 10, 31, 3, Set.of()).floor().isEmpty(), "solid rock below");
    }

    @Test
    void glowsAtItsEndAndDiesOutBeforeTheEntrance() {
        for (long seed = 0; seed < 50; seed++) {
            EscapeTunnel tunnel = EscapeTunnel.plan(rock(), 0.5, 0.5, 10, 31, seed, Set.of());
            List<Long> feet = tunnel.carve().stream().filter(c -> CellKey.y(c) == 10).toList();
            java.util.Map<Long, Integer> glow = tunnel.glow(0.5, 0.5, 10, 30);
            assertFalse(glow.isEmpty());
            for (java.util.Map.Entry<Long, Integer> light : glow.entrySet()) {
                long cell = light.getKey();
                assertTrue(tunnel.carve().contains(cell), "in the tunnel");
                double r = Math.hypot(CellKey.x(cell), CellKey.z(cell));
                assertTrue(r < 30 && r > 28, "at the end, short of where the player is out: " + r);
                long feetCell = CellKey.of(CellKey.x(cell), 10, CellKey.z(cell));
                int along = feet.indexOf(feetCell);
                assertTrue(light.getValue() <= EscapeTunnel.GLOW && light.getValue() - 1 < along
                        - EscapeTunnel.DARK_ENTRANCE + 1, "dies out before the entrance");
            }
        }
        assertTrue(EscapeTunnel.plan(rock(), 0.5, 0.5, 10, 10, 1, Set.of()).glow(0.5, 0.5, 10, 9).isEmpty(),
                "too short");
        assertTrue(EscapeTunnel.NONE.glow(0.5, 0.5, 10, 30).isEmpty());
    }

    @Test
    void leavesTheWayToTheNodeAsItIs() {
        EscapeTunnel free = EscapeTunnel.plan(rock(), 0.5, 0.5, 10, 31, 11, Set.of());
        long crossing = free.carve().iterator().next();
        EscapeTunnel kept = EscapeTunnel.plan(rock(), 0.5, 0.5, 10, 31, 11, Set.of(crossing));
        assertFalse(kept.carve().contains(crossing));
        assertEquals(free.carve().size() - 1, kept.carve().size());
    }
}
