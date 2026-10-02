package tremor.core.graph;

/**
 * A graph over voxels, as searched by {@link tremor.core.path.PathSearch}. Nodes are packed voxel positions
 * ({@link tremor.core.math.VoxelPos}). Implementations may compute edges lazily and must be deterministic.
 */
public interface Graph {
    boolean isNode(long node);

    /** Calls {@code consumer} for every edge leaving {@code node} (which must be a node), in a fixed order. */
    void forEachEdge(long node, EdgeConsumer consumer);

    @FunctionalInterface
    interface EdgeConsumer {
        /**
         * @param to     the neighbouring node
         * @param length straight distance between the voxel centres (1, sqrt 2, sqrt 3, or the dive length)
         * @param dive   true if the edge goes through solid rock (SPEC 5.4), the bump is hidden while on it
         */
        void accept(long to, double length, boolean dive);
    }
}
