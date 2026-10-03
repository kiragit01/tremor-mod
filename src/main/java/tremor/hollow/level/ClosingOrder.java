package tremor.hollow.level;

import java.util.Arrays;

/**
 * The order in which the columns of the copy close (SPEC 9, "Смыкание"): from the edge inwards, each column once the
 * closing radius is below its distance from the centre, moved in or out by up to {@code wobble} blocks of noise, so
 * the closing front is not a perfect cylinder. Columns at or beyond {@code edge} are left out: a player who gets there
 * is out ({@code Outcomes.edgeEscape}), so filling them would be work for nothing. Plain Java.
 */
public final class ClosingOrder {
    /** Size of the bumps of the front: noise per block. */
    private static final double NOISE_SCALE = 0.13;

    private final int minX;
    private final int minZ;
    private final int sizeX;
    private final int sizeZ;
    /** Distance at which each column closes; NaN for columns left out. Indexed {@code x + z * sizeX}, from the minimum. */
    private final float[] keys;
    /** The columns left in, farthest first. */
    private final int[] order;
    private int due;

    /**
     * Columns {@code minX..maxX} by {@code minZ..maxZ}, bounds inclusive.
     *
     * @param centreX x of the centre of the copy (continuous: a column's middle is at {@code x + 0.5})
     * @param centreZ z of the centre of the copy
     * @param edge    distance of the edge from the centre
     * @param wobble  how far the front is moved in and out by noise (blocks)
     */
    public ClosingOrder(int minX, int minZ, int maxX, int maxZ, double centreX, double centreZ, double edge,
                        double wobble, long seed) {
        this.minX = minX;
        this.minZ = minZ;
        sizeX = maxX - minX + 1;
        sizeZ = maxZ - minZ + 1;
        keys = new float[sizeX * sizeZ];
        int[] columns = new int[keys.length];
        int count = 0;
        for (int z = 0; z < sizeZ; z++) {
            for (int x = 0; x < sizeX; x++) {
                int i = x + z * sizeX;
                double d = Math.hypot(minX + x + 0.5 - centreX, minZ + z + 0.5 - centreZ);
                if (d >= edge) {
                    keys[i] = Float.NaN;
                    continue;
                }
                keys[i] = (float) (d + wobble * LevelNoise.at(seed, 3, (minX + x) * NOISE_SCALE, 0.5,
                        (minZ + z) * NOISE_SCALE));
                columns[count++] = i;
            }
        }
        Integer[] sorted = new Integer[count];
        for (int i = 0; i < count; i++) {
            sorted[i] = columns[i];
        }
        Arrays.sort(sorted, (a, b) -> Float.compare(keys[b], keys[a]));
        order = new int[count];
        for (int i = 0; i < count; i++) {
            order[i] = sorted[i];
        }
    }

    /** Number of columns that close at some radius. */
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

    /** Whether the column {@code x, z} has closed at {@code radius} (false for columns left out or outside). */
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
