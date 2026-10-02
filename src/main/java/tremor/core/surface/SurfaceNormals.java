package tremor.core.surface;

import tremor.core.VoxelView;
import tremor.core.math.Vec3;

/**
 * Surface normal estimation on the voxel grid (SPEC 6.2). The same code gives correct normals on floors, walls,
 * ceilings and their edges. For repeated queries over one area use {@link NormalField}, which caches.
 */
public final class SurfaceNormals {
    /** Below this length a summed normal counts as cancelled. */
    static final double CANCEL_EPSILON = 1e-6;

    /** The 26 neighbour offsets and the matching unit direction vectors, in a fixed (y, z, x) order. */
    private static final int[] OFFSET_X = new int[26], OFFSET_Y = new int[26], OFFSET_Z = new int[26];
    private static final double[] DIR_X = new double[26], DIR_Y = new double[26], DIR_Z = new double[26];

    static {
        int i = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    OFFSET_X[i] = dx;
                    OFFSET_Y[i] = dy;
                    OFFSET_Z[i] = dz;
                    DIR_X[i] = dx / len;
                    DIR_Y[i] = dy / len;
                    DIR_Z[i] = dz / len;
                    i++;
                }
            }
        }
    }

    private SurfaceNormals() {
    }

    /**
     * Step 1: for a solid voxel, the normalized sum of unit vectors toward each open voxel among its 26 neighbours.
     * {@link Vec3#ZERO} if the voxel is open, has no open neighbours, or the sum cancels (e.g. a 1-thick wall).
     */
    public static Vec3 rawNormal(VoxelView view, int x, int y, int z) {
        if (!view.isSolid(x, y, z)) {
            return Vec3.ZERO;
        }
        double sx = 0, sy = 0, sz = 0;
        for (int i = 0; i < 26; i++) {
            if (view.isOpen(x + OFFSET_X[i], y + OFFSET_Y[i], z + OFFSET_Z[i])) {
                sx += DIR_X[i];
                sy += DIR_Y[i];
                sz += DIR_Z[i];
            }
        }
        return normalizeOrZero(sx, sy, sz);
    }

    /**
     * Step 2: the normalized sum of {@link #rawNormal} over the surface voxels in the 3×3×3 block centred on the
     * voxel, itself included, that face the same way as the voxel (see {@link #smooth}). {@link Vec3#ZERO} if the
     * voxel is not a surface voxel; falls back to its own raw normal if the average cancels or turns away from it.
     */
    public static Vec3 smoothNormal(VoxelView view, int x, int y, int z) {
        if (!Surface.isSurface(view, x, y, z)) {
            return Vec3.ZERO;
        }
        return smooth(new Lookup() {
            @Override
            public boolean isSurface(int nx, int ny, int nz) {
                return Surface.isSurface(view, nx, ny, nz);
            }

            @Override
            public Vec3 raw(int nx, int ny, int nz) {
                return rawNormal(view, nx, ny, nz);
            }
        }, x, y, z);
    }

    /** Where {@link #smooth} gets surface flags and raw normals from, so {@link NormalField} can plug in caches. */
    interface Lookup {
        boolean isSurface(int x, int y, int z);

        Vec3 raw(int x, int y, int z);
    }

    /** Below this cosine between the smoothed and the voxel's own raw normal, the raw normal is used instead. */
    static final double MAX_SMOOTHING_COS = 0.5;

    /**
     * Smoothing of a voxel already known to be a surface voxel. Shared so both entry points agree bit for bit.
     * <p>
     * Only neighbours facing the same way as the voxel itself are averaged in (positive dot product of the raw
     * normals). Otherwise the opposite face of a slab only 2 blocks thick, e.g. the roof of a cave right under the
     * ground, would cancel the up component and tip flat ground sideways. If the voxel's own raw normal cancels
     * (1-thick walls) every neighbour counts, as in the plain SPEC 6.2 average.
     */
    static Vec3 smooth(Lookup lookup, int x, int y, int z) {
        Vec3 own = lookup.raw(x, y, z);
        boolean filter = !own.isNearZero();
        double sx = 0, sy = 0, sz = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (lookup.isSurface(x + dx, y + dy, z + dz)) {
                        Vec3 raw = lookup.raw(x + dx, y + dy, z + dz);
                        if (filter && raw.dot(own) <= 0) {
                            continue;
                        }
                        sx += raw.x();
                        sy += raw.y();
                        sz += raw.z();
                    }
                }
            }
        }
        Vec3 smooth = normalizeOrZero(sx, sy, sz);
        if (smooth.isNearZero() || (filter && smooth.dot(own) < MAX_SMOOTHING_COS)) {
            return own;
        }
        return smooth;
    }

    private static Vec3 normalizeOrZero(double sx, double sy, double sz) {
        double len = Math.sqrt(sx * sx + sy * sy + sz * sz);
        return len < CANCEL_EPSILON ? Vec3.ZERO : new Vec3(sx / len, sy / len, sz / len);
    }
}
