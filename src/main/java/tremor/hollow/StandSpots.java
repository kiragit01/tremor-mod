package tremor.hollow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Where a player moved back out of the hollow is put if the stored place is taken (SPEC 9: back at the place of the
 * swallowing, which somebody may have built over, or lava flowed into, since): the offsets from that place tried in
 * turn, the place itself first, then the nearest ones, a block up before a block aside or down. Plain Java.
 */
public final class StandSpots {
    /** Blocks tried around the place horizontally. */
    public static final int RADIUS = 2;
    /** Blocks tried below the place. */
    public static final int DOWN = 1;
    /** Blocks tried above the place. */
    public static final int UP = 3;

    private static final List<Offset> OFFSETS = build();

    private StandSpots() {
    }

    /** An offset in blocks from the stored place. */
    public record Offset(int dx, int dy, int dz) {
    }

    /** All offsets, in the order they are tried. */
    public static List<Offset> offsets() {
        return OFFSETS;
    }

    /** How far an offset is: a block aside counts as four up, a block down as two up. */
    static int cost(Offset offset) {
        return 4 * (offset.dx() * offset.dx() + offset.dz() * offset.dz())
                + (offset.dy() >= 0 ? offset.dy() : -2 * offset.dy());
    }

    private static List<Offset> build() {
        List<Offset> offsets = new ArrayList<>();
        for (int step = 0; step <= UP + DOWN; step++) {
            int dy = step <= UP ? step : UP - step;
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                    offsets.add(new Offset(dx, dy, dz));
                }
            }
        }
        // Stable: equally far offsets keep the order above (up before down).
        offsets.sort(Comparator.comparingInt(StandSpots::cost));
        return List.copyOf(offsets);
    }
}
