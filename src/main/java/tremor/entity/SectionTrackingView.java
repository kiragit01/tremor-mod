package tremor.entity;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import tremor.core.VoxelView;
import tremor.core.math.VoxelPos;
import tremor.world.LevelVoxelView;

/**
 * The world as the runtime's {@link tremor.core.voxel.VoxelCache} reads it: a {@link LevelVoxelView} that remembers
 * which 16³ sections were read through it. Nothing cached or memoized can depend on a section that was never read,
 * so block changes there are ignored.
 * <p>
 * {@link LevelVoxelView} keeps a reference to the last chunk it touched, so the view is replaced by a fresh one at
 * the start of every pass ({@link #refresh}); a chunk unloaded between ticks is never read through a stale reference.
 */
final class SectionTrackingView implements VoxelView {
    private final Level level;
    private final LongOpenHashSet sections = new LongOpenHashSet();
    /** Chunk columns ({@link ChunkPos#asLong}) with a section in {@link #sections}. */
    private final LongOpenHashSet columns = new LongOpenHashSet();
    private LevelVoxelView view;
    /** Section of the previous read: sections are read voxel by voxel, so this skips almost every set lookup. */
    private long lastSection = Long.MIN_VALUE;

    SectionTrackingView(Level level) {
        this.level = level;
        this.view = new LevelVoxelView(level);
    }

    void refresh() {
        view = new LevelVoxelView(level);
    }

    /** Whether the section containing the voxel was read since the last {@link #forget}. */
    boolean wasRead(int x, int y, int z) {
        return sections.contains(sectionKey(x, y, z));
    }

    /** Whether a section of the chunk column was read since the last {@link #forget}. */
    boolean wasReadInColumn(int chunkX, int chunkZ) {
        return columns.contains(ChunkPos.asLong(chunkX, chunkZ));
    }

    void forget() {
        sections.clear();
        columns.clear();
        lastSection = Long.MIN_VALUE;
    }

    int sectionCount() {
        return sections.size();
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        mark(x, y, z);
        return view.isSolid(x, y, z);
    }

    @Override
    public boolean isKnown(int x, int y, int z) {
        mark(x, y, z);
        return view.isKnown(x, y, z);
    }

    @Override
    public float conductivity(int x, int y, int z) {
        return view.conductivity(x, y, z);
    }

    @Override
    public boolean isProtected(int x, int y, int z) {
        return view.isProtected(x, y, z);
    }

    private void mark(int x, int y, int z) {
        long key = sectionKey(x, y, z);
        if (key != lastSection) {
            if (sections.add(key)) {
                columns.add(ChunkPos.asLong(x >> 4, z >> 4));
            }
            lastSection = key;
        }
    }

    /** Key of the section containing the voxel. */
    static long sectionKey(int x, int y, int z) {
        return VoxelPos.pack(x >> 4, y >> 4, z >> 4);
    }
}
