package tremor.hearing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LoudnessTableTest {
    @Test
    void defaultsMatchTheSpecTable() {
        Map<String, Double> table = LoudnessTable.parse(LoudnessTable.DEFAULTS);
        assertEquals(Map.of("minecraft:step", 3.0, "minecraft:hit_ground", 8.0, "minecraft:block_destroy", 9.0,
                "minecraft:block_place", 6.0, "minecraft:projectile_land", 10.0, "minecraft:explode", 20.0,
                "minecraft:block_open", 14.0, "minecraft:block_close", 14.0), table);
        LoudnessTable.DEFAULTS.forEach(entry -> assertTrue(LoudnessTable.isValid(entry), entry));
    }

    @Test
    void onlyTheOldDefaultsUntouchedAreOutdated() {
        assertTrue(LoudnessTable.outdated(new java.util.ArrayList<>(LoudnessTable.OLD_DEFAULTS)));
        assertFalse(LoudnessTable.outdated(LoudnessTable.DEFAULTS));
        List<String> changed = new java.util.ArrayList<>(LoudnessTable.OLD_DEFAULTS);
        changed.set(0, "minecraft:step=2");
        assertFalse(LoudnessTable.outdated(changed));
        assertFalse(LoudnessTable.outdated(List.of()));
    }

    @Test
    void namespaceDefaultsToMinecraftAndSpacesAreTolerated() {
        Map<String, Double> table = LoudnessTable.parse(List.of("step=3", " mymod:big/thump = 7.5 ", "swim=.5"));
        assertEquals(Map.of("minecraft:step", 3.0, "mymod:big/thump", 7.5, "minecraft:swim", 0.5), table);
    }

    @Test
    void laterEntryWins() {
        assertEquals(Map.of("minecraft:step", 0.0),
                LoudnessTable.parse(List.of("minecraft:step=2", "step=0")));
    }

    @Test
    void invalidEntriesAreRejectedAndSkipped() {
        List<Object> bad = Arrays.asList("minecraft:step", "Minecraft:step=2", "step=-1", "step=abc", "step=1e3",
                "a:b:c=1", "=2", "step=2000", "", 5, null);
        for (Object entry : bad) {
            assertFalse(LoudnessTable.isValid(entry), String.valueOf(entry));
        }
        assertEquals(Map.of("minecraft:hit_ground", 5.0),
                LoudnessTable.parse(Arrays.asList("step=x", null, 3, "hit_ground=5")));
    }
}
