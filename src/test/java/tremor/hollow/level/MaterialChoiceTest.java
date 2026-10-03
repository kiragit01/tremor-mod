package tremor.hollow.level;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tremor.hollow.level.MaterialChoice.Kind;

import java.util.ArrayList;
import java.util.List;

class MaterialChoiceTest {
    /** The choice among {@code sides} in that order, and the sides it looked at. */
    private static int choose(List<Integer> asked, Kind... sides) {
        return MaterialChoice.choose(sides.length, i -> {
            asked.add(i);
            return sides[i];
        });
    }

    private static int choose(Kind... sides) {
        return choose(new ArrayList<>(), sides);
    }

    @Test
    void aFloorLaidOverMagmaIsNotMagmaAgain() {
        // The magma field of a flooded cave: magma under the cell to fill (tried first), stone beside it.
        assertEquals(1, choose(Kind.OTHER, Kind.COPY, Kind.OTHER, Kind.OPEN, Kind.OPEN, Kind.OPEN));
        // Magma all around: stone, not magma.
        assertEquals(MaterialChoice.STONE, choose(Kind.OTHER, Kind.OTHER, Kind.OPEN, Kind.OTHER, Kind.OPEN,
                Kind.OPEN));
    }

    @Test
    void thePreferredSideFirstAndNothingFromNothing() {
        List<Integer> asked = new ArrayList<>();
        assertEquals(0, choose(asked, Kind.COPY, Kind.COPY, Kind.COPY));
        assertEquals(List.of(0), asked, "looks no further than the first block to copy");
        assertEquals(2, choose(Kind.OPEN, Kind.OPEN, Kind.COPY));
        assertEquals(MaterialChoice.NOTHING, choose(Kind.OPEN, Kind.OPEN, Kind.OPEN, Kind.OPEN, Kind.OPEN,
                Kind.OPEN));
    }
}
