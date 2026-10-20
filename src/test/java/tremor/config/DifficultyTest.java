package tremor.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DifficultyTest {
    @Test
    void normalLeavesTheSettingsAsTheyAre() {
        Difficulty d = Difficulty.NORMAL;
        assertEquals(0.3, d.hearingThreshold(0.3), 1e-12);
        assertEquals(6, d.anger(6), 1e-12);
        assertEquals(1, d.speed(), 1e-12);
        assertEquals(0.04, d.spawnChance(0.04), 1e-12);
        assertEquals(4, d.maxTremors(4));
        assertEquals(140, d.buildupTicks(7));
        assertEquals(60, d.hollowSeconds(60), 1e-12);
        assertEquals(0.35, d.ambushChance(0.35), 1e-12);
        assertEquals(4, d.damage(4), 1e-12);
        assertTrue(d.lethal(true));
        assertFalse(d.lethal(false));
        assertFalse(d.takesEverything());
    }

    @Test
    void theLevelsGoFromSoftToHard() {
        Difficulty[] levels = Difficulty.values();
        for (int i = 1; i < levels.length; i++) {
            Difficulty softer = levels[i - 1], harder = levels[i];
            assertTrue(harder.hearingThreshold(1) < softer.hearingThreshold(1), harder + " hears more");
            assertTrue(harder.anger(1) > softer.anger(1));
            assertTrue(harder.speed() > softer.speed());
            assertTrue(harder.buildupTicks(7) < softer.buildupTicks(7), harder + " gives less time to get away");
            assertTrue(harder.hollowSeconds(60) < softer.hollowSeconds(60));
            assertTrue(harder.ambushChance(0.35) > softer.ambushChance(0.35));
        }
    }

    @Test
    void defeatsAndLimits() {
        assertFalse(Difficulty.CALM.lethal(true), "calm spares the player");
        assertTrue(Difficulty.HARD.lethal(false), "hard kills whatever the setting says");
        assertTrue(Difficulty.NIGHTMARE.lethal(false) && Difficulty.NIGHTMARE.takesEverything());
        assertEquals(1, Difficulty.CALM.maxTremors(1), "at least one tremor");
        assertEquals(20, Difficulty.NIGHTMARE.buildupTicks(1), "at least a second of build-up");
        assertEquals(1, Difficulty.NIGHTMARE.ambushChance(0.8), 1e-12, "a chance stays a chance");
        assertEquals(0, Difficulty.HARD.hollowSeconds(0), 1e-12, "no limit stays no limit");
    }
}
