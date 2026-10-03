package tremor.awakening;

import java.util.function.IntConsumer;

/**
 * What the sinkhole of a defeat (SPEC 9 "Поражение", 12) digs out of the real world, column by column: plain Java,
 * unit-tested directly. The world is seen through {@link Cells}; what a block is to the sinkhole ({@link Cell}) is
 * decided by {@code Sinkholes}, which digs without updating the neighbours (nothing next to the sinkhole pops off,
 * falls or flows because of it) and sees what it dug as air.
 * <p>
 * A column is dug from the swallow point's height down ({@link #digColumn}): past the air at the top; then whatever
 * rests on the ground there (a plant, a slab, a carpet; also above that height, if the column's ground is at it, but
 * nothing under another block: the sinkhole never undermines anything); then the ground block by block, for as deep
 * as the {@link SinkholeShape} says. Each block is looked at on all its faces before it goes ({@link #mayDigGround}),
 * and the first one that may not go ends the column for good (nothing below is dug either). So:
 * <ul>
 *   <li>a kept block (a block entity, {@code #tremor:protected}, an unbreakable or a burning block, the spawn
 *   protection) is never dug, nor laid bare: the ground next to it, under it and on it stays (it stands on a pillar),
 *   and what is fixed to it stays fixed;</li>
 *   <li>no fluid gets a way in, so nothing pours into the sinkhole, ever (no waterfall, no lava over the things on
 *   its bottom), and none lies under its floor or beside an open side of it;</li>
 *   <li>the sinkhole never breaks into a cave or a hollow below or beside it: under each block dug lies a whole block
 *   that holds (not a plant, a cobweb or lichen hanging into a cave, nor sand over a cavity), and beside it lies a
 *   whole block, or air open from the top (the ground beside is lower), never a cavity. It is a closed bowl, open only
 *   at the top, and what falls in stays on its bottom.</li>
 * </ul>
 * A plant next to a dug block that cannot stay without it drops as usual ({@code Sinkholes}).
 */
public final class SinkholeRules {
    /** At most this many blocks over the swallow point's height go with a column whose ground is at that height. */
    static final int HEAD = 2;

    private SinkholeRules() {
    }

    /** What a block of the real world is to the sinkhole. */
    public enum Cell {
        /** Air: nothing to dig. */
        AIR,
        /** Nothing to bump into (a plant, a flower, a torch, fire, a cobweb...): may go on top; holds nothing. */
        PLANT,
        /**
         * Something to bump into, but not a whole block (a slab, stairs, a fence, farmland, a carpet, a ladder...): may
         * go on top; neither a floor nor a side of the sinkhole.
         */
        LOOSE,
        /** A whole block that may go. */
        SOLID,
        /** A whole block that falls when nothing holds it (sand, gravel...): may go, and holds if it is held itself. */
        FALLING,
        /** Holds a fluid (also a waterlogged block). */
        FLUID,
        /**
         * Never dug: has a block entity, is in {@code #tremor:protected}, cannot be broken or burns (magma), lies in
         * the spawn protection, outside the world (the build height, the world border) or in a chunk that is not
         * loaded.
         */
        KEEP
    }

    /** The world as the sinkhole sees it. */
    @FunctionalInterface
    public interface Cells {
        Cell at(int x, int y, int z);
    }

    /**
     * Digs the column at {@code x, z} from {@code top} (the swallow point's height) down to {@code top - depth}
     * ({@code dig} is told each height dug, the highest first, and must see it as air afterwards): air at the top is
     * passed over; then what rests on the ground goes ({@link #mayDigTop}: plants and other blocks that are not whole,
     * up to {@value #HEAD} over {@code top} if the column's ground is at {@code top}, with air above them; a column with
     * anything else above {@code top} is not touched); then the ground ({@link #mayDigGround}). The first block that
     * may not go ends the column. Returns the lowest height of the column that is open (dug, or air from the top
     * down); {@code top + 1} if the top itself is neither.
     */
    public static int digColumn(Cells cells, int x, int z, int top, int depth, IntConsumer dig) {
        int bottom = top - depth;
        int open = top + 1;
        int y = top;
        while (y >= bottom && cells.at(x, y, z) == Cell.AIR) {
            open = y;
            y--;
        }
        if (y < bottom) {
            return open;
        }
        if (y == top) {
            int head = top + 1;
            while (head <= top + HEAD && onTop(cells.at(x, head, z))) {
                head++;
            }
            if (cells.at(x, head, z) != Cell.AIR) {
                // Under something: never undermined.
                return open;
            }
            for (int h = head - 1; h > top; h--) {
                if (!mayDigTop(cells, x, h, z)) {
                    return open;
                }
                dig.accept(h);
            }
        }
        boolean ground = false;
        for (; y >= bottom; y--) {
            Cell cell = cells.at(x, y, z);
            boolean dug;
            if (onTop(cell)) {
                dug = !ground && mayDigTop(cells, x, y, z);
            } else if (cell == Cell.SOLID || cell == Cell.FALLING) {
                dug = mayDigGround(cells, x, y, z, top);
                ground = true;
            } else {
                // Air under what was dug (a cavity), a fluid, a kept block.
                dug = false;
            }
            if (!dug) {
                break;
            }
            dig.accept(y);
            open = y;
        }
        return open;
    }

