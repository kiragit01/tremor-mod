package tremor.awakening;

import tremor.core.math.Vec3;
import tremor.core.shape.AwakeningShape;
import tremor.hollow.HollowOutcome;

import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * Rules of the Awakening's real-world part (SPEC 9 phase 1) that do not need the game, unit-tested directly: the zone
 * and the escape from it, when the seeking entity has reached a player and who is taken, when the Darkness comes and
 * which Darkness is the Awakening's to take away, how strong the ring of a step is, when a rooted player has strayed
 * and whether it may drop, how the end of the event in the hollow ends the Awakening, when the hill of a victory rises
 * and how hard the ground pulls in a defeat.
 * <p>
 * The zone is a vertical cylinder around the centre (SPEC 9: "сфера/цилиндр"): only the horizontal distance counts,
 * so climbing a tower or digging down does not get the player out, walking away does.
 */
public final class AwakeningRules {
    /** A step ripple weaker than this (relative to a walking step) is not sent: the ground hardly shook. */
    public static final float MIN_RIPPLE_STRENGTH = 0.05f;
    /** Ripples are capped at this strength: a long fall would otherwise make a ring of dozens of steps. */
    public static final float MAX_RIPPLE_STRENGTH = 4;
    /** Loudness of a walking step when the config gives steps none (the default of {@code hearing.loudness}). */
    static final double DEFAULT_WALKING_STEP = 2;
    /**
     * Length (ticks) of the Darkness the target is given. Short, so that one left over when the Awakening could not
     * take it away (a crash) soon runs out; renewed long before that.
     */
    public static final int DARKNESS_TICKS = 80;
    /**
     * The Darkness is renewed once this many ticks or fewer are left: well above the {@value #DARKNESS_FADE_TICKS}
     * ticks over which vanilla fades it out at its end, so it never starts to fade while the target is in it (lag
     * included).
     */
    public static final int DARKNESS_RENEW_TICKS = 50;
    /** Ticks over which vanilla fades the Darkness in and out (its blend duration). */
    static final int DARKNESS_FADE_TICKS = 22;
    /** Leeway (ticks) when a Darkness is told apart from the one given ({@link #ownDarkness}). */
    static final int DARKNESS_LEEWAY = 2;

    /** A block of the column under a rooted player (or the vehicle it is kept on), for {@link #mayDrop}. */
    public enum Cell {
        /** Nothing to land on (air, a plant, a cobweb...): a fall goes on through it. */
        OPEN,
        /** Something to stand on. */
        FLOOR,
        /** A fluid, lava or fire, or a block that is not loaded: it is held above it rather than dropped into it. */
        UNSAFE
    }

    /**
     * How far (blocks) above or below a player's feet the bump may be and still have reached the player
     * ({@link #reaches}): the floor under the feet is half a block below them, the ceiling of a tall cave room (or the
     * top of a pillar the player stands next to) about 5 above; a bump in the ground over a deep tunnel, or at the
     * foot of a tower, has not reached the player. The comment of {@code awakening.reachDistance} states it.
     */
    public static final double REACH_HEIGHT = 5;

    /**
     * A player who might be taken by an Awakening that starts by itself (SPEC 8 AWAKENING): one in the level of the
     * entity.
     *
     * @param id       the player
     * @param feet     where the player stands
     * @param eligible whether the player can be taken at all: alive, in survival mode (not adventure: the level in
     *                 the hollow cannot be won without breaking blocks), in no event of the hollow already
     */
    public record Candidate(UUID id, Vec3 feet, boolean eligible) {
    }

    private AwakeningRules() {
    }

