package tremor.hollow.level;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The one way out of the hollow besides the node: a narrow tunnel (one block wide, two high) from a few blocks off the
 * start to the edge, in a random direction and gently winding. Nothing marks it, no ring comes from it: the player has
 * to come upon it, and without running there is no time to go along every wall. The closing never fills it (its cells
 * are kept open like the way to the node), so as the walls close in it is a dark hole in one of them; walking it to
 * the edge is the escape over the edge ({@link EventLevel}). Under every cell of it there is a floor, and at its end
 * a light ({@link #glow}): the way out shows only to whoever is already in it. Plain Java.
 *
 * @param carve cells to empty (feet and head, {@link CellKey}), in order from the start outward
 * @param floor cells to fill under it, where the copy had nothing to stand on
 */
public record EscapeTunnel(Set<Long> carve, Set<Long> floor) {
    /** None: a nightmare has no way out over the edge. */
    public static final EscapeTunnel NONE = new EscapeTunnel(Set.of(), Set.of());
    /** Where it starts, in blocks from the centre: off the player's first sight, inside the last of the closing. */
    static final double ENTRANCE = 7;
    /** How far it winds sideways (blocks) and over what length (blocks per radian). */
    static final double WIND = 1.5;
    static final double WIND_LENGTH = 4;
    /** Light level at the end of the tunnel, at most (a torch gives 14). */
    static final int GLOW = 13;
    /** Blocks of the tunnel from its entrance that its light never reaches: from the hollow it is a dark hole. */
    static final int DARK_ENTRANCE = 5;

    /**
     * Plans the tunnel at the height of {@code startY} (the player's feet), from {@value #ENTRANCE} blocks off the
     * centre to the edge ({@code edge} blocks off it); cells the way to the node fills ({@code keep}) are left as they
     * are, so the tunnel never cuts the way's floor (it then has a step up there).
     */
    public static EscapeTunnel plan(VoxelGrid grid, double centreX, double centreZ, int startY, double edge, long seed,
                                    Set<Long> keep) {
        Random random = new Random(seed ^ 0x5EC2E7L);
        double angle = random.nextDouble() * 2 * Math.PI;
        double dx = Math.cos(angle);
        double dz = Math.sin(angle);
        double phase = random.nextDouble() * 2 * Math.PI;
        Set<Long> carve = new LinkedHashSet<>();
        Set<Long> floor = new LinkedHashSet<>();
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (double t = ENTRANCE; t <= edge + 1; t += 0.5) {
            double side = WIND * Math.sin(t / WIND_LENGTH + phase);
            int x = (int) Math.floor(centreX + dx * t - dz * side);
            int z = (int) Math.floor(centreZ + dz * t + dx * side);
            if (lastX != Integer.MIN_VALUE && x != lastX && z != lastZ) {
                add(grid, lastX, startY, z, keep, carve, floor); // a diagonal step: walkable, not squeezed between corners
            }
            add(grid, x, startY, z, keep, carve, floor);
            lastX = x;
            lastZ = z;
        }
        return new EscapeTunnel(carve, floor);
    }

    /**
     * The light at the end of the tunnel: the cell at feet height ({@code startY}) and the one above it, in the last
     * column of the tunnel short of {@code reach} blocks off the centre (where the player is out), so the player runs
     * into the light. Its level drops a level a block back along the tunnel, and it is low enough to die out
     * {@value #DARK_ENTRANCE} blocks short of the entrance at least. Cell ({@link CellKey}) to light level; empty if the
     * tunnel is too short for any, or there is none.
     */
    public Map<Long, Integer> glow(double centreX, double centreZ, int startY, double reach) {
        List<Long> feet = carve.stream().filter(cell -> CellKey.y(cell) == startY).toList();
        int last = -1;
        for (int i = 0; i < feet.size(); i++) {
            long cell = feet.get(i);
            if (Math.hypot(CellKey.x(cell) + 0.5 - centreX, CellKey.z(cell) + 0.5 - centreZ) < reach) {
                last = i;
            }
        }
        int level = Math.min(GLOW, last - DARK_ENTRANCE); // lights the level - 1 cells back along the tunnel
        if (level < 1) {
            return Map.of();
        }
        long end = feet.get(last);
        Map<Long, Integer> glow = new LinkedHashMap<>();
        glow.put(end, level);
        long above = CellKey.of(CellKey.x(end), startY + 1, CellKey.z(end));
        if (carve.contains(above)) {
            glow.put(above, level);
        }
        return glow;
    }

    private static void add(VoxelGrid grid, int x, int y, int z, Set<Long> keep, Set<Long> carve, Set<Long> floor) {
        if (!grid.contains(x, y, z)) {
            return;
        }
        for (int dy = 0; dy <= 1; dy++) {
            long cell = CellKey.of(x, y + dy, z);
            if (!keep.contains(cell)) {
                carve.add(cell);
            }
        }
        long under = CellKey.of(x, y - 1, z);
        if (grid.contains(x, y - 1, z) && grid.open(x, y - 1, z) && !keep.contains(under)) {
            floor.add(under);
        }
    }
}
