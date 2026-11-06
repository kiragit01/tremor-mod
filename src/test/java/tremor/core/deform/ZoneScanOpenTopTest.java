package tremor.core.deform;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.testing.ArrayVoxelGrid;

/**
 * Every block of a floor that is open above moves with the ground of an Awakening or the hollow (the user: a block
 * among pits stayed still as the hill rose under the player, and blocks open on the other side did not breathe).
 */
class ZoneScanOpenTopTest {
    private static Set<Long> scan(ArrayVoxelGrid g, Vec3 center) {
        ZoneScan scan = new ZoneScan(g, center, 10, 8, 8);
        assertTrue(scan.advance(Integer.MAX_VALUE));
        Set<Long> set = new HashSet<>();
        for (SurfacePoint p : scan.points()) {
            set.add(VoxelPos.pack(p.x(), p.y(), p.z()));
        }
        return set;
    }

    private static void assertOpenTopsIn(ArrayVoxelGrid g, Set<Long> found, int r, boolean sealedBelow) {
        List<String> missing = new ArrayList<>();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int y = -6; y <= 0; y++) {
                    if (y < 0 && sealedBelow) {
                        continue;
                    }
                    if (g.isSolid(x, y, z) && !g.isSolid(x, y + 1, z) && !found.contains(VoxelPos.pack(x, y, z))) {
                        missing.add(x + "," + y + "," + z);
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "open above but left still: " + missing);
    }

    @Test
    void aBlockAmongPitsRises() {
        // The user's screenshot: pits three deep beside and diagonal to the block the player stands on.
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        int[][] pits = {{-1, 0}, {1, 1}, {1, -1}, {-1, -1}, {0, 2}, {2, 0}};
        for (int[] p : pits) {
            g.fill(p[0], -3, p[1], p[0], 0, p[1], false);
        }
        assertOpenTopsIn(g, scan(g, new Vec3(0.5, 1, 0.5)), 3, false);
    }

    @Test
    void aFloorOneBlockThickOverACaveBreathes() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        g.fill(-4, -3, -4, 4, -1, 4, false); // a cave right under the floor, sealed from the zone
        assertOpenTopsIn(g, scan(g, new Vec3(0.5, 1, 0.5)), 4, true);
    }

    @Test
    void aWallOneBlockThickMovesOutOfTheFaceARingRunsOver() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        g.fill(3, 1, -4, 3, 4, 4, true); // a wall one block thick, its face at x 4 toward the scan
        ZoneScan plain = new ZoneScan(g, new Vec3(5.5, 2, 0.5), 6, 4, 4);
        ZoneScan ring = new ZoneScan(g, new Vec3(5.5, 2, 0.5), 6, 4, 4, tremor.core.math.Vec3.UNIT_X);
        assertTrue(plain.advance(Integer.MAX_VALUE) && ring.advance(Integer.MAX_VALUE));
        boolean plainHas = plain.points().stream().anyMatch(p -> p.x() == 3 && p.y() == 2 && p.z() == 0);
        SurfacePoint face = ring.points().stream().filter(p -> p.x() == 3 && p.y() == 2 && p.z() == 0).findFirst()
                .orElse(null);
        assertTrue(!plainHas, "no normal of its own: left out of a plain scan");
        assertTrue(face != null && face.normal().equals(tremor.core.math.Vec3.UNIT_X), "out of the face: " + face);
    }

    @Test
    void aRaggedEdgeOverADrop() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        g.fill(3, -6, -6, 8, 0, 6, false); // a drop beyond x 3
        g.set(3, 0, 0, true).set(3, 0, 2, true).set(4, 0, 2, true).set(2, 0, -2, false); // ragged lips
        g.fill(1, -6, 4, 1, -1, 4, false); // a lip open below
        assertOpenTopsIn(g, scan(g, new Vec3(0.5, 1, 0.5)), 3, false);
    }
}
