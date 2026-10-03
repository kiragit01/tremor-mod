package tremor.hollow.level;

import java.util.ArrayDeque;
import java.util.Collection;

/**
 * For the tests: the walk of the planning ({@link WayPlanner#landing}) run backwards, worked out here on its own: the
 * cells a player walks from to one of some cells.
 */
final class WalkBack {
    private WalkBack() {
    }

    /** The cells of {@code grid} (by {@link VoxelGrid#index}) that walk to one of {@code targets}, those included. */
    static boolean[] to(VoxelGrid grid, Collection<Long> targets) {
        boolean[] back = new boolean[grid.volume()];
        ArrayDeque<Long> queue = new ArrayDeque<>();
        for (long target : targets) {
            back[grid.index(CellKey.x(target), CellKey.y(target), CellKey.z(target))] = true;
            queue.add(target);
        }
        while (!queue.isEmpty()) {
            long cell = queue.poll();
            int x = CellKey.x(cell);
            int y = CellKey.y(cell);
            int z = CellKey.z(cell);
            for (int dir = 0; dir < 4; dir++) {
                int px = x - (dir == 0 ? 1 : dir == 1 ? -1 : 0);
                int pz = z - (dir == 2 ? 1 : dir == 3 ? -1 : 0);
                // From a block below (a jump up), the same height, or a drop.
                for (int py = y - 1; py <= y + WayPlanner.MAX_DROP; py++) {
                    if (grid.contains(px, py, pz) && !back[grid.index(px, py, pz)] && grid.standable(px, py, pz)
                            && WayPlanner.landing(grid, px, py, pz, x, z) == y) {
                        back[grid.index(px, py, pz)] = true;
                        queue.add(CellKey.of(px, py, pz));
                    }
                }
            }
        }
        return back;
    }

    /** The cell of {@code grid} at the index {@code index}. */
    static long key(VoxelGrid grid, int index) {
        int sizeX = grid.maxX() - grid.minX() + 1;
        int sizeZ = grid.maxZ() - grid.minZ() + 1;
        return CellKey.of(grid.minX() + index % sizeX, grid.minY() + index / sizeX / sizeZ,
                grid.minZ() + index / sizeX % sizeZ);
    }
}
