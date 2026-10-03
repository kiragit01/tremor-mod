package tremor.hollow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StraysTest {
    /** A copy of blocks 0..64 aside, 40..88 high: its shell is at -1 and 65 aside, 39 under it. */
    private static final HollowBox COPY = new HollowBox(0, 40, 0, 64, 88, 64);

    @Test
    void inTheCopyOrItsShellIsNotOut() {
        assertFalse(Strays.left(COPY, 32.5, 60, 32.5));
        assertFalse(Strays.left(COPY, -0.9, 60, 32.5), "in the shell");
        assertFalse(Strays.left(COPY, 65.9, 60, 65.9), "in the shell's corner");
        assertFalse(Strays.left(COPY, 32.5, 39, 32.5), "on the shell under the copy... in it");
        assertFalse(Strays.left(COPY, 32.5, 200, 32.5), "high over the copy: it comes down again");
    }

    @Test
    void pastTheShellAsideOrUnderTheBottomIsOut() {
        assertTrue(Strays.left(COPY, -1.1, 60, 32.5));
        assertTrue(Strays.left(COPY, 66, 60, 32.5));
        assertTrue(Strays.left(COPY, 32.5, 60, 66.2));
        assertTrue(Strays.left(COPY, 32.5, 200, -3), "over the side");
        assertTrue(Strays.left(COPY, 32.5, 38.9, 32.5), "under the bottom");
    }
}
