package tremor.hollow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tremor.hollow.PlayerBlocks.Change;
import tremor.hollow.PlayerBlocks.Outcome;
import tremor.hollow.PlayerBlocks.Replaced;

class PlayerBlocksTest {

    @Test
    void placingInTheOwnEventOverAirOrTheOwnBlocks() {
        assertTrue(PlayerBlocks.mayPlace(true, false, Replaced.NOTHING));
        assertTrue(PlayerBlocks.mayPlace(true, false, Replaced.PLAYERS));
    }

    @Test
    void noPlacingIntoTheCopyOutsideTheEventOrOfUnplaceableBlocks() {
        assertFalse(PlayerBlocks.mayPlace(true, false, Replaced.COPY));
        assertFalse(PlayerBlocks.mayPlace(false, false, Replaced.NOTHING));
        assertFalse(PlayerBlocks.mayPlace(false, false, Replaced.PLAYERS));
        assertFalse(PlayerBlocks.mayPlace(true, true, Replaced.NOTHING));
        assertFalse(PlayerBlocks.mayPlace(true, true, Replaced.PLAYERS));
    }

    @Test
    void aPlacedBlockStaysThePlayersAndWhatItBecameToo() {
        // Placed and still there, or turned into something of the player's (a sponge soaking, powder to concrete).
        assertEquals(Outcome.MARK, PlayerBlocks.afterTick(Change.PLACED, true, false, true, false));
    }

    @Test
    void aPlacingPutBackOrGoneAtOnceIsNotThePlayers() {
        // Another mod cancelled after the record: the copy is back as it was.
        assertEquals(Outcome.UNMARK, PlayerBlocks.afterTick(Change.PLACED, true, true, true, false));
        // Gone within the tick (TNT that primed at once, fire from flint and steel).
        assertEquals(Outcome.UNMARK, PlayerBlocks.afterTick(Change.PLACED, true, false, false, false));
    }

    @Test
    void whatAUseChangedIsThePlayers() {
        // A bucket into air, a replaceable copy, or the player's own slab it waterlogs; lava that turned to obsidian;
        // a lily pad on water.
        assertEquals(Outcome.MARK, PlayerBlocks.afterTick(Change.USED, false, false, true, false));
        assertEquals(Outcome.MARK, PlayerBlocks.afterTick(Change.USED, true, false, true, false));
    }

    @Test
    void aPourIntoTheSameFluidOfTheCopyMakesThatSourceThePlayers() {
        // The bucket is empty now; the source it went into can be taken back.
        assertEquals(Outcome.MARK, PlayerBlocks.afterTick(Change.USED, false, true, true, true));
    }

    @Test
    void aUseThatDidNothingChangesNothing() {
        assertEquals(Outcome.NONE, PlayerBlocks.afterTick(Change.USED, false, true, true, false));
        // Aimed at a block of the player it could not pour into: the block stays the player's.
        assertEquals(Outcome.MARK, PlayerBlocks.afterTick(Change.USED, true, true, true, false));
        // Ran off at once (flowing water is not a block of anyone).
        assertEquals(Outcome.NONE, PlayerBlocks.afterTick(Change.USED, false, false, false, false));
    }

    @Test
    void aChangedBlockOfThePlayerIsFollowed() {
        // Grown, oxidized, waterlogged: still the player's, recorded as what it is now.
        assertEquals(Outcome.MARK, PlayerBlocks.afterTick(Change.CHANGED, true, false, true, false));
        // Broken, burnt, fallen, picked up, washed away, pushed out by a piston.
        assertEquals(Outcome.UNMARK, PlayerBlocks.afterTick(Change.CHANGED, true, false, false, false));
        // Given back meanwhile (the event ended): nothing to follow.
        assertEquals(Outcome.NONE, PlayerBlocks.afterTick(Change.CHANGED, false, false, true, false));
        assertEquals(Outcome.NONE, PlayerBlocks.afterTick(Change.CHANGED, false, false, false, false));
    }
}
