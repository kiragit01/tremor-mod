package tremor.awakening;

import java.util.function.IntConsumer;

/**
 * What the sinkhole of a defeat (SPEC 9 "Поражение", 12) digs out of the real world, column by column: plain Java,
 * unit-tested directly. The world is seen through {@link Cells}; what a block is to the sinkhole ({@link Cell}) is
 * decided by {@code Sinkholes}.
 * <p>
 * A column is dug from the swallow point's height down ({@link #digColumn}): past the air at the top, then block by
 * block for as deep as the {@link SinkholeShape} says. It stops for good (nothing below is dug either) at a block that
 * must be kept, at a fluid, at a block touching a fluid on any of its six faces, and above a cavity. So:
 * <ul>
 *   <li>a kept block (a block entity, {@code #tremor:protected}, an unbreakable block, the spawn protection) is never
 *   dug, nor undermined: it stays on what carries it;</li>
 *   <li>no fluid gets a way in, so nothing pours into the sinkhole, ever (no waterfall, no lava over the things on
 *   its bottom), and none lies under its floor;</li>
 *   <li>the sinkhole never breaks into a cave below, so what falls in stays on its bottom: it is a closed bowl, open
 *   only at the top.</li>
 * </ul>
 * The blocks around are not looked at otherwise: sand or gravel over the edge may slide in, plants and torches on dug
 * blocks drop as usual.
 */
public final class SinkholeRules {
    private SinkholeRules() {
    }

    /** What a block of the real world is to the sinkhole. */
    public enum Cell {
        /** Air: nothing to dig. */
        AIR,
        /** Any other block that may go. */
        SOLID,
        /** Holds a fluid (also a waterlogged block). */
        FLUID,
        /**
         * Never dug: has a block entity, is in {@code #tremor:protected} or cannot be broken, lies in the spawn
         * protection, outside the world (the build height, the world border) or in a chunk that is not loaded.
         */
        KEEP
    }

    /** The world as the sinkhole sees it. */
    @FunctionalInterface
    public interface Cells {
        Cell at(int x, int y, int z);
    }

    /**
     * Digs the column at {@code x, z} from {@code top} (the swallow point's height) down to {@code top - depth}: air at
     * the top is passed over; from the first block on, each block is dug ({@code dig} is told its height, top first)
     * while it is {@link Cell#SOLID}, touches no fluid and has no air below it. The first block that fails ends the
     * column. Returns the lowest height of the column that is open (dug, or air from the top down); {@code top + 1}
     * if the top itself is neither.
     */
    public static int digColumn(Cells cells, int x, int z, int top, int depth, IntConsumer dig) {
        int open = top + 1;
        for (int y = top; y >= top - depth; y--) {
            Cell cell = cells.at(x, y, z);
            if (cell == Cell.AIR) {
                // Only above the ground: once a block is dug, the one below it is never air.
                open = y;
                continue;
            }
            if (cell != Cell.SOLID || touchesFluid(cells, x, y, z) || cells.at(x, y - 1, z) == Cell.AIR) {
                break;
            }
            dig.accept(y);
            open = y;
        }
        return open;
    }

    /** Whether a fluid lies on any of the six faces of the block. */
    static boolean touchesFluid(Cells cells, int x, int y, int z) {
        return cells.at(x, y + 1, z) == Cell.FLUID || cells.at(x, y - 1, z) == Cell.FLUID
                || cells.at(x + 1, y, z) == Cell.FLUID || cells.at(x - 1, y, z) == Cell.FLUID
                || cells.at(x, y, z + 1) == Cell.FLUID || cells.at(x, y, z - 1) == Cell.FLUID;
    }
}
