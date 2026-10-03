package tremor.hollow.level;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

/**
 * Where the node goes and the way to it (SPEC 9, "Узел": "где-то в изнанке появляется пульсирующий блок-«узел»... К
 * узлу всегда ведёт проход"). Plain Java, on a {@link VoxelGrid}.
 * <p>
 * The walk from the player ({@link #distances}) goes over the cells a player can stand in, a block aside at a time:
 * on the level, a block up (with room to jump), or down a drop of up to {@value #MAX_DROP} blocks. If some cell the
 * region allows is between {@code minLength} and {@code maxLength} steps away, the node goes into one of them, at
 * random: in the open space the player can reach. Otherwise a tunnel is carved from the farthest cell reached: a worm
 * two blocks wide and three high that winds by noise and goes up and down a block now and then, ending in a small
 * chamber with the node, the whole way about {@code minLength}..{@code maxLength} steps long. A worm that would leave
 * the region turns aside; one that cannot ends early, and the node is closer.
 * <p>
 * The way ({@link Plan#way}) is every open cell the player passes through on it: the feet and head cells of the walk,
 * the room for a jump up and the column of a drop, and the whole tunnel and chamber. Nothing may ever fill it.
 */
public final class WayPlanner {
    /** Longest drop the walk takes. */
    static final int MAX_DROP = 3;
    /** Shortest tunnel: a few steps, so the chamber does not open right where the walk ended. */
    static final int MIN_TUNNEL = 6;
    /** How far the tunnel's heading swings either way (radians). */
    private static final double TURN = 1.8;
    /** How fast the heading swings: noise per step. */
    private static final double TURN_RATE = 0.11;
    /** How far the tunnel wanders up and down (blocks), and how fast. */
    private static final double RISE = 3;
    private static final double RISE_RATE = 0.07;
    /** Steps between two changes of height of the tunnel. */
    private static final int LEVEL_STEPS = 3;
    /** Times in a row a tunnel may be turned away from the edge of the region before it ends. */
    private static final int MAX_TURNS = 8;
    /** The chamber at the end: horizontal radius and height. */
    private static final double CHAMBER_RADIUS = 2.6;
    private static final double CHAMBER_HEIGHT = 3.6;
    private static final int[] DX = {1, 0, -1, 0};
    private static final int[] DZ = {0, 1, 0, -1};

    private WayPlanner() {
    }

    /**
     * What to change and keep: the node's cell, the cells to empty ({@code carve}, the tunnel and chamber) and to fill
     * ({@code floor}, under them), and the way to keep open (which holds {@code carve}).
     *
     * @param length steps from the player to the node
     * @param tunnel whether the way had to be carved
     */
    public record Plan(long node, Set<Long> carve, Set<Long> floor, Set<Long> way, int length, boolean tunnel) {
        /** Writes the plan into the grid: the node is solid. */
        public void applyTo(VoxelGrid grid) {
            for (long cell : carve) {
                grid.carve(cell);
            }
            for (long cell : floor) {
                grid.fill(cell);
            }
            grid.fill(node);
        }
    }

    /** The walk from a cell: steps to every cell reached ({@code -1}: not reached), and where each step came from. */
    record Walk(VoxelGrid grid, int[] distance, int[] parent, int[] order, int reached) {
        int distance(int x, int y, int z) {
            return grid.contains(x, y, z) ? distance[grid.index(x, y, z)] : -1;
        }
    }

    /**
     * Plans the node and the way for a player at {@code x, y, z} (feet).
     *
     * @throws IllegalArgumentException if the player's cell is outside the grid
     */
    public static Plan plan(VoxelGrid grid, VoxelGrid.Region region, int x, int y, int z, int minLength,
                            int maxLength, long seed) {
        Random random = new Random(seed);
        Walk walk = distances(grid, x, y, z, maxLength);
        int candidates = 0;
        int chosen = -1;
        for (int i = 0; i < walk.reached(); i++) {
            int cell = walk.order()[i];
            int d = walk.distance()[cell];
            if (d < minLength || d > maxLength || !allowed(grid, region, cell)) {
                continue;
            }
            // Reservoir sampling: every candidate equally likely.
            candidates++;
            if (random.nextInt(candidates) == 0) {
                chosen = cell;
            }
        }
        Set<Long> way = new HashSet<>();
        if (chosen >= 0) {
            addPath(walk, walk.parent()[chosen], way);
            return new Plan(key(grid, chosen), new HashSet<>(), new HashSet<>(), way,
                    walk.distance()[chosen], false);
        }
        int from = walk.order()[0];
        for (int i = walk.reached() - 1; i > 0; i--) {
            if (allowed(grid, region, walk.order()[i])) {
                from = walk.order()[i];
                break;
            }
        }
        addPath(walk, from, way);
        int target = minLength + random.nextInt(maxLength - minLength + 1);
        int steps = Math.max(MIN_TUNNEL, target - walk.distance()[from]);
        return tunnel(grid, region, walk, from, steps, way, random, seed);
    }

