package tremor.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.path.Path;

class KeepAwayTest {
    /** A player standing on the floor y <= 0 at the origin column. */
    private static final Vec3 FEET = new Vec3(0.5, 1, 0.5);
    /** The seeking defaults: legs end 24 from the player, their way passes no nearer than 8 + 2, within 5 + 1. */
    private static final KeepAway SEEKING = new KeepAway(List.of(FEET), 24, 10, 6);

    /** A route through the centres of the given floor voxels (x, z pairs at y = 0). */
    private static Path route(int... xz) {
        long[] nodes = new long[xz.length / 2];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = VoxelPos.pack(xz[2 * i], 0, xz[2 * i + 1]);
        }
        return new Path(nodes, new boolean[nodes.length - 1], true);
    }

    private static Vec3 floor(double x, double z) {
        return new Vec3(x, 0.5, z);
    }

    @Test
    void aLegEndsFarEnoughFromEveryPlayer() {
        KeepAway two = new KeepAway(List.of(FEET, new Vec3(40.5, 1, 0.5)), 24, 0, 0);
        assertTrue(two.endsClear(floor(20.5, 30.5)));
        assertFalse(two.endsClear(floor(-20.5, 0.5)), "20 from the first");
        assertFalse(two.endsClear(floor(60.5, 0.5)), "20 from the second");
        assertTrue(two.endsClear(floor(-23.5, 0.5)), "24 from the first in a straight line");
        assertTrue(KeepAway.NONE.endsClear(FEET));
        assertTrue(new KeepAway(List.of(FEET), 0, 0, 0).endsClear(FEET), "a minDistance of 0 sets no rule");
    }

    @Test
    void aStraightWayPassingWithinReachIsNotClear() {
        // The review's example: from 10 blocks east of the player to a point 24.4 from it and 31.2 away; the way
        // passes 6.4 from the player.
        Vec3 from = floor(10.5, 0.5), to = floor(-13.5, 20.5);
        assertTrue(SEEKING.endsClear(to));
        assertFalse(SEEKING.passesClear(from, to));
        assertTrue(new KeepAway(List.of(FEET), 24, 6, 6).passesClear(from, to), "6.4 is out of a reach of 6");
        assertTrue(new KeepAway(List.of(FEET), 24, 0, 0).passesClear(from, to), "no rule for the way");
        // Straight away from the player, or past it far enough.
        assertTrue(SEEKING.passesClear(floor(12.5, 0.5), floor(40.5, 0.5)));
        assertTrue(SEEKING.passesClear(floor(-20.5, 12.5), floor(20.5, 12.5)), "passes 12 from it");
        assertFalse(SEEKING.passesClear(floor(-20.5, 8.5), floor(20.5, 8.5)), "passes 8 from it");
    }

    @Test
    void theWayCountsOnlyNearThePlayersHeight() {
        Vec3 from = floor(-20.5, 0.5), to = floor(20.5, 0.5); // right over the player's column
        assertFalse(SEEKING.passesClear(from, to));
        assertFalse(new KeepAway(List.of(FEET.add(0, -6.5, 0)), 24, 10, 6).passesClear(from, to), "6 below the way");
        assertTrue(new KeepAway(List.of(FEET.add(0, -7.5, 0)), 24, 10, 6).passesClear(from, to), "7 below the way");
        assertTrue(new KeepAway(List.of(FEET.add(0, 6.5, 0)), 24, 10, 6).passesClear(from, to), "7 above the way");
        // A way that climbs to the player's height only far from it.
        assertTrue(SEEKING.passesClear(new Vec3(0.5, 20.5, 0.5), new Vec3(30.5, 0.5, 0.5)));
    }

    @Test
    void aWayStartingNearAPlayerMayLeadAwayButNotCloser() {
        Vec3 start = floor(9.5, 0.5); // 9 from the player: nearer than the 10 of the rule
        assertTrue(SEEKING.passesClear(start, floor(35.5, 0.5)), "straight away");
        assertTrue(SEEKING.passesClear(start, floor(9.5, 30.5)), "sideways: never nearer than 9");
        assertFalse(SEEKING.passesClear(start, floor(-20.5, 15.5)), "around it, nearer than it started");
        assertFalse(SEEKING.passesClear(start, floor(7.5, 30.5)), "a little toward it first");
        // Starting above or below the player's height, the full distance applies once the way is at that height.
        assertFalse(SEEKING.passesClear(new Vec3(9.5, 7.5, 0.5), floor(9.5, 30.5)));
    }

    @Test
    void aRouteIsTestedAlongEveryEdge() {
        // From 20 east to 20 east and 20 south: the straight way stays 20 away, the route bends to 7.1 from it.
        Path bent = route(20, 0, 5, 5, 20, 20);
        assertFalse(SEEKING.passesClear(bent));
        assertTrue(SEEKING.passesClear(route(20, 0, 20, 20)));
        assertTrue(SEEKING.passesClear(route(20, 0)), "a route of one node");
        // A long dive edge through the rock under the player counts: the bump is there, under the rock.
        Path dive = new Path(new long[]{VoxelPos.pack(-15, -3, 0), VoxelPos.pack(15, -3, 0)}, new boolean[]{true},
                true);
        assertFalse(SEEKING.passesClear(dive));
        // A long edge is tested every half block, not only at its ends (31.3 from the player; its middle 9).
        Path sparse = new Path(new long[]{VoxelPos.pack(30, 0, 9), VoxelPos.pack(-30, 0, 9)}, new boolean[1], true);
        assertFalse(SEEKING.passesClear(sparse));
        assertTrue(KeepAway.NONE.passesClear(bent));
    }

    @Test
    void everyPlayerCounts() {
        KeepAway two = new KeepAway(List.of(new Vec3(100.5, 1, 0.5), FEET), 24, 10, 6);
        assertFalse(two.passesClear(floor(-20.5, 5.5), floor(20.5, 5.5)), "near the second of them");
        assertTrue(two.passesClear(floor(-20.5, 15.5), floor(20.5, 15.5)));
        // A planned route too: from far from both, past the second one.
        assertFalse(two.passesClear(route(80, 30, 40, 30, 0, 5, -30, 5)), "5 from the second of them");
        assertTrue(two.passesClear(route(80, 30, 40, 30, 0, 15, -30, 15)));
    }

    @Test
    void whileSeekingOnlyThePlayersWhoCanBeTakenAreKeptAwayFrom() {
        // An operator in creative mode stands nearest, a survival player 30 blocks away, a spectator in between.
        Vec3 creative = new Vec3(10.5, 1, 0.5), survival = new Vec3(-29.5, 1, 0.5), spectator = new Vec3(-5.5, 1, 0.5);
        List<KeepAway.Player> players = List.of(new KeepAway.Player(creative, false, false),
                new KeepAway.Player(survival, true, false), new KeepAway.Player(spectator, false, true));
        KeepAway seeking = KeepAway.of(Stage.AWAKENING, 24, players, 10, 6);
        assertEquals(List.of(survival), seeking.players(), "the creative player stands in for nobody");
        assertEquals(10, seeking.passHorizontal());
        assertEquals(6, seeking.passVertical());
        assertTrue(seeking.endsClear(floor(14.5, 0.5)), "near the creative player");
        assertFalse(seeking.endsClear(floor(-9.5, 0.5)), "20 from the survival player");
        assertFalse(seeking.passesClear(floor(0.5, 0.5), floor(-60.5, 5.5)), "passes 2.5 from the survival player");
        // The way is tested also for a leg that has no rule for its end (the route of a planned leg).
        assertFalse(KeepAway.of(Stage.AWAKENING, 0, players, 10, 6).passesClear(floor(0.5, 0.5),
                floor(-60.5, 5.5)));
        // Nobody to take: nobody to keep away from.
        assertTrue(KeepAway.of(Stage.AWAKENING, 24, List.of(players.get(0), players.get(2)), 10, 6).players()
                .isEmpty());

        // DORMANT: the end of the leg only, away from every player who is not a spectator.
        KeepAway dormant = KeepAway.of(Stage.DORMANT, 24, players, 10, 6);
        assertEquals(List.of(creative, survival), dormant.players());
        assertEquals(0, dormant.passHorizontal(), "no rule for the way");
        assertTrue(dormant.passesClear(floor(0.5, 0.5), floor(-60.5, 5.5)));
        // ALERT and HUNTING wandering (minDistance 0) keeps away from nobody.
        assertSame(KeepAway.NONE, KeepAway.of(Stage.HUNTING, 0, players, 10, 6));
        assertSame(KeepAway.NONE, KeepAway.of(Stage.ALERT, 0, players, 10, 6));
        assertThrows(IllegalArgumentException.class, () -> KeepAway.of(Stage.DORMANT, -1, players, 10, 6));
    }

    @Test
    void invalidDistances() {
        assertThrows(IllegalArgumentException.class, () -> new KeepAway(List.of(), -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new KeepAway(List.of(), 0, Double.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> new KeepAway(List.of(), 0, 0, -0.5));
    }
}
