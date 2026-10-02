package tremor.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ChunkCoverageTest {
    /** A client's loaded chunks, by packed chunk coordinates. */
    private final Set<Long> loaded = new HashSet<>();

    private void load(int chunkX, int chunkZ) {
        loaded.add(key(chunkX, chunkZ));
    }

    private void unload(int chunkX, int chunkZ) {
        loaded.remove(key(chunkX, chunkZ));
    }

    private static long key(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
    }

    private ChunkCoverage cover(int minX, int minZ, int maxX, int maxZ) {
        return ChunkCoverage.of(minX, minZ, maxX, maxZ, (x, z) -> loaded.contains(key(x, z)));
    }

    @Test
    void boxCoversEveryChunkItOverlapsAlsoAtNegativeCoordinates() {
        // Blocks -17..16 lie in chunks -2..1 on each axis: 16 chunks.
        for (int x = -3; x <= 2; x++) {
            for (int z = -3; z <= 2; z++) {
                load(x, z);
            }
        }
        ChunkCoverage all = cover(-17, -17, 16, 16);
        int inBox = 0;
        for (int x = -3; x <= 2; x++) {
            for (int z = -3; z <= 2; z++) {
                inBox += all.has(x, z) ? 1 : 0;
            }
        }
        assertEquals(16, inBox);
        assertTrue(all.has(-2, -2));
        assertTrue(all.has(1, 1));
        assertFalse(all.has(-3, 0), "outside the box");
        assertFalse(all.has(2, 0), "outside the box");
    }

    @Test
    void arrivingChunksAreGainsMissingOnesAreNot() {
        load(0, 0);
        ChunkCoverage first = cover(-20, -20, 40, 40); // chunks -2..2
        assertTrue(first.has(0, 0));
        assertFalse(first.has(1, 0));
        assertFalse(cover(-20, -20, 40, 40).gainedSince(first), "nothing arrived");

        load(2, -1);
        ChunkCoverage second = cover(-20, -20, 40, 40);
        assertTrue(second.gainedSince(first), "a chunk arrived");
        assertFalse(second.gainedSince(second));

        unload(0, 0);
        ChunkCoverage third = cover(-20, -20, 40, 40);
        assertFalse(third.gainedSince(second), "a chunk that went away is no new terrain");
        load(0, 0);
        assertTrue(cover(-20, -20, 40, 40).gainedSince(third), "the same chunk back again is");

        load(5, 5);
        assertFalse(cover(-20, -20, 40, 40).gainedSince(cover(-20, -20, 40, 40)), "outside the box");
    }

    @Test
    void anyLoadedChunkIsAGainOverNothingKnown() {
        assertFalse(cover(0, 0, 15, 15).gainedSince(null));
        load(0, 0);
        assertTrue(cover(0, 0, 15, 15).gainedSince(null));
    }

    @Test
    void sameCountWithOtherChunksIsAGain() {
        load(0, 0);
        ChunkCoverage before = cover(0, 0, 31, 31);
        unload(0, 0);
        load(1, 1);
        ChunkCoverage after = cover(0, 0, 31, 31);
        assertTrue(after.gainedSince(before), "as many chunks, but terrain the scan never saw arrived");
    }
}
