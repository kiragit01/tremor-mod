package tremor.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import tremor.client.ClientTremor;
import tremor.config.TremorConfig;
import tremor.core.VoxelView;
import tremor.core.deform.SurfaceCollector;
import tremor.core.deform.SurfacePoint;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.shape.BumpFrame;
import tremor.core.shape.BumpParams;
import tremor.core.shape.BumpShape;
import tremor.core.shape.Ripple;
import tremor.core.shape.RippleParams;
import tremor.core.voxel.VoxelCache;
import tremor.world.LevelVoxelView;
import tremor.world.SurfaceLight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * Additive client-side deformation (SPEC 6.3): the world is never touched; instead copies of the affected surface
 * blocks are drawn pushed out along the surface normal, following the entity's bump as {@link ClientTremor}
 * interpolates it, and, while one runs, the ground ripple of an ALERT freeze (SPEC 8) around it ({@link HeightField}),
 * fading with the bump ({@link Ripple#visibility}).
 * <p>
 * The deformed voxels are re-collected only when the bump has moved or turned noticeably (or every few ticks), over a
 * client-side {@link VoxelCache} of the level, and only as far out as the height can reach the render threshold
 * ({@link RenderReach}): a starting ripple widens the collection once, and it shrinks back once the ripple is over.
 * A voxel's block model is tesselated into {@link VertexList}s (with the real biome tint and the
 * light of the open space next to the original block) the first time it rises above the threshold, a limited number
 * per frame, and kept, keyed by position, while the bump stays near, so a moving bump only bakes the voxels it newly
 * raises. Every frame the heights are evaluated for the current frame of the bump and the baked meshes are replayed at
 * that offset, so the per-frame cost is just copying vertices. Opaque layers are drawn after the block entities,
 * translucent and tripwire layers in their own terrain passes so that they blend correctly (and survive Fabulous
 * graphics, which clears its translucent target before the translucent terrain).
 * <p>
 * Once per game tick a running ripple also kicks up a little dust of the ground along its front ({@link #spawnDust}),
 * from the collected voxels.
 */
public final class DeformationRenderer {
    private static final List<RenderType> OPAQUE = List.of(RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout());
    /** Re-collect the deformed voxels when the bump centre moved this far (blocks) since the last collection, */
    private static final double RECOLLECT_DISTANCE = 0.5;
    /** ... its normal turned by more than 10 degrees, */
    private static final double RECOLLECT_NORMAL_COS = Math.cos(Math.toRadians(10));
    /** ... or this many ticks passed. */
    private static final int RECOLLECT_INTERVAL = 10;
    /**
     * Added to the reach of the bump for the collection: the centre moves up to {@link #RECOLLECT_DISTANCE} and the
     * normal turns a little before the next collection.
     */
    private static final double REACH_SLACK = RECOLLECT_DISTANCE + 1;
    /**
     * The collection radius is rounded up to this step and re-collected when it grows, or shrinks by more than
     * {@link #RADIUS_SHRINK}, so a fading amplitude does not re-collect every frame.
     */
    private static final double RADIUS_STEP = 0.5;
    private static final double RADIUS_SHRINK = 1.0;
    /** Columns baked per frame at most; the other newly raised ones wait for the next frames. */
    private static final int MAX_BAKES_PER_FRAME = 48;
    /** Ticks between checks whether the blocks under the drawn copies changed. */
    private static final int VALIDATE_INTERVAL = 5;
    /** Ticks between re-reads of the light of the drawn copies. */
    private static final int LIGHT_INTERVAL = 20;
    /** Columns (baked or not) the bump has not covered for this many ticks are dropped. */
    private static final int EVICT_AGE = 100;
    private static final int EVICT_INTERVAL = 20;
    /**
     * The client gets no block change events, so cached terrain sections are re-read once they are this old (ticks).
     * Only a share of the sections in use is re-read per collection, so a resting bump does not re-read its whole
     * neighbourhood in a single frame.
     */
    private static final int SECTION_MAX_AGE = 40;
    private static final int SECTION_REFRESH_SHARE = 4;
    private static final int MIN_SECTION_REFRESH = 2;
    /** Sections the refresh lost track of (if any) are dropped after this many ticks. */
    private static final int SECTION_EXPIRE = 400;
    /** Surface and normal estimation (SPEC 6.2) read up to this many voxels beyond the collected ones. */
    private static final int NORMAL_REACH = 2;
    /**
     * Constant depth bias of the copies, so they win against the original block's faces they exactly overlap. No
     * slope factor: that would also push faces seen edge-on (the shared sides of neighbouring copies) through the
     * surface in front of them.
     */
    private static final float POLYGON_OFFSET_FACTOR = 0f;
    private static final float POLYGON_OFFSET_UNITS = -4f;
    /**
     * Ripple dust starts this far out of the open face, so that the particle's box does not begin inside the block (as
     * vanilla's running dust starts 0.1 above the feet).
     */
    private static final double DUST_CLEARANCE = 0.1;
    /**
     * Velocity along the surface normal handed to a dust particle. A particle turns what it is handed into a small
     * random speed (the Particle constructor adds noise of up to 0.4 per axis, then rescales) and so keeps roughly its
     * direction only; vanilla's running dust hands 1.5 upward the same way.
     */
    private static final double DUST_LAUNCH = 1.5;
    private static final Comparator<Column> NEAREST_FIRST = Comparator.comparingDouble(c -> c.distanceSq);
    private static final Comparator<Column> HIGHEST_FIRST = Comparator.comparingDouble(c -> -c.h);

    /** One deformed surface voxel: where it is and, once baked, its geometry per render layer. */
    private static final class Column {
        final int x, y, z;
        final BlockPos pos;
        /** Where a plant/snow layer riding on the voxel sits. */
        final BlockPos decoPos;
        final double cx, cy, cz;
        final Direction axis;
        /** Smoothed surface normal, refreshed by every collection. */
        Vec3 normal;
        BlockState state;
        BlockState decoState;
        BlockPos lightPos;
        int skyLight;
        int blockLight;
        /** Block-local model of the voxel itself and of a plant/snow layer sitting on it (offset by {@link #axis}). */
        final Map<RenderType, VertexList> meshes = new IdentityHashMap<>();
        final Map<RenderType, VertexList> decoMeshes = new IdentityHashMap<>();
        /** Whether the meshes were baked; columns start unbaked and are baked once they rise above the threshold. */
        boolean baked;
        long lastUsed;
        double h;
        /** h at the 8 corners of the voxel, for the warp style. Index: x + 2y + 4z. */
        final double[] corners = new double[8];
        double distanceSq;

        Column(int x, int y, int z, Direction axis, Vec3 normal) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.pos = new BlockPos(x, y, z);
            this.decoPos = pos.relative(axis);
            this.cx = x + 0.5;
            this.cy = y + 0.5;
            this.cz = z + 0.5;
            this.axis = axis;
            this.normal = normal;
        }

        boolean hasGeometry() {
            return !meshes.isEmpty() || !decoMeshes.isEmpty();
        }

        /** Copies drawn: the stack filling the gap down to the original, plus the decoration. */
        int cost() {
            return (meshes.isEmpty() ? 0 : (int) Math.ceil(h)) + (decoMeshes.isEmpty() ? 0 : 1);
        }
    }

    /**
     * Source of the terrain cache: the level read through a {@link LevelVoxelView} that is replaced for every pass,
     * because a view keeps the last chunk it touched, which a later pass must not read after the chunk was unloaded
     * or re-sent.
     */
    private static final class LevelSource implements VoxelView {
        private LevelVoxelView view;

        LevelVoxelView begin(ClientLevel level) {
            view = new LevelVoxelView(level);
            return view;
        }

        void clear() {
            view = null;
        }

        @Override
        public boolean isSolid(int x, int y, int z) {
            return view.isSolid(x, y, z);
        }

        @Override
        public boolean isKnown(int x, int y, int z) {
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
    }

    /** Columns (baked or not yet) by packed position, reused across collections. */
    private static final Map<Long, Column> columns = new HashMap<>();
    /** Columns of the last collection. */
    private static final List<Column> active = new ArrayList<>();
    /** This frame's columns with a visible height that are not baked yet. */
    private static final List<Column> pending = new ArrayList<>();
    /** This frame's baked columns with a visible height, within budget. */
    private static final List<Column> drawn = new ArrayList<>();
    /** Columns on the ripple's front, gathered by {@link #spawnDust} and emptied again before it returns. */
    private static final List<Column> dustFront = new ArrayList<>();
    /** Terrain sections in use by packed section position, with the tick they were last (re)read. */
    private static final Map<Long, Long> sectionReads = new HashMap<>();
    private static final ProbeLevel probe = new ProbeLevel();
    private static final RandomSource random = RandomSource.create();
    private static final LevelSource source = new LevelSource();
    private static VoxelCache voxels;
    private static MultiBufferSource.BufferSource buffers;

    private static ClientLevel boundLevel;
    private static boolean modelsChanged;
    private static boolean collected;
    private static boolean recollect;
    private static double collectX, collectY, collectZ;
    private static Vec3 collectNormal = Vec3.UNIT_Y;
    private static double collectRadius;
    private static long collectTick;
    private static long nextValidateTick;
    private static long nextLightTick;
    private static long nextEvictTick;
    /** Game time of the last {@link #spawnDust}. */
    private static long dustTick = Long.MIN_VALUE;

    // per-frame state, prepared in the first pass and reused by the translucent passes of the same frame
    private static boolean frameReady;
    private static boolean frameWarp;
    private static double camX, camY, camZ;
    private static int frameCopies;
    private static int frameVertices;
    private static long pendingNanos;

    private DeformationRenderer() {
    }

    /** Forgets all baked geometry, e.g. after a resource reload moved the textures around the atlas. */
    public static void invalidate() {
        modelsChanged = true;
    }

    /** Drops everything that references the old level. */
    public static void reset() {
        columns.clear();
        active.clear();
        pending.clear();
        drawn.clear();
        sectionReads.clear();
        if (voxels != null) {
            voxels.clear();
        }
        source.clear();
        boundLevel = null;
        collected = false;
        frameReady = false;
        probe.set(null, 0, 0);
    }

    public static void onModelBakingCompleted(ModelEvent.BakingCompleted event) {
        invalidate();
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            long start = System.nanoTime();
            frameReady = prepare(event);
            if (frameReady) {
                for (RenderType layer : OPAQUE) {
                    draw(layer);
                }
                RenderStats.record(System.nanoTime() - start + pendingNanos, frameCopies, frameVertices);
            }
            pendingNanos = 0;
        } else if (frameReady && stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            long start = System.nanoTime();
            draw(RenderType.translucent());
            pendingNanos += System.nanoTime() - start;
        } else if (frameReady && stage == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS) {
            long start = System.nanoTime();
            draw(RenderType.tripwire());
            pendingNanos += System.nanoTime() - start;
        }
    }

    /** Brings the collection and the bakes up to date and computes this frame's heights and the columns to draw. */
    private static boolean prepare(RenderLevelStageEvent event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != boundLevel) {
            reset();
            boundLevel = level;
        }
        drawn.clear();
        if (level == null) {
            return false;
        }
        if (modelsChanged) {
            modelsChanged = false;
            columns.clear();
            active.clear();
            collected = false;
        }
        long tick = level.getGameTime();
        if (due(tick, nextEvictTick, EVICT_INTERVAL)) {
            nextEvictTick = tick + EVICT_INTERVAL;
            columns.values().removeIf(col -> tick - col.lastUsed > EVICT_AGE);
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        ClientTremor.RenderState state = ClientTremor.renderState(level, partialTick);
        if (state == null) {
            return false;
        }
        BumpFrame frame = state.frame();
        BumpParams params = state.params();
        RippleParams ripple = runningRipple(state.rippleAgeSeconds(),
                Ripple.visibility(params.amplitude(), state.fullAmplitude()));
        if (!(params.amplitude() >= BumpShape.RENDER_THRESHOLD) && ripple == null) {
            return false; // dived (or inverted), and its ripple is gone with it, or there is none
        }
        net.minecraft.world.phys.Vec3 cam = event.getCamera().getPosition();
        camX = cam.x;
        camY = cam.y;
        camZ = cam.z;
        Vec3 c = frame.center();
        RenderStats.recordCenter(c.x(), c.y(), c.z(), System.nanoTime());
        double range = TremorConfig.CLIENT.renderDistance.get();
        double ox = c.x() - camX, oy = c.y() - camY, oz = c.z() - camZ;
        if (ox * ox + oy * oy + oz * oz > range * range) {
            return false;
        }
        SurfaceCollector.Options options = SurfaceCollector.Options.forParams(params);
        double radius = RenderReach.collectRadius(params, ripple, REACH_SLACK, options.normalBand(), RADIUS_STEP);
        if (needsCollect(frame, radius, tick)) {
            collect(level, frame, new SurfaceCollector.Options(radius, options.normalBand(), options.minNormalDot()),
                    tick);
        }

        HeightField field = new HeightField(params, frame, state.timeSeconds(), TremorConfig.CLIENT.jitter.get(),
                ripple, state.rippleAgeSeconds());
        frameWarp = TremorConfig.CLIENT.style.get() == TremorConfig.Style.WARP;
        for (int i = 0, n = active.size(); i < n; i++) {
            Column col = active.get(i);
            col.h = field.at(col.cx, col.cy, col.cz);
            if (!col.baked && col.h >= BumpShape.RENDER_THRESHOLD) {
                pending.add(col);
            }
        }
        bakePending(level);
        if (ripple != null && tick != dustTick && TremorConfig.CLIENT.rippleDust.get()) {
            // Once per game tick (per frame below 20 fps); none while the game time stands still, like the ripple.
            dustTick = tick;
            spawnDust(level, frame, ripple, state.rippleAgeSeconds());
        }
        int total = 0;
        for (int i = 0, n = active.size(); i < n; i++) {
            Column col = active.get(i);
            if (col.h < BumpShape.RENDER_THRESHOLD) {
                continue; // the trailing depression (h < 0) needs hidden real blocks: stage 4
            }
            if (!col.baked || !col.hasGeometry()) {
                continue; // waits for its bake in a later frame, or there is nothing to draw
            }
            if (frameWarp) {
                for (int k = 0; k < 8; k++) {
                    col.corners[k] = field.at(col.x + (k & 1), col.y + (k >> 1 & 1), col.z + (k >> 2 & 1));
                }
            }
            drawn.add(col);
            total += col.cost();
        }
        int budget = TremorConfig.CLIENT.maxDeformedBlocks.get();
        if (total > budget) {
            // Over budget: keep whole columns (block + decoration together), nearest to the camera first.
            for (int i = 0, n = drawn.size(); i < n; i++) {
                Column col = drawn.get(i);
                double dx = col.cx - camX, dy = col.cy - camY, dz = col.cz - camZ;
                col.distanceSq = dx * dx + dy * dy + dz * dz;
            }
            drawn.sort(NEAREST_FIRST);
            int used = 0, keep = 0;
            while (keep < drawn.size() && used + drawn.get(keep).cost() <= budget) {
                used += drawn.get(keep++).cost();
            }
            while (drawn.size() > keep) {
                drawn.removeLast();
            }
        }
        if (due(tick, nextValidateTick, VALIDATE_INTERVAL)) {
            validate(level, tick);
        }
        frameCopies = 0;
        frameVertices = 0;
        return !drawn.isEmpty();
    }

    /**
     * The ground ripple running at {@code ageSeconds} (NaN: none has started), as high as the client config says
     * times {@code visibility} ({@link Ripple#visibility}: it fades with the bump, so none shows around a bump hidden
     * in a dive); null if there is none, it is over, it is flat, or the client config switches it off.
     */
    private static RippleParams runningRipple(double ageSeconds, double visibility) {
        RippleParams ripple = RippleParams.defaults();
        if (!Ripple.active(ripple, ageSeconds) || !TremorConfig.CLIENT.ripple.get()) {
            return null;
        }
        double amplitude = TremorConfig.CLIENT.rippleAmplitude.get() * visibility;
        return amplitude > 0 ? ripple.withAmplitude(amplitude) : null;
    }

    /**
     * A little dust of the ground kicked up along the front of the running {@code ripple} (SPEC 8 ALERT), so that the
     * rings read where raised copies of a block over the same block show only as thin seams: {@link RippleDust#count}
     * particles, each a {@link ParticleTypes#BLOCK} particle of the surface block (as vanilla's running and landing
     * dust) on the open face of a random collected voxel on the front circle, raised with the copy drawn over the
     * voxel, and thrown out along the surface normal. As with vanilla's rain splashes, half as many at the decreased
     * particle setting and none at minimal; the level then thins and culls them like any particle. Voxels in chunks
     * that are no longer loaded, or whose open face got covered since the collection, give no dust.
     */
    private static void spawnDust(ClientLevel level, BumpFrame frame, RippleParams ripple, double ageSeconds) {
        ParticleStatus setting = Minecraft.getInstance().options.particles().get();
        double share = setting == ParticleStatus.ALL ? 1 : (setting == ParticleStatus.DECREASED ? 0.5 : 0);
        RandomSource rand = level.random;
        int count = RippleDust.count(ripple, ageSeconds, share, rand.nextDouble());
        if (count == 0) {
            return;
        }
        double front = RippleDust.front(ripple, ageSeconds);
        for (int i = 0, n = active.size(); i < n; i++) {
            Column col = active.get(i);
            if (RippleDust.onFront(HeightField.tangentDistance(frame, col.cx, col.cy, col.cz), front)) {
                dustFront.add(col);
            }
        }
        if (dustFront.isEmpty()) {
            return; // no ground on the front, or it ran beyond the collection, where the ripple is too low to draw
        }
        LevelVoxelView view = source.begin(level);
        double out = 0.5 + DUST_CLEARANCE;
        for (int i = 0; i < count; i++) {
            Column col = dustFront.get(rand.nextInt(dustFront.size()));
            BlockState block = view.stateAt(col.x, col.y, col.z);
            BlockPos open = col.decoPos;
            if (block == null || block.getRenderShape() == RenderShape.INVISIBLE
                    || !view.isOpen(open.getX(), open.getY(), open.getZ())) {
                continue;
            }
            Direction axis = col.axis;
            double x = col.cx + axis.getStepX() * out;
            double y = col.cy + axis.getStepY() * out;
            double z = col.cz + axis.getStepZ() * out;
            double u = rand.nextDouble() - 0.5, v = rand.nextDouble() - 0.5; // anywhere on the face
            switch (axis.getAxis()) {
                case X -> {
                    y += u;
                    z += v;
                }
                case Y -> {
                    x += u;
                    z += v;
                }
                case Z -> {
                    x += u;
                    y += v;
                }
            }
            Vec3 normal = col.normal;
            double lift = col.h >= BumpShape.RENDER_THRESHOLD ? col.h : 0;
            level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, block).setPos(col.pos),
                    x + normal.x() * lift, y + normal.y() * lift, z + normal.z() * lift,
                    normal.x() * DUST_LAUNCH, normal.y() * DUST_LAUNCH, normal.z() * DUST_LAUNCH);
        }
        dustFront.clear();
    }

    /** Whether a periodic job scheduled for {@code next} should run (also if the game time jumped back). */
    private static boolean due(long tick, long next, int interval) {
        return tick >= next || next - tick > interval;
    }

    /** @param radius collection radius this frame needs, see {@link RenderReach#collectRadius} */
    private static boolean needsCollect(BumpFrame frame, double radius, long tick) {
        if (!collected || recollect || tick - collectTick >= RECOLLECT_INTERVAL || tick < collectTick) {
            return true;
        }
        Vec3 c = frame.center();
        double dx = c.x() - collectX, dy = c.y() - collectY, dz = c.z() - collectZ;
        return dx * dx + dy * dy + dz * dz > RECOLLECT_DISTANCE * RECOLLECT_DISTANCE
                || frame.normal().dot(collectNormal) < RECOLLECT_NORMAL_COS
                || radius > collectRadius || radius < collectRadius - RADIUS_SHRINK;
    }

    /**
     * Finds the voxels the bump may raise now. New ones are not baked here but once they rise above the threshold
     * ({@link #bakePending}).
     */
    private static void collect(ClientLevel level, BumpFrame frame, SurfaceCollector.Options options, long tick) {
        source.begin(level);
        if (voxels == null) {
            voxels = new VoxelCache(source);
        }
        voxels.setTime(tick);
        refreshSections(frame.center(), options.radius(), tick);
        List<SurfacePoint> points = SurfaceCollector.collect(voxels, frame, options);

        active.clear();
        for (SurfacePoint p : points) {
            long key = VoxelPos.pack(p.x(), p.y(), p.z());
            Vec3 n = p.normal();
            Direction axis = Direction.getNearest(n.x(), n.y(), n.z());
            Column col = columns.get(key);
            if (col == null || col.axis != axis) {
                col = new Column(p.x(), p.y(), p.z(), axis, n);
                columns.put(key, col);
            } else if (!col.hasGeometry()) {
                col.baked = false; // baked empty (no model, chunk not loaded): look again when it rises next
            }
            col.normal = n;
            col.lastUsed = tick;
            active.add(col);
        }
        Vec3 c = frame.center();
        collectX = c.x();
        collectY = c.y();
        collectZ = c.z();
        collectNormal = frame.normal();
        collectRadius = options.radius();
        collectTick = tick;
        collected = true;
        recollect = false;
    }

    /**
     * Keeps the cached terrain around the bump fresh: sections older than {@link #SECTION_MAX_AGE} are re-read, a share
     * of them per collection; sections the bump has left are dropped once they are that old.
     */
    private static void refreshSections(Vec3 center, double radius, long tick) {
        int reach = (int) Math.ceil(radius) + NORMAL_REACH + 1;
        int bx = (int) Math.floor(center.x()), by = (int) Math.floor(center.y()), bz = (int) Math.floor(center.z());
        int x0 = bx - reach >> 4, x1 = bx + reach >> 4;
        int y0 = by - reach >> 4, y1 = by + reach >> 4;
        int z0 = bz - reach >> 4, z1 = bz + reach >> 4;
        int inUse = 0;
        for (int sy = y0; sy <= y1; sy++) {
            for (int sz = z0; sz <= z1; sz++) {
                for (int sx = x0; sx <= x1; sx++) {
                    sectionReads.putIfAbsent(VoxelPos.pack(sx, sy, sz), tick);
                    inUse++;
                }
            }
        }
        int refresh = Math.max(MIN_SECTION_REFRESH, inUse / SECTION_REFRESH_SHARE);
        for (Iterator<Map.Entry<Long, Long>> it = sectionReads.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Long> entry = it.next();
            long read = entry.getValue();
            if (tick - read < SECTION_MAX_AGE && read <= tick) {
                continue;
            }
            long key = entry.getKey();
            int sx = VoxelPos.x(key), sy = VoxelPos.y(key), sz = VoxelPos.z(key);
            boolean inside = sx >= x0 && sx <= x1 && sy >= y0 && sy <= y1 && sz >= z0 && sz <= z1;
            if (!inside) {
                voxels.invalidate(sx << 4, sy << 4, sz << 4);
                it.remove();
            } else if (refresh > 0) {
                voxels.invalidate(sx << 4, sy << 4, sz << 4);
                entry.setValue(tick);
                refresh--;
            }
        }
        voxels.expire(SECTION_EXPIRE);
    }

    /**
     * Bakes this frame's newly raised columns, the highest first and at most {@link #MAX_BAKES_PER_FRAME}; the rest
     * are left unbaked (and undrawn) until a later frame.
     */
    private static void bakePending(ClientLevel level) {
        if (pending.isEmpty()) {
            return;
        }
        if (pending.size() > MAX_BAKES_PER_FRAME) {
            pending.sort(HIGHEST_FIRST);
        }
        LevelVoxelView view = source.begin(level);
        for (int i = 0, n = Math.min(pending.size(), MAX_BAKES_PER_FRAME); i < n; i++) {
            bake(level, view, pending.get(i));
        }
        pending.clear();
    }

    /**
     * Re-bakes drawn columns whose block (or the block riding on it) changed, and every {@link #LIGHT_INTERVAL} ticks
     * those whose light changed.
     */
    private static void validate(ClientLevel level, long tick) {
        nextValidateTick = tick + VALIDATE_INTERVAL;
        boolean light = due(tick, nextLightTick, LIGHT_INTERVAL);
        if (light) {
            nextLightTick = tick + LIGHT_INTERVAL;
        }
        LevelVoxelView view = source.begin(level);
        for (int i = 0, n = drawn.size(); i < n; i++) {
            Column col = drawn.get(i);
            boolean blocksChanged = view.stateAt(col.x, col.y, col.z) != col.state
                    || view.stateAt(col.decoPos.getX(), col.decoPos.getY(), col.decoPos.getZ()) != col.decoState;
            boolean lightChanged = light && col.lightPos != null
                    && (level.getBrightness(LightLayer.SKY, col.lightPos) != col.skyLight
                    || level.getBrightness(LightLayer.BLOCK, col.lightPos) != col.blockLight);
            if (blocksChanged || lightChanged) {
                bake(level, view, col);
            }
            if (blocksChanged && voxels != null) {
                // The surface around it changed too: re-read the terrain there and collect again.
                voxels.invalidateBox(col.x - NORMAL_REACH, col.y - NORMAL_REACH, col.z - NORMAL_REACH,
                        col.x + NORMAL_REACH, col.y + NORMAL_REACH, col.z + NORMAL_REACH);
                recollect = true;
            }
        }
    }

    private static void draw(RenderType layer) {
        MultiBufferSource.BufferSource out = buffers();
        VertexConsumer consumer = null;
        for (int i = 0, n = drawn.size(); i < n; i++) {
            Column col = drawn.get(i);
            VertexList mesh = col.meshes.get(layer);
            VertexList deco = col.decoMeshes.get(layer);
            if (mesh == null && deco == null) {
                continue;
            }
            if (consumer == null) {
                consumer = out.getBuffer(layer);
            }
            float bx = (float) (col.x - camX);
            float by = (float) (col.y - camY);
            float bz = (float) (col.z - camZ);
            Vec3 normal = col.normal;
            if (mesh != null) {
                int stack = (int) Math.ceil(col.h); // copies at h, h-1, ... fill the gap down to the original
                for (int k = 0; k < stack; k++) {
                    if (frameWarp) {
                        emitWarped(consumer, mesh, col, k, bx, by, bz, 0, 0, 0);
                    } else {
                        double off = col.h - k;
                        mesh.emit(consumer, bx + (float) (normal.x() * off), by + (float) (normal.y() * off),
                                bz + (float) (normal.z() * off));
                    }
                    frameVertices += mesh.size();
                }
                frameCopies += stack;
            }
            if (deco != null) {
                int ox = col.axis.getStepX(), oy = col.axis.getStepY(), oz = col.axis.getStepZ();
                if (frameWarp) {
                    emitWarped(consumer, deco, col, 0, bx, by, bz, ox, oy, oz);
                } else {
                    deco.emit(consumer, bx + ox + (float) (normal.x() * col.h), by + oy + (float) (normal.y() * col.h),
                            bz + oz + (float) (normal.z() * col.h));
                }
                frameVertices += deco.size();
                frameCopies++;
            }
        }
        if (consumer == null) {
            return;
        }
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(POLYGON_OFFSET_FACTOR, POLYGON_OFFSET_UNITS);
        out.endBatch(layer);
        RenderSystem.polygonOffset(0f, 0f);
        RenderSystem.disablePolygonOffset();
    }

    /**
     * Warp style: each vertex moves by h at that vertex (trilinear from the voxel corners), so neighbouring copies
     * share displaced corners and form one continuous mound instead of terraces.
     */
    private static void emitWarped(VertexConsumer consumer, VertexList mesh, Column col, int layer,
                                   float bx, float by, float bz, int ox, int oy, int oz) {
        Vec3 n = col.normal;
        double[] h = col.corners;
        for (int i = 0; i < mesh.size(); i++) {
            float lx = mesh.x(i) + ox, ly = mesh.y(i) + oy, lz = mesh.z(i) + oz;
            double tx = clamp01(lx), ty = clamp01(ly), tz = clamp01(lz);
            double h00 = lerp(h[0], h[1], tx), h10 = lerp(h[2], h[3], tx);
            double h01 = lerp(h[4], h[5], tx), h11 = lerp(h[6], h[7], tx);
            double hv = lerp(lerp(h00, h10, ty), lerp(h01, h11, ty), tz) - layer;
            mesh.emitAt(consumer, i, bx + lx + (float) (n.x() * hv), by + ly + (float) (n.y() * hv),
                    bz + lz + (float) (n.z() * hv));
        }
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /** (Re-)tesselates the voxel and the plant/snow layer on it as they are in the level now. */
    private static void bake(ClientLevel level, LevelVoxelView view, Column col) {
        col.baked = true;
        col.meshes.clear();
        col.decoMeshes.clear();
        BlockPos pos = col.pos, decoPos = col.decoPos;
        BlockState state = view.stateAt(col.x, col.y, col.z);
        BlockState deco = view.stateAt(decoPos.getX(), decoPos.getY(), decoPos.getZ());
        col.state = state;
        col.decoState = deco;
        if (state == null || state.getRenderShape() != RenderShape.MODEL) {
            col.lightPos = null;
            return;
        }
        col.lightPos = SurfaceLight.brightestOpenNeighbour(level, view, pos, col.axis);
        col.skyLight = level.getBrightness(LightLayer.SKY, col.lightPos);
        col.blockLight = level.getBrightness(LightLayer.BLOCK, col.lightPos);
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        probe.set(level, col.skyLight, col.blockLight);
        bakeBlock(dispatcher, level, state, pos, col.meshes);
        // Grass, flowers and snow layers ride on top of the ground they grow on.
        if (col.axis == Direction.UP && deco != null && !deco.isAir() && deco.getRenderShape() == RenderShape.MODEL
                && !deco.hasBlockEntity() && deco.getFluidState().isEmpty()
                && view.isOpen(decoPos.getX(), decoPos.getY(), decoPos.getZ())) {
            bakeBlock(dispatcher, level, deco, decoPos, col.decoMeshes);
        }
        probe.set(null, 0, 0);
    }

    private static void bakeBlock(BlockRenderDispatcher dispatcher, ClientLevel level, BlockState state, BlockPos pos,
                                  Map<RenderType, VertexList> into) {
        BakedModel model = dispatcher.getBlockModel(state);
        ModelData data = model.getModelData(probe, pos, state, level.getModelData(pos));
        random.setSeed(state.getSeed(pos));
        for (RenderType type : model.getRenderTypes(state, random, data)) {
            VertexList mesh = into.computeIfAbsent(type, t -> new VertexList());
            dispatcher.renderBatched(state, pos, probe, new PoseStack(), mesh, false, random, data, type);
        }
    }

    private static MultiBufferSource.BufferSource buffers() {
        if (buffers == null) {
            SequencedMap<RenderType, ByteBufferBuilder> fixed = new LinkedHashMap<>();
            for (RenderType layer : RenderType.chunkBufferLayers()) {
                fixed.put(layer, new ByteBufferBuilder(layer.bufferSize()));
            }
            buffers = MultiBufferSource.immediateWithBuffers(fixed, new ByteBufferBuilder(256));
        }
        return buffers;
    }
}
