package tremor.core.math;

/**
 * Voxel positions packed into a {@code long}: 26 bits x, 26 bits z, 12 bits y, two's complement, the same layout as
 * Minecraft's {@code BlockPos.asLong}. Valid for x, z in [-2^25, 2^25) and y in [-2048, 2048).
 */
public final class VoxelPos {
    private static final int XZ_BITS = 26;
    private static final int Y_BITS = 12;
    private static final long XZ_MASK = (1L << XZ_BITS) - 1;
    private static final long Y_MASK = (1L << Y_BITS) - 1;
    private static final int X_SHIFT = XZ_BITS + Y_BITS;
    private static final int Z_SHIFT = Y_BITS;

    private VoxelPos() {
    }

    public static long pack(int x, int y, int z) {
        return ((long) x & XZ_MASK) << X_SHIFT | ((long) z & XZ_MASK) << Z_SHIFT | (long) y & Y_MASK;
    }

    public static int x(long packed) {
        return (int) (packed << (64 - X_SHIFT - XZ_BITS) >> (64 - XZ_BITS));
    }

    public static int y(long packed) {
        return (int) (packed << (64 - Y_BITS) >> (64 - Y_BITS));
    }

    public static int z(long packed) {
        return (int) (packed << (64 - Z_SHIFT - XZ_BITS) >> (64 - XZ_BITS));
    }

    public static long offset(long packed, int dx, int dy, int dz) {
        return pack(x(packed) + dx, y(packed) + dy, z(packed) + dz);
    }

    /** Centre of the packed voxel. */
    public static Vec3 center(long packed) {
        return Vec3.voxelCenter(x(packed), y(packed), z(packed));
    }

    /** The voxel containing the point. */
    public static long containing(Vec3 p) {
        return pack((int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()));
    }

    public static String toString(long packed) {
        return x(packed) + "," + y(packed) + "," + z(packed);
    }
}
