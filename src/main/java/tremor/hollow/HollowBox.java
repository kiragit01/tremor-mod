package tremor.hollow;

/**
 * An axis-aligned box of blocks, bounds inclusive: the part of the world copied into the hollow for one event
 * (SPEC 9), that copy in the hollow, or the area of a slot that is cleared after the event. Plain Java, so the box
 * math is tested without the game.
 */
public record HollowBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public HollowBox {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Empty box " + minX + " " + minY + " " + minZ + " .. " + maxX + " "
                    + maxY + " " + maxZ);
        }
    }

    /**
     * The box {@code radius} blocks around ({@code x}, {@code z}) horizontally and from {@code below} blocks under
     * to {@code above} blocks over {@code y}, cut to the build height {@code [minBuildY, maxBuildY)}; null if
     * {@code y} itself is outside the build height.
     */
    public static HollowBox around(int x, int y, int z, int radius, int below, int above, int minBuildY,
                                   int maxBuildY) {
        if (y < minBuildY || y >= maxBuildY) {
            return null;
        }
        return new HollowBox(x - radius, Math.max(y - below, minBuildY), z - radius,
                x + radius, Math.min(y + above, maxBuildY - 1), z + radius);
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** Whether the two boxes share a block. */
    public boolean intersects(HollowBox other) {
        return minX <= other.maxX && other.minX <= maxX && minY <= other.maxY && other.minY <= maxY
                && minZ <= other.maxZ && other.minZ <= maxZ;
    }

    public HollowBox offset(int dx, int dy, int dz) {
        return new HollowBox(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /**
     * The box {@code n} blocks bigger on every side, cut to the build height {@code [minBuildY, maxBuildY)} (which
     * this box must lie in).
     */
    public HollowBox grow(int n, int minBuildY, int maxBuildY) {
        return new HollowBox(minX - n, Math.max(minY - n, minBuildY), minZ - n,
                maxX + n, Math.min(maxY + n, maxBuildY - 1), maxZ + n);
    }

    public int minChunkX() {
        return minX >> 4;
    }

    public int maxChunkX() {
        return maxX >> 4;
    }

    public int minChunkZ() {
        return minZ >> 4;
    }

    public int maxChunkZ() {
        return maxZ >> 4;
    }

    /**
     * The whole chunk columns this box touches, {@code margin} more chunks on every side, over the full build height
     * {@code [minBuildY, maxBuildY)}.
     */
    public HollowBox chunkColumns(int margin, int minBuildY, int maxBuildY) {
        return new HollowBox((minChunkX() - margin) << 4, minBuildY, (minChunkZ() - margin) << 4,
                ((maxChunkX() + margin) << 4) + 15, maxBuildY - 1, ((maxChunkZ() + margin) << 4) + 15);
    }

    /** The largest Chebyshev distance, in chunks, from the chunk ({@code chunkX}, {@code chunkZ}) to a chunk of the box. */
    public int chunkDistance(int chunkX, int chunkZ) {
        int x = Math.max(Math.abs(minChunkX() - chunkX), Math.abs(maxChunkX() - chunkX));
        int z = Math.max(Math.abs(minChunkZ() - chunkZ), Math.abs(maxChunkZ() - chunkZ));
        return Math.max(x, z);
    }

    @Override
    public String toString() {
        return minX + " " + minY + " " + minZ + " .. " + maxX + " " + maxY + " " + maxZ;
    }
}
