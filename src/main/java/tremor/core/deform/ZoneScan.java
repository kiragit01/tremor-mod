package tremor.core.deform;

import java.util.ArrayList;
import java.util.List;

import tremor.core.VoxelView;
import tremor.core.math.Vec3;
import tremor.core.surface.NormalField;

/**
 * Finds the surface of an Awakening zone (SPEC 9: "вся поверхность в зоне"), a little at a time: the surface voxels
 * in a vertical cylinder around the zone centre that border the open space the centre is in. Unlike
 * {@link SurfaceCollector}, which takes everything around a bump within a band along its normal, this takes floors,
 * walls and ceilings alike, but leaves out caves sealed off from the centre within the cylinder: their ground could
 * not be seen from the zone, and drawing it would only eat the render budget.
 * <p>
 * The scan floods the open voxels of the cylinder connected to the centre across faces, and reports every solid face
 * neighbour of a flooded voxel that is a surface voxel ({@link tremor.core.surface.Surface#isSurface}) with a
 * non-zero smoothed normal (SPEC 6.2). It starts at the voxel containing the centre, or, if that one is solid, at the
 * first open voxel above it; nothing is found if there is none within the cylinder. Voxels whose centre lies within
 * {@code radius} horizontally of the zone centre and within {@code below}/{@code above} whole voxels of the centre's
 * voxel are in the cylinder; the scan reads the view up to 2 voxels beyond it (normal estimation).
 * <p>
 * Every open voxel of the space is visited once, the open sky over a zone included: about 150 thousand for a zone
 * on flat ground with a radius of 45 blocks and 24 blocks of air above. The work is done in {@link #advance} steps of
 * visited voxels, so a caller can spread a scan over many frames. Results are deterministic: the points come in flood
 * order, roughly nearest to the centre first. Not thread-safe; the view must not change during the scan.
 */
public final class ZoneScan {
    private static final int[] FACE_X = {1, -1, 0, 0, 0, 0};
    private static final int[] FACE_Y = {0, 0, 1, -1, 0, 0};
    private static final int[] FACE_Z = {0, 0, 0, 0, 1, -1};
    /** Bits of each local coordinate in a queued voxel, see {@link #pack}. */
    private static final int LOCAL_BITS = 10;
    private static final int LOCAL_LIMIT = 1 << LOCAL_BITS;
    private static final int LOCAL_MASK = LOCAL_LIMIT - 1;

    private final VoxelView view;
    private final Vec3 prefer;
    /** {@link #prefer} snapped to its nearest axis, as {@link SurfaceCollector#openWay} goes; null without it. */
    private final Vec3 preferAxis;
    private final NormalField normals;
    private final double cx, cz, radiusSq;
    private final int startX, startY, startZ;
    private final int minX, minY, minZ, maxX, maxY, maxZ;
    private final int sizeX, sizeZ;
    /** Voxels of the box already looked at (open or solid), one bit each, index from {@link #index}. */
    private final long[] seen;
    /**
     * Flood queue of voxels, packed by {@link #pack}: a ring buffer, its length a power of two, grown when full. It
     * holds the flood's front only.
     */
    private int[] queue = new int[1024];
    private int head, size;
    private boolean started, done;
    private final List<SurfacePoint> points = new ArrayList<>();

    /**
     * @param center centre of the zone
     * @param radius horizontal radius of the cylinder, finite and {@code >= 0}
     * @param below  whole voxels below the centre's voxel in the cylinder, {@code >= 0}
     * @param above  whole voxels above it, {@code >= 0}
     * @throws IllegalArgumentException for a bad radius or reach, or a cylinder too big to scan (over 1024 voxels
     *                                  across or high)
     */
    public ZoneScan(VoxelView view, Vec3 center, double radius, int below, int above) {
        this(view, center, radius, below, above, null);
    }

