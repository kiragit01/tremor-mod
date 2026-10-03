package tremor.hollow.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ClosingOrderTest {

    /** The copy of a box of radius 10 around block 0, 0: centre 0.5, 0.5, edge 9. */
    private static ClosingOrder order(double wobble) {
        return new ClosingOrder(-10, -10, 10, 10, 0.5, 0.5, 9, wobble, 77);
    }

    private static double distance(int x, int z) {
        return Math.hypot(x, z);
    }

    @Test
    void columnsAtOrBeyondTheEdgeAreLeftOut() {
        ClosingOrder order = order(0);
        int inside = 0;
        for (int z = -10; z <= 10; z++) {
            for (int x = -10; x <= 10; x++) {
                if (distance(x, z) < 9) {
                    inside++;
                }
            }
        }
        assertEquals(inside, order.size());
        assertEquals(order.size(), order.advance(-1));
        assertFalse(order.closed(9, 0, -1), "on the edge");
        assertFalse(order.closed(10, 10, -1), "a corner");
        assertFalse(order.closed(50, 0, -1), "outside");
    }

    @Test
    void withoutWobbleTheFarthestCloseFirst() {
        ClosingOrder order = order(0);
        for (int i = 1; i < order.size(); i++) {
            assertTrue(distance(order.x(i - 1), order.z(i - 1)) >= distance(order.x(i), order.z(i)) - 1e-6);
        }
        assertEquals(0, order.advance(9));
        int due = order.advance(6);
        for (int i = 0; i < order.size(); i++) {
            assertEquals(i < due, distance(order.x(i), order.z(i)) > 6, "column " + i);
            assertEquals(i < due, order.closed(order.x(i), order.z(i), 6));
        }
        assertEquals(due, order.due());
        assertEquals(due, order.advance(7), "the radius never grows back");
    }

    @Test
    void theWobbleMovesTheFrontByAtMostItsAmount() {
        ClosingOrder order = order(1.5);
        int due = order.advance(5);
        for (int i = 0; i < order.size(); i++) {
            double d = distance(order.x(i), order.z(i));
            if (i < due) {
                assertTrue(d > 5 - 1.5, "closed too early at " + d);
            } else {
                assertTrue(d <= 5 + 1.5, "left open at " + d);
            }
        }
        // ...and it is uneven: some columns at the same distance close and others do not.
        boolean closed = false;
        boolean open = false;
        for (int i = 0; i < order.size(); i++) {
            double d = distance(order.x(i), order.z(i));
            if (Math.abs(d - 5) < 0.6) {
                closed |= i < due;
                open |= i >= due;
            }
        }
        assertTrue(closed && open);
    }
}
