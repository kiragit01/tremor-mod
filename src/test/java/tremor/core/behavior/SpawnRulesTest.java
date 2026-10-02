package tremor.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SpawnRulesTest {
    private static final SpawnRules.Multipliers M = new SpawnRules.Multipliers(2, 1.5, 3, 4);

    private static SpawnRules.Conditions c(boolean cave, boolean dark, boolean deep, boolean night) {
        return new SpawnRules.Conditions(cave, dark, deep, night);
    }

    @Test
    void baseTimesTheMultiplierOfEveryConditionThatHolds() {
        assertEquals(0.01, SpawnRules.chance(0.01, c(false, false, false, false), M), 1e-15);
        assertEquals(0.02, SpawnRules.chance(0.01, c(true, false, false, false), M), 1e-15);
        assertEquals(0.015, SpawnRules.chance(0.01, c(false, true, false, false), M), 1e-15);
        assertEquals(0.03, SpawnRules.chance(0.01, c(false, false, true, false), M), 1e-15);
        assertEquals(0.04, SpawnRules.chance(0.01, c(false, false, false, true), M), 1e-15);
        assertEquals(0.01 * 2 * 3, SpawnRules.chance(0.01, c(true, false, true, false), M), 1e-15);
        assertEquals(0.01 * 2 * 1.5 * 3 * 4, SpawnRules.chance(0.01, c(true, true, true, true), M), 1e-15);
    }

    @Test
    void clampedToAProbability() {
        assertEquals(1, SpawnRules.chance(0.2, c(true, true, true, true), M));
        assertEquals(1, SpawnRules.chance(3, c(false, false, false, false), M));
        assertEquals(0, SpawnRules.chance(-0.5, c(true, false, false, false), M));
        assertEquals(0, SpawnRules.chance(Double.NaN, c(true, false, false, false), M));
        // A zero multiplier rules the condition out.
        SpawnRules.Multipliers noNight = new SpawnRules.Multipliers(1, 1, 1, 0);
        assertEquals(0, SpawnRules.chance(0.5, c(false, false, false, true), noNight));
        assertEquals(0.5, SpawnRules.chance(0.5, c(true, true, true, false), noNight));
    }

    @Test
    void multipliersAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> new SpawnRules.Multipliers(-1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SpawnRules.Multipliers(1, Double.NaN, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new SpawnRules.Multipliers(1, 1, Double.POSITIVE_INFINITY, 1));
    }
}
