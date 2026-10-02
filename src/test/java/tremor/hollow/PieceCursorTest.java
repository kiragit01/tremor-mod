package tremor.hollow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class PieceCursorTest {

    private static List<PieceCursor.Piece> all(PieceCursor cursor) {
        List<PieceCursor.Piece> pieces = new ArrayList<>();
        while (cursor.hasNext()) {
            pieces.add(cursor.next());
        }
        return pieces;
    }

    @Test
    void everyBlockOfTheBoxExactlyOnce() {
        HollowBox box = new HollowBox(-28, 13, -19, 36, 61, 45);
        PieceCursor cursor = new PieceCursor(box);
        List<PieceCursor.Piece> pieces = all(cursor);
        assertEquals(cursor.total(), pieces.size());
        assertEquals(cursor.total(), cursor.done());
        Set<Long> blocks = new HashSet<>();
        for (PieceCursor.Piece p : pieces) {
            assertEquals(p.chunkX(), p.minX() >> 4);
            assertEquals(p.chunkX(), p.maxX() >> 4);
            assertEquals(p.chunkZ(), p.minZ() >> 4);
            assertEquals(p.chunkZ(), p.maxZ() >> 4);
            assertEquals(p.sectionY(), p.minY() >> 4, "a piece never crosses a section");
            assertEquals(p.sectionY(), p.maxY() >> 4, "a piece never crosses a section");
            assertTrue(p.maxY() - p.minY() < PieceCursor.SLICE);
            for (int y = p.minY(); y <= p.maxY(); y++) {
                for (int z = p.minZ(); z <= p.maxZ(); z++) {
                    for (int x = p.minX(); x <= p.maxX(); x++) {
                        assertTrue(box.contains(x, y, z));
                        assertTrue(blocks.add(((long) x << 40) ^ ((long) y << 20) ^ (z & 0xFFFFF)), "twice " + x
                                + " " + y + " " + z);
                    }
                }
            }
        }
        assertEquals(box.volume(), blocks.size());
        assertEquals(box.volume(), pieces.stream().mapToLong(PieceCursor.Piece::volume).sum());
    }

    @Test
    void columnByColumnFromTheTopDown() {
        HollowBox box = new HollowBox(0, -3, 0, 20, 21, 5);
        List<PieceCursor.Piece> pieces = all(new PieceCursor(box, 8));
        // Two chunk columns; y -3..21 in slices of 8 aligned to the grid: 16..21, 8..15, 0..7, -3..-1.
        assertEquals(8, pieces.size());
        int[][] ys = {{16, 21}, {8, 15}, {0, 7}, {-3, -1}};
        for (int i = 0; i < pieces.size(); i++) {
            PieceCursor.Piece p = pieces.get(i);
            assertEquals(i < 4 ? 0 : 1, p.chunkX());
            assertEquals(ys[i % 4][0], p.minY());
            assertEquals(ys[i % 4][1], p.maxY());
            assertEquals(i % 4 == 0, p.firstInColumn());
            assertEquals(i % 4 == 3, p.lastInColumn());
        }
        assertEquals(1, pieces.get(0).sectionY());
        assertEquals(-1, pieces.get(3).sectionY());
        assertEquals(16, pieces.get(4).minX());
        assertEquals(20, pieces.get(4).maxX());
    }

    @Test
    void aOneSliceColumnIsFirstAndLast() {
        PieceCursor.Piece p = new PieceCursor(new HollowBox(5, 5, 5, 6, 6, 6)).next();
        assertTrue(p.firstInColumn());
        assertTrue(p.lastInColumn());
        assertEquals(8, p.volume());
    }

    @Test
    void sliceHeightMustDivideASection() {
        HollowBox box = new HollowBox(0, 0, 0, 1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> new PieceCursor(box, 3));
        assertThrows(IllegalArgumentException.class, () -> new PieceCursor(box, 0));
        assertThrows(java.util.NoSuchElementException.class, () -> {
            PieceCursor cursor = new PieceCursor(box, 16);
            cursor.next();
            cursor.next();
        });
    }

    @Test
    void workIsSpreadOverTicksByTheBudget() {
        AtomicLong clock = new AtomicLong();
        TickBudget budget = new TickBudget(clock::get);
        PieceCursor cursor = new PieceCursor(new HollowBox(0, 0, 0, 15, 63, 15), 4);
        assertEquals(16, cursor.total());
        // Every piece takes 1 ms; a tick may take 3.
        List<Integer> perTick = new ArrayList<>();
        while (cursor.hasNext()) {
            budget.start(3_000_000);
            perTick.add(cursor.run(budget, piece -> clock.addAndGet(1_000_000)));
        }
        assertEquals(List.of(3, 3, 3, 3, 3, 1), perTick);
        assertFalse(cursor.hasNext());
    }

    @Test
    void atLeastOnePiecePerTickHoweverSmallTheBudget() {
        AtomicLong clock = new AtomicLong();
        TickBudget budget = new TickBudget(clock::get);
        PieceCursor cursor = new PieceCursor(new HollowBox(0, 0, 0, 15, 15, 15), 4);
        int ticks = 0;
        while (cursor.hasNext()) {
            budget.start(0);
            assertEquals(1, cursor.run(budget, piece -> clock.addAndGet(5_000_000)));
            ticks++;
        }
        assertEquals(4, ticks);
    }

    @Test
    void anUnspentBudgetRunsTheWholeBoxInOneTick() {
        TickBudget budget = new TickBudget(() -> 0L);
        PieceCursor cursor = new PieceCursor(new HollowBox(-40, -10, -40, 40, 40, 40));
        budget.start(1);
        assertEquals(cursor.total(), cursor.run(budget, piece -> {
        }));
    }

    @Test
    void statsOfAJob() {
        WorkStats stats = new WorkStats();
        stats.addTick(2_000_000);
        stats.addTick(5_500_000);
        stats.addTick(1_000_000);
        stats.addPiece(1024, 600, 500);
        stats.addPiece(256, 0, 0);
        assertEquals(3, stats.ticks());
        assertEquals(8.5, stats.totalMillis(), 1e-9);
        assertEquals(5.5, stats.worstMillis(), 1e-9);
        assertEquals(1280, stats.blocks());
        assertEquals(600, stats.changed());
        assertEquals(500, stats.lightChecks());
        assertFalse(stats.finished());
        stats.lightDone(7);
        assertTrue(stats.finished());
        assertEquals(7, stats.lightTicks());
    }
}
