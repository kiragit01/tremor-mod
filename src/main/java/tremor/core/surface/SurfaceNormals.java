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

    /** The share of the given normal in {@link #wideNormal}: what it comes to where the faces around cancel. */
    static final double WIDE_OWN = 0.02;
    /** A face turned this far away from the given normal (minus the cosine) no longer counts in {@link #wideNormal}. */
    static final double WIDE_AWAY = 0.5;
    /** Faces count less over this many blocks before the radius of {@link #wideNormal}, down to nothing at it. */
    static final double WIDE_TAPER = 2;

    /**
     * The way the ground faces around {@code center} seen at the scale of {@code radius} blocks: the open faces of
     * the solid voxels within that radius, summed by the way they face, the sum along each axis squared (its sign
     * kept) so that the way most of them face outweighs the rest. A face counts less near the radius and not at all
     * at it, so nothing comes or goes at once as the centre moves. Faces turned away from {@code normal} are left
     * out, softly: the far side of a wall and the roof of a cave are not the ground the caller is on. A small share
     * of {@code normal} itself is added, so it is that where the faces cancel (a shaft) or there are none.
     * <p>
     * A ledge lower than the radius hardly turns it, the faces of the floor and of the top outweighing those of its
     * side; a wall higher than the radius turns it all the way, within a few blocks of its foot; a slope of one in
     * one is its own way, a gentler one comes out a little flatter than it is. A big bump is drawn along it: along
     * the normal of the voxel under its centre it turned onto the side of every ledge lower than itself and off it
     * again within a few ticks, sinking and shooting up each time (the user: under the ground it need not go round
     * every corner).
     */
    public static Vec3 wideNormal(VoxelView view, Vec3 center, Vec3 normal, double radius) {
        double reach2 = (radius + 1) * (radius + 1);
        int x0 = (int) Math.floor(center.x() - radius - 0.5), x1 = (int) Math.floor(center.x() + radius + 0.5);
        int y0 = (int) Math.floor(center.y() - radius - 0.5), y1 = (int) Math.floor(center.y() + radius + 0.5);
        int z0 = (int) Math.floor(center.z() - radius - 0.5), z1 = (int) Math.floor(center.z() + radius + 0.5);
        double sx = 0, sy = 0, sz = 0, total = 0;
        for (int y = y0; y <= y1; y++) {
            double cy = y + 0.5 - center.y();
            for (int z = z0; z <= z1; z++) {
                double cz = z + 0.5 - center.z();
                for (int x = x0; x <= x1; x++) {
                    double cx = x + 0.5 - center.x();
                    if (cx * cx + cy * cy + cz * cz > reach2 || !view.isSolid(x, y, z)) {
                        continue; // none of its faces is within the radius; or it has none
                    }
                    for (int i = 0; i < 6; i++) {
                        int ax = FACE_X[i], ay = FACE_Y[i], az = FACE_Z[i];
                        double facing = ax * normal.x() + ay * normal.y() + az * normal.z();
                        if (facing <= -WIDE_AWAY || view.isSolid(x + ax, y + ay, z + az)) {
                            continue;
                        }
                        double fx = cx + 0.5 * ax, fy = cy + 0.5 * ay, fz = cz + 0.5 * az;
                        double w = Math.min(1, (radius - Math.sqrt(fx * fx + fy * fy + fz * fz)) / WIDE_TAPER);
                        if (w <= 0) {
                            continue;
                        }
                        if (facing < 0) {
                            double t = 1 + facing / WIDE_AWAY;
                            w *= t * t * (3 - 2 * t);
                        }
                        sx += w * ax;
                        sy += w * ay;
                        sz += w * az;
                        total += w;
                    }
                }
            }
        }
        sx += WIDE_OWN * total * normal.x();
        sy += WIDE_OWN * total * normal.y();
        sz += WIDE_OWN * total * normal.z();
        Vec3 wide = normalizeOrZero(sx * Math.abs(sx), sy * Math.abs(sy), sz * Math.abs(sz));
        return wide.isNearZero() ? normal : wide;
    }

    private static final int[] FACE_X = {1, -1, 0, 0, 0, 0}, FACE_Y = {0, 0, 1, -1, 0, 0}, FACE_Z = {0, 0, 0, 0, 1, -1};

    private static Vec3 normalizeOrZero(double sx, double sy, double sz) {
        double len = Math.sqrt(sx * sx + sy * sy + sz * sz);
        return len < CANCEL_EPSILON ? Vec3.ZERO : new Vec3(sx / len, sy / len, sz / len);
    }
}
