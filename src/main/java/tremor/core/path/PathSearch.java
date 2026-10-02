package tremor.core.path;

import java.util.Arrays;
import java.util.Objects;

import tremor.core.graph.Graph;
import tremor.core.math.VoxelPos;

/**
 * Incremental A* over a {@link Graph} (SPEC 5.3), so a search can be spread over several server ticks.
 * <p>
 * Cost of an edge = its length, times {@code diveCostFactor} for dive edges. Heuristic = straight distance between
 * voxel centres (admissible because every factor is >= 1). Ties are broken deterministically.
 * <p>
 * The search stops with
 * <ul>
 *   <li>{@link Status#FOUND} when the goal is expanded: {@link #path()} is the cheapest path, {@code complete};</li>
 *   <li>{@link Status#PARTIAL} when {@code maxExpansions} nodes were expanded, or the reachable part of the graph is
 *   exhausted, without reaching the goal: {@link #path()} leads to the expanded node closest to the goal (by
 *   straight distance; ties: cheaper path) and is not {@code complete}. SPEC: "move to the best node and replan";</li>
 *   <li>{@link Status#FAILED} when the start is not a node, or no node closer to the goal than the start was found.</li>
 * </ul>
 * Not thread-safe; the graph must not change during the search.
 */
public final class PathSearch {
    public enum Status { RUNNING, FOUND, PARTIAL, FAILED }

    private static final int INITIAL_CAPACITY = 256;
    /** {@link #heapPos} of a node that has been expanded. */
    private static final int CLOSED = -1;

    private final Graph graph;
    private final long start;
    private final long goal;
    private final int maxExpansions;
    private final double diveCostFactor;
    private final int goalX, goalY, goalZ;

    // Per discovered node, indexed in discovery order (index 0 is the start).
    private int count;
    private long[] nodeKey = new long[INITIAL_CAPACITY];
    private double[] cost = new double[INITIAL_CAPACITY];
    private double[] heuristic = new double[INITIAL_CAPACITY];
    private int[] parent = new int[INITIAL_CAPACITY];
    private boolean[] viaDive = new boolean[INITIAL_CAPACITY];
    /** Position in {@link #heap}, or {@link #CLOSED}. */
    private int[] heapPos = new int[INITIAL_CAPACITY];

    // Open-addressing hash map node key -> index + 1 (0 = empty slot), linear probing, load factor <= 1/2.
    private long[] tableKeys = new long[INITIAL_CAPACITY * 2];
    private int[] tableValues = new int[INITIAL_CAPACITY * 2];

    // Binary min-heap of node indices, ordered by (f, h, discovery index).
    private int[] heap = new int[INITIAL_CAPACITY];
    private int heapSize;

    private Status status;
    private int expanded;
    private int best;
    private long bestDistanceSquared;
    private int found = -1;
    private Path path;

    /** Node being expanded, read by {@link #relax}. */
    private int current;
    private final Graph.EdgeConsumer relax = this::relax;

    /** @throws IllegalArgumentException if {@code maxExpansions < 1} or {@code diveCostFactor < 1} */
    public PathSearch(Graph graph, long start, long goal, int maxExpansions, double diveCostFactor) {
        if (maxExpansions < 1) {
            throw new IllegalArgumentException("maxExpansions " + maxExpansions);
        }
        if (!(diveCostFactor >= 1)) {
            throw new IllegalArgumentException("diveCostFactor " + diveCostFactor);
        }
        this.graph = Objects.requireNonNull(graph, "graph");
        this.start = start;
        this.goal = goal;
        this.maxExpansions = maxExpansions;
        this.diveCostFactor = diveCostFactor;
        this.goalX = VoxelPos.x(goal);
        this.goalY = VoxelPos.y(goal);
        this.goalZ = VoxelPos.z(goal);
        if (graph.isNode(start)) {
            int index = add(start, 0, -1, false);
            push(index);
            best = index;
            bestDistanceSquared = distanceSquaredToGoal(start);
            status = Status.RUNNING;
        } else {
            status = Status.FAILED;
        }
    }

    /** Expands up to {@code budget} more nodes (at least one if still running) and returns the status. */
    public Status step(int budget) {
        int remaining = Math.max(1, budget);
        while (status == Status.RUNNING && remaining-- > 0) {
            expandNext();
        }
        return status;
    }

    /** Runs to completion. */
    public Status run() {
        while (status == Status.RUNNING) {
            expandNext();
        }
        return status;
    }

    public Status status() {
        return status;
    }

    public int expanded() {
        return expanded;
    }

    public long start() {
        return start;
    }

    public long goal() {
        return goal;
    }

    /** @throws IllegalStateException unless the status is FOUND or PARTIAL */
    public Path path() {
        if (status != Status.FOUND && status != Status.PARTIAL) {
            throw new IllegalStateException("no path: " + status);
        }
        if (path == null) {
            path = reconstruct(status == Status.FOUND ? found : best, status == Status.FOUND);
        }
        return path;
    }

    // ---- search ----------------------------------------------------------------------------------------------------

    private void expandNext() {
        int node = pop();
        heapPos[node] = CLOSED;
        expanded++;
        long key = nodeKey[node];
        if (key == goal) {
            found = node;
            status = Status.FOUND;
            return;
        }
        long d2 = distanceSquaredToGoal(key);
        if (d2 < bestDistanceSquared || d2 == bestDistanceSquared && cost[node] < cost[best]) {
            best = node;
            bestDistanceSquared = d2;
        }
        current = node;
        graph.forEachEdge(key, relax);
        if (heapSize == 0 || expanded >= maxExpansions) {
            // The start is the first node expanded, so "best is the start" means nothing closer was found.
            status = best == 0 ? Status.FAILED : Status.PARTIAL;
        }
    }

