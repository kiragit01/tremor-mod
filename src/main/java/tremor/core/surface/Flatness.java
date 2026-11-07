package tremor.core.surface;

import tremor.core.VoxelView;

/**
 * Whether a floor is flat enough for something the tremor sets on it to read clearly (the user: listeners left in pits
 * and on ledges, where their middle could not be told; quicksand made in a small pit a few blocks deep): around the
 * floor voxel every column within the radius has its ground within {@code step} blocks of it and a share of them level
 * with it, and nothing rises over it higher than that (no pit's rim, no wall). A ledge's edge, a drop or a pit deeper
 * than the step fail (for an ambush steps of one or two are fine, the user).
 */
public final class Flatness {
    private Flatness() {
    }

    /**
     * Whether the floor voxel {@code (x, y, z)} (solid, open above) is flat within {@code radius} blocks: the ground
     * of every column there within {@code step} blocks of it, at least {@code levelShare} of them level with it.
     */
    public static boolean flat(VoxelView view, int x, int y, int z, double radius, int step, double levelShare) {
        if (!view.isSolid(x, y, z) || !view.isOpen(x, y + 1, z)) {
            return false;
        }
        int r = (int) Math.ceil(radius), all = 0, level = 0;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                int top = top(view, x + dx, y, z + dz, step);
                if (top == Integer.MIN_VALUE) {
                    return false;
                }
                all++;
                level += top == y ? 1 : 0;
            }
        }
        return level >= levelShare * all;
    }

    /** The ground of column {@code (x, z)} within {@code step} of {@code y}: solid, two open over it; or MIN_VALUE. */
    private static int top(VoxelView view, int x, int y, int z, int step) {
        for (int ty = y + step; ty >= y - step; ty--) {
            if (view.isSolid(x, ty, z) && view.isOpen(x, ty + 1, z) && view.isOpen(x, ty + 2, z)) {
                return ty;
            }
        }
        return Integer.MIN_VALUE;
    }
}
