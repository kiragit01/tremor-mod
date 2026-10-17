package tremor.core.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class AmbushShapeTest {
    @Test
    void lyingInWaitTheBumpIsRoundWideAndLeavesNoDip() {
        BumpParams d = BumpParams.defaults();
        assertSame(d, d.lurking(0, 1.6));
        BumpParams l = d.lurking(1, 1.6);
        double round = Math.max(d.sigmaFront(), Math.max(d.sigmaBack(), d.sigmaSide())) * 1.6;
        assertEquals(round, l.sigmaFront(), 1e-9);
        assertEquals(round, l.sigmaBack(), 1e-9);
        assertEquals(round, l.sigmaSide(), 1e-9);
        assertEquals(0, l.trailDepth(), 1e-9);
        assertEquals(d.amplitude(), l.amplitude(), 1e-9);
        BumpParams half = d.lurking(0.5, 1.6);
        assertEquals((d.sigmaSide() + round) / 2, half.sigmaSide(), 1e-9);
        assertEquals(d.trailDepth() / 2, half.trailDepth(), 1e-9);
    }
}
