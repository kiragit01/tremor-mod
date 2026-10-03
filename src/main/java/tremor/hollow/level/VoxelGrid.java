package tremor.hollow.level;

import java.util.HashSet;
import java.util.Set;

/**
 * What the planning of the level (SPEC 9, phase 2: the widened cave, the network of ways) needs to know of each block
 * of a box of the hollow: whether a player passes through it, whether it holds a fluid, whether it hurts, whether it
 * is too tall to stand on. A snapshot taken once (the game fills it from the copy) or built by hand in the tests;
 * cells outside the box read as solid, but are never stood on ({@link #standable}). Plain Java.
 */
public final class VoxelGrid {
    /** No collision: a player passes through (air, plants, torches, fluids). Solid otherwise. */
    public static final int OPEN = 1;
    /** A fluid is in it: open water or lava, or a waterlogged block. */
    public static final int LIQUID = 2;
    /** Hurts or traps whoever is in it or stands on it: lava, fire, magma, cactus, powder snow... */
    public static final int HAZARD = 4;
    /** Solid and higher than a block (a fence, a wall): nobody stands on it or jumps up onto it. */
    public static final int TALL = 8;

    /** Where the planning may change blocks and put the node: inside the copy, away from its edges. */
    @FunctionalInterface
    public interface Region {
        boolean allows(int x, int y, int z);
    }

    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private final int minX;
    private final int minY;
    private final int minZ;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final byte[] cells;

    /** An all-solid grid over the box, bounds inclusive. */
    public VoxelGrid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Empty grid");
        }
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        sizeX = maxX - minX + 1;
        sizeY = maxY - minY + 1;
        sizeZ = maxZ - minZ + 1;
        cells = new byte[sizeX * sizeY * sizeZ];
    }

    private VoxelGrid(VoxelGrid other) {
        minX = other.minX;
        minY = other.minY;
        minZ = other.minZ;
        sizeX = other.sizeX;
        sizeY = other.sizeY;
        sizeZ = other.sizeZ;
        cells = other.cells.clone();
    }

    /** A grid over the same box with the same cells, changed apart from this one (a plan tried out on it). */
    public VoxelGrid copy() {
        return new VoxelGrid(this);
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return minX + sizeX - 1;
    }

    public int maxY() {
        return minY + sizeY - 1;
    }

    public int maxZ() {
        return minZ + sizeZ - 1;
    }

    /** Number of cells; cell indices ({@link #index}) run from 0 below it. */
    public int volume() {
        return cells.length;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && y >= minY && z >= minZ && x < minX + sizeX && y < minY + sizeY && z < minZ + sizeZ;
    }

    /**
     * Whether the cell lies inside the grid with all its face neighbours: what is around it is known, so a cell
     * emptied there cannot open into something the grid does not know of.
     */
    public boolean interior(int x, int y, int z) {
        return x > minX && y > minY && z > minZ && x < minX + sizeX - 1 && y < minY + sizeY - 1
                && z < minZ + sizeZ - 1;
    }

    /** Index of a cell inside the grid, x fastest. */
    public int index(int x, int y, int z) {
        return ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
    }

    /** The flags of a cell; 0 (solid) outside the grid. */
    public int flags(int x, int y, int z) {
        return contains(x, y, z) ? cells[index(x, y, z)] : 0;
    }

    /** Sets the flags of a cell; ignored outside the grid. */
    public void set(int x, int y, int z, int flags) {
        if (contains(x, y, z)) {
            cells[index(x, y, z)] = (byte) flags;
        }
    }

    public boolean open(int x, int y, int z) {
        return (flags(x, y, z) & OPEN) != 0;
    }

    /** Whether a player can pass through the cell unhurt: open, and not lava or fire. */
    public boolean passable(int x, int y, int z) {
        return (flags(x, y, z) & (OPEN | HAZARD)) == OPEN;
    }

    /** Whether the cell is dry: no fluid in it. */
    public boolean dry(int x, int y, int z) {
        return (flags(x, y, z) & LIQUID) == 0;
    }

    /**
     * Whether a player can stand in the cell: it and the one above are open, the one below is solid ground no higher
     * than a block, all three are known (inside the grid), and none of them hurts.
     */
    public boolean standable(int x, int y, int z) {
        if (!contains(x, y - 1, z) || !contains(x, y + 1, z)) {
            return false;
        }
        int feet = flags(x, y, z);
        int head = flags(x, y + 1, z);
        int ground = flags(x, y - 1, z);
        return (feet & OPEN) != 0 && (head & OPEN) != 0 && (ground & (OPEN | TALL)) == 0
                && ((feet | head | ground) & HAZARD) == 0;
    }

    /**
     * The cells to fill so that nothing flows or burns into the cells {@code carved} once they are emptied: each face
     * neighbour of a carved cell that holds a fluid, or is open and hurts (lava, fire), unless it is carved too or in
     * {@code keep}. Neighbours outside the grid are not known, so they are left alone (carve only {@link #interior}
     * cells).
     */
    public Set<Long> leaksAround(Set<Long> carved, Set<Long> keep) {
        Set<Long> leaks = new HashSet<>();
        for (long cell : carved) {
            int x = CellKey.x(cell);
            int y = CellKey.y(cell);
            int z = CellKey.z(cell);
            for (int[] face : FACES) {
                int nx = x + face[0];
                int ny = y + face[1];
                int nz = z + face[2];
                int flags = flags(nx, ny, nz);
                if ((flags & LIQUID) == 0 && (flags & (OPEN | HAZARD)) != (OPEN | HAZARD)) {
                    continue;
                }
                long next = CellKey.of(nx, ny, nz);
                if (contains(nx, ny, nz) && !carved.contains(next) && !keep.contains(next)) {
                    leaks.add(next);
                }
            }
        }
        return leaks;
    }

    /** The cell becomes empty: air. */
    public void carve(long key) {
        set(CellKey.x(key), CellKey.y(key), CellKey.z(key), OPEN);
    }

    /** The cell becomes solid. */
    public void fill(long key) {
        set(CellKey.x(key), CellKey.y(key), CellKey.z(key), 0);
    }
}
