package tremor.core.deform;

import tremor.core.VoxelView;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.noise.PerlinNoise;
import tremor.core.shape.BumpFrame;
import tremor.core.shape.BumpParams;
import tremor.core.shape.BumpShape;
import tremor.core.surface.Surface;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The bump on thin ground (the user's report, 2026-10-20: on a ridge or a pillar one or two blocks wide it looked
 * crooked, and on a platform one block thick it did not show at all). Every voxel of a narrow structure is an edge or a
 * corner, so the copies pushed out along their normals fanned out in every direction, and the voxels under a top one
 * block wide were pushed up through it; in a slab one block thick the normals cancel, open over and under alike. Ground
 * narrower than {@value #THIN_WIDTH} blocks across x or z, or one block thick ({@link #thin}), is therefore not pushed
 * out along its normals, except for a top the bump rises along. Where the entity itself is on such ground, the structure swells instead, as the user
 * asked: the open cells around it within reach are filled with copies of its nearest voxel, a ball wrapping it around
 * the entity ({@link #coverage}); and so does one the hill of an Awakening rises on, around the swallowed player
 * ({@link tremor.core.shape.GroundField#swellCenter}). The copies are whole blocks: the outer ones slide out of the
 * swelling along the ray from their source, diagonals too, so they stand off the grid, and the swelling breathes,
 * unevenly over the structure ({@link #breath}), so they move in and out as something alive. Pure math, no game
 * classes.
 */
public final class ThinSwell {
    /** Ground less than this wide across x or z (blocks) is thin: 3 wide holds a bump as it is. */
    static final int THIN_WIDTH = 3;
    /** The share of the bump's height a thin structure swells out by around the entity. */
    public static final double SWELL = 0.65;
    /** Cells farther than this from the structure (blocks) are never filled. */
    public static final double MAX_REACH = 3.5;
    /** How much the swelling breathes, as a share of it, and how long a breath lasts (seconds). */
    static final double BREATH = 0.15;
    static final double BREATH_PERIOD = 2.8;
    /** How far apart (blocks) the parts of the structure breathe out of step: the scale of the noise of the phase. */
    static final double BREATH_SCALE = 3.0;
    /** A voxel of the structure swelling less than this (blocks) fills nothing. */
    static final double MIN_SWELL = 0.05;
    /** At most this many voxels of the structure are looked at (a long thin wall). */
    static final int MAX_STRUCTURE = 1024;
    /** The six steps to the face neighbours of a voxel. */
    private static final int[][] STEPS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    /** Integer offsets to the cells within {@link #MAX_REACH} of a voxel, nearest first, and their lengths. */
    private static final int[][] OFFSETS;
    private static final double[] OFFSET_LENGTHS;

    static {
        int r = (int) Math.ceil(MAX_REACH);
        List<int[]> offsets = new ArrayList<>();
        for (int dy = -r; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 > 0 && d2 <= MAX_REACH * MAX_REACH) {
                        offsets.add(new int[]{dx, dy, dz, d2});
                    }
                }
            }
        }
        offsets.sort((a, b) -> Integer.compare(a[3], b[3]));
        OFFSETS = offsets.toArray(new int[0][]);
        OFFSET_LENGTHS = Arrays.stream(OFFSETS).mapToDouble(o -> Math.sqrt(o[3])).toArray();
    }

    /**
     * An open cell the swelling may fill: a copy sliding out along the ray from the structure's voxel {@code source}
     * into the cell as the swelling reaches it ({@link #coverage}), of the block at {@code look}: the source, or the
     * voxel under it if something is over the cell (dirt under grass: the ball is earth with grass on top).
     *
     * @param distance from the centre of the cell to that of its source, blocks
     */
    public record Fill(int x, int y, int z, int sourceX, int sourceY, int sourceZ, int lookX, int lookY, int lookZ,
                       double distance) {
    }

    /** The points to push out along their normals, and the cells the swelling may fill. */
    public record Result(List<SurfacePoint> points, List<Fill> fills) {
    }

    /** How far a voxel of a thin structure may swell out (blocks), the most of it while the deformation lasts. */
    @FunctionalInterface
    public interface Swell {
        double at(double x, double y, double z);
    }

    private ThinSwell() {
    }

    /**
     * Sorts the points a bump collected ({@link SurfaceCollector#collect}): thin ones are left out, but for a top the
     * bump rises along (its axis that of the bump's normal, open beyond); and if the entity is on thin ground, the
     * cells its structure swells into within {@code radius} of the bump's centre are added.
     */
    public static Result apply(VoxelView view, BumpFrame frame, BumpParams params, double radius,
                               List<SurfacePoint> points) {
        int[] axis = axisStep(frame.normal());
        List<SurfacePoint> kept = new ArrayList<>(points.size());
        for (SurfacePoint p : points) {
            if (!thin(view, p.x(), p.y(), p.z()) || isTop(view, p, axis)) {
                kept.add(p);
            }
        }
        Set<Long> structure = structure(view, frame.center(), frame.normal(), radius);
        if (structure.isEmpty()) {
            return new Result(kept, List.of());
        }
        kept.removeIf(p -> structure.contains(VoxelPos.pack(p.x(), p.y(), p.z())));
        Swell swell = (x, y, z) -> SWELL * Math.max(0, BumpShape.height(params, frame, x, y, z))
                * falloff(frame, params.sigmaSide(), x, y, z);
        return new Result(kept, fills(view, structure, kept, swell));
    }

    /**
     * Whether the voxel is solid ground less than {@value #THIN_WIDTH} blocks wide across x or across z, or one block
     * thick (open over and under it).
     */
    public static boolean thin(VoxelView view, int x, int y, int z) {
        return view.isSolid(x, y, z) && (run(view, x, y, z, 1, 0) < THIN_WIDTH || run(view, x, y, z, 0, 1) < THIN_WIDTH
                || !view.isSolid(x, y + 1, z) && !view.isSolid(x, y - 1, z));
    }

    /**
     * How much of a fill cell is filled this frame, 0..1: by how far the swelling of its source, {@link #SWELL} of the
     * bump's height there ({@code height}) faded with the distance from the bump's plane ({@code falloff}), reaches past
     * the cell's inner face.
     */
    public static double coverage(double height, double falloff, double distance) {
        double reach = SWELL * Math.max(0, height) * falloff - distance + 1; // how far it is out of the inner layer
        return reach <= 0 ? 0 : Math.min(1, reach);
    }

    /**
     * The breathing of the swelling at {@code timeSeconds}, a factor of it for the cell: slow, and out of step from one
     * part of the structure to another (a smooth noise of the place sets the phase), so that the outer copies move in
     * and out unevenly rather than all at once.
     */
    public static double breath(double timeSeconds, int x, int y, int z) {
        double phase = 2.5 * PerlinNoise.noise(x / BREATH_SCALE + 0.31, y / BREATH_SCALE + 0.17, z / BREATH_SCALE + 0.53);
        return 1 + BREATH * Math.sin(2 * Math.PI * timeSeconds / BREATH_PERIOD + phase);
    }

    /**
     * The fading of the swelling with the distance {@code e} of the point from the plane of the bump through its centre,
     * {@code exp(-(e/sigma)²)}: the bump's height does not depend on it, a ball around the entity does.
     */
    public static double falloff(BumpFrame frame, double sigma, double x, double y, double z) {
        Vec3 c = frame.center(), n = frame.normal();
        double e = (x - c.x()) * n.x() + (y - c.y()) * n.y() + (z - c.z()) * n.z();
        return Math.exp(-(e * e) / (sigma * sigma));
    }

    /** Solid voxels in a row through the voxel along x ({@code dx = 1}) or z, counted up to {@link #THIN_WIDTH}. */
    private static int run(VoxelView view, int x, int y, int z, int dx, int dz) {
        int count = 1;
        for (int k = 1; k < THIN_WIDTH && view.isSolid(x + k * dx, y, z + k * dz); k++) {
            count++;
        }
        for (int k = 1; k < THIN_WIDTH && count < THIN_WIDTH && view.isSolid(x - k * dx, y, z - k * dz); k++) {
            count++;
        }
        return count;
    }

    /** Whether the point is pushed out along the axis of the bump's normal, open beyond it: the top it rises along. */
    private static boolean isTop(VoxelView view, SurfacePoint p, int[] axis) {
        int[] own = axisStep(p.normal());
        return Arrays.equals(own, axis) && view.isOpen(p.x() + axis[0], p.y() + axis[1], p.z() + axis[2]);
    }

    /** The unit step along the axis nearest to {@code v} (the larger component wins; y before x before z). */
    static int[] axisStep(Vec3 v) {
        double ax = Math.abs(v.x()), ay = Math.abs(v.y()), az = Math.abs(v.z());
        if (ay >= ax && ay >= az) {
            return new int[]{0, v.y() >= 0 ? 1 : -1, 0};
        }
        return ax >= az ? new int[]{v.x() >= 0 ? 1 : -1, 0, 0} : new int[]{0, 0, v.z() >= 0 ? 1 : -1};
    }

    /**
     * The ground voxel at a point on the ground: the surface voxel nearest to half a block under it along
     * {@code normal}, among the 27 around there; null if there is none.
     */
    static int[] anchor(VoxelView view, Vec3 point, Vec3 normal) {
        Vec3 c = point, n = normal;
        double px = c.x() - 0.5 * n.x(), py = c.y() - 0.5 * n.y(), pz = c.z() - 0.5 * n.z();
        int bx = (int) Math.floor(px), by = (int) Math.floor(py), bz = (int) Math.floor(pz);
        int[] best = null;
        double bestD2 = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int x = bx + dx, y = by + dy, z = bz + dz;
                    if (!Surface.isSurface(view, x, y, z)) {
                        continue;
                    }
                    double ex = x + 0.5 - px, ey = y + 0.5 - py, ez = z + 0.5 - pz;
                    double d2 = ex * ex + ey * ey + ez * ez;
                    if (d2 < bestD2) {
                        bestD2 = d2;
                        best = new int[]{x, y, z};
                    }
                }
            }
        }
        return best;
    }

    /**
     * The thin structure the ground at {@code point} (on the ground, {@code normal} out of it) belongs to: its voxels
     * joined face to face to the ground voxel there within {@code radius} of the point; none if that ground is not
     * thin.
     */
    public static Set<Long> structure(VoxelView view, Vec3 point, Vec3 normal, double radius) {
        int[] anchor = anchor(view, point, normal);
        if (anchor == null || !thin(view, anchor[0], anchor[1], anchor[2])) {
            return Set.of();
        }
        Vec3 c = point;
        Set<Long> seen = new HashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        long start = VoxelPos.pack(anchor[0], anchor[1], anchor[2]);
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty() && seen.size() < MAX_STRUCTURE) {
            long v = queue.poll();
            int x = VoxelPos.x(v), y = VoxelPos.y(v), z = VoxelPos.z(v);
            for (int[] s : STEPS) {
                int nx = x + s[0], ny = y + s[1], nz = z + s[2];
                long key = VoxelPos.pack(nx, ny, nz);
                if (seen.contains(key) || !view.isKnown(nx, ny, nz) || !thin(view, nx, ny, nz)
                        || Vec3.voxelCenter(nx, ny, nz).distanceSquared(c) > radius * radius) {
                    continue;
                }
                seen.add(key);
                queue.add(key);
            }
        }
        return seen;
    }

    /**
     * The open cells around a thin structure its swelling may reach (as far as {@code swell} says each of its voxels
     * may swell out, and a little more as the ground breathes), each with its nearest voxel of the structure (of two as
     * near, the one swelling more) as its source; not those one of the {@code columns} pushed out along its axis may
     * rise through.
     */
    public static List<Fill> fills(VoxelView view, Set<Long> structure, List<SurfacePoint> columns, Swell swell) {
        Map<Long, int[]> axes = new HashMap<>();
        for (SurfacePoint p : columns) {
            axes.put(VoxelPos.pack(p.x(), p.y(), p.z()), axisStep(p.normal()));
        }
        Map<Long, double[]> best = new HashMap<>(); // cell -> {distance, swell, source x, y, z}
        for (long v : structure) {
            int x = VoxelPos.x(v), y = VoxelPos.y(v), z = VoxelPos.z(v);
            double reach = swell.at(x + 0.5, y + 0.5, z + 0.5);
            if (!(reach >= MIN_SWELL)) {
                continue;
            }
            for (int i = 0; i < OFFSETS.length; i++) {
                double length = OFFSET_LENGTHS[i];
                if (length > reach + 1.5) {
                    break; // never reached, even as the ground breathes
                }
                int[] o = OFFSETS[i];
                int qx = x + o[0], qy = y + o[1], qz = z + o[2];
                if (!view.isOpen(qx, qy, qz) || !view.isKnown(qx, qy, qz)) {
                    continue;
                }
                long key = VoxelPos.pack(qx, qy, qz);
                double[] old = best.get(key);
                if (old == null || length < old[0] - 1e-9 || (length < old[0] + 1e-9 && reach > old[1])) {
                    best.put(key, new double[]{length, reach, x, y, z});
                }
            }
        }
        List<Fill> fills = new ArrayList<>(best.size());
        for (Map.Entry<Long, double[]> entry : best.entrySet()) {
            long key = entry.getKey();
            int qx = VoxelPos.x(key), qy = VoxelPos.y(key), qz = VoxelPos.z(key);
            if (risenThrough(axes, qx, qy, qz)) {
                continue;
            }
            double[] b = entry.getValue();
            int sx = (int) b[2], sy = (int) b[3], sz = (int) b[4];
            boolean covered = best.containsKey(VoxelPos.pack(qx, qy + 1, qz)) || view.isSolid(qx, qy + 1, qz);
            int lookY = covered && view.isSolid(sx, sy - 1, sz) && view.isKnown(sx, sy - 1, sz) ? sy - 1 : sy;
            fills.add(new Fill(qx, qy, qz, sx, sy, sz, sx, lookY, sz, b[0]));
        }
        fills.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y())
                : a.z() != b.z() ? Integer.compare(a.z(), b.z()) : Integer.compare(a.x(), b.x()));
        return fills;
    }

    /** Whether a column of the bump (a point pushed out along its axis) may rise through the cell. */
    private static boolean risenThrough(Map<Long, int[]> columns, int x, int y, int z) {
        int reach = (int) Math.ceil(MAX_REACH);
        for (int[] s : STEPS) {
            for (int k = 1; k <= reach; k++) {
                int[] axis = columns.get(VoxelPos.pack(x - k * s[0], y - k * s[1], z - k * s[2]));
                if (axis != null && axis[0] == s[0] && axis[1] == s[1] && axis[2] == s[2]) {
                    return true;
                }
            }
        }
        return false;
    }
}
