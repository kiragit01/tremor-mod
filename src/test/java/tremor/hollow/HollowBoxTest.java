package tremor.hollow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HollowBoxTest {

    @Test
    void theDefaultBoxAroundThePlayer() {
        HollowBox box = HollowBox.around(4, 40, 13, 32, 24, 24, -64, 320);
        assertEquals(new HollowBox(-28, 16, -19, 36, 64, 45), box);
        assertEquals(65, box.sizeX());
        assertEquals(49, box.sizeY());
        assertEquals(65, box.sizeZ());
        assertEquals(65L * 49 * 65, box.volume());
        assertTrue(box.contains(4, 40, 13));
        assertTrue(box.contains(-28, 16, 45));
        assertFalse(box.contains(-29, 40, 13));
        assertFalse(box.contains(4, 65, 13));
    }

    @Test
    void isCutToTheBuildHeight() {
        assertEquals(new HollowBox(-32, -64, -32, 32, -36, 32), HollowBox.around(0, -60, 0, 32, 24, 24, -64, 320));
        assertEquals(new HollowBox(-32, 295, -32, 32, 319, 32), HollowBox.around(0, 319, 0, 32, 24, 24, -64, 320));
        // A smaller world (the Nether's 0..256) cuts more.
        assertEquals(new HollowBox(-8, 0, -8, 8, 30, 8), HollowBox.around(0, 6, 0, 8, 24, 24, 0, 256));
    }

    @Test
    void nothingOutsideTheBuildHeight() {
        assertNull(HollowBox.around(0, 320, 0, 32, 24, 24, -64, 320));
        assertNull(HollowBox.around(0, -65, 0, 32, 24, 24, -64, 320));
    }

    @Test
    void emptyBoxesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new HollowBox(1, 0, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new HollowBox(0, 5, 0, 0, 4, 0));
    }

    @Test
    void chunksOfTheBox() {
        HollowBox box = new HollowBox(-28, 16, -19, 36, 64, 45);
        assertEquals(-2, box.minChunkX());
        assertEquals(2, box.maxChunkX());
        assertEquals(-2, box.minChunkZ());
        assertEquals(2, box.maxChunkZ());
        // A radius of 32 always covers 5 chunks, wherever in its chunk the player stands.
        for (int x = 0; x < 16; x++) {
            HollowBox around = HollowBox.around(x, 0, x, 32, 4, 4, -64, 320);
            assertEquals(4, around.maxChunkX() - around.minChunkX());
            assertEquals(2, around.chunkDistance(0, 0));
        }
    }

    @Test
    void offsetKeepsTheShape() {
        HollowBox box = new HollowBox(-28, 16, -19, 36, 64, 45);
        HollowBox moved = box.offset(1024, 0, -2048);
        assertEquals(new HollowBox(996, 16, -2067, 1060, 64, -2003), moved);
        assertEquals(box.volume(), moved.volume());
        assertEquals(box.minChunkX() + 64, moved.minChunkX());
        assertEquals(box.maxChunkZ() - 128, moved.maxChunkZ());
    }

    @Test
    void growingStaysInsideTheBuildHeight() {
        HollowBox box = new HollowBox(-28, 16, -19, 36, 64, 45);
        assertEquals(new HollowBox(-29, 15, -20, 37, 65, 46), box.grow(1, -64, 320));
        assertEquals(new HollowBox(-29, -64, -20, 37, 319, 46),
                new HollowBox(-28, -64, -19, 36, 319, 45).grow(1, -64, 320));
        // The shell of a box at an edge of a chunk reaches into the next chunk: the margin of the slot covers it.
        HollowBox edge = new HollowBox(-32, 0, -32, 32, 10, 32);
        assertEquals(-3, edge.grow(1, -64, 320).minChunkX());
        assertTrue(edge.chunkColumns(HollowEvent.MARGIN_CHUNKS, -64, 320).minChunkX()
                <= edge.grow(1, -64, 320).minChunkX());
    }

    @Test
    void chunkColumnsWithAMargin() {
        HollowBox box = new HollowBox(-28, 16, -19, 36, 64, 45);
        HollowBox area = box.chunkColumns(1, -64, 320);
        assertEquals(new HollowBox(-48, -64, -48, 63, 319, 63), area);
        assertEquals(-3, area.minChunkX());
        assertEquals(3, area.maxChunkX());
        assertEquals(3, area.chunkDistance(0, 0));
        assertEquals(new HollowBox(-32, -64, -32, 47, 319, 47), box.chunkColumns(0, -64, 320));
    }

    @Test
    void intersectingBoxesShareABlock() {
        HollowBox box = new HollowBox(0, 0, 0, 15, 15, 15);
        assertTrue(box.intersects(box));
        assertTrue(box.intersects(new HollowBox(15, 15, 15, 20, 20, 20)));
        assertTrue(box.intersects(new HollowBox(-5, 3, -5, 30, 4, 30)));
        assertTrue(new HollowBox(-5, 3, -5, 30, 4, 30).intersects(box));
        assertFalse(box.intersects(new HollowBox(16, 0, 0, 20, 15, 15)));
        assertFalse(box.intersects(new HollowBox(0, -10, 0, 15, -1, 15)));
        assertFalse(box.intersects(new HollowBox(0, 0, 16, 15, 15, 31)));
    }

    @Test
    void theSlotIsOneChunkMoreThanWhereThePlayerMayBuild() {
        HollowBox box = new HollowBox(-28, 16, -19, 36, 64, 45);
        HollowBox area = box.chunkColumns(HollowEvent.MARGIN_CHUNKS, -64, 320);
        HollowBox slot = box.chunkColumns(HollowEvent.SLOT_MARGIN_CHUNKS, -64, 320);
        assertEquals(area.minChunkX() - 1, slot.minChunkX());
        assertEquals(area.maxChunkZ() + 1, slot.maxChunkZ());
        assertEquals(new HollowBox(-64, -64, -64, 79, 319, 79), slot);
        // A slot fits next to its neighbour on the layout even for the largest radius (96).
        HollowBox largest = new HollowBox(-96, 0, -96, 96, 10, 96).chunkColumns(HollowEvent.SLOT_MARGIN_CHUNKS, -64,
                320);
        assertFalse(largest.intersects(largest.offset(SlotLayout.SPACING_CHUNKS << 4, 0, 0)));
    }

    @Test
    void chunkDistanceIsToTheFarthestChunk() {
        HollowBox box = new HollowBox(0, 0, 0, 47, 0, 15);
        assertEquals(2, box.chunkDistance(0, 0));
        assertEquals(1, box.chunkDistance(1, 0));
        assertEquals(5, box.chunkDistance(-3, 0));
        assertEquals(4, box.chunkDistance(1, 4));
    }
}
