package tremor.hollow.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WideningTest {
    private static final VoxelGrid.Region ANYWHERE = (x, y, z) -> true;

    /** Solid rock 41 x 21 x 41 around the origin. */
    private static VoxelGrid rock() {
        return new VoxelGrid(-20, -10, -20, 20, 10, 20);
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
    void openSpaceIsWhatIsConnected() {
        VoxelGrid grid = rock();
        open(grid, 0, 0, 0, 0, 1, 0);
        // A big cave behind a wall does not count.
        open(grid, 2, 0, -5, 6, 4, 5);
        assertEquals(2, Widening.openSpace(grid, 0, 0, 0, 8, 100));
    }

    @Test
    void theCaveIsSomeSevenBlocksAcrossOnAFloor() {
        VoxelGrid grid = rock();
        open(grid, -20, 0, 0, 20, 1, 0);
        // A gap under part of the tunnel: the cave gets a floor there.
        grid.set(1, -1, 0, VoxelGrid.OPEN);
        Widening.Cave cave = Widening.cave(grid, ANYWHERE, 0, 0, 0, 3.5, 4, 42);
        cave.applyTo(grid);
        assertTrue(grid.open(0, 0, 0) && grid.open(0, 1, 0));
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long cell : cave.carve()) {
            int y = CellKey.y(cell);
            assertTrue(y >= 0 && y <= 5, "cell at y " + y);
            if (y == 0) {
                minX = Math.min(minX, CellKey.x(cell));
                maxX = Math.max(maxX, CellKey.x(cell));
                minZ = Math.min(minZ, CellKey.z(cell));
                maxZ = Math.max(maxZ, CellKey.z(cell));
            }
        }
        // The tunnel itself was open already: measure across it.
        int across = maxZ - minZ + 1;
        assertTrue(across >= 5 && across <= 10, "across " + across);
        assertTrue(maxX - minX + 1 >= 5, "along " + (maxX - minX + 1));
        assertTrue(cave.floor().contains(CellKey.of(1, -1, 0)));
        assertFalse(grid.open(1, -1, 0));
        // Standing room: the player is in a cave now, not a tunnel.
        assertFalse(Widening.needed(Widening.openSpace(grid, 0, 0, 0, 8, 100), 100));
        for (long cell : cave.carve()) {
            assertFalse(grid.open(CellKey.x(cell), CellKey.y(cell) - 1, CellKey.z(cell))
                    && CellKey.y(cell) == 0, "a hole in the floor at " + CellKey.x(cell) + " " + CellKey.z(cell));
        }
    }

    @Test
    void theCaveStaysInTheRegion() {
        VoxelGrid grid = rock();
        VoxelGrid.Region region = (x, y, z) -> x <= 1 && y <= 2;
        Widening.Cave cave = Widening.cave(grid, region, 0, 0, 0, 3.5, 4, 7);
        assertFalse(cave.carve().isEmpty());
        for (long cell : cave.carve()) {
            assertTrue(CellKey.x(cell) <= 1 && CellKey.y(cell) <= 2);
        }
    }

    @Test
    void theShapeDependsOnTheSeedOnly() {
        assertEquals(Widening.cave(rock(), ANYWHERE, 0, 0, 0, 3.5, 4, 5).carve(),
                Widening.cave(rock(), ANYWHERE, 0, 0, 0, 3.5, 4, 5).carve());
    }
}