    /**
     * Whether something on top of the ground (a {@link Cell#PLANT} or a {@link Cell#LOOSE} block) at {@code x, y, z}
     * may go: no fluid touches it, nothing kept rests on it, something is under it, and (unless it is a plant, to which
     * nothing can be fixed) no kept block touches it.
     */
    static boolean mayDigTop(Cells cells, int x, int y, int z) {
        Cell above = cells.at(x, y + 1, z);
        Cell below = cells.at(x, y - 1, z);
        if (touchesFluid(cells, x, y, z) || above == Cell.KEEP || below == Cell.AIR) {
            return false;
        }
        return cells.at(x, y, z) == Cell.PLANT || !touchesKeep(cells, x, y, z);
    }

    /**
     * Whether a block of the ground at {@code x, y, z} may go: no fluid and no kept block touches it, it has air above
     * it (what rested on it went, or it was open), the block under it holds ({@link #holds}), and each of its four
     * sides is a whole block, or air or a plant open from the top ({@code top}: the swallow point's height) that no
     * fluid touches.
     */
    static boolean mayDigGround(Cells cells, int x, int y, int z, int top) {
        if (touchesFluid(cells, x, y, z) || touchesKeep(cells, x, y, z) || cells.at(x, y + 1, z) != Cell.AIR
                || !holds(cells, x, y - 1, z)) {
            return false;
        }
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] side : sides) {
            int sx = x + side[0], sz = z + side[1];
            Cell cell = cells.at(sx, y, sz);
            if (cell != Cell.SOLID && cell != Cell.FALLING
                    && !(openFromTop(cells, sx, y, sz, top) && !touchesFluid(cells, sx, y, sz))) {
                return false;
            }
        }
        return true;
    }

    /** Whether the block at {@code x, y, z} holds what lies on it: a whole block, sand or gravel only if held itself. */
    static boolean holds(Cells cells, int x, int y, int z) {
        Cell cell = cells.at(x, y, z);
        if (cell == Cell.SOLID) {
            return true;
        }
        if (cell != Cell.FALLING) {
            return false;
        }
        Cell below = cells.at(x, y - 1, z);
        return below == Cell.SOLID || below == Cell.FALLING || below == Cell.LOOSE || below == Cell.KEEP;
    }

    /**
     * Whether the block at {@code x, y, z} and every block above it up to {@code top} is air or a plant: open to where
     * the player stood, not a cavity.
     */
    static boolean openFromTop(Cells cells, int x, int y, int z, int top) {
        for (int at = y; at <= Math.max(y, top); at++) {
            Cell cell = cells.at(x, at, z);
            if (cell != Cell.AIR && cell != Cell.PLANT) {
                return false;
            }
        }
        return true;
    }

    /** Whether a fluid lies on any of the six faces of the block. */
    static boolean touchesFluid(Cells cells, int x, int y, int z) {
        return touches(cells, x, y, z, Cell.FLUID);
    }

    /** Whether a kept block lies on any of the six faces of the block. */
    static boolean touchesKeep(Cells cells, int x, int y, int z) {
        return touches(cells, x, y, z, Cell.KEEP);
    }

    private static boolean onTop(Cell cell) {
        return cell == Cell.PLANT || cell == Cell.LOOSE;
    }

    private static boolean touches(Cells cells, int x, int y, int z, Cell what) {
        return cells.at(x, y + 1, z) == what || cells.at(x, y - 1, z) == what
                || cells.at(x + 1, y, z) == what || cells.at(x - 1, y, z) == what
                || cells.at(x, y, z + 1) == what || cells.at(x, y, z - 1) == what;
    }
}
