package tremor.awakening;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

class SinkholeShapeTest {
    private static final long[] SEEDS = {0, 1, 42, -7, 123456789L, Long.MIN_VALUE};

    @Test
    void theCentreGoesTheFullDepth() {
        for (long seed : SEEDS) {
            assertEquals(6, new SinkholeShape(4, 6, seed).depthAt(0, 0));
            assertEquals(1, new SinkholeShape(1, 1, seed).depthAt(0, 0));
        }
    }

    @Test
    void neverDeeperThanTheDepthNorBeyondTheReach() {
        for (long seed : SEEDS) {
            for (int radius = 1; radius <= 8; radius++) {
                SinkholeShape shape = new SinkholeShape(radius, 6, seed);
                int reach = shape.reach();
                for (int dz = -reach - 3; dz <= reach + 3; dz++) {
                    for (int dx = -reach - 3; dx <= reach + 3; dx++) {
                        int depth = shape.depthAt(dx, dz);
                        assertTrue(depth >= 0 && depth <= 6, dx + " " + dz + ": " + depth);
                        if (Math.abs(dx) > reach || Math.abs(dz) > reach
                                || dx * dx + dz * dz >= Math.pow(radius * (1 + SinkholeShape.RIM_ROUGHNESS), 2)) {
                            assertEquals(0, depth, "outside the rim at " + dx + " " + dz);
                        }
                    }
                }
            }
        }
    }

    @Test
    void theCoreIsDugAllRound() {
        // Within the nearest the rim can come, a column of the default size goes down at least a block.
        for (long seed : SEEDS) {
            SinkholeShape shape = new SinkholeShape(4, 6, seed);
            double core = 4 * (1 - SinkholeShape.RIM_ROUGHNESS) - 1;
            for (int dz = -4; dz <= 4; dz++) {
                for (int dx = -4; dx <= 4; dx++) {
                    if (dx * dx + dz * dz <= core * core) {
                        assertTrue(shape.depthAt(dx, dz) >= 1, seed + ": " + dx + " " + dz);
                    }
                }
            }
        }
    }

    @Test
    void itIsABowl() {
        // Deepest in the middle, shallower towards the rim (on average over each ring).
        for (long seed : SEEDS) {
            SinkholeShape shape = new SinkholeShape(4, 6, seed);
            double previous = Double.MAX_VALUE;
            for (int ring = 0; ring <= 3; ring++) {
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
                assertTrue(mean < previous || ring == 0, seed + ": ring " + ring + " " + mean + " >= " + previous);
                previous = mean;
            }
        }
    }

    @Test
    void theSeedPicksTheRoughness() {
        SinkholeShape a = new SinkholeShape(4, 6, 1);
        SinkholeShape again = new SinkholeShape(4, 6, 1);
        assertEquals(a.columns(), again.columns());
        Set<List<SinkholeShape.Column>> shapes = new HashSet<>();
        for (long seed : SEEDS) {
            shapes.add(new SinkholeShape(4, 6, seed).columns());
        }
        assertTrue(shapes.size() > 1, "every seed gave the same sinkhole");
    }

    @Test
    void notAStampedDisc() {
        // The rim is torn: some seed's columns differ from those of a plain disc of the radius.
        SinkholeShape shape = new SinkholeShape(4, 6, 42);
        Set<String> disc = new HashSet<>();
        Set<String> dug = new HashSet<>();
        for (int dz = -6; dz <= 6; dz++) {
            for (int dx = -6; dx <= 6; dx++) {
                if (dx * dx + dz * dz < 16) {
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
    void theColumnsGoFromTheCentreOutwards() {
        SinkholeShape shape = new SinkholeShape(4, 6, 7);
        List<SinkholeShape.Column> columns = shape.columns();
        assertEquals(new SinkholeShape.Column(0, 0, 6), columns.get(0));
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
            SinkholeShape.Column before = columns.get(i - 1), column = columns.get(i);
            assertTrue(before.dx() * before.dx() + before.dz() * before.dz()
                    <= column.dx() * column.dx() + column.dz() * column.dz(), "at " + i);
            assertEquals(shape.depthAt(column.dx(), column.dz()), column.depth());
        }
    }

    @Test
    void noEmptySinkhole() {
        assertThrows(IllegalArgumentException.class, () -> new SinkholeShape(0, 6, 1));
        assertThrows(IllegalArgumentException.class, () -> new SinkholeShape(4, 0, 1));
    }
}
