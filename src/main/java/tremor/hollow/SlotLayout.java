package tremor.hollow;

import java.util.function.IntPredicate;

/**
 * Where the slots of the hollow lie (SPEC 9): every event gets a region of {@code tremor:hollow} of its own, its
 * copy shifted there by a whole number of chunks. The slots sit on a square spiral around a middle chunk (the middle
 * of the hollow's world border, which is the overworld's: {@link #middleChunk}), {@value #SPACING_CHUNKS} chunks
 * apart: the ones in use stay near the middle (inside any world border that is not tiny, wherever it is), and nobody
 * ever sees or reaches another player's copy, since even the largest view distance is 32 chunks. Plain Java.
 */
public final class SlotLayout {
    /** Distance between the middles of neighbouring slots, in chunks. */
    public static final int SPACING_CHUNKS = 64;
    /** Rings of the spiral that are used; the farthest slot is this many spacings from the middle. */
    public static final int RINGS = 15;
    /** Number of slots: all cells of the spiral up to ring {@link #RINGS}. */
    public static final int MAX_SLOTS = (2 * RINGS + 1) * (2 * RINGS + 1);

    private SlotLayout() {
    }

    /** Chunk x of the middle of slot {@code index}, counted from the middle chunk of the layout. */
    public static int chunkX(int index) {
        return cell(index).x() * SPACING_CHUNKS;
    }

    /** Chunk z of the middle of slot {@code index}, counted from the middle chunk of the layout. */
    public static int chunkZ(int index) {
        return cell(index).z() * SPACING_CHUNKS;
    }

    /** The chunk (x or z) holding the block coordinate {@code center}, such as that of the world border's middle. */
    public static int middleChunk(double center) {
        return (int) Math.floor(center) >> 4;
    }

    /**
     * The lowest slot below {@link #MAX_SLOTS} that is not {@code taken} and {@code fits} (for example inside the
     * world border), or -1 if there is none.
     */
    public static int lowestFree(IntPredicate taken, IntPredicate fits) {
        for (int index = 0; index < MAX_SLOTS; index++) {
            if (!taken.test(index) && fits.test(index)) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Cell {@code index} of the square spiral: 0 is (0, 0), then ring after ring, ring {@code r} holding the
     * {@code 8r} cells at Chebyshev distance {@code r}, so the first {@code (2r + 1)^2} cells fill the square of
     * ring {@code r}.
     */
    static Cell cell(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Negative slot " + index);
        }
        if (index == 0) {
            return new Cell(0, 0);
        }
        int r = (int) ((Math.sqrt(index) + 1) / 2);
        while ((2L * r + 1) * (2L * r + 1) <= index) {
            r++;
        }
        while ((2L * r - 1) * (2L * r - 1) > index) {
            r--;
        }
        int k = index - (2 * r - 1) * (2 * r - 1);
        int side = k / (2 * r);
        int t = k % (2 * r);
        return switch (side) {
            case 0 -> new Cell(r, -r + 1 + t);
            case 1 -> new Cell(r - 1 - t, r);
            case 2 -> new Cell(-r, r - 1 - t);
            default -> new Cell(-r + 1 + t, -r);
        };
    }

    record Cell(int x, int z) {
    }
}
