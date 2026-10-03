package tremor.hollow.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

class WideningTest {
    private static final VoxelGrid.Region ANYWHERE = (x, y, z) -> true;

    /** Solid rock 41 x 25 x 41 around the origin. */
    private static VoxelGrid rock() {
        return new VoxelGrid(-20, -12, -20, 20, 12, 20);
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

    @Test
    void aTunnelAndASmallRoomAreTightABigCaveIsNot() {
        VoxelGrid tunnel = rock();
        open(tunnel, -20, 0, 0, 20, 1, 0);
        int inTunnel = Widening.openSpace(tunnel, 0, 0, 0, 8, 100);
        // 17 blocks of a 1x2 tunnel within 8 blocks, two cells each (less at the ends, out of the sphere).
        assertTrue(inTunnel <= 34 && inTunnel >= 30, "open " + inTunnel);
        assertTrue(Widening.needed(inTunnel, 100));

        VoxelGrid room = rock();
        open(room, -1, 0, -1, 1, 2, 1);
        assertEquals(27, Widening.openSpace(room, 0, 0, 0, 8, 100));
        assertTrue(Widening.needed(27, 100));

        VoxelGrid cave = rock();
        open(cave, -10, 0, -10, 10, 5, 10);
        assertEquals(100, Widening.openSpace(cave, 0, 0, 0, 8, 100));
        assertFalse(Widening.needed(100, 100));
        assertFalse(Widening.needed(5, 0), "0 turns the widening off");
    }

    @Test
    void theGameWidensTunnelsUpToSomeFourAcrossAndSmallRoomsIntoACaveSixteenAcross() {
        int threshold = 400;
        VoxelGrid two = rock();
        open(two, -20, 0, 0, 20, 1, 1);
        VoxelGrid three = rock();
        open(three, -20, 0, -1, 20, 2, 1);
        VoxelGrid four = rock();
        open(four, -20, 0, -1, 20, 3, 2);
        VoxelGrid room = rock();
        open(room, -3, 0, -3, 3, 3, 3);
        for (VoxelGrid tight : new VoxelGrid[] {two, three, four, room}) {
            assertTrue(Widening.needed(Widening.openSpace(tight, 0, 0, 0, 8, threshold), threshold));
        }
        // Open ground is roomy.
        VoxelGrid field = rock();
        open(field, -20, 0, -20, 20, 12, 20);
        assertFalse(Widening.needed(Widening.openSpace(field, 0, 0, 0, 8, threshold), threshold));
        // The cave of the game around a 1x2 tunnel: some 16 blocks across, and roomy enough itself.
        for (long seed = 0; seed < 10; seed++) {
            VoxelGrid grid = rock();
            open(grid, -20, 0, 0, 20, 1, 0);
            Widening.Cave cave = Widening.cave(grid, ANYWHERE, 0, 0, 0, 8, 6, seed);
            cave.applyTo(grid);
            int minX = 0;
            int maxX = 0;
            int minZ = 0;
            int maxZ = 0;
            for (long cell : cave.carve()) {
                if (CellKey.z(cell) != 0) {
                    minX = Math.min(minX, CellKey.x(cell));
                    maxX = Math.max(maxX, CellKey.x(cell));
                }
                minZ = Math.min(minZ, CellKey.z(cell));
                maxZ = Math.max(maxZ, CellKey.z(cell));
            }
            assertTrue(maxX - minX + 1 >= 14 && maxZ - minZ + 1 >= 14, "small, seed " + seed + ": "
                    + (maxX - minX + 1) + " x " + (maxZ - minZ + 1));
            assertFalse(Widening.needed(Widening.openSpace(grid, 0, 0, 0, 8, threshold), threshold),
                    "seed " + seed);
        }
    }

    @Test
    void openSpaceIsWhatIsConnected() {
        VoxelGrid grid = rock();
        open(grid, 0, 0, 0, 0, 1, 0);
        // A big cave behind a wall does not count.
        open(grid, 2, 0, -5, 6, 4, 5);
        assertEquals(2, Widening.openSpace(grid, 0, 0, 0, 8, 100));
    }

    /** The cave of the game's size around a player in a 1x2 tunnel along x. */
    private static Widening.Cave caveInATunnel(VoxelGrid grid, long seed) {
        open(grid, -20, 0, 0, 20, 1, 0);
        return Widening.cave(grid, ANYWHERE, 0, 0, 0, 5, 5, seed);
    }

    @Test
    void theCaveIsRoomyWithAFloorAndHeadroomAtThePlayer() {
        for (long seed = 0; seed < 30; seed++) {
            VoxelGrid grid = rock();
            // A gap under part of the tunnel next to the player: the cave gets a floor there.
            grid.set(1, -1, 0, VoxelGrid.OPEN);
            Widening.Cave cave = caveInATunnel(grid, seed);
            cave.applyTo(grid);
            assertTrue(cave.fill().contains(CellKey.of(1, -1, 0)));
            // Around the player: the floor where the player stands, room to jump.
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    assertFalse(grid.open(dx, -1, dz), "a hole next to the player, seed " + seed);
                    for (int dy = 0; dy < Widening.HEADROOM; dy++) {
                        assertTrue(grid.open(dx, dy, dz), "no headroom at " + dx + " " + dy + " " + dz);
                    }
                }
            }
            assertTrue(cave.carve().size() >= 120, "only " + cave.carve().size() + " carved, seed " + seed);
            assertFalse(Widening.needed(Widening.openSpace(grid, 0, 0, 0, 8, 100), 100));
            // Every column of the cave stands on a floor at most a block under the player's.
            Map<Long, Integer> lowest = new HashMap<>();
            for (long cell : cave.carve()) {
                lowest.merge(CellKey.of(CellKey.x(cell), 0, CellKey.z(cell)), CellKey.y(cell), Math::min);
            }
            for (Map.Entry<Long, Integer> column : lowest.entrySet()) {
                int x = CellKey.x(column.getKey());
                int z = CellKey.z(column.getKey());
                int y = column.getValue();
                while (grid.open(x, y - 1, z)) {
                    y--;
                }
                assertTrue(y >= -1, "a hole in the floor at " + x + " " + z + ", seed " + seed);
            }
        }
    }

    @Test
    void theCaveIsNoBox() {
        int dips = 0;
        int rises = 0;
        for (long seed = 0; seed < 10; seed++) {
            VoxelGrid grid = rock();
            Widening.Cave cave = caveInATunnel(grid, seed);
            // Columns: the floor (lowest carved cell) and the roof (one over the highest), off the tunnel.
            Map<Long, int[]> columns = new HashMap<>();
            for (long cell : cave.carve()) {
                int y = CellKey.y(cell);
                columns.merge(CellKey.of(CellKey.x(cell), 0, CellKey.z(cell)), new int[] {y, y + 1},
                        (a, b) -> new int[] {Math.min(a[0], b[0]), Math.max(a[1], b[1])});
            }
            Set<Integer> roofs = new HashSet<>();
            int offDisc = 0;
            for (int z = -10; z <= 10; z++) {
                for (int x = -10; x <= 10; x++) {
                    offDisc += x * x + z * z < 25 != columns.containsKey(CellKey.of(x, 0, z)) ? 1 : 0;
                }
            }
            int minX = 0;
            int maxX = 0;
            int minZ = 0;
            int maxZ = 0;
            for (Map.Entry<Long, int[]> column : columns.entrySet()) {
                int x = CellKey.x(column.getKey());
                int z = CellKey.z(column.getKey());
                roofs.add(column.getValue()[1]);
                if (z != 0) {
                    dips += column.getValue()[0] < 0 ? 1 : 0;
                    rises += column.getValue()[0] > 0 ? 1 : 0;
                }
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
            assertTrue(roofs.size() >= 4, "a flat roof, seed " + seed + ": " + roofs);
            assertTrue(maxX - minX + 1 >= 8 && maxZ - minZ + 1 >= 8, "small, seed " + seed);
            // Not a disc around the player: off the middle, the wall in and out.
            assertTrue(offDisc >= 6, "round, seed " + seed);
        }
        assertTrue(dips > 0 && rises > 0, "a flat floor: " + dips + " dips, " + rises + " rises");
    }

    @Test
    void theCaveIsSealedFromLavaAndWater() {
        for (long seed = 0; seed < 10; seed++) {
            VoxelGrid grid = rock();
            // A lava lake beside the player and water in the rock above.
            for (int z = -20; z <= 20; z++) {
                for (int x = 2; x <= 20; x++) {
                    for (int y = -1; y <= 1; y++) {
                        grid.set(x, y, z, VoxelGrid.OPEN | VoxelGrid.LIQUID | VoxelGrid.HAZARD);
                    }
                }
                for (int x = -20; x <= 20; x++) {
                    grid.set(x, 4, z, VoxelGrid.OPEN | VoxelGrid.LIQUID);
                }
            }
            Widening.Cave cave = caveInATunnel(grid, seed);
            cave.applyTo(grid);
            int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (long cell : cave.carve()) {
                for (int[] face : faces) {
                    int flags = grid.flags(CellKey.x(cell) + face[0], CellKey.y(cell) + face[1],
                            CellKey.z(cell) + face[2]);
                    assertEquals(0, flags & (VoxelGrid.LIQUID | VoxelGrid.HAZARD), "a leak next to "
                            + CellKey.x(cell) + " " + CellKey.y(cell) + " " + CellKey.z(cell) + ", seed " + seed);
                }
            }
        }
    }

    @Test
    void theCaveStaysInTheRegion() {
        VoxelGrid grid = rock();
        VoxelGrid.Region region = (x, y, z) -> x <= 1 && y <= 2;
        Widening.Cave cave = Widening.cave(grid, region, 0, 0, 0, 5, 5, 7);
        assertFalse(cave.carve().isEmpty());
        for (long cell : cave.carve()) {
            assertTrue(CellKey.x(cell) <= 1 && CellKey.y(cell) <= 2);
        }
        for (long cell : cave.fill()) {
            assertTrue(CellKey.x(cell) <= 1 && CellKey.y(cell) <= 2);
        }
    }

    @Test
    void theShapeDependsOnTheSeedOnly() {
        assertEquals(Widening.cave(rock(), ANYWHERE, 0, 0, 0, 5, 5, 5).carve(),
                Widening.cave(rock(), ANYWHERE, 0, 0, 0, 5, 5, 5).carve());
        assertNotEquals(Widening.cave(rock(), ANYWHERE, 0, 0, 0, 5, 5, 5).carve(),
                Widening.cave(rock(), ANYWHERE, 0, 0, 0, 5, 5, 6).carve());
    }
}
