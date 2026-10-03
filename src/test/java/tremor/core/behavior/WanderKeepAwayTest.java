package tremor.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;
import tremor.core.graph.SurfaceGraph;
import tremor.core.math.Vec3;
import tremor.core.testing.ArrayVoxelGrid;

/**
 * Wander legs of the {@link Brain} over a surface graph, stage by stage (SPEC 5.6), with the world answering as the
 * game's does ({@code tremor.entity.TremorMind}): {@link KeepAway#of} for the stage and the distance the brain passes,
 * and {@link SurfacePicker#wanderTarget} with the nearest player who is no spectator as the viewer and
 * {@code minWanderDistance} as the distance a point counts as seen from. The body arrives at every leg at once and
 * nothing is heard, so every GO is a wander leg.
 * <p>
 * The scene: a flat floor; a survival player keeps quiet in a closed stone hut at the origin column (he sees only its
 * floor), a creative player stands in the open, a spectator floats over the floor; the entity starts 26 blocks south
 * of the hut.
 */
class WanderKeepAwayTest {
    private static final Vec3 HIDDEN = new Vec3(0.5, 1, 0.5);
    private static final Vec3 CREATIVE = new Vec3(-35.5, 1, 30.5);
    private static final Vec3 SPECTATOR = new Vec3(20.5, 1, 40.5);
    private static final List<KeepAway.Player> PLAYERS = List.of(new KeepAway.Player(HIDDEN, true, false),
            new KeepAway.Player(CREATIVE, false, false), new KeepAway.Player(SPECTATOR, false, true));
    private static final Vec3 START = new Vec3(0.5, 0.5, 26.5);
    /** The default {@code behavior.minWanderDistance}. */
    private static final double MIN = 24;
    /** The game's way rule while seeking: reach 8 plus 2 horizontally, 5 plus 1 in height. */
    private static final double PASS_HORIZONTAL = 10, PASS_VERTICAL = 6;
    private static final int SEEDS = 100, LEGS = 12;

    /** Default numbers (SPEC 8), no wander pause, the given {@code wanderKeepAway}. */
    private static BehaviorParams params(boolean wanderKeepAway) {
        return new BehaviorParams(25, 60, 100, 3, 0.5, 20, 3, 0.35, 2.5, 12, 8, 20, 0, MIN, 4, wanderKeepAway);
    }

