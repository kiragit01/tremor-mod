package tremor.item;

import org.junit.jupiter.api.Test;
import tremor.core.behavior.Stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensingTest {
    @Test
    void powerIsFifteenRightByAndFallsToOneAtTheRange() {
        assertEquals(15, Sensing.power(0, 32));
        assertEquals(8, Sensing.power(16, 32));
        assertEquals(1, Sensing.power(31.9, 32));
        assertEquals(0, Sensing.power(32, 32));
        assertEquals(0, Sensing.power(Double.NaN, 32));
        assertEquals(0, Sensing.power(5, 0));
    }

    @Test
    void powerNeverGrowsWithTheDistance() {
        int last = 15;
        for (double d = 0; d < 40; d += 0.5) {
            int power = Sensing.power(d, 32);
            assertTrue(power <= last);
            last = power;
        }
    }

    @Test
    void theComparatorReadsTheStage() {
        assertEquals(0, Sensing.stageSignal(null));
        assertEquals(4, Sensing.stageSignal(Stage.DORMANT));
        assertEquals(8, Sensing.stageSignal(Stage.ALERT));
        assertEquals(12, Sensing.stageSignal(Stage.HUNTING));
        assertEquals(15, Sensing.stageSignal(Stage.AWAKENING));
    }

    @Test
    void theNeedleTremblesMoreTheAngrierTheEntity() {
        assertEquals(0, Sensing.tremble(null));
        assertTrue(Sensing.tremble(Stage.DORMANT) < Sensing.tremble(Stage.ALERT));
        assertTrue(Sensing.tremble(Stage.ALERT) < Sensing.tremble(Stage.HUNTING));
        assertTrue(Sensing.tremble(Stage.HUNTING) < Sensing.tremble(Stage.AWAKENING));
    }
}
