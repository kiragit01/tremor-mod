package tremor.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;


/** The seam of a ledge's corner ({@link Column#seamLength}): nothing of it comes or goes in the open. */
class ColumnSeamTest {
    /** The copies of a seam, as the renderer lays them: {along the axis, out the other way} each. */
    private static List<double[]> copies(double h, double reach) {
        List<double[]> copies = new ArrayList<>();
        int stack = (int) Math.ceil(h);
        for (int k = 1; k <= stack; k++) {
            double off = Column.seamOff(h, k);
            double length = Column.seamLength(reach, h, off);
            for (int s = 0; length - s > Column.SEAM_MIN; s++) {
                copies.add(new double[]{off, length - s});
            }
        }
        return copies;
    }

    @Test
    void reachesOutAtTheFootAndNotAtAllAtTheTop() {
        assertEquals(2.5, Column.seamLength(2.5, 3, 0), 1e-12);
        assertEquals(0, Column.seamLength(2.5, 3, 3), 1e-12);
        assertEquals(0, Column.seamLength(2.5, 0, 0), 1e-12, "a column not risen has no seam");
        double before = Double.MAX_VALUE;
        for (double off = 0; off <= 3; off += 0.05) {
            double length = Column.seamLength(2.5, 3, off);
            assertTrue(length <= before + 1e-12, "shorter the higher up");
            before = length;
        }
    }

    /**
     * As a hill rises and settles, every copy of the seam is, a moment before, either right where it is (it moved a
     * little), or all but inside the stack it slides out of: none appears a block out in the open, as the steps of the
     * stair it was did at every whole block of height.
     */
    @Test
    void noCopyComesOrGoesInTheOpen() {
        double step = 1e-3, near = 0.05;
        for (double share : new double[]{1, 0.6, 0.25}) { // the face behind it as far out, or less
            for (double h = step; h < 6; h += step) {
                List<double[]> now = copies(h, share * h), next = copies(h + step, share * (h + step));
                for (double[] c : next) {
                    assertTrue(c[1] <= Column.SEAM_MIN + near || close(c, now, near),
                            "a copy appears in the open at height " + (h + step) + ": " + c[0] + " up, " + c[1] + " out");
                }
                for (double[] c : now) {
                    assertTrue(c[1] <= Column.SEAM_MIN + near || close(c, next, near),
                            "a copy vanishes in the open at height " + h + ": " + c[0] + " up, " + c[1] + " out");
                }
            }
        }
    }

    private static boolean close(double[] copy, List<double[]> others, double near) {
        for (double[] o : others) {
            if (Math.abs(o[0] - copy[0]) <= near && Math.abs(o[1] - copy[1]) <= near) {
                return true;
            }
        }
        return false;
    }
}
