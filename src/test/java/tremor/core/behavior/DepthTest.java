package tremor.core.behavior;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DepthTest {
    @Test
    void noneAtOrAboveTheShallowHeight() {
        assertEquals(0, Depth.share(40, 40, -40));
        assertEquals(0, Depth.share(120, 40, -40));
    }

    @Test
    void allAtOrBelowTheDeepHeight() {
        assertEquals(1, Depth.share(-40, 40, -40));
        assertEquals(1, Depth.share(-63, 40, -40));
    }

    @Test
    void straightLineBetween() {
        assertEquals(0.5, Depth.share(0, 40, -40), 1e-9);
        assertEquals(0.25, Depth.share(20, 40, -40), 1e-9);
    }

    @Test
    void emptyOrUpsideDownBandHasNoEffect() {
        assertEquals(0, Depth.share(-100, 0, 0));
        assertEquals(0, Depth.share(-100, -40, 40));
    }

    @Test
    void factorRunsFromOneToFull() {
        assertEquals(1, Depth.factor(1.5, 0), 1e-9);
        assertEquals(1.25, Depth.factor(1.5, 0.5), 1e-9);
        assertEquals(1.5, Depth.factor(1.5, 1), 1e-9);
        assertEquals(0.75, Depth.factor(0.5, 0.5), 1e-9); // a factor below 1 makes it calmer deep down
    }
}
