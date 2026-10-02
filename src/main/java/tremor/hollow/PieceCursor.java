package tremor.hollow;

import java.util.NoSuchElementException;
import java.util.function.Consumer;

/**
 * Walks a {@link HollowBox} in pieces small enough to cost little each (at most 16 x {@code slice} x 16 blocks, never
 * across a chunk section), so that copying or clearing a box can be spread over server ticks (SPEC 9: "по частям",
 * SPEC 16). Chunk column after chunk column, and in every column from the top down: blocks written under ones already
 * written change neither the heightmap nor the sky light sources, which keeps those updates cheap. Plain Java.
 */
public final class PieceCursor {
    /** Default height of a piece: a quarter of a chunk section, about 1000 blocks. */
    public static final int SLICE = 4;

    private final HollowBox box;
    private final int slice;
    private final int columnsX;
    private final int topSlice;
    private final int slicesPerColumn;
    private final int total;
    private int next;

    public PieceCursor(HollowBox box) {
        this(box, SLICE);
    }

    /** @param slice height of a piece; must divide 16 */
    PieceCursor(HollowBox box, int slice) {
        if (slice <= 0 || 16 % slice != 0) {
            throw new IllegalArgumentException("Slice height must divide 16: " + slice);
        }
        this.box = box;
        this.slice = slice;
        columnsX = box.maxChunkX() - box.minChunkX() + 1;
        int columnsZ = box.maxChunkZ() - box.minChunkZ() + 1;
        topSlice = Math.floorDiv(box.maxY(), slice);
        slicesPerColumn = topSlice - Math.floorDiv(box.minY(), slice) + 1;
        total = columnsX * columnsZ * slicesPerColumn;
    }

    public HollowBox box() {
        return box;
    }

    public boolean hasNext() {
        return next < total;
    }

    /** Pieces handed out so far. */
    public int done() {
        return next;
    }

    public int total() {
        return total;
    }

    public Piece next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        int index = next++;
        int column = index / slicesPerColumn;
        int step = index % slicesPerColumn;
        int chunkX = box.minChunkX() + column % columnsX;
        int chunkZ = box.minChunkZ() + column / columnsX;
        int sliceY = (topSlice - step) * slice;
        return new Piece(chunkX, chunkZ, sliceY >> 4,
                Math.max(box.minX(), chunkX << 4), Math.max(box.minY(), sliceY), Math.max(box.minZ(), chunkZ << 4),
                Math.min(box.maxX(), (chunkX << 4) + 15), Math.min(box.maxY(), sliceY + slice - 1),
                Math.min(box.maxZ(), (chunkZ << 4) + 15),
                step == 0, step == slicesPerColumn - 1);
    }

    /**
     * Hands pieces to {@code work} until the budget of this tick is spent or the box is done, but always at least one
     * piece, so that the work moves on however small the budget. Returns the number of pieces done.
     */
    public int run(TickBudget budget, Consumer<Piece> work) {
        int count = 0;
        do {
            work.accept(next());
            count++;
        } while (hasNext() && budget.hasTime());
        return count;
    }

    /**
     * One piece: the blocks of the box in one chunk section between {@code minY} and {@code maxY} (bounds inclusive).
     *
     * @param firstInColumn the first piece of its chunk column
     * @param lastInColumn  the last piece of its chunk column
     */
    public record Piece(int chunkX, int chunkZ, int sectionY, int minX, int minY, int minZ, int maxX, int maxY,
                       int maxZ, boolean firstInColumn, boolean lastInColumn) {

        public long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }
}
