package tremor.client.render;

import java.util.BitSet;

/**
 * Which chunks of a box of blocks the client has loaded at one moment, for {@link ZoneColumns}: the scan of a zone
 * reads terrain that is not loaded yet as unknown ground, so it is scanned again once chunks of its box arrive (a
 * player joins near a running Awakening, respawns or teleports there, or chunks of the zone are re-sent). Pure logic,
 * no game classes.
 */
final class ChunkCoverage {
    /** Whether the client has a chunk loaded now. */
    @FunctionalInterface
    interface Loaded {
        boolean isLoaded(int chunkX, int chunkZ);
    }

    private final int minChunkX, minChunkZ, maxChunkX, maxChunkZ;
    /** Loaded chunks of the box, row by row (x fastest). */
    private final BitSet loaded;
    private final int count;

    private ChunkCoverage(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, BitSet loaded) {
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkX = maxChunkX;
        this.maxChunkZ = maxChunkZ;
        this.loaded = loaded;
        this.count = loaded.cardinality();
    }

    /** The chunks overlapping the blocks {@code [minX..maxX] x [minZ..maxZ]} that {@code loaded} has now. */
    static ChunkCoverage of(int minX, int minZ, int maxX, int maxZ, Loaded loaded) {
        int x0 = minX >> 4, z0 = minZ >> 4, x1 = Math.max(x0, maxX >> 4), z1 = Math.max(z0, maxZ >> 4);
        int width = x1 - x0 + 1;
        BitSet bits = new BitSet(width * (z1 - z0 + 1));
        for (int cz = z0; cz <= z1; cz++) {
            for (int cx = x0; cx <= x1; cx++) {
                if (loaded.isLoaded(cx, cz)) {
                    bits.set((cz - z0) * width + (cx - x0));
                }
            }
        }
        return new ChunkCoverage(x0, z0, x1, z1, bits);
    }

    /** Whether the chunk is in the box and was loaded. */
    boolean has(int chunkX, int chunkZ) {
        if (chunkX < minChunkX || chunkX > maxChunkX || chunkZ < minChunkZ || chunkZ > maxChunkZ) {
            return false;
        }
        return loaded.get((chunkZ - minChunkZ) * (maxChunkX - minChunkX + 1) + (chunkX - minChunkX));
    }

    /**
     * Whether some chunk loaded here was not loaded in {@code earlier} (or {@code earlier} is null and some chunk is
     * loaded here): terrain has arrived since then. Chunks that went away do not count.
     */
    boolean gainedSince(ChunkCoverage earlier) {
        if (earlier == null) {
            return count > 0;
        }
        int width = maxChunkX - minChunkX + 1;
        for (int i = loaded.nextSetBit(0); i >= 0; i = loaded.nextSetBit(i + 1)) {
            if (!earlier.has(minChunkX + i % width, minChunkZ + i / width)) {
                return true;
            }
        }
        return false;
    }
}
