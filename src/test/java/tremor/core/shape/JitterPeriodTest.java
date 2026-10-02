package tremor.core.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;
import tremor.core.math.Vec3;

/** {@link BumpShape#JITTER_PERIOD_SECONDS}: an animation clock may be reduced modulo it without any visible change. */
class JitterPeriodTest {
    @Test
    void shapeRepeatsAfterOnePeriod() {
        BumpParams params = BumpParams.defaults();
        assertTrue(params.jitter() > 0);
        BumpFrame frame = BumpFrame.of(new Vec3(10.25, 64, -7.5), Vec3.UNIT_Y, Vec3.UNIT_X);
        double period = BumpShape.JITTER_PERIOD_SECONDS;
        Random random = new Random(5);
        double largest = 0;
        for (int i = 0; i < 5000; i++) {
            double x = 10.25 + random.nextGaussian() * 2, y = 64 + random.nextGaussian(), z = -7.5 + random.nextGaussian();
            double t = random.nextDouble() * 3 * period;
            double h = BumpShape.height(params, frame, x, y, z, t);
            assertEquals(h, BumpShape.height(params, frame, x, y, z, t + period), 1e-9, "t " + t);
            assertEquals(h, BumpShape.height(params, frame, x, y, z, t % period), 1e-9, "t " + t);
            // The jitter itself does change within a period (so the test is not vacuous).
            largest = Math.max(largest, Math.abs(h - BumpShape.height(params, frame, x, y, z, t + period / 3)));
        }
        assertTrue(largest > 1e-3, "jitter amplitude " + largest);
    }
}
