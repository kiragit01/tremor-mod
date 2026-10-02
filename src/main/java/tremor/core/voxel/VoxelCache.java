package tremor.core.voxel;

import java.util.Objects;

import tremor.core.VoxelView;

/**
 * Caching {@link VoxelView}: reads {@code isSolid} and {@code isKnown} of whole 16³ sections from the source once and
 * answers from bitsets afterwards ("graph cache per chunk", SPEC 5.2 / 16). {@code conductivity} and
 * {@code isProtected} are passed through to the source uncached.
 * <p>
 * The cache never notices world changes by itself: the owner calls {@link #invalidate} or {@link #reload} on block
 * changes and {@link #expire} periodically as a safety net for changes it was not told about. Time is whatever
 * monotonic tick counter the owner passes to {@link #setTime}. Not thread-safe.
 */
public final class VoxelCache implements VoxelView {
    /** Side of a cached section, in voxels. */
    public static final int SECTION_SIZE = 16;

    private static final int SHIFT = 4;
    private static final int LOCAL_MASK = SECTION_SIZE - 1;
    private static final int WORDS = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE / Long.SIZE;
    private static final int INITIAL_CAPACITY = 64;

    /** Receives the voxels whose cached answers changed when a section is {@linkplain #reload reloaded}. */
    @FunctionalInterface
    public interface ChangeListener {
        void changed(int x, int y, int z);
    }

    private final VoxelView source;
    private long now;
    /** Open addressing with linear probing, keyed by section coordinates; capacity is a power of two. */
    private Section[] table = new Section[INITIAL_CAPACITY];
    private int size;
    /** The section of the previous query, so runs of queries in one section skip the table. */
    private Section last;

    public VoxelCache(VoxelView source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public VoxelView source() {
        return source;
    }

    /** Current time; sections read from now on are stamped with it. */
    public void setTime(long now) {
        this.now = now;
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        return Section.bit(section(x, y, z).solid, index(x, y, z));
    }

    @Override
    public boolean isKnown(int x, int y, int z) {
        return Section.bit(section(x, y, z).known, index(x, y, z));
    }

    @Override
    public float conductivity(int x, int y, int z) {
        return source.conductivity(x, y, z);
    }

    @Override
    public boolean isProtected(int x, int y, int z) {
        return source.isProtected(x, y, z);
    }

    /** Forgets the section containing the voxel. */
    public void invalidate(int x, int y, int z) {
        Section s = find(x >> SHIFT, y >> SHIFT, z >> SHIFT);
        if (s != null) {
            remove(s);
        }
    }

    /**
     * Reads the section containing the voxel from the source again, if it is cached, and reports every voxel of it
     * whose {@code isSolid} or {@code isKnown} answer changed (in y, z, x order). Unlike {@link #invalidate} followed
     * by a read, this tells the owner everything the new read took from the source, not only the voxels it was told
     * about. The section is stamped with the current time.
     *
     * @return the number of changed voxels; 0 if the section was not cached
     */
    public int reload(int x, int y, int z, ChangeListener changes) {
        Objects.requireNonNull(changes, "changes");
        int sx = x >> SHIFT, sy = y >> SHIFT, sz = z >> SHIFT;
        int mask = table.length - 1;
        int slot = hash(sx, sy, sz) & mask;
        while (table[slot] != null && (table[slot].sx != sx || table[slot].sy != sy || table[slot].sz != sz)) {
            slot = slot + 1 & mask;
        }
        Section old = table[slot];
        if (old == null) {
            return 0;
        }
        Section fresh = load(sx, sy, sz);
        table[slot] = fresh; // same key, same probe run
        if (last == old) {
            last = fresh;
        }
        int x0 = sx << SHIFT, y0 = sy << SHIFT, z0 = sz << SHIFT;
        int count = 0;
        for (int w = 0; w < WORDS; w++) {
            for (long diff = old.solid[w] ^ fresh.solid[w] | old.known[w] ^ fresh.known[w]; diff != 0;
                 diff &= diff - 1) {
                int i = w << 6 | Long.numberOfTrailingZeros(diff);
                changes.changed(x0 + (i & LOCAL_MASK), y0 + (i >>> 8), z0 + (i >>> 4 & LOCAL_MASK));
                count++;
            }
        }
        return count;
    }

    /** Forgets every section overlapping the box (inclusive voxel bounds, any order). */
    public void invalidateBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        int sx0 = Math.min(x0, x1) >> SHIFT, sx1 = Math.max(x0, x1) >> SHIFT;
        int sy0 = Math.min(y0, y1) >> SHIFT, sy1 = Math.max(y0, y1) >> SHIFT;
        int sz0 = Math.min(z0, z1) >> SHIFT, sz1 = Math.max(z0, z1) >> SHIFT;
        double boxSections = ((double) sx1 - sx0 + 1) * ((double) sy1 - sy0 + 1) * ((double) sz1 - sz0 + 1);
        if (boxSections > size) {
            removeWhere(s -> s.sx >= sx0 && s.sx <= sx1 && s.sy >= sy0 && s.sy <= sy1 && s.sz >= sz0 && s.sz <= sz1);
            return;
        }
        for (int sy = sy0; sy <= sy1; sy++) {
            for (int sz = sz0; sz <= sz1; sz++) {
                for (int sx = sx0; sx <= sx1; sx++) {
                    Section s = find(sx, sy, sz);
                    if (s != null) {
                        remove(s);
                    }
                }
            }
        }
    }

