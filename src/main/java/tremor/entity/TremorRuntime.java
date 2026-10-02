package tremor.entity;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.PacketDistributor;
import tremor.Tremor;
import tremor.config.TremorConfig;
import tremor.core.VoxelView;
import tremor.core.behavior.Stage;
import tremor.core.graph.SurfaceGraph;
import tremor.core.hearing.Hearing;
import tremor.core.hearing.HearingParams;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.motion.Crawler;
import tremor.core.path.Path;
import tremor.core.path.PathSearch;
import tremor.core.path.PathSpline;
import tremor.core.shape.BumpParams;
import tremor.core.voxel.VoxelCache;
import tremor.hearing.Perception;
import tremor.hearing.Vibration;
import tremor.network.TremorShapePayload;
import tremor.network.TremorStatePayload;
import tremor.world.LevelVoxelView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Server logic of the entity in one level (SPEC 3, 5): a terrain cache and the surface graph over it, the incremental
 * path search, the motion along the route, block change invalidation and the state sync to players; the stage
 * behaviour (SPEC 8, 11) is a {@link TremorMind}, whose orders it carries out. Owned by {@link TremorManager}; server
 * thread only.
 * <p>
 * Each tick: terrain changes reported since the last tick are applied to the cache and graph; the mind decays the
 * anger and its brain decides ({@link TremorMind#think}); a running search expands up to {@code pathNodesPerTick}
 * nodes; the crawler moves at the stage's speed and bump height ({@link TremorMind#motion}); the mind checks contact
 * and despawning ({@link TremorMind#afterMove}), and an entity that has left is removed; every
 * {@code syncInterval} ticks (and at the end of the tick of a stage change) nearby players get the state. While the
 * chunk under the entity is not loaded nothing runs but the despawn clock of a naturally spawned entity
 * ({@link TremorMind#pausedTick}); such an entity is removed at once when it is due.
 * <p>
 * A state is a snapshot of one game time: the position after that time's move. A stage change before the move (the
 * mind's tick, a vibration heard while the level ticks its entities) or during the tick is sent with the state at the
 * end of the tick; one between ticks (a command, a player's movement) at once, as the position is the current one
 * then (see {@link #stageChanged}).
 * <p>
 * Terrain changes: a block change matters if it changes what the cache says about the voxel (solid or known) in a
 * section the cache has read; a loaded chunk matters for the sections of its column the cache has read (they may
 * have been read as unknown, and no block event reports the terrain that appears). Either way the whole section is
 * read again, and the graph forgets what depended on every voxel that differs, not only on the reported one.
 * <p>
 * Routes: a partial route (search limit) is followed to its end and planned on from there; a route is replanned when
 * the terrain within {@value #REPLAN_DISTANCE} blocks of its remaining part changes (of its nodes, and of the rock its
 * dives pass through). A new route starts at the far end of the dive the crawler is in, if any (the crawler finishes
 * a dive before it turns), else at the node nearest to it. The whole cache and graph are dropped every
 * {@code cacheMaxAge} ticks as a safety net for changes no event reported, and when the entity's chunk loads again;
 * an active route is then planned again (see {@link #resetTerrain}).
 * <p>
 * Hearing (SPEC 7.3, {@link #hear}): a heard vibration updates {@code lastHeard} and the anger, and the brain hears it;
 * whether it goes there is the brain's decision on the next tick. Targets: a {@code /tremor goto}
 * ({@link TremorEntity.TargetKind#MANUAL}, which suspends the brain's decisions until it ends), a heard sound the
 * brain goes after ({@link #brainGo}, {@link TremorEntity.TargetKind#SOUND}: at most once per retarget cooldown, and
 * not again for a while near a goal whose search just failed from the same start, see {@link SoundPursuit}), or a
 * wander or search leg ({@link TremorEntity.TargetKind#ROAM}). A target without a path is given up (a DEBUG log for
 * the brain's targets, INFO for a goto), the retarget cooldown running on from the give-up, and the body is idle.
 * <p>
 * Stopping (a give-up, a goal the body cannot go to, a freeze of the brain, {@code /tremor stop}) brakes along the
 * current path within the acceleration limit ({@link Crawler#brake}); in a dive the entity first goes on to the dive's
 * far end.
 * <p>
 * An exception stops the runtime (see {@link #error}): a command's entity freezes until it is respawned or despawned,
 * a naturally spawned one is removed at once, as it would otherwise keep other entities from spawning naturally.
 */
public final class TremorRuntime {
    /** How far below an open requested position the ground is looked for (SPEC 14.1 spawn / goto). */
    public static final int SNAP_DEPTH = 24;
    /** Chebyshev radius of the nearest-node fallback when snapping a requested position. */
    public static final int SNAP_RADIUS = 3;
    /** Chebyshev radius around the crawler searched for its current node (search start, normals). */
    public static final int NODE_RADIUS = 2;
    /** Number of recent ticks the cost statistics cover. */
    public static final int COST_WINDOW = 200;

    /**
     * A terrain change this close (Chebyshev) to a node of the remaining route, or to the box spanned by the ends of
     * one of its dives, triggers a replan.
     */
    private static final int REPLAN_DISTANCE = 2;
    /** Length of a server tick in seconds. */
    static final double TICK_SECONDS = 0.05;
    /** Ticks between marking the saved data dirty while the entity moves, so its position survives a restart. */
    private static final int SAVE_INTERVAL = 100;
    /** Above this many changed sections in one tick the graph is cleared instead of invalidated around each. */
    private static final int MASS_CHANGE = 16;
    /** Changed boxes during a search that are remembered one by one; beyond that its route is replanned anyway. */
    private static final int MAX_SEARCH_CHANGES = 256;
    /** How many nodes at the start of a new route may be skipped as already passed (see {@link #skipPassed}). */
    private static final int SKIP_LOOKAHEAD = 6;

    private final ServerLevel level;
    private final TremorSavedData data;
    private final SectionTrackingView tracked;
    private final VoxelCache cache;
    private SurfaceGraph graph;
    /** Uncached view of the world for the current pass (see {@link SectionTrackingView} on why it is replaced). */
    private LevelVoxelView live;
    private final Crawler.NormalSource normals = this::normalAt;

    private PathSearch search;
    /** Player told about the outcome of the running search (a goto), or null. */
    private UUID searchRequester;
    /** Terrain that changed while the search ran: its route is replanned if it passes near one of these. */
    private final List<Box> searchChanges = new ArrayList<>();
    private boolean searchChangesOverflow;
    /** The crawler follows a route whose arrival has not been handled yet. */
    private boolean pathActive;
    /** Ends of the dive edge the crawler is in ({@link #trackDive}), NO_NODE if it is not diving. */
    private long diveFrom = SurfaceGraph.NO_NODE, diveTo = SurfaceGraph.NO_NODE;
    /** That route is partial: planning goes on from its end. */
    private boolean pathPartial;
    private boolean replanRequested;
    /** Block positions ({@link BlockPos#asLong}) reported changed since the last tick. */
    private final LongOpenHashSet pendingChanges = new LongOpenHashSet();
    /**
     * Loaded chunks ({@link ChunkPos#asLong}) with sections the cache has read, until those are read again; kept
     * while the chunk is not readable yet ({@link #chunkLoaded}).
     */
    private final LongOpenHashSet pendingChunks = new LongOpenHashSet();
    /** Collects what a section {@linkplain VoxelCache#reload reload} changed. */
    private final Bounds reloaded = new Bounds();

    /** Retarget cooldown and failed sound searches. */
    private final SoundPursuit pursuit = new SoundPursuit();
    /** Stage behaviour of the entity; made anew with each entity ({@link #mind(TremorEntity)}). */
    private TremorMind mind;

    private long lastTerrainReset;
    private boolean paused;
    /** Inside {@link #tick()}. */
    private boolean ticking;
    /** Game time of the last move of the crawler ({@link #tick(TremorEntity)}), or {@link Long#MIN_VALUE}. */
    private long movedAt = Long.MIN_VALUE;
    /** A stage change waits for the state sent at the end of the tick ({@link #stageChanged}). */
    private boolean syncDue;
    private int ticksSinceSync;
    private int ticksSinceSave;
    private BumpParams sentShape;
    private String error;

    private final long[] costs = new long[COST_WINDOW];
    private int costCount;
    private int costIndex;

    TremorRuntime(ServerLevel level, TremorSavedData data) {
        this.level = level;
        this.data = data;
        this.live = new LevelVoxelView(level);
        this.tracked = new SectionTrackingView(level);
        this.cache = new VoxelCache(tracked);
        this.lastTerrainReset = level.getGameTime();
        cache.setTime(lastTerrainReset);
        TremorEntity entity = data.entity();
        this.graph = new SurfaceGraph(cache, entity != null
                ? entity.params().maxDiveDepth() : TremorConfig.COMMON.maxDiveDepth.get());
        // The route is not saved: an entity loaded on its way somewhere plans a new one.
        this.replanRequested = entity != null && entity.target() != null;
    }

    public ServerLevel level() {
        return level;
    }

    /** The level's entity, or null. */
    public TremorEntity entity() {
        return data.entity();
    }

    public SurfaceGraph graph() {
        return graph;
    }

    public VoxelCache cache() {
        return cache;
    }

    /** The running path search, or null. */
    public PathSearch search() {
        return search;
    }

    /** True while the chunk under the entity is not loaded (nothing is simulated then). */
    public boolean isPaused() {
        return paused;
    }

    /** Description of the exception that stopped this runtime, or null if it runs normally. */
    public String error() {
        return error;
    }

    /** Average wall time of the recent ticks ({@value #COST_WINDOW}), in milliseconds. */
    public double averageTickMillis() {
        long sum = 0;
        for (int i = 0; i < costCount; i++) {
            sum += costs[i];
        }
        return costCount == 0 ? 0 : sum / (costCount * 1e6);
    }

    /** Longest of the recent ticks, in milliseconds. */
    public double maxTickMillis() {
        long max = 0;
        for (int i = 0; i < costCount; i++) {
            max = Math.max(max, costs[i]);
        }
        return max / 1e6;
    }

    /** Number of ticks the statistics currently cover. */
    public int measuredTicks() {
        return costCount;
    }

    /**
     * The graph node for a requested position (SPEC 14.1 spawn / goto): the voxel itself if it is a node; for an open
     * voxel the first solid voxel at most {@value #SNAP_DEPTH} blocks below it, if that is a node; otherwise the node
     * nearest to the voxel centre within {@value #SNAP_RADIUS} blocks. {@link SurfaceGraph#NO_NODE} if there is none.
     */
    public long snap(BlockPos pos) {
        beginPass();
        processChanges();
        return snapNode(pos.getX(), pos.getY(), pos.getZ());
    }

    /** The node the entity is at (nearest within {@value #NODE_RADIUS} blocks), or {@link SurfaceGraph#NO_NODE}. */
    public long currentNode() {
        TremorEntity entity = data.entity();
        if (entity == null) {
            return SurfaceGraph.NO_NODE;
        }
        beginPass();
        return graph.nearestNode(entity.crawler().position(), NODE_RADIUS);
    }

    /** {@link #spawn(long, boolean)} of an entity that does not despawn by itself (a command's). */
    public TremorEntity spawn(long node) {
        return spawn(node, false);
    }

    /**
     * Replaces the level's entity (if any) by a new one standing at the centre of {@code node} with the smoothed
     * normal there, and sends its shape and state to every player in the dimension.
     *
     * @param natural spawned by the world, not by a command: it despawns by itself (SPEC 11)
     */
    public TremorEntity spawn(long node, boolean natural) {
        beginPass();
        int x = VoxelPos.x(node), y = VoxelPos.y(node), z = VoxelPos.z(node);
        TremorEntity entity = data.spawn(VoxelPos.center(node), graph.normal(x, y, z), natural, level.getGameTime());
        resetRoute();
        pursuit.reset();
        error = null;
        costCount = 0;
        costIndex = 0;
        syncDue = false;
        ticksSinceSync = 0;
        ticksSinceSave = 0;
        sendShape(entity);
        PacketDistributor.sendToPlayersInDimension(level, state(entity));
        return entity;
    }

    /** Removes the entity; every player in the dimension is told it is gone. Returns false if there was none. */
    public boolean despawn() {
        TremorEntity entity = data.remove(level.getGameTime());
        if (entity == null) {
            return false;
        }
        resetRoute();
        mind = null;
        syncDue = false;
        PacketDistributor.sendToPlayersInDimension(level, TremorStatePayload.absent(entity.instance(),
                level.getGameTime()));
        return true;
    }

    /**
     * Sends the entity to {@code goal} (a node, e.g. from {@link #snap}): it becomes the target and a new path search
     * starts from the node the entity is at, or mid-dive from the far end of the dive ({@link #startNode}). The
     * current route is followed until the search is done. Heard sounds do not replace this target until it is
     * reached, given up or {@linkplain #stop() cancelled}.
     *
     * @param requester player told about the outcome of the search, or null
     * @return false if there is no entity or it is not near any node (the terrain under it changed)
     */
    public boolean moveTo(long goal, UUID requester) {
        TremorEntity entity = data.entity();
        if (entity == null) {
            return false;
        }
        beginPass();
        processChanges();
        long start = startNode(entity.crawler());
        if (start == SurfaceGraph.NO_NODE) {
            return false;
        }
        head(entity, start, goal, TremorEntity.TargetKind.MANUAL, requester);
        return true;
    }

    /**
     * Forgets the target, whoever chose it, and what the brain ordered, and stops: braking along the current path, or
     * mid-dive at the far end of the dive ({@link #halt}). Returns false if there is no entity.
     */
    public boolean stop() {
        TremorEntity entity = data.entity();
        if (entity == null) {
            return false;
        }
        beginPass();
        dropTarget(entity);
        return true;
    }

    /**
     * Debug ({@code /tremor anger}, SPEC 14.1): sets the anger and the stage it implies; a stage change is marked by
     * its sound and sent to the players as usual. @throws IllegalStateException if there is no entity
     */
    public void setAnger(double anger) {
        TremorEntity entity = requireEntity();
        mind(entity).setAnger(anger);
        data.setDirty();
    }

    /**
     * Debug ({@code /tremor stage}, SPEC 14.1): jumps to {@code stage}, the anger set to its threshold; the change is
     * marked as usual. @throws IllegalStateException if there is no entity
     */
    public void forceStage(Stage stage) {
        TremorEntity entity = requireEntity();
        mind(entity).forceStage(stage);
        data.setDirty();
    }

    /**
     * Debug ({@code /tremor ai on|off}): switches the stage behaviour's decisions (see
     * {@link TremorEntity#aiEnabled}). Switched off, the entity stops, unless it is on a {@code /tremor goto}.
     * @throws IllegalStateException if there is no entity
     */
    public void setAi(boolean on) {
        TremorEntity entity = requireEntity();
        if (!on && entity.aiEnabled() && entity.targetKind() != TremorEntity.TargetKind.MANUAL) {
            beginPass();
            dropTarget(entity);
        }
        mind(entity).setAi(on);
        data.setDirty();
    }

    /**
     * What the stage behaviour is doing, e.g. {@code hunting: searching, 12 s left}, {@code ai off} or
     * {@code leaving}. @throws IllegalStateException if there is no entity
     */
    public String behavior() {
        return mind(requireEntity()).describe();
    }

    /**
     * Hands the entity to an Awakening (SPEC 9, {@link TremorMind#absorb}): it drops its target and stops (braking)
     * and from now on is the whole area rather than a bump, until it is removed. Nothing happens if it is taken
     * already. @throws IllegalStateException if there is no entity
     */
    public void absorb() {
        TremorEntity entity = requireEntity();
        TremorMind mind = mind(entity);
        if (mind.absorbed()) {
            return;
        }
        beginPass();
        dropTarget(entity);
        mind.absorb();
        data.setDirty();
    }

    /** Whether an Awakening took the entity ({@link #absorb}); false if there is none. */
    public boolean absorbed() {
        TremorEntity entity = data.entity();
        return entity != null && mind(entity).absorbed();
    }

    /**
     * Reaction to a vibration in the level (SPEC 7.2, 7.3, 8). Its perceived loudness at the entity is
     * {@link Hearing#perceived} over the live level. From the threshold on it is heard: {@code lastHeard} is updated,
     * the anger grows by {@code perceived * angerPerLoudness} plus the vibration's bonus, and the brain hears it
     * ({@link TremorMind#heard}); what the brain does about it is decided on the next tick.
     *
     * @return what the entity perceived, or null if the vibration was not evaluated: no entity, stopped by an error,
     * paused (its chunk is not loaded), or the source is beyond the hearing distance
     */
    public Perception hear(Vibration vibration) {
        TremorEntity entity = data.entity();
        if (entity == null || error != null || paused) {
            return null;
        }
        try {
            return hear(entity, vibration);
        } catch (RuntimeException e) {
            fail(entity, e);
            return null;
        }
    }

    private Perception hear(TremorEntity entity, Vibration vibration) {
        HearingParams params = TremorConfig.COMMON.hearingParams();
        Vec3 listener = entity.crawler().position();
        double distance = vibration.source().distance(listener);
        if (distance > params.maxDistance()) {
            return null;
        }
        LevelVoxelView view = new LevelVoxelView(level);
        if (!view.isKnown(floor(listener.x()), floor(listener.y()), floor(listener.z()))) {
            return null; // its chunk unloaded since the last tick
        }
        double perceived = Hearing.perceived(view, vibration.source(), listener, vibration.loudness(),
                vibration.footing(), params);
        Stage stage = entity.stage();
        if (!(perceived >= params.threshold())) {
            return new Perception(listener, distance, perceived, false, 0, entity.anger(), stage, stage, null);
        }
        long now = level.getGameTime();
        entity.setLastHeard(new TremorEntity.Heard(vibration.source(), now, perceived, vibration.event()));
        data.setDirty();
        float before = entity.anger();
        String reaction = mind(entity).heard(vibration.source(), perceived,
                perceived * TremorConfig.COMMON.angerPerLoudness.getAsDouble() + vibration.angerBonus());
        return new Perception(listener, distance, perceived, true, entity.anger() - before, entity.anger(), stage,
                entity.stage(), reaction);
    }

    // ---- the brain's orders (TremorMind) ----

    /**
     * Whether the body has nothing left to do for the brain: no target, no search or replan under way, and the
     * crawler stands (no path to follow or brake on, no route waiting for it to stop).
     */
    boolean bodyIdle(TremorEntity entity) {
        Crawler crawler = entity.crawler();
        return entity.target() == null && search == null && !replanRequested && crawler.speed() == 0
                && (crawler.spline() == null || crawler.arrived()) && crawler.pendingPath() == null
                && !crawler.braking();
    }

    /**
     * Whether a sound heard {@code perceived} loud may become the target now: not while the entity heads for another
     * sound (or gave one up) less than {@code retargetCooldownTicks} ago, unless it is 1.5 times louder
     * ({@link SoundPursuit#mayRetarget}).
     */
    boolean mayRetarget(TremorEntity entity, long now, double perceived) {
        return pursuit.mayRetarget(entity.targetKind() == TremorEntity.TargetKind.SOUND, now,
                TremorConfig.COMMON.retargetCooldownTicks.get(), perceived);
    }

    /**
     * Carries out a GO of the brain (SPEC 8, 5.6): the node for {@code point} (the {@link #snap} rules) becomes the
     * target of {@code kind}, unless it already is the target (that route goes on). Where the body cannot go (no node
     * there, the entity is not on the surface itself, or, for a sound, a search from here just failed near that goal,
     * {@link SoundPursuit#knownUnreachable}), or is there already, it stops: idle, so the brain moves on.
     *
     * @param kind      {@link TremorEntity.TargetKind#SOUND} (the caller checked {@link #mayRetarget}) or
     *                  {@link TremorEntity.TargetKind#ROAM}
     * @param perceived how loud a sound target was heard (the loudness a later sound must beat)
     */
    void brainGo(TremorEntity entity, Vec3 point, TremorEntity.TargetKind kind, double perceived) {
        long now = level.getGameTime();
        long goal = snapNode(floor(point.x()), floor(point.y()), floor(point.z()));
        if (goal == SurfaceGraph.NO_NODE) {
            forget(entity);
            return;
        }
        boolean sound = kind == TremorEntity.TargetKind.SOUND;
        if (sound) {
            pursuit.retargeted(now, perceived);
        }
        BlockPos target = entity.target();
        if (target != null && VoxelPos.pack(target.getX(), target.getY(), target.getZ()) == goal) {
            return; // already on its way there
        }
        long start = startNode(entity.crawler());
        if (start == SurfaceGraph.NO_NODE || start == goal || sound && pursuit.knownUnreachable(start, goal, now)) {
            forget(entity);
            return;
        }
        head(entity, start, goal, kind, null);
    }

    /**
     * Carries out a FREEZE of the brain (SPEC 8 ALERT): forgets the target and stops ({@link #halt}: braking, or at
     * the far end of a dive it is in), then turns toward {@code facing} once standing ({@link Crawler#face}).
     */
    void freeze(TremorEntity entity, Vec3 facing) {
        forget(entity);
        Crawler crawler = entity.crawler();
        crawler.face(facing.sub(crawler.position()));
    }

    /** Forgets the target and what the brain ordered, and stops ({@link #forget}). */
    void dropTarget(TremorEntity entity) {
        mind(entity).clearOrders();
        forget(entity);
    }

    /**
     * The stage changed (SPEC 8): saved soon, and the players get the state with the stage as soon as the position
     * in it belongs to the game time it is sent with. That is at the end of the tick if the change came during it, or
     * before it in the same server tick (the crawler has not moved for this game time yet); at once otherwise (between
     * ticks, or when no tick of the runtime is to come: paused, stopped by an error, the game frozen).
     */
    void stageChanged(TremorEntity entity) {
        data.setDirty();
        if (ticking || error == null && !paused && level.tickRateManager().runsNormally()
                && movedAt != level.getGameTime()) {
            syncDue = true;
            return;
        }
        ticksSinceSync = 0;
        sync(entity);
    }

    /** The uncached view of the world for the current tick (or command), for checks against the live terrain. */
    VoxelView liveView() {
        return live;
    }

    /** Forgets the target and any search for it, and stops ({@link #halt}). */
    private void forget(TremorEntity entity) {
        search = null;
        searchRequester = null;
        replanRequested = false;
        pursuit.targetSet();
        halt(entity);
    }

    /** The stage behaviour of the entity, made anew for a new entity. */
    private TremorMind mind(TremorEntity entity) {
        if (mind == null || mind.entity() != entity) {
            mind = new TremorMind(this, entity);
        }
        return mind;
    }

    /** Makes {@code goal} the target and starts the search for it. */
    private void head(TremorEntity entity, long start, long goal, TremorEntity.TargetKind kind, UUID requester) {
        pursuit.targetSet();
        entity.setTarget(toBlockPos(goal), kind);
        data.setDirty();
        replanRequested = false;
        searchRequester = requester;
        startSearch(entity, start, goal);
    }

    /**
     * Overrides a parameter of the entity; a shape change is sent to the clients, a dive depth or cost change replans.
     *
     * @throws IllegalArgumentException (with a message for players) if the value is not allowed
     * @throws IllegalStateException    if there is no entity
     */
    public void setParam(Param param, double value) {
        TremorEntity entity = requireEntity();
        double before = entity.params().diveCost();
        entity.params().set(param, value);
        paramsChanged(entity, before);
    }

    /** Restores the config values of all parameters. @throws IllegalStateException if there is no entity */
    public void resetParams() {
        TremorEntity entity = requireEntity();
        double before = entity.params().diveCost();
        entity.params().reset();
        paramsChanged(entity, before);
    }

    /** Sends the shape and the state of the entity (or that there is none) to a player entering the dimension. */
    public void sendTo(ServerPlayer player) {
        TremorEntity entity = data.entity();
        if (entity == null) {
            PacketDistributor.sendToPlayer(player, TremorStatePayload.absent(data.lastInstance(), level.getGameTime()));
            return;
        }
        PacketDistributor.sendToPlayer(player, new TremorShapePayload(entity.instance(), entity.params().bumpParams()));
        PacketDistributor.sendToPlayer(player, state(entity));
    }

    /** Called for every block that may have changed in the level (block update, explosion, piston). */
    void blockChanged(BlockPos pos) {
        if (data.entity() != null && tracked.wasRead(pos.getX(), pos.getY(), pos.getZ())) {
            pendingChanges.add(pos.asLong());
        }
    }

    /**
     * Called when a chunk of the level has loaded. That happens in the middle of loading it, when the level must not
     * be read yet, so the chunk is only noted; {@link #processChanges} reads its sections again once it is readable.
     */
    void chunkLoaded(int chunkX, int chunkZ) {
        if (data.entity() != null && tracked.wasReadInColumn(chunkX, chunkZ)) {
            pendingChunks.add(ChunkPos.asLong(chunkX, chunkZ));
        }
    }

    /** Before an orderly shutdown: make sure the latest position is written. */
    void markDirty() {
        if (data.entity() != null) {
            data.setDirty();
        }
    }

    void tick() {
        TremorEntity entity = data.entity();
        if (entity == null || error != null) {
            return;
        }
        long start = System.nanoTime();
        ticking = true;
        try {
            tick(entity);
        } catch (RuntimeException e) {
            fail(entity, e);
        } finally {
            ticking = false;
        }
        costs[costIndex] = System.nanoTime() - start;
        costIndex = (costIndex + 1) % COST_WINDOW;
        costCount = Math.min(costCount + 1, COST_WINDOW);
    }

    /**
     * Keeps the server alive: a command's entity freezes until it is respawned or despawned; a naturally spawned one
     * is removed (while it exists, no other entity spawns naturally in the dimension).
     */
    private void fail(TremorEntity entity, RuntimeException e) {
        error = e.toString();
        resetRoute();
        entity.crawler().stop();
        Tremor.LOGGER.error("Tremor #{} in {} stopped after an error{}", entity.instance(),
                level.dimension().location(), entity.natural() ? "; it is removed" : "", e);
        if (entity.natural()) {
            TremorManager.despawn(level);
        }
    }

    private void tick(TremorEntity entity) {
        long now = level.getGameTime();
        beginPass();
        cache.setTime(now);
        int depth = entity.params().maxDiveDepth();
        if (depth != graph.maxDiveDepth()) {
            rebuildGraph(depth); // config reload
        }
        // Before a reset, which forgets the changes: they are still checked against the route.
        processChanges();
        if (search == null && now - lastTerrainReset >= TremorConfig.COMMON.cacheMaxAge.get()) {
            resetTerrain(now);
        }

        Crawler crawler = entity.crawler();
        Vec3 p = crawler.position();
        if (!live.isKnown(floor(p.x()), floor(p.y()), floor(p.z()))) {
            paused = true;
            if (mind(entity).pausedTick()) {
                TremorManager.despawn(level);
            } else if (syncDue) {
                // A stage change while the level ticked: the crawler stands still while paused, so this position is
                // the one of this game time.
                syncDue = false;
                ticksSinceSync = 0;
                sync(entity);
            }
            return;
        }
        if (paused) {
            // Sections read while chunks were missing say "unknown" for terrain that may be loaded by now.
            paused = false;
            resetTerrain(now);
        }

        TremorMind mind = mind(entity);
        mind.think(now);
        if (replanRequested && search == null) {
            replanRequested = false;
            startSearch(entity, SurfaceGraph.NO_NODE, SurfaceGraph.NO_NODE);
        }
        if (search != null) {
            stepSearch(entity);
        }
        crawler.tick(TICK_SECONDS, mind.motion(), normals);
        movedAt = now;
        trackDive(crawler);
        if (pathActive && crawler.arrived()) {
            arrived(entity);
        }
        if (mind.afterMove(now)) {
            Tremor.LOGGER.info("Tremor #{} in {} is gone", entity.instance(), level.dimension().location());
            TremorManager.despawn(level);
            return;
        }

        if (crawler.speed() > 0 && ++ticksSinceSave >= SAVE_INTERVAL) {
            ticksSinceSave = 0;
            data.setDirty();
        }
        if (syncDue || ++ticksSinceSync >= TremorConfig.COMMON.syncInterval.get()) {
            syncDue = false;
            ticksSinceSync = 0;
            sync(entity);
        }
    }

    // ---- route ----

    /**
     * Starts a search toward the entity's target.
     *
     * @param start node to start from, or NO_NODE for the {@link #startNode} of the entity
     * @param goal  goal node, or NO_NODE for the target (snapped again if it stopped being a node)
     */
    private void startSearch(TremorEntity entity, long start, long goal) {
        BlockPos target = entity.target();
        if (target == null) {
            return;
        }
        if (goal == SurfaceGraph.NO_NODE) {
            goal = VoxelPos.pack(target.getX(), target.getY(), target.getZ());
            if (!graph.isNode(goal)) {
                long snapped = snapNode(target.getX(), target.getY(), target.getZ());
                if (snapped != SurfaceGraph.NO_NODE) {
                    goal = snapped;
                    entity.setTarget(toBlockPos(snapped));
                    data.setDirty();
                }
            }
        }
        if (start == SurfaceGraph.NO_NODE) {
            start = startNode(entity.crawler());
        }
        searchChanges.clear();
        searchChangesOverflow = false;
        if (start == SurfaceGraph.NO_NODE) {
            search = null;
            giveUp(entity, "it is not on the surface");
            return;
        }
        search = new PathSearch(graph, start, goal, TremorConfig.COMMON.pathMaxNodes.get(),
                entity.params().diveCost());
    }

    private void stepSearch(TremorEntity entity) {
        PathSearch.Status status = search.step(TremorConfig.COMMON.pathNodesPerTick.get());
        if (status == PathSearch.Status.RUNNING) {
            return;
        }
        PathSearch done = search;
        search = null;
        UUID requester = searchRequester;
        searchRequester = null;
        if (status == PathSearch.Status.FAILED) {
            tell(requester, String.format(Locale.ROOT, "Tremor #%d: no path to %s (%d nodes expanded)",
                    entity.instance(), node(done.goal()), done.expanded()));
            if (entity.targetKind() == TremorEntity.TargetKind.SOUND) {
                pursuit.searchFailed(done.start(), done.goal(), level.getGameTime());
            }
            giveUp(entity, "no path (" + done.expanded() + " nodes expanded)");
            return;
        }
        Crawler crawler = entity.crawler();
        Path path = done.path();
        long exit = diveExit(crawler);
        if (exit != SurfaceGraph.NO_NODE && path.node(0) != exit) {
            // The crawler went into a dive while the search ran, and a route must start where that dive ends: plan
            // again from there. Meanwhile it keeps to its current route.
            searchRequester = requester;
            replanRequested = true;
            searchChanges.clear();
            searchChangesOverflow = false;
            return;
        }
        if (exit == SurfaceGraph.NO_NODE) {
            path = skipPassed(path, crawler.position());
        }
        crawler.follow(path);
        pathActive = true;
        pathPartial = status == PathSearch.Status.PARTIAL;
        if (requester != null) {
            tell(requester, describe(entity, done, path));
        }
        if (searchChangesOverflow || touches(path, 0, searchChanges) || nearTrackedDive(searchChanges)) {
            replanRequested = true; // the terrain changed while the search ran (on the route, or in the dive it is in)
        }
        searchChanges.clear();
        searchChangesOverflow = false;
    }

    private void arrived(TremorEntity entity) {
        pathActive = false;
        if (search != null) {
            return; // a replan is running and brings the next route
        }
        if (pathPartial) {
            pathPartial = false;
            long reached = entity.crawler().spline().path().last();
            startSearch(entity, graph.isNode(reached) ? reached : SurfaceGraph.NO_NODE, SurfaceGraph.NO_NODE);
        } else {
            entity.setTarget(null);
            data.setDirty();
        }
    }

    /**
     * Forgets the target, which cannot be reached, and stops ({@link #halt}); the brain then finds the body idle and
     * moves on. The brain's targets are given up often in normal play (a player walking where the entity cannot get,
     * a wander point across a chasm): that logs at DEBUG, and for a sound the retarget cooldown runs on from here
     * ({@link SoundPursuit#gaveUp}). A goto logs at INFO.
     */
    private void giveUp(TremorEntity entity, String reason) {
        String target = entity.target() == null ? "-" : entity.target().toShortString();
        TremorEntity.TargetKind kind = entity.targetKind();
        if (kind == TremorEntity.TargetKind.SOUND || kind == TremorEntity.TargetKind.ROAM) {
            Tremor.LOGGER.debug("Tremor #{} in {} gives up on the {} target {}: {}", entity.instance(),
                    level.dimension().location(), kind.name().toLowerCase(Locale.ROOT), target, reason);
            if (kind == TremorEntity.TargetKind.SOUND) {
                pursuit.gaveUp(level.getGameTime());
            }
        } else {
            Tremor.LOGGER.info("Tremor #{} in {} gives up on {}: {}", entity.instance(), level.dimension().location(),
                    target, reason);
        }
        halt(entity);
    }

    /**
     * Forgets the target and stops the entity: it brakes along its current path within the acceleration limit (SPEC
     * 5.5) and stands where that ends ({@link Crawler#brake}, which never stops inside rock). In a dive it goes on to
     * the far end of the dive instead and stops there.
     */
    private void halt(TremorEntity entity) {
        Crawler crawler = entity.crawler();
        long exit = diveExit(crawler);
        if (exit != SurfaceGraph.NO_NODE) {
            crawler.follow(new Path(new long[]{exit}, new boolean[0], true)); // never stop inside the rock
        } else {
            crawler.brake();
        }
        entity.setTarget(null);
        data.setDirty();
        pathActive = false;
        pathPartial = false;
    }

    private void resetRoute() {
        search = null;
        searchRequester = null;
        searchChanges.clear();
        searchChangesOverflow = false;
        pathActive = false;
        pathPartial = false;
        replanRequested = false;
    }

    private void paramsChanged(TremorEntity entity, double diveCostBefore) {
        data.setDirty();
        BumpParams shape = entity.params().bumpParams();
        if (!shape.equals(sentShape)) {
            sendShape(entity);
        }
        int depth = entity.params().maxDiveDepth();
        if (depth != graph.maxDiveDepth()) {
            rebuildGraph(depth);
        } else if (entity.params().diveCost() != diveCostBefore) {
            requestReplan();
        }
    }

    /** New dive depth: a new graph over the same cache, and a new route through it. */
    private void rebuildGraph(int depth) {
        graph = new SurfaceGraph(cache, depth);
        pursuit.forgetFailure(); // other dives, other places within reach
        requestReplan();
    }

    /** Plans the route to the target again; a running search is restarted (its graph or costs are outdated). */
    private void requestReplan() {
        if (search != null || pathActive) {
            search = null;
            replanRequested = true;
        }
    }

    // ---- terrain ----

    private void beginPass() {
        live = new LevelVoxelView(level);
        tracked.refresh();
    }

    /**
     * The node a new route of the entity starts from. In a dive: its far end, while the dive is still an edge (the
     * crawler finishes a dive before it turns onto a new route). Otherwise the node nearest to the crawler within
     * {@value #NODE_RADIUS} blocks, or, if it is deeper in rock than that (loaded, unpaused or given up in the middle
     * of a dive, or its dive was cut), within half the longest dive. {@link SurfaceGraph#NO_NODE} if there is none.
     */
    private long startNode(Crawler crawler) {
        long exit = diveExit(crawler);
        if (exit != SurfaceGraph.NO_NODE) {
            return exit;
        }
        long node = graph.nearestNode(crawler.position(), NODE_RADIUS);
        return node != SurfaceGraph.NO_NODE ? node
                : graph.nearestNode(crawler.position(), (graph.maxDiveDepth() + 1) / 2 + 1);
    }

    /** The far end of the dive the crawler is in, if that dive is still an edge of the graph; else NO_NODE. */
    private long diveExit(Crawler crawler) {
        trackDive(crawler);
        long exit = diveTo;
        if (exit == SurfaceGraph.NO_NODE) {
            return SurfaceGraph.NO_NODE;
        }
        boolean[] found = {false};
        graph.forEachEdge(diveFrom, (to, length, dive) -> found[0] |= dive && to == exit);
        return found[0] ? exit : SurfaceGraph.NO_NODE;
    }

    /**
     * Notes the dive edge the crawler is on ({@link #diveFrom} to {@link #diveTo}; NO_NODE when not diving). A route
     * taken over mid-dive starts at the dive's far end, reached by a lead-in from inside the rock that has no node of
     * its own: the edge seen before still describes that crossing. Called after every move of the crawler.
     */
    private void trackDive(Crawler crawler) {
        if (!crawler.diving()) {
            diveFrom = diveTo = SurfaceGraph.NO_NODE;
            return;
        }
        PathSpline spline = crawler.spline();
        Path path = spline.path();
        int edge = spline.segment(crawler.progress());
        if (edge >= 0) {
            diveFrom = path.node(edge);
            diveTo = path.node(edge + 1);
        } else if (path.node(0) != diveTo) {
            diveFrom = diveTo = SurfaceGraph.NO_NODE; // a lead-in into a dive not seen before (stopped right in it)
        }
    }

    /** Whether a changed box lies within {@value #REPLAN_DISTANCE} of the tracked dive (see {@link #trackDive}). */
    private boolean nearTrackedDive(List<Box> changes) {
        if (diveTo == SurfaceGraph.NO_NODE) {
            return false;
        }
        for (Box change : changes) {
            if (change.near(diveFrom, diveTo, REPLAN_DISTANCE)) {
                return true;
            }
        }
        return false;
    }

    private long snapNode(int x, int y, int z) {
        if (graph.isNode(x, y, z)) {
            return VoxelPos.pack(x, y, z);
        }
        if (cache.isOpen(x, y, z)) {
            for (int below = y - 1; below >= y - SNAP_DEPTH; below--) {
                if (cache.isSolid(x, below, z)) {
                    if (graph.isNode(x, below, z)) {
                        return VoxelPos.pack(x, below, z);
                    }
                    break;
                }
            }
        }
        return graph.nearestNode(Vec3.voxelCenter(x, y, z), SNAP_RADIUS);
    }

    private Vec3 normalAt(Vec3 position) {
        long node = graph.nearestNode(position, NODE_RADIUS);
        return node == SurfaceGraph.NO_NODE ? Vec3.ZERO
                : graph.normal(VoxelPos.x(node), VoxelPos.y(node), VoxelPos.z(node));
    }

    /**
     * Drops the cache, the graph and the section tracking (safety net for changes no event reported). Afterwards no
     * section along the remaining route is tracked, so changes there would go unnoticed until something reads them
     * again, and the route itself may already cross terrain that changed without an event: an active route (or a
     * running search) is therefore planned again. The new search reads, and so tracks again, the terrain around the
     * new route, which it plans on the fresh terrain. Run {@link #processChanges} first: the reset forgets what is
     * still pending.
     */
    private void resetTerrain(long now) {
        cache.clear();
        graph.clear();
        tracked.forget();
        pendingChanges.clear();
        pendingChunks.clear();
        lastTerrainReset = now;
        pursuit.forgetFailure(); // the safety net for changes no event reported
        requestReplan();
    }

    /**
     * Applies the reported terrain changes. A block change matters only if the cache's answer for the voxel (solid,
     * known) differs from the world in a section the cache holds; a loaded chunk matters for the sections of its
     * column the cache holds, once it can be read. Each such section is read again as a whole, which takes in every
     * change in it, reported or not (typically a chunk that loaded after its section was read as unknown), so the
     * graph forgets what depended on any voxel that differs (their bounding box). The cache then holds what the world
     * looked like after the last processed change, and the next comparison is valid.
     */
    private void processChanges() {
        if (pendingChanges.isEmpty() && pendingChunks.isEmpty()) {
            return;
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
                continue; // not readable yet; dropped by the next terrain reset at the latest
            }
            it.remove();
            // Outside the build height nothing depends on chunks.
            for (int sy = level.getMinSection(); sy < level.getMaxSection(); sy++) {
                if (tracked.wasRead(chunkX << 4, sy << 4, chunkZ << 4)) {
                    sections.add(VoxelPos.pack(chunkX, sy, chunkZ));
                }
            }
        }
        if (sections.isEmpty()) {
            return;
        }
        List<Box> changes = new ArrayList<>();
        for (LongIterator it = sections.iterator(); it.hasNext(); ) {
            long section = it.nextLong();
            reloaded.reset();
            if (cache.reload(VoxelPos.x(section) << 4, VoxelPos.y(section) << 4, VoxelPos.z(section) << 4,
                    reloaded) > 0) {
                changes.add(reloaded.box());
            }
        }
        if (changes.isEmpty()) {
            return;
        }
        pursuit.forgetFailure(); // a way to an unreachable sound may have opened
        if (changes.size() > MASS_CHANGE) {
            graph.clear();
        } else {
            for (Box b : changes) {
                graph.invalidateBox(b.x0(), b.y0(), b.z0(), b.x1(), b.y1(), b.z1());
            }
        }

        if (search != null) {
            if (searchChanges.size() + changes.size() > MAX_SEARCH_CHANGES) {
                searchChangesOverflow = true;
            } else {
                searchChanges.addAll(changes);
            }
        } else if (pathActive) {
            // The rest of the route, the dive the crawler is in (also on a lead-in, which has no nodes), and a route
            // that starts once the crawler has braked.
            Crawler crawler = data.entity().crawler();
            PathSpline spline = crawler.spline();
            Path pending = crawler.pendingPath();
            if (spline != null) {
                trackDive(crawler);
                if (touches(spline.path(), spline.segment(crawler.progress()), changes) || nearTrackedDive(changes)
                        || pending != null && touches(pending, 0, changes)) {
                    replanRequested = true;
                }
            }
        }
    }

    /**
     * The route from the one of its first nodes that is nearest to {@code position}. The search started where the
     * crawler was when it began; meanwhile the crawler kept moving along its old route. The crawler keeps its motion
     * continuous ({@link Crawler#follow(Path)}), but only takes over a route that heads on within 45° of its motion:
     * one whose first nodes it has passed in the meantime heads back, so it would brake to a stop and walk back over
     * them first. Not for a route that starts at the end of the dive the crawler is in (that start is ahead of it,
     * and the crawler needs it).
     */
    private static Path skipPassed(Path path, Vec3 position) {
        int first = 0;
        double best = position.distanceSquared(path.point(0));
        for (int i = 1; i < Math.min(path.size(), SKIP_LOOKAHEAD + 1); i++) {
            double d = position.distanceSquared(path.point(i));
            if (d <= best) {
                best = d;
                first = i;
            }
        }
        if (first == 0) {
            return path;
        }
        return new Path(Arrays.copyOfRange(path.nodes(), first, path.size()),
                Arrays.copyOfRange(path.dive(), first, path.size() - 1), path.complete());
    }

    /**
     * Whether a changed box lies within {@value #REPLAN_DISTANCE} of the route from node {@code from} on: of one of
     * its nodes, or of the rock a dive passes through (the box spanned by the dive's ends; a long dive has no node in
     * its middle).
     */
    private static boolean touches(Path path, int from, List<Box> changes) {
        if (changes.isEmpty()) {
            return false;
        }
        boolean[] dive = path.dive();
        for (int i = Math.max(0, from); i < path.size(); i++) {
            long a = path.node(i);
            long b = i < dive.length && dive[i] ? path.node(i + 1) : a;
            for (Box change : changes) {
                if (change.near(a, b, REPLAN_DISTANCE)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Inclusive voxel box of changed terrain. */
    private record Box(int x0, int y0, int z0, int x1, int y1, int z1) {
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

    // ---- sync ----

    /** The phase is sent modulo the jitter period: unbounded, it would lose its precision as a float. */
    private TremorStatePayload state(TremorEntity entity) {
        Crawler c = entity.crawler();
        return new TremorStatePayload(entity.instance(), true, level.getGameTime(), c.position(), c.normal(),
                c.forward(), c.velocity(), (float) c.amplitude(), (float) TremorEntity.wrapPhase(c.phase()),
                entity.stage(), mind(entity).rippleAge(level.getGameTime()));
    }

    private void sendShape(TremorEntity entity) {
        sentShape = entity.params().bumpParams();
        PacketDistributor.sendToPlayersInDimension(level, new TremorShapePayload(entity.instance(), sentShape));
    }

    private void sync(TremorEntity entity) {
        if (!entity.params().bumpParams().equals(sentShape)) {
            sendShape(entity); // config reload
        }
        TremorStatePayload state = state(entity);
        Vec3 p = entity.crawler().position();
        double range = TremorConfig.COMMON.syncRange.get();
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(p.x(), p.y(), p.z()) <= range * range) {
                PacketDistributor.sendToPlayer(player, state);
            }
        }
    }

    private void tell(UUID player, String message) {
        ServerPlayer receiver = player == null ? null : level.getServer().getPlayerList().getPlayer(player);
        if (receiver != null) {
            receiver.sendSystemMessage(Component.literal(message));
        }
    }

    private static String describe(TremorEntity entity, PathSearch search, Path path) {
        int dives = 0;
        for (boolean dive : path.dive()) {
            if (dive) {
                dives++;
            }
        }
        if (path.complete()) {
            return String.format(Locale.ROOT, "Tremor #%d: path to %s, %.1f blocks, %d segments, %d dives "
                            + "(%d nodes expanded)", entity.instance(), node(search.goal()), path.length(),
                    path.size() - 1, dives, search.expanded());
        }
        return String.format(Locale.ROOT, "Tremor #%d: no full path to %s within %d nodes; heading for the closest "
                        + "point found, %s (%.1f blocks, %d dives), and planning on from there",
                entity.instance(), node(search.goal()), search.expanded(), node(path.last()), path.length(), dives);
    }

    private TremorEntity requireEntity() {
        TremorEntity entity = data.entity();
        if (entity == null) {
            throw new IllegalStateException("no tremor in " + level.dimension().location());
        }
        return entity;
    }

    private static BlockPos toBlockPos(long node) {
        return new BlockPos(VoxelPos.x(node), VoxelPos.y(node), VoxelPos.z(node));
    }

    private static String node(long node) {
        return VoxelPos.x(node) + " " + VoxelPos.y(node) + " " + VoxelPos.z(node);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
