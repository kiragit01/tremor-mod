package tremor.core.path;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

import tremor.core.graph.Graph;
import tremor.core.math.VoxelPos;

/** Small {@link Graph}s for the search tests. */
final class TestGraphs {
    private TestGraphs() {
    }

    record Edge(long to, double length, boolean dive) {
    }

    static long p(int x, int y, int z) {
        return VoxelPos.pack(x, y, z);
    }

    static double distance(long a, long b) {
        return VoxelPos.center(a).distance(VoxelPos.center(b));
    }

    /** Explicit adjacency lists; edges are added in both directions with length = distance between the centres. */
    static final class MapGraph implements Graph {
        private final Map<Long, List<Edge>> edges = new LinkedHashMap<>();

        MapGraph node(long node) {
            edges.computeIfAbsent(node, k -> new ArrayList<>());
            return this;
        }

        MapGraph edge(long a, long b, boolean dive) {
            node(a).node(b);
            double length = distance(a, b);
            edges.get(a).add(new Edge(b, length, dive));
            edges.get(b).add(new Edge(a, length, dive));
            return this;
        }

        Set<Long> nodes() {
            return edges.keySet();
        }

        List<Edge> edges(long node) {
            return edges.get(node);
        }

        @Override
        public boolean isNode(long node) {
            return edges.containsKey(node);
        }

        @Override
        public void forEachEdge(long node, EdgeConsumer consumer) {
            for (Edge e : edges.get(node)) {
                consumer.accept(e.to(), e.length(), e.dive());
            }
        }
    }

    /**
     * Implicit, unbounded grid over the voxels accepted by {@code nodes}: each node is joined to the accepted voxels
     * among its 26 neighbours (or 6 when {@code faceOnly}), all skin edges.
     */
    static final class GridGraph implements Graph {
        private final LongPredicate nodes;
        private final boolean faceOnly;

        GridGraph(LongPredicate nodes, boolean faceOnly) {
            this.nodes = nodes;
            this.faceOnly = faceOnly;
        }

        /** The plane {@code y = 0} without the given blocked voxels, 8-connected. */
        static GridGraph plane(Set<Long> blocked) {
            return new GridGraph(n -> VoxelPos.y(n) == 0 && !blocked.contains(n), false);
        }

        @Override
        public boolean isNode(long node) {
            return nodes.test(node);
        }

        @Override
        public void forEachEdge(long node, EdgeConsumer consumer) {
            int x = VoxelPos.x(node), y = VoxelPos.y(node), z = VoxelPos.z(node);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int m = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                        if (m == 0 || faceOnly && m > 1) {
                            continue;
                        }
                        long to = VoxelPos.pack(x + dx, y + dy, z + dz);
                        if (nodes.test(to)) {
                            consumer.accept(to, Math.sqrt(m), false);
                        }
                    }
                }
            }
        }
    }

    /** Wraps a graph and records the nodes whose edges were asked for (the expanded nodes, except a found goal). */
    static final class Recording implements Graph {
        final Graph graph;
        final Set<Long> expanded = new LinkedHashSet<>();

        Recording(Graph graph) {
            this.graph = graph;
        }

        @Override
        public boolean isNode(long node) {
            return graph.isNode(node);
        }

        @Override
        public void forEachEdge(long node, EdgeConsumer consumer) {
            if (!expanded.add(node)) {
                throw new AssertionError("expanded twice: " + VoxelPos.toString(node));
            }
            graph.forEachEdge(node, consumer);
        }
    }

    /** The first edge {@code a -> b} with the given dive flag as reported by the graph, or null. */
    static Edge findEdge(Graph graph, long a, long b, boolean dive) {
        Edge[] found = new Edge[1];
        graph.forEachEdge(a, (to, length, isDive) -> {
            if (to == b && isDive == dive && found[0] == null) {
                found[0] = new Edge(to, length, isDive);
            }
        });
        return found[0];
    }

    /**
     * Cost of a path under the search's cost model; fails unless consecutive nodes are joined by an edge with the
     * path's dive flag. (A pair may have several edges.)
     */
    static double cost(Graph graph, Path path, double diveCostFactor) {
        double sum = 0;
        for (int i = 0; i + 1 < path.size(); i++) {
            Edge e = findEdge(graph, path.node(i), path.node(i + 1), path.dive()[i]);
            if (e == null) {
                throw new AssertionError("no edge " + VoxelPos.toString(path.node(i)) + " -> "
                        + VoxelPos.toString(path.node(i + 1)) + " with dive = " + path.dive()[i]);
            }
            sum += e.dive() ? e.length() * diveCostFactor : e.length();
        }
        return sum;
    }

    /** Brute-force Dijkstra (O(n²), no heap) over a finite graph: cheapest cost from start to every node. */
    static Map<Long, Double> dijkstra(MapGraph graph, long start, double diveCostFactor) {
        Map<Long, Double> dist = new LinkedHashMap<>();
        Set<Long> done = new LinkedHashSet<>();
        dist.put(start, 0.0);
        while (true) {
            long bestNode = 0;
            double best = Double.POSITIVE_INFINITY;
            for (Map.Entry<Long, Double> e : dist.entrySet()) {
                if (!done.contains(e.getKey()) && e.getValue() < best) {
                    best = e.getValue();
                    bestNode = e.getKey();
                }
            }
            if (best == Double.POSITIVE_INFINITY) {
                return dist;
            }
            done.add(bestNode);
            for (Edge e : graph.edges(bestNode)) {
                double g = best + (e.dive() ? e.length() * diveCostFactor : e.length());
                if (g < dist.getOrDefault(e.to(), Double.POSITIVE_INFINITY)) {
                    dist.put(e.to(), g);
                }
            }
        }
    }
}
