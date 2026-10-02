package tremor.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;

import org.junit.jupiter.api.Test;
import tremor.core.VoxelView;
import tremor.core.math.Vec3;
import tremor.core.testing.ArrayVoxelGrid;

class ContactZoneTest {
    /** Height of a standing player. */
    private static final double H = 1.8;
    /** Centre of the floor voxel (0, 0, 0) of {@link #floor()}: the skin is at y = 1. */
    private static final Vec3 CENTER = new Vec3(0.5, 0.5, 0.5);

    /** Solid for y <= 0. */
    private static ArrayVoxelGrid floor() {
        return ArrayVoxelGrid.flatFloor(0);
    }

    /** Nothing solid: only the geometry of the zone counts. */
    private static ArrayVoxelGrid open() {
        return new ArrayVoxelGrid(-12, -12, -12, 12, 12, 12, false);
    }

    private static boolean touches(VoxelView view, Vec3 center, Vec3 normal, double amplitude, Vec3 feet,
                                   double radius) {
        return ContactZone.touches(view, center, normal, amplitude, feet, H, radius);
    }

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(0, expected.distance(actual), 1e-12, () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void playerStandingOnTheBumpTouchesIt() {
        Vec3 feet = new Vec3(1.2, 1, 0.1);
        assertTrue(touches(floor(), CENTER, Vec3.UNIT_Y, 1.5, feet, 2));
        // Lifted by the bump, or jumping a little over it: the zone ends 1.5 above its top (along 3 from the centre).
        assertTrue(touches(floor(), CENTER, Vec3.UNIT_Y, 1.5, feet.add(0, 2.3, 0), 2));
        assertFalse(touches(floor(), CENTER, Vec3.UNIT_Y, 1.5, feet.add(0, 2.5, 0), 2));
        // A non-unit normal is normalized.
        assertTrue(touches(floor(), CENTER, new Vec3(0, 3, 0), 1.5, feet, 2));
    }

    @Test
    void boundsOfTheZone() {
        double a = 1.0, r = 2.0;
        // along: from the centre plane (the top of the body) to a + 1.5 (the bottom of the body, 0.1 inside it).
        assertTrue(touches(open(), CENTER, Vec3.UNIT_Y, a, CENTER.add(0, -1.65, 0), r));
        assertFalse(touches(open(), CENTER, Vec3.UNIT_Y, a, CENTER.add(0, -1.75, 0), r));
        assertTrue(touches(open(), CENTER, Vec3.UNIT_Y, a, CENTER.add(0, 2.35, 0), r));
        assertFalse(touches(open(), CENTER, Vec3.UNIT_Y, a, CENTER.add(0, 2.45, 0), r));
        // tangential: up to the radius inclusive, in any direction of the plane.
        assertTrue(touches(open(), CENTER, Vec3.UNIT_Y, a, CENTER.add(2, 0.5, 0), r));
        assertTrue(touches(open(), CENTER, Vec3.UNIT_Y, a, CENTER.add(0, 0.5, -2), r));
        assertTrue(touches(open(), CENTER, Vec3.UNIT_Y, a, CENTER.add(-1.1, 0.5, 1.6), r));
        assertFalse(touches(open(), CENTER, Vec3.UNIT_Y, a, CENTER.add(2.01, 0.5, 0), r));
        assertFalse(touches(open(), CENTER, Vec3.UNIT_Y, a, CENTER.add(-1.3, 0.5, 1.6), r));
    }

    @Test
    void aBumpOnAWallStrikesTheHeadOfAPlayerOnTheFloor() {
        // Wall facing +x (solid for x <= 0) on a floor (solid for y <= 0); the bump is on the third wall voxel.
        ArrayVoxelGrid g = ArrayVoxelGrid.floorWallCorner(0, 0);
        Vec3 wall = Vec3.voxelCenter(0, 3, 0);
        Vec3 feet = new Vec3(1.3, 1, 0.5);
        // The feet are 2.5 below the axis, the top of the body 0.8 below it.
        assertTrue(touches(g, wall, Vec3.UNIT_X, 1, feet, 1.6));
        // A short player (crawling, 0.6 high) does not reach it.
        assertFalse(ContactZone.touches(g, wall, Vec3.UNIT_X, 1, feet, 0.6, 1.6));
        // Out of the wall further than the zone reaches (2.5 from the voxel centre).
        assertFalse(touches(g, wall, Vec3.UNIT_X, 1, new Vec3(3.1, 1, 0.5), 1.6));
    }

    @Test
    void aBumpOnACeilingStrikesTheHeadOfAPlayerBelow() {
        // A cave four blocks high: floor solid for y <= 0, ceiling for y >= 5.
        Vec3 ceiling = Vec3.voxelCenter(0, 5, 0);
        Vec3 down = new Vec3(0, -1, 0);
        Vec3 feet = new Vec3(1, 1, 0.5);
        // The feet are 4.5 below the centre, the top of the body 2.8: within 1.5 + 1.5.
        assertTrue(touches(ArrayVoxelGrid.cave(0, 5), ceiling, down, 1.5, feet, 1.6));
        // Five blocks high, the top of the body is 3.8 below the centre: out of reach.
        assertFalse(touches(ArrayVoxelGrid.cave(0, 6), Vec3.voxelCenter(0, 6, 0), down, 1.5, feet, 1.6));
    }

    @Test
    void noStrikeThroughARoofOneBlockThick() {
        // A cave under a roof one block thick (y = 5), open above it.
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0).fill(-12, 5, -12, 12, 5, 12, true);
        Vec3 roof = Vec3.voxelCenter(0, 5, 0);
        Vec3 onTheRoof = new Vec3(0.8, 6, 0.5);
        Vec3 inTheCave = new Vec3(0.8, 3, 0.5);
        Vec3 down = new Vec3(0, -1, 0);
        // A bump hanging under the roof does not strike a player standing on it, nor one on the roof a player below.
        assertFalse(touches(g, roof, down, 1.5, onTheRoof, 1.6));
        assertFalse(touches(g, roof, Vec3.UNIT_Y, 1.5, inTheCave, 1.6));
        // On its own side it does.
        assertTrue(touches(g, roof, Vec3.UNIT_Y, 1.5, onTheRoof, 1.6));
        assertTrue(touches(g, roof, down, 1.5, inTheCave, 1.6));
    }

