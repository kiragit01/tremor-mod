package tremor.core.voxel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tremor.core.VoxelView;
import tremor.core.testing.ArrayVoxelGrid;

class VoxelCacheTest {
    private static final int READS_PER_SECTION = 2 * 16 * 16 * 16;

    /** A grid seen through a view with scattered unknown voxels (which read as solid), counting solid/known reads. */
    private static final class CountingView implements VoxelView {
        final ArrayVoxelGrid grid;
        final long unknownSeed;
        int reads;

        CountingView(ArrayVoxelGrid grid, long unknownSeed) {
            this.grid = grid;
            this.unknownSeed = unknownSeed;
        }

        boolean unknown(int x, int y, int z) {
            return unknownSeed != 0 && Math.floorMod(mix(x, y, z, unknownSeed), 7) == 0;
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            reads++;
            return unknown(x, y, z) || grid.isSolid(x, y, z);
        }

        @Override
        public boolean isKnown(int x, int y, int z) {
            reads++;
            return !unknown(x, y, z);
        }

        @Override
        public float conductivity(int x, int y, int z) {
            return grid.conductivity(x, y, z);
        }

        @Override
        public boolean isProtected(int x, int y, int z) {
            return grid.isProtected(x, y, z);
        }
    }

    private static long mix(int x, int y, int z, long seed) {
        long h = seed ^ x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ z * 0x165667B19E3779F9L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ h >>> 32;
    }

    private static ArrayVoxelGrid randomGrid(Random random) {
        ArrayVoxelGrid.Outside outside = ArrayVoxelGrid.Outside.values()[random.nextInt(3)];
        ArrayVoxelGrid g = new ArrayVoxelGrid(-21, -19, -17, 18, 20, 22, outside);
        double density = 0.2 + 0.6 * random.nextDouble();
        for (int y = g.minY; y <= g.maxY; y++) {
            for (int z = g.minZ; z <= g.maxZ; z++) {
                for (int x = g.minX; x <= g.maxX; x++) {
                    g.set(x, y, z, random.nextDouble() < density);
                    if (random.nextInt(50) == 0) {
                        g.setConductivity(x, y, z, random.nextFloat() * 3);
                        g.setProtected(x, y, z, random.nextBoolean());
                    }
                }
            }
        }
        return g;
    }

    private static void assertSameAnswers(VoxelView expected, VoxelView actual, int x, int y, int z) {
        String at = "(" + x + ", " + y + ", " + z + ")";
        assertEquals(expected.isSolid(x, y, z), actual.isSolid(x, y, z), at);
        assertEquals(expected.isKnown(x, y, z), actual.isKnown(x, y, z), at);
        assertEquals(expected.isOpen(x, y, z), actual.isOpen(x, y, z), at);
    }

    @Test
    void answersLikeTheSourceOnRandomGrids() {
        Random random = new Random(11);
        for (int round = 0; round < 12; round++) {
            ArrayVoxelGrid g = randomGrid(random);
            CountingView source = new CountingView(g, round % 3 == 0 ? 0 : random.nextLong() | 1);
            VoxelCache cache = new VoxelCache(source);
            assertSame(source, cache.source());
            // Random order first (many section switches), then a sweep that also leaves the grid box.
            for (int i = 0; i < 20000; i++) {
                int x = random.nextInt(60) - 30, y = random.nextInt(60) - 30, z = random.nextInt(60) - 30;
                assertSameAnswers(source, cache, x, y, z);
                assertEquals(g.conductivity(x, y, z), cache.conductivity(x, y, z));
                assertEquals(g.isProtected(x, y, z), cache.isProtected(x, y, z));
            }
            for (int y = 26; y >= -26; y--) {
                for (int x = -26; x <= 26; x++) {
                    for (int z = 26; z >= -26; z--) {
                        assertSameAnswers(source, cache, x, y, z);
                    }
                }
            }
        }
    }

