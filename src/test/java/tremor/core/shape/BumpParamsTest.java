package tremor.core.shape;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BumpParamsTest {
    private static final double EPS = 1e-12;

    @Test
    void defaults() {
        BumpParams p = BumpParams.defaults();
        assertEquals(2.0, p.amplitude());
        assertEquals(1.6, p.sigmaFront());
        assertEquals(2.6, p.sigmaBack());
        assertEquals(2.2, p.sigmaSide());
        assertEquals(4.0, p.trailLag());
        assertEquals(3.0, p.trailSigma());
        assertEquals(0.3, p.trailDepth());
        assertEquals(0.06, p.jitter());
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new BumpParams(Double.NaN, 1, 1, 1, 1, 1, 0.3, 0.1));
        assertThrows(IllegalArgumentException.class,
                () -> new BumpParams(Double.POSITIVE_INFINITY, 1, 1, 1, 1, 1, 0.3, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new BumpParams(1, 0, 1, 1, 1, 1, 0.3, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new BumpParams(1, 1, -1, 1, 1, 1, 0.3, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new BumpParams(1, 1, 1, 0, 1, 1, 0.3, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new BumpParams(1, 1, 1, Double.NaN, 1, 1, 0.3, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new BumpParams(1, 1, 1, 1, -0.1, 1, 0.3, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new BumpParams(1, 1, 1, 1, 1, 0, 0.3, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new BumpParams(1, 1, 1, 1, 1, 1, -0.3, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new BumpParams(1, 1, 1, 1, 1, 1, 0.3, -0.1));
        assertThrows(IllegalArgumentException.class, () -> new BumpParams(1, 1, 1, 1, 1, 1, 0.3, Double.NaN));

        // Boundary values that are allowed.
        assertDoesNotThrow(() -> new BumpParams(-2, 1, 1, 1, 0, 1, 0, 0));
        assertDoesNotThrow(() -> new BumpParams(0, 1e-3, 1e-3, 1e-3, 0, 1e-3, 0, 0));
    }

    @Test
    void influenceRadius() {
        // Defaults: main 3 * 2.6 = 7.8, trail 4 + 3 * 3 = 13.
        assertEquals(13.0, BumpParams.defaults().influenceRadius(), EPS);
        // Without a trail only the main bump counts, whatever the trail geometry.
        assertEquals(7.8, new BumpParams(2, 1.6, 2.6, 2.2, 4, 3, 0, 0).influenceRadius(), EPS);
        // Main bump dominates a short trail; the widest sigma wins.
        assertEquals(15.0, new BumpParams(2, 1, 2, 5, 1, 1, 0.3, 0).influenceRadius(), EPS);
        assertEquals(12.0, new BumpParams(2, 4, 2, 1, 0, 1, 0.3, 0).influenceRadius(), EPS);
    }

    @Test
    void withAmplitude() {
        BumpParams p = BumpParams.defaults().withAmplitude(-1.25);
        assertEquals(-1.25, p.amplitude());
        assertEquals(new BumpParams(-1.25, 1.6, 2.6, 2.2, 4.0, 3.0, 0.3, 0.06), p);
        assertThrows(IllegalArgumentException.class, () -> BumpParams.defaults().withAmplitude(Double.NaN));
    }

    @Test
    void scaled() {
        BumpParams p = BumpParams.defaults().scaled(2);
        assertEquals(2.0, p.amplitude(), EPS);
        assertEquals(3.2, p.sigmaFront(), EPS);
        assertEquals(5.2, p.sigmaBack(), EPS);
        assertEquals(4.4, p.sigmaSide(), EPS);
        assertEquals(8.0, p.trailLag(), EPS);
        assertEquals(6.0, p.trailSigma(), EPS);
        assertEquals(0.3, p.trailDepth(), EPS);
        assertEquals(0.06, p.jitter(), EPS);
        assertEquals(2 * BumpParams.defaults().influenceRadius(), p.influenceRadius(), EPS);

        assertThrows(IllegalArgumentException.class, () -> BumpParams.defaults().scaled(0));
        assertThrows(IllegalArgumentException.class, () -> BumpParams.defaults().scaled(-1));
        assertThrows(IllegalArgumentException.class, () -> BumpParams.defaults().scaled(Double.NaN));
    }
}
