package tremor.hearing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FallSlotTest {
    private static final int PIG = 7;
    private static final int OTHER = 9;

    @Test
    void emptySlotMatchesNothing() {
        FallSlot slot = new FallSlot();
        assertFalse(slot.isMarked(0, 0));
        assertFalse(slot.consume(0, 0));
        assertFalse(slot.isMarked(0, Long.MIN_VALUE));
    }

    @Test
    void ridersShareAndHitGroundOfAHeardFallAreDropped() {
        FallSlot slot = new FallSlot();
        slot.mark(PIG, 100); // the pig's own fall event
        assertTrue(slot.isMarked(PIG, 100)); // its rider's forwarded fall: same root, same tick
        assertTrue(slot.isMarked(PIG, 100)); // a second rider
        assertTrue(slot.consume(PIG, 100)); // the pig's hit_ground
        assertFalse(slot.isMarked(PIG, 100)); // used up
        assertFalse(slot.consume(PIG, 100));
    }

    @Test
    void otherLandingsAreNotMatched() {
        FallSlot slot = new FallSlot();
        slot.mark(PIG, 100);
        assertFalse(slot.consume(OTHER, 100)); // someone else landing in the same tick
        assertFalse(slot.consume(PIG, 101)); // the pig landing again later
        assertTrue(slot.consume(PIG, 100));
    }
}