    /** The walk from {@code x, y, z} up to {@code maxLength} steps. */
    static Walk distances(VoxelGrid grid, int x, int y, int z, int maxLength) {
        if (!grid.contains(x, y, z)) {
            throw new IllegalArgumentException("The start " + x + " " + y + " " + z + " is outside the grid");
        }
        int[] distance = new int[grid.volume()];
        int[] parent = new int[grid.volume()];
        Arrays.fill(distance, -1);
        int[] order = new int[grid.volume()];
        int start = grid.index(x, y, z);
        distance[start] = 0;
        parent[start] = -1;
        order[0] = start;
        int head = 0;
        int tail = 1;
        int sizeX = grid.maxX() - grid.minX() + 1;
        int sizeZ = grid.maxZ() - grid.minZ() + 1;
        while (head < tail) {
            int cell = order[head++];
            if (distance[cell] >= maxLength) {
                continue;
            }
            int cx = grid.minX() + cell % sizeX;
            int cz = grid.minZ() + cell / sizeX % sizeZ;
            int cy = grid.minY() + cell / sizeX / sizeZ;
            for (int dir = 0; dir < 4; dir++) {
                int nx = cx + DX[dir];
                int nz = cz + DZ[dir];
                int ny = landing(grid, cx, cy, cz, nx, nz);
                if (ny == Integer.MIN_VALUE) {
                    continue;
                }
                int next = grid.index(nx, ny, nz);
                if (distance[next] < 0) {
                    distance[next] = distance[cell] + 1;
                    parent[next] = cell;
                    order[tail++] = next;
                }
            }
        }
        return new Walk(grid, distance, parent, order, tail);
    }

