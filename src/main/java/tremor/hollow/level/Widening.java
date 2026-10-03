package tremor.hollow.level;

import java.util.HashSet;
import java.util.Set;

/**
 * Tight places are widened (SPEC 9: "Тесные места (туннель 1×2 и т.п.) раздвигаются — вокруг вырастает пещера, чтобы
 * было где двигаться"): if the open space connected to the player nearby is small ({@link #openSpace},
 * {@link #needed}), an irregular cave is carved around the player ({@link #cave}): a dome of about
 * {@code 2 * radius} blocks across and {@code height} high whose wall is shaped by noise, on a floor under the player.
 * Plain Java, on a {@link VoxelGrid}.
 */
public final class Widening {
    /** How far the wall of the cave wanders in and out, as a share of its radii. */
    private static final double WOBBLE = 0.35;
    /** Size of the bumps of the wall: noise per block. */
    private static final double NOISE_SCALE = 0.3;

    private Widening() {
    }

    /** What the widening changes: cells to empty (air), and cells to fill under them (the floor). */
    public record Cave(Set<Long> carve, Set<Long> floor) {
        public void applyTo(VoxelGrid grid) {
            for (long cell : carve) {
                grid.carve(cell);
            }
            for (long cell : floor) {
                grid.fill(cell);
            }
        }
    }

    /**
     * The open cells connected (through faces) to the player's feet ({@code x, y, z}) and head, no farther than
     * {@code radius} blocks from the feet, counted up to {@code limit}.
     */
    public static int openSpace(VoxelGrid grid, int x, int y, int z, int radius, int limit) {
        boolean[] seen = new boolean[grid.volume()];
        int[] queue = new int[grid.volume()];
        int head = 0;
        int tail = 0;
        for (int dy = 0; dy <= 1; dy++) {
            if (grid.open(x, y + dy, z)) {
                int i = grid.index(x, y + dy, z);
                seen[i] = true;
                queue[tail++] = i;
            }
        }
        int sizeX = grid.maxX() - grid.minX() + 1;
        int sizeZ = grid.maxZ() - grid.minZ() + 1;
        int r2 = radius * radius;
        while (head < tail && tail < limit) {
            int i = queue[head++];
            int cx = grid.minX() + i % sizeX;
            int cz = grid.minZ() + i / sizeX % sizeZ;
            int cy = grid.minY() + i / sizeX / sizeZ;
            for (int face = 0; face < 6; face++) {
                int nx = cx + (face == 0 ? 1 : face == 1 ? -1 : 0);
                int ny = cy + (face == 2 ? 1 : face == 3 ? -1 : 0);
                int nz = cz + (face == 4 ? 1 : face == 5 ? -1 : 0);
                int dx = nx - x;
                int dy = ny - y;
                int dz = nz - z;
                if (dx * dx + dy * dy + dz * dz > r2 || !grid.open(nx, ny, nz)) {
                    continue;
                }
                int n = grid.index(nx, ny, nz);
                if (!seen[n]) {
                    seen[n] = true;
                    queue[tail++] = n;
                }
            }
        }
        return Math.min(tail, limit);
    }

    /** Whether a place with {@code openCells} of open space around the player is tight enough to widen. */
    public static boolean needed(int openCells, int threshold) {
        return openCells < threshold;
    }

    /**
     * The cave around the player standing at {@code x, y, z} (feet): a half ellipsoid from the feet layer up, of
     * horizontal radius {@code radius} and height {@code height}, its wall moved in and out by noise; the player's own
     * two cells always belong to it. Every cell in it that is not plain open (solid, fluid, fire) is carved; every cell
     * under its lowest layer that is open or hurts is filled, so the player stands on a floor. Only cells
     * {@code region} allows change.
     */
    public static Cave cave(VoxelGrid grid, VoxelGrid.Region region, int x, int y, int z, double radius, double height,
                            long seed) {
        Set<Long> carve = new HashSet<>();
        Set<Long> floor = new HashSet<>();
        int reach = (int) Math.ceil(radius * (1 + WOBBLE));
        int top = (int) Math.ceil(height * (1 + WOBBLE));
        for (int dy = 0; dy < top; dy++) {
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dx = -reach; dx <= reach; dx++) {
                    int cx = x + dx;
                    int cy = y + dy;
                    int cz = z + dz;
                    double across = (dx * dx + dz * dz) / (radius * radius);
                    double up = (dy + 0.5) / height;
                    double edge = 1 + WOBBLE * LevelNoise.at(seed, 0, cx * NOISE_SCALE, cy * NOISE_SCALE,
                            cz * NOISE_SCALE);
                    boolean player = dx == 0 && dz == 0 && dy <= 1;
                    if (!player && across + up * up > edge || !grid.contains(cx, cy, cz) || !region.allows(cx, cy, cz)) {
                        continue;
                    }
                    if (grid.flags(cx, cy, cz) != VoxelGrid.OPEN) {
                        carve.add(CellKey.of(cx, cy, cz));
                    }
                    if (dy == 0 && grid.contains(cx, cy - 1, cz) && region.allows(cx, cy - 1, cz)
                            && grid.flags(cx, cy - 1, cz) != 0) {
                        floor.add(CellKey.of(cx, cy - 1, cz));
                    }
                }
            }
        }
        return new Cave(carve, floor);
    }
}
