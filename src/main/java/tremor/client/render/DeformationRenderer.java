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
import tremor.client.awakening.AwakeningGround;
import tremor.config.TremorConfig;
import tremor.core.VoxelView;
import tremor.core.deform.SurfaceCollector;
import tremor.core.deform.SurfacePoint;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.shape.AwakeningField;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

/**
 * Additive client-side deformation (SPEC 6.3): the world is never touched; instead copies of the affected surface
 * blocks are drawn pushed out along the surface normal, following the entity's bump as {@link ClientTremor}
 * interpolates it, and, while one runs, the ground ripple of an ALERT freeze (SPEC 8) around it ({@link HeightField}),
 * fading with the bump ({@link Ripple#visibility}). While an Awakening runs in the real world (SPEC 9, phase 1), the
 * ground of its whole zone moves too ({@link AwakeningGround}): it breathes, rings run out from steps, a hill rises
 * under the swallowed player; its height adds to the bump's where both cover a voxel, and is drawn without the entity
 * (sunk during the event) as well.
 * <p>
 * The bump's voxels are re-collected only when the bump has moved or turned noticeably (or every few ticks), over a
 * client-side {@link VoxelCache} of the level, and only as far out as the height can reach the render threshold
 * ({@link RenderReach}): a starting ripple widens the collection once, and it shrinks back once the ripple is over.
 * The zone's voxels are scanned once over several frames and kept ({@link ZoneColumns}); each frame evaluates only
 * those in view. A voxel's block model is tesselated into {@link VertexList}s (with the real biome tint and the
 * light of the open space next to the original block) the first time it rises above the threshold, a limited number
 * per frame, and kept, keyed by position, while the bump stays near or the zone lasts, so a moving bump only bakes the
 * voxels it newly raises; the zone's ground that will breathe is baked ahead in the spare bakes of each frame. Every
 * frame the heights are evaluated and the baked meshes are replayed at that offset, so the per-frame cost is just
 * copying vertices. Opaque layers are drawn after the block entities, translucent and tripwire layers in their own
 * terrain passes so that they blend correctly (and survive Fabulous graphics, which clears its translucent target
 * before the translucent terrain).
 * <p>
 * Once per game tick a running ripple and every step ring also kick up a little dust of the ground along their fronts
 * ({@link #spawnDust}, {@link #spawnRingDust}), from the collected voxels.
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
    /**
     * Drawn columns checked per {@link #validate} at most. A larger drawn set (an Awakening zone) is checked a slice
     * at a time, round and round, with its light re-read on every check: each column is looked at about every
     * {@link #LIGHT_INTERVAL} ticks for the default budget.
     */
    private static final int MAX_VALIDATED = 1024;
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
    /** What a voxel is for the surface ({@link #surface}): not loaded, ground, open. */
    private static final int UNKNOWN = 0, GROUND = 1, OPEN = 2;
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

    /** Columns (baked or not yet) by packed position, reused across collections, the bump's and the zone's alike. */
    private static final Map<Long, Column> columns = new HashMap<>();
    /** Columns of the bump's last collection. */
    private static final List<Column> active = new ArrayList<>();
    /** This frame's columns with a height: the bump's, then the zone's in view not already among them. */
    private static final List<Column> frameColumns = new ArrayList<>();
    /** This frame's columns with a visible height that are not baked yet, then those baked ahead. */
    private static final List<Column> pending = new ArrayList<>();
    /** This frame's baked columns with a visible height, within budget. */
    private static final List<Column> drawn = new ArrayList<>();
    /** Columns on the front of a ripple, gathered for the dust and emptied again by {@link #kickUpDust}. */
    private static final List<Column> dustFront = new ArrayList<>();
    /** Terrain sections in use by packed section position, with the tick they were last (re)read. */
    private static final Map<Long, Long> sectionReads = new HashMap<>();
    private static final ProbeLevel probe = new ProbeLevel();
    private static final RandomSource random = RandomSource.create();
    private static final LevelSource source = new LevelSource();
    private static final VoxelCache voxels = new VoxelCache(source);
    private static final ZoneColumns zone = new ZoneColumns(voxels, DeformationRenderer::column,
            DeformationRenderer::chunkLoaded);
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
    /** Where the next {@link #validate} of a large drawn set starts. */
    private static int validateCursor;
    private static long nextEvictTick;
    /** Game time of the last dust. */
    private static long dustTick = Long.MIN_VALUE;

    // per-frame state, prepared in the first pass and reused by the translucent passes of the same frame
    private static boolean frameReady;
    /** Number of the frame, for {@link Column#stamp}. */
    private static int frameStamp;
    private static boolean frameWarp;
    /** Distance from the camera at which the budget cut an Awakening's ground off ({@link RenderBudget}); NaN: none. */
    private static double frameCut = Double.NaN;
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
        frameColumns.clear();
        pending.clear();
        drawn.clear();
        sectionReads.clear();
        voxels.clear();
        zone.clear();
        AwakeningGround.reset();
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

    /** Brings the collections and the bakes up to date and computes this frame's heights and the columns to draw. */
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
            zone.clear();
            collected = false;
        }
        long tick = level.getGameTime();
        if (due(tick, nextEvictTick, EVICT_INTERVAL)) {
            nextEvictTick = tick + EVICT_INTERVAL;
            zone.touch(tick);
            columns.values().removeIf(col -> tick - col.lastUsed > EVICT_AGE);
        }

        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        net.minecraft.world.phys.Vec3 cam = event.getCamera().getPosition();
        camX = cam.x;
        camY = cam.y;
        camZ = cam.z;
        HeightField bump = bump(level, partialTick, tick);
        AwakeningField ground = ground(level, (double) tick + partialTick, tick);
        if (bump == null && ground == null) {
            return false;
        }

        frameStamp++;
        frameColumns.clear();
        if (bump != null) {
            for (int i = 0, n = active.size(); i < n; i++) {
                Column col = active.get(i);
                col.stamp = frameStamp;
                col.inBump = true;
                col.inZone = false;
                col.h = bump.at(col.cx, col.cy, col.cz);
                frameColumns.add(col);
            }
        }
        if (ground != null) {
            zone.evaluate(ground, event.getFrustum(), frameStamp, frameColumns);
        }
        for (int i = 0, n = frameColumns.size(); i < n; i++) {
            Column col = frameColumns.get(i);
            if (!col.baked && col.h >= BumpShape.RENDER_THRESHOLD) {
                pending.add(col);
            }
        }
        bakePending(level, ground);
        boolean bumpRipple = bump != null && bump.ripple() != null;
        boolean rings = ground != null && !ground.rings().isEmpty();
        if ((bumpRipple || rings) && tick != dustTick && TremorConfig.CLIENT.rippleDust.get()) {
            // Once per game tick (per frame below 20 fps); none while the game time stands still, like the ripples.
            dustTick = tick;
            if (bumpRipple) {
                spawnDust(level, bump.frame(), bump.ripple(), bump.rippleAge());
            }
            if (rings) {
                spawnRingDust(level, ground);
            }
        }
        frameWarp = TremorConfig.CLIENT.style.get() == TremorConfig.Style.WARP;
        int total = 0;
        for (int i = 0, n = frameColumns.size(); i < n; i++) {
            Column col = frameColumns.get(i);
            if (col.h < BumpShape.RENDER_THRESHOLD) {
                continue; // the trailing depression (h < 0) needs hidden real blocks: stage 4
            }
            if (!col.baked || !col.hasGeometry()) {
                continue; // waits for its bake in a later frame, or there is nothing to draw
            }
            drawn.add(col);
            total += col.cost();
        }
        frameCut = Double.NaN;
        int budget = ground != null ? TremorConfig.CLIENT.awakeningMaxBlocks.get()
                : TremorConfig.CLIENT.maxDeformedBlocks.get();
        if (total > budget) {
            trim(budget, ground != null);
        }
        if (frameWarp) {
            for (int i = 0, n = drawn.size(); i < n; i++) {
                warpCorners(drawn.get(i), bump, ground);
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
     * The entity's bump this frame, with the ground ripple of an ALERT freeze while one runs, its voxels collected;
     * null if there is no entity, nothing of it is high enough to draw, or it is too far from the camera.
     */
    private static HeightField bump(ClientLevel level, float partialTick, long tick) {
        ClientTremor.RenderState state = ClientTremor.renderState(level, partialTick);
        if (state == null) {
            return null;
        }
        BumpFrame frame = state.frame();
        BumpParams params = state.params();
        RippleParams ripple = runningRipple(state.rippleAgeSeconds(),
                Ripple.visibility(params.amplitude(), state.fullAmplitude()));
        if (!(params.amplitude() >= BumpShape.RENDER_THRESHOLD) && ripple == null) {
            return null; // dived (or inverted), and its ripple is gone with it, or there is none
        }
        Vec3 c = frame.center();
        RenderStats.recordCenter(c.x(), c.y(), c.z(), System.nanoTime());
        double range = TremorConfig.CLIENT.renderDistance.get();
        double ox = c.x() - camX, oy = c.y() - camY, oz = c.z() - camZ;
        if (ox * ox + oy * oy + oz * oz > range * range) {
            return null;
        }
        SurfaceCollector.Options options = SurfaceCollector.Options.forParams(params);
        double radius = RenderReach.collectRadius(params, ripple, REACH_SLACK, options.normalBand(), RADIUS_STEP);
        if (needsCollect(frame, radius, tick)) {
            collect(level, frame, new SurfaceCollector.Options(radius, options.normalBand(), options.minNormalDot()),
                    tick);
        }
        return new HeightField(params, frame, state.timeSeconds(), TremorConfig.CLIENT.jitter.get(), ripple,
                state.rippleAgeSeconds());
    }

    /**
     * The ground of the Awakening this frame ({@link AwakeningGround}) with its zone scanned ({@link ZoneColumns});
     * null if there is none, it is too far from the camera, or the first scan of its zone is not complete yet.
     *
     * @param gameTime the level's game time plus the partial tick
     */
    private static AwakeningField ground(ClientLevel level, double gameTime, long tick) {
        AwakeningField ground = AwakeningGround.frame(gameTime);
        if (ground == null || !nearCamera(ground)) {
            zone.release();
            return null;
        }
        source.begin(level);
        return zone.update(ground, tick, camX, camY, camZ) ? ground : null;
    }

    /** Whether the zone's ground can lie within the deformation draw distance of the camera. */
    private static boolean nearCamera(AwakeningField ground) {
        double range = TremorConfig.CLIENT.renderDistance.get();
        Vec3 c = ground.center();
        double dx = c.x() - camX, dz = c.z() - camZ, across = range + ground.reach();
        return dx * dx + dz * dz <= across * across
                && Math.abs(c.y() - camY) <= range + ground.params().verticalReach();
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
     * Over budget: keeps whole columns (block + decoration together), nearest to the camera first. While an
     * Awakening is drawn ({@code soft}), the kept ground also lowers smoothly towards the distance where the budget
     * ran out ({@link RenderBudget}), and what falls below the threshold is not drawn.
     */
    private static void trim(int budget, boolean soft) {
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
        if (soft && keep < drawn.size()) {
            frameCut = Math.sqrt(drawn.get(keep).distanceSq);
        }
        while (drawn.size() > keep) {
            drawn.removeLast();
        }
        if (Double.isNaN(frameCut)) {
            return;
        }
        int kept = 0;
        for (int i = 0, n = drawn.size(); i < n; i++) {
            Column col = drawn.get(i);
            col.h *= RenderBudget.fade(Math.sqrt(col.distanceSq), frameCut);
            if (col.h >= BumpShape.RENDER_THRESHOLD) {
                drawn.set(kept++, col);
            }
        }
        while (drawn.size() > kept) {
            drawn.removeLast();
        }
    }

    /** Warp style: the height at the 8 corners of the voxel, from the fields that cover it this frame. */
    private static void warpCorners(Column col, HeightField bump, AwakeningField ground) {
        for (int k = 0; k < 8; k++) {
            double x = col.x + (k & 1), y = col.y + (k >> 1 & 1), z = col.z + (k >> 2 & 1);
            double h = 0;
            if (col.inBump) {
                h += bump.at(x, y, z);
            }
            if (col.inZone) {
                h += ground.at(x, y, z);
            }
            if (!Double.isNaN(frameCut)) {
                double dx = x - camX, dy = y - camY, dz = z - camZ;
                h *= RenderBudget.fade(Math.sqrt(dx * dx + dy * dy + dz * dz), frameCut);
            }
            col.corners[k] = h;
        }
    }

    /**
     * A little dust of the ground kicked up along the front of the running {@code ripple} (SPEC 8 ALERT), so that the
     * rings read where raised copies of a block over the same block show only as thin seams: {@link RippleDust#count}
     * particles on the open faces of random collected voxels on the front circle ({@link #kickUpDust}).
     */
    private static void spawnDust(ClientLevel level, BumpFrame frame, RippleParams ripple, double ageSeconds) {
        int count = RippleDust.count(ripple, ageSeconds, dustShare(), level.random.nextDouble());
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
        kickUpDust(level, count);
    }

    /**
     * The same dust along the fronts of the step rings of an Awakening (SPEC 9), {@link RippleDust#count} particles
     * per ring on the zone's voxels on its front sphere ({@link ZoneColumns#front}). All the rings running at once kick
     * up no more than one ripple at its densest ({@link RippleDust#MAX_PER_TICK}) between them.
     */
    private static void spawnRingDust(ClientLevel level, AwakeningField ground) {
        double share = dustShare(), total = 0;
        for (AwakeningField.Ring ring : ground.rings()) {
            total += Math.min(RippleDust.rate(ring.params(), ring.ageSeconds()), RippleDust.MAX_PER_TICK);
        }
        if (total > RippleDust.MAX_PER_TICK) {
            share *= RippleDust.MAX_PER_TICK / total;
        }
        for (AwakeningField.Ring ring : ground.rings()) {
            int count = RippleDust.count(ring.params(), ring.ageSeconds(), share, level.random.nextDouble());
            if (count > 0) {
                zone.front(ring.origin(), RippleDust.front(ring.params(), ring.ageSeconds()), dustFront);
                kickUpDust(level, count);
            }
        }
    }

    /** Share of the dust the particle setting allows: as with vanilla's rain splashes, half at decreased, none at minimal. */
    private static double dustShare() {
        ParticleStatus setting = Minecraft.getInstance().options.particles().get();
        return setting == ParticleStatus.ALL ? 1 : (setting == ParticleStatus.DECREASED ? 0.5 : 0);
    }

    /**
     * Spawns {@code count} dust particles on {@link #dustFront} and empties it: each a {@link ParticleTypes#BLOCK}
     * particle of the surface block (as vanilla's running and landing dust) on the open face of a random voxel of the
     * front, raised with the copy drawn over the voxel this frame, and thrown out along the surface normal. The level
     * then thins and culls them like any particle. Voxels in chunks that are no longer loaded, or whose open face got
     * covered since the collection, give no dust; no voxels on the front (it ran beyond the collection, where the
     * ripple is too low to draw), no dust.
     */
    private static void kickUpDust(ClientLevel level, int count) {
        if (dustFront.isEmpty()) {
            return;
        }
        RandomSource rand = level.random;
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
            double lift = col.stamp == frameStamp && col.h >= BumpShape.RENDER_THRESHOLD ? col.h : 0;
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
        voxels.setTime(tick);
        refreshSections(frame.center(), options.radius(), tick);
        List<SurfacePoint> points = SurfaceCollector.collect(voxels, frame, options);

        active.clear();
        for (SurfacePoint p : points) {
            active.add(column(p, tick));
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

    /** The column of a collected surface voxel (the bump's or the zone's), made or refreshed, used at {@code tick}. */
    private static Column column(SurfacePoint p, long tick) {
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
        return col;
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
     * are left unbaked (and undrawn) until a later frame. Bakes to spare go to the zone's ground that the breathing is
     * going to raise ({@link ZoneColumns#prebake}), nearest first.
     */
    private static void bakePending(ClientLevel level, AwakeningField ground) {
        if (pending.size() > MAX_BAKES_PER_FRAME) {
            pending.sort(HIGHEST_FIRST);
            pending.subList(MAX_BAKES_PER_FRAME, pending.size()).clear();
        }
        LevelVoxelView view = null;
        if (!pending.isEmpty()) {
            view = source.begin(level);
            for (int i = 0, n = pending.size(); i < n; i++) {
                bake(level, view, pending.get(i));
            }
        }
        int spare = MAX_BAKES_PER_FRAME - pending.size();
        pending.clear();
        if (ground != null && spare > 0) {
            zone.prebake(ground, spare, pending);
            if (!pending.isEmpty() && view == null) {
                view = source.begin(level);
            }
            for (int i = 0, n = pending.size(); i < n; i++) {
                bake(level, view, pending.get(i));
            }
            pending.clear();
        }
    }

    /**
     * Re-bakes drawn columns whose block (or the block riding on it) changed, and every {@link #LIGHT_INTERVAL} ticks
     * those whose light changed; at most {@link #MAX_VALIDATED} per call. A change that moves the surface (ground
     * became open or the other way round, or the chunk came or went: {@link #surface}) also has the terrain around it
     * re-read, and the bump's voxels collected and the zone scanned again; one that does not (a crop growing, a door,
     * redstone power, a water level) only re-bakes the column, since the surface reads nothing else.
     */
    private static void validate(ClientLevel level, long tick) {
        nextValidateTick = tick + VALIDATE_INTERVAL;
        boolean light = due(tick, nextLightTick, LIGHT_INTERVAL);
        if (light) {
            nextLightTick = tick + LIGHT_INTERVAL;
        }
        int n = drawn.size();
        boolean sliced = n > MAX_VALIDATED;
        int first = sliced ? validateCursor % n : 0, count = Math.min(n, MAX_VALIDATED);
        validateCursor = sliced ? (first + count) % n : 0;
        light |= sliced;
        LevelVoxelView view = source.begin(level);
        for (int j = 0; j < count; j++) {
            Column col = drawn.get((first + j) % n);
            BlockPos decoPos = col.decoPos;
            BlockState state = view.stateAt(col.x, col.y, col.z);
            BlockState deco = view.stateAt(decoPos.getX(), decoPos.getY(), decoPos.getZ());
            boolean blocksChanged = state != col.state || deco != col.decoState;
            boolean surfaceChanged = blocksChanged
                    && (surface(view, state, col.pos) != surface(view, col.state, col.pos)
                    || surface(view, deco, decoPos) != surface(view, col.decoState, decoPos));
            boolean lightChanged = light && col.lightPos != null
                    && (level.getBrightness(LightLayer.SKY, col.lightPos) != col.skyLight
                    || level.getBrightness(LightLayer.BLOCK, col.lightPos) != col.blockLight);
            if (blocksChanged || lightChanged) {
                bake(level, view, col);
            }
            if (surfaceChanged) {
                // The surface around it changed too: re-read the terrain there and collect again.
                voxels.invalidateBox(col.x - NORMAL_REACH, col.y - NORMAL_REACH, col.z - NORMAL_REACH,
                        col.x + NORMAL_REACH, col.y + NORMAL_REACH, col.z + NORMAL_REACH);
                recollect = true;
                if (col.inZone) {
                    zone.requestRescan();
                }
            }
        }
    }

    /**
     * What a block state makes of its voxel for the surface, as the terrain cache reads it ({@link LevelVoxelView}):
     * {@link #UNKNOWN} (no chunk), {@link #GROUND} or {@link #OPEN}.
     */
    private static int surface(LevelVoxelView view, BlockState state, BlockPos pos) {
        return state == null ? UNKNOWN : view.isSkin(state, pos) ? GROUND : OPEN;
    }

    /** Whether the client has the chunk now (the zone's terrain arriving, {@link ZoneColumns}). */
    private static boolean chunkLoaded(int chunkX, int chunkZ) {
        ClientLevel level = boundLevel;
        return level != null && level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
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
