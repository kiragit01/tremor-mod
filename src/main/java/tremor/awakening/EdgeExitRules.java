package tremor.awakening;

import tremor.hollow.HollowBox;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Where a player who got out through the edge of the hollow (SPEC 9 "Побег") comes out in the real world: plain Java,
 * unit-tested directly. The world is seen through {@link Cells}; what a block is to the search ({@link Cell}) is
 * decided by {@code EdgeExits}.
 * <p>
 * The place the player reached in the copy maps 1:1 onto the real world, but the copy is not the real world any more:
 * the player may have dug through its rock, stood on the closing or on blocks of their own, or fallen out of it. So
 * the exit is always a safe standing spot of the real world ({@link #standing}: a floor below, two open blocks, no
 * fluid, no fire) near the place reached ({@link #spots}): in its column the spot the player would land on falling from
 * there through open air ({@value #DOWN} blocks at most), else the first one above it ({@value #UP} blocks at most:
 * out of solid rock, out of water); the same in the columns around, up to {@value #AROUND} blocks aside. Of these the
 * nearest few are tried ({@value #CANDIDATES}), the first one the player could have walked, climbed or swum to from the
 * swallow point in the real world ({@link #connected}: through open blocks or water, within the copied box): the copy
 * is no way into a place its walls close off in the real world (a sealed room, a vault). Without one ({@link #choose}
 * gives null) the player comes out at the swallow point.
 */
public final class EdgeExitRules {
    /** Columns this far aside (blocks, on either axis) are searched too. */
    public static final int AROUND = 3;
    /** A spot is looked for this many blocks above the place reached, out of whatever fills it in the real world. */
    public static final int UP = 8;
    /** A player in open air lands at most this many blocks lower. */
    public static final int DOWN = 64;
    /** This many of the nearest spots are tried for a way to the swallow point. */
    public static final int CANDIDATES = 3;
    /** The way to the swallow point is given up after this many blocks looked at (a closed off place gives up early). */
    public static final int MAX_VISITS = 16384;

    private EdgeExitRules() {
    }

    /** What a block of the real world is to the search. */
    public enum Cell {
        /** Nothing to bump into, no fluid, nothing that hurts (air, a plant, a torch): a player can be in it. */
        OPEN,
        /** Water (or another fluid but lava) without a block to bump into: a way, but no place to be put. */
        WATER,
        /** Something to stand on that does not hurt (no higher than a block): also no way through. */
        FLOOR,
        /**
         * Neither a floor nor a way: a block too high to stand on (a fence), a block in a fluid, outside the world or
         * not loaded.
         */
        SOLID,
        /** Hurts, burns or holds a player: lava, fire, magma, a cactus, a cobweb, powder snow, a berry bush... */
        DANGER
    }

    /** The world as the search sees it. */
    @FunctionalInterface
    public interface Cells {
        Cell at(int x, int y, int z);
    }

    /** A block position: of a spot, the block the player's feet are in. */
    public record Spot(int x, int y, int z) {
    }

    /**
     * The exit for a player who reached the edge at the block {@code x, y, z} (mapped into the real world, its height
     * within the copied box): the first of the {@link #spots} that has a way to one of {@code targets} (the blocks the
     * player stood in at the swallow point), or null if none has: the player comes out at the swallow point then.
     *
     * @param within where the search and the way may go: the copied box and a margin, inside the world
     */
    public static Spot choose(Cells cells, int x, int y, int z, HollowBox within, List<Spot> targets) {
        for (Spot spot : spots(cells, x, y, z, within)) {
            if (connected(cells, spot, targets, within, MAX_VISITS)) {
                return spot;
            }
        }
        return null;
    }

    /**
     * The safe standing spots near the block {@code x, y, z}, the nearest first, at most {@value #CANDIDATES}: one per
     * column ({@link #inColumn}) up to {@value #AROUND} blocks aside. A block aside counts as four up or down, a block
     * of a fall as a quarter of one (landing below is what the player would do anyway).
     */
    public static List<Spot> spots(Cells cells, int x, int y, int z, HollowBox within) {
        record Found(Spot spot, int cost, int order) {
        }
        List<Found> found = new ArrayList<>();
        for (int dz = -AROUND; dz <= AROUND; dz++) {
            for (int dx = -AROUND; dx <= AROUND; dx++) {
                Spot spot = inColumn(cells, x + dx, y, z + dz, within);
                if (spot != null) {
                    int dy = spot.y() - y;
                    int cost = 16 * (dx * dx + dz * dz) + (dy < 0 && fall(cells, spot, y) ? -dy : 4 * Math.abs(dy));
                    found.add(new Found(spot, cost, found.size()));
                }
            }
        }
        found.sort(Comparator.comparingInt(Found::cost).thenComparingInt(Found::order));
        List<Spot> spots = new ArrayList<>(CANDIDATES);
        for (int i = 0; i < found.size() && i < CANDIDATES; i++) {
            spots.add(found.get(i).spot());
        }
        return spots;
    }

    /**
     * The safe standing spot of the column {@code x, z} for a player at height {@code y}: if the block is open, where a
     * fall from it through open blocks ends ({@value #DOWN} blocks at most), if that is a spot; otherwise (the block is
     * filled in the real world, or the fall ends in water, on a fence, in a crawl space...) the first spot above it,
     * {@value #UP} blocks at most. Null if neither, or the column is outside {@code within}.
     */
    public static Spot inColumn(Cells cells, int x, int y, int z, HollowBox within) {
        if (x < within.minX() || x > within.maxX() || z < within.minZ() || z > within.maxZ()) {
            return null;
        }
        if (inside(within, y) && cells.at(x, y, z) == Cell.OPEN) {
            int feet = y;
            while (feet - 1 >= within.minY() && feet > y - DOWN && cells.at(x, feet - 1, z) == Cell.OPEN) {
                feet--;
            }
            if (standing(cells, x, feet, z, within)) {
                return new Spot(x, feet, z);
            }
        }
        for (int feet = y + 1; feet <= y + UP; feet++) {
            if (standing(cells, x, feet, z, within)) {
                return new Spot(x, feet, z);
            }
        }
        return null;
    }

    /** Whether a player stands safely with the feet in the block {@code x, y, z}: a floor below, two open blocks. */
    public static boolean standing(Cells cells, int x, int y, int z, HollowBox within) {
        return y - 1 >= within.minY() && y + 1 <= within.maxY() && cells.at(x, y - 1, z) == Cell.FLOOR
                && cells.at(x, y, z) == Cell.OPEN && cells.at(x, y + 1, z) == Cell.OPEN;
    }

    /**
     * Whether a player in the block {@code from} could get to one of {@code targets} through open blocks and water
     * (face to face, so a gap of one block will do: crawling), never leaving {@code within}; a target that is not open
     * or water is no way in. Searched towards the targets first; false once {@code maxVisits} blocks were looked at.
     */
    public static boolean connected(Cells cells, Spot from, List<Spot> targets, HollowBox within, int maxVisits) {
        List<Spot> goals = new ArrayList<>();
        for (Spot target : targets) {
            if (within.contains(target.x(), target.y(), target.z())
                    && passable(cells.at(target.x(), target.y(), target.z()))) {
                goals.add(target);
            }
        }
        if (goals.isEmpty() || !within.contains(from.x(), from.y(), from.z())
                || !passable(cells.at(from.x(), from.y(), from.z()))) {
            return false;
        }
        int sizeX = within.sizeX();
        int sizeZ = within.sizeZ();
        BitSet seen = new BitSet((int) within.volume());
        // Weighted A*: f = g + 2h, the index of the block in the low 32 bits.
        PriorityQueue<Long> open = new PriorityQueue<>();
        int start = index(within, from.x(), from.y(), from.z());
        seen.set(start);
        open.add(key(2 * distance(goals, from.x(), from.y(), from.z()), start));
        int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int visits = 0;
        while (!open.isEmpty() && visits < maxVisits) {
            long head = open.poll();
            int at = (int) head;
            int x = within.minX() + at % sizeX;
            int z = within.minZ() + at / sizeX % sizeZ;
            int y = within.minY() + at / sizeX / sizeZ;
            int h = distance(goals, x, y, z);
            if (h == 0) {
                return true;
            }
            visits++;
            int g = (int) (head >>> 32) - 2 * h;
            for (int[] face : faces) {
                int nx = x + face[0], ny = y + face[1], nz = z + face[2];
                if (!within.contains(nx, ny, nz)) {
                    continue;
                }
                int next = index(within, nx, ny, nz);
                if (seen.get(next)) {
                    continue;
                }
                seen.set(next);
                if (passable(cells.at(nx, ny, nz))) {
                    open.add(key(g + 1 + 2 * distance(goals, nx, ny, nz), next));
                }
            }
        }
        return false;
    }

    /** Whether the spot lies below {@code y} with nothing but open blocks between: a fall from {@code y} lands there. */
    private static boolean fall(Cells cells, Spot spot, int y) {
        for (int at = spot.y() + 1; at <= y; at++) {
            if (cells.at(spot.x(), at, spot.z()) != Cell.OPEN) {
                return false;
            }
        }
        return true;
    }

    private static boolean inside(HollowBox within, int y) {
        return y >= within.minY() && y <= within.maxY();
    }

    private static boolean passable(Cell cell) {
        return cell == Cell.OPEN || cell == Cell.WATER;
    }

    /** Manhattan distance to the nearest goal. */
    private static int distance(List<Spot> goals, int x, int y, int z) {
        int best = Integer.MAX_VALUE;
        for (Spot goal : goals) {
            best = Math.min(best, Math.abs(goal.x() - x) + Math.abs(goal.y() - y) + Math.abs(goal.z() - z));
        }
        return best;
    }

    private static int index(HollowBox within, int x, int y, int z) {
        return ((y - within.minY()) * within.sizeZ() + (z - within.minZ())) * within.sizeX() + (x - within.minX());
    }

    private static long key(int f, int index) {
        return (long) f << 32 | index;
    }
}
