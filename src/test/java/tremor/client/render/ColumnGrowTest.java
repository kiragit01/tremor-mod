package tremor.client.render;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ColumnGrowTest {
    private static final int[][] SIDES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    /** Two growths closer than this would put their faces in one plane as the depth buffer sees it. */
    private static final float APART = 0.2f * Column.GROW_MIN;

    @Test
    void copiesSideBySideOrOneOnTheOtherNeverGrowAlike() {
        for (int x = -6; x <= 6; x++) {
            for (int y = -6; y <= 6; y++) {
                for (int z = -6; z <= 6; z++) {
                    float g = Column.grow(x, y, z);
                    for (int k = 0; k < 4; k++) {
                        float a = Column.stackGrow(g, k);
                        assertTrue(a > 0 && a <= VertexList.INSET_HALF, "within the inside faces' inset: " + a);
                        assertTrue(Math.abs(a - Column.stackGrow(g, k + 1)) >= APART, "a copy and the one on it");
                        for (int[] s : SIDES) {
                            float n = Column.grow(x + s[0], y + s[1], z + s[2]);
                            for (int j = 0; j < 4; j++) {
                                assertTrue(Math.abs(a - Column.stackGrow(n, j)) >= APART,
                                        "copies of cells side by side at " + x + " " + y + " " + z);
                            }
                        }
                    }
                }
            }
        }
    }
}
