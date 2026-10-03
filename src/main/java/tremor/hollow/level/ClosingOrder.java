package tremor.hollow.level;

import java.util.Arrays;

/**
 * The order in which the columns of the copy close (SPEC 9, "Смыкание"): from the edge inwards, each column once the
 * closing radius is below its distance from the centre, moved in or out by up to {@code wobble} blocks of noise, so
 * the closing front is not a perfect cylinder. No column closes before the radius drops below {@code edge} (where the
 * closing starts, after its grace): the ones whose distance, wobble included, is that far or farther (the ring at and
 * beyond the edge, the corners of the box) all close then, outermost first, so getting out later means digging
 * through the closed part all the way. Plain Java.
 */
public final class ClosingOrder {
    /** Size of the bumps of the front: noise per block. */
    private static final double NOISE_SCALE = 0.13;

    private final int minX;
    private final int minZ;
    private final int sizeX;
    private final int sizeZ;
    /** Distance at which each column closes, at most {@code edge}. Indexed {@code x + z * sizeX}, from the minimum. */
    private final float[] keys;
    /** The columns, the first to close first. */
    private final int[] order;
    private int due;

    /**
     * Columns {@code minX..maxX} by {@code minZ..maxZ}, bounds inclusive.
     *
     * @param centreX x of the centre of the copy (continuous: a column's middle is at {@code x + 0.5})
     * @param centreZ z of the centre of the copy
     * @param edge    distance of the edge from the centre: the radius the closing starts from
     * @param wobble  how far the front is moved in and out by noise (blocks)
     */
    public ClosingOrder(int minX, int minZ, int maxX, int maxZ, double centreX, double centreZ, double edge,
                        double wobble, long seed) {
        this.minX = minX;
        this.minZ = minZ;
        sizeX = maxX - minX + 1;
        sizeZ = maxZ - minZ + 1;
        keys = new float[sizeX * sizeZ];
        float[] distances = new float[keys.length];
        Integer[] sorted = new Integer[keys.length];
        for (int z = 0; z < sizeZ; z++) {
            for (int x = 0; x < sizeX; x++) {
                int i = x + z * sizeX;
                double d = Math.hypot(minX + x + 0.5 - centreX, minZ + z + 0.5 - centreZ);
                distances[i] = (float) d;
                keys[i] = (float) Math.min(edge, d + wobble * LevelNoise.at(seed, 3, (minX + x) * NOISE_SCALE, 0.5,
                        (minZ + z) * NOISE_SCALE));
                sorted[i] = i;
            }
        }
        // Farthest first; of the ones that close at the edge, the outermost.
        Arrays.sort(sorted, (a, b) -> keys[a] != keys[b] ? Float.compare(keys[b], keys[a])
                : Float.compare(distances[b], distances[a]));
        order = new int[keys.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = sorted[i];
        }
    }

    /** Number of columns (all of the box: each closes once the radius is below its key). */
    public int size() {
        return order.length;
    }

    /** Moves on to the closing radius {@code radius}: returns how many columns (the first ones in order) are due. */
    public int advance(double radius) {
        while (due < order.length && keys[order[due]] > radius) {
            due++;
        }
        return due;
    }

    /** Columns due so far ({@link #advance}). */
    public int due() {
        return due;
    }

    /** x of the {@code i}-th column in order. */
    public int x(int i) {
        return minX + order[i] % sizeX;
    }

    /** z of the {@code i}-th column in order. */
    public int z(int i) {
        return minZ + order[i] / sizeX;
    }

    /** Whether the column {@code x, z} has closed at {@code radius} (false outside the box). */
    public boolean closed(int x, int z, double radius) {
        int dx = x - minX;
        int dz = z - minZ;
        if (dx < 0 || dz < 0 || dx >= sizeX || dz >= sizeZ) {
            return false;
        }
        float key = keys[dx + dz * sizeX];
        return key > radius;
    }
}
