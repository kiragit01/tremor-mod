package tremor.hollow.level;

/**
 * A block position packed into a {@code long}, with the bit layout of {@code BlockPos.asLong} (x: 26 bits at 38, z: 26
 * bits at 12, y: 12 bits at 0, all two's complement), so the plain planning code of the level and the game code share
 * keys: {@code BlockPos.of(key)} and {@code pos.asLong()} read and write the same values. Plain Java.
 */
public final class CellKey {
    private static final int XZ_BITS = 26;
    private static final int Y_BITS = 12;
    private static final int Z_SHIFT = Y_BITS;
    private static final int X_SHIFT = Y_BITS + XZ_BITS;
    private static final long XZ_MASK = (1L << XZ_BITS) - 1;
    private static final long Y_MASK = (1L << Y_BITS) - 1;

    private CellKey() {
    }

    public static long of(int x, int y, int z) {
        return ((x & XZ_MASK) << X_SHIFT) | (y & Y_MASK) | ((z & XZ_MASK) << Z_SHIFT);
    }

    public static int x(long key) {
        return (int) (key >> X_SHIFT);
    }

    public static int y(long key) {
        return (int) (key << (64 - Y_BITS) >> (64 - Y_BITS));
    }

    public static int z(long key) {
        return (int) (key << (64 - Z_SHIFT - XZ_BITS) >> (64 - XZ_BITS));
    }

    /** The key of the cell {@code dy} blocks above (below if negative) the one of {@code key}. */
    public static long above(long key, int dy) {
        return of(x(key), y(key) + dy, z(key));
    }
}
