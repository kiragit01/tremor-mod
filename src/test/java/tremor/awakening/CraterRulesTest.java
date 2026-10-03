package tremor.awakening;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tremor.awakening.CraterRules.Cell.AIR;
import static tremor.awakening.CraterRules.Cell.FALLING;
import static tremor.awakening.CraterRules.Cell.FLUID;
import static tremor.awakening.CraterRules.Cell.KEEP;
import static tremor.awakening.CraterRules.Cell.LOOSE;
import static tremor.awakening.CraterRules.Cell.PLANT;
import static tremor.awakening.CraterRules.Cell.SOLID;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

class CraterRulesTest {
    /** Height the player stood at (the block the feet were in): the crater is dug from here down. */
    private static final int TOP = 64;

    /**
     * Flat ground: stone below {@link #TOP}, air from it up; single cells set on top of that. Records the works, and
     * keeps a clock of the time they take: a unit for each block looked at, ten for each block changed.
     */
    private static final class World implements CraterRules.Cells, CraterRules.Works {
        final Map<String, CraterRules.Cell> cells = new HashMap<>();
        final List<String> carved = new ArrayList<>();
        final List<String> sealed = new ArrayList<>();
        final List<CraterRules.Column> bottomed = new ArrayList<>();
        long clock;

        World set(int x, int y, int z, CraterRules.Cell cell) {
            cells.put(x + " " + y + " " + z, cell);
            return this;
        }

