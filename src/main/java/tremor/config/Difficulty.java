package tremor.config;

import java.util.Locale;

/**
 * How hard the mod is as a whole (the user's request, 2026-10-20): one choice over the settings of its parts, so that
 * nobody has to tune the hearing, the anger and the hollow one by one ({@code difficulty} in the common config,
 * {@code /tremor difficulty}). A level scales what the settings say, and {@link #NORMAL} leaves them as they are: a
 * value tuned by hand stays tuned, only softer or harder. Pure Java, but for {@link #current}.
 */
public enum Difficulty {
    /** For the atmosphere: it hears less, is slower and rarer, there is more time everywhere; a defeat does not kill. */
    CALM(1.5, 0.6, 0.85, 0.5, -2, 1.7, 1.5, 0.75, 0.75, 0.45, 0.5, Defeat.SPARED),
    /** As the settings say. */
    NORMAL(1, 1, 1, 1, 0, 1, 1, 1, 1, 1, 1, Defeat.AS_SET),
    /** It hears more, is angrier and faster, there is less time; a defeat always kills. */
    HARD(0.75, 1.3, 1.15, 1.5, 1, 0.7, 0.83, 1.17, 1.25, 1.4, 1.5, Defeat.LETHAL),
    /** Hardly a chance: and a defeat kills and takes everything the player carries, as a nightmare hollow does. */
    NIGHTMARE(0.6, 1.6, 1.3, 2, 2, 0.55, 0.67, 1.33, 1.5, 2, 2, Defeat.LETHAL_EMPTY);

    /** What a defeat in the hollow does to the player. */
    public enum Defeat {
        /** The player comes out alive on the bottom of the crater, whatever {@code awakening.lethal} says. */
        SPARED,
        /** As {@code awakening.lethal} says. */
        AS_SET,
        /** The player dies, whatever {@code awakening.lethal} says; the things lie on the bottom of the crater. */
        LETHAL,
        /** The player dies, and nothing is left: neither the things nor the experience. */
        LETHAL_EMPTY
    }

    private final double hearing;
    private final double anger;
    private final double speed;
    private final double spawns;
    private final int moreTremors;
    private final double buildup;
    private final double hollowTime;
    private final double nodeDistance;
    private final double closing;
    private final double ambush;
    private final double damage;
    private final Defeat defeat;

    /**
     * @param hearing      factor on the loudness it needs to hear a vibration (above 1: it hears less)
     * @param anger        factor on the anger a heard vibration adds
     * @param speed        factor on its speed in every stage
     * @param spawns       factor on the chance of a natural spawn
     * @param moreTremors  added to the most tremors a dimension holds (at least one is left)
     * @param buildup      factor on the build-up of an Awakening: the time to get out of the zone
     * @param hollowTime   factor on the time the player has inside the hollow
     * @param nodeDistance factor on how far the node lies along the way
     * @param closing      factor on how fast the walls of the hollow close in
     * @param ambush       factor on the chance to lie in wait
     * @param damage       factor on the damage of a strike
     * @param defeat       what a defeat in the hollow does
     */
    Difficulty(double hearing, double anger, double speed, double spawns, int moreTremors, double buildup,
               double hollowTime, double nodeDistance, double closing, double ambush, double damage, Defeat defeat) {
        this.hearing = hearing;
        this.anger = anger;
        this.speed = speed;
        this.spawns = spawns;
        this.moreTremors = moreTremors;
        this.buildup = buildup;
        this.hollowTime = hollowTime;
        this.nodeDistance = nodeDistance;
        this.closing = closing;
        this.ambush = ambush;
        this.damage = damage;
        this.defeat = defeat;
    }

    /** The level the common config sets. */
    public static Difficulty current() {
        return TremorConfig.COMMON.difficulty.get();
    }

    /** The loudness a vibration needs to be heard, from what {@code hearing.threshold} says. */
    public double hearingThreshold(double configured) {
        return configured * hearing;
    }

    /** The anger a heard vibration adds, from what the settings make of it. */
    public double anger(double configured) {
        return configured * anger;
    }

    /** Factor on its speed, in every stage. */
    public double speed() {
        return speed;
    }

    /** The chance of a natural spawn, from what {@code spawn.baseChance} says. */
    public double spawnChance(double configured) {
        return Math.min(1, configured * spawns);
    }

    /** The most tremors a dimension holds, from what {@code spawn.maxEntities} says; at least one. */
    public int maxTremors(int configured) {
        return Math.max(1, configured + moreTremors);
    }

    /** The build-up of an Awakening in ticks, from {@code awakening.buildupSeconds}; at least a second. */
    public int buildupTicks(int seconds) {
        return Math.max(20, (int) Math.round(seconds * 20 * buildup));
    }

    /** The time inside the hollow (seconds), from {@code hollow.level.breathSeconds}; 0 (no limit) stays 0. */
    public double hollowSeconds(double configured) {
        return configured * hollowTime;
    }

    /** How far the node lies along the way (blocks), from what the settings say. */
    public double nodeDistance(double configured) {
        return configured * nodeDistance;
    }

    /** How fast the walls of the hollow close in, from {@code hollow.level.closeSpeed}. */
    public double closeSpeed(double configured) {
        return configured * closing;
    }

    /** The chance to lie in wait, from {@code ambush.chance}. */
    public double ambushChance(double configured) {
        return Math.min(1, configured * ambush);
    }

    /** The damage of a strike, from {@code behavior.contact.damage}. */
    public double damage(double configured) {
        return configured * damage;
    }

    /** Whether a defeat in the hollow kills, given what {@code awakening.lethal} says. */
    public boolean lethal(boolean configured) {
        return switch (defeat) {
            case SPARED -> false;
            case AS_SET -> configured;
            case LETHAL, LETHAL_EMPTY -> true;
        };
    }

    /** Whether a defeat in the hollow also takes everything the player carries, and the experience. */
    public boolean takesEverything() {
        return defeat == Defeat.LETHAL_EMPTY;
    }

    /** The name in commands and in the config: {@code calm}, {@code normal}, {@code hard}, {@code nightmare}. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** What the level changes, for {@code /tremor difficulty}. */
    public String summary() {
        if (this == NORMAL) {
            return "normal: everything as the settings say";
        }
        String what = switch (defeat) {
            case SPARED -> "does not kill";
            case AS_SET -> "as awakening.lethal says";
            case LETHAL -> "kills";
            case LETHAL_EMPTY -> "kills and takes everything";
        };
        return String.format(Locale.ROOT, "%s: hearing threshold x%.2f, anger x%.2f, speed x%.2f, spawns x%.2f "
                        + "(%+d tremors at most), build-up x%.2f, time in the hollow x%.2f, node x%.2f, closing x%.2f, "
                        + "ambush x%.2f, strikes x%.2f; a defeat %s", id(), hearing, anger, speed, spawns, moreTremors,
                buildup, hollowTime, nodeDistance, closing, ambush, damage, what);
    }
}
