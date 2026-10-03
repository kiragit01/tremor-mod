package tremor.hollow.level;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Tight places are widened (SPEC 9: "Тесные места (туннель 1×2 и т.п.) раздвигаются — вокруг вырастает пещера, чтобы
 * было где двигаться"): if the open space connected to the player nearby is small ({@link #openSpace},
 * {@link #needed}), an irregular cave is carved around the player ({@link #cave}). Plain Java, on a {@link VoxelGrid}.
 * <p>
 * The cave is a low dome some {@code 2 * radius} blocks across and up to {@code height} high under its middle, which
 * lies up to {@value #OFFSET} blocks off the player, every part of it bent by smooth noise, in blocks: its wall wanders
 * up to {@value #WALL_WOBBLE} blocks in and out (and leans, as the noise changes with the height), its roof sags and
 * bulges by up to {@value #ROOF_WOBBLE} blocks, and its floor dips or rises a block here and there away from the
 * player. Around the player ({@value #FLAT_RADIUS} blocks) the floor stays where the player stands, with room to jump
 * above ({@value #HEADROOM} blocks).
 */
public final class Widening {
    /** How far the wall wanders in and out at most (blocks; smooth noise is mostly within half of it). */
    static final double WALL_WOBBLE = 2.6;
    /** How far the roof sags or bulges at most (blocks). */
    static final double ROOF_WOBBLE = 1.8;
    /** How far the floor dips or rises (blocks, rounded: -1, 0 or 1). */
    private static final double FLOOR_WOBBLE = 2.4;
    /** How far the middle of the cave lies off the player (blocks, either way along x and z). */
    private static final double OFFSET = 1.5;
    /** Size of the bumps of the wall and the roof: noise per block (a bump is some 6 blocks across). */
    private static final double NOISE_SCALE = 0.16;
    /** Size of the dips of the floor: noise per block. */
    private static final double FLOOR_SCALE = 0.23;
    /** Within this distance of the player (blocks, horizontally) the floor is the player's and the roof high. */
    static final double FLAT_RADIUS = 1.5;
    /** The room the player gets: cells open from the feet up within {@link #FLAT_RADIUS}. */
    static final int HEADROOM = 3;

    private Widening() {
    }

    /**
     * What the widening changes: cells to empty (air), and cells to fill: the floor under them, and the fluids, lava
     * and fire next to them ({@link VoxelGrid#leaksAround}).
     */
    public record Cave(Set<Long> carve, Set<Long> fill) {
        public void applyTo(VoxelGrid grid) {
            for (long cell : carve) {
                grid.carve(cell);
            }
            for (long cell : fill) {
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
     * The cave around the player standing at {@code x, y, z} (feet), {@code radius} blocks across from the middle and
     * {@code height} high under it, shaped by the noise of {@code seed} (see the class comment). Each column of it is
     * open from its floor to its roof: every cell there that is not plain open (solid, fluid, fire) is carved, the cell
     * under its floor is filled if it is open, hurts or is too tall to stand on, and every fluid, lava or fire next to
     * a carved cell is filled. Only cells {@code region} allows are carved or floored, and only cells whose neighbours
     * the grid knows ({@link VoxelGrid#interior}) are carved.
     */
    public static Cave cave(VoxelGrid grid, VoxelGrid.Region region, int x, int y, int z, double radius, double height,
                            long seed) {
        Set<Long> carve = new HashSet<>();
        Set<Long> fill = new HashSet<>();
        Random random = new Random(seed);
        double middleX = x + 0.5 + OFFSET * (2 * random.nextDouble() - 1);
        double middleZ = z + 0.5 + OFFSET * (2 * random.nextDouble() - 1);
        int reach = (int) Math.ceil(radius + WALL_WOBBLE + OFFSET);
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                int cx = x + dx;
                int cz = z + dz;
                double d = Math.hypot(cx + 0.5 - middleX, cz + 0.5 - middleZ);
                boolean near = dx * dx + dz * dz <= FLAT_RADIUS * FLAT_RADIUS;
                int bottom = near ? y : y + floorStep(seed, cx, cz);
                // The wall at the height of the floor here: how far out the column may lie.
                double wall = wall(seed, radius, cx, bottom, cz);
                if (!near && d >= wall) {
                    continue;
                }
                double across = Math.min(1, d / wall);
                double roof = height * Math.sqrt(1 - across * across)
                        + ROOF_WOBBLE * LevelNoise.at(seed, 2, cx * NOISE_SCALE, 0.5, cz * NOISE_SCALE);
                int top = y + (int) Math.round(roof);
                if (near) {
                    top = Math.max(top, y + HEADROOM);
                }
                for (int cy = bottom; cy < top; cy++) {
                    // The wall leans: higher up it may lie farther in or out than at the floor.
                    if (!near && cy > bottom && d >= wall(seed, radius, cx, cy, cz)) {
                        break;
                    }
                    if (grid.interior(cx, cy, cz) && region.allows(cx, cy, cz)
                            && grid.flags(cx, cy, cz) != VoxelGrid.OPEN) {
                        carve.add(CellKey.of(cx, cy, cz));
                    }
                }
                if (top > bottom && grid.contains(cx, bottom - 1, cz) && region.allows(cx, bottom - 1, cz)
                        && grid.flags(cx, bottom - 1, cz) != 0) {
                    fill.add(CellKey.of(cx, bottom - 1, cz));
                }
            }
        }
        fill.addAll(grid.leaksAround(carve, Set.of()));
        return new Cave(carve, fill);
    }

    /** How far from the middle the wall lies at the height {@code y} of the column {@code x, z} (blocks). */
    private static double wall(long seed, double radius, int x, int y, int z) {
        return radius + WALL_WOBBLE * LevelNoise.at(seed, 0, x * NOISE_SCALE, y * NOISE_SCALE, z * NOISE_SCALE);
    }

    /** How far the floor of the column {@code x, z} lies below (negative) or above the player's: -1, 0 or 1. */
    static int floorStep(long seed, int x, int z) {
        double step = FLOOR_WOBBLE * LevelNoise.at(seed, 1, x * FLOOR_SCALE, 0.5, z * FLOOR_SCALE);
        return (int) Math.max(-1, Math.min(1, Math.round(step)));
    }
}
