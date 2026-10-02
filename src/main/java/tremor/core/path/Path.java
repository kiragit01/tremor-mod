package tremor.core.path;

import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;

/**
 * A route over the surface graph: {@code nodes[0]} is the start, consecutive nodes are joined by graph edges,
 * {@code dive[i]} tells whether the edge {@code nodes[i] -> nodes[i + 1]} goes through rock.
 *
 * @param complete false if the search gave up (node limit / unreachable goal) and this leads to the best node found
 */
public record Path(long[] nodes, boolean[] dive, boolean complete) {
    public Path {
        if (nodes.length == 0 || dive.length != Math.max(0, nodes.length - 1)) {
            throw new IllegalArgumentException("nodes " + nodes.length + ", dive flags " + dive.length);
        }
    }

    public int size() {
        return nodes.length;
    }

    public long node(int i) {
        return nodes[i];
    }

    public Vec3 point(int i) {
        return VoxelPos.center(nodes[i]);
    }

    public long last() {
        return nodes[nodes.length - 1];
    }

    /** Sum of the straight edge lengths. */
    public double length() {
        double sum = 0;
        for (int i = 1; i < nodes.length; i++) {
            sum += point(i - 1).distance(point(i));
        }
        return sum;
    }
}
