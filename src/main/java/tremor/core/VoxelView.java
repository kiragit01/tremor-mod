package tremor.core;

/**
 * Read-only access to the voxel world, as seen by the entity logic.
 * <p>
 * This is the only bridge between {@code tremor.core} and the game: the core never touches Minecraft classes,
 * so all of its math (shape, normals, surface graph, path finding, hearing) can be unit-tested on synthetic grids.
 * Implementations must be cheap to query and must never trigger chunk loading.
 */
public interface VoxelView {
    /** Solid voxels form the "skin" the entity crawls along. Everything else is open space. */
    boolean isSolid(int x, int y, int z);

    /** Vibration conductivity of the voxel, {@code 1.0} = baseline (dirt-like). Used by hearing (stage 2). */
    float conductivity(int x, int y, int z);

    /** Protected voxels must never be modified by world-changing events (block entities, {@code #tremor:protected}). */
    boolean isProtected(int x, int y, int z);

    default boolean isOpen(int x, int y, int z) {
        return !isSolid(x, y, z);
    }

    /**
     * {@code false} for voxels the view has no data for (unloaded chunks, outside the world). Such voxels read as
     * solid, so nothing can pass through them, but they are never part of the surface either: the entity must not
     * walk on the edge of the loaded world or on the floor of the void.
     */
    default boolean isKnown(int x, int y, int z) {
        return true;
    }
}