    @Test
    void noStrikeThroughAWallOneBlockThick() {
        // Caves on both sides of a wall at x = 0, one block thick.
        ArrayVoxelGrid g = ArrayVoxelGrid.thinWallBetweenCaves(0, 6, 0, 1);
        Vec3 wall = Vec3.voxelCenter(0, 2, 0);
        Vec3 behind = new Vec3(-0.3, 1, 0.5);
        assertFalse(touches(g, wall, Vec3.UNIT_X, 1.5, behind, 1.6));
        assertTrue(touches(g, wall, new Vec3(-1, 0, 0), 1.5, behind, 1.6));
        assertTrue(touches(g, wall, Vec3.UNIT_X, 1.5, new Vec3(1.3, 1, 0.5), 1.6));
    }

    @Test
    void noStrikeThroughRockBetweenTheBumpAndThePlayer() {
        Vec3 beyond = new Vec3(2.3, 1, 0.5);
        // A wall one block thick right next to the bump, between it and a player within a wide zone.
        ArrayVoxelGrid walled = floor().fill(1, 1, -12, 1, 3, 12, true);
        assertFalse(touches(walled, CENTER, Vec3.UNIT_Y, 1.5, beyond, 2.5));
        assertTrue(touches(floor(), CENTER, Vec3.UNIT_Y, 1.5, beyond, 2.5));
        // A slab one block above the floor, over the bump: a player standing on it is in the zone, but behind rock.
        Vec3 onTheSlab = new Vec3(0.5, 3, 0.5);
        ArrayVoxelGrid slab = floor().fill(-3, 2, -3, 3, 2, 3, true);
        assertFalse(touches(slab, CENTER, Vec3.UNIT_Y, 2.5, onTheSlab, 1.6));
        assertTrue(touches(floor(), CENTER, Vec3.UNIT_Y, 2.5, onTheSlab, 1.6));
    }