        World fill(int x0, int y0, int z0, int x1, int y1, int z1, CraterRules.Cell cell) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        set(x, y, z, cell);
                    }
                }
            }
            return this;
        }

        @Override
        public CraterRules.Cell at(int x, int y, int z) {
            clock++;
            return cells.getOrDefault(x + " " + y + " " + z, y >= TOP ? AIR : SOLID);
        }

        @Override
        public void carve(int x, int y, int z) {
            clock += 10;
            carved.add(x + " " + y + " " + z);
            set(x, y, z, AIR);
        }

        @Override
        public void seal(int x, int y, int z, int fromX, int fromY, int fromZ) {
            // What it closes off: a block beside it, air by now.
            assertEquals(1, Math.abs(x - fromX) + Math.abs(y - fromY) + Math.abs(z - fromZ), "not beside " + x + " " + y
                    + " " + z);
            assertEquals(AIR, at(fromX, fromY, fromZ), "the crater beside " + x + " " + y + " " + z);
            clock += 10;
            sealed.add(x + " " + y + " " + z);
            set(x, y, z, SOLID);
        }

        @Override
        public void bottomed(CraterRules.Column column) {
            clock += 10;
            bottomed.add(column);
        }

        /** A dig of the columns {x, z, depth}, each from its top ({@link CraterRules#carveTop}, 8 blocks up). */
        CraterRules.Dig dig(int[]... columns) {
            List<CraterRules.Column> list = new ArrayList<>();
            for (int[] column : columns) {
                list.add(new CraterRules.Column(column[0], column[1], CraterRules.carveTop(this, column[0], column[1],
                        TOP, 8), TOP - column[2]));
            }
            return new CraterRules.Dig(TOP, list);
        }

        /** Runs the dig to the end, checking {@code after} after every step. */
        void run(CraterRules.Dig dig, Consumer<World> after) {
            int steps = 0;
            while (dig.step(this, this)) {
                after.accept(this);
                assertTrue(++steps < 100000, "the dig does not end");
            }
            assertTrue(dig.done());
        }

        void run(CraterRules.Dig dig) {
            run(dig, world -> {
            });
        }
    }

    /** {x, z, depth} of a column (the order of {@link World#dig}: x, then z, then the depth). */
    private static int[] column(int x, int z, int depth) {
        return new int[]{x, z, depth};
    }

    @Test
    void flatGroundIsCarvedLayerByLayerFromTheMiddleOut() {
        World world = new World();
        CraterRules.Dig dig = world.dig(column(0, 0, 3), column(1, 0, 2));
        world.run(dig);
        // The feet block is air already; then the ground, the highest layer first, the middle first in each.
        assertEquals(List.of("0 63 0", "1 63 0", "0 62 0", "1 62 0", "0 61 0"), world.carved);
        assertEquals(List.of(), world.sealed);
        assertEquals(2, world.bottomed.size());
        assertTrue(dig.column(0, 0).through());
        assertEquals(SOLID, world.at(0, 60, 0));
    }

    @Test
    void theColumnStartsAtItsTop() {
        // A hill, a tree and a tall plant over the swallow point's height go from their tops down.
        World world = new World()
                .fill(0, 64, 0, 0, 66, 0, SOLID)
                .fill(1, 64, 0, 1, 69, 0, SOLID).fill(1, 70, 0, 1, 71, 0, LOOSE)
                .fill(2, 64, 0, 2, 65, 0, PLANT);
        assertEquals(66, CraterRules.carveTop(world, 0, 0, TOP, 8));
        assertEquals(71, CraterRules.carveTop(world, 1, 0, TOP, 8));
        assertEquals(65, CraterRules.carveTop(world, 2, 0, TOP, 8));
        // A tree crown over open ground: from the top of the crown, through the air under it.
        world.fill(3, 68, 0, 3, 70, 0, SOLID);
        assertEquals(70, CraterRules.carveTop(world, 3, 0, TOP, 8));
        // Open ground: from the swallow point's height; under a roof thicker than the reach: from there, under it.
        assertEquals(TOP, CraterRules.carveTop(world, 4, 0, TOP, 8));
        world.fill(5, 64, 0, 5, 80, 0, SOLID);
        assertEquals(TOP, CraterRules.carveTop(world, 5, 0, TOP, 8));
        CraterRules.Dig dig = world.dig(column(0, 0, 1), column(1, 0, 1), column(2, 0, 1), column(3, 0, 1),
                column(5, 0, 1));
        world.run(dig);
        for (int x = 0; x <= 3; x++) {
            for (int y = 63; y <= 71; y++) {
                assertEquals(AIR, world.at(x, y, 0), x + " " + y);
            }
        }
        // Under the thick roof: the column only, the roof stays.
        assertEquals(AIR, world.at(5, 64, 0));
        assertEquals(SOLID, world.at(5, 65, 0));
        assertTrue(world.carved.indexOf("1 71 0") < world.carved.indexOf("0 66 0"), "not from the top down");
    }

    @Test
    void sandOrWaterOverAColumnCarvedUnderARoofIsFilledIn() {
        World world = new World().fill(0, 64, 0, 0, 80, 0, SOLID).set(0, 65, 0, FALLING)
                .fill(1, 64, 0, 1, 80, 0, SOLID).set(1, 65, 0, FLUID);
        world.run(world.dig(column(0, 0, 2), column(1, 0, 2)));
        assertTrue(world.sealed.contains("0 65 0"));
        assertTrue(world.sealed.contains("1 65 0"));
        assertEquals(AIR, world.at(0, 64, 0));
    }

    @Test
    void aKeptBlockStandsOnAPillar() {
        // A chest where the player stood: its column stays, the ground beside it goes.
        World world = new World().set(0, 64, 0, KEEP);
        CraterRules.Dig dig = world.dig(column(0, 0, 3), column(1, 0, 3), column(0, 1, 3));
        world.run(dig);
        assertTrue(dig.column(0, 0).ended());
        for (int y = 61; y <= 63; y++) {
            assertEquals(SOLID, world.at(0, y, 0), "under the chest at " + y);
            assertEquals(AIR, world.at(1, y, 0), "beside it at " + y);
        }
        assertEquals(KEEP, world.at(0, 64, 0));
        // A block touching a kept one (a chest buried in the ground) ends its column: nothing below goes.
        World buried = new World().set(1, 61, 0, KEEP);
        CraterRules.Dig deep = buried.dig(column(0, 0, 4), column(1, 0, 4));
        buried.run(deep);
        assertEquals(SOLID, buried.at(1, 62, 0));
        assertEquals(SOLID, buried.at(0, 61, 0), "beside the chest");
        assertEquals(AIR, buried.at(0, 62, 0));
        assertTrue(deep.column(1, 0).ended() && deep.column(0, 0).ended());
    }

    @Test
    void aPlantNextToAKeptBlockGoesAllTheSame() {
        World world = new World().set(1, 64, 0, KEEP).set(0, 64, 0, PLANT);
        world.run(world.dig(column(0, 0, 1)));
        assertTrue(world.carved.contains("0 64 0"));
    }

    @Test
    void noFluidGetsIn() {
        // Water beside the crater, under its floor and in a pocket the crater cuts through: all filled in, and no
        // fluid ever touches a carved block, after any step.
        World world = new World().set(2, 62, 0, FLUID).set(0, 60, 0, FLUID).fill(-1, 61, 1, 0, 62, 1, FLUID);
        CraterRules.Dig dig = world.dig(column(0, 0, 3), column(1, 0, 2));
        world.run(dig, CraterRulesTest::noFluidBesideTheCrater);
        assertTrue(world.sealed.contains("2 62 0"));
        assertTrue(world.sealed.contains("0 60 0"));
        assertTrue(world.sealed.contains("0 61 1") && world.sealed.contains("0 62 1"));
        assertEquals(FLUID, world.at(-1, 62, 1), "only the water touching the crater is filled in");
    }

    @Test
    void aPondInTheCraterIsCarvedAndPluggedWhereItGoesOn() {
        // A pond lower than the swallow point, partly in the crater: no column stops at the water (no wall of ground is
        // left standing in the crater); the water in it goes, and the water beyond is plugged at its own blocks.
        World world = new World().fill(1, 62, 0, 3, 63, 0, FLUID).fill(1, 62, 1, 3, 63, 1, FLUID);
        CraterRules.Dig dig = world.dig(column(0, 0, 3), column(1, 0, 3), column(2, 0, 3));
        world.run(dig, CraterRulesTest::noFluidBesideTheCrater);
        for (int x = 0; x <= 2; x++) {
            assertTrue(dig.column(x, 0).through(), "column " + x);
            for (int y = 61; y <= 63; y++) {
                assertEquals(AIR, world.at(x, y, 0), x + " " + y);
            }
        }
        assertEquals(3, world.bottomed.size());
        // The plugs: the water touching the crater beside it (and nothing more of it).
        for (String plug : List.of("3 63 0", "3 62 0", "1 63 1", "1 62 1", "2 63 1", "2 62 1")) {
            assertTrue(world.sealed.contains(plug), plug);
            assertEquals(SOLID, world.at(parse(plug)[0], parse(plug)[1], parse(plug)[2]), plug);
        }
        assertEquals(FLUID, world.at(3, 63, 1), "only the water touching the crater is plugged");
    }

    @Test
    void theWaterThePlayerStoodInIsCarvedToo() {
        // The player waded in a stream (its water in the feet block, x 0..1, and on along z beyond the crater): the
        // columns in it are carved down like the others (no wall of ground under the stream across the crater), and
        // the stream is plugged where it goes on, at every step.
        World world = new World().fill(0, 64, -3, 1, 64, 3, FLUID);
        CraterRules.Dig dig = world.dig(column(0, 0, 3), column(1, 0, 3), column(-1, 0, 3), column(0, 1, 3),
                column(0, -1, 3), column(1, 1, 2), column(1, -1, 2));
        world.run(dig, CraterRulesTest::closed);
        for (CraterRules.Column column : dig.columns()) {
            assertTrue(column.through(), "column " + column.x + " " + column.z);
            for (int y = column.bottom; y <= 64; y++) {
                assertEquals(AIR, world.at(column.x, y, column.z), column.x + " " + y + " " + column.z);
            }
        }
        assertEquals(dig.columns().size(), world.bottomed.size());
        for (String plug : List.of("0 64 2", "0 64 -2", "1 64 2", "1 64 -2")) {
            assertTrue(world.sealed.contains(plug), plug);
        }
        assertEquals(FLUID, world.at(0, 64, 3), "only the water touching the crater is plugged");
        // Water higher than the swallow point in a column (a pool on a hill cut away): carved, the water under it
        // filled in first, so no water lies open in the crater even for a moment.
        World hill = new World().fill(0, 64, 0, 0, 66, 0, SOLID).fill(0, 67, 0, 0, 68, 0, FLUID);
        CraterRules.Dig pool = hill.dig(column(0, 0, 2));
        hill.run(pool, CraterRulesTest::closed);
        assertTrue(pool.column(0, 0).through());
        assertEquals(AIR, hill.at(0, 67, 0));
        assertTrue(hill.sealed.contains("0 67 0"));
    }

    @Test
    void theBottomIsSolid() {
        // A cave under the floor, sand over a cave, and a slab: filled in; the rubble can lie on it.
        // (The floor is the block under the lowest one carved: 59 under a column 4 deep, 60 under one 3 deep.)
        World world = new World().set(0, 59, 0, AIR).set(1, 60, 0, FALLING).set(1, 59, 0, AIR)
                .set(2, 59, 0, LOOSE);
        CraterRules.Dig dig = world.dig(column(0, 0, 4), column(1, 0, 3), column(2, 0, 4));
        world.run(dig, CraterRulesTest::closed);
        assertEquals(List.of("1 60 0", "0 59 0", "2 59 0"), world.sealed);
        assertEquals(3, world.bottomed.size());
        for (CraterRules.Column column : world.bottomed) {
            assertTrue(CraterRules.holds(world, column.x, column.bottom - 1, column.z), "under " + column.x);
        }
    }

    @Test
    void aCaveIsNeverOpened() {
        // A cave crossing the crater: inside it is part of the crater, outside it is walled off; at every step the
        // crater is closed (no carved block has cave air beside or under it).
        World world = new World().fill(-3, 60, -1, 3, 61, 1, AIR);
        CraterRules.Dig dig = world.dig(column(0, 0, 6), column(1, 0, 6), column(-1, 0, 6));
        world.run(dig, CraterRulesTest::closed);
        for (int y = 58; y <= 63; y++) {
            assertEquals(AIR, world.at(0, y, 0), "the middle at " + y);
        }
        assertEquals(SOLID, world.at(2, 60, 0));
        assertEquals(SOLID, world.at(0, 60, 1));
        assertEquals(AIR, world.at(3, 60, 0), "the cave beyond the wall stays");
        assertEquals(3, world.bottomed.size());
    }

    @Test
    void openGroundBesideIsNotFilledIn() {
        // A slope going down beside the crater, lower than the swallow point: open from the top, it stays open.
        World world = new World().fill(1, 62, 0, 1, 63, 0, AIR).fill(2, 61, 0, 2, 63, 0, AIR);
        world.run(world.dig(column(0, 0, 4)));
        assertEquals(List.of(), world.sealed);
        assertEquals(AIR, world.at(1, 62, 0));
    }

    @Test
    void groundLowerThanTheBottomIsLeftOpen() {
        // A column whose ground lies below the crater's bottom (a steep slope): nothing to carve, nothing filled in,
        // and no rubble goes on it.
        World world = new World().fill(0, 58, 0, 0, 63, 0, AIR);
        CraterRules.Dig dig = world.dig(column(0, 0, 3));
        world.run(dig);
        assertEquals(List.of(), world.carved);
        assertEquals(List.of(), world.sealed);
        assertEquals(List.of(), world.bottomed);
    }

    @Test
    void anUnfinishedDigIsClosedToo() {
        // Stopped after any step: no fluid and no cave beside or under a carved block.
        for (int stop = 0; stop < 40; stop++) {
            World world = new World().fill(-3, 60, -1, 3, 61, 1, AIR).set(2, 62, 0, FLUID).set(0, 57, 0, FLUID);
            CraterRules.Dig dig = world.dig(column(0, 0, 6), column(1, 0, 4), column(-1, 0, 6));
            for (int step = 0; step < stop && dig.step(world, world); step++) {
                closed(world);
            }
            closed(world);
        }
    }

    @Test
    void thePlanIsTheShapesColumnsAPlaceAtATime() {
        // The columns of the shape under the swallow point (10, TOP, -3), in its order, each from the top the world
        // gives it (a hill over part of it), planned one place of the shape's square per step.
        for (long seed = 0; seed < 6; seed++) {
            CraterShape shape = new CraterShape(6, 5, seed);
            World world = new World().fill(8, 64, -5, 12, 66, -1, SOLID);
            CraterRules.Plan plan = new CraterRules.Plan(shape, 10, TOP, -3);
            int[] tops = {0};
            int steps = 0;
            while (plan.step((x, z) -> {
                tops[0]++;
                return CraterRules.carveTop(world, x, z, TOP, 8);
            })) {
                steps++;
                assertTrue(tops[0] <= steps, "more than a column in a step");
                assertEquals(steps, plan.looked());
            }
            assertTrue(plan.done());
            int side = 2 * shape.reach() + 1;
            assertEquals(side * side, steps);
            assertEquals(side * side, plan.places());
            List<CraterShape.Column> expected = shape.columns();
            List<CraterRules.Column> columns = plan.dig().columns();
            assertEquals(expected.size(), columns.size());
            assertEquals(expected.size(), tops[0]);
            for (int i = 0; i < expected.size(); i++) {
                CraterShape.Column want = expected.get(i);
                CraterRules.Column column = columns.get(i);
                assertEquals(10 + want.dx(), column.x, "column " + i);
                assertEquals(-3 + want.dz(), column.z, "column " + i);
                assertEquals(TOP - want.depth(), column.bottom, "column " + i);
                assertEquals(CraterRules.carveTop(world, column.x, column.z, TOP, 8), column.top, "column " + i);
            }
        }
    }

    @Test
    void aDiggingStoppedByTheClockGoesOnWhereItStopped() {
        // The same crater (a hill over it, a cave across it, water and a chest beside it) planned and dug at once, and
        // over ticks of a small budget: the same blocks carved and filled in, in the same order; every tick but the
        // last takes its budget and goes over it by less than its last step; the crater is closed after every tick.
        CraterShape shape = new CraterShape(4, 6, 3);
        World once = site();
        CraterRules.Digging whole = new CraterRules.Digging(new CraterRules.Plan(shape, 0, TOP, 0));
        long[] last = {0};
        long[] largest = {0};
        assertFalse(whole.run(once, tops(once), once, () -> {
            largest[0] = Math.max(largest[0], once.clock - last[0]);
            last[0] = once.clock;
            return true;
        }));
        assertTrue(once.carved.size() > 100 && !once.sealed.isEmpty() && !once.bottomed.isEmpty(), "a crater");

        World ticked = site();
        CraterRules.Digging digging = new CraterRules.Digging(new CraterRules.Plan(shape, 0, TOP, 0));
        long budget = 4 * largest[0];
        int ticks = 0;
        boolean going = true;
        while (going) {
            long start = ticked.clock;
            going = digging.run(ticked, tops(ticked), ticked, () -> ticked.clock - start < budget);
            long took = ticked.clock - start;
            assertTrue(took < budget + largest[0], "tick " + ticks + " took " + took + " of " + budget);
            if (going) {
                assertTrue(took >= budget, "tick " + ticks + " stopped early: " + took + " of " + budget);
            }
            closed(ticked);
            assertTrue(++ticks < 100000, "the digging does not end");
        }
        assertTrue(ticks > 10, "only " + ticks + " ticks");
        assertEquals(once.carved, ticked.carved);
        assertEquals(once.sealed, ticked.sealed);
        assertEquals(places(once.bottomed), places(ticked.bottomed));
        assertTrue(digging.dig().done());
    }

    @Test
    void aTickWithoutTimeTakesNoStep() {
        World world = site();
        CraterRules.Digging digging = new CraterRules.Digging(new CraterRules.Plan(new CraterShape(4, 6, 3), 0, TOP,
                0));
        assertTrue(digging.run(world, tops(world), world, () -> false));
        assertEquals(0, world.clock);
        assertEquals(0, digging.plan().looked());
        assertNull(digging.dig());
        // One step a tick: the plan first, a place at a time, then the dig.
        int[] allowed = {0};
        int ticks = 0;
        while (digging.run(world, tops(world), world, () -> allowed[0]++ == 0)) {
            allowed[0] = 0;
            ticks++;
            assertTrue(digging.dig() != null || digging.plan().looked() == ticks, "tick " + ticks);
            closed(world);
        }
        assertEquals(digging.plan().places(), digging.plan().looked());
        assertTrue(digging.dig().done());
    }

    @Test
    void holdsAndOpenness() {
        World world = new World().set(0, 60, 0, FALLING).set(0, 59, 0, AIR).set(1, 60, 0, FALLING)
                .set(2, 60, 0, KEEP).set(3, 60, 0, PLANT);
        assertFalse(CraterRules.holds(world, 0, 60, 0), "sand over air");
        assertTrue(CraterRules.holds(world, 1, 60, 0), "sand over stone");
        assertTrue(CraterRules.holds(world, 2, 60, 0));
        assertFalse(CraterRules.holds(world, 3, 60, 0));
        assertTrue(CraterRules.openFromTop(world, 4, 64, 0, TOP));
        assertFalse(CraterRules.openFromTop(world, 0, 59, 0, TOP));
    }

    /**
     * Ground for a crater at (0, TOP, 0): a hill over the swallow point, a cave across, water in two pockets beside it,
     * a chest on the ground.
     */
    private static World site() {
        return new World().fill(-1, 64, -1, 1, 66, 1, SOLID)
                .fill(-6, 59, 1, 6, 60, 2, AIR)
                .set(3, 61, -2, FLUID).set(-2, 62, 3, FLUID)
                .set(2, 63, 0, KEEP);
    }

    /** The tops of the columns in {@code world}: from the swallow point's height, 8 blocks up at most. */
    private static CraterRules.Tops tops(World world) {
        return (x, z) -> CraterRules.carveTop(world, x, z, TOP, 8);
    }

    private static List<String> places(List<CraterRules.Column> columns) {
        List<String> places = new ArrayList<>();
        for (CraterRules.Column column : columns) {
            places.add(column.x + " " + column.z);
        }
        return places;
    }

    /** No fluid touches a carved block (one recorded as carved, and air now). */
    private static void noFluidBesideTheCrater(World world) {
        for (String at : world.carved) {
            int[] p = parse(at);
            if (world.at(p[0], p[1], p[2]) != AIR) {
                continue;
            }
            int[][] faces = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] f : faces) {
                assertTrue(world.at(p[0] + f[0], p[1] + f[1], p[2] + f[2]) != FLUID, "fluid next to " + at);
            }
        }
    }

    /**
     * The crater is closed: no fluid touches a carved block; under each lies a block that holds or another carved
     * block; beside each lies no air that is not open from the swallow point's height.
     */
    private static void closed(World world) {
        noFluidBesideTheCrater(world);
        for (String at : world.carved) {
            int[] p = parse(at);
            String below = p[0] + " " + (p[1] - 1) + " " + p[2];
            CraterRules.Cell under = world.at(p[0], p[1] - 1, p[2]);
            assertTrue(CraterRules.holds(world, p[0], p[1] - 1, p[2]) || world.carved.contains(below)
                    || under == AIR && CraterRules.openFromTop(world, p[0], p[1] - 1, p[2], TOP), "open under " + at);
            int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] s : sides) {
                int x = p[0] + s[0], z = p[2] + s[1];
                if (world.at(x, p[1], z) == AIR && p[1] < TOP && !world.carved.contains(x + " " + p[1] + " " + z)) {
                    assertTrue(CraterRules.openFromTop(world, x, p[1], z, TOP), "a cave beside " + at);
                }
            }
        }
    }

    private static int[] parse(String at) {
        String[] parts = at.split(" ");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
    }
}
