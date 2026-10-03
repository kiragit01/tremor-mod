package tremor.awakening;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the crater of a defeat or of an escape through the edge (SPEC 9 "Исходы", 12) digs out of the real world, and
 * what it fills in so that nothing gets into it: plain Java, unit-tested directly. The world is seen through
 * {@link Cells}; what a block is to the crater ({@link Cell}) is decided by {@code Craters}, which carves and fills
 * without updating the neighbours (nothing next to the crater pops off, falls or flows because of it) and sees what it
 * carved as air and what it filled as a whole block.
 * <p>
 * The crater is a set of {@link Column}s (from a {@link CraterShape}), each carved from its {@link #carveTop top} down
 * to its bottom: a column of open ground from the swallow point's height, a hill, a tree or the thin roof of a cave
 * from its top within {@code reachUp} blocks over that height, anything under a thicker roof (a tunnel, a cave, a
 * cliff) from that height, under the roof. The {@link Dig} goes layer by layer from the highest down, each layer from
 * the centre out, so the ground caves in from the top. A kept block (a block entity, {@code #tremor:protected}, an
 * unbreakable or burning block, the spawn protection, what is not loaded) is never carved, nor is a block fixed to it
 * (a whole block or anything to bump into touching it): it ends its column for good (nothing below it is carved
 * either), so a kept block stands on a pillar of the ground under it. A fluid in the crater (a pond, a stream, the edge
 * of a lake, the water the player stood in) is carved like the rest, no column stops at it: the fluid around is plugged
 * at its own blocks (below), and the walls of the crater keep their shape.
 * <p>
 * After each block carved (also each block of air in the crater, a cave crossing it), the crater is closed again, so
 * it is a closed bowl, open only at the top, at every moment of the dig (a server stopping in the middle leaves a
 * shallower crater, as safe as a finished one):
 * <ul>
 *   <li>a fluid beside it is filled in ({@link Works#seal}): each block of a fluid that touches the crater becomes
 *   ground, a plug where the fluid was, and nothing flows into the crater, ever; so does a fluid under it (higher
 *   than the swallow point, in the crater, its plug is carved next);</li>
 *   <li>under it, below the swallow point's height, lies a block that holds ({@link #holds}), or it is filled in: the
 *   bottom is solid, nothing falls into a cave below; unless the column was open from the swallow point's height down
 *   to there (air or a plant over a slope that lies lower than the crater's bottom: the column goes on down through
 *   it, and nothing is filled in). Higher up (a hill, a tree) what is under it is carved next;</li>
 *   <li>beside it, below the swallow point's height, lies no cavity: air or a plant there that was not open from that
 *   height down is filled in (the crater never opens into a cave or a hollow beside it);</li>
 *   <li>over the top of a column carved under a roof, sand, gravel or a fluid is filled in (nothing falls or flows in
 *   from above).</li>
 * </ul>
 * A column carved down to its bottom on ground that holds is {@link Works#bottomed} (the rubble goes on it).
 */
public final class CraterRules {
    private CraterRules() {
    }

    /** What a block of the real world is to the crater. */
    public enum Cell {
        /** Air: nothing to carve. */
        AIR,
        /** Nothing to bump into (a plant, a flower, a torch, fire, a cobweb...): carved; holds nothing. */
        PLANT,
        /** Something to bump into, not a whole block (a slab, stairs, a fence, a carpet...): carved; holds nothing. */
        LOOSE,
        /** A whole block: carved; holds. */
        SOLID,
        /** A whole block that falls when nothing holds it (sand, gravel...): carved; holds if it is held itself. */
        FALLING,
        /** Holds a fluid (also a waterlogged block). */
        FLUID,
        /**
         * Never carved nor filled: has a block entity, is in {@code #tremor:protected}, cannot be broken or burns
         * (magma), lies in the spawn protection, outside the world (the build height, the world border) or in a chunk
         * that is not loaded.
         */
        KEEP
    }

    /** The world as the crater sees it. */
    @FunctionalInterface
    public interface Cells {
        Cell at(int x, int y, int z);
    }

    /** What the dig does to the world; the world must see each change at once. */
    public interface Works {
        /** The block at {@code x, y, z} goes: it is {@link Cell#AIR} afterwards. */
        void carve(int x, int y, int z);

        /**
         * The block at {@code x, y, z} is filled in with ground: it is {@link Cell#SOLID} afterwards. It closes off the
         * block of the crater at {@code fromX, fromY, fromZ} beside it (air by now).
         */
        void seal(int x, int y, int z, int fromX, int fromY, int fromZ);

        /** {@code column} is carved down to its bottom, and the block under it holds. */
        void bottomed(Column column);
    }

    /**
     * The highest block of the column at {@code x, z} the crater carves: the highest block that is not air and has air
     * above it, from {@code top} (the swallow point's height) up to {@code top + reachUp} (the top of a hill, a tree, a
     * thin roof); {@code top} itself if there is none (open ground lower than the swallow point, or a roof thicker than
     * that: the column is carved from {@code top}, under it).
     */
    public static int carveTop(Cells cells, int x, int z, int top, int reachUp) {
        Cell above = cells.at(x, top + reachUp + 1, z);
        for (int y = top + reachUp; y >= top; y--) {
            Cell cell = cells.at(x, y, z);
            if (cell != Cell.AIR && above == Cell.AIR) {
                return y;
            }
            above = cell;
        }
        return top;
    }

    /** Whether the block at {@code x, y, z} holds what lies on it: a whole block, sand or gravel only if held itself. */
    public static boolean holds(Cells cells, int x, int y, int z) {
        Cell cell = cells.at(x, y, z);
        if (cell == Cell.SOLID || cell == Cell.KEEP) {
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

    /** Whether a kept block lies on any of the six faces of the block. */
    static boolean touchesKeep(Cells cells, int x, int y, int z) {
        return cells.at(x, y + 1, z) == Cell.KEEP || cells.at(x, y - 1, z) == Cell.KEEP
                || cells.at(x + 1, y, z) == Cell.KEEP || cells.at(x - 1, y, z) == Cell.KEEP
                || cells.at(x, y, z + 1) == Cell.KEEP || cells.at(x, y, z - 1) == Cell.KEEP;
    }

    /** A column of the crater: carved from {@link #top} down to {@link #bottom}, unless something stops it first. */
    public static final class Column {
        public final int x;
        public final int z;
        /** The highest block carved ({@link #carveTop}). */
        public final int top;
        /** The lowest block carved: the block under it is the floor. */
        public final int bottom;
        /** The next height to carve, the highest first; below {@link #bottom} once the column is through. */
        private int next;
        /** Something that may not go stopped the column at {@link #next}. */
        private boolean ended;
        /**
         * The blocks of the column from the swallow point's height down to the last one carved were all air or plants
         * before the dig: what lies under them is open to where the player stood, not a cavity.
         */
        private boolean open = true;

        public Column(int x, int z, int top, int bottom) {
            this.x = x;
            this.z = z;
            this.top = top;
            this.bottom = bottom;
            this.next = top;
        }

        /** Whether the block at height {@code y} of the column has been carved (or went through as air). */
        public boolean carved(int y) {
            return y > next && y <= top;
        }

        /** Whether something that may not go stopped the column. */
        public boolean ended() {
            return ended;
        }

        /** Whether the column is carved down to its bottom. */
        public boolean through() {
            return !ended && next < bottom;
        }
    }

    /**
     * The dig of a crater: its columns (the nearest to the centre first), layer by layer from the highest top down to
     * the lowest bottom, each layer in the columns' order, a block at a time ({@link #step}).
     */
    public static final class Dig {
        private final int top;
        private final List<Column> columns;
        private final Map<Long, Column> byPosition = new HashMap<>();
        private final int lowest;
        private final int highest;
        /** The layer being carved, and the index of the next column in it. */
        private int layer;
        private int index;

        /**
         * @param top     the swallow point's height (the block the player's feet were in)
         * @param columns the crater's columns, the nearest to the centre first
         */
        public Dig(int top, List<Column> columns) {
            this.top = top;
            this.columns = List.copyOf(columns);
            int low = Integer.MAX_VALUE;
            int high = Integer.MIN_VALUE;
            for (Column column : this.columns) {
                byPosition.put(key(column.x, column.z), column);
                low = Math.min(low, column.bottom);
                high = Math.max(high, column.top);
            }
            lowest = low;
            highest = high;
            layer = high;
        }

        /** The layer being carved (lower and lower; below {@link #lowest} once the dig is over). */
        public int layer() {
            return layer;
        }

        /** The lowest block any column goes down to. */
        public int lowest() {
            return lowest;
        }

        /** The highest block any column is carved from. */
        public int highest() {
            return highest;
        }

        public List<Column> columns() {
            return columns;
        }

        /** The crater's column at {@code x, z}, or null. */
        public Column column(int x, int z) {
            return byPosition.get(key(x, z));
        }

        /** Whether every column is through or stopped. */
        public boolean done() {
            return layer < lowest;
        }

        /**
         * Looks at the next block of the dig: carves it or stops its column, then closes the crater around it; false
         * once there is nothing left to look at.
         */
        public boolean step(Cells cells, Works works) {
            while (layer >= lowest) {
                while (index < columns.size()) {
                    Column column = columns.get(index++);
                    if (!column.ended && layer <= column.top && layer >= column.bottom) {
                        dig(cells, works, column, layer);
                        return true;
                    }
                }
                layer--;
                index = 0;
            }
            return false;
        }

        private void dig(Cells cells, Works works, Column column, int y) {
            int x = column.x;
            int z = column.z;
            Cell cell = cells.at(x, y, z);
            boolean ground = cell != Cell.AIR && cell != Cell.PLANT;
            if (cell == Cell.KEEP || ground && touchesKeep(cells, x, y, z)) {
                column.ended = true;
                return;
            }
            if (cell != Cell.AIR) {
                works.carve(x, y, z);
            }
            if (y <= top && ground) {
                column.open = false;
            }
            column.next = y - 1;
            // Under it, below the swallow point's height: a block that holds (a fluid is plugged), or, open from the
            // top, the air over a slope (the column goes on through it, unfilled). Higher up, what is under it goes
            // next; a fluid there is plugged all the same, and its plug carved next (no water lies open in the crater).
            Cell under = cells.at(x, y - 1, z);
            boolean openBelow = column.open && (under == Cell.AIR || under == Cell.PLANT);
            if (under == Cell.FLUID
                    || y - 1 < top && !openBelow && under != Cell.KEEP && !holds(cells, x, y - 1, z)) {
                works.seal(x, y - 1, z, x, y, z);
            }
            side(cells, works, x + 1, y, z, x, z);
            side(cells, works, x - 1, y, z, x, z);
            side(cells, works, x, y, z + 1, x, z);
            side(cells, works, x, y, z - 1, x, z);
            if (y == column.top && cell != Cell.AIR) {
                // The roof over a column carved under it.
                Cell over = cells.at(x, y + 1, z);
                if (over == Cell.FLUID || over == Cell.FALLING) {
                    works.seal(x, y + 1, z, x, y, z);
                }
            }
            if (y == column.bottom && holds(cells, x, y - 1, z)) {
                works.bottomed(column);
            }
        }

        /**
         * Closes the crater at the side of the block at {@code fromX, y, fromZ}, carved (or gone through as air): a
         * fluid or a cavity at {@code x, y, z} is filled in. A fluid in a column of the crater that has not got there
         * yet is filled in all the same (that column carves the plug when it gets there, unless it has stopped).
         */
        private void side(Cells cells, Works works, int x, int y, int z, int fromX, int fromZ) {
            Column column = byPosition.get(key(x, z));
            if (column != null && column.carved(y)) {
                return;
            }
            Cell cell = cells.at(x, y, z);
            if (cell == Cell.FLUID
                    || (cell == Cell.AIR || cell == Cell.PLANT) && y < top && !open(cells, column, x, y, z)) {
                works.seal(x, y, z, fromX, y, fromZ);
            }
        }

        /**
         * Whether the block at {@code x, y, z} (air or a plant, below the swallow point's height, not carved) was open to
         * where the player stood before the dig: if it lies in a column of the crater that has not got there yet, that
         * column's blocks above it were all air or plants; otherwise the blocks above it are now.
         */
        private boolean open(Cells cells, Column column, int x, int y, int z) {
            if (column != null && !column.ended && y <= column.top && y >= column.bottom) {
                return column.open;
            }
            return openFromTop(cells, x, y, z, top);
        }

        private static long key(int x, int z) {
            return (long) x << 32 | (z & 0xFFFFFFFFL);
        }
    }
}