    @Test
    void readsEachSectionOnceInOneSweep() {
        CountingView source = new CountingView(randomGrid(new Random(3)), 0);
        VoxelCache cache = new VoxelCache(source);
        assertEquals(0, cache.sectionCount());
        cache.isSolid(5, 5, 5);
        assertEquals(READS_PER_SECTION, source.reads);
        assertEquals(1, cache.sectionCount());
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    cache.isSolid(x, y, z);
                    cache.isKnown(x, y, z);
                }
            }
        }
        assertEquals(READS_PER_SECTION, source.reads);
        // Negative neighbour section: arithmetic shifts put -1 into section -1, not 0.
        cache.isKnown(-1, 0, 0);
        assertEquals(2 * READS_PER_SECTION, source.reads);
        assertEquals(2, cache.sectionCount());
        cache.isSolid(-16, 15, 15);
        cache.isSolid(0, 0, 0);
        cache.isSolid(-7, 3, 9);
        assertEquals(2 * READS_PER_SECTION, source.reads);
    }

    @Test
    void staysStaleUntilInvalidated() {
        ArrayVoxelGrid g = new ArrayVoxelGrid(-40, -40, -40, 40, 40, 40, false);
        CountingView source = new CountingView(g, 0);
        VoxelCache cache = new VoxelCache(source);
        assertFalse(cache.isSolid(-3, 7, 20));
        assertFalse(cache.isSolid(30, 7, 20));
        int reads = source.reads;

        g.set(-3, 7, 20, true);
        assertFalse(cache.isSolid(-3, 7, 20), "the cache does not notice changes by itself");
        cache.invalidate(-3, 7, 20);
        assertEquals(1, cache.sectionCount());
        assertTrue(cache.isSolid(-3, 7, 20));
        assertEquals(reads + READS_PER_SECTION, source.reads, "only the invalidated section is read again");

        cache.invalidate(100, 100, 100); // not cached: nothing happens
        assertEquals(2, cache.sectionCount());
    }

    private static VoxelCache cacheWith125Sections(VoxelView source) {
        VoxelCache cache = new VoxelCache(source);
        for (int sy = -2; sy <= 2; sy++) {
            for (int sz = -2; sz <= 2; sz++) {
                for (int sx = -2; sx <= 2; sx++) {
                    cache.isSolid(sx * 16 + 3, sy * 16 + 3, sz * 16 + 3);
                }
            }
        }
        assertEquals(125, cache.sectionCount());
        return cache;
    }

    @Test
    void invalidateBoxForgetsExactlyTheOverlappingSections() {
        ArrayVoxelGrid g = new ArrayVoxelGrid(-64, -64, -64, 64, 64, 64, false);
        VoxelCache cache = cacheWith125Sections(g);
        // Bounds in any order; sections x in {0, 1}, y in {-2, -1, 0}, z in {-1, 0}.
        cache.invalidateBox(17, 5, 0, 0, -20, -1);
        assertEquals(125 - 2 * 3 * 2, cache.sectionCount());
        // Changes inside the forgotten sections are seen, others are still stale.
        g.set(17, -20, -1, true);
        g.set(-1, 0, 0, true);
        assertTrue(cache.isSolid(17, -20, -1));
        assertFalse(cache.isSolid(-1, 0, 0));
        assertEquals(125 - 2 * 3 * 2 + 1, cache.sectionCount());

        // Boxes with more sections than the cache holds take the scanning path.
        cache = cacheWith125Sections(g);
        cache.invalidateBox(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, -1, 0, 0);
        assertEquals(125 - 4 * 3 * 3, cache.sectionCount());
        assertTrue(cache.isSolid(-1, 0, 0));
        assertFalse(cache.isSolid(-17, 0, 0));
        cache.invalidateBox(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        assertEquals(0, cache.sectionCount());
        g.set(-17, 0, 0, true);
        assertTrue(cache.isSolid(-17, 0, 0));
    }

    /** A grid with a mutable set of unknown voxels (which read as solid). */
    private static final class MaskedView implements VoxelView {
        final ArrayVoxelGrid grid;
        final Set<Long> unknown = new HashSet<>();
        int reads;

        MaskedView(ArrayVoxelGrid grid) {
            this.grid = grid;
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            reads++;
            return grid.isSolid(x, y, z) || unknown.contains(key(x, y, z));
        }

        @Override
        public boolean isKnown(int x, int y, int z) {
            reads++;
            return !unknown.contains(key(x, y, z));
        }

        @Override
        public float conductivity(int x, int y, int z) {
            return 1;
        }

        @Override
        public boolean isProtected(int x, int y, int z) {
            return false;
        }
    }

    private static long key(int x, int y, int z) {
        return (long) x << 42 ^ (long) (y & 0x1FFFFF) << 21 ^ z & 0x1FFFFF;
    }

    @Test
    void reloadReportsExactlyTheVoxelsThatChanged() {
        Random random = new Random(23);
        ArrayVoxelGrid g = randomGrid(random);
        MaskedView source = new MaskedView(g);
        VoxelCache cache = new VoxelCache(source);
        for (int round = 0; round < 200; round++) {
            int sx = random.nextInt(4) - 2, sy = random.nextInt(4) - 2, sz = random.nextInt(4) - 2;
            int bx = sx * 16, by = sy * 16, bz = sz * 16;
            // What the cache says about the section now (read it first if it is not cached).
            boolean[] solid = new boolean[4096], known = new boolean[4096];
            for (int i = 0; i < 4096; i++) {
                solid[i] = cache.isSolid(bx + (i & 15), by + (i >> 8), bz + (i >> 4 & 15));
                known[i] = cache.isKnown(bx + (i & 15), by + (i >> 8), bz + (i >> 4 & 15));
            }
            // Edits in and around it, solidity and knowledge.
            int edits = random.nextInt(round % 10 == 0 ? 3000 : 12);
            for (int e = 0; e < edits; e++) {
                int x = bx + random.nextInt(20) - 2, y = by + random.nextInt(20) - 2, z = bz + random.nextInt(20) - 2;
                if (random.nextBoolean() && g.contains(x, y, z)) {
                    g.set(x, y, z, !g.isSolid(x, y, z));
                } else if (!source.unknown.remove(key(x, y, z))) {
                    source.unknown.add(key(x, y, z));
                }
            }
            int sections = cache.sectionCount();
            int vx = bx + random.nextInt(16), vy = by + random.nextInt(16), vz = bz + random.nextInt(16);
            List<Long> reported = new ArrayList<>();
            int count = cache.reload(vx, vy, vz, (x, y, z) -> reported.add(key(x, y, z)));
            List<Long> expected = new ArrayList<>();
            for (int i = 0; i < 4096; i++) {
                int x = bx + (i & 15), y = by + (i >> 8), z = bz + (i >> 4 & 15);
                if (solid[i] != source.isSolid(x, y, z) || known[i] != source.isKnown(x, y, z)) {
                    expected.add(key(x, y, z));
                }
            }
            assertEquals(expected, reported, "round " + round + ": changed voxels in y, z, x order");
            assertEquals(expected.size(), count);
            assertEquals(sections, cache.sectionCount());
            for (int i = 0; i < 4096; i++) {
                assertSameAnswers(source, cache, bx + (i & 15), by + (i >> 8), bz + (i >> 4 & 15));
            }
        }
    }

    @Test
    void reloadReadsOnlyThatSectionAndLeavesUncachedOnesAlone() {
        ArrayVoxelGrid g = new ArrayVoxelGrid(-40, -40, -40, 40, 40, 40, false);
        CountingView source = new CountingView(g, 0);
        VoxelCache cache = new VoxelCache(source);
        cache.setTime(5);
        for (int x = -32; x < 32; x += 16) {
            cache.isSolid(x, 0, 0);
        }
        assertEquals(4, cache.sectionCount());
        g.set(1, 2, 3, true).set(-20, 0, 0, true);
        int reads = source.reads;

        assertEquals(0, cache.reload(1, 50, 3, (x, y, z) -> {
            throw new AssertionError("not cached");
        }));
        assertEquals(reads, source.reads);
        assertEquals(4, cache.sectionCount());

        assertFalse(cache.isSolid(5, 5, 5), "the section to reload was queried last");
        List<Long> reported = new ArrayList<>();
        cache.setTime(9);
        assertEquals(1, cache.reload(15, 15, 15, (x, y, z) -> reported.add(key(x, y, z))));
        assertEquals(List.of(key(1, 2, 3)), reported);
        assertEquals(reads + READS_PER_SECTION, source.reads);
        assertTrue(cache.isSolid(1, 2, 3), "no stale answer through the last-section shortcut");
        assertFalse(cache.isSolid(-20, 0, 0), "other sections are still stale");
        assertEquals(reads + READS_PER_SECTION, source.reads);
        // Reloading stamps the section anew.
        assertEquals(3, cache.expire(2));
        assertTrue(cache.isSolid(1, 2, 3));
        cache.setTime(20);
        assertEquals(0, cache.expire(11));
        assertEquals(1, cache.expire(10));
    }

    @Test
    void expireDropsSectionsReadBeforeTheLimit() {
        ArrayVoxelGrid g = new ArrayVoxelGrid(-40, -40, -40, 40, 40, 40, false);
        VoxelCache cache = new VoxelCache(g);
        cache.setTime(100);
        cache.isSolid(0, 0, 0);
        cache.setTime(110);
        cache.isSolid(20, 0, 0);
        cache.isSolid(0, 0, 3); // already cached: keeps its stamp
        cache.setTime(120);
        assertEquals(0, cache.expire(Long.MAX_VALUE));
        assertEquals(0, cache.expire(20));
        assertEquals(1, cache.expire(15));
        assertEquals(1, cache.sectionCount());
        assertEquals(0, cache.expire(10), "read at exactly now - maxAge: kept");

        g.set(20, 0, 0, true);
        g.set(1, 1, 1, true);
        assertTrue(cache.isSolid(1, 1, 1), "expired section is read again");
        assertFalse(cache.isSolid(20, 0, 0), "still stale");
        assertEquals(1, cache.expire(9));
        assertTrue(cache.isSolid(20, 0, 0));
        assertEquals(2, cache.sectionCount());

        // Randomized: after expiring everything the cache agrees with the changed source again.
        Random random = new Random(7);
        for (int i = 0; i < 2000; i++) {
            g.set(random.nextInt(81) - 40, random.nextInt(81) - 40, random.nextInt(81) - 40, random.nextBoolean());
            cache.isSolid(random.nextInt(81) - 40, random.nextInt(81) - 40, random.nextInt(81) - 40);
        }
        cache.setTime(500);
        cache.expire(0);
        for (int i = 0; i < 20000; i++) {
            int x = random.nextInt(90) - 45, y = random.nextInt(90) - 45, z = random.nextInt(90) - 45;
            assertSameAnswers(g, cache, x, y, z);
        }
    }

    @Test
    void agreesAfterRandomEditsAndInvalidations() {
        Random random = new Random(19);
        ArrayVoxelGrid g = randomGrid(random);
        CountingView source = new CountingView(g, 12345);
        VoxelCache cache = new VoxelCache(source);
        for (int i = 0; i < 3000; i++) {
            int x = random.nextInt(g.maxX - g.minX + 1) + g.minX;
            int y = random.nextInt(g.maxY - g.minY + 1) + g.minY;
            int z = random.nextInt(g.maxZ - g.minZ + 1) + g.minZ;
            g.set(x, y, z, random.nextBoolean());
            if (random.nextInt(4) == 0) {
                cache.invalidateBox(x - random.nextInt(20), y, z + random.nextInt(20), x + 1, y - 3, z);
            } else {
                cache.invalidate(x, y, z);
            }
            for (int j = 0; j < 20; j++) {
                assertSameAnswers(source, cache, x + random.nextInt(9) - 4, y + random.nextInt(9) - 4,
                        z + random.nextInt(9) - 4);
            }
        }
        for (int y = g.minY - 3; y <= g.maxY + 3; y++) {
            for (int z = g.minZ - 3; z <= g.maxZ + 3; z++) {
                for (int x = g.minX - 3; x <= g.maxX + 3; x++) {
                    assertSameAnswers(source, cache, x, y, z);
                }
            }
        }
    }

    @Test
    void handlesExtremeCoordinates() {
        long seed = 99;
        VoxelView hashed = new VoxelView() {
            @Override
            public boolean isSolid(int x, int y, int z) {
                return (mix(x, y, z, seed) & 1) != 0;
            }

            @Override
            public boolean isKnown(int x, int y, int z) {
                return (mix(x, y, z, seed) & 6) != 0;
            }

            @Override
            public float conductivity(int x, int y, int z) {
                return 1;
            }

            @Override
            public boolean isProtected(int x, int y, int z) {
                return false;
            }
        };
        VoxelCache cache = new VoxelCache(hashed);
        int[] edges = {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, Integer.MIN_VALUE + 16, -17, -16, -1, 0, 15, 16,
                Integer.MAX_VALUE - 16, Integer.MAX_VALUE - 1, Integer.MAX_VALUE};
        for (int x : edges) {
            for (int y : edges) {
                for (int z : edges) {
                    assertSameAnswers(hashed, cache, x, y, z);
                }
            }
        }
        Random random = new Random(5);
        for (int i = 0; i < 5000; i++) {
            assertSameAnswers(hashed, cache, random.nextInt(), random.nextInt(), random.nextInt());
        }
        cache.clear();
        assertEquals(0, cache.sectionCount());
        assertSameAnswers(hashed, cache, Integer.MAX_VALUE, Integer.MIN_VALUE, 0);
    }

    @Test
    void conductivityAndProtectionAreNotCached() {
        ArrayVoxelGrid g = new ArrayVoxelGrid(-8, -8, -8, 8, 8, 8, true);
        VoxelCache cache = new VoxelCache(g);
        assertEquals(1f, cache.conductivity(2, 2, 2));
        assertFalse(cache.isProtected(2, 2, 2));
        g.setConductivity(2, 2, 2, 2.5f);
        g.setProtected(2, 2, 2, true);
        assertEquals(2.5f, cache.conductivity(2, 2, 2));
        assertTrue(cache.isProtected(2, 2, 2));
        assertEquals(0, cache.sectionCount());
    }

    @Test
    void clearForgetsEverything() {
        ArrayVoxelGrid g = new ArrayVoxelGrid(-8, -8, -8, 40, 8, 8, false);
        CountingView source = new CountingView(g, 0);
        VoxelCache cache = new VoxelCache(source);
        for (int x = -8; x <= 40; x += 4) {
            cache.isSolid(x, 0, 0);
        }
        assertEquals(4, cache.sectionCount());
        g.set(0, 0, 0, true);
        cache.clear();
        assertEquals(0, cache.sectionCount());
        int reads = source.reads;
        assertTrue(cache.isSolid(0, 0, 0));
        assertEquals(reads + READS_PER_SECTION, source.reads);
    }
}
