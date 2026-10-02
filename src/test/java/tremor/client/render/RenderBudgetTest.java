package tremor.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RenderBudgetTest {
    @Test
    void groundLowersSmoothlyTowardsTheCut() {
        double cut = 23;
        assertEquals(1, RenderBudget.fade(0, cut));
        assertEquals(1, RenderBudget.fade(cut - RenderBudget.SOFT_EDGE, cut));
        assertEquals(0.5, RenderBudget.fade(cut - RenderBudget.SOFT_EDGE / 2, cut), 1e-12);
        assertEquals(0, RenderBudget.fade(cut, cut), "nothing left where the budget ran out");
        assertEquals(0, RenderBudget.fade(cut + 3, cut));
        double last = 1;
        for (double d = cut - 6; d <= cut + 1; d += 0.01) {
            double f = RenderBudget.fade(d, cut);
            assertTrue(f <= last, "lowering outward at " + d);
            last = f;
        }
        // No step anywhere: neighbouring columns a block apart differ by well under the full height.
        for (double d = cut - 6; d <= cut; d += 0.01) {
            assertTrue(RenderBudget.fade(d, cut) - RenderBudget.fade(d + 1, cut) < 0.4, "at " + d);
        }
    }
}
