package tremor.core.voxel;

import java.util.Objects;

import tremor.core.VoxelView;
import tremor.core.math.Vec3;

/**
 * Line of sight through the voxel grid (SPEC 5.6 and 11: wander and spawn points the player can see).
 * <p>
 * A segment is clear when no voxel it touches is solid or unknown ({@link VoxelView#isKnown} false), apart from the
 * <em>end voxels</em>: those whose closed cube contains one of the two end points (the voxel containing an interior
 * point; both voxels sharing a face, all voxels sharing an edge or a corner the point lies on). "Touches" is meant
 * conservatively, over closed cubes: a segment through an edge or a corner of the grid touches every voxel around
 * that edge or corner, so a ray squeezing exactly between two solid voxels that meet along an edge is blocked, and a
 * segment running exactly in a face plane touches the voxels on both sides of it (grazing a floor is blocked).
 * <p>
 * The traversal is Amanatides-Woo: the segment is cut where it crosses the grid planes, each crossing time computed
 * directly from the start point (no accumulated error); crossings of several axes within {@link #TIE_EPSILON} blocks
 * of each other count as one crossing of an edge or a corner. It visits about {@code |dx| + |dy| + |dz|} voxels.
 */
public final class LineOfSight {
    /** Longer segments are never clear (bounds the work of one query). */
    public static final double MAX_LENGTH = 256;
    /** Crossings of several grid planes closer than this (in blocks along the segment) are one edge or corner. */
    public static final double TIE_EPSILON = 1e-9;

    private LineOfSight() {
    }

    /**
     * True if the segment {@code from}-{@code to} touches no solid or unknown voxel other than the end voxels (see
     * the class description), and is at most {@link #MAX_LENGTH} long. A segment of length 0 is clear; non-finite
     * coordinates are not.
     */
    public static boolean clear(VoxelView view, Vec3 from, Vec3 to) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        double length = from.distance(to);
        if (!(length <= MAX_LENGTH)) {
            return false; // too long, or NaN
        }
        if (length == 0) {
            return true; // only the end voxels (a non-finite coordinate gives a NaN length)
        }
        double[] start = {from.x(), from.y(), from.z()};
        double[] delta = {to.x() - from.x(), to.y() - from.y(), to.z() - from.z()};
        // Per axis, the voxel layers the current piece of the segment touches: [lo, hi], two layers only for an axis
        // the segment does not move along and whose coordinate is an integer (it runs in a grid plane).
        int[] lo = new int[3], hi = new int[3], step = new int[3];
        for (int i = 0; i < 3; i++) {
            double a = start[i];
            if (delta[i] > 0) {
                step[i] = 1;
                lo[i] = hi[i] = (int) Math.floor(a);
            } else if (delta[i] < 0) {
                step[i] = -1;
                lo[i] = hi[i] = (int) Math.ceil(a) - 1;
            } else {
                hi[i] = (int) Math.floor(a);
                lo[i] = a == hi[i] ? hi[i] - 1 : hi[i];
            }
        }
        double tie = TIE_EPSILON / length;
        double[] next = new double[3];
        boolean[] crossing = new boolean[3];
        int[] cornerLo = new int[3], cornerHi = new int[3];
        while (true) {
            if (!open(view, lo, hi, from, to)) {
                return false;
            }
            // The next grid plane crossed, as a fraction of the segment.
            double t = Double.POSITIVE_INFINITY;
            for (int i = 0; i < 3; i++) {
                if (step[i] != 0) {
                    int plane = step[i] > 0 ? lo[i] + 1 : lo[i];
                    next[i] = (plane - start[i]) / delta[i];
                    t = Math.min(t, next[i]);
                }
            }
            if (t >= 1) {
                return true; // the current piece reaches the end point
            }
            int crossed = 0;
            for (int i = 0; i < 3; i++) {
                crossing[i] = step[i] != 0 && next[i] - t <= tie;
                if (crossing[i]) {
                    crossed++;
                }
            }
            if (crossed >= 2) {
                // Through an edge or a corner: it touches every voxel around it, not just the pieces before and after.
                for (int i = 0; i < 3; i++) {
                    cornerLo[i] = crossing[i] ? Math.min(lo[i], lo[i] + step[i]) : lo[i];
                    cornerHi[i] = crossing[i] ? Math.max(hi[i], hi[i] + step[i]) : hi[i];
                }
                if (!open(view, cornerLo, cornerHi, from, to)) {
                    return false;
                }
            }
            for (int i = 0; i < 3; i++) {
                if (crossing[i]) {
                    lo[i] += step[i];
                    hi[i] += step[i];
                }
            }
        }
    }

    /** True if every voxel of the box {@code [lo, hi]} is open and known, or an end voxel. */
    private static boolean open(VoxelView view, int[] lo, int[] hi, Vec3 from, Vec3 to) {
        for (int y = lo[1]; y <= hi[1]; y++) {
            for (int z = lo[2]; z <= hi[2]; z++) {
                for (int x = lo[0]; x <= hi[0]; x++) {
                    if ((view.isSolid(x, y, z) || !view.isKnown(x, y, z)) && !contains(x, y, z, from)
                            && !contains(x, y, z, to)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Whether the closed cube of voxel {@code (x, y, z)} contains the point. */
    private static boolean contains(int x, int y, int z, Vec3 p) {
        return p.x() >= x && p.x() <= x + 1 && p.y() >= y && p.y() <= y + 1 && p.z() >= z && p.z() <= z + 1;
    }
}