    /** Horizontal distance between two points (the zone is a vertical cylinder). */
    public static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = a.x() - b.x(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Whether {@code point} is in the zone of {@code radius} around {@code center}: horizontally within the radius
     * (the edge itself is inside). Out of it during the buildup, the target has escaped (SPEC 9 "Побег").
     */
    public static boolean inZone(Vec3 center, double radius, Vec3 point) {
        double dx = point.x() - center.x(), dz = point.z() - center.z();
        return dx * dx + dz * dz <= radius * radius;
    }

    /**
     * Whether the bump of an entity seeking in AWAKENING (its centre, {@code bump}) has reached a player standing at
     * {@code feet} (SPEC 8: "добралась до игрока"): horizontally within {@code reachDistance} ({@code
     * awakening.reachDistance}; the edge itself counts), like the zone, and at most {@link #REACH_HEIGHT} above or
     * below the feet.
     */
    public static boolean reaches(Vec3 bump, Vec3 feet, double reachDistance) {
        return horizontalDistance(bump, feet) <= reachDistance && Math.abs(bump.y() - feet.y()) <= REACH_HEIGHT;
    }

    /**
     * The player an Awakening that starts by itself takes, once the seeking entity has reached somebody
     * ({@link #reaches}): of the eligible players it has reached, the one it heard last, else the nearest
     * (horizontally; the first of equally near ones). Null if it has reached nobody eligible: the entity seeks on.
     *
     * @param lastHeard the player whose vibration the entity heard last, or null
     * @param bump      the centre of the entity's bump
     */
    public static UUID chooseTarget(UUID lastHeard, Vec3 bump, List<Candidate> candidates, double reachDistance) {
        Candidate nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (Candidate candidate : candidates) {
            if (!candidate.eligible() || !reaches(bump, candidate.feet(), reachDistance)) {
                continue;
            }
            if (candidate.id().equals(lastHeard)) {
                return candidate.id();
            }
            double distance = horizontalDistance(bump, candidate.feet());
            if (nearest == null || distance < best) {
                nearest = candidate;
                best = distance;
            }
        }
        return nearest == null ? null : nearest.id();
    }

    /**
     * Ticks into a buildup of {@code buildupTicks} from which the target is in the Darkness: its last third, rounded
     * down (none of a buildup shorter than three ticks).
     */
    public static int darknessStart(int buildupTicks) {
        return buildupTicks - buildupTicks / 3;
    }

    /**
     * Strength of the ring wave of a vibration (SPEC 9 "Рябь от шагов"), relative to a walking step on ordinary
     * ground: {@code loudness * footing / walkingStep}, so a step on stone is a little stronger, a sprint or a landing
     * twice as strong, a step on wool a tenth and a sneaking step (or one in the air) nothing. Capped at
     * {@link #MAX_RIPPLE_STRENGTH}.
     *
     * @param loudness    base loudness after the per-source rules (sneaking, sprinting...)
     * @param footing     conductivity under the source (0 in the air)
     * @param walkingStep loudness of a walking step from the config; {@value #DEFAULT_WALKING_STEP} if that is not
     *                    positive (steps are not heard)
     */
    public static float rippleStrength(double loudness, double footing, double walkingStep) {
        double step = walkingStep > 0 ? walkingStep : DEFAULT_WALKING_STEP;
        double strength = loudness * footing / step;
        return strength > 0 ? (float) Math.min(MAX_RIPPLE_STRENGTH, strength) : 0;
    }

    /**
     * Whether the Darkness a player has ({@code duration} ticks left, -1 for an infinite one) may be taken away at game
     * time {@code now} as the one an Awakening gave, which ends at game time {@code givenEnd}: it ends no later than
     * that one (with a leeway of {@value #DARKNESS_LEEWAY} ticks), so it is that one or lies within it. A longer one (a
     * warden's given again meanwhile, a command's, an infinite one) is somebody else's and stays.
     */
    public static boolean ownDarkness(int duration, long givenEnd, long now) {
        return duration >= 0 && duration <= givenEnd - now + DARKNESS_LEEWAY;
    }

    /**
     * Whether a rooted player (or the vehicle it is kept on) at {@code position} has strayed from the {@code anchor}:
     * farther than {@code tolerance} from it sideways, more than that above it, or, if it may not drop
     * ({@link #mayDrop}), more than that below it. Sinking and falling onto a floor are not straying.
     */
    public static boolean strayed(Vec3 anchor, Vec3 position, double tolerance, boolean mayDrop) {
        double dy = position.y() - anchor.y();
        return horizontalDistance(anchor, position) > tolerance || dy > tolerance || !mayDrop && dy < -tolerance;
    }

    /**
     * How an Awakening ends when the event in the hollow of its target ends (SPEC 9 "Исходы"): as the outcome the
     * level in there came to, if any (a victory, an escape through the edge, a defeat), whatever ended the event
     * afterwards (the target may have logged out on the way out); without one, {@link Awakening.End#HOLLOW_OVER} once
     * the target was in the hollow, else {@link Awakening.End#CANCELLED} (the swallowing failed before the move).
     *
     * @param outcome  the outcome of the event, or null
     * @param inHollow whether the Awakening had seen its target moved into the hollow (its phase was HOLLOW)
     */
    static Awakening.End afterHollow(HollowOutcome outcome, boolean inHollow) {
        if (outcome == null) {
            return inHollow ? Awakening.End.HOLLOW_OVER : Awakening.End.CANCELLED;
        }
        return switch (outcome) {
            case VICTORY -> Awakening.End.VICTORY;
            case EDGE_ESCAPE -> Awakening.End.EDGE_ESCAPED;
            case DEFEAT -> Awakening.End.DEFEAT;
        };
    }

    /**
     * Ticks from a victory until the hill the victor comes out of starts to rise ({@code awakening.emergeTicks} long,
     * shooting up over its first {@link AwakeningShape#EMERGE_RISE}): so that it is at its highest when the victor is
     * moved out of the hollow, {@code fadeTicks} ({@code hollow.fadeTicks}) after the victory. 0 if the rise takes that
     * long or longer.
     */
    public static int emergeDelay(int fadeTicks, int emergeTicks) {
        return Math.max(0, fadeTicks - (int) Math.round(AwakeningShape.EMERGE_RISE * emergeTicks));
    }

    /**
     * Damage of the ground's pull in a defeat ({@code awakening.lethal}): what a player with {@code health} and
     * {@code absorption} has, and one more. Its damage type ignores armour, enchantments and effects, so this kills;
     * a finite amount, so that nothing that scales with the damage runs away with it.
     */
    public static float pullDamage(float health, float absorption) {
        return Math.max(0, health) + Math.max(0, absorption) + 1;
    }

    /**
     * Whether a rooted player (or the vehicle it is kept on) in the block at height {@code fromY} may sink or fall:
     * the first cell at or below it that is not {@link Cell#OPEN} is a {@link Cell#FLOOR}. Into a fluid, lava or fire
     * it would be held while it drowned or burned, with nothing down to {@code minY} (the bottom of the level) it would
     * fall into the void: then it is held at its height. A fall onto a floor is no danger: a rooted player takes no
     * fall damage.
     *
     * @param column the cell at each block height of the column
     */
    public static boolean mayDrop(IntFunction<Cell> column, int fromY, int minY) {
        for (int y = fromY; y >= minY; y--) {
            Cell cell = column.apply(y);
            if (cell != Cell.OPEN) {
                return cell == Cell.FLOOR;
            }
        }
        return false;
    }
}
