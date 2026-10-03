package tremor.hollow.level;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CellKeyTest {

    @Test
    void roundTripsOverTheWholeRange() {
        int[][] cells = {{0, 0, 0}, {1, 2, 3}, {-1, -1, -1}, {-30_000_000, -2048, 29_999_999},
                {33_554_431, 2047, -33_554_432}, {12345, -64, -98765}};
        for (int[] c : cells) {
            long key = CellKey.of(c[0], c[1], c[2]);
            assertEquals(c[0], CellKey.x(key));
            assertEquals(c[1], CellKey.y(key));
            assertEquals(c[2], CellKey.z(key));
        }
    }

    @Test
    void hasTheLayoutOfBlockPos() {
        // BlockPos.asLong: x in 26 bits from bit 38, z in 26 bits from bit 12, y in the low 12 bits.
        assertEquals((1L << 38) | 2 | (3L << 12), CellKey.of(1, 2, 3));
        assertEquals(-1L, CellKey.of(-1, -1, -1));
    }

    @Test
    void movesUpAndDown() {
        long key = CellKey.of(5, -3, 7);
        assertEquals(CellKey.of(5, 0, 7), CellKey.above(key, 3));
        assertEquals(CellKey.of(5, -10, 7), CellKey.above(key, -7));
    }
}
