package tremor.awakening;

import tremor.core.math.Vec3;

import java.util.List;
import java.util.UUID;
import java.util.function.IntFunction;

/**
 * Rules of the Awakening's real-world part (SPEC 9 phase 1) that do not need the game, unit-tested directly: the zone
 * and the escape from it, who is taken, when the Darkness comes and which Darkness is the Awakening's to take away, how
 * strong the ring of a step is, when a rooted player has strayed and whether it may drop.
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
     * A player who might be taken by an Awakening that starts by itself (SPEC 8 AWAKENING).
     *
     * @param id       the player
     * @param distance from the entity (blocks)
     * @param eligible whether the player can be taken at all: alive, in survival or adventure mode, in no event of
     *                 the hollow already
     */
    public record Candidate(UUID id, double distance, boolean eligible) {
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
     * The player an Awakening that starts by itself takes: the one the entity heard last, if that player is eligible
     * and within {@code maxDistance} (the hearing distance) of the entity; else the nearest eligible player within
     * it (the first of equally near ones). Null if there is none: the entity then keeps hunting.
     *
     * @param lastHeard the player whose vibration the entity heard last, or null
     */
    public static UUID chooseTarget(UUID lastHeard, List<Candidate> candidates, double maxDistance) {
        Candidate nearest = null;
        for (Candidate candidate : candidates) {
            if (!candidate.eligible() || !(candidate.distance() <= maxDistance)) {
                continue;
            }
            if (candidate.id().equals(lastHeard)) {
                return candidate.id();
            }
            if (nearest == null || candidate.distance() < nearest.distance()) {
                nearest = candidate;
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
