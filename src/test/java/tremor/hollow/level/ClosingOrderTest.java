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
    void theRingAtTheEdgeClosesAsSoonAsTheClosingStarts() {
        ClosingOrder order = order(0);
        assertEquals(21 * 21, order.size(), "every column of the box");
        // Nothing at the start radius (the edge): the grace.
        assertEquals(0, order.advance(9));
        assertFalse(order.closed(9, 0, 9));
        // Just under it the ring at and beyond the edge, corners included, is due: farthest first.
        int due = order.advance(8.99);
        for (int i = 0; i < order.size(); i++) {
            assertEquals(i < due, distance(order.x(i), order.z(i)) >= 8.99, "column " + i);
        }
        assertTrue(order.closed(9, 0, 8.99), "on the edge");
        assertTrue(order.closed(10, 10, 8.99), "a corner");
        assertFalse(order.closed(50, 0, -1), "outside");
        assertEquals(order.size(), order.advance(-1));
    }

    @Test
    void noColumnClosesBeforeTheRadiusLeavesTheEdge() {
        for (long seed = 0; seed < 20; seed++) {
            // The wobble would put some columns inside the edge beyond it.
            ClosingOrder order = new ClosingOrder(-10, -10, 10, 10, 0.5, 0.5, 9, 1.5, seed);
            assertEquals(0, order.advance(9), "seed " + seed);
            for (int z = -10; z <= 10; z++) {
                for (int x = -10; x <= 10; x++) {
                    assertFalse(order.closed(x, z, 9));
                }
            }
        }
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
