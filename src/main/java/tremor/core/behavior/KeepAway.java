package tremor.core.behavior;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntFunction;

import tremor.core.math.Vec3;
import tremor.core.path.Path;

/**
 * The players a wander leg keeps away from (SPEC 5.6, 8), and how far: where each of them stands, how far from every
 * one the leg must end ({@link #endsClear}), and how near its way there may pass ({@link #passesClear}). DORMANT
 * wandering keeps only its end away (and so does ALERT and HUNTING wandering with {@code behavior.wanderKeepAway});
 * a roam while seeking (AWAKENING) keeps its way out of reach of every player who can be taken too, so that a player
 * who keeps quiet is not found by chance. {@link SurfacePicker#wanderTarget} tests the straight way to each
 * candidate, the body the route it plans for the leg.
 * <p>
 * The way: a point of it at most {@code passVertical} above or below a player's feet must be at least
 * {@code passHorizontal} from them horizontally, or, if the way starts nearer than that already (within the height
 * too), at least as far as its start: a leg may lead away from a player it starts close to, never toward one. The way
 * is tested at its points and between them, at most {@value #STEP} blocks apart.
 *
 * @param players        the feet of the players kept away from
 * @param minDistance    a leg ends at least this far (straight line) from each of them; 0 sets no rule
 * @param passHorizontal its way passes no nearer than this horizontally...; 0 sets no rule for the way
 * @param passVertical   ...to a player whose feet are at most this far above or below that part of the way
 */
public record KeepAway(List<Vec3> players, double minDistance, double passHorizontal, double passVertical) {
    /** Keeps away from nobody. */
    public static final KeepAway NONE = new KeepAway(List.of(), 0, 0, 0);
    /** Most blocks between two tested points of a way. */
    public static final double STEP = 0.5;

    /**
     * A player in the level, as {@link #of} chooses the ones to keep away from.
     *
     * @param feet      where the player's feet are
     * @param takeable  whether an Awakening starting by itself may take the player (alive, in survival mode, in no
     *                  event of the hollow)
     * @param spectator whether the player is a spectator
     */
    public record Player(Vec3 feet, boolean takeable, boolean spectator) {
        public Player {
            Objects.requireNonNull(feet, "feet");
        }
    }

    /** @throws IllegalArgumentException if a distance is negative or not a number */
    public KeepAway {
        players = List.copyOf(Objects.requireNonNull(players, "players"));
        if (!(minDistance >= 0) || !(passHorizontal >= 0) || !(passVertical >= 0)) {
            throw new IllegalArgumentException("distances " + minDistance + ", " + passHorizontal + ", "
                    + passVertical);
        }
    }

    /**
     * What a wander leg of an entity in {@code stage} keeps away from (SPEC 5.6, 8). While it seeks (AWAKENING): every
     * player who can be taken, and only those (one who cannot, in creative mode for one, takes none of that care away
     * from the others), the leg ending {@code minDistance} from each and its way passing none of them nearer than
     * {@code passHorizontal} horizontally where it is at most {@code passVertical} above or below the feet (or, starting
     * nearer than that, nearer than its start; see the class). In another stage (DORMANT, ALERT, HUNTING): every player
     * who is not a spectator, only the end of the leg {@code minDistance} away ({@link #NONE} for 0).
     *
     * @throws IllegalArgumentException if a distance is negative or not a number
     */
    public static KeepAway of(Stage stage, double minDistance, List<Player> players, double passHorizontal,
                              double passVertical) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(players, "players");
        boolean seeking = stage == Stage.AWAKENING;
        if (!seeking && minDistance == 0) {
            return NONE;
        }
        List<Vec3> kept = new ArrayList<>();
        for (Player player : players) {
            if (seeking ? player.takeable() : !player.spectator()) {
                kept.add(player.feet());
            }
        }
        return seeking ? new KeepAway(kept, minDistance, passHorizontal, passVertical)
                : new KeepAway(kept, minDistance, 0, 0);
    }

    /** Whether a leg may end at {@code end}: at least {@code minDistance} from every player. */
    public boolean endsClear(Vec3 end) {
        Objects.requireNonNull(end, "end");
        for (Vec3 feet : players) {
            if (end.distance(feet) < minDistance) {
                return false;
            }
        }
        return true;
    }

    /** Whether the straight way from {@code from} to {@code to} keeps away from every player (see the class). */
    public boolean passesClear(Vec3 from, Vec3 to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        return wayClear(2, i -> i == 0 ? from : to);
    }

    /**
     * Whether a route keeps away from every player (see the class): its way through the centres of its nodes, from the
     * first. A dive is a straight line through the rock like any other edge: the bump is where it is, also under rock.
     */
    public boolean passesClear(Path path) {
        Objects.requireNonNull(path, "path");
        return wayClear(path.size(), path::point);
    }

    private boolean wayClear(int count, IntFunction<Vec3> point) {
        if (passHorizontal <= 0 || players.isEmpty()) {
            return true;
        }
        Vec3 start = point.apply(0);
        for (Vec3 feet : players) {
            double limit = passHorizontal;
            if (Math.abs(start.y() - feet.y()) <= passVertical) {
                limit = Math.min(limit, horizontalDistance(start, feet));
            }
            Vec3 a = start;
            for (int i = 1; i < count; i++) {
                Vec3 b = point.apply(i);
                int steps = Math.max(1, (int) Math.ceil(a.distance(b) / STEP));
                for (int k = 1; k <= steps; k++) {
                    Vec3 p = a.lerp(b, (double) k / steps);
                    if (Math.abs(p.y() - feet.y()) <= passVertical && horizontalDistance(p, feet) < limit) {
                        return false;
                    }
                }
                a = b;
            }
        }
        return true;
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }
}
