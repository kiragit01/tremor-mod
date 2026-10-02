package tremor.hearing;

/**
 * Loudness and attention rules of SPEC 7.1 / 7.3 that do not need the game (unit-tested directly).
 */
public final class SoundRules {
    /** An item hitting the ground slower than this (blocks per tick, downward) makes no vibration. */
    public static final double MIN_ITEM_SPEED = 0.15;
    /** Extra loudness of a fast item impact, reached at {@link #MIN_ITEM_SPEED} + {@link #ITEM_SPEED_RANGE}. */
    public static final double ITEM_SPEED_BONUS = 2;
    /** About a 7-block drop. */
    public static final double ITEM_SPEED_RANGE = 0.6;
    /** A new sound replaces the followed one before the cooldown is over if it is at least this much louder. */
    public static final double LOUDER = 1.5;
    /**
     * A {@code hit_ground} after a shorter drop (blocks) is not a landing: stepping down a stair or a slab (0.5), a
     * jump up onto a block (~0.25) or onto a slab (~0.75). A jump on level ground drops ~1.25 blocks and stepping off
     * a full block 1; the margin covers the float sum of the per-tick drops.
     */
    public static final double MIN_LANDING_DROP = 0.9;

    private SoundRules() {
    }

    /**
     * Whether an entity's {@code hit_ground} is heard as a landing (SPEC 7.1, "landing after a jump"): vanilla posts
     * one whenever the entity lands with any fall distance, so it comes with every half-block step down; only a drop
     * ({@code fallDistance}, still set when the event is posted) of at least {@link #MIN_LANDING_DROP} counts. Shorter
     * drops are ignored rather than heard as steps: walking down stairs would post two of them per block on top of the
     * steps of the walk, which are heard anyway.
     */
    public static boolean isLanding(double drop) {
        return drop >= MIN_LANDING_DROP;
    }

    /**
     * Loudness of a player's step or landing: 0 while sneaking (vanilla sculk ignores both from a sneaking entity,
     * {@code #minecraft:ignore_vibrations_sneaking}), {@code sprintStep} for a sprinting step, else {@code base}.
     */
    public static double playerMovement(double base, boolean landing, boolean sneaking, boolean sprinting,
                                        double sprintStep) {
        if (sneaking) {
            return 0;
        }
        return !landing && sprinting ? sprintStep : base;
    }

    /**
     * Whether a fall hurts (and is heard as a {@code fall}, not a landing): longer than the entity's safe fall
     * distance, with a positive damage multiplier (a slime block bounces with 0).
     */
    public static boolean isDamagingFall(double distance, double safeDistance, double multiplier) {
        return distance > safeDistance && multiplier > 0;
    }

    /**
     * Loudness of an item hitting the ground at {@code speed} (blocks per tick, downward): 0 up to
     * {@link #MIN_ITEM_SPEED}, then {@code base} plus up to {@link #ITEM_SPEED_BONUS} growing linearly with the speed.
     */
    public static double itemLanding(double base, double speed) {
        if (!(base > 0) || !(speed > MIN_ITEM_SPEED)) {
            return 0;
        }
        return base + ITEM_SPEED_BONUS * Math.min(1, (speed - MIN_ITEM_SPEED) / ITEM_SPEED_RANGE);
    }

    /** Loudness of a fall with fall damage (SPEC 7.1: 10 + height); 0 if the base is 0 (falls ignored). */
    public static double fall(double base, double distance) {
        return base > 0 ? base + Math.max(0, distance) : 0;
    }

    /**
     * Whether a heard sound may become the entity's target. Not following a sound: always. Following one: once
     * {@code cooldown} ticks have passed since the last retarget, or if the new sound is at least {@link #LOUDER}
     * times louder (perceived) than the followed one. A manual goto is never replaced (checked by the caller).
     */
    public static boolean mayRetarget(boolean followingSound, long ticksSinceRetarget, int cooldown, double perceived,
                                      double followedPerceived) {
        return !followingSound || ticksSinceRetarget >= cooldown || perceived >= LOUDER * followedPerceived;
    }
}
