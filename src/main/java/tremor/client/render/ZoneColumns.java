package tremor.client.render;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import tremor.core.deform.SurfacePoint;
import tremor.core.deform.ZoneScan;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.shape.AwakeningField;
import tremor.core.shape.BumpShape;
import tremor.core.voxel.VoxelCache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The ground of an Awakening zone (SPEC 9: "вся поверхность в зоне") for {@link DeformationRenderer}: every surface
 * voxel of the zone and as far around it as a step ring runs ({@link AwakeningField#reach}), found by a
 * {@link ZoneScan} spread over frames and kept, so a frame only evaluates the heights. The columns are grouped in
 * cells of 8³ blocks that are culled against the view frustum together (the zone is all around the camera, most of
 * it out of view) and visited nearest to the camera first.
 * <p>
 * The client gets no block change events. A zone is scanned again when the renderer sees the surface of its drawn
 * ground change ({@link #requestRescan}), when chunks of its terrain arrive that the client did not have before (a scan
 * reads terrain that is not loaded as unknown ground: someone who joins, respawns or teleports next to a running
 * Awakening gets its chunks a batch at a time; {@link ChunkCoverage}, looked at every {@link #COVERAGE_INTERVAL}
 * ticks), and every {@link #RESCAN_INTERVAL} ticks for changes elsewhere; the terrain of the zone is read afresh for
 * each scan, and the old ground is drawn until the new scan is complete. Render thread only.
 */
final class ZoneColumns {
    /** Gives the renderer's column for a scanned surface voxel (shared with the bump), marked as used at {@code tick}. */
    @FunctionalInterface
    interface Factory {
        Column column(SurfacePoint point, long tick);
    }

    /** Cells are {@code 1 << CELL_SHIFT} blocks wide. */
    private static final int CELL_SHIFT = 3;
    /** Scan steps (visited open voxels) between two looks at the clock. */
    private static final int SCAN_STEP = 64;
    /** Time a frame spends on the scan at most, give or take one step. */
    private static final long SCAN_NANOS = 1_000_000;
    /** Ticks between scans of a zone in which no change was seen. */
    private static final int RESCAN_INTERVAL = 200;
    /**
     * A change seen in the drawn ground, or terrain that arrived, scans the zone again, but not sooner than this many
     * ticks after the last.
     */
    private static final int MIN_RESCAN_INTERVAL = 20;
    /** Ticks between looks at which chunks of the zone's terrain the client has. */
    private static final int COVERAGE_INTERVAL = 10;
    /** The scan reads this far beyond its cylinder (normal estimation). */
    private static final int READ_MARGIN = 2;
    private static final Comparator<Cell> NEAREST_FIRST = Comparator.comparingDouble(c -> c.distanceSq);

    /** Columns within one 8³ cell, with the bounds of their voxels. */
    private static final class Cell {
        final List<Column> columns = new ArrayList<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        double distanceSq;
        /** Whether every column the breathing will raise has been baked ({@link #prebake}). */
        boolean baked;

        void add(Column col) {
            columns.add(col);
            minX = Math.min(minX, col.x);
            minY = Math.min(minY, col.y);
            minZ = Math.min(minZ, col.z);
            maxX = Math.max(maxX, col.x);
            maxY = Math.max(maxY, col.y);
            maxZ = Math.max(maxZ, col.z);
        }

        /** The voxels' box grown by {@code margin} on every side. */
        AABB bounds(double margin) {
            return new AABB(minX - margin, minY - margin, minZ - margin, maxX + 1 + margin, maxY + 1 + margin,
                    maxZ + 1 + margin);
        }

        /** Squared distance from the point to the nearest voxel centre the box of this cell could hold. */
        double nearSq(double px, double py, double pz) {
            double dx = Math.max(0, Math.max(minX + 0.5 - px, px - maxX - 0.5));
            double dy = Math.max(0, Math.max(minY + 0.5 - py, py - maxY - 0.5));
            double dz = Math.max(0, Math.max(minZ + 0.5 - pz, pz - maxZ - 0.5));
            return dx * dx + dy * dy + dz * dz;
        }

        /** Squared distance from the point to the farthest voxel centre the box of this cell could hold. */
        double farSq(double px, double py, double pz) {
            double dx = Math.max(Math.abs(minX + 0.5 - px), Math.abs(maxX + 0.5 - px));
            double dy = Math.max(Math.abs(minY + 0.5 - py), Math.abs(maxY + 0.5 - py));
            double dz = Math.max(Math.abs(minZ + 0.5 - pz), Math.abs(maxZ + 0.5 - pz));
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private final VoxelCache voxels;
    private final Factory factory;
    private final ChunkCoverage.Loaded chunks;
    /** Cells of the last complete scan, nearest to the camera first after {@link #update}. */
    private final List<Cell> cells = new ArrayList<>();
    /** The zone the cells and the scan belong to; null if none. */
    private Vec3 zoneCenter;
    private double zoneReach;
    /** Scan in progress, null if none. */
    private ZoneScan scan;
    /** Terrain box the scans of this zone read, for dropping it from the cache. */
    private int boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ;
    private long scanTick;
    private boolean rescanRequested;
    /** Chunks of the box the client had at the last look (or when the last scan started); null if none yet. */
    private ChunkCoverage coverage;
    private long coverageTick;

    /**
     * @param chunks whether the client has a chunk of the level loaded now (the level the voxel cache's source is bound
     *               to when {@link #update} runs)
     */
    ZoneColumns(VoxelCache voxels, Factory factory, ChunkCoverage.Loaded chunks) {
        this.voxels = voxels;
        this.factory = factory;
        this.chunks = chunks;
    }

    /**
     * Brings the ground up to date for this frame of {@code ground}: a new zone is scanned from scratch (nothing of it
     * is drawn until its scan is complete), a known one again when due; a running scan advances within the frame's
     * time. The voxel cache's source must be bound to the level for this frame.
     *
     * @return whether there is ground to draw (a complete scan)
     */
    boolean update(AwakeningField ground, long tick, double camX, double camY, double camZ) {
        if (!ground.center().equals(zoneCenter) || ground.reach() != zoneReach) {
            release();
            zoneCenter = ground.center();
            zoneReach = ground.reach();
            startScan(ground, tick);
        } else {
            if (tick < coverageTick || tick - coverageTick >= COVERAGE_INTERVAL) {
                lookAtCoverage(tick);
            }
            if (scan == null && (tick < scanTick || tick - scanTick >= RESCAN_INTERVAL
                    || rescanRequested && tick - scanTick >= MIN_RESCAN_INTERVAL)) {
                startScan(ground, tick);
            }
        }
        if (scan != null) {
            advanceScan(tick);
        }
        if (cells.isEmpty()) {
            return false;
        }
        for (int i = 0, n = cells.size(); i < n; i++) {
            Cell cell = cells.get(i);
            double dx = 0.5 * (cell.minX + cell.maxX + 1) - camX;
            double dy = 0.5 * (cell.minY + cell.maxY + 1) - camY;
            double dz = 0.5 * (cell.minZ + cell.maxZ + 1) - camZ;
            cell.distanceSq = dx * dx + dy * dy + dz * dz;
        }
        cells.sort(NEAREST_FIRST);
        return true;
    }

    /** The surface of the drawn ground changed: scan the zone again soon. */
    void requestRescan() {
        rescanRequested = true;
    }

    /**
     * Adds this frame's heights of {@code ground} to the columns of the cells in view, nearest cells first: a column
     * {@code frame} already holds for this {@code stamp} (the bump's) gets the height added, others are set and
     * appended.
     */
    void evaluate(AwakeningField ground, Frustum frustum, int stamp, List<Column> frame) {
        double margin = ground.maxHeight() + 1; // the copies move up to maxHeight; a plant rides one block out
        for (int c = 0, nc = cells.size(); c < nc; c++) {
            Cell cell = cells.get(c);
            if (!frustum.isVisible(cell.bounds(margin))) {
                continue;
            }
            List<Column> columns = cell.columns;
            for (int i = 0, n = columns.size(); i < n; i++) {
                Column col = columns.get(i);
                double h = ground.at(col.cx, col.cy, col.cz);
                if (col.stamp == stamp) {
                    col.h += h;
                } else {
                    col.stamp = stamp;
                    col.h = h;
                    col.inBump = false;
                    frame.add(col);
                }
                col.inZone = true;
            }
        }
    }

    /**
     * Picks up to {@code slots} unbaked columns the breathing is going to raise, nearest cells first, whether in view or
     * not, into {@code out}, for baking right away: the zone gets baked ahead in the frames before it first rises,
     * and the ground behind the camera is ready when it turns, instead of rising patch by patch as it is baked.
     */
    void prebake(AwakeningField ground, int slots, List<Column> out) {
        for (int c = 0, nc = cells.size(); c < nc && slots > 0; c++) {
            Cell cell = cells.get(c);
            if (cell.baked) {
                continue;
            }
            boolean all = true;
            for (int i = 0, n = cell.columns.size(); i < n; i++) {
                Column col = cell.columns.get(i);
                if (col.baked || ground.peakBreath(col.cx, col.cy, col.cz) < BumpShape.RENDER_THRESHOLD) {
                    continue;
                }
                if (slots == 0) {
                    all = false;
                    break;
                }
                out.add(col);
                slots--;
            }
            cell.baked = all;
        }
    }

    /**
     * Collects into {@code out} the columns on the front of a step ring: centres within {@link RippleDust#HALF_WIDTH}
     * of the sphere of radius {@code front} around {@code origin} (the rings run in 3D, {@link AwakeningField}).
     */
    void front(Vec3 origin, double front, List<Column> out) {
        double ox = origin.x(), oy = origin.y(), oz = origin.z();
        double near = Math.max(0, front - RippleDust.HALF_WIDTH), far = front + RippleDust.HALF_WIDTH;
        for (int c = 0, nc = cells.size(); c < nc; c++) {
            Cell cell = cells.get(c);
            if (cell.nearSq(ox, oy, oz) > far * far || cell.farSq(ox, oy, oz) < near * near) {
                continue;
            }
            List<Column> columns = cell.columns;
            for (int i = 0, n = columns.size(); i < n; i++) {
                Column col = columns.get(i);
                double dx = col.cx - ox, dy = col.cy - oy, dz = col.cz - oz;
                if (RippleDust.onFront(Math.sqrt(dx * dx + dy * dy + dz * dz), front)) {
                    out.add(col);
                }
            }
        }
    }

    /** Marks every column of the zone as used, so the renderer keeps them while the zone lasts. */
    void touch(long tick) {
        for (int c = 0, nc = cells.size(); c < nc; c++) {
            List<Column> columns = cells.get(c).columns;
            for (int i = 0, n = columns.size(); i < n; i++) {
                columns.get(i).lastUsed = tick;
            }
        }
    }

    /** Forgets the zone and drops its terrain from the cache: the Awakening is over, or far from the camera. */
    void release() {
        if (zoneCenter != null) {
            voxels.invalidateBox(boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ);
        }
        clear();
    }

    /** Forgets the zone (its columns are gone, or the level is): the next {@link #update} scans it from scratch. */
    void clear() {
        cells.clear();
        scan = null;
        zoneCenter = null;
        rescanRequested = false;
        coverage = null;
    }

    private void startScan(AwakeningField ground, long tick) {
        int reach = ground.params().verticalReach();
        scan = new ZoneScan(voxels, ground.center(), ground.reach(), reach, reach);
        boxMinX = scan.minX() - READ_MARGIN;
        boxMinY = scan.minY() - READ_MARGIN;
        boxMinZ = scan.minZ() - READ_MARGIN;
        boxMaxX = scan.maxX() + READ_MARGIN;
        boxMaxY = scan.maxY() + READ_MARGIN;
        boxMaxZ = scan.maxZ() + READ_MARGIN;
        voxels.invalidateBox(boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ); // read the terrain afresh
        scanTick = tick;
        rescanRequested = false;
        coverage = ChunkCoverage.of(boxMinX, boxMinZ, boxMaxX, boxMaxZ, chunks);
        coverageTick = tick;
    }

    /**
     * Asks for a scan once chunks of the zone's terrain arrived that the client did not have at the last look: the
     * running or last scan read them as unknown ground (also chunks that went away and came back). Only arrivals
     * count: the part of a zone beyond the client's view distance, which never arrives, does not keep it scanning.
     */
    private void lookAtCoverage(long tick) {
        ChunkCoverage now = ChunkCoverage.of(boxMinX, boxMinZ, boxMaxX, boxMaxZ, chunks);
        if (now.gainedSince(coverage)) {
            requestRescan();
        }
        coverage = now;
        coverageTick = tick;
    }

    private void advanceScan(long tick) {
        voxels.setTime(tick);
        long start = System.nanoTime();
        while (!scan.advance(SCAN_STEP)) {
            if (System.nanoTime() - start >= SCAN_NANOS) {
                return;
            }
        }
        cells.clear();
        Map<Long, Cell> byKey = new HashMap<>();
        for (SurfacePoint p : scan.points()) {
            Column col = factory.column(p, tick);
            long key = VoxelPos.pack(p.x() >> CELL_SHIFT, p.y() >> CELL_SHIFT, p.z() >> CELL_SHIFT);
            byKey.computeIfAbsent(key, k -> new Cell()).add(col);
        }
        cells.addAll(byKey.values());
        scan = null;
    }
}
