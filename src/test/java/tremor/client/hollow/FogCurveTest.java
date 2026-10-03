package tremor.client.hollow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import tremor.core.shape.HollowParams;

class FogCurveTest {
    @Test
    void aBlackFogAFewBlocksDeep() {
        // The default: from about half a block to about 5.5 blocks.
        double far = FogCurve.far(5.5, 0);
        assertEquals(5.5, far, 1e-12);
        assertEquals(0.5, FogCurve.near(far), 1e-12);
        assertTrue(FogCurve.near(far) < far);
        assertEquals(0, FogCurve.near(0));
    }

    @Test
    void aThickerFogIsKeptButNotTheWatersStartBehindTheEye() {
        double far = 5.5;
        // Air: vanilla's fog starts far out; the hollow's own start.
        assertEquals(FogCurve.near(far), FogCurve.near(far, 250), 1e-12);
        // Water starts 8 blocks behind the eye: the hollow's own start, as in the air.
        assertEquals(FogCurve.near(far), FogCurve.near(far, -8), 1e-12);
        assertEquals(FogCurve.near(far), FogCurve.near(far, Double.NaN), 1e-12);
        // Powder snow, lava and blindness start nearer: kept.
        assertEquals(0, FogCurve.near(far, 0));
        assertEquals(0.25, FogCurve.near(far, 0.25), 1e-12);
        // A fog that ends sooner (lava: 0.25 to 1 block) ends the hollow's too (the caller's minimum); its start is
        // then beyond the hollow's own, which stays, short of the end.
        assertEquals(FogCurve.near(1), FogCurve.near(1, 0.25), 1e-12);
        assertTrue(FogCurve.near(1, 0.25) < 1);
    }

    @Test
    void theFogClosesInALittleAsTheHollowCloses() {
        assertEquals(5.5 * FogCurve.CLOSED_SHARE, FogCurve.far(5.5, 1), 1e-12);
        assertEquals(5.5 * FogCurve.CLOSED_SHARE, FogCurve.far(5.5, 3), 1e-12, "not beyond closed");
        assertEquals(5.5, FogCurve.far(5.5, -1), 1e-12);
        assertEquals(5.5, FogCurve.far(5.5, Double.NaN), 1e-12, "as before the closing while unknown");
        double last = Double.MAX_VALUE;
        for (double c = 0; c <= 1; c += 0.05) {
            double far = FogCurve.far(5.5, c);
            assertTrue(far <= last && far >= 4, "closing in at " + c + ": " + far);
            last = far;
        }
        assertEquals((5.5 + 5.5 * FogCurve.CLOSED_SHARE) / 2, FogCurve.far(5.5, 0.5), 1e-12);
    }

    @Test
    void theGroundIsDrawnJustBeyondTheFog() {
        double reach = FogCurve.reach(5.5);
        // The defaults of the hollow's ground are made for the default fog.
        assertEquals(HollowParams.defaults().ringRadius(), reach, 1e-12);
        // The rings show in full wherever the player sees, before the fog closes in as well as after.
        HollowParams p = HollowParams.defaults().withReach(reach);
        assertTrue(p.ringRadius() - p.ringFade() >= FogCurve.far(5.5, 0));
        assertTrue(p.breathRadius() - p.breathFade() >= FogCurve.far(5.5, 0));
        // Bounded both ways, and steady while the hollow closes (it depends on the configured fog only).
        assertEquals(FogCurve.MIN_REACH, FogCurve.reach(1));
        assertEquals(FogCurve.MAX_REACH, FogCurve.reach(32));
        assertEquals(FogCurve.MIN_REACH, FogCurve.reach(Double.NaN));
        assertEquals(12 + FogCurve.REACH_MARGIN, FogCurve.reach(12), 1e-12);
    }
}
