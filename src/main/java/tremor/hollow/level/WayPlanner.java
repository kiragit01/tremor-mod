package tremor.hollow.level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Where the node goes and the network of ways around it (SPEC 9, "Узел": "Узел в 40–60 шагах от игрока; к нему ведёт
 * сеть ходов с развилками и тупиками, вырезанная в копии. На открытой местности узел уходит под землю — вниз ведёт
 * открывшееся «горло». К узлу всегда ведёт проход"). Plain Java, on a {@link VoxelGrid}. The work is cut into
 * {@link #step}s of a millisecond or two (a walk of the grid, a {@link Worm}, a check), so the game spreads it over
 * ticks.
 * <p>
 * <b>The walk</b> ({@link #distances}) goes over the cells a player can stand in, a block aside at a time: on the
 * level, a block up (with room to jump), or down a drop of up to {@value #MAX_DROP} blocks, never through lava or fire
 * (water is swum through). Lengths are steps of this walk.
 * <p>
 * <b>The way to the node</b> is a worm of the {@link Worm.Slope#DEEP} kind, from where the player arrived, or, on open
 * ground (the player under the sky or nearly, {@link #nearSurface}), from a mouth on the surface
 * {@value #MOUTH_NEAR}..{@value #MOUTH_FAR} blocks away that the player walks to: the throat, a trench that goes down
 * into the ground at a walkable slope. It winds on through the rock (and through the caves of the copy in its way, as
 * a walled corridor over a floor laid for it) to a chamber with the node under {@value #COVER} blocks of solid ground
 * at least: the node is not seen from anywhere but its chamber, and its glow does not get out. Each try is checked by
 * walking the grid with it dug: {@code minLength}..{@code maxLength} steps from the player to the node,
 * {@code minStraight} blocks or more from where the player arrived in a straight line and {@value #CLEARANCE} or more
 * from every place of the start the player walks to without digging (the widened cave, the ground around), the ground
 * over the chamber whole, every place of the tunnel and the chamber walked back from to the start ({@link
 * #walksBack}), and no place a player walks to left without a way on to the node that the copy did not have already
 * ({@link #stuck}: the roof of a corridor a drop below the ground of the start, leading on down into a cave). A try
 * that fails (a cave of the start cuts the way short, the edge of the region turns it back, the way winds back round to
 * its chamber right behind the wall of the start cave) is followed by another, {@value #TRIES} at most, and the best
 * is kept (the game tells of a way it could not make long enough, or of places to be stuck in).
 * <p>
 * <b>The dead ends</b> ({@code minBranches}..{@code maxBranches}), worms of {@value #BRANCH_MIN}..{@value #BRANCH_MAX}
 * steps, half of them ending in a small chamber, so the player has to choose: one leaves the start (the widened cave)
 * or, on open ground, one or two decoy throats go down from the surface beside the true one; the others fork off the
 * way to the node, far out to the side facing the player (the way often runs along the edge of the region there). A
 * dead end keeps a block away from the other ways once off its fork, and is dropped if the walk to the node gets
 * shorter with it, if a place of it is not walked back from to the start or the way to the node (a drop the player
 * could not climb back up would end the search there), or if it leaves a new place without a way on to the node
 * ({@link #stuck}); another is tried, {@value #BRANCH_TRIES} times, and one that cannot be dug is made up for by
 * another fork while there are fewer than {@code minBranches}.
 * <p>
 * Everything dug is sealed (a fluid, lava or fire next to it is filled, {@link VoxelGrid#leaksAround}), stands on a
 * floor (the cell under it is filled if it is open, hurts or is too tall to stand on), is walled where it passes
 * through open space of the copy ({@link #addWalls}), lies in the region and never under the player's feet. The way
 * ({@link Plan#way}) is every cell the player passes through on the way to the node: the feet and head cells of the
 * walk to the throat with the room for each jump and the column of each drop, the tunnel and the chamber. Nothing may
 * ever fill it; the dead ends may close.
 */
public final class WayPlanner {
    /** Longest drop the walk takes. */
    static final int MAX_DROP = 3;
    /** Solid ground over the node's chamber, at least (blocks). */
    static final int COVER = 4;
    /** Tries of the way to the node before the best one is taken. */
    static final int TRIES = 12;
    /** The walk around the start, where the throats and the dead end out of the start begin (steps). */
    private static final int NEAR_STEPS = 16;
    /**
     * The open space of the start: the air over every cell a player walks to in this many steps without digging (the
     * widened cave, the ground around), where the tunnels get no walls.
     */
    static final int START_STEPS = 24;
    /**
     * The node lies at least this far (blocks, in a straight line) from every cell of that walk, so that its chamber is
     * not right behind the wall of the start cave, a few blocks of digging away (some 4 blocks of rock at least).
     */
    static final double CLEARANCE = 7;
    /** Within this many steps of the player a cell near the surface makes the place open ground. */
    private static final int SURFACE_STEPS = 2;
    /** The mouth of the throat: this far from the player, horizontally (blocks). */
    static final int MOUTH_NEAR = 4;
    static final int MOUTH_FAR = 8;
    /** How far the throat's heading may turn from the line from the player to its mouth (radians, either way). */
    private static final double MOUTH_JITTER = 0.4;
    /** The mouth of a decoy throat: this far from the player... */
    static final int DECOY_NEAR = 4;
    static final int DECOY_FAR = 10;
    /** ...this far round from the true one (radians), and this far from any way (blocks). */
    private static final double DECOY_APART = 1.2;
    private static final int DECOY_CLEAR = 3;
    /** The dead end out of the start: from a cell this far from the player... */
    private static final int START_NEAR = 3;
    private static final int START_FAR = 9;
    /** ...this far round from where the way to the node leaves (radians), and this far from it (blocks). */
    private static final double START_APART = 1.75;
    private static final int START_CLEAR = 2;
    /** The way to the node leaves the start in the direction of its cell this many steps on. */
    private static final int LEAVING_STEPS = 6;
    /** A dead end: steps. */
    static final int BRANCH_MIN = 8;
    static final int BRANCH_MAX = 20;
    /** Dead ends fork off the way to the node no closer than this many of its steps to its start... */
    private static final int FORK_FROM = 4;
    /** ...nor to its end. */
    private static final int FORK_TO = 8;
    /** How far a fork's heading may turn from square to the way (radians, either way). */
    private static final double FORK_JITTER = 0.5;
    /** Farther than this from the player (blocks, horizontally) a fork goes to the side of the way facing the player. */
    private static final double INWARD = 12;
    /** Steps a dead end may run next to the way it leaves: at a fork, out of the start. */
    private static final int FORK_GRACE = 3;
    private static final int START_GRACE = 2;
    /** Tries of each dead end. */
    private static final int BRANCH_TRIES = 3;
    /** The length aimed at lies this many steps (at most a quarter of the range) over the shortest allowed. */
    private static final int SLACK = 3;
    /** The chamber of the node, and the small one at the end of a dead end: horizontal radius and height. */
    private static final double CHAMBER_RADIUS = 2.6;
    private static final double CHAMBER_HEIGHT = 3.6;
    private static final double SMALL_RADIUS = 2.0;
    private static final double SMALL_HEIGHT = 3.0;
    private static final int[] DX = {1, 0, -1, 0};
    private static final int[] DZ = {0, 1, 0, -1};

    /**
     * What the network is to be like.
     *
     * @param minLength   fewest steps from the player to the node
     * @param maxLength   most steps (at least {@code minLength})
     * @param minStraight fewest blocks from the player to the node in a straight line
     * @param minBranches fewest dead ends
     * @param maxBranches most dead ends (at least {@code minBranches})
     */
    public record Params(int minLength, int maxLength, double minStraight, int minBranches, int maxBranches) {
        public Params {
            minLength = Math.max(1, minLength);
            maxLength = Math.max(minLength, maxLength);
            minBranches = Math.max(0, minBranches);
            maxBranches = Math.max(minBranches, maxBranches);
        }
    }

    /**
     * What to change and keep: the node's cell, the cells to empty ({@code carve}: the tunnels and chambers) and to
     * fill ({@code fill}: the floors under them, the walls where they pass through open space, and the fluids, lava and
     * fire next to them), and the way to the node to keep open.
     *
     * @param network      every cell of the way to the node and of the dead ends (dug or open already), and the node
     * @param length       steps from the player to the node
     * @param straight     blocks from the player to the node in a straight line
     * @param clearance    blocks from the node to the nearest place of the start the player walks to without digging
     *                     ({@value #NEAR_STEPS} steps of the walk on the grid as it was)
     * @param covered      whether the node's chamber has {@value #COVER} blocks of solid ground over it
     * @param throat       whether the way starts with a throat from the surface
     * @param mouth        where the tunnel of the way starts (the feet of a player there): the mouth of the throat,
     *                     or a cell of the start
     * @param branches     dead ends dug
     * @param fromStart    ...of them leaving the start
     * @param decoys       ...of them decoy throats from the surface
     * @param branchStarts where each dead end starts (the feet of a player there)
     * @param branchEnds   where each ends
     * @param tries        tries of the way to the node
     * @param stuck        places a player walks to and gets stuck in, the node out of reach, that are not the copy's
     *                     own pits ({@link WayPlanner#stuck}): 0 unless the best try of the way to the node left some
     *                     (the dead ends never do)
     */
    public record Plan(long node, Set<Long> carve, Set<Long> fill, Set<Long> way, Set<Long> network, int length,
                       double straight, double clearance, boolean covered, boolean throat, long mouth, int branches,
                       int fromStart, int decoys, List<Long> branchStarts, List<Long> branchEnds, int tries,
                       int stuck) {
        /** Writes the plan into the grid: the node is solid. */
        public void applyTo(VoxelGrid grid) {
            for (long cell : carve) {
                grid.carve(cell);
            }
            for (long cell : fill) {
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

    private enum Phase {
        WALK, WAY, BRANCHES, DONE
    }

    /** Where a dead end starts. */
    private enum Kind {
        FORK, START, DECOY
    }

    /** What some cells dug change around them: the cells to empty, and the floors, walls and seals to fill. */
    private record Changes(Set<Long> carve, Set<Long> fill) {
        void applyTo(VoxelGrid grid) {
            for (long cell : carve) {
                grid.carve(cell);
            }
            for (long cell : fill) {
                grid.fill(cell);
            }
        }
    }

    /**
     * A try of the way to the node: the grid with it dug (the node's cell open), its changes, its way (without the
     * node), its own cells (tunnel, chamber), the ground over its chamber, the worm's path, what the check found.
     */
    private record Try(VoxelGrid grid, Changes changes, Set<Long> way, Set<Long> cells, Set<Long> cover, long node,
                       int length, double straight, double clearance, boolean covered, boolean throat, double mouth,
                       List<Long> path, int stuck, double penalty) {
    }

    private final VoxelGrid base;
    private final VoxelGrid.Region region;
    /** The region but the cells under the player's feet: the player would fall into them on arrival. */
    private final VoxelGrid.Region carvable;
    private final int x;
    private final int y;
    private final int z;
    private final Params params;
    private final long seed;
    private final Random random;
    private Phase phase = Phase.WALK;
    /** The walk around the start on the grid as it was, {@value #NEAR_STEPS} steps. */
    private Walk near;
    /** The open space of the start ({@value #START_STEPS}), by the index of the cell in the grid. */
    private boolean[] startAir;
    /** The cells of {@link #near} (indices of the grid): the start, where the way to the node walks back to. */
    private int[] startCells;
    /** ...and the cells stood in along the way to the node: where a dead end walks back to. */
    private int[] returnCells;
    /**
     * The copy's own pits, by the index of the cell in the grid: the places a player walks to from the start on the
     * grid as it was but not back (the foot of a cliff, the floor of a cave dropped into). A player there may never get
     * to the node, but that is the copy's doing, not the network's ({@link #stuck}).
     */
    private boolean[] natural;
    /** Where the way to the node chosen leaves a player stuck ({@link #stuck}; the dead ends add none), or null. */
    private boolean[] lost;
    private boolean openGround;
    private int tries;
    private Try best;

    /** The grid with all planned so far dug in it (the node's cell open). */
    private VoxelGrid grid;
    private final Set<Long> carve = new HashSet<>();
    private final Set<Long> fill = new HashSet<>();
    private final Set<Long> way = new HashSet<>();
    /** Every cell of the way and of the dead ends, and the node: never filled, their floors never dug. */
    private final Set<Long> ways = new HashSet<>();
    /** What a dead end keeps a block away from: {@link #ways} and the ground over the node's chamber. */
    private final Set<Long> taken = new HashSet<>();
    private Try chosen;

    private final List<Kind> kinds = new ArrayList<>();
    private int kind;
    private int kindTries;
    private int branchTries;
    private int fromStart;
    private int decoys;
    private final List<Long> branchStarts = new ArrayList<>();
    private final List<Long> branchEnds = new ArrayList<>();
    /** Reused by the walks of {@link #stepsTo}, {@link #walksBack} and {@link #stuck}: all -1 between walks. */
    private int[] scratchDistance;
    private int[] scratchOrder;
    private int[] scratchBack;
    private int[] scratchBackOrder;
    private Plan plan;

    /**
     * Plans for a player at {@code x, y, z} (feet) of {@code grid}, which is not changed; the cells changed and the
     * node lie where {@code region} allows.
     *
     * @throws IllegalArgumentException if the player's cell is outside the grid
     */
    public WayPlanner(VoxelGrid grid, VoxelGrid.Region region, int x, int y, int z, Params params, long seed) {
        if (!grid.contains(x, y, z)) {
            throw new IllegalArgumentException("The start " + x + " " + y + " " + z + " is outside the grid");
        }
        this.base = grid;
        this.region = region;
        this.carvable = (cx, cy, cz) -> region.allows(cx, cy, cz) && (cx != x || cz != z || cy >= y);
        this.x = x;
        this.y = y;
        this.z = z;
        this.params = params;
        this.seed = seed;
        this.random = new Random(seed);
    }

    /** Plans it all at once ({@link #step} until done). */
    public static Plan plan(VoxelGrid grid, VoxelGrid.Region region, int x, int y, int z, Params params, long seed) {
        WayPlanner planner = new WayPlanner(grid, region, x, y, z, params, seed);
        while (!planner.step()) {
            // Every step does a part.
        }
        return planner.plan();
    }

    /** Does the next part of the planning; true once it is done ({@link #plan}). */
    public boolean step() {
        switch (phase) {
            case WALK -> walk();
            case WAY -> tryWay();
            case BRANCHES -> tryBranch();
            case DONE -> {
            }
        }
        return phase == Phase.DONE;
    }

    /** The plan, once {@link #step} said it is done. */
    public Plan plan() {
        if (plan == null) {
            throw new IllegalStateException("Not planned yet");
        }
        return plan;
    }

    // ---- the parts ----

    private void walk() {
        near = distances(base, x, y, z, NEAR_STEPS);
        for (int i = 0; i < near.reached() && !openGround; i++) {
            int cell = near.order()[i];
            if (near.distance()[cell] > SURFACE_STEPS) {
                break;
            }
            openGround = nearSurface(base, key(base, cell));
        }
        startCells = Arrays.copyOf(near.order(), near.reached());
        Walk start = distances(base, x, y, z, START_STEPS);
        startAir = new boolean[base.volume()];
        for (int i = 0; i < start.reached(); i++) {
            long cell = key(base, start.order()[i]);
            int cx = CellKey.x(cell);
            int cz = CellKey.z(cell);
            for (int h = CellKey.y(cell); base.contains(cx, h, cz) && base.open(cx, h, cz); h++) {
                startAir[base.index(cx, h, cz)] = true;
            }
        }
        scratch(base);
        int player = base.index(x, y, z);
        int reached = walk(base, player, Integer.MAX_VALUE, -1, scratchDistance, null, scratchOrder);
        int found = walkBack(base, new int[] {player}, Integer.MAX_VALUE, scratchBack, scratchBackOrder);
        natural = new boolean[base.volume()];
        for (int i = 0; i < reached; i++) {
            natural[scratchOrder[i]] = scratchBack[scratchOrder[i]] < 0;
        }
        clear(reached, found);
        phase = Phase.WAY;
    }

    private void tryWay() {
        Try next = tryOfTheWay(tries++);
        if (best == null || next.penalty() < best.penalty()) {
            best = next;
        }
        if (best.penalty() > 0 && tries < TRIES) {
            return;
        }
        commit(best);
        int count = params.minBranches() + random.nextInt(params.maxBranches() - params.minBranches() + 1);
        if (count > 0) {
            if (chosen.throat()) {
                for (int i = Math.min(count, 1 + random.nextInt(2)); i > 0; i--) {
                    kinds.add(Kind.DECOY);
                }
            } else {
                kinds.add(Kind.START);
            }
        }
        while (kinds.size() < count) {
            kinds.add(Kind.FORK);
        }
        phase = Phase.BRANCHES;
    }

    private void tryBranch() {
        if (kind >= kinds.size()) {
            plan = new Plan(chosen.node(), carve, fill, way, ways, chosen.length(), chosen.straight(),
                    chosen.clearance(), chosen.covered(), chosen.throat(), chosen.path().get(0), branchEnds.size(),
                    fromStart, decoys, List.copyOf(branchStarts), List.copyOf(branchEnds), tries, chosen.stuck());
            phase = Phase.DONE;
            return;
        }
        Random r = new Random(LevelNoise.mix(seed * 31 + 7919L * (kind + 1) + kindTries));
        boolean dug = branch(kinds.get(kind), r);
        kindTries++;
        branchTries++;
        if (dug || kindTries >= BRANCH_TRIES) {
            kind++;
            kindTries = 0;
            // One that could not be dug is made up for by another fork, while there are too few.
            if (!dug && branchEnds.size() + kinds.size() - kind < params.minBranches()
                    && branchTries < BRANCH_TRIES * (params.maxBranches() + 2)) {
                kinds.add(Kind.FORK);
            }
        }
    }

    /** One try of the way to the node (see the class comment). */
    private Try tryOfTheWay(int n) {
        Random r = new Random(LevelNoise.mix(seed + 0x9E3779B97F4A7C15L * (n + 1)));
        int start = near.order()[0];
        boolean throat = false;
        if (openGround) {
            int mouth = pick(r, cell -> {
                long at = key(base, cell);
                double across = Math.hypot(CellKey.x(at) - x, CellKey.z(at) - z);
                return across >= MOUTH_NEAR && across <= MOUTH_FAR && allowed(cell) && nearSurface(base, at);
            });
            if (mouth >= 0) {
                start = mouth;
                throat = true;
            }
        }
        long from = key(base, start);
        int sx = CellKey.x(from);
        int sy = CellKey.y(from);
        int sz = CellKey.z(from);
        Set<Long> path = new HashSet<>();
        addPath(near, start, path);
        double mouth = Math.atan2(sz - z, sx - x);
        double heading = throat ? mouth + (2 * r.nextDouble() - 1) * MOUTH_JITTER : r.nextDouble() * 2 * Math.PI;
        int range = params.maxLength() - params.minLength();
        int slack = Math.min(SLACK, range / 4);
        int target = params.minLength() + slack + r.nextInt(range - slack + 1);
        // The node is a step on from the end of the tunnel, in the chamber.
        int steps = Math.max(1, target - near.distance()[start] - 1);
        Worm worm = new Worm(base, carvable, path, Set.of(), r.nextLong(), r);
        Worm.Dig dig = worm.dig(sx, sy, sz, heading, steps, Worm.Slope.DEEP, 0);
        long end = dig.end();
        int ex = CellKey.x(end);
        int ey = CellKey.y(end);
        int ez = CellKey.z(end);
        Set<Long> chamber = worm.chamber(ex, ey, ez, CHAMBER_RADIUS, CHAMBER_HEIGHT, false, dig.cells());
        long node = end;
        for (int ahead = 2; ahead >= 1; ahead--) {
            long cell = CellKey.of(ex + ahead * dig.lastX(), ey, ez + ahead * dig.lastZ());
            if (chamber.contains(cell)) {
                node = cell;
                break;
            }
        }
        Set<Long> cells = new HashSet<>(dig.cells());
        cells.addAll(chamber);
        Set<Long> wayCells = new HashSet<>(path);
        wayCells.addAll(cells);
        wayCells.remove(node);
        Set<Long> keep = new HashSet<>(wayCells);
        keep.add(node);
        Changes changes = changes(base, cells, keep, node);
        VoxelGrid trial = base.copy();
        changes.applyTo(trial);
        trial.carve(node);
        int length = stepsTo(trial, node, 2 * params.maxLength() + 8);
        double straight = Math.sqrt(square(CellKey.x(node) - x) + square(CellKey.y(node) - y)
                + square(CellKey.z(node) - z));
        double clearance = clearance(node);
        Set<Long> cover = new HashSet<>();
        chamber.add(node);
        boolean covered = cover(trial, chamber, cells, cover);
        // The node's cell is among the cells (the chamber's or the tunnel's end), open in the trial.
        boolean returns = walksBack(trial, from, cells, startCells, 2 * params.maxLength() + 8);
        int stuck = length < 0 ? 0 : stuck(trial, node, null, null);
        double penalty = (returns ? 0 : 2000) + (stuck == 0 ? 0 : 1500) + (covered ? 0 : 1000)
                + 10 * Math.max(0, params.minStraight() - straight) + 10 * Math.max(0, CLEARANCE - clearance)
                + 10 * (length < 0 ? 1000 : Math.max(0, params.minLength() - length)
                + Math.max(0, length - params.maxLength()));
        return new Try(trial, changes, wayCells, cells, cover, node, length, straight, clearance, covered, throat,
                mouth, dig.path(), stuck, penalty);
    }

    /** The way to the node is chosen: it is dug into {@link #grid}, the dead ends go around it. */
    private void commit(Try chosen) {
        this.chosen = chosen;
        grid = chosen.grid();
        carve.addAll(chosen.changes().carve());
        fill.addAll(chosen.changes().fill());
        way.addAll(chosen.way());
        ways.addAll(chosen.way());
        ways.addAll(chosen.cells());
        ways.add(chosen.node());
        taken.addAll(ways);
        taken.addAll(chosen.cover());
        returnCells = Arrays.copyOf(startCells, startCells.length + chosen.path().size());
        for (int i = 0; i < chosen.path().size(); i++) {
            long cell = chosen.path().get(i);
            returnCells[startCells.length + i] = grid.index(CellKey.x(cell), CellKey.y(cell), CellKey.z(cell));
        }
        if (chosen.stuck() > 0) {
            lost = new boolean[grid.volume()];
            stuck(grid, chosen.node(), null, lost);
        }
    }

    /** One try of a dead end of the kind; true if it was dug. */
    private boolean branch(Kind what, Random r) {
        List<Long> path = chosen.path();
        long start;
        double heading;
        int grace;
        Worm.Slope slope = Worm.Slope.LEVEL;
        switch (what) {
            case FORK -> {
                int first = Math.min(FORK_FROM, path.size() - 1);
                int last = Math.max(first, path.size() - 1 - FORK_TO);
                int at = first + r.nextInt(last - first + 1);
                start = path.get(at);
                long before = path.get(Math.max(0, at - 2));
                long after = path.get(Math.min(path.size() - 1, at + 2));
                double along = Math.atan2(CellKey.z(after) - CellKey.z(before), CellKey.x(after) - CellKey.x(before));
                // Far out the way often runs along the edge of the region: there a fork goes to the inner side.
                double fx = CellKey.x(start) - x;
                double fz = CellKey.z(start) - z;
                int side = Math.hypot(fx, fz) > INWARD ? (Math.sin(along) * fx - Math.cos(along) * fz > 0 ? 1 : -1)
                        : r.nextBoolean() ? 1 : -1;
                heading = along + side * (Math.PI / 2 + (2 * r.nextDouble() - 1) * FORK_JITTER);
                grace = FORK_GRACE;
            }
            case START -> {
                long on = path.get(Math.min(LEAVING_STEPS, path.size() - 1));
                double leaving = Math.atan2(CellKey.z(on) - z, CellKey.x(on) - x);
                int cell = pick(r, c -> away(c, START_NEAR, START_FAR, leaving, START_APART, START_CLEAR));
                if (cell < 0) {
                    // Nowhere in the start to leave from but the player's own cell.
                    start = CellKey.of(x, y, z);
                    heading = leaving + Math.PI + (2 * r.nextDouble() - 1) * FORK_JITTER;
                    grace = FORK_GRACE + START_GRACE;
                } else {
                    start = key(base, cell);
                    heading = Math.atan2(CellKey.z(start) - z, CellKey.x(start) - x);
                    grace = START_GRACE;
                }
            }
            case DECOY -> {
                int cell = pick(r, c -> away(c, DECOY_NEAR, DECOY_FAR, chosen.mouth(), DECOY_APART, DECOY_CLEAR)
                        && nearSurface(base, key(base, c)));
                if (cell < 0) {
                    // No room on the surface for another throat: a fork instead.
                    kinds.set(kind, Kind.FORK);
                    return false;
                }
                start = key(base, cell);
                heading = Math.atan2(CellKey.z(start) - z, CellKey.x(start) - x);
                grace = 0;
                slope = Worm.Slope.ROOFED;
            }
            default -> throw new IllegalStateException();
        }
        int steps = BRANCH_MIN + r.nextInt(BRANCH_MAX - BRANCH_MIN + 1);
        Worm worm = new Worm(grid, carvable, ways, taken, r.nextLong(), r);
        Worm.Dig dig = worm.dig(CellKey.x(start), CellKey.y(start), CellKey.z(start), heading, steps, slope, grace);
        if (dig.steps() < BRANCH_MIN) {
            return false;
        }
        Set<Long> cells = new HashSet<>(dig.cells());
        if (r.nextBoolean()) {
            long end = dig.end();
            cells.addAll(worm.chamber(CellKey.x(end), CellKey.y(end), CellKey.z(end), SMALL_RADIUS, SMALL_HEIGHT, true,
                    dig.cells()));
        }
        Set<Long> keep = new HashSet<>(ways);
        keep.addAll(cells);
        Changes changes = changes(grid, cells, keep, null);
        VoxelGrid trial = grid.copy();
        changes.applyTo(trial);
        if (stepsTo(trial, chosen.node(), chosen.length()) != chosen.length()) {
            // A shortcut to the node (or, which should not be, the node cut off): not this one.
            return false;
        }
        if (stepsTo(trial, dig.end(), 4 * params.maxLength() + BRANCH_MAX) < 0) {
            // Cut off (sealed from where it starts, say): no dead end, just a hole in the rock.
            return false;
        }
        if (!walksBack(trial, start, cells, returnCells, 2 * BRANCH_MAX + 8)) {
            // A drop in it the player could not climb back up: a trap, not a dead end.
            return false;
        }
        if (stuck(trial, chosen.node(), lost, null) > 0) {
            // Its walls make a way down into a pit of the copy, or it leads on from one: a place to be stuck in.
            return false;
        }
        grid = trial;
        // A cell filled before (a seal next to a way) that this one empties is empty.
        fill.removeAll(changes.carve());
        carve.addAll(changes.carve());
        fill.addAll(changes.fill());
        ways.addAll(cells);
        taken.addAll(cells);
        branchStarts.add(start);
        branchEnds.add(dig.end());
        fromStart += what == Kind.START ? 1 : 0;
        decoys += what == Kind.DECOY ? 1 : 0;
        return true;
    }

    // ---- the walk ----

    /** The walk from {@code x, y, z} up to {@code maxLength} steps. */
    static Walk distances(VoxelGrid grid, int x, int y, int z, int maxLength) {
        if (!grid.contains(x, y, z)) {
            throw new IllegalArgumentException("The start " + x + " " + y + " " + z + " is outside the grid");
        }
        int[] distance = new int[grid.volume()];
        int[] parent = new int[grid.volume()];
        Arrays.fill(distance, -1);
        int[] order = new int[grid.volume()];
        int reached = walk(grid, grid.index(x, y, z), maxLength, -1, distance, parent, order);
        return new Walk(grid, distance, parent, order, reached);
    }

    /**
     * The walk from the cell {@code start} up to {@code maxLength} steps, into {@code distance} (all -1 before),
     * {@code parent} (if not null) and {@code order}; it stops as soon as it reaches the cell {@code target} (-1: none).
     * Returns the number of cells reached (the first ones of {@code order}).
     */
    private static int walk(VoxelGrid grid, int start, int maxLength, int target, int[] distance, int[] parent,
                            int[] order) {
        distance[start] = 0;
        if (parent != null) {
            parent[start] = -1;
        }
        order[0] = start;
        int head = 0;
        int tail = 1;
        if (start == target) {
            return tail;
        }
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
                    if (parent != null) {
                        parent[next] = cell;
                    }
                    order[tail++] = next;
                    if (next == target) {
                        return tail;
                    }
                }
            }
        }
        return tail;
    }

    /**
     * The walk back to any of the cells {@code targets} up to {@code maxLength} steps, into {@code distance} (all -1
     * before) and {@code order}: the steps from each cell that walks there ({@link #landing}, as {@link #walk} goes).
     * Returns the number of cells found (the first ones of {@code order}).
     */
    private static int walkBack(VoxelGrid grid, int[] targets, int maxLength, int[] distance, int[] order) {
        int head = 0;
        int tail = 0;
        for (int target : targets) {
            if (distance[target] < 0) {
                distance[target] = 0;
                order[tail++] = target;
            }
        }
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
                int px = cx - DX[dir];
                int pz = cz - DZ[dir];
                // From a block below (a jump up), the same height, or a drop.
                for (int py = cy - 1; py <= cy + MAX_DROP; py++) {
                    if (!grid.contains(px, py, pz)) {
                        continue;
                    }
                    int from = grid.index(px, py, pz);
                    if (distance[from] < 0 && grid.standable(px, py, pz) && landing(grid, px, py, pz, cx, cz) == cy) {
                        distance[from] = distance[cell] + 1;
                        order[tail++] = from;
                    }
                }
            }
        }
        return tail;
    }

    /** Steps from the player to {@code target} in {@code grid}, -1 if more than {@code maxLength} or no way. */
    private int stepsTo(VoxelGrid grid, long target, int maxLength) {
        int tx = CellKey.x(target);
        int ty = CellKey.y(target);
        int tz = CellKey.z(target);
        if (!grid.contains(tx, ty, tz)) {
            return -1;
        }
        scratch(grid);
        int to = grid.index(tx, ty, tz);
        int reached = walk(grid, grid.index(x, y, z), maxLength, to, scratchDistance, null, scratchOrder);
        int steps = scratchDistance[to];
        for (int i = 0; i < reached; i++) {
            scratchDistance[scratchOrder[i]] = -1;
        }
        return steps;
    }

    /**
     * Whether a player who walks from {@code from} into any of {@code cells} in {@code grid} (a cell stood in) walks
     * on back to one of the cells {@code to} (indices of the grid), both ways within {@code maxLength} steps: no drop
     * among them that cannot be climbed back up, no pit to be stuck in.
     */
    private boolean walksBack(VoxelGrid grid, long from, Set<Long> cells, int[] to, int maxLength) {
        scratch(grid);
        int start = grid.index(CellKey.x(from), CellKey.y(from), CellKey.z(from));
        int reached = walk(grid, start, maxLength, -1, scratchDistance, null, scratchOrder);
        int found = walkBack(grid, to, maxLength, scratchBack, scratchBackOrder);
        boolean returns = true;
        for (long cell : cells) {
            int cx = CellKey.x(cell);
            int cy = CellKey.y(cell);
            int cz = CellKey.z(cell);
            if (grid.contains(cx, cy, cz) && scratchDistance[grid.index(cx, cy, cz)] >= 0
                    && scratchBack[grid.index(cx, cy, cz)] < 0) {
                returns = false;
                break;
            }
        }
        clear(reached, found);
        return returns;
    }

    /**
     * The places of {@code grid} a player walks to from the start but not on to {@code node} (open in it): where the
     * network made a place to be stuck in, the node out of reach (the roof of a corridor through a cave, a drop below
     * the ground of the start, that leads on down into the cave; a floor laid over the steps up out of a dip of the
     * start cave; a dead end that leads on from a pit of the copy). The copy's own pits ({@link #natural}) and the
     * player's own cell (a slab, say, not stood in) do not count, nor do the places {@code known} says (if not null).
     * Returns how many there are, and marks them in {@code into} (if not null).
     */
    private int stuck(VoxelGrid grid, long node, boolean[] known, boolean[] into) {
        scratch(grid);
        int player = grid.index(x, y, z);
        int reached = walk(grid, player, Integer.MAX_VALUE, -1, scratchDistance, null, scratchOrder);
        int found = walkBack(grid, new int[] {grid.index(CellKey.x(node), CellKey.y(node), CellKey.z(node))},
                Integer.MAX_VALUE, scratchBack, scratchBackOrder);
        int stuck = 0;
        for (int i = 1; i < reached; i++) {
            int cell = scratchOrder[i];
            if (scratchBack[cell] < 0 && !natural[cell] && (known == null || !known[cell])) {
                stuck++;
                if (into != null) {
                    into[cell] = true;
                }
            }
        }
        clear(reached, found);
        return stuck;
    }

    /** Sets the arrays of the last walk ({@code reached} cells) and walk back ({@code found}) back to all -1. */
    private void clear(int reached, int found) {
        for (int i = 0; i < reached; i++) {
            scratchDistance[scratchOrder[i]] = -1;
        }
        for (int i = 0; i < found; i++) {
            scratchBack[scratchBackOrder[i]] = -1;
        }
    }

    /** Makes the arrays of the walks of the planning for grids the size of {@code grid}. */
    private void scratch(VoxelGrid grid) {
        if (scratchDistance == null) {
            scratchDistance = new int[grid.volume()];
            scratchOrder = new int[grid.volume()];
            scratchBack = new int[grid.volume()];
            scratchBackOrder = new int[grid.volume()];
            Arrays.fill(scratchDistance, -1);
            Arrays.fill(scratchBack, -1);
        }
    }

    /**
     * Where a player standing at {@code x, y, z} ends up after a block aside to {@code nx, nz}: the same height, a block
     * up (with the room to jump above the head), or down a drop; {@link Integer#MIN_VALUE} if there is no way. Nothing
     * on the way hurts (a drop through lava, a jump into fire).
     */
    static int landing(VoxelGrid grid, int x, int y, int z, int nx, int nz) {
        if (grid.standable(nx, y, nz)) {
            return y;
        }
        if (grid.standable(nx, y + 1, nz) && grid.passable(x, y + 2, z)) {
            return y + 1;
        }
        if (!grid.passable(nx, y, nz) || !grid.passable(nx, y + 1, nz)) {
            return Integer.MIN_VALUE;
        }
        for (int ly = y - 1; ly >= y - MAX_DROP && grid.passable(nx, ly, nz); ly--) {
            if (grid.standable(nx, ly, nz)) {
                return ly;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Whether a player standing at {@code x, y, z} is under the sky or nearly: fewer than {@value #COVER} solid cells
     * over the head (leaves, a roof), and open at the top of the grid.
     */
    static boolean nearSurface(VoxelGrid grid, int x, int y, int z) {
        if (!grid.open(x, grid.maxY(), z)) {
            return false;
        }
        int solid = 0;
        for (int h = y + 2; h < grid.maxY() && solid < COVER; h++) {
            solid += grid.open(x, h, z) ? 0 : 1;
        }
        return solid < COVER;
    }

    private static boolean nearSurface(VoxelGrid grid, long cell) {
        return nearSurface(grid, CellKey.x(cell), CellKey.y(cell), CellKey.z(cell));
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

    // ---- internals ----

    /**
     * What digging {@code cells} out of {@code grid} changes: the cells that are not plain open yet (but the node's),
     * the floors under them (and under the node) not in {@code keep}, the seals around what is emptied, and the walls
     * ({@link #addWalls}).
     */
    private Changes changes(VoxelGrid grid, Set<Long> cells, Set<Long> keep, Long node) {
        Set<Long> dig = new HashSet<>();
        for (long cell : cells) {
            if (grid.flags(CellKey.x(cell), CellKey.y(cell), CellKey.z(cell)) != VoxelGrid.OPEN) {
                dig.add(cell);
            }
        }
        Set<Long> filled = new HashSet<>();
        for (long cell : cells) {
            addFloor(grid, keep, cell, filled);
        }
        if (node != null) {
            dig.remove(node);
            addFloor(grid, keep, node, filled);
        }
        filled.addAll(grid.leaksAround(dig, keep));
        addWalls(grid, cells, keep, filled);
        return new Changes(dig, filled);
    }

    /**
     * The walls of a tunnel where it passes through open space of the copy (a cave, a ravine, the air or the water
     * over the ground): every open cell beside a cell of {@code cells} or right over it that is not one of them nor in
     * {@code keep} is filled. So the tunnel goes through there as a corridor, walled and roofed: no ledge or bridge
     * with a drop, lava or water beside it, no way in from a cave but its ends (no shortcut to the node, no chamber of
     * the node open to a cave); the cells under it are its floors ({@link #addFloor}). In the open space of the start
     * ({@link #startAir}) only the sides a player there would fall from ({@link #falls}) are walled, the rest stays
     * open. A wall may lie a block out of the region.
     */
    private void addWalls(VoxelGrid grid, Set<Long> cells, Set<Long> keep, Set<Long> walls) {
        for (long cell : cells) {
            int cx = CellKey.x(cell);
            int cy = CellKey.y(cell);
            int cz = CellKey.z(cell);
            // Where a player in this column stands: on the floor under the lowest of its cells here.
            int feet = cy;
            while (cells.contains(CellKey.of(cx, feet - 1, cz))) {
                feet--;
            }
            for (int side = 0; side <= DX.length; side++) {
                int nx = side < DX.length ? cx + DX[side] : cx;
                int ny = side < DX.length ? cy : cy + 1;
                int nz = side < DX.length ? cz + DZ[side] : cz;
                long next = CellKey.of(nx, ny, nz);
                if (!grid.contains(nx, ny, nz) || !grid.open(nx, ny, nz) || cells.contains(next)
                        || keep.contains(next)) {
                    continue;
                }
                if (!startAir[grid.index(nx, ny, nz)] || side < DX.length && falls(grid, nx, feet, nz)) {
                    walls.add(next);
                }
            }
        }
    }

    /**
     * Whether a player standing at the height {@code y} who steps aside into the column {@code x, z} of {@code grid}
     * gets hurt (lava or fire there, magma under it) or falls more than a block (where the walk does not climb back
     * up). Water breaks a fall and is swum out of.
     */
    static boolean falls(VoxelGrid grid, int x, int y, int z) {
        int feet = grid.flags(x, y, z);
        int head = grid.flags(x, y + 1, z);
        if ((feet & (VoxelGrid.OPEN | VoxelGrid.HAZARD)) == (VoxelGrid.OPEN | VoxelGrid.HAZARD)) {
            return true;
        }
        if ((feet & VoxelGrid.OPEN) == 0 || (head & VoxelGrid.OPEN) == 0) {
            // No way in: a step up at most.
            return false;
        }
        if ((head & VoxelGrid.HAZARD) != 0) {
            return true;
        }
        int ground = grid.flags(x, y - 1, z);
        if ((ground & VoxelGrid.OPEN) == 0) {
            return (ground & VoxelGrid.HAZARD) != 0;
        }
        if ((ground & VoxelGrid.LIQUID) != 0) {
            return (ground & VoxelGrid.HAZARD) != 0;
        }
        return !grid.standable(x, y - 1, z);
    }

    /** The cell under {@code cell} is filled if it is not kept, lies in the region and is open, hurts or is too tall. */
    private void addFloor(VoxelGrid grid, Set<Long> keep, long cell, Set<Long> floors) {
        int fx = CellKey.x(cell);
        int fy = CellKey.y(cell) - 1;
        int fz = CellKey.z(cell);
        long below = CellKey.of(fx, fy, fz);
        if (!keep.contains(below) && grid.contains(fx, fy, fz) && region.allows(fx, fy, fz)
                && grid.flags(fx, fy, fz) != 0) {
            floors.add(below);
        }
    }

    /**
     * Whether the ground over the chamber (and the node) is whole in {@code grid}: in every column of it,
     * {@value #COVER} solid cells over the top of the chamber and of the tunnel ({@code cells}) there. Those cells go
     * into {@code cover}.
     */
    private static boolean cover(VoxelGrid grid, Set<Long> chamber, Set<Long> cells, Set<Long> cover) {
        Map<Long, Integer> tops = new HashMap<>();
        for (long cell : chamber) {
            tops.merge(CellKey.of(CellKey.x(cell), 0, CellKey.z(cell)), CellKey.y(cell), Math::max);
        }
        boolean whole = true;
        for (Map.Entry<Long, Integer> column : tops.entrySet()) {
            int cx = CellKey.x(column.getKey());
            int cz = CellKey.z(column.getKey());
            int over = column.getValue() + 1;
            while (cells.contains(CellKey.of(cx, over, cz))) {
                over++;
            }
            for (int h = 0; h < COVER; h++) {
                cover.add(CellKey.of(cx, over + h, cz));
                whole &= !grid.open(cx, over + h, cz);
            }
        }
        return whole;
    }

    /**
     * How far {@code node} lies (blocks, in a straight line) from the nearest cell of the walk around the start
     * ({@value #NEAR_STEPS} steps on the grid as it was: where the player gets without digging).
     */
    private double clearance(long node) {
        int nx = CellKey.x(node);
        int ny = CellKey.y(node);
        int nz = CellKey.z(node);
        double nearest = Double.MAX_VALUE;
        for (int i = 0; i < near.reached(); i++) {
            long cell = key(base, near.order()[i]);
            nearest = Math.min(nearest, square(CellKey.x(cell) - nx) + square(CellKey.y(cell) - ny)
                    + square(CellKey.z(cell) - nz));
        }
        return Math.sqrt(nearest);
    }

    /**
     * Whether the walk cell {@code cell} near the start may begin a dead end: {@code near}..{@code far} blocks from the
     * player horizontally, {@code apart} radians or more round from {@code direction}, allowed, and {@code clear} blocks
     * from every way.
     */
    private boolean away(int cell, int near, int far, double direction, double apart, int clear) {
        long at = key(base, cell);
        int cx = CellKey.x(at);
        int cy = CellKey.y(at);
        int cz = CellKey.z(at);
        double across = Math.hypot(cx - x, cz - z);
        if (across < near || across > far || !allowed(cell)
                || Math.abs(Math.IEEEremainder(Math.atan2(cz - z, cx - x) - direction, 2 * Math.PI)) < apart) {
            return false;
        }
        for (int dy = -1; dy <= 3; dy++) {
            for (int dz = -clear; dz <= clear; dz++) {
                for (int dx = -clear; dx <= clear; dx++) {
                    if (taken.contains(CellKey.of(cx + dx, cy + dy, cz + dz))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** One of the cells of the walk around the start that {@code test} takes, each as likely; -1 if none. */
    private int pick(Random r, IntPredicate test) {
        int found = 0;
        int chosen = -1;
        for (int i = 0; i < near.reached(); i++) {
            int cell = near.order()[i];
            if (test.test(cell)) {
                // Reservoir sampling: every candidate equally likely.
                found++;
                if (r.nextInt(found) == 0) {
                    chosen = cell;
                }
            }
        }
        return chosen;
    }

    /**
     * Whether a way may start at the walk cell {@code cell}: the region allows it and the one above it (the player's
     * feet and head), and both are dry (a tunnel from the bed of a lake would be sealed off from it).
     */
    private boolean allowed(int cell) {
        long key = key(base, cell);
        int cx = CellKey.x(key);
        int cy = CellKey.y(key);
        int cz = CellKey.z(key);
        return region.allows(cx, cy, cz) && region.allows(cx, cy + 1, cz) && base.dry(cx, cy, cz)
                && base.dry(cx, cy + 1, cz);
    }

    private static long key(VoxelGrid grid, int cell) {
        int sizeX = grid.maxX() - grid.minX() + 1;
        int sizeZ = grid.maxZ() - grid.minZ() + 1;
        return CellKey.of(grid.minX() + cell % sizeX, grid.minY() + cell / sizeX / sizeZ,
                grid.minZ() + cell / sizeX % sizeZ);
    }

    private static double square(double v) {
        return v * v;
    }
}
