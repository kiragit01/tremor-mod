package tremor.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;
import tremor.core.graph.SurfaceGraph;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.testing.ArrayVoxelGrid;

class SurfacePickerTest {
    /** Player standing on the floor y <= 0 at the origin column. */
    private static final Vec3 FEET = new Vec3(0.5, 1, 0.5);
    private static final Vec3 EYE = FEET.add(0, 1.62, 0);

    private static SurfaceGraph graph(ArrayVoxelGrid grid) {
        return new SurfaceGraph(grid, 4);
    }

    private static long p(int x, int y, int z) {
        return VoxelPos.pack(x, y, z);
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    /** Whether a spawn pick's voxel centre is {@code min..max} from the feet, give or take half a voxel diagonal. */
    private static boolean inSpawnBand(Vec3 position, double min, double max) {
        double d = position.distance(FEET);
        return d >= min - SurfacePicker.HALF_VOXEL_DIAGONAL && d <= max + SurfacePicker.HALF_VOXEL_DIAGONAL;
    }

    /** A generator whose {@code nextDouble()} returns {@code values} in turn (the pickers draw nothing else). */
    private static RandomGenerator scripted(double... values) {
        return new RandomGenerator() {
            private int next;

            @Override
            public long nextLong() {
                throw new UnsupportedOperationException();
            }

            @Override
            public double nextDouble() {
                return values[next++];
            }
        };
    }

    /** Flat floor with the player in a 1x2 shaft: walls up to y = 2 on the four sides block every view sideways. */
    private static ArrayVoxelGrid floorWithShaft() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        g.fill(-1, 1, 0, -1, 2, 0, true).fill(1, 1, 0, 1, 2, 0, true);
        g.fill(0, 1, -1, 0, 2, -1, true).fill(0, 1, 1, 0, 2, 1, true);
        return g;
    }

    @Test
    void nodeInColumnScansOutwardFromTheStartLevelAboveFirst() {
        SurfaceGraph floor = graph(ArrayVoxelGrid.flatFloor(0));
        assertEquals(p(3, 0, -2), SurfacePicker.nodeInColumn(floor, 3, -2, 0, 0));
        assertEquals(p(3, 0, -2), SurfacePicker.nodeInColumn(floor, 3, -2, 5, 5));
        assertEquals(p(3, 0, -2), SurfacePicker.nodeInColumn(floor, 3, -2, -4, 4));
        assertEquals(SurfaceGraph.NO_NODE, SurfacePicker.nodeInColumn(floor, 3, -2, 5, 4));
        assertEquals(SurfaceGraph.NO_NODE, SurfacePicker.nodeInColumn(floor, 3, -2, 0, -1));

        // Cave: floor surface y = 0, ceiling surface y = 6.
        SurfaceGraph cave = graph(ArrayVoxelGrid.cave(0, 6));
        assertEquals(p(1, 6, 1), SurfacePicker.nodeInColumn(cave, 1, 1, 3, 10), "tie: above first");
        assertEquals(p(1, 0, 1), SurfacePicker.nodeInColumn(cave, 1, 1, 2, 10));
        assertEquals(p(1, 6, 1), SurfacePicker.nodeInColumn(cave, 1, 1, 4, 10));
    }

    @Test
    void viewPointIsTheOpenFaceNeighbourAlongTheNormal() {
        assertEquals(new Vec3(2.5, 1.5, 3.5), SurfacePicker.viewPoint(graph(ArrayVoxelGrid.flatFloor(0)), p(2, 0, 3)));
        assertEquals(new Vec3(1.5, 5.5, 0.5),
                SurfacePicker.viewPoint(graph(ArrayVoxelGrid.wallFacingPlusX(0)), p(0, 5, 0)));
        assertEquals(new Vec3(1.5, 5.5, 0.5), SurfacePicker.viewPoint(graph(ArrayVoxelGrid.cave(0, 6)), p(1, 6, 0)));
        // Floor/wall corner (solid for y <= 0 or x <= 0): the wall voxel next to the edge leans up, but its only
        // open face is +x.
        SurfaceGraph corner = graph(ArrayVoxelGrid.floorWallCorner(0, 0));
        assertTrue(corner.normal(0, 1, 0).y() > 0.1, "diagonal normal");
        assertEquals(new Vec3(1.5, 1.5, 0.5), SurfacePicker.viewPoint(corner, p(0, 1, 0)));
        // Convex edge of a step (solid y <= 0 for x <= 0, y <= -1 beyond): the face most aligned with the normal,
        // +x (before +y) on a tie.
        SurfaceGraph step = graph(ArrayVoxelGrid.step(0, 0));
        Vec3 n = step.normal(0, 0, 0);
        Vec3 expected = n.y() > n.x() ? new Vec3(0.5, 1.5, 0.5) : new Vec3(1.5, 0.5, 0.5);
        assertEquals(expected, SurfacePicker.viewPoint(step, p(0, 0, 0)));
        // Deep rock: no open face.
        assertNull(SurfacePicker.viewPoint(graph(ArrayVoxelGrid.flatFloor(0)), p(0, -5, 0)));
    }

