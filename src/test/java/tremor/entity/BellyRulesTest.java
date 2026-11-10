package tremor.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class BellyRulesTest {
    @Test
    void aMobThatMattersToSomeoneIsKept() {
        assertTrue(BellyRules.keepsMob(false, true, false, false, false));
        assertTrue(BellyRules.keepsMob(false, false, true, false, false));
        assertTrue(BellyRules.keepsMob(false, false, false, true, false));
        assertTrue(BellyRules.keepsMob(false, false, false, false, true));
    }

    @Test
    void anyOtherMobIsDigested() {
        assertFalse(BellyRules.keepsMob(false, false, false, false, false));
    }

    @Test
    void neverAPlayer() {
        assertFalse(BellyRules.keepsMob(true, true, true, true, true));
    }

    @Test
    void whenFullTheOldestGoes() {
        List<Integer> entries = new ArrayList<>();
        for (int i = 0; i < BellyRules.MAX_MOBS + 3; i++) {
            BellyRules.add(entries, i, BellyRules.MAX_MOBS);
        }
        assertEquals(BellyRules.MAX_MOBS, entries.size());
        assertEquals(3, entries.get(0));
        assertEquals(BellyRules.MAX_MOBS + 2, entries.get(entries.size() - 1));
    }

    @Test
    void belowTheLimitNothingGoes() {
        List<String> entries = new ArrayList<>(List.of("a", "b"));
        BellyRules.add(entries, "c", 3);
        assertEquals(List.of("a", "b", "c"), entries);
    }
}
