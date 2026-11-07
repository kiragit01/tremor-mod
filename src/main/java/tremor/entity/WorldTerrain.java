package tremor.entity;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import tremor.config.TremorConfig;
import tremor.core.math.VoxelPos;
import tremor.core.voxel.VoxelCache;
import tremor.world.LevelVoxelView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The terrain of a level as the creatures see it, one for all of them (the user: the tremors, the listeners, the
 * stalactite crawlers and whatever comes): solid and known voxels read a whole 16³ section at a time and kept
 * ({@link VoxelCache}), told of every block change and every chunk that loads in a section it holds, read again where
 * they changed, and all forgotten every {@code cacheMaxAge} ticks as a safety net for changes no event reported.
 * <p>
 * Once a tick ({@link #tick}, before its users) it takes in what changed: a block change matters only if the cache's
 * answer for the voxel (solid, known) differs from the world, a loaded chunk for the sections of its column the cache
 * holds once it can be read; each such section is read again as a whole, and the box of the voxels that differ is told
 * ({@link #changes}). Its users keep their own graphs over it and forget what depended on those boxes, or all of it
 * after a reset ({@link #resetAt}) or a tick they missed. Server thread only.
 */
public final class WorldTerrain {
    private static final Map<ServerLevel, WorldTerrain> TERRAINS = new HashMap<>();

    private final ServerLevel level;
    private final SectionTrackingView tracked;
    private final VoxelCache cache;
    /** Uncached view of the world for the current tick (replaced each tick, see {@link SectionTrackingView}). */
    private LevelVoxelView live;
    private final LongOpenHashSet pendingChanges = new LongOpenHashSet();
    /** Chunks ({@link ChunkPos#asLong}) that loaded with sections the cache holds, to read again once readable. */
    private final LongOpenHashSet pendingChunks = new LongOpenHashSet();
    private final Bounds reloaded = new Bounds();
    private long tickedAt;
    private long resetAt;
    private List<Box> changes = List.of();

    private WorldTerrain(ServerLevel level) {
        this.level = level;
        this.tracked = new SectionTrackingView(level);
        this.cache = new VoxelCache(tracked);
        this.live = new LevelVoxelView(level);
        this.tickedAt = level.getGameTime();
        this.resetAt = tickedAt;
        cache.setTime(tickedAt);
    }

    /** The terrain of {@code level}, made the first time it is asked for. */
    public static WorldTerrain of(ServerLevel level) {
        return TERRAINS.computeIfAbsent(level, WorldTerrain::new);
    }

    /** Takes in what changed in {@code level}'s terrain since the last tick, if it has one. */
    static void tick(ServerLevel level) {
        WorldTerrain terrain = TERRAINS.get(level);
        if (terrain != null) {
            terrain.tick();
        }
    }

    /** A block that may have changed in {@code level} (block update, explosion, piston). */
    static void blockChanged(ServerLevel level, BlockPos pos) {
        WorldTerrain terrain = TERRAINS.get(level);
        if (terrain != null && terrain.tracked.wasRead(pos.getX(), pos.getY(), pos.getZ())) {
            terrain.pendingChanges.add(pos.asLong());
        }
    }

    /**
     * A chunk of {@code level} has loaded. That happens in the middle of loading it, when the level must not be read
     * yet, so the chunk is only noted; {@link #tick} reads its sections again once it is readable.
     */
    static void chunkLoaded(ServerLevel level, int chunkX, int chunkZ) {
        WorldTerrain terrain = TERRAINS.get(level);
        if (terrain != null && terrain.tracked.wasReadInColumn(chunkX, chunkZ)) {
            terrain.pendingChunks.add(ChunkPos.asLong(chunkX, chunkZ));
        }
    }

    /** Drops every terrain (the server stops). */
    static void clearAll() {
        TERRAINS.clear();
    }

    public VoxelCache cache() {
        return cache;
    }

    /** The uncached view of the world for this tick. */
    public LevelVoxelView live() {
        return live;
    }

    /** Game time of the last tick it took in. */
    public long tickedAt() {
        return tickedAt;
    }

    /** Game time it was all forgotten last. */
    public long resetAt() {
        return resetAt;
    }

    /** The boxes of terrain that changed at the last tick it took in. */
    List<Box> changes() {
        return changes;
    }

    /** Once a tick, before its users: what changed, or all of it forgotten when it is time. */
    public void tick() {
        long now = level.getGameTime();
        if (now == tickedAt) {
            return;
        }
        tickedAt = now;
        live = new LevelVoxelView(level);
        tracked.refresh();
        cache.setTime(now);
        changes = takeIn();
        if (now - resetAt >= TremorConfig.COMMON.cacheMaxAge.get()) {
            cache.clear();
            tracked.forget();
            pendingChanges.clear();
            pendingChunks.clear();
            changes = List.of();
            resetAt = now;
        }
    }

    /** Reads again the sections where the reported changes differ from what it holds; their boxes. */
    private List<Box> takeIn() {
        if (pendingChanges.isEmpty() && pendingChunks.isEmpty()) {
            return List.of();
        }
        LongOpenHashSet sections = new LongOpenHashSet();
        for (LongIterator it = pendingChanges.iterator(); it.hasNext(); ) {
            long pos = it.nextLong();
            int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
            if (tracked.wasRead(x, y, z) && (cache.isSolid(x, y, z) != live.isSolid(x, y, z)
                    || cache.isKnown(x, y, z) != live.isKnown(x, y, z))) {
                sections.add(SectionTrackingView.sectionKey(x, y, z));
            }
        }
        pendingChanges.clear();
        for (LongIterator it = pendingChunks.iterator(); it.hasNext(); ) {
            long chunk = it.nextLong();
            int chunkX = ChunkPos.getX(chunk), chunkZ = ChunkPos.getZ(chunk);
            if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
                continue; // not readable yet; dropped by the next reset at the latest
            }
            it.remove();
            // Outside the build height nothing depends on chunks.
            for (int sy = level.getMinSection(); sy < level.getMaxSection(); sy++) {
                if (tracked.wasRead(chunkX << 4, sy << 4, chunkZ << 4)) {
                    sections.add(VoxelPos.pack(chunkX, sy, chunkZ));
                }
            }
        }
        List<Box> boxes = new ArrayList<>();
        for (LongIterator it = sections.iterator(); it.hasNext(); ) {
            long section = it.nextLong();
            reloaded.reset();
            if (cache.reload(VoxelPos.x(section) << 4, VoxelPos.y(section) << 4, VoxelPos.z(section) << 4,
                    reloaded) > 0) {
                boxes.add(reloaded.box());
            }
        }
        return boxes;
    }

    /** Inclusive voxel box of changed terrain. */
    record Box(int x0, int y0, int z0, int x1, int y1, int z1) {
        /** Whether it comes within {@code margin} (Chebyshev) of the box spanned by voxels {@code a} and {@code b}. */
        boolean near(long a, long b, int margin) {
            return overlaps(x0, x1, VoxelPos.x(a), VoxelPos.x(b), margin)
                    && overlaps(y0, y1, VoxelPos.y(a), VoxelPos.y(b), margin)
                    && overlaps(z0, z1, VoxelPos.z(a), VoxelPos.z(b), margin);
        }

        private static boolean overlaps(int lo, int hi, int a, int b, int margin) {
            return lo <= (long) Math.max(a, b) + margin && hi >= (long) Math.min(a, b) - margin;
        }
    }

    /** Bounding box of the voxels a section reload reports. */
    private static final class Bounds implements VoxelCache.ChangeListener {
        private int x0, y0, z0, x1, y1, z1;

        void reset() {
            x0 = y0 = z0 = Integer.MAX_VALUE;
            x1 = y1 = z1 = Integer.MIN_VALUE;
        }

        @Override
        public void changed(int x, int y, int z) {
            x0 = Math.min(x0, x);
            y0 = Math.min(y0, y);
            z0 = Math.min(z0, z);
            x1 = Math.max(x1, x);
            y1 = Math.max(y1, y);
            z1 = Math.max(z1, z);
        }

        Box box() {
            return new Box(x0, y0, z0, x1, y1, z1);
        }
    }
}