    /** Forgets sections read before {@code now - maxAge}; returns how many. */
    public int expire(long maxAge) {
        long limit = now - maxAge;
        if (((now ^ maxAge) & (now ^ limit)) < 0) {
            limit = maxAge > 0 ? Long.MIN_VALUE : Long.MAX_VALUE; // saturate on overflow
        }
        long before = limit;
        return removeWhere(s -> s.stamp < before);
    }

    public void clear() {
        table = new Section[INITIAL_CAPACITY];
        size = 0;
        last = null;
    }

    public int sectionCount() {
        return size;
    }

    // ---- sections --------------------------------------------------------------------------------------------------

    private static int index(int x, int y, int z) {
        return (y & LOCAL_MASK) << 8 | (z & LOCAL_MASK) << 4 | x & LOCAL_MASK;
    }

    private static int hash(int sx, int sy, int sz) {
        int h = sx * 0x9E3779B1 ^ sy * 0x85EBCA77 ^ sz * 0xC2B2AE3D;
        return h ^ h >>> 15;
    }

    private Section section(int x, int y, int z) {
        int sx = x >> SHIFT, sy = y >> SHIFT, sz = z >> SHIFT;
        Section s = last;
        if (s != null && s.sx == sx && s.sy == sy && s.sz == sz) {
            return s;
        }
        s = find(sx, sy, sz);
        if (s == null) {
            s = load(sx, sy, sz);
            insert(s);
        }
        last = s;
        return s;
    }

    /** Reads a whole section from the source in one sweep. */
    private Section load(int sx, int sy, int sz) {
        Section s = new Section(sx, sy, sz, hash(sx, sy, sz), now);
        int x0 = sx << SHIFT, y0 = sy << SHIFT, z0 = sz << SHIFT;
        int i = 0;
        for (int ly = 0; ly < SECTION_SIZE; ly++) {
            for (int lz = 0; lz < SECTION_SIZE; lz++) {
                for (int lx = 0; lx < SECTION_SIZE; lx++, i++) {
                    int x = x0 + lx, y = y0 + ly, z = z0 + lz;
                    if (source.isSolid(x, y, z)) {
                        s.solid[i >>> 6] |= 1L << i;
                    }
                    if (source.isKnown(x, y, z)) {
                        s.known[i >>> 6] |= 1L << i;
                    }
                }
            }
        }
        return s;
    }

    private Section find(int sx, int sy, int sz) {
        int mask = table.length - 1;
        for (int i = hash(sx, sy, sz) & mask; ; i = i + 1 & mask) {
            Section s = table[i];
            if (s == null || s.sx == sx && s.sy == sy && s.sz == sz) {
                return s;
            }
        }
    }

    private void insert(Section s) {
        if ((size + 1) * 2 > table.length) {
            Section[] old = table;
            table = new Section[old.length * 2];
            for (Section o : old) {
                if (o != null) {
                    place(o);
                }
            }
        }
        place(s);
        size++;
    }

    private void place(Section s) {
        int mask = table.length - 1;
        int i = s.hash & mask;
        while (table[i] != null) {
            i = i + 1 & mask;
        }
        table[i] = s;
    }

    /** Removes a section that is in the table, shifting later entries of its probe run back (no tombstones). */
    private void remove(Section s) {
        int mask = table.length - 1;
        int hole = s.hash & mask;
        while (table[hole] != s) {
            hole = hole + 1 & mask;
        }
        table[hole] = null;
        size--;
        for (int i = hole + 1 & mask; table[i] != null; i = i + 1 & mask) {
            int home = table[i].hash & mask;
            // The entry may fill the hole unless its home slot lies cyclically in (hole, i].
            boolean stays = hole <= i ? hole < home && home <= i : hole < home || home <= i;
            if (!stays) {
                table[hole] = table[i];
                table[i] = null;
                hole = i;
            }
        }
        if (last == s) {
            last = null;
        }
    }

    private interface SectionFilter {
        boolean test(Section s);
    }

    private int removeWhere(SectionFilter filter) {
        Section[] old = table;
        table = new Section[old.length];
        int removed = 0;
        for (Section s : old) {
            if (s == null) {
                continue;
            }
            if (filter.test(s)) {
                removed++;
                if (last == s) {
                    last = null;
                }
            } else {
                place(s);
            }
        }
        size -= removed;
        return removed;
    }

    private static final class Section {
        final int sx, sy, sz, hash;
        final long stamp;
        /** One bit per voxel, index {@code y << 8 | z << 4 | x} in section-local coordinates. */
        final long[] solid = new long[WORDS], known = new long[WORDS];

        Section(int sx, int sy, int sz, int hash, long stamp) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.hash = hash;
            this.stamp = stamp;
        }

        static boolean bit(long[] words, int i) {
            return (words[i >>> 6] >>> i & 1L) != 0;
        }
    }
}
