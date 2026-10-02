package tremor.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;
import tremor.core.behavior.SurfacePicker;
import tremor.core.graph.SurfaceGraph;
import tremor.core.math.Vec3;
import tremor.core.testing.ArrayVoxelGrid;

class HeadingTest {
    private static final Vec3 FACING_NORTH = new Vec3(0, 0, -1);

    private static void assertDirection(double x, double z, Heading heading) {
        assertEquals(x, heading.direction().x(), 1e-12);
        assertEquals(0, heading.direction().y());
        assertEquals(z, heading.direction().z(), 1e-12);
    }

    @Test
    void theMotionOfTheLastMovePacketComesFirst() {
        Heading h = Heading.of(new Vec3(0.2, -0.08, 0), new Vec3(0, 0, 30), FACING_NORTH);
        assertEquals(Heading.Source.MOVEMENT, h.source());
        assertDirection(1, 0, h);
        // Sneaking still counts.
        h = Heading.of(new Vec3(0.04, 0, 0.05), null, FACING_NORTH);
        assertEquals(Heading.Source.MOVEMENT, h.source());
        assertDirection(0.04 / Math.hypot(0.04, 0.05), 0.05 / Math.hypot(0.04, 0.05), h);
    }

    @Test
    void standingOrMovingVerticallyFallsBackToTheDisplacementSinceTheLastCheck() {
        // Falling or climbing a ladder: no horizontal motion.
        Heading h = Heading.of(new Vec3(0, -0.5, 0), new Vec3(-30, 4, 40), FACING_NORTH);
        assertEquals(Heading.Source.DISPLACEMENT, h.source());
        assertDirection(-0.6, 0.8, h);
        h = Heading.of(Vec3.ZERO, new Vec3(Heading.MIN_DISPLACEMENT, 0, 0), FACING_NORTH);
        assertEquals(Heading.Source.DISPLACEMENT, h.source());
        h = Heading.of(new Vec3(Heading.MIN_MOVEMENT / 2, 0, 0), new Vec3(3, 0, 0), FACING_NORTH);
        assertEquals(Heading.Source.DISPLACEMENT, h.source());
    }

    @Test
    void thenTheFacingDirection() {
        Heading h = Heading.of(Vec3.ZERO, new Vec3(1, 50, 1), new Vec3(0.3, 0.9, 0.4));
        assertEquals(Heading.Source.FACING, h.source());
        assertDirection(0.6, 0.8, h);
        h = Heading.of(null, null, FACING_NORTH);
        assertEquals(Heading.Source.FACING, h.source());
        assertDirection(0, -1, h);
    }

    @Test
    void aPointIsPassedIfAheadWithinTheHalfWidthAndTheGrade() {
        Heading east = Heading.of(new Vec3(0.2, 0, 0), null, FACING_NORTH);
        Vec3 feet = new Vec3(10, 64, 10);
        // Voxel centres: the top of the voxel at y + 0.5.
        assertTrue(east.passes(feet, new Vec3(70.5, 63.5, 10.5), 10));
        assertTrue(east.passes(feet, new Vec3(70, 63.5, 20), 10));
        assertFalse(east.passes(feet, new Vec3(70, 63.5, 20.01), 10));
        assertFalse(east.passes(feet, new Vec3(70, 63.5, -0.01), 10));
        // 40 ahead the way may be 10 + 40 above or below the feet.
        assertTrue(east.passes(feet, new Vec3(50, 113.5, 10), 10));
        assertFalse(east.passes(feet, new Vec3(50, 114.5, 10), 10));
        assertTrue(east.passes(feet, new Vec3(50, 13.5, 10), 10));
        assertFalse(east.passes(feet, new Vec3(50, 12.5, 10), 10));
        // Behind or beside the player is not ahead.
        assertFalse(east.passes(feet, new Vec3(-50, 63.5, 10), 10));
        assertFalse(east.passes(feet, new Vec3(10, 63.5, 15), 10));
        assertFalse(Heading.of(null, null, null).passes(feet, new Vec3(70, 63.5, 10), 10));
    }

    @Test
    void passesIsTheNearRouteTestOfSpawnPoint() {
        // Ground up to y = 0 around the player and in every other run of 7 columns along x, up to y = -40 between:
        // a node down there is near the way only far enough ahead.
        ArrayVoxelGrid grid = new ArrayVoxelGrid(-90, -50, -4, 90, 5, 4, ArrayVoxelGrid.Outside.EXTEND);
        for (int x = grid.minX; x <= grid.maxX; x++) {
            int top = Math.abs(x) < 10 || Math.floorMod(Math.floorDiv(x, 7), 2) == 0 ? 0 : -40;
            grid.fill(x, grid.minY, grid.minZ, x, top, grid.maxZ, true);
        }
        SurfaceGraph graph = new SurfaceGraph(grid, 4);
        Vec3 feet = new Vec3(0.5, 1, 0.5);
        // The eye in the rock sees nothing, so every candidate is judged by the way alone.
        Vec3 buried = new Vec3(0.5, -9, 0.5);
        int[] outcomes = new int[2];
        for (Vec3 movement : new Vec3[]{Vec3.UNIT_X, new Vec3(-1, 0, 0), new Vec3(3, 0, 4), new Vec3(-2, 0, -1),
                new Vec3(0, 0, 1)}) {
            Heading heading = Heading.of(movement, null, null);
            for (int seed = 0; seed < 20; seed++) {
                List<Vec3> candidates = new ArrayList<>();
                SurfacePicker.SpawnPick pick = SurfacePicker.spawnPoint(graph, feet, buried, heading.direction(), 40,
                        80, 4, 48, p -> {
                            candidates.add(p);
                            return true;
                        }, new SplittableRandom(seed), 32);
                // The search stops at the first candidate near the way; none before it was.
                for (int i = 0; i < candidates.size(); i++) {
                    boolean picked = pick != null && i == candidates.size() - 1;
                    boolean passes = heading.passes(feet, candidates.get(i), 4);
                    assertEquals(picked && pick.nearRoute(), passes,
                            movement + ", seed " + seed + ": " + candidates.get(i));
                    outcomes[passes ? 1 : 0]++;
                }
            }
        }
        assertTrue(outcomes[0] > 0 && outcomes[1] > 0, "passed " + outcomes[1] + ", not " + outcomes[0]);
    }

    @Test
    void nothingKnownIsNoHeading() {
        Heading h = Heading.of(null, null, null);
        assertEquals(Heading.Source.NONE, h.source());
        assertNull(h.direction());
        h = Heading.of(Vec3.ZERO, Vec3.ZERO, new Vec3(0, -1, 0));
        assertEquals(Heading.Source.NONE, h.source());
        assertEquals("none", h.source().id());
    }
}