    @Test
    void viewPointOfAZeroNormalTakesTheFirstOpenFace() {
        // A 1-thick wall between two caves: its normal cancels; open faces -x and +x, -x comes first.
        SurfaceGraph g = graph(ArrayVoxelGrid.thinWallBetweenCaves(0, 10, 3, 1));
        assertEquals(Vec3.ZERO, g.normal(3, 5, 0));
        assertEquals(new Vec3(2.5, 5.5, 0.5), SurfacePicker.viewPoint(g, p(3, 5, 0)));
    }

    @Test
    void visibleOnAFloorAndBehindAWall() {
        SurfaceGraph floor = graph(ArrayVoxelGrid.flatFloor(0));
        assertTrue(SurfacePicker.visible(floor, EYE, p(15, 0, -7)));
        // Cave y 1..7 with a wall at x = 5 (floor to ceiling).
        SurfaceGraph cave = graph(ArrayVoxelGrid.thinWallBetweenCaves(0, 8, 5, 1));
        assertTrue(SurfacePicker.visible(cave, EYE, p(3, 0, 2)));
        assertTrue(SurfacePicker.visible(cave, EYE, p(5, 3, 0)), "the wall itself");
        assertFalse(SurfacePicker.visible(cave, EYE, p(10, 0, 0)));
        assertFalse(SurfacePicker.visible(cave, EYE, p(0, -6, 0)), "no open face");
    }

    @Test
    void wanderTargetOnAFlatFloor() {
        SurfaceGraph floor = graph(ArrayVoxelGrid.flatFloor(0));
        Vec3 from = new Vec3(0.5, 0.5, 0.5);
        for (int seed = 0; seed < 20; seed++) {
            Vec3 target = SurfacePicker.wanderTarget(floor, from, 16, 32, 8, null, 0, KeepAway.NONE,
                    new SplittableRandom(seed), 8);
            assertNotNull(target);
            assertEquals(0.5, target.y());
            double d = horizontalDistance(target, from);
            assertTrue(d >= 16 - 1 && d <= 32 + 1, "distance " + d);
            assertEquals(target, SurfacePicker.wanderTarget(floor, from, 16, 32, 8, null, 0, KeepAway.NONE,
                    new SplittableRandom(seed), 8), "deterministic");
        }
    }

    @Test
    void wanderTargetPrefersPointsTheViewerSees() {
        // Flat floor with a tall wall at x = 3..4; the entity east of it, the viewer west of it.
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        g.fill(3, 1, g.minZ, 4, g.maxY, g.maxZ, true);
        SurfaceGraph graph = graph(g);
        Vec3 from = new Vec3(8.5, 0.5, 0.5);
        Vec3 eye = new Vec3(-9.5, 2.62, 0.5);
        int hidden = 0;
        for (int seed = 0; seed < 30; seed++) {
            Vec3 target = SurfacePicker.wanderTarget(graph, from, 16, 32, 8, eye, 0, KeepAway.NONE,
                    new SplittableRandom(seed), 16);
            assertNotNull(target);
            assertTrue(SurfacePicker.visible(graph, eye, VoxelPos.containing(target)), "seed " + seed);
            // A single attempt takes whatever it finds.
            Vec3 single = SurfacePicker.wanderTarget(graph, from, 16, 32, 8, eye, 0, KeepAway.NONE,
                    new SplittableRandom(seed), 1);
            if (single != null && !SurfacePicker.visible(graph, eye, VoxelPos.containing(single))) {
                hidden++;
            }
        }
        assertTrue(hidden > 0, "some single candidates are hidden");
    }