    /**
     * Where a player standing at {@code x, y, z} ends up after a block aside to {@code nx, nz}: the same height, a block
     * up (with the room to jump above the head), or down a drop; {@link Integer#MIN_VALUE} if there is no way.
     */
    static int landing(VoxelGrid grid, int x, int y, int z, int nx, int nz) {
        if (grid.standable(nx, y, nz)) {
            return y;
        }
        if (grid.standable(nx, y + 1, nz) && grid.open(x, y + 2, z)) {
            return y + 1;
        }
        if (!grid.open(nx, y, nz) || !grid.open(nx, y + 1, nz)) {
            return Integer.MIN_VALUE;
        }
        for (int ly = y - 1; ly >= y - MAX_DROP && grid.open(nx, ly, nz); ly--) {
            if (grid.standable(nx, ly, nz)) {
                return ly;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Carves the winding tunnel from the walk's cell {@code from} and the chamber at its end. */
    private static Plan tunnel(VoxelGrid grid, VoxelGrid.Region region, Walk walk, int from, int steps,
                               Set<Long> way, Random random, long seed) {
        int sizeX = grid.maxX() - grid.minX() + 1;
        int sizeZ = grid.maxZ() - grid.minZ() + 1;
        int startCell = walk.order()[0];
        int cx = grid.minX() + from % sizeX;
        int cz = grid.minZ() + from / sizeX % sizeZ;
        int cy = grid.minY() + from / sizeX / sizeZ;
        int baseY = cy;
        int sx = grid.minX() + startCell % sizeX;
        int sz = grid.minZ() + startCell / sizeX % sizeZ;
        double heading = cx != sx || cz != sz ? Math.atan2(cz - sz, cx - sx) : random.nextDouble() * 2 * Math.PI;
        double px = cx + 0.5;
        double pz = cz + 0.5;
        int sy = grid.minY() + startCell / sizeX / sizeZ;
        // Never under the player's feet: the player would fall into it on arrival.
        VoxelGrid.Region carvable = (x, y, z) -> region.allows(x, y, z) && (x != sx || z != sz || y >= sy);
        Set<Long> tunnel = new HashSet<>();
        int done = 0;
        int sinceLevel = LEVEL_STEPS;
        int turns = 0;
        int lastX = 1;
        int lastZ = 0;
        // A unit step of the heading enters a new cell at least every other iteration; the cap only guards that.
        for (int i = 0; done < steps && turns < MAX_TURNS && i < 4 * steps + 64; i++) {
            double angle = heading + TURN * LevelNoise.at(seed, 1, i * TURN_RATE, 0.5, 0.5);
            px += Math.cos(angle);
            pz += Math.sin(angle);
            int wantY = baseY + (int) Math.round(RISE * LevelNoise.at(seed, 2, i * RISE_RATE, 0.5, 0.5));
            boolean blocked = false;
            while (done < steps && !blocked && (cx != (int) Math.floor(px) || cz != (int) Math.floor(pz))) {
                int ddx = (int) Math.floor(px) - cx;
                int ddz = (int) Math.floor(pz) - cz;
                int mx = Math.abs(ddx) >= Math.abs(ddz) ? Integer.signum(ddx) : 0;
                int mz = mx == 0 ? Integer.signum(ddz) : 0;
                int ny = sinceLevel >= LEVEL_STEPS && wantY != cy ? cy + Integer.signum(wantY - cy) : cy;
                if (!fits(grid, carvable, cx + mx, ny, cz + mz, mx, mz)) {
                    ny = cy;
                }
                if (!fits(grid, carvable, cx + mx, ny, cz + mz, mx, mz)) {
                    blocked = true;
                    continue;
                }
                if (ny > cy) {
                    // Room to jump up from the lower cell (in the tunnel it is there already, but not where the
                    // tunnel starts: in a 1x2 tunnel, say).
                    tunnel.add(CellKey.of(cx, cy + 2, cz));
                }
                sinceLevel = ny != cy ? 0 : sinceLevel + 1;
                cx += mx;
                cz += mz;
                cy = ny;
                lastX = mx;
                lastZ = mz;
                section(tunnel, cx, cy, cz, mx, mz);
                done++;
                turns = 0;
            }
            if (blocked) {
                heading += random.nextBoolean() ? Math.PI / 2 : -Math.PI / 2;
                px = cx + 0.5;
                pz = cz + 0.5;
                turns++;
            }
        }
        Set<Long> chamber = chamber(grid, carvable, cx, cy, cz);
        long end = CellKey.of(cx, cy, cz);
        long node = end;
        for (int ahead = 2; ahead >= 1; ahead--) {
            long cell = CellKey.of(cx + ahead * lastX, cy, cz + ahead * lastZ);
            if (chamber.contains(cell)) {
                node = cell;
                break;
            }
        }
        int length = walk.distance()[from] + done + (node == end ? 0 : 1);
        way.addAll(tunnel);
        way.addAll(chamber);
        way.remove(node);
        Set<Long> carve = new HashSet<>();
        for (long cell : tunnel) {
            needsCarving(grid, cell, carve);
        }
        for (long cell : chamber) {
            needsCarving(grid, cell, carve);
        }
        carve.remove(node);
        Set<Long> floor = new HashSet<>();
        for (long cell : way) {
            addFloor(grid, region, way, cell, floor);
        }
        addFloor(grid, region, way, node, floor);
        return new Plan(node, carve, floor, way, length, true);
    }

    /** Whether the tunnel's section at {@code x, y, z}, going {@code mx, mz}, lies in the grid and the region. */
    private static boolean fits(VoxelGrid grid, VoxelGrid.Region region, int x, int y, int z, int mx, int mz) {
        for (int h = -1; h <= 2; h++) {
            // The floor below too: it may have to be filled.
            if (!inside(grid, region, x, y + h, z) || !inside(grid, region, x - mz, y + h, z + mx)) {
                return false;
            }
        }
        return true;
    }

    /** The tunnel's section at {@code x, y, z} going {@code mx, mz}: two wide (the second to the left), three high. */
    private static void section(Set<Long> tunnel, int x, int y, int z, int mx, int mz) {
        for (int h = 0; h < 3; h++) {
            tunnel.add(CellKey.of(x, y + h, z));
            tunnel.add(CellKey.of(x - mz, y + h, z + mx));
        }
    }

    /** The chamber at the end of the tunnel: a dome on the tunnel's floor, cut to the region. */
    private static Set<Long> chamber(VoxelGrid grid, VoxelGrid.Region region, int x, int y, int z) {
        Set<Long> chamber = new HashSet<>();
        int reach = (int) Math.ceil(CHAMBER_RADIUS);
        for (int dy = 0; dy < CHAMBER_HEIGHT; dy++) {
            double up = (dy + 0.5) / CHAMBER_HEIGHT;
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dx = -reach; dx <= reach; dx++) {
                    if ((dx * dx + dz * dz) / (CHAMBER_RADIUS * CHAMBER_RADIUS) + up * up <= 1
                            && inside(grid, region, x + dx, y + dy, z + dz)
                            && inside(grid, region, x + dx, y + dy - 1, z + dz)) {
                        chamber.add(CellKey.of(x + dx, y + dy, z + dz));
                    }
                }
            }
        }
        return chamber;
    }

    private static void needsCarving(VoxelGrid grid, long cell, Set<Long> carve) {
        if (grid.flags(CellKey.x(cell), CellKey.y(cell), CellKey.z(cell)) != VoxelGrid.OPEN) {
            carve.add(cell);
        }
    }

    /** The cell under {@code cell} is filled if it is not on the way and is open or hurts. */
    private static void addFloor(VoxelGrid grid, VoxelGrid.Region region, Set<Long> way, long cell,
                                 Set<Long> floor) {
        int x = CellKey.x(cell);
        int y = CellKey.y(cell) - 1;
        int z = CellKey.z(cell);
        long below = CellKey.of(x, y, z);
        if (!way.contains(below) && inside(grid, region, x, y, z) && grid.flags(x, y, z) != 0) {
            floor.add(below);
        }
    }

    /**
     * Adds the cells the walk passes through from the start to {@code cell}: feet and head of each cell, the room for
     * each jump up and the column of each drop.
     */
    private static void addPath(Walk walk, int cell, Set<Long> way) {
        VoxelGrid grid = walk.grid();
        int sizeX = grid.maxX() - grid.minX() + 1;
        int sizeZ = grid.maxZ() - grid.minZ() + 1;
        for (int at = cell; at >= 0; at = walk.parent()[at]) {
            int x = grid.minX() + at % sizeX;
            int z = grid.minZ() + at / sizeX % sizeZ;
            int y = grid.minY() + at / sizeX / sizeZ;
            way.add(CellKey.of(x, y, z));
            way.add(CellKey.of(x, y + 1, z));
            int from = walk.parent()[at];
            if (from < 0) {
                continue;
            }
            int fy = grid.minY() + from / sizeX / sizeZ;
            if (fy < y) {
                way.add(CellKey.of(grid.minX() + from % sizeX, fy + 2, grid.minZ() + from / sizeX % sizeZ));
            }
            for (int h = y + 2; h <= fy + 1; h++) {
                way.add(CellKey.of(x, h, z));
            }
        }
    }

    private static boolean allowed(VoxelGrid grid, VoxelGrid.Region region, int cell) {
        long key = key(grid, cell);
        int x = CellKey.x(key);
        int y = CellKey.y(key);
        int z = CellKey.z(key);
        return region.allows(x, y, z) && region.allows(x, y + 1, z);
    }

    private static boolean inside(VoxelGrid grid, VoxelGrid.Region region, int x, int y, int z) {
        return grid.contains(x, y, z) && region.allows(x, y, z);
    }

    private static long key(VoxelGrid grid, int cell) {
        int sizeX = grid.maxX() - grid.minX() + 1;
        int sizeZ = grid.maxZ() - grid.minZ() + 1;
        return CellKey.of(grid.minX() + cell % sizeX, grid.minY() + cell / sizeX / sizeZ,
                grid.minZ() + cell / sizeX % sizeZ);
    }
}
