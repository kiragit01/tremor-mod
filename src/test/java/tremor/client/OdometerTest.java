package tremor.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OdometerTest {
    private static Odometer.Glyph at(List<Odometer.Glyph> glyphs, int place, int digit) {
        return glyphs.stream().filter(g -> g.place() == place && g.digit() == digit).findFirst().orElse(null);
    }

    @Test
    void aWholeNumberShowsItsDigitsInTheWindow() {
        List<Odometer.Glyph> g = Odometer.glyphs(23, 2);
        assertEquals(2, g.size());
        assertEquals(0, at(g, 0, 3).offset(), 1e-12);
        assertEquals(0, at(g, 1, 2).offset(), 1e-12);
    }

    @Test
    void goingDownTheOldDigitDropsOutBelowAndTheNextComesFromAbove() {
        List<Odometer.Glyph> g = Odometer.glyphs(2.25, 1); // on its way from 3 down to 2, three quarters there
        assertEquals(0.75, at(g, 0, 3).offset(), 1e-12);   // 3 below the window, nearly out
        assertEquals(-0.25, at(g, 0, 2).offset(), 1e-12);  // 2 coming in from above
    }

    @Test
    void aHigherPlaceTurnsOnlyAsTheOnesPassNineToZero() {
        List<Odometer.Glyph> g = Odometer.glyphs(14.5, 2);
        assertEquals(0, at(g, 1, 1).offset(), 1e-12, "the tens stand still at 14.5");
        g = Odometer.glyphs(9.5, 2);
        assertEquals(0.5, at(g, 1, 1).offset(), 1e-12, "between 9 and 10 the tens turn with the ones, 1 below");
        assertEquals(null, at(g, 1, 0), "no leading zero");
    }

    @Test
    void neverBelowZeroAndPlacesFitTheCount() {
        List<Odometer.Glyph> g = Odometer.glyphs(-0.2, 1);
        assertEquals(1, g.size());
        assertEquals(0, g.get(0).digit());
        assertEquals(1, Odometer.places(0));
        assertEquals(1, Odometer.places(9));
        assertEquals(2, Odometer.places(10));
        assertTrue(Odometer.glyphs(5, 2).stream().noneMatch(x -> x.place() == 1), "5 has no tens");
    }
}