    @Test
    void wanderTargetSeenByNoOneIsTheFirstAcceptableCandidate() {
        SurfaceGraph floor = graph(ArrayVoxelGrid.flatFloor(0));
        Vec3 from = new Vec3(0.5, 0.5, 0.5);
        // Three candidates 16 blocks from the start, toward +x, -x and +z (an angle and a radius draw each).
        double[] draws = {0, 0, 0.5, 0, 0.25, 0};
        Vec3 east = new Vec3(16.5, 0.5, 0.5), west = new Vec3(-15.5, 0.5, 0.5);
        // A viewer in the rock sees none of them; south is 8.3 from its eye, east 25.4, west 27.8. It is also the
        // player kept away from. The candidate nearest to it is no fallback: that would lead toward a player.
        Vec3 buried = new Vec3(2.5, -6.5, 20.5);
        assertEquals(east, wander(floor, from, buried, away(buried, 0), draws, 3), "not south, the nearest");
        assertEquals(east, wander(floor, from, buried, away(buried, 10), draws, 3));
        assertEquals(west, wander(floor, from, buried, away(buried, 26), draws, 3), "the first at least 26 away");
        assertNull(wander(floor, from, buried, away(buried, 30), draws, 3));
        assertNull(wander(floor, from, buried, away(buried, 26), draws, 1), "only the attempts made count");
        // Without a viewer the first acceptable candidate wins too.
        assertEquals(east, wander(floor, from, null, KeepAway.NONE, draws, 3));
        assertEquals(west, wander(floor, from, null, away(new Vec3(24.5, 1, 4.5), 24), draws, 3));
    }

