package tremor.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeathDropsTest {
    /** A minute: {@code contact.spareDeathDropsSeconds} set to 60. */
    private static final long MINUTE = 1200;
    private static final long DIED = 5_000_000;

    @Test
    void theThingsAreLeftFromTheTickOfTheDeathToTheLastTickOfTheMinute() {
        assertTrue(DeathDrops.spared(DIED, DIED, MINUTE));
        assertTrue(DeathDrops.spared(DIED, DIED + 1199, MINUTE));
    }

    @Test
    void afterTheMinuteTheyAreItemsLikeAnyOther() {
        assertFalse(DeathDrops.spared(DIED, DIED + 1200, MINUTE));
        assertFalse(DeathDrops.spared(DIED, DIED + 100_000, MINUTE));
    }

    @Test
    void aDeathInTheFutureSparesNothing() {
        // The world's time was set back: the item would be left for ever otherwise.
        assertFalse(DeathDrops.spared(DIED, DIED - 1, MINUTE));
        assertFalse(DeathDrops.spared(DIED, 0, MINUTE));
    }

    @Test
    void withNoTimeSetNothingIsEverLeft() {
        // The default of the setting: as it was before there was one.
        assertFalse(DeathDrops.spared(DIED, DIED, 0));
        assertFalse(DeathDrops.spared(DIED, DIED + 1, 0));
        assertFalse(DeathDrops.spared(DIED, DIED - 1, 0));
    }
}
