package tremor.hearing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code hearing.loudness} config list (SPEC 7.1): base loudness of vanilla game events as
 * {@code "namespace:event=loudness"} entries ({@code "event=loudness"} means the {@code minecraft} namespace).
 * No Minecraft classes, so it is unit-tested directly.
 */
public final class LoudnessTable {
    /** Highest loudness an entry may give. */
    public static final double MAX = 1000;

    public static final List<String> DEFAULTS = List.of(
            "minecraft:step=3",
            "minecraft:hit_ground=8",
            "minecraft:block_destroy=6",
            "minecraft:block_place=4",
            "minecraft:projectile_land=4",
            "minecraft:explode=20");

    /** Same character sets as a {@code ResourceLocation}. */
    private static final Pattern ENTRY =
            Pattern.compile("\\s*(?:([a-z0-9_.-]+):)?([a-z0-9_./-]+)\\s*=\\s*([0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)\\s*");

    private LoudnessTable() {
    }

    /** Config validator: a string entry with a valid id and a loudness in [0, {@value #MAX}]. */
    public static boolean isValid(Object entry) {
        return entry instanceof String s && parseEntry(s) != null;
    }

    /**
     * Event id ({@code namespace:path}) to loudness, in list order; invalid entries are skipped, a later entry for
     * the same event replaces an earlier one.
     */
    public static Map<String, Double> parse(List<?> entries) {
        Map<String, Double> table = new LinkedHashMap<>();
        for (Object entry : entries) {
            if (entry instanceof String s) {
                Map.Entry<String, Double> parsed = parseEntry(s);
                if (parsed != null) {
                    table.put(parsed.getKey(), parsed.getValue());
                }
            }
        }
        return table;
    }

    private static Map.Entry<String, Double> parseEntry(String entry) {
        Matcher m = ENTRY.matcher(entry);
        if (!m.matches()) {
            return null;
        }
        double value = Double.parseDouble(m.group(3));
        if (!(value <= MAX)) {
            return null;
        }
        String namespace = m.group(1) != null ? m.group(1) : "minecraft";
        return Map.entry(namespace + ":" + m.group(2), value);
    }
}