    /**
     * A player in a closed hut (stone walls and roof, 3 x 3 x 3 of air inside) at the origin column: the player sees
     * only the 9 floor columns inside, every other column is out of sight.
     */
    private static SurfaceGraph hut() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        g.fill(-2, 1, -2, 2, 4, 2, true).fill(-1, 1, -1, 1, 3, 1, false);
        return graph(g);
    }

    /** Whether a point is on the floor inside {@link #hut()}. */
    private static boolean inHut(Vec3 p) {
        return horizontalDistance(p, FEET) < 1.5 && p.y() == 0.5;
    }

    @Test
    void aPlayerHiddenFromEveryCandidateDrawsNoLegTowardItself() {
        // The review's case (SPEC 5.6): a player keeps quiet in a closed hut while the entity, 30 blocks away and
        // calmed down to HUNTING, wanders on with nothing to go for. The player sees no candidate but those on the
        // hut's own floor, nearer than minWanderDistance (24, what the game passes as seenFrom), and none is chosen
        // for being near the player: the legs are those of an entity nobody watches. HUNTING wandering is random by
        // default (the brain passes 0, KeepAway.NONE); with behavior.wanderKeepAway every leg also ends 24 away.
        SurfaceGraph hut = hut();
        Vec3 from = new Vec3(0.5, 0.5, 30.5);
        List<KeepAway.Player> players = List.of(new KeepAway.Player(FEET, true, false));
        KeepAway random = KeepAway.of(Stage.HUNTING, 0, players, 10, 6);
        KeepAway kept = KeepAway.of(Stage.HUNTING, 24, players, 10, 6);
        double sum = 0;
        int near = 0;
        for (int seed = 0; seed < 200; seed++) {
            for (KeepAway keepAway : List.of(random, kept)) {
                Vec3 target = SurfacePicker.wanderTarget(hut, from, 16, 32, 8, EYE, 24, keepAway,
                        new SplittableRandom(seed), 48);
                assertNotNull(target, "seed " + seed);
                assertEquals(SurfacePicker.wanderTarget(hut, from, 16, 32, 8, null, 24, keepAway,
                        new SplittableRandom(seed), 48), target, "seed " + seed + ": as if nobody watched");
                if (keepAway == kept) {
                    assertTrue(target.distance(FEET) >= 24, "seed " + seed + ": " + target);
                } else {
                    sum += target.distance(FEET);
                    near += target.distance(FEET) < 10 ? 1 : 0;
                }
            }
        }
        // The fall back of old, the candidate nearest to the eye, ended 188 of these 200 random legs within 10 of the
        // player, 4.8 away on average; now 22 (on the walls and roof of the hut and the floor around it), 33.5.
        assertTrue(sum / 200 > 25, "mean distance of a random leg from the hidden player " + sum / 200);
        assertTrue(near < 40, near + " of 200 random legs end within 10 of the hidden player");
    }

    @Test
    void aCandidateInSightNearerThanSeenFromIsNotPreferred() {
        // The hut again: 9 columns the player sees, all within 2 of the eye, and many out of sight farther out. With
        // seenFrom 0 a candidate on the hut floor wins whenever one is drawn among the 48; with seenFrom 24 it is not
        // preferred (none in sight is that far), so the leg is the first candidate as if nobody watched, and the hut
        // floor stays reachable only at random odds (the switch off: KeepAway.NONE). With behavior.wanderKeepAway
        // (24 from the player) it is rejected outright.
        SurfaceGraph hut = hut();
        Vec3 from = new Vec3(0.5, 0.5, 30.5);
        KeepAway kept = KeepAway.of(Stage.HUNTING, 24, List.of(new KeepAway.Player(FEET, true, false)), 10, 6);
        int preferred = 0, atRandom = 0, kepAway = 0;
        for (int seed = 0; seed < 4000; seed++) {
            if (inHut(SurfacePicker.wanderTarget(hut, from, 16, 32, 8, EYE, 0, KeepAway.NONE,
                    new SplittableRandom(seed), 48))) {
                preferred++;
            }
            Vec3 target = SurfacePicker.wanderTarget(hut, from, 16, 32, 8, EYE, 24, KeepAway.NONE,
                    new SplittableRandom(seed), 48);
            assertEquals(SurfacePicker.wanderTarget(hut, from, 16, 32, 8, null, 24, KeepAway.NONE,
                    new SplittableRandom(seed), 48), target, "seed " + seed + ": no priority for being in sight");
            if (inHut(target)) {
                atRandom++;
                // At random: the first candidate drawn.
                assertEquals(target, SurfacePicker.wanderTarget(hut, from, 16, 32, 8, null, 24, KeepAway.NONE,
                        new SplittableRandom(seed), 1), "seed " + seed);
            }
            if (inHut(SurfacePicker.wanderTarget(hut, from, 16, 32, 8, EYE, 24, kept, new SplittableRandom(seed),
                    48))) {
                kepAway++;
            }
        }
        // 543 legs to the hut floor of 4000 when it is preferred, 17 at random.
        assertTrue(atRandom > 0, "the hut floor is still reachable at random");
        assertTrue(preferred > 8 * atRandom, preferred + " legs to the hut floor when preferred, " + atRandom
                + " at random");
        assertEquals(0, kepAway);
    }

    @Test
    void wanderTargetKeepsAwayFromThePlayer() {
        SurfaceGraph floor = graph(ArrayVoxelGrid.flatFloor(0));
        Vec3 from = new Vec3(0.5, 0.5, 0.5);
        Vec3 eye = new Vec3(10.5, 2.62, 0.5);
        for (int seed = 0; seed < 30; seed++) {
            Vec3 target = SurfacePicker.wanderTarget(floor, from, 16, 32, 8, eye, 0, away(eye, 24),
                    new SplittableRandom(seed), 16);
            assertNotNull(target);
            assertTrue(target.distance(eye) >= 24, "seed " + seed + ": " + target);
        }
        assertNull(SurfacePicker.wanderTarget(floor, from, 16, 32, 8, eye, 0, away(eye, 100), new SplittableRandom(1),
                16));
    }

    @Test
    void wanderTargetKeepsAwayFromEveryPlayerNotOnlyTheViewer() {
        SurfaceGraph floor = graph(ArrayVoxelGrid.flatFloor(0));
        Vec3 from = new Vec3(0.5, 0.5, 0.5);
        double[] draws = {0, 0, 0.5, 0, 0.25, 0}; // east, west, south, 16 blocks away (see above)
        Vec3 west = new Vec3(-15.5, 0.5, 0.5), south = new Vec3(0.5, 0.5, 16.5);
        // A is 8.9 from east, B 5 from west; south is 26.8 and 26.4 from them.
        Vec3 a = new Vec3(24.5, 1, 4.5), b = new Vec3(-20.5, 1, 0.5);
        assertEquals(west, wander(floor, from, null, new KeepAway(List.of(a), 24, 0, 0), draws, 3));
        assertEquals(south, wander(floor, from, null, new KeepAway(List.of(a, b), 24, 0, 0), draws, 3));
        assertEquals(south, wander(floor, from, a.add(0, 1.62, 0), new KeepAway(List.of(a, b), 24, 0, 0), draws, 3),
                "the viewer (the nearest player) is not the only one kept away from");

        // Two players on either side of the entity, 20 and 30 blocks away: every leg ends 24 from both.
        Vec3 near = new Vec3(20.5, 1, 0.5), far = new Vec3(-29.5, 1, 0.5);
        Vec3 eye = near.add(0, 1.62, 0);
        KeepAway both = new KeepAway(List.of(near, far), 24, 0, 0);
        KeepAway nearOnly = new KeepAway(List.of(near), 24, 0, 0);
        int nearTheOther = 0;
        for (int seed = 0; seed < 200; seed++) {
            Vec3 target = SurfacePicker.wanderTarget(floor, from, 16, 32, 8, eye, 0, both, new SplittableRandom(seed),
                    48);
            assertNotNull(target, "seed " + seed);
            assertTrue(target.distance(near) >= 24 && target.distance(far) >= 24, "seed " + seed + ": " + target);
            Vec3 single = SurfacePicker.wanderTarget(floor, from, 16, 32, 8, eye, 0, nearOnly,
                    new SplittableRandom(seed), 48);
            if (single.distance(far) < 24) {
                nearTheOther++;
            }
        }
        assertTrue(nearTheOther > 0, "keeping away from the nearest one only would end legs near the other one");
    }

    @Test
    void whileSeekingACreativePlayerNearestToTheEntityTakesNoCareAwayFromASurvivalOne() {
        SurfaceGraph floor = graph(ArrayVoxelGrid.flatFloor(0));
        Vec3 from = new Vec3(0.5, 0.5, 0.5);
        // An operator in creative mode 12 blocks east (the nearest player, the viewer), a survival player 20 west.
        Vec3 creative = new Vec3(12.5, 1, 0.5), survival = new Vec3(-19.5, 1, 0.5);
        Vec3 eye = creative.add(0, 1.62, 0);
        KeepAway seeking = KeepAway.of(Stage.AWAKENING, 24, List.of(new KeepAway.Player(creative, false, false),
                new KeepAway.Player(survival, true, false)), 10, 6);
        // The rule before: the viewer alone kept 24 away from.
        KeepAway viewerOnly = new KeepAway(List.of(creative), 24, 0, 0);
        int reachedBefore = 0;
        for (int seed = 0; seed < 200; seed++) {
            Vec3 target = SurfacePicker.wanderTarget(floor, from, 16, 32, 8, eye, 0, seeking,
                    new SplittableRandom(seed), 48);
            assertNotNull(target, "seed " + seed);
            assertTrue(target.distance(survival) >= 24, "seed " + seed + ": " + target);
            assertTrue(seeking.passesClear(from, target), "seed " + seed + ": " + target);
            Vec3 before = SurfacePicker.wanderTarget(floor, from, 16, 32, 8, eye, 0, viewerOnly,
                    new SplittableRandom(seed), 48);
            if (!seeking.passesClear(from, before)) {
                reachedBefore++;
            }
        }
        assertTrue(reachedBefore > 0, "keeping away from the creative player only, legs pass the survival one");
    }

    @Test
    void wanderTargetWhoseStraightWayPassesNearAPlayerIsSkipped() {
        SurfaceGraph floor = graph(ArrayVoxelGrid.flatFloor(0));
        Vec3 from = new Vec3(0.5, 0.5, 0.5);
        // First candidate 32 blocks west, then one 16 blocks east.
        double[] draws = {0.5, 1, 0, 0};
        Vec3 west = new Vec3(-31.5, 0.5, 0.5), east = new Vec3(16.5, 0.5, 0.5);
        // The player is 11.4 from the start, 26.6 from west and 24.7 from east; the way west passes 9 from it.
        Vec3 player = new Vec3(-6.5, 1, 9.5);
        assertEquals(west, wander(floor, from, null, new KeepAway(List.of(player), 24, 0, 0), draws, 2));
        assertEquals(east, wander(floor, from, null, new KeepAway(List.of(player), 24, 10, 6), draws, 2));
        assertEquals(west, wander(floor, from, null, new KeepAway(List.of(player), 24, 8.5, 6), draws, 2));
        assertEquals(west, wander(floor, from, null, new KeepAway(List.of(player.add(0, -7, 0)), 24, 10, 6), draws,
                2), "far enough below the way");

        // From 12 blocks away no leg's straight way comes within 10 of the player; without the rule some would.
        Vec3 start = new Vec3(12.5, 0.5, 0.5);
        Vec3 feet = new Vec3(0.5, 1, 0.5);
        KeepAway reach = new KeepAway(List.of(feet), 24, 10, 6);
        KeepAway endOnly = new KeepAway(List.of(feet), 24, 0, 0);
        int crossing = 0;
        for (int seed = 0; seed < 300; seed++) {
            Vec3 target = SurfacePicker.wanderTarget(floor, start, 16, 32, 8, null, 0, reach,
                    new SplittableRandom(seed), 48);
            assertNotNull(target, "seed " + seed);
            assertTrue(reach.passesClear(start, target), "seed " + seed + ": " + target);
            Vec3 single = SurfacePicker.wanderTarget(floor, start, 16, 32, 8, null, 0, endOnly,
                    new SplittableRandom(seed), 48);
            if (!reach.passesClear(start, single)) {
                crossing++;
            }
        }
        assertTrue(crossing > 0, "without the way rule some legs cross the reach");
    }

    /** {@link SurfacePicker#wanderTarget} with the radii 16..32, a vertical range of 8 and scripted draws. */
    private static Vec3 wander(SurfaceGraph graph, Vec3 from, Vec3 eye, KeepAway keepAway, double[] draws,
                               int attempts) {
        return SurfacePicker.wanderTarget(graph, from, 16, 32, 8, eye, 0, keepAway, scripted(draws), attempts);
    }

    /** Keeps legs {@code minDistance} away from one player, with no rule for the way. */
    private static KeepAway away(Vec3 player, double minDistance) {
        return new KeepAway(List.of(player), minDistance, 0, 0);
    }

    @Test
    void searchTargetInsideTheDisk() {
        SurfaceGraph cave = graph(ArrayVoxelGrid.cave(0, 5));
        Vec3 center = new Vec3(2.5, 1, -3.5);
        for (int seed = 0; seed < 30; seed++) {
            Vec3 target = SurfacePicker.searchTarget(cave, center, 8, new SplittableRandom(seed), 4);
            assertNotNull(target);
            assertTrue(target.y() == 0.5 || target.y() == 5.5, "a floor or ceiling node: " + target);
            assertTrue(horizontalDistance(target, center) <= 8 + Math.sqrt(0.5), "distance");
            assertEquals(target, SurfacePicker.searchTarget(cave, center, 8, new SplittableRandom(seed), 4));
        }
        assertEquals(new Vec3(2.5, 0.5, -3.5),
                SurfacePicker.searchTarget(cave, center, 0, new SplittableRandom(1), 1));
        // Nothing within range ceil(r / 2) + 2 of the start level.
        SurfaceGraph deep = graph(ArrayVoxelGrid.flatFloor(-10));
        assertNull(SurfacePicker.searchTarget(deep, center, 8, new SplittableRandom(1), 10));
    }

    @Test
    void spawnPointOnAFlatFloorIsVisibleAndAtTheRightDistance() {
        SurfaceGraph floor = graph(ArrayVoxelGrid.flatFloor(0));
        for (int seed = 0; seed < 20; seed++) {
            SurfacePicker.SpawnPick pick = SurfacePicker.spawnPoint(floor, FEET, EYE, null, 40, 80, 4, 8, null,
                    new SplittableRandom(seed), 16);
            assertNotNull(pick);
            assertTrue(pick.visible());
            assertFalse(pick.nearRoute(), "standing: no route");
            assertEquals(VoxelPos.center(pick.node()), pick.position());
            assertEquals(0.5, pick.position().y());
            assertTrue(inSpawnBand(pick.position(), 40, 80), "distance " + pick.position().distance(FEET));
            assertEquals(pick, SurfacePicker.spawnPoint(floor, FEET, EYE, null, 40, 80, 4, 8, null,
                    new SplittableRandom(seed), 16), "deterministic");
        }
    }

    @Test
    void spawnPointWithEqualMinAndMaxDistance() {
        // No voxel centre is exactly 60 from the feet: every candidate counts within half a voxel diagonal of it.
        SurfaceGraph floor = graph(ArrayVoxelGrid.flatFloor(0));
        for (int seed = 0; seed < 20; seed++) {
            for (Vec3 heading : new Vec3[]{null, Vec3.UNIT_X}) {
                SurfacePicker.SpawnPick pick = SurfacePicker.spawnPoint(floor, FEET, EYE, heading, 60, 60, 4, 8, null,
                        new SplittableRandom(seed), 1);
                assertNotNull(pick, "seed " + seed + ", heading " + heading);
                double d = pick.position().distance(FEET);
                assertTrue(inSpawnBand(pick.position(), 60, 60), "distance " + d);
                assertTrue(d != 60, "the unwidened band would reject it");
            }
        }
    }

    @Test
    void spawnPointHonoursTheAllowedPredicate() {
        SurfaceGraph floor = graph(ArrayVoxelGrid.flatFloor(0));
        for (int seed = 0; seed < 20; seed++) {
            SurfacePicker.SpawnPick pick = SurfacePicker.spawnPoint(floor, FEET, EYE, null, 40, 80, 4, 8,
                    q -> q.x() < 0, new SplittableRandom(seed), 32);
            assertNotNull(pick);
            assertTrue(pick.position().x() < 0);
        }
        assertNull(SurfacePicker.spawnPoint(floor, FEET, EYE, null, 40, 80, 4, 8, q -> false,
                new SplittableRandom(1), 32));
    }

    @Test
    void spawnPointNearTheRouteWhenNothingIsVisible() {
        SurfaceGraph shaft = graph(floorWithShaft());
        // Standing in the shaft the player sees no floor 40..80 away.
        assertNull(SurfacePicker.spawnPoint(shaft, FEET, EYE, null, 40, 80, 4, 8, null, new SplittableRandom(5), 32));
        assertNull(SurfacePicker.spawnPoint(shaft, FEET, EYE, new Vec3(0, -0.5, 0), 40, 80, 4, 8, null,
                new SplittableRandom(5), 32), "vertical motion is standing");
        Vec3 heading = new Vec3(-0.3, 0.7, 0.4); // only the horizontal part counts
        Vec3 ahead = new Vec3(-0.3, 0, 0.4).normalize();
        for (int seed = 0; seed < 20; seed++) {
            SurfacePicker.SpawnPick pick = SurfacePicker.spawnPoint(shaft, FEET, EYE, heading, 40, 80, 4, 8, null,
                    new SplittableRandom(seed), 16);
            assertNotNull(pick, "seed " + seed);
            assertTrue(pick.nearRoute());
            assertFalse(pick.visible());
            Vec3 w = pick.position().sub(FEET);
            double along = w.dot(ahead);
            assertTrue(along > 0);
            assertTrue(new Vec3(w.x(), 0, w.z()).sub(ahead.scale(along)).length() <= 4 + 1e-9, "off the route");
            assertTrue(inSpawnBand(pick.position(), 40, 80), "distance " + pick.position().distance(FEET));
        }
    }

    /** Ground solid for {@code y <= floor(x / 2)}, rising toward +x (27°); x from -90 to 90, the same for every z. */
    private static ArrayVoxelGrid ramp() {
        ArrayVoxelGrid g = new ArrayVoxelGrid(-90, -50, -4, 90, 50, 4, ArrayVoxelGrid.Outside.EXTEND);
        for (int x = g.minX; x <= g.maxX; x++) {
            g.fill(x, g.minY, g.minZ, x, Math.floorDiv(x, 2), g.maxZ, true);
        }
        return g;
    }

    @Test
    void spawnPointNearTheRouteUpAndDownASlope() {
        SurfaceGraph ramp = graph(ramp());
        // The eye deep in the rock sees nothing: only the way counts. The player stands at x = 0, on y = 0.
        Vec3 buried = new Vec3(0.5, -9, 0.5);
        for (Vec3 heading : new Vec3[]{Vec3.UNIT_X, new Vec3(-1, 0, 0)}) {
            for (int seed = 0; seed < 20; seed++) {
                SurfacePicker.SpawnPick pick = SurfacePicker.spawnPoint(ramp, FEET, buried, heading, 40, 80, 4, 48,
                        null, new SplittableRandom(seed), 16);
                assertNotNull(pick, "heading " + heading + ", seed " + seed);
                assertTrue(pick.nearRoute());
                assertFalse(pick.visible());
                Vec3 w = pick.position().sub(FEET);
                assertTrue(w.x() * heading.x() > 0, "ahead");
                assertTrue(Math.abs(w.z()) <= 4, "off the route");
                // The top of the node is far above (below) the feet: more than the half width off the horizontal.
                double rise = w.y() + 0.5;
                assertTrue(Math.abs(rise) >= 15 && Math.signum(rise) == heading.x(), "rise " + rise);
                assertTrue(inSpawnBand(pick.position(), 40, 80), "distance " + pick.position().distance(FEET));
            }
        }
    }

    /** A floor solid for {@code y <= floorY}, and under the player a 5x5 pillar up to y = 0. */
    private static ArrayVoxelGrid pillarOverFloor(int floorY) {
        ArrayVoxelGrid g = new ArrayVoxelGrid(-10, floorY - 10, -10, 10, 10, 10, ArrayVoxelGrid.Outside.EXTEND);
        g.fill(g.minX, g.minY, g.minZ, g.maxX, floorY, g.maxZ, true).fill(-2, floorY, -2, 2, 0, 2, true);
        return g;
    }

    @Test
    void spawnPointFarBelowTheWayIsNotNearIt() {
        Vec3 buried = new Vec3(0.5, -9, 0.5);
        // 20 below: a node in the band is at least 34.6 ahead, and 20 <= 4 + 34.6, so the way may lead down there.
        SurfacePicker.SpawnPick pick = SurfacePicker.spawnPoint(graph(pillarOverFloor(-20)), FEET, buried,
                Vec3.UNIT_X, 40, 80, 4, 64, null, new SplittableRandom(1), 16);
        assertNotNull(pick);
        assertTrue(pick.nearRoute());
        assertEquals(-19.5, pick.position().y());
        // 60 below: a node in the band is at most 53.7 ahead, steeper than 45° all the way down.
        SurfaceGraph deep = graph(pillarOverFloor(-60));
        for (int seed = 0; seed < 20; seed++) {
            assertNull(SurfacePicker.spawnPoint(deep, FEET, buried, Vec3.UNIT_X, 40, 80, 4, 64, null,
                    new SplittableRandom(seed), 32), "seed " + seed);
        }
        // The candidates are there: an eye in the open above the floor sees some.
        assertNotNull(SurfacePicker.spawnPoint(deep, FEET, FEET.add(30, 0, 0), null, 40, 80, 4, 64, null,
                new SplittableRandom(1), 32));
    }

    @Test
    void nearRouteMeasuresSidewaysHorizontallyAndHeightWithinASlope() {
        Vec3 feet = new Vec3(0.5, 64, 0.5);
        Vec3 east = Vec3.UNIT_X;
        // Sideways, horizontally (a floor node: its top is level with the feet).
        assertTrue(SurfacePicker.nearRoute(feet, east, new Vec3(50.5, 63.5, 10.5), 10));
        assertFalse(SurfacePicker.nearRoute(feet, east, new Vec3(50.5, 63.5, 10.51), 10));
        // The top of the voxel within 10 + t of the feet in height, up and down (t = 40).
        assertTrue(SurfacePicker.nearRoute(feet, east, new Vec3(40.5, 113.5, 10.5), 10));
        assertFalse(SurfacePicker.nearRoute(feet, east, new Vec3(40.5, 113.51, 0.5), 10));
        assertTrue(SurfacePicker.nearRoute(feet, east, new Vec3(40.5, 13.5, -9.5), 10));
        assertFalse(SurfacePicker.nearRoute(feet, east, new Vec3(40.5, 13.49, 0.5), 10));
        // Only ahead.
        assertFalse(SurfacePicker.nearRoute(feet, east, new Vec3(-40.5, 63.5, 0.5), 10));
        assertFalse(SurfacePicker.nearRoute(feet, east, new Vec3(0.5, 63.5, 5.5), 10));
        // Any horizontal direction: 20 east is 14.1 ahead and 14.1 to the side of north-east.
        Vec3 northEast = new Vec3(1, 0, 1).normalize();
        assertFalse(SurfacePicker.nearRoute(feet, northEast, new Vec3(20.5, 63.5, 0.5), 14));
        assertTrue(SurfacePicker.nearRoute(feet, northEast, new Vec3(20.5, 63.5, 0.5), 14.2));
    }

    @Test
    void spawnPointNothingFound() {
        SurfaceGraph air = graph(new ArrayVoxelGrid(0, 0, 0, 0, 0, 0, false));
        assertNull(SurfacePicker.spawnPoint(air, FEET, EYE, Vec3.UNIT_X, 40, 80, 4, 8, null,
                new SplittableRandom(1), 32));
        // The floor is out of the vertical range.
        SurfaceGraph deep = graph(ArrayVoxelGrid.flatFloor(-12));
        assertNull(SurfacePicker.spawnPoint(deep, FEET, EYE, null, 40, 80, 4, 8, null, new SplittableRandom(1), 32));
        assertNull(SurfacePicker.wanderTarget(deep, FEET, 16, 32, 8, null, 0, KeepAway.NONE, new SplittableRandom(1),
                32));
        assertNull(SurfacePicker.spawnPoint(graph(ArrayVoxelGrid.flatFloor(0)), FEET, EYE, null, 40, 80, 4, 8, null,
                new SplittableRandom(1), 0), "no attempts");
    }

    @Test
    void invalidArguments() {
        SurfaceGraph floor = graph(ArrayVoxelGrid.flatFloor(0));
        SplittableRandom random = new SplittableRandom(1);
        assertThrows(IllegalArgumentException.class,
                () -> SurfacePicker.wanderTarget(floor, FEET, 32, 16, 8, null, 0, KeepAway.NONE, random, 8));
        assertThrows(IllegalArgumentException.class,
                () -> SurfacePicker.searchTarget(floor, FEET, -1, random, 8));
        assertThrows(IllegalArgumentException.class,
                () -> SurfacePicker.spawnPoint(floor, FEET, EYE, null, 80, 40, 4, 8, null, random, 8));
        assertThrows(IllegalArgumentException.class,
                () -> SurfacePicker.spawnPoint(floor, FEET, EYE, null, 40, 80, -1, 8, null, random, 8));
    }
}
