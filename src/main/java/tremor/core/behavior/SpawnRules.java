package tremor.core.behavior;

/**
 * Natural spawn chance (SPEC 11: "Предпочтения: пещеры, темнота, глубина, ночь (множители шанса в конфиге)"). The
 * game decides which conditions hold for a player; the chance of one spawn check is the base chance times the
 * multiplier of every condition that holds.
 */
public final class SpawnRules {
    private SpawnRules() {
    }

    /**
     * What holds for the player at a spawn check.
     *
     * @param cave  in a cave (e.g. no sky above)
     * @param dark  in the dark
     * @param deep  deep underground
     * @param night night time
     */
    public record Conditions(boolean cave, boolean dark, boolean deep, boolean night) {
    }

    /**
     * Chance multipliers of the conditions.
     *
     * @throws IllegalArgumentException if one is negative or not finite
     */
    public record Multipliers(double cave, double dark, double deep, double night) {
        public Multipliers {
            if (!valid(cave) || !valid(dark) || !valid(deep) || !valid(night)) {
                throw new IllegalArgumentException(toString());
            }
        }

        private static boolean valid(double m) {
            return m >= 0 && Double.isFinite(m);
        }
    }

    /**
     * {@code base} times the multiplier of every condition that holds, clamped to [0, 1] (NaN gives 0).
     */
    public static double chance(double base, Conditions c, Multipliers m) {
        double chance = base;
        if (c.cave()) {
            chance *= m.cave();
        }
        if (c.dark()) {
            chance *= m.dark();
        }
        if (c.deep()) {
            chance *= m.deep();
        }
        if (c.night()) {
            chance *= m.night();
        }
        return chance > 0 ? Math.min(chance, 1) : 0;
    }
}