    @Test
    void thePlayerMayStandLowerThanTheBump() {
        // In a pit two blocks deep next to the bump: the head is beside the bump's voxel.
        ArrayVoxelGrid pit = floor().fill(1, -1, -12, 3, 0, 12, false);
        assertTrue(touches(pit, CENTER, Vec3.UNIT_Y, 1.5, new Vec3(1.5, -1, 0.5), 1.6));
        // Three blocks deep, the head is below the centre plane.
        ArrayVoxelGrid deep = floor().fill(1, -2, -12, 3, 0, 12, false);
        assertFalse(touches(deep, CENTER, Vec3.UNIT_Y, 1.5, new Vec3(1.5, -2, 0.5), 1.6));
    }

    @Test
    void aHiddenBumpTouchesNobody() {
        Vec3 feet = CENTER.add(0, 0.5, 0);
        assertFalse(touches(floor(), CENTER, Vec3.UNIT_Y, 0, feet, 2));
        assertFalse(touches(floor(), CENTER, Vec3.UNIT_Y, -1, feet, 2));
        assertFalse(touches(floor(), CENTER, Vec3.UNIT_Y, Double.NaN, feet, 2));
        assertFalse(touches(floor(), CENTER, Vec3.ZERO, 1, feet, 2));
    }

    @Test
    void nobodyBeyondTheReachTouches() {
        assertEquals(2 + 1.5 + 1.5 + H, ContactZone.reach(1.5, 2, H), 1e-12);
        SplittableRandom random = new SplittableRandom(7);
        ArrayVoxelGrid g = open();
        int touching = 0;
        for (int i = 0; i < 20000; i++) {
            Vec3 normal = new Vec3(random.nextDouble(-1, 1), random.nextDouble(-1, 1), random.nextDouble(-1, 1));
            double amplitude = random.nextDouble(0.1, 3);
            double radius = random.nextDouble(0.5, 3);
            double height = random.nextDouble(0.5, 2);
            Vec3 feet = CENTER.add(random.nextDouble(-6, 6), random.nextDouble(-6, 6), random.nextDouble(-6, 6));
            if (ContactZone.touches(g, CENTER, normal, amplitude, feet, height, radius)) {
                touching++;
                assertTrue(feet.distance(CENTER) <= ContactZone.reach(amplitude, radius, height));
            }
        }
        assertTrue(touching > 100, "too few touching samples: " + touching);
    }

    @Test
    void pushGoesAwayFromTheCentreAlongTheSkin() {
        assertVec(new Vec3(0.6, 0, 0.8), ContactZone.push(CENTER, Vec3.UNIT_Y, Vec3.UNIT_X,
                CENTER.add(0.3, 1.7, 0.4)));
        // On a wall facing +x the push lies in the wall plane.
        Vec3 wall = new Vec3(10.5, 70.5, 0.5);
        assertVec(new Vec3(0, -1, 0), ContactZone.push(wall, Vec3.UNIT_X, Vec3.UNIT_Z, wall.add(1.5, -2, 0)));
    }

    @Test
    void pushRightAboveTheCentreGoesForwardOrAnyPerpendicular() {
        Vec3 above = CENTER.add(0, 1.2, 0);
        assertVec(new Vec3(0, 0, -1), ContactZone.push(CENTER, Vec3.UNIT_Y, new Vec3(0, 0.5, -2), above));
        Vec3 fallback = ContactZone.push(CENTER, Vec3.UNIT_Y, Vec3.UNIT_Y, above);
        assertEquals(1, fallback.length(), 1e-12);
        assertEquals(0, fallback.dot(Vec3.UNIT_Y), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> ContactZone.push(CENTER, Vec3.ZERO, Vec3.UNIT_X, above));
    }
}
