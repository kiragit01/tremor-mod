package tremor.core.surface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tremor.core.math.Vec3;
import tremor.core.testing.ArrayVoxelGrid;

class SurfaceNormalsTest {
    private static final double EPS = 1e-12;

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(0, expected.distance(actual), EPS, () -> "expected " + expected + " but was " + actual);
    }

    private static double angleDeg(Vec3 a, Vec3 b) {
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, a.normalize().dot(b.normalize())))));
    }

    @Test
    void flatFloor() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                assertTrue(Surface.isSurface(g, x, 0, z));
                assertVec(Vec3.UNIT_Y, SurfaceNormals.rawNormal(g, x, 0, z));
                assertVec(Vec3.UNIT_Y, SurfaceNormals.smoothNormal(g, x, 0, z));

                // Directly below the top layer: solid, but no open face neighbour and no open neighbour at all.
                assertFalse(Surface.isSurface(g, x, -1, z));
                assertSame(Vec3.ZERO, SurfaceNormals.rawNormal(g, x, -1, z));
                assertSame(Vec3.ZERO, SurfaceNormals.smoothNormal(g, x, -1, z));
                assertSame(Vec3.ZERO, SurfaceNormals.smoothNormal(g, x, -8, z));

                // Air is never surface and has no normal.
                assertFalse(Surface.isSurface(g, x, 1, z));
                assertSame(Vec3.ZERO, SurfaceNormals.rawNormal(g, x, 1, z));
                assertSame(Vec3.ZERO, SurfaceNormals.smoothNormal(g, x, 1, z));
            }
        }
    }

    @Test
    void floorBeyondTheGridBoxIsStillFlat() {
        // EXTEND makes the scenes infinite: no edge artefacts at the box boundary.
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        int edge = ArrayVoxelGrid.SCENE_HALF_WIDTH;
        assertVec(Vec3.UNIT_Y, SurfaceNormals.smoothNormal(g, edge, 0, edge));
        assertVec(Vec3.UNIT_Y, SurfaceNormals.smoothNormal(g, edge + 5, 0, -edge - 5));
    }

    @Test
    void ceiling() {
        ArrayVoxelGrid g = ArrayVoxelGrid.cave(0, 5);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                assertTrue(Surface.isSurface(g, x, 5, z));
                assertVec(Vec3.UNIT_Y.negate(), SurfaceNormals.rawNormal(g, x, 5, z));
                assertVec(Vec3.UNIT_Y.negate(), SurfaceNormals.smoothNormal(g, x, 5, z));
                assertVec(Vec3.UNIT_Y, SurfaceNormals.smoothNormal(g, x, 0, z));
                assertFalse(Surface.isSurface(g, x, 6, z));
            }
        }
    }

    @Test
    void wallFacingPlusX() {
        ArrayVoxelGrid g = ArrayVoxelGrid.wallFacingPlusX(0);
        for (int y = -2; y <= 2; y++) {
            for (int z = -2; z <= 2; z++) {
                assertTrue(Surface.isSurface(g, 0, y, z));
                assertVec(Vec3.UNIT_X, SurfaceNormals.rawNormal(g, 0, y, z));
                assertVec(Vec3.UNIT_X, SurfaceNormals.smoothNormal(g, 0, y, z));
                assertFalse(Surface.isSurface(g, -1, y, z));
                assertSame(Vec3.ZERO, SurfaceNormals.smoothNormal(g, -1, y, z));
            }
        }
    }

    @Test
    void concaveFloorWallEdge() {
        ArrayVoxelGrid g = ArrayVoxelGrid.floorWallCorner(0, 0);
        // The voxel in the inner corner itself is buried (both its +x and +y faces touch solid).
        assertFalse(Surface.isSurface(g, 0, 0, 0));

        Vec3 floorSide = SurfaceNormals.smoothNormal(g, 1, 0, 0);
        Vec3 wallSide = SurfaceNormals.smoothNormal(g, 0, 1, 0);
        for (Vec3 n : new Vec3[]{floorSide, wallSide}) {
            assertTrue(n.x() > 0.2 && n.y() > 0.2, "tilted between up and +x: " + n);
            assertEquals(0, n.z(), EPS);
            assertEquals(1, n.length(), EPS);
            double angle = angleDeg(n, new Vec3(1, 1, 0));
            assertTrue(angle < 20, "within 20° of the 45° diagonal, was " + angle);
        }
        // Mirror images of each other across the diagonal, so together they point exactly at 45°.
        assertVec(new Vec3(wallSide.y(), wallSide.x(), 0), floorSide);
        assertVec(new Vec3(1, 1, 0).normalize(), floorSide.add(wallSide).normalize());
        // Floor side leans up, wall side leans out.
        assertTrue(floorSide.y() > floorSide.x());
        assertTrue(wallSide.x() > wallSide.y());

        // Away from the edge the plain floor and wall normals come back.
        assertVec(Vec3.UNIT_Y, SurfaceNormals.smoothNormal(g, 4, 0, 0));
        assertVec(Vec3.UNIT_X, SurfaceNormals.smoothNormal(g, 0, 4, 0));
    }

    @Test
    void convexStepEdge() {
        ArrayVoxelGrid g = ArrayVoxelGrid.step(0, 0);
        Vec3 raw = SurfaceNormals.rawNormal(g, 0, 0, 0);
        Vec3 smooth = SurfaceNormals.smoothNormal(g, 0, 0, 0);
        for (Vec3 n : new Vec3[]{raw, smooth}) {
            assertTrue(n.x() > 0.1 && n.y() > 0.1, "diagonal between up and +x: " + n);
            assertEquals(0, n.z(), EPS);
            assertEquals(1, n.length(), EPS);
        }
        // Raw normal of the edge: 9 open voxels above, 3 more beside it (+x), so it leans toward up.
        Vec3 expectedRaw = new Vec3(1 + Math.sqrt(2), 1 + 2 * Math.sqrt(2) + 4 / Math.sqrt(3), 0).normalize();
        assertVec(expectedRaw, raw);
        // Far from the edge both floors are flat again.
        assertVec(Vec3.UNIT_Y, SurfaceNormals.smoothNormal(g, -4, 0, 0));
        assertVec(Vec3.UNIT_Y, SurfaceNormals.smoothNormal(g, 4, -1, 0));
    }

    @Test
    void concaveRoomCorner() {
        ArrayVoxelGrid g = ArrayVoxelGrid.room(0, 1, 0, 9, 6, 9);
        // Floor voxel in the corner formed by the floor (y = 0) and the walls at x = -1 and z = -1.
        Vec3 n = SurfaceNormals.smoothNormal(g, 0, 0, 0);
        assertTrue(n.x() > 0.1 && n.y() > 0.1 && n.z() > 0.1, "points into the room: " + n);
        assertEquals(n.x(), n.z(), EPS, "symmetric in x and z");
        // Upper corner of the opposite side: ceiling (y = 7) with walls x = 10 and z = 10.
        Vec3 m = SurfaceNormals.smoothNormal(g, 9, 7, 9);
        assertTrue(m.x() < -0.1 && m.y() < -0.1 && m.z() < -0.1, "points into the room: " + m);
        // Middle of the floor and of a wall.
        assertVec(Vec3.UNIT_Y, SurfaceNormals.smoothNormal(g, 5, 0, 5));
        assertVec(Vec3.UNIT_Z.negate(), SurfaceNormals.smoothNormal(g, 5, 3, 10));
    }

    @Test
    void thinWallCancels() {
        ArrayVoxelGrid g = ArrayVoxelGrid.thinWallBetweenCaves(0, 8, 0, 1);
        // A 1-thick wall is open on both sides: it is surface, but its normal cancels.
        assertTrue(Surface.isSurface(g, 0, 4, 0));
        assertSame(Vec3.ZERO, SurfaceNormals.rawNormal(g, 0, 4, 0));
        assertSame(Vec3.ZERO, SurfaceNormals.smoothNormal(g, 0, 4, 0));
        // A 2-thick wall has two proper faces.
        ArrayVoxelGrid thick = ArrayVoxelGrid.thinWallBetweenCaves(0, 8, 0, 2);
        assertVec(Vec3.UNIT_X.negate(), SurfaceNormals.smoothNormal(thick, 0, 4, 0));
        assertVec(Vec3.UNIT_X, SurfaceNormals.smoothNormal(thick, 1, 4, 0));
    }

    @Test
    void smoothFallsBackToRawWhenNeighboursCancel() {
        // A lone pillar voxel standing on nothing: open all around, raw normal cancels, nothing else to average.
        ArrayVoxelGrid g = new ArrayVoxelGrid(-3, -3, -3, 3, 3, 3, false);
        g.set(0, 0, 0, true);
        assertTrue(Surface.isSurface(g, 0, 0, 0));
        assertSame(Vec3.ZERO, SurfaceNormals.rawNormal(g, 0, 0, 0));
        assertSame(Vec3.ZERO, SurfaceNormals.smoothNormal(g, 0, 0, 0));

        // A single voxel sticking out of a floor: the tilts of the floor voxels around it cancel, so it points up.
        ArrayVoxelGrid bump = ArrayVoxelGrid.flatFloor(0);
        bump.set(0, 1, 0, true);
        Vec3 top = SurfaceNormals.smoothNormal(bump, 0, 1, 0);
        assertVec(Vec3.UNIT_Y, top);
    }

    @Test
    void groundOverACaveWithUnevenRoofStaysUp() {
        // Flat ground (top y=0) over a cave whose roof is 2 thick for x<0 and 3 thick for x>=0. The cave-side layer
        // of the 2-thick roof faces down; averaging it in used to tip the ground normals almost horizontal.
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        g.fill(-12, -6, -12, -1, -2, 12, false);
        g.fill(0, -6, -12, 12, -3, 12, false);
        for (int x = -4; x <= 3; x++) {
            Vec3 n = SurfaceNormals.smoothNormal(g, x, 0, 0);
            assertTrue(angleDeg(Vec3.UNIT_Y, n) < 1e-6, "ground at x=" + x + " tipped to " + n);
        }
        // The roof underneath still faces down.
        assertVec(Vec3.UNIT_Y.negate(), SurfaceNormals.smoothNormal(g, -4, -1, 0));
    }

    @Test
    void smoothedNormalNeverTurnsAwayFromTheVoxel() {
        // On rough terrain with thin overhangs the smoothed normal stays within 60 degrees of the voxel's own one.
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        java.util.Random random = new java.util.Random(5);
        for (int i = 0; i < 400; i++) {
            g.set(random.nextInt(17) - 8, random.nextInt(9) - 6, random.nextInt(17) - 8, random.nextBoolean());
        }
        for (int x = -7; x <= 7; x++) {
            for (int y = -5; y <= 1; y++) {
                for (int z = -7; z <= 7; z++) {
                    Vec3 raw = SurfaceNormals.rawNormal(g, x, y, z);
                    Vec3 smooth = SurfaceNormals.smoothNormal(g, x, y, z);
                    if (!raw.isNearZero() && Surface.isSurface(g, x, y, z)) {
                        assertTrue(smooth.dot(raw) >= SurfaceNormals.MAX_SMOOTHING_COS - 1e-12,
                                "voxel " + x + "," + y + "," + z + ": raw " + raw + " smooth " + smooth);
                    }
                }
            }
        }
    }

    @Test
    void unknownVoxelsAreNeverSurface() {
        // A view that knows nothing below y=0: the solid it reports there must not become a floor of the void.
        ArrayVoxelGrid open = new ArrayVoxelGrid(-4, -4, -4, 4, 4, 4, false);
        tremor.core.VoxelView view = new tremor.core.VoxelView() {
            @Override
            public boolean isSolid(int x, int y, int z) {
                return y < 0 || open.isSolid(x, y, z);
            }

            @Override
            public float conductivity(int x, int y, int z) {
                return 1;
            }

            @Override
            public boolean isProtected(int x, int y, int z) {
                return false;
            }

            @Override
            public boolean isKnown(int x, int y, int z) {
                return y >= 0;
            }
        };
        assertFalse(Surface.isSurface(view, 0, -1, 0));
        assertSame(Vec3.ZERO, SurfaceNormals.smoothNormal(view, 0, -1, 0));
        assertFalse(new NormalField(view).isSurface(0, -1, 0));
    }

    /** Flat ground with a ledge: solid for {@code y <= 0}, and up to {@code height} for {@code x <= -1}; its side faces +x. */
    private static ArrayVoxelGrid ledge(int height) {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        g.fill(g.minX, 1, g.minZ, -1, height, g.maxZ, true);
        return g;
    }

    @Test
    void wideNormalOfFlatGroundIsUp() {
        assertVec(Vec3.UNIT_Y,
                SurfaceNormals.wideNormal(ArrayVoxelGrid.flatFloor(0), new Vec3(0.5, 1, 0.5), Vec3.UNIT_Y, 6));
    }

    /**
     * A bump six blocks high crossing a ledge four high ({@link SurfaceNormals#wideNormal}): over the floor to its
     * foot, up its side, where the normal of the voxel under it lies flat, and on along its top. The ground at its
     * scale stays more up than not all the way, and turns by little from one place to the next.
     */
    @Test
    void aLedgeLowerThanTheRadiusHardlyTurnsTheWideNormal() {
        ArrayVoxelGrid g = ledge(4);
        Vec3 before = null;
        for (double s = 0; s <= 20; s += 0.25) {
            Vec3 c = s < 8 ? new Vec3(8 - s, 1, 0.5) : s <= 12 ? new Vec3(0, 1 + (s - 8), 0.5) : new Vec3(12 - s, 5, 0.5);
            Vec3 own = s > 8 && s < 12 ? Vec3.UNIT_X : Vec3.UNIT_Y;
            Vec3 wide = SurfaceNormals.wideNormal(g, c, own, 6);
            assertTrue(angleDeg(wide, Vec3.UNIT_Y) < 30, "at " + c + " it leans " + angleDeg(wide, Vec3.UNIT_Y));
            assertEquals(0, wide.z(), 1e-9, "the ledge runs along z");
            if (before != null) {
                assertTrue(angleDeg(wide, before) < 6, "at " + c + " it turns by " + angleDeg(wide, before));
            }
            before = wide;
        }
    }

    /** A wall higher than the radius turns it: all the way out of reach of the floor, part of the way at its foot. */
    @Test
    void aWallHigherThanTheRadiusTurnsTheWideNormal() {
        ArrayVoxelGrid g = ArrayVoxelGrid.floorWallCorner(0, -1);
        assertVec(Vec3.UNIT_X, SurfaceNormals.wideNormal(g, new Vec3(0, 8.5, 0.5), Vec3.UNIT_X, 6));
        double foot = angleDeg(SurfaceNormals.wideNormal(g, new Vec3(0, 1.5, 0.5), Vec3.UNIT_X, 6), Vec3.UNIT_Y);
        assertTrue(foot > 30 && foot < 70, "at its foot it leans " + foot);
        double up = angleDeg(SurfaceNormals.wideNormal(g, new Vec3(0, 4.5, 0.5), Vec3.UNIT_X, 6), Vec3.UNIT_Y);
        assertTrue(up > 75, "four blocks up it leans " + up);
    }

    /** Faces turned away from the given normal are not its ground: the roof of a cave, the far wall of a passage. */
    @Test
    void wideNormalLeavesOutTheFacesTurnedAway() {
        ArrayVoxelGrid cave = ArrayVoxelGrid.cave(0, 4);
        assertVec(Vec3.UNIT_Y, SurfaceNormals.wideNormal(cave, new Vec3(0.5, 1, 0.5), Vec3.UNIT_Y, 6));
        assertVec(new Vec3(0, -1, 0), SurfaceNormals.wideNormal(cave, new Vec3(0.5, 4, 0.5), new Vec3(0, -1, 0), 6));
        // a passage three blocks wide: its two walls cancel, its floor is left
        ArrayVoxelGrid passage = ArrayVoxelGrid.room(-1, 1, -19, 1, 3, 19);
        assertTrue(angleDeg(SurfaceNormals.wideNormal(passage, new Vec3(0.5, 1, 0.5), Vec3.UNIT_Y, 6), Vec3.UNIT_Y) < 1e-3);
        // nothing solid around: the given normal itself
        ArrayVoxelGrid air = new ArrayVoxelGrid(-8, -8, -8, 8, 8, 8, false);
        assertSame(Vec3.UNIT_Z, SurfaceNormals.wideNormal(air, new Vec3(0.5, 0.5, 0.5), Vec3.UNIT_Z, 6));
    }

    /** A slope of one in one is its own way at any scale: stairs rising toward -x face up and +x alike. */
    @Test
    void wideNormalOfAStairSlopeIsTheSlope() {
        ArrayVoxelGrid g = new ArrayVoxelGrid(-20, -16, -20, 20, 16, 20, ArrayVoxelGrid.Outside.EXTEND);
        for (int x = -15; x <= 15; x++) {
            g.fill(x, g.minY, g.minZ, x, -x, g.maxZ, true);
        }
        g.fill(g.minX, g.minY, g.minZ, -16, 15, g.maxZ, true);
        Vec3 slope = new Vec3(1, 1, 0).normalize();
        for (double x = 0; x < 1; x += 0.25) {
            Vec3 wide = SurfaceNormals.wideNormal(g, new Vec3(x, 1, 0.5), slope, 6);
            assertTrue(angleDeg(wide, slope) < 3, "at x = " + x + " it is off the slope by " + angleDeg(wide, slope));
        }
    }
}
