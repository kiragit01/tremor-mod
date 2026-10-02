package tremor.hollow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SlotLayoutTest {

    @Test
    void theFirstSlotIsInTheMiddle() {
        assertEquals(0, SlotLayout.chunkX(0));
        assertEquals(0, SlotLayout.chunkZ(0));
    }

    @Test
    void theSpiralFillsSquareAfterSquare() {
        Set<SlotLayout.Cell> seen = new HashSet<>();
        for (int ring = 0; ring <= SlotLayout.RINGS; ring++) {
            int from = ring == 0 ? 0 : (2 * ring - 1) * (2 * ring - 1);
            int to = (2 * ring + 1) * (2 * ring + 1);
            for (int index = from; index < to; index++) {
                SlotLayout.Cell cell = SlotLayout.cell(index);
                assertEquals(ring, Math.max(Math.abs(cell.x()), Math.abs(cell.z())), "slot " + index);
                assertTrue(seen.add(cell), "slot " + index + " repeats " + cell);
            }
            // Every cell of the square of this ring is taken.
            assertEquals((2 * ring + 1) * (2 * ring + 1), seen.size());
        }
        assertEquals(SlotLayout.MAX_SLOTS, seen.size());
    }

    @Test
    void neighbouringSlotsTouch() {
        for (int index = 1; index < SlotLayout.MAX_SLOTS; index++) {
            SlotLayout.Cell a = SlotLayout.cell(index - 1);
            SlotLayout.Cell b = SlotLayout.cell(index);
            assertTrue(Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z())) == 1, "slots " + (index - 1)
                    + " and " + index + ": " + a + " " + b);
        }
    }

    @Test
    void slotsAreFarApart() {
        assertEquals(SlotLayout.SPACING_CHUNKS, SlotLayout.chunkX(1));
        assertEquals(0, SlotLayout.chunkZ(1));
        for (int index = 0; index < 49; index++) {
            assertEquals(0, SlotLayout.chunkX(index) % SlotLayout.SPACING_CHUNKS);
            assertEquals(0, SlotLayout.chunkZ(index) % SlotLayout.SPACING_CHUNKS);
        }
    }

    @Test
    void theLowestFreeSlotThatFits() {
        assertEquals(0, SlotLayout.lowestFree(slot -> false, slot -> true));
        assertEquals(2, SlotLayout.lowestFree(slot -> slot < 2, slot -> true));
        // Slots are reused: a freed one comes first again.
        assertEquals(1, SlotLayout.lowestFree(slot -> slot != 1 && slot < 5, slot -> true));
        // Only the middle fits (a small world border), and it is taken.
        assertEquals(-1, SlotLayout.lowestFree(slot -> slot == 0, slot -> slot == 0));
        assertEquals(7, SlotLayout.lowestFree(slot -> false, slot -> slot == 7));
    }

    @Test
    void theMiddleIsTheChunkOfTheBorderCentre() {
        assertEquals(0, SlotLayout.middleChunk(0.0));
        assertEquals(0, SlotLayout.middleChunk(15.9));
        assertEquals(1, SlotLayout.middleChunk(16.0));
        assertEquals(-1, SlotLayout.middleChunk(-0.5));
        assertEquals(-1, SlotLayout.middleChunk(-16.0));
        assertEquals(1875, SlotLayout.middleChunk(30000.0));
    }

    @Test
    void negativeSlotsDoNotExist() {
        assertThrows(IllegalArgumentException.class, () -> SlotLayout.cell(-1));
    }
}
