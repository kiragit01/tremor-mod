package tremor.hollow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

class StandSpotsTest {

    @Test
    void thePlaceItselfComesFirstThenStraightUp() {
        List<StandSpots.Offset> offsets = StandSpots.offsets();
        assertEquals(new StandSpots.Offset(0, 0, 0), offsets.get(0));
        assertEquals(new StandSpots.Offset(0, 1, 0), offsets.get(1));
        // A block down costs as much as two up; up comes first among equals.
        assertEquals(new StandSpots.Offset(0, 2, 0), offsets.get(2));
        assertEquals(new StandSpots.Offset(0, -1, 0), offsets.get(3));
        assertEquals(new StandSpots.Offset(0, 3, 0), offsets.get(4));
    }

    @Test
    void everyOffsetOnceNearestFirst() {
        List<StandSpots.Offset> offsets = StandSpots.offsets();
        int side = 2 * StandSpots.RADIUS + 1;
        assertEquals(side * side * (StandSpots.DOWN + StandSpots.UP + 1), offsets.size());
        assertEquals(offsets.size(), new HashSet<>(offsets).size());
        for (int i = 1; i < offsets.size(); i++) {
            assertTrue(StandSpots.cost(offsets.get(i - 1)) <= StandSpots.cost(offsets.get(i)), "at " + i);
        }
        for (StandSpots.Offset offset : offsets) {
            assertTrue(Math.abs(offset.dx()) <= StandSpots.RADIUS && Math.abs(offset.dz()) <= StandSpots.RADIUS);
            assertTrue(offset.dy() >= -StandSpots.DOWN && offset.dy() <= StandSpots.UP);
        }
    }
}