    /**
     * A scan whose voxels without a normal of their own (a wall one block thick) go along {@code prefer} where it is
     * open ({@link SurfaceCollector#openWay(VoxelView, int, int, int, Vec3)}): the face a ring runs over; null as the
     * other constructor.
     */
    public ZoneScan(VoxelView view, Vec3 center, double radius, int below, int above, Vec3 prefer) {
        if (!(radius >= 0) || !Double.isFinite(radius)) {
            throw new IllegalArgumentException("radius must be finite and >= 0: " + radius);
        }
        if (below < 0 || above < 0) {
            throw new IllegalArgumentException("below and above must be >= 0: " + below + ", " + above);
        }
        this.view = view;
        this.prefer = prefer;
        int[] axis = prefer == null ? null : ThinSwell.axisStep(prefer);
        this.preferAxis = axis == null ? null : new Vec3(axis[0], axis[1], axis[2]);
        this.normals = new NormalField(view);
        this.cx = center.x();
        this.cz = center.z();
        this.radiusSq = radius * radius;
        startX = (int) Math.floor(center.x());
        startY = (int) Math.floor(center.y());
        startZ = (int) Math.floor(center.z());
        // Voxel x is in the cylinder only if its centre x + 0.5 lies in [cx - r, cx + r].
        minX = (int) Math.ceil(cx - radius - 0.5);
        maxX = (int) Math.floor(cx + radius - 0.5);
        minZ = (int) Math.ceil(cz - radius - 0.5);
        maxZ = (int) Math.floor(cz + radius - 0.5);
        minY = startY - below;
        maxY = startY + above;
        sizeX = Math.max(0, maxX - minX + 1);
        sizeZ = Math.max(0, maxZ - minZ + 1);
        int sizeY = maxY - minY + 1;
        if (sizeX > LOCAL_LIMIT || sizeY > LOCAL_LIMIT || sizeZ > LOCAL_LIMIT) {
            throw new IllegalArgumentException("zone too big to scan: " + sizeX + " x " + sizeY + " x " + sizeZ);
        }
        seen = new long[(int) (((long) sizeX * sizeY * sizeZ + 63) >>> 6)];
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    /**
     * Visits up to {@code maxVisits} more open voxels.
     *
     * @return true once the scan is complete (then {@link #points} is final)
     */
    public boolean advance(int maxVisits) {
        if (!started) {
            started = true;
            start();
        }
        for (int visits = 0; visits < maxVisits && size > 0; visits++) {
            int packed = queue[head];
            head = head + 1 & queue.length - 1;
            size--;
            int x = minX + (packed & LOCAL_MASK);
            int z = minZ + (packed >>> LOCAL_BITS & LOCAL_MASK);
            int y = minY + (packed >>> 2 * LOCAL_BITS);
            for (int f = 0; f < 6; f++) {
                look(x + FACE_X[f], y + FACE_Y[f], z + FACE_Z[f]);
            }
        }
        if (size == 0 && !done) {
            done = true;
            queue = null; // the flood is over: drop it
        }
        return done;
    }

    public boolean done() {
        return done;
    }

    /** Surface voxels found so far, in flood order; a live view, final once {@link #done}. */
    public List<SurfacePoint> points() {
        return points;
    }

    /** Queues the first open voxel, see the class comment. */
    private void start() {
        if (!inside(startX, startY, startZ)) {
            return;
        }
        int y = startY;
        while (y < maxY && view.isSolid(startX, y, startZ)) {
            y++;
        }
        if (view.isOpen(startX, y, startZ)) {
            mark(index(startX, y, startZ));
            enqueue(startX, y, startZ);
        }
    }

    /** A face neighbour of a flooded voxel: report it if it is surface, flood it if it is open. */
    private void look(int x, int y, int z) {
        if (!inside(x, y, z)) {
            return;
        }
        int i = index(x, y, z);
        if ((seen[i >>> 6] & 1L << i) != 0) {
            return;
        }
        mark(i);
        if (view.isOpen(x, y, z)) {
            enqueue(x, y, z);
        } else if (normals.isSurface(x, y, z)) {
            Vec3 normal = normals.smooth(x, y, z);
            boolean none = normal.isNearZero() || SurfaceCollector.pushNormal(view, x, y, z, normal) == null;
            if (prefer != null) {
                // A ring running by: the sides of a thin wall ripple out of it as any wall's do (the user: a floor's
                // ring climbed a wall as two blocks). One with no normal of its own (a wall one block thick) moves
                // along the ring's axis if open there, else out of the side the ring comes from.
                if (none) {
                    Vec3 way = SurfaceCollector.openWay(view, x, y, z, prefer);
                    normal = way != null && way.equals(preferAxis) ? way : toward(x, y, z, way);
                }
            } else if (none || ThinSwell.thin(view, x, y, z) && ThinSwell.axisStep(normal)[1] == 0) {
                // Thin ground (a pillar, a ridge one or two blocks wide) only rises at its top: its sides, all edges
                // and corners, would fan out every way. Its top, a floor one block thick, a lip over a drop go
                // straight up instead of standing still (the user).
                normal = SurfaceCollector.openWay(view, x, y, z, null);
            }
            if (normal != null) {
                points.add(new SurfacePoint(x, y, z, normal));
            }
        }
    }

    /**
     * The open side of the voxel facing the centre across (the way a ring comes from), else {@code fallback}: the side
     * of a wall one block thick a ring on the ground reaches first.
     */
    private Vec3 toward(int x, int y, int z, Vec3 fallback) {
        double dx = cx - (x + 0.5), dz = cz - (z + 0.5);
        int sx = dx > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
        boolean xFirst = Math.abs(dx) >= Math.abs(dz);
        for (int i = 0; i < 2; i++) {
            boolean alongX = xFirst == (i == 0);
            int ax = alongX ? sx : 0, az = alongX ? 0 : sz;
            if ((alongX ? Math.abs(dx) : Math.abs(dz)) > 0.25 && view.isOpen(x + ax, y, z + az)) {
                return new Vec3(ax, 0, az);
            }
        }
        return fallback;
    }

    private boolean inside(int x, int y, int z) {
        if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) {
            return false;
        }
        double dx = x + 0.5 - cx, dz = z + 0.5 - cz;
        return dx * dx + dz * dz <= radiusSq;
    }

    private int index(int x, int y, int z) {
        return ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
    }

    private void mark(int i) {
        seen[i >>> 6] |= 1L << i;
    }

    private void enqueue(int x, int y, int z) {
        int mask = queue.length - 1;
        if (size == queue.length) {
            int[] grown = new int[queue.length * 2];
            for (int k = 0; k < size; k++) {
                grown[k] = queue[head + k & mask];
            }
            queue = grown;
            head = 0;
            mask = queue.length - 1;
        }
        queue[head + size & mask] = pack(x, y, z);
        size++;
    }

    /** The voxel's coordinates within the box, {@link #LOCAL_BITS} bits each: x lowest, then z, then y. */
    private int pack(int x, int y, int z) {
        return (y - minY) << 2 * LOCAL_BITS | (z - minZ) << LOCAL_BITS | x - minX;
    }
}
