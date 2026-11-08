package tremor.client;

import java.util.ArrayList;
import java.util.List;

/**
 * A mechanical counter showing a value between whole numbers (the marks a charm takes off, rolled down): each place a
 * wheel of digits; the ones turn with the value, a higher place only while the places under it pass from 9 to 0 (or
 * back). A wheel going down drops its digit out of the window below and brings the next lower one in from above. No
 * leading zeros. Pure math.
 */
final class Odometer {
    /**
     * A digit to draw: its place (0 the ones), the digit, and how far below the window it sits, in lines (-1..1, 0 in
     * the window).
     */
    record Glyph(int place, int digit, double offset) {
    }

    private Odometer() {
    }

    /** How many places a count up to {@code max} needs. */
    static int places(int max) {
        return Math.max(1, String.valueOf(Math.max(0, max)).length());
    }

    /** The digits to draw for {@code value} (not below 0) over {@code places} places. */
    static List<Glyph> glyphs(double value, int places) {
        double v = Math.max(0, value);
        List<Glyph> out = new ArrayList<>();
        for (int k = 0; k < places; k++) {
            double unit = Math.pow(10, k);
            double whole = Math.floor(v / unit);
            double below = v - whole * unit;
            double turn = k == 0 ? below : Math.max(0, below - (unit - 1)); // only in the last unit of the places under
            double at = whole + turn;
            long first = (long) Math.floor(at);
            for (long n = first; n <= first + 1; n++) {
                double offset = n - at;
                if (Math.abs(offset) >= 1 || (k > 0 && n == 0)) {
                    continue; // out of the window, or a leading zero
                }
                out.add(new Glyph(k, (int) (n % 10), offset));
            }
        }
        return out;
    }
}