    private static SurfaceGraph hutOnAFloor() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        g.fill(-2, 1, -2, 2, 4, 2, true).fill(-1, 1, -1, 1, 3, 1, false);
        return new SurfaceGraph(g, 4);
    }

    /** Answers the brain as {@code TremorMind} does, for an entity in {@code stage}. */
    private record GameWorld(SurfaceGraph graph, Stage stage) implements BrainWorld {
        @Override
        public Vec3 wanderTarget(Vec3 from, double minDistanceToPlayer, RandomGenerator random) {
            KeepAway keepAway = KeepAway.of(stage, minDistanceToPlayer, PLAYERS, PASS_HORIZONTAL, PASS_VERTICAL);
            return SurfacePicker.wanderTarget(graph, from, 16, 32, 8, viewerEye(from), MIN, keepAway, random, 48);
        }

        @Override
        public Vec3 searchTarget(Vec3 center, double radius, RandomGenerator random) {
            throw new AssertionError("nothing was heard: no search");
        }

        /** The eye of the nearest player who is no spectator (all are within the viewer range of 128). */
        private static Vec3 viewerEye(Vec3 from) {
            Vec3 nearest = null;
            for (KeepAway.Player player : PLAYERS) {
                if (!player.spectator() && (nearest == null
                        || player.feet().distance(from) < nearest.distance(from))) {
                    nearest = player.feet();
                }
            }
            return nearest.add(0, 1.62, 0);
        }
    }

    /** The ends of {@link #LEGS} wander legs from {@link #START} of a brain in {@code stage} with the given seed. */
    private static List<Vec3> legs(SurfaceGraph graph, Stage stage, boolean wanderKeepAway, long seed) {
        Brain brain = new Brain(params(wanderKeepAway), seed);
        GameWorld world = new GameWorld(graph, stage);
        Vec3 here = START;
        List<Vec3> ends = new ArrayList<>();
        for (int tick = 0; ends.size() < LEGS && tick < 4 * LEGS; tick++) {
            Decision decision = brain.tick(0.25, stage, here, true, world);
            if (decision.action() == Decision.Action.GO) {
                assertEquals("wander", decision.reason(), stage + ", seed " + seed);
                ends.add(decision.target());
                here = decision.target();
            }
        }
        assertEquals(LEGS, ends.size(), stage + ", seed " + seed + ": legs found");
        return ends;
    }

    @Test
    void everyLegThatKeepsAwayEndsMinWanderDistanceFromEveryPlayerItKeepsAwayFrom() {
        // DORMANT and AWAKENING always, ALERT and HUNTING with behavior.wanderKeepAway. DORMANT, ALERT and HUNTING keep
        // away from every player who is no spectator; AWAKENING from every one who can be taken, and the straight way
        // of each leg passes none of them within reach.
        SurfaceGraph graph = hutOnAFloor();
        KeepAway seekingWay = KeepAway.of(Stage.AWAKENING, MIN, PLAYERS, PASS_HORIZONTAL, PASS_VERTICAL);
        int nearTheSpectator = 0, nearTheCreativeWhileSeeking = 0;
        for (boolean wanderKeepAway : new boolean[]{false, true}) {
            for (Stage stage : Stage.values()) {
                boolean seeking = stage == Stage.AWAKENING;
                if (!wanderKeepAway && (stage == Stage.ALERT || stage == Stage.HUNTING)) {
                    continue;
                }
                for (int seed = 0; seed < SEEDS; seed++) {
                    Vec3 from = START;
                    for (Vec3 end : legs(graph, stage, wanderKeepAway, seed)) {
                        String where = stage + ", switch " + wanderKeepAway + ", seed " + seed + ": " + end;
                        assertTrue(end.distance(HIDDEN) >= MIN, where);
                        if (seeking) {
                            assertTrue(seekingWay.passesClear(from, end), where + " from " + from);
                            nearTheCreativeWhileSeeking += end.distance(CREATIVE) < MIN ? 1 : 0;
                        } else {
                            assertTrue(end.distance(CREATIVE) >= MIN, where);
                        }
                        nearTheSpectator += end.distance(SPECTATOR) < MIN ? 1 : 0;
                        from = end;
                    }
                }
            }
        }
        // Neither the spectator nor, while seeking, the creative player (who cannot be taken) is kept away from.
        assertTrue(nearTheSpectator > 0, "legs within 24 of the spectator: " + nearTheSpectator);
        assertTrue(nearTheCreativeWhileSeeking > 0, "seeking legs within 24 of the creative player: "
                + nearTheCreativeWhileSeeking);
    }

    @Test
    void randomAlertAndHuntingLegsMayEndNearAQuietPlayerButAreNotDrawnToHim() {
        // Without behavior.wanderKeepAway (the default) ALERT and HUNTING wander at random, as a warden roams: a leg
        // may end near the hidden player. None is drawn to him though: he sees only the floor of his hut, nearer than
        // minWanderDistance, which counts as seen by no one, and a leg seen by no one is the first candidate drawn,
        // not the one nearest to him. Until 2026-10-03 the picker took that one: then most legs ended at the hut.
        SurfaceGraph graph = hutOnAFloor();
        for (Stage stage : List.of(Stage.ALERT, Stage.HUNTING)) {
            int near = 0, atTheHut = 0;
            for (int seed = 0; seed < SEEDS; seed++) {
                for (Vec3 end : legs(graph, stage, false, seed)) {
                    near += end.distance(HIDDEN) < MIN ? 1 : 0;
                    atTheHut += end.distance(HIDDEN) < 4 ? 1 : 0;
                }
            }
            assertTrue(near > 0, stage + ": legs within 24 of the hidden player " + near);
            assertTrue(atTheHut < SEEDS * LEGS / 50, stage + ": legs at the hut " + atTheHut + " of " + SEEDS * LEGS);
        }
    }
}
