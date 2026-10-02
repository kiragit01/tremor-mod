package tremor.core.surface;

import tremor.core.VoxelView;

/** Surface ("skin") voxels, SPEC 5.1. */
public final class Surface {
    private Surface() {
    }

    /**
     * A surface voxel is a solid voxel with at least one open neighbour across its 6 faces. Voxels the view knows
     * nothing about ({@link VoxelView#isKnown}) are never surface.
     */
    public static boolean isSurface(VoxelView view, int x, int y, int z) {
        return view.isSolid(x, y, z) && view.isKnown(x, y, z)
                && (view.isOpen(x, y + 1, z) || view.isOpen(x, y - 1, z)
                || view.isOpen(x + 1, y, z) || view.isOpen(x - 1, y, z)
                || view.isOpen(x, y, z + 1) || view.isOpen(x, y, z - 1));
    }
}
