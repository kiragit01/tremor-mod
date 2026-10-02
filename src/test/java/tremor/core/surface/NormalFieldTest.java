package tremor.core.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Random;

import org.junit.jupiter.api.Test;
import tremor.core.math.Vec3;
import tremor.core.testing.ArrayVoxelGrid;

class NormalFieldTest {
    private static ArrayVoxelGrid randomRock(long seed) {
        Random random = new Random(seed);
        ArrayVoxelGrid g = new ArrayVoxelGrid(-6, -6, -6, 6, 6, 6, ArrayVoxelGrid.Outside.SOLID);
        for (int y = -6; y <= 6; y++) {
            for (int z = -6; z <= 6; z++) {
                for (int x = -6; x <= 6; x++) {
                    g.set(x, y, z, random.nextDouble() < 0.55);
                }
            }
        }
        return g;
    }

    private static ArrayVoxelGrid[] scenes() {
        return new ArrayVoxelGrid[]{
                ArrayVoxelGrid.flatFloor(0),
                ArrayVoxelGrid.cave(0, 4),
                ArrayVoxelGrid.wallFacingPlusX(0),
                ArrayVoxelGrid.floorWallCorner(0, 0),
                ArrayVoxelGrid.room(-3, 1, -3, 3, 5, 3),
                ArrayVoxelGrid.step(0, 0),
                ArrayVoxelGrid.thinWallBetweenCaves(0, 6, 0, 1),
                randomRock(1),
                randomRock(2),
        };
    }

    @Test
    void agreesWithSurfaceNormalsEverywhere() {
        for (ArrayVoxelGrid g : scenes()) {
            NormalField field = new NormalField(g);
            // Query in a scrambled order so the caches fill in a different order than a straight sweep.
            for (int pass = 0; pass < 2; pass++) {
                for (int y = 7; y >= -7; y--) {
                    for (int x = -7; x <= 7; x++) {
                        for (int z = 7; z >= -7; z--) {
                            String at = "(" + x + ", " + y + ", " + z + ")";
                            assertEquals(Surface.isSurface(g, x, y, z), field.isSurface(x, y, z), at);
                            assertEquals(SurfaceNormals.rawNormal(g, x, y, z), field.raw(x, y, z), at);
                            assertEquals(SurfaceNormals.smoothNormal(g, x, y, z), field.smooth(x, y, z), at);
                        }
                    }
                }
            }
        }
    }

    @Test
    void cachesUntilCleared() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        NormalField field = new NormalField(g);
        Vec3 before = field.smooth(0, 0, 0);
        assertEquals(0, Vec3.UNIT_Y.distance(before), 1e-12);
        assertSame(before, field.smooth(0, 0, 0));

        // Carve a hole next to the voxel: the cached values are stale until clear().
        g.set(1, 0, 0, false);
        assertSame(before, field.smooth(0, 0, 0));
        field.clear();
        Vec3 after = field.smooth(0, 0, 0);
        assertNotEquals(before, after);
        assertEquals(SurfaceNormals.smoothNormal(g, 0, 0, 0), after);
        assertEquals(Surface.isSurface(g, 0, -1, 0), field.isSurface(0, -1, 0));
    }

    @Test
    void packedKeysDoNotCollideForNearbyOrNegativeCoordinates() {
        long[] keys = {
                NormalField.pack(0, 0, 0), NormalField.pack(-1, 0, 0), NormalField.pack(0, -1, 0),
                NormalField.pack(0, 0, -1), NormalField.pack(1, 0, 0), NormalField.pack(0, 1, 0),
                NormalField.pack(0, 0, 1), NormalField.pack(-30_000_000, -64, 30_000_000),
                NormalField.pack(30_000_000, 319, -30_000_000), NormalField.pack(-30_000_000, 319, 30_000_000),
        };
        for (int i = 0; i < keys.length; i++) {
            for (int j = i + 1; j < keys.length; j++) {
                assertNotEquals(keys[i], keys[j], i + " vs " + j);
            }
        }
    }
}
