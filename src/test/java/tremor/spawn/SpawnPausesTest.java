package tremor.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SpawnPausesTest {
    @Test
    void holdsOnlyNearWhereItHappenedAndForItsKind() {
        SpawnPauses pauses = new SpawnPauses();
        pauses.add(new SpawnPauses.Pause(SpawnPauses.Kind.AWAKENING, 100, 100, 1000));
        assertEquals(600, pauses.left(SpawnPauses.Kind.AWAKENING, 100, 500, 500, 1400, 1000), "400 blocks off");
        assertEquals(0, pauses.left(SpawnPauses.Kind.AWAKENING, 100, 700, 500, 1400, 1000), "600 blocks off");
        assertEquals(0, pauses.left(SpawnPauses.Kind.LEFT, 100, 100, 500, 1400, 1000), "another kind");
        assertEquals(0, pauses.left(SpawnPauses.Kind.AWAKENING, 100, 100, 500, 2000, 1000), "over");
    }

    @Test
    void theLongestLeftOfSeveralCounts() {
        SpawnPauses pauses = new SpawnPauses();
        pauses.add(new SpawnPauses.Pause(SpawnPauses.Kind.LEFT, 0, 0, 1000));
        pauses.add(new SpawnPauses.Pause(SpawnPauses.Kind.LEFT, 10, 0, 1500));
        assertEquals(1100, pauses.left(SpawnPauses.Kind.LEFT, 0, 0, 50, 1600, 1200));
    }

    @Test
    void oneWithoutAPlaceHoldsEverywhere() {
        SpawnPauses pauses = new SpawnPauses();
        pauses.add(new SpawnPauses.Pause(SpawnPauses.Kind.AWAKENING, Double.NaN, Double.NaN, 0));
        assertEquals(50, pauses.left(SpawnPauses.Kind.AWAKENING, 1e6, -1e6, 500, 50, 100));
    }

    @Test
    void forgetsWhatIsOverAndKeepsAFewAtMost() {
        SpawnPauses pauses = new SpawnPauses();
        pauses.add(new SpawnPauses.Pause(SpawnPauses.Kind.LEFT, 0, 0, 0));
        pauses.add(new SpawnPauses.Pause(SpawnPauses.Kind.LEFT, 0, 0, 900));
        pauses.add(new SpawnPauses.Pause(SpawnPauses.Kind.AWAKENING, 0, 0, 0));
        assertTrue(pauses.forgetOver(SpawnPauses.Kind.LEFT, 1000, 500));
        assertEquals(2, pauses.all().size());
        assertFalse(pauses.forgetOver(SpawnPauses.Kind.LEFT, 1000, 500));
        for (int i = 0; i < SpawnPauses.MAX + 5; i++) {
            pauses.add(new SpawnPauses.Pause(SpawnPauses.Kind.LEFT, i, 0, i));
        }
        assertEquals(SpawnPauses.MAX, pauses.all().size());
        assertEquals(5, pauses.all().get(0).x(), "the oldest went first");
    }
}
