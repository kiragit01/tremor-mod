package tremor.hearing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VoicesTest {
    @Test
    void levelOfASound() {
        assertEquals(Double.NEGATIVE_INFINITY, Voices.level(new short[960]));
        assertEquals(Double.NEGATIVE_INFINITY, Voices.level(null));
        short[] full = new short[960];
        java.util.Arrays.fill(full, Short.MAX_VALUE);
        assertEquals(0, Voices.level(full), 0.01);
        short[] tenth = new short[960];
        java.util.Arrays.fill(tenth, (short) 3277);
        assertEquals(-20, Voices.level(tenth), 0.05);
    }

    @Test
    void loudnessOfAVoice() {
        assertEquals(0, Voices.loudness(-60, false, -50, -15, 8, 0.3));
        assertEquals(0, Voices.loudness(-50, false, -50, -15, 8, 0.3), "at the quiet level it is not heard");
        assertEquals(4, Voices.loudness(-32.5, false, -50, -15, 8, 0.3), 1e-9, "halfway");
        assertEquals(8, Voices.loudness(-15, false, -50, -15, 8, 0.3), 1e-9);
        assertEquals(8, Voices.loudness(-3, false, -50, -15, 8, 0.3), 1e-9, "no louder than the loudest");
        assertEquals(2.4, Voices.loudness(-15, true, -50, -15, 8, 0.3), 1e-9, "a whisper");
        assertEquals(0, Voices.loudness(-20, false, -15, -50, 8, 0.3), "a bad range hears nothing");
        assertTrue(Voices.loudness(Double.NaN, false, -50, -15, 8, 0.3) == 0);
    }
}
