package tremor.awakening;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class CraterShapeTest {
    private static final long[] SEEDS = {0, 1, 42, -7, 123456789L, Long.MIN_VALUE};
    /** Radius and depth: the default, and the corners and odd mixes of the config's ranges. */
    private static final int[][] SIZES = {{12, 20}, {1, 1}, {1, 40}, {3, 6}, {6, 10}, {8, 12}, {12, 8}, {10, 5},
            {16, 20}, {24, 10}, {24, 20}, {24, 40}, {4, 20}, {12, 40}};

    @Test
    void theMiddleGoesTheFullDepth() {
        for (long seed : SEEDS) {
            for (int[] size : SIZES) {
                assertEquals(size[1], new CraterShape(size[0], size[1], seed).depthAt(0, 0));
            }
        }
    }

    @Test
    void neverDeeperThanTheDepthNorBeyondTheReach() {
        for (long seed : SEEDS) {
            for (int[] size : SIZES) {
                CraterShape shape = new CraterShape(size[0], size[1], seed);
                int reach = shape.reach();
                assertEquals(CraterShape.reach(size[0]), reach);
                for (int dz = -reach - 3; dz <= reach + 3; dz++) {
                    for (int dx = -reach - 3; dx <= reach + 3; dx++) {
                        int depth = shape.depthAt(dx, dz);
                        assertTrue(depth >= 0 && depth <= size[1], dx + " " + dz + ": " + depth);
                        if (Math.abs(dx) > reach || Math.abs(dz) > reach || dx * dx + dz * dz
                                >= Math.pow(size[0] * (1 + CraterShape.RIM_ROUGHNESS), 2)) {
                            assertEquals(0, depth, "outside the rim at " + dx + " " + dz);
                        }
                    }
                }
            }
        }
    }

    @Test
    void theRimIsADrop() {
        // Every column inside the rim goes at least the rim's drop deep (all of a shallower crater).
        for (long seed : SEEDS) {
            for (int[] size : SIZES) {
                CraterShape shape = new CraterShape(size[0], size[1], seed);
                for (CraterShape.Column column : shape.columns()) {
                    assertTrue(column.depth() >= Math.min(size[1], CraterShape.RIM_DROP), column.toString());
                    assertTrue(column.depth() - column.rubble() >= Math.min(size[1], CraterShape.RIM_DROP),
                            "the rubble fills it up: " + column);
                }
            }
        }
    }

    @Test
    void noSlopeOfSingleStepsLeadsOut() {
        // From the middle of the bottom (on its rubble), walking and jumping a block up at most, nobody gets out.
        for (int[] size : SIZES) {
            if (size[1] < 2) {
                continue;
            }
            for (long seed = 0; seed < 150; seed++) {
                CraterShape shape = new CraterShape(size[0], size[1], seed * 7919 + 13);
                assertTrue(highestReached(shape) < 0, size[0] + "/" + size[1] + " seed " + seed + " can be walked out");
            }
        }
    }

    @Test
    void theWallsOfTheDefaultCraterAreSteep() {
        // Not only the rim: the upper part of the wall, several blocks of it, cannot be climbed either.
        for (long seed = 0; seed < 150; seed++) {
            CraterShape shape = new CraterShape(12, 20, seed);
            assertTrue(highestReached(shape) <= -5, "seed " + seed + " climbs up to " + highestReached(shape));
        }
    }

    @Test
    void itIsAFunnel() {
        // Deepest in the middle, shallower towards the rim (on average over each ring).
        for (long seed : SEEDS) {
            CraterShape shape = new CraterShape(12, 20, seed);
            double previous = Double.MAX_VALUE;
            for (int ring = 0; ring <= 14; ring += 2) {
                double sum = 0;
                int count = 0;
                for (int dz = -ring; dz <= ring; dz++) {
                    for (int dx = -ring; dx <= ring; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) == ring) {
                            sum += shape.depthAt(dx, dz);
                            count++;
                        }
                    }
                }
                double mean = sum / count;
                assertTrue(mean < previous || ring == 0 || mean == 0, seed + ": ring " + ring + " " + mean + " >= "
                        + previous);
                previous = mean;
            }
        }
    }

    @Test
    void theRubbleCoversTheBottom() {
        for (long seed : SEEDS) {
            CraterShape shape = new CraterShape(12, 20, seed);
            assertTrue(shape.rubbleAt(0, 0) >= 1, "the middle is bare");
            int one = 0;
            int two = 0;
            for (CraterShape.Column column : shape.columns()) {
                assertTrue(column.rubble() >= 0 && column.rubble() <= 2, column.toString());
                assertEquals(shape.rubbleAt(column.dx(), column.dz()), column.rubble());
                // None up the wall: only on the bottom and the foot of it.
                if (column.depth() < 20 * (1 - CraterShape.BOWL) * (1 - CraterShape.DEPTH_ROUGHNESS) - 4) {
                    assertEquals(0, column.rubble(), "up the wall: " + column);
                }
                one += column.rubble() == 1 ? 1 : 0;
                two += column.rubble() == 2 ? 1 : 0;
            }
            assertTrue(one > 0 && two > 0, "not uneven: " + one + " one layer, " + two + " two");
            assertEquals(0, shape.rubbleAt(shape.reach() + 1, 0));
        }
    }

    @Test
    void theSeedPicksTheRoughness() {
        CraterShape a = new CraterShape(12, 20, 1);
        CraterShape again = new CraterShape(12, 20, 1);
        assertEquals(a.columns(), again.columns());
        Set<List<CraterShape.Column>> shapes = new HashSet<>();
        for (long seed : SEEDS) {
            shapes.add(new CraterShape(12, 20, seed).columns());
        }
        assertTrue(shapes.size() > 1, "every seed gave the same crater");
    }

    @Test
    void notAStampedDisc() {
        // The rim is torn: the columns differ from those of a plain disc of the radius.
        CraterShape shape = new CraterShape(12, 20, 42);
        Set<String> disc = new HashSet<>();
        Set<String> dug = new HashSet<>();
        for (int dz = -16; dz <= 16; dz++) {
            for (int dx = -16; dx <= 16; dx++) {
                if (dx * dx + dz * dz < 144) {
                    disc.add(dx + " " + dz);
                }
                if (shape.depthAt(dx, dz) > 0) {
                    dug.add(dx + " " + dz);
                }
            }
        }
        assertNotEquals(disc, dug);
    }

    @Test
    void theRimBulgesAndDents() {
        // Seen from above it is no circle: by direction the rim of the default crater lies blocks nearer or farther.
        double spans = 0;
        for (long seed = 0; seed < 150; seed++) {
            CraterShape shape = new CraterShape(12, 20, seed * 104729 + 3);
            double nearest = Double.MAX_VALUE;
            double farthest = 0;
            for (int direction = 0; direction < 64; direction++) {
                double angle = 2 * Math.PI * direction / 64;
                double rim = 0;
                for (double distance = 0; distance <= shape.reach() + 1; distance += 0.25) {
                    if (shape.depthAt((int) Math.round(distance * Math.cos(angle)),
                            (int) Math.round(distance * Math.sin(angle))) > 0) {
                        rim = distance;
                    }
                }
                nearest = Math.min(nearest, rim);
                farthest = Math.max(farthest, rim);
            }
            assertTrue(farthest - nearest >= 2, "seed " + seed + ": the rim lies " + nearest + " to " + farthest);
            spans += farthest - nearest;
        }
        assertTrue(spans / 150 >= 3.5, "the rim lies only " + spans / 150 + " blocks nearer or farther on average");
    }

    @Test
    void theColumnsGoFromTheCentreOutwards() {
        CraterShape shape = new CraterShape(12, 20, 7);
        List<CraterShape.Column> columns = shape.columns();
        assertEquals(new CraterShape.Column(0, 0, 20, shape.rubbleAt(0, 0)), columns.get(0));
        int count = 0;
        for (int dz = -shape.reach(); dz <= shape.reach(); dz++) {
            for (int dx = -shape.reach(); dx <= shape.reach(); dx++) {
                if (shape.depthAt(dx, dz) > 0) {
                    count++;
                }
            }
        }
        assertEquals(count, columns.size());
        for (int i = 1; i < columns.size(); i++) {
            CraterShape.Column before = columns.get(i - 1), column = columns.get(i);
            assertTrue(before.dx() * before.dx() + before.dz() * before.dz()
                    <= column.dx() * column.dx() + column.dz() * column.dz(), "at " + i);
            assertEquals(shape.depthAt(column.dx(), column.dz()), column.depth());
        }
    }

    @Test
    void noEmptyCrater() {
        assertThrows(IllegalArgumentException.class, () -> new CraterShape(0, 20, 1));
        assertThrows(IllegalArgumentException.class, () -> new CraterShape(12, 0, 1));
    }

    /**
     * How high a player gets from the middle of the bottom (on its rubble), walking and jumping a block up at most
     * (4-connected: through the corner between two higher columns nobody squeezes): the highest feet reached, the
     * ground around the crater at 0; MAX_VALUE if out of the crater.
     */
    private static int highestReached(CraterShape shape) {
        return flood(feet(shape), shape.reach() + 2);
    }

    /** The height of the feet of a player standing on each column (the ground around is at 0). */
    private static int[][] feet(CraterShape shape) {
        int reach = shape.reach() + 2;
        int[][] feet = new int[2 * reach + 1][2 * reach + 1];
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                feet[dx + reach][dz + reach] = -shape.depthAt(dx, dz) + shape.rubbleAt(dx, dz);
            }
        }
        return feet;
    }

    private static int flood(int[][] feet, int middle) {
        int size = feet.length;
        boolean[][] seen = new boolean[size][size];
        ArrayDeque<int[]> open = new ArrayDeque<>();
        open.add(new int[]{middle, middle});
        seen[middle][middle] = true;
        int highest = Integer.MIN_VALUE;
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!open.isEmpty()) {
            int[] at = open.poll();
            int here = feet[at[0]][at[1]];
            if (here == 0) {
                return Integer.MAX_VALUE;
            }
            highest = Math.max(highest, here);
            for (int[] step : steps) {
                int x = at[0] + step[0], z = at[1] + step[1];
                if (x >= 0 && z >= 0 && x < size && z < size && !seen[x][z] && feet[x][z] - here <= 1) {
                    seen[x][z] = true;
                    open.add(new int[]{x, z});
                }
            }
        }
        return highest;
    }
}
