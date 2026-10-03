package tremor.hollow.level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * One winding tunnel of the network of ways (SPEC 9: "к нему ведёт сеть ходов с развилками и тупиками, вырезанная в
 * копии"): the way to the node, or a dead end. Plain Java, on a {@link VoxelGrid}: it only says which cells the
 * tunnel takes ({@link Dig}); {@link WayPlanner} empties them and lays the floors.
 * <p>
 * The worm starts in a cell a player stands in and moves a block aside at a time, its heading swinging up to
 * {@value #TURN} radians either way by smooth noise ({@link LevelNoise}), so the tunnel winds. Its section is three
 * high and two wide (one wide where the noise narrows it, or where two do not fit), and it goes up or down a block at
 * a time as its {@link Slope} says: a step up only with the room to jump, a step down a drop of one, so the tunnel is
 * walked both ways. A section fits only inside the region over a floor that can be laid ({@link #fits}: not an open
 * cell of a way); it never digs the floor from under another way ({@code ways}) or from under its own sections (nor
 * does its chamber), and after the first {@code grace} steps it neither enters {@code taken}, nor digs within a block
 * of it, nor stands on it (a dead end does not cut into the way to the node or into another dead end); nor does it step
 * back onto a column of its own path. A worm that does not fit turns aside, and one turned {@value #MAX_TURNS} times in
 * a row ends early. Open cells it passes through (an existing cave) are not dug but are its own all the same: the
 * planning lays a floor under them and, away from the open space of the start, walls them in.
 */
final class Worm {
    /** How the tunnel goes up and down. */
    enum Slope {
        /**
         * The way to the node: down a block every {@value Worm#THROAT_STEPS} steps while the ground over the section is
         * thinner than the node needs ({@link WayPlanner#COVER} blocks over the dome of its chamber: a throat, on open
         * ground), in its last {@value Worm#FINAL_STEPS} steps also while that ground is not right over it (the node
         * is not put under a lake or a cave), then up and down by noise around the depth reached, never up into
         * thinner ground.
         */
        DEEP,
        /**
         * A decoy throat: down a block every {@value Worm#THROAT_STEPS} steps until {@value Worm#ROOF} blocks of
         * ground are over it, then as {@link #LEVEL} from there.
         */
        ROOFED,
        /** A dead end: after its grace, down and back up by noise, never above the height it started at. */
        LEVEL
    }

    /** A throat goes down a block every this many steps: a walkable slope (a drop of one, a jump of one). */
    static final int THROAT_STEPS = 2;
    /** Otherwise at least this many steps go on the level between two changes of height. */
    static final int LEVEL_STEPS = 3;
    /** A decoy throat goes down until this many blocks of ground are over it. */
    static final int ROOF = 2;
    /** The last steps of the way to the node, where its chamber will be. */
    static final int FINAL_STEPS = 24;
    /** How far the heading swings either way (radians). */
    private static final double TURN = 1.8;
    /** How fast the heading swings: noise per step. */
    private static final double TURN_RATE = 0.11;
    /** How far the tunnel wanders up and down (blocks), and how fast. */
    private static final double RISE = 3;
    private static final double RISE_RATE = 0.07;
    /** Times in a row a worm may be turned away before it ends. */
    private static final int MAX_TURNS = 8;
    /** Narrow stretches: noise per step, and the noise above which the section is one wide. */
    private static final double WIDTH_RATE = 0.09;
    private static final double NARROW = 0.3;

    /**
     * What the worm took: its cells (the sections, and the room to jump where it went up from its start), the cell a
     * player stands in at its start and after each step, and the direction of its last step.
     */
    record Dig(Set<Long> cells, List<Long> path, int lastX, int lastZ) {
        /** Steps the worm made. */
        int steps() {
            return path.size() - 1;
        }

        /** Where it ended: the feet of a player at its end. */
        long end() {
            return path.get(path.size() - 1);
        }
    }

    private final VoxelGrid grid;
    private final VoxelGrid.Region region;
    private final Set<Long> ways;
    private final Set<Long> taken;
    private final long seed;
    private final Random random;

    /**
     * @param grid   the grid as it is now (the ways planned so far dug in it)
     * @param region where cells may be dug and floors laid
     * @param ways   the cells players walk through on the ways planned so far: their floors are never dug
     * @param taken  what the worm keeps a block away from after its grace (empty: nothing)
     * @param seed   the seed of its noise
     * @param random where it turns when it does not fit
     */
    Worm(VoxelGrid grid, VoxelGrid.Region region, Set<Long> ways, Set<Long> taken, long seed, Random random) {
        this.grid = grid;
        this.region = region;
        this.ways = ways;
        this.taken = taken;
        this.seed = seed;
        this.random = random;
    }

    /**
     * Digs from the feet cell {@code x, y, z} along {@code heading} (radians, x towards z) for {@code steps} steps, or
     * fewer if it gets stuck. For its first {@code grace} steps it may run next to {@code taken} (where it forks off)
     * and keeps its height.
     */
    Dig dig(int x, int y, int z, double heading, int steps, Slope slope, int grace) {
        Set<Long> cells = new HashSet<>();
        List<Long> path = new ArrayList<>();
        path.add(CellKey.of(x, y, z));
        // The columns of the path (at height 0).
        Set<Long> columns = new HashSet<>();
        columns.add(CellKey.of(x, 0, z));
        int cx = x;
        int cy = y;
        int cz = z;
        double px = cx + 0.5;
        double pz = cz + 0.5;
        // DEEP: deep enough now; ROOFED: under its roof; the height the wandering goes around from then on.
        boolean settled = slope == Slope.LEVEL;
        int baseY = y;
        int sinceLevel = LEVEL_STEPS;
        int turns = 0;
        int lastX = 1;
        int lastZ = 0;
        int done = 0;
        // The swing starts from the heading: the worm sets off the way it is sent (square to the way at a fork).
        double swing = LevelNoise.at(seed, 1, 0, 0.5, 0.5);
        // A unit step of the heading enters a new cell at least every other iteration; the cap only guards that.
        for (int i = 0; done < steps && turns < MAX_TURNS && i < 4 * steps + 64; i++) {
            double angle = heading + TURN * (LevelNoise.at(seed, 1, i * TURN_RATE, 0.5, 0.5) - swing);
            px += Math.cos(angle);
            pz += Math.sin(angle);
            boolean blocked = false;
            while (done < steps && !blocked && (cx != (int) Math.floor(px) || cz != (int) Math.floor(pz))) {
                int ddx = (int) Math.floor(px) - cx;
                int ddz = (int) Math.floor(pz) - cz;
                int mx = Math.abs(ddx) >= Math.abs(ddz) ? Integer.signum(ddx) : 0;
                int mz = mx == 0 ? Integer.signum(ddz) : 0;
                int nx = cx + mx;
                int nz = cz + mz;
                if (columns.contains(CellKey.of(nx, 0, nz))) {
                    // Never back over its own path: no loops, no tunnel dug twice.
                    blocked = true;
                    continue;
                }
                boolean guarded = done >= grace;
                int ny = cy;
                switch (slope) {
                    case DEEP -> {
                        boolean ending = steps - done <= FINAL_STEPS;
                        if (thin(nx, cy, nz, cells, ending)) {
                            settled = false;
                            if (sinceLevel + 1 >= THROAT_STEPS) {
                                ny = cy - 1;
                            }
                        } else {
                            if (!settled) {
                                settled = true;
                                baseY = cy;
                            }
                            ny = toward(cy, baseY + rise(i), sinceLevel);
                            if (ny > cy && thin(nx, ny, nz, cells, ending)) {
                                ny = cy;
                            }
                        }
                    }
                    case ROOFED -> {
                        if (!settled && roof(nx, cy, nz, cells) < ROOF) {
                            if (sinceLevel + 1 >= THROAT_STEPS) {
                                ny = cy - 1;
                            }
                        } else {
                            if (!settled) {
                                settled = true;
                                baseY = cy;
                            }
                            ny = toward(cy, baseY - Math.abs(rise(i)), sinceLevel);
                        }
                    }
                    case LEVEL -> ny = guarded ? toward(cy, baseY - Math.abs(rise(i)), sinceLevel) : cy;
                }
                int wanted = narrow(done) ? 1 : 2;
                // A step up needs the room to jump over the lower cell (in the tunnel it is there already, but not
                // where the tunnel starts: in a 1x2 tunnel, say).
                int width = ny > cy && !diggable(cx, cy + 2, cz, guarded, cells) ? 0
                        : width(nx, ny, nz, mx, mz, wanted, guarded, cells);
                if (width == 0 && ny != cy) {
                    ny = cy;
                    width = width(nx, cy, nz, mx, mz, wanted, guarded, cells);
                }
                if (width == 0) {
                    blocked = true;
                    continue;
                }
                if (ny > cy) {
                    cells.add(CellKey.of(cx, cy + 2, cz));
                }
                sinceLevel = ny != cy ? 0 : sinceLevel + 1;
                cx = nx;
                cy = ny;
                cz = nz;
                lastX = mx;
                lastZ = mz;
                section(cells, cx, cy, cz, mx, mz, width);
                path.add(CellKey.of(cx, cy, cz));
                columns.add(CellKey.of(cx, 0, cz));
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
        return new Dig(cells, path, lastX, lastZ);
    }

    /**
     * A chamber with its floor at the height {@code y}, around {@code x, z}: a dome {@code radius} across and
     * {@code height} high, cut to the cells that may be dug ({@link #diggable}), the lowest ones over a floor
     * ({@link #floor}), the others in the region.
     */
    Set<Long> chamber(int x, int y, int z, double radius, double height, boolean guarded, Set<Long> own) {
        Set<Long> chamber = new HashSet<>();
        int reach = (int) Math.ceil(radius);
        for (int dy = 0; dy < height; dy++) {
            double up = (dy + 0.5) / height;
            for (int dz = -reach; dz <= reach; dz++) {
                for (int dx = -reach; dx <= reach; dx++) {
                    if ((dx * dx + dz * dz) / (radius * radius) + up * up <= 1
                            && (dy == 0 ? floor(x + dx, y - 1, z + dz, guarded, own)
                            : grid.contains(x + dx, y + dy - 1, z + dz) && region.allows(x + dx, y + dy - 1, z + dz))
                            && diggable(x + dx, y + dy, z + dz, guarded, own)) {
                        chamber.add(CellKey.of(x + dx, y + dy, z + dz));
                    }
                }
            }
        }
        return chamber;
    }

    /**
     * The ground over a section standing at {@code x, y, z}: solid cells from {@code y + 3} up to the top of the grid,
     * open ones (a cave over it, a lake, the worm's own cells) not counted, at most {@link WayPlanner#COVER} + 1. Under
     * the roof of a cave deep down it is deep; on open ground it is as deep as it has dug.
     */
    int ground(int x, int y, int z, Set<Long> own) {
        int n = 0;
        for (int h = y + 3; h <= grid.maxY() && n <= WayPlanner.COVER; h++) {
            if (!grid.open(x, h, z) && !own.contains(CellKey.of(x, h, z))) {
                n++;
            }
        }
        return n;
    }

    /**
     * The roof over a section standing at {@code x, y, z}: solid cells right over it, from {@code y + 3} up to the
     * first open one (the worm's own cells counted open), at most {@link WayPlanner#COVER} + 1.
     */
    int roof(int x, int y, int z, Set<Long> own) {
        int n = 0;
        while (n <= WayPlanner.COVER) {
            int h = y + 3 + n;
            if (grid.open(x, h, z) || own.contains(CellKey.of(x, h, z))) {
                break;
            }
            n++;
        }
        return n;
    }

    /**
     * Whether the way to the node is still too near the surface at {@code x, y, z}: not {@link WayPlanner#COVER} + 1
     * blocks of ground over it ({@link #ground}), or, {@code ending} (where its chamber will be), not as much roof
     * ({@link #roof}: under the bed of a lake or the floor of a cave, it goes on down).
     */
    private boolean thin(int x, int y, int z, Set<Long> own, boolean ending) {
        return ground(x, y, z, own) <= WayPlanner.COVER || ending && roof(x, y, z, own) <= WayPlanner.COVER;
    }

    /**
     * Whether the cell may become part of the worm: inside the grid with its neighbours ({@link VoxelGrid#interior},
     * so sealing it knows what is around) and in the region, once {@code guarded} not {@code taken}; never under a cell
     * of its own ({@code own}: the floor of a section it dug before, which would leave a drop it cannot be climbed back
     * up); open already, or else not the floor of a way and (once {@code guarded}) not within a block of {@code taken}.
     */
    private boolean diggable(int x, int y, int z, boolean guarded, Set<Long> own) {
        if (!grid.interior(x, y, z) || !region.allows(x, y, z)) {
            return false;
        }
        long cell = CellKey.of(x, y, z);
        if (guarded && taken.contains(cell)) {
            // Not into another way, even where it is open or where the worm forked off it.
            return false;
        }
        if (own.contains(cell)) {
            return true;
        }
        if (own.contains(CellKey.above(cell, 1))) {
            return false;
        }
        if (grid.flags(x, y, z) == VoxelGrid.OPEN) {
            return true;
        }
        if (ways.contains(CellKey.above(cell, 1)) && !ways.contains(cell)) {
            return false;
        }
        return !guarded || !nearTaken(x, y, z);
    }

    /**
     * Whether the cell under a section of the worm may be its floor: in the region (to be filled if it is open), not
     * open for good (a cell of the worm or of another way: a hole), and not {@code taken} once guarded.
     */
    private boolean floor(int x, int y, int z, boolean guarded, Set<Long> own) {
        long cell = CellKey.of(x, y, z);
        return grid.contains(x, y, z) && region.allows(x, y, z) && !own.contains(cell) && !ways.contains(cell)
                && !(guarded && taken.contains(cell));
    }

    /** The width of the section at {@code x, y, z} going {@code mx, mz} that fits, {@code wanted} first; 0 if none. */
    private int width(int x, int y, int z, int mx, int mz, int wanted, boolean guarded, Set<Long> own) {
        if (!fits(x, y, z, guarded, own)) {
            return 0;
        }
        return wanted == 2 && fits(x - mz, y, z + mx, guarded, own) ? 2 : 1;
    }

    /** Whether a column of the section fits: its three cells may be dug ({@link #diggable}) over a floor that may be laid. */
    private boolean fits(int x, int y, int z, boolean guarded, Set<Long> own) {
        if (!floor(x, y - 1, z, guarded, own)) {
            return false;
        }
        for (int h = 0; h < 3; h++) {
            if (!diggable(x, y + h, z, guarded, own)) {
                return false;
            }
        }
        return true;
    }

    private boolean nearTaken(int x, int y, int z) {
        if (taken.isEmpty()) {
            return false;
        }
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (taken.contains(CellKey.of(x + dx, y + dy, z + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The section at {@code x, y, z} going {@code mx, mz}: three high, the second column (if two wide) to the left. */
    private static void section(Set<Long> cells, int x, int y, int z, int mx, int mz, int width) {
        for (int h = 0; h < 3; h++) {
            cells.add(CellKey.of(x, y + h, z));
            if (width == 2) {
                cells.add(CellKey.of(x - mz, y + h, z + mx));
            }
        }
    }

    /** A block from {@code y} towards {@code want}, if the height may change now. */
    private static int toward(int y, int want, int sinceLevel) {
        return sinceLevel >= LEVEL_STEPS && want != y ? y + Integer.signum(want - y) : y;
    }

    /** How far the tunnel wants to be up or down at iteration {@code i}: -3..3 blocks. */
    private int rise(int i) {
        return (int) Math.round(RISE * LevelNoise.at(seed, 2, i * RISE_RATE, 0.5, 0.5));
    }

    /** Whether the section is one wide at step {@code step}. */
    private boolean narrow(int step) {
        return LevelNoise.at(seed, 4, step * WIDTH_RATE, 0.5, 0.5) > NARROW;
    }
}
