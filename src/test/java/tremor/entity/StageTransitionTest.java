package tremor.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tremor.core.behavior.Stage;

class StageTransitionTest {
    @Test
    void noChangeNoSound() {
        for (Stage stage : Stage.values()) {
            assertEquals(StageTransition.NONE, StageTransition.of(stage, stage));
        }
    }

    @Test
    void aRiseSoundsLikeTheStageItReaches() {
        assertEquals(StageTransition.RUMBLE, StageTransition.of(Stage.DORMANT, Stage.ALERT));
        assertEquals(StageTransition.CRACK, StageTransition.of(Stage.ALERT, Stage.HUNTING));
        assertEquals(StageTransition.CRACK, StageTransition.of(Stage.DORMANT, Stage.HUNTING));
        assertEquals(StageTransition.AWAKEN, StageTransition.of(Stage.HUNTING, Stage.AWAKENING));
        assertEquals(StageTransition.AWAKEN, StageTransition.of(Stage.ALERT, Stage.AWAKENING));
        assertEquals(StageTransition.AWAKEN, StageTransition.of(Stage.DORMANT, Stage.AWAKENING));
    }

    @Test
    void everyDropSighs() {
        Stage[] stages = Stage.values();
        for (int from = 1; from < stages.length; from++) {
            for (int to = 0; to < from; to++) {
                assertEquals(StageTransition.SIGH, StageTransition.of(stages[from], stages[to]));
            }
        }
    }
}
