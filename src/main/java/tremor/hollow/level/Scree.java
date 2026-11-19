package tremor.hollow.level;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * No pit in the copy to be stuck in (the owner, 2026-11-17: "a scree slope one can climb out by", smoother ground): a
 * place a player gets to from the start, walking or by a fall of any height, but cannot walk out of to an exit (the
 * start, the node, the escape tunnel), such as a ravine, a pit or the foot of a cliff, gets a ragged heap of rubble
 * ({@link #heap}): from a block under the lowest rim it can be climbed to, it falls away into the place a block for
 * every one to two blocks across, its top uneven, so a player climbs out by it without blocks of their own. Where no
 * heap fits (no room over the rim: a hole down into a cave), the drops into the place are floored over instead
 * ({@link #cap}). Each round is checked by walking the grid again, and what is left after the last is counted.
 * Water is taken to be swum out of. The walk is the planning's ({@link WayPlanner#landing}), with falls of any height.
 * Plain Java, on a {@link VoxelGrid}: the cells filled are filled in it.
 */
final class Scree {
    /** Rounds that may lay heaps; the rounds after them only floor drops over. */
    private static final int HEAP_ROUNDS = 3;
    /** Rounds in all. */
    private static final int ROUNDS = 8;
    /** A rim at most this much over a floor is climbed to by a heap (blocks). */
    private static final int MAX_RISE = 32;
    /** A heap falls away by this many blocks for every block across, at the least and at the most. */
    private static final double SLOPE_MIN = 0.6;
    private static final double SLOPE_MAX = 0.85;
    private static final int[] DX = {1, 0, -1, 0};
    private static final int[] DZ = {0, 1, 0, -1};

    /**
     * What was laid: the cells filled (each column's from the bottom up), the heaps, the drops floored over, and the
     * places to be stuck in that are left (cells stood in).
     */
    record Result(Set<Long> fill, int heaps, int caps, int left) {
    }

    private Scree() {
    }

    /**
     * Lays the scree in {@code grid} (the copy as the level leaves it) for a player at {@code start}: every place the
     * player gets to and cannot walk on from to {@code exits} or back to the start gets a way out. Cells are filled
     * only where {@code region} allows, never one of {@code keep}.
     */
    static Result lay(VoxelGrid grid, VoxelGrid.Region region, long start, Collection<Long> exits, Set<Long> keep,
                      long seed) {
        Random random = new Random(seed);
        int volume = grid.volume();
        int[] back = new int[volume];
        int[] backOrder = new int[volume];
        int[] seen = new int[volume];
        int[] order = new int[volume];
        int[] trapped = new int[volume];
        int[] part = new int[volume];
        Arrays.fill(back, -1);
        int from = grid.index(CellKey.x(start), CellKey.y(start), CellKey.z(start));
        List<Integer> targets = new ArrayList<>(List.of(from));
        for (long cell : exits) {
            if (grid.contains(CellKey.x(cell), CellKey.y(cell), CellKey.z(cell))) {
                targets.add(grid.index(CellKey.x(cell), CellKey.y(cell), CellKey.z(cell)));
            }
        }
        int[] to = targets.stream().mapToInt(Integer::intValue).toArray();
        Set<Long> fill = new LinkedHashSet<>();
        int heaps = 0;
        int caps = 0;
        int places = 0;
        for (int round = 1; ; round++) {
            int found = WayPlanner.walkBack(grid, to, Integer.MAX_VALUE, back, backOrder);
            int reached = fallWalk(grid, from, round, seen, order);
            List<Integer> stuck = new ArrayList<>();
            for (int i = 0; i < reached; i++) {
                int cell = order[i];
                if (back[cell] < 0 && grid.dry(x(grid, cell), y(grid, cell), z(grid, cell))) {
                    trapped[cell] = round;
                    stuck.add(cell);
                }
            }
            int before = fill.size();
            if (!stuck.isEmpty() && round <= ROUNDS) {
                int first = places + 1;
                Set<Integer> toCap = new HashSet<>();
                for (int cell : stuck) {
                    if (part[cell] >= first) {
                        continue;
                    }
                    List<Integer> place = flood(grid, cell, ++places, round, trapped, part);
                    if (round <= HEAP_ROUNDS && heap(grid, region, keep, place, back, random, fill)) {
                        heaps++;
                    } else {
                        toCap.add(places);
                    }
                }
                if (!toCap.isEmpty()) {
                    caps += cap(grid, region, keep, order, reached, round, trapped, part, toCap, fill);
                }
            }
            if (stuck.isEmpty() || fill.size() == before) {
                return new Result(fill, heaps, caps, stuck.size());
            }
            for (int i = 0; i < found; i++) {
                back[backOrder[i]] = -1;
            }
        }
    }

    /**
     * Where a player standing at {@code x, y, z} ends up after a block aside to {@code nx, nz}: as the planning walks,
     * or down a fall of any height (unhurt by what it lands in); {@link Integer#MIN_VALUE} if there is no way.
     */
    static int fall(VoxelGrid grid, int x, int y, int z, int nx, int nz) {
        int to = WayPlanner.landing(grid, x, y, z, nx, nz);
        if (to != Integer.MIN_VALUE || !grid.passable(nx, y, nz) || !grid.passable(nx, y + 1, nz)) {
            return to;
        }
        for (int ly = y - 1; ly > grid.minY() && grid.passable(nx, ly, nz); ly--) {
            if (grid.standable(nx, ly, nz)) {
                return ly;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** The cells walked or fallen to from {@code from}, into {@code order}, each marked with {@code round}. */
    private static int fallWalk(VoxelGrid grid, int from, int round, int[] seen, int[] order) {
        seen[from] = round;
        order[0] = from;
        int head = 0;
        int tail = 1;
        while (head < tail) {
            int cell = order[head++];
            int x = x(grid, cell);
            int y = y(grid, cell);
            int z = z(grid, cell);
            for (int dir = 0; dir < 4; dir++) {
                int ny = fall(grid, x, y, z, x + DX[dir], z + DZ[dir]);
                if (ny == Integer.MIN_VALUE) {
                    continue;
                }
                int next = grid.index(x + DX[dir], ny, z + DZ[dir]);
                if (seen[next] != round) {
                    seen[next] = round;
                    order[tail++] = next;
                }
            }
        }
        return tail;
    }

    /** The place of the stuck cell {@code first}: the stuck cells a block aside of each other, two up or down at most. */
    private static List<Integer> flood(VoxelGrid grid, int first, int id, int round, int[] trapped, int[] part) {
        List<Integer> cells = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>(List.of(first));
        part[first] = id;
        while (!queue.isEmpty()) {
            int cell = queue.poll();
            cells.add(cell);
            for (int dir = 0; dir < 4; dir++) {
                for (int dy = -2; dy <= 2; dy++) {
                    int nx = x(grid, cell) + DX[dir];
                    int ny = y(grid, cell) + dy;
                    int nz = z(grid, cell) + DZ[dir];
                    if (grid.contains(nx, ny, nz) && trapped[grid.index(nx, ny, nz)] == round
                            && part[grid.index(nx, ny, nz)] != id) {
                        part[grid.index(nx, ny, nz)] = id;
                        queue.add(grid.index(nx, ny, nz));
                    }
                }
            }
        }
        return cells;
    }

    /**
     * A heap out of the place {@code cells}: under the lowest rim a cell walks out from (one at random of those as
     * low), with room over the place's column beside it to stand a block under the rim and jump up, and the heap's top
     * there in the region, the column is
     * filled up to that block, and every column of the place a step further off a block lower for every
     * {@code 1 / slope} steps, a random part of a block more, down to its floor. The slope and the raggedness stay
     * under a block a step, so the heap is climbed along any line toward the rim. False if no rim has room (nothing
     * filled).
     */
    private static boolean heap(VoxelGrid grid, VoxelGrid.Region region, Set<Long> keep, List<Integer> cells,
                                int[] back, Random random, Set<Long> fill) {
        Map<Long, Integer> floors = new HashMap<>();
        for (int cell : cells) {
            floors.merge(CellKey.of(x(grid, cell), 0, z(grid, cell)), y(grid, cell), Math::min);
        }
        long rim = 0;
        int rimY = 0;
        int rise = Integer.MAX_VALUE;
        int ties = 0;
        for (Map.Entry<Long, Integer> column : floors.entrySet()) {
            int x = CellKey.x(column.getKey());
            int z = CellKey.z(column.getKey());
            int floor = column.getValue();
            for (int dir = 0; dir < 4; dir++) {
                int nx = x + DX[dir];
                int nz = z + DZ[dir];
                for (int h = floor + 2; h - floor <= Math.min(MAX_RISE, rise); h++) {
                    if (!grid.standable(nx, h, nz) || back[grid.index(nx, h, nz)] < 0) {
                        continue;
                    }
                    if (grid.passable(x, h - 1, z) && grid.passable(x, h, z) && grid.passable(x, h + 1, z)
                            && region.allows(x, h - 2, z)) {
                        if (h - floor < rise) {
                            rise = h - floor;
                            ties = 0;
                        }
                        if (random.nextInt(++ties) == 0) {
                            rim = column.getKey();
                            rimY = h;
                        }
                    }
                    break;
                }
            }
        }
        if (rise == Integer.MAX_VALUE) {
            return false;
        }
        double slope = SLOPE_MIN + (SLOPE_MAX - SLOPE_MIN) * random.nextDouble();
        Map<Long, Integer> steps = new HashMap<>(Map.of(rim, 1));
        ArrayDeque<Long> queue = new ArrayDeque<>(List.of(rim));
        int filled = 0;
        while (!queue.isEmpty()) {
            long column = queue.poll();
            int x = CellKey.x(column);
            int z = CellKey.z(column);
            int step = steps.get(column);
            // The feet of a player on the heap here: ceil keeps the step to the next column toward the rim at a block.
            int top = rimY - (int) Math.ceil(step * slope + (1 - slope) * random.nextDouble());
            for (int y = floors.get(column); y < top; y++) {
                long cell = CellKey.of(x, y, z);
                if (grid.open(x, y, z) && region.allows(x, y, z) && !keep.contains(cell)) {
                    grid.set(x, y, z, 0);
                    fill.add(cell);
                    filled++;
                }
            }
            for (int dir = 0; dir < 4; dir++) {
                long next = CellKey.of(x + DX[dir], 0, z + DZ[dir]);
                if (floors.containsKey(next) && !steps.containsKey(next)) {
                    steps.put(next, step + 1);
                    queue.add(next);
                }
            }
        }
        return filled > 0;
    }

    /**
     * Floors over every drop into the places {@code places} from a cell that walks out: the cell a block aside of it
     * under its feet, so the player walks on instead of dropping in. Returns the cells filled.
     */
    private static int cap(VoxelGrid grid, VoxelGrid.Region region, Set<Long> keep, int[] order, int reached,
                           int round, int[] trapped, int[] part, Set<Integer> places, Set<Long> fill) {
        int capped = 0;
        for (int i = 0; i < reached; i++) {
            int cell = order[i];
            if (trapped[cell] == round) {
                continue;
            }
            int x = x(grid, cell);
            int y = y(grid, cell);
            int z = z(grid, cell);
            for (int dir = 0; dir < 4; dir++) {
                int nx = x + DX[dir];
                int nz = z + DZ[dir];
                int ny = fall(grid, x, y, z, nx, nz);
                if (ny == Integer.MIN_VALUE || ny >= y) {
                    continue;
                }
                int into = grid.index(nx, ny, nz);
                long lid = CellKey.of(nx, y - 1, nz);
                if (trapped[into] == round && places.contains(part[into]) && grid.open(nx, y - 1, nz)
                        && region.allows(nx, y - 1, nz) && !keep.contains(lid)) {
                    grid.set(nx, y - 1, nz, 0);
                    fill.add(lid);
                    capped++;
                }
            }
        }
        return capped;
    }

    private static int x(VoxelGrid grid, int index) {
        return grid.minX() + index % (grid.maxX() - grid.minX() + 1);
    }

    private static int y(VoxelGrid grid, int index) {
        int sizeX = grid.maxX() - grid.minX() + 1;
        return grid.minY() + index / sizeX / (grid.maxZ() - grid.minZ() + 1);
    }

    private static int z(VoxelGrid grid, int index) {
        int sizeX = grid.maxX() - grid.minX() + 1;
        return grid.minZ() + index / sizeX % (grid.maxZ() - grid.minZ() + 1);
    }
}