    private void relax(long to, double length, boolean dive) {
        double g = cost[current] + (dive ? length * diveCostFactor : length);
        int index = indexOf(to);
        if (index < 0) {
            push(add(to, g, current, dive));
        } else if (heapPos[index] != CLOSED && g < cost[index]) {
            // A closed node is final: the heuristic is consistent (lengths are straight distances, factors >= 1).
            cost[index] = g;
            parent[index] = current;
            viaDive[index] = dive;
            siftUp(heapPos[index]);
        }
    }

    private long distanceSquaredToGoal(long node) {
        long dx = VoxelPos.x(node) - goalX;
        long dy = VoxelPos.y(node) - goalY;
        long dz = VoxelPos.z(node) - goalZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private Path reconstruct(int target, boolean complete) {
        int size = 0;
        for (int i = target; i >= 0; i = parent[i]) {
            size++;
        }
        long[] nodes = new long[size];
        boolean[] dive = new boolean[size - 1];
        int i = target;
        for (int k = size - 1; k >= 0; k--) {
            nodes[k] = nodeKey[i];
            if (k > 0) {
                dive[k - 1] = viaDive[i];
            }
            i = parent[i];
        }
        return new Path(nodes, dive, complete);
    }

    // ---- node storage ----------------------------------------------------------------------------------------------

    private int add(long key, double g, int from, boolean dive) {
        if (count == nodeKey.length) {
            int capacity = count * 2;
            nodeKey = Arrays.copyOf(nodeKey, capacity);
            cost = Arrays.copyOf(cost, capacity);
            heuristic = Arrays.copyOf(heuristic, capacity);
            parent = Arrays.copyOf(parent, capacity);
            viaDive = Arrays.copyOf(viaDive, capacity);
            heapPos = Arrays.copyOf(heapPos, capacity);
            heap = Arrays.copyOf(heap, capacity);
        }
        int index = count++;
        nodeKey[index] = key;
        cost[index] = g;
        heuristic[index] = Math.sqrt(distanceSquaredToGoal(key));
        parent[index] = from;
        viaDive[index] = dive;
        if (count * 2 > tableKeys.length) {
            rehash(tableKeys.length * 2);
        }
        insert(key, index);
        return index;
    }

    private int indexOf(long key) {
        int mask = tableKeys.length - 1;
        for (int slot = hash(key) & mask; ; slot = (slot + 1) & mask) {
            int value = tableValues[slot];
            if (value == 0) {
                return -1;
            }
            if (tableKeys[slot] == key) {
                return value - 1;
            }
        }
    }

    private void insert(long key, int index) {
        int mask = tableKeys.length - 1;
        int slot = hash(key) & mask;
        while (tableValues[slot] != 0) {
            slot = (slot + 1) & mask;
        }
        tableKeys[slot] = key;
        tableValues[slot] = index + 1;
    }

    private void rehash(int capacity) {
        long[] oldKeys = tableKeys;
        int[] oldValues = tableValues;
        tableKeys = new long[capacity];
        tableValues = new int[capacity];
        for (int slot = 0; slot < oldKeys.length; slot++) {
            if (oldValues[slot] != 0) {
                insert(oldKeys[slot], oldValues[slot] - 1);
            }
        }
    }

    /** Murmur3 finalizer: packed positions differ mostly in a few bit ranges, so they need a good mix. */
    private static int hash(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        key *= 0xc4ceb9fe1a85ec53L;
        key ^= key >>> 33;
        return (int) key;
    }

    // ---- open set --------------------------------------------------------------------------------------------------

    /** Heap order: lower f, then lower h (deeper node), then earlier discovery. */
    private boolean before(int a, int b) {
        double fa = cost[a] + heuristic[a];
        double fb = cost[b] + heuristic[b];
        if (fa != fb) {
            return fa < fb;
        }
        if (heuristic[a] != heuristic[b]) {
            return heuristic[a] < heuristic[b];
        }
        return a < b;
    }

    private void push(int node) {
        heap[heapSize] = node;
        heapPos[node] = heapSize;
        heapSize++;
        siftUp(heapSize - 1);
    }

    private int pop() {
        int top = heap[0];
        heapSize--;
        if (heapSize > 0) {
            heap[0] = heap[heapSize];
            heapPos[heap[0]] = 0;
            siftDown(0);
        }
        return top;
    }

    private void siftUp(int pos) {
        int node = heap[pos];
        while (pos > 0) {
            int parentPos = (pos - 1) >>> 1;
            int other = heap[parentPos];
            if (!before(node, other)) {
                break;
            }
            heap[pos] = other;
            heapPos[other] = pos;
            pos = parentPos;
        }
        heap[pos] = node;
        heapPos[node] = pos;
    }

    private void siftDown(int pos) {
        int node = heap[pos];
        while (true) {
            int child = 2 * pos + 1;
            if (child >= heapSize) {
                break;
            }
            if (child + 1 < heapSize && before(heap[child + 1], heap[child])) {
                child++;
            }
            int other = heap[child];
            if (!before(other, node)) {
                break;
            }
            heap[pos] = other;
            heapPos[other] = pos;
            pos = child;
        }
        heap[pos] = node;
        heapPos[node] = pos;
    }
}
