package tremor.hollow.level;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import tremor.Tremor;
import tremor.awakening.Outcomes;
import tremor.block.TremorBlocks;
import tremor.config.TremorConfig;
import tremor.hollow.HollowBox;
import tremor.hollow.HollowEvent;
import tremor.network.TremorHollowStatePayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The level of one event of the hollow (SPEC 9, phase 2): what is played out in the copy around the swallowed player.
 * Runtime only (an event does not survive a restart). Server thread only.
 * <p>
 * <b>Before the player arrives</b> ({@link #prepare}, while the screen is still dark; a step per tick, then the
 * light engine is waited for): a tight place around the player is widened into a cave ({@link Widening}); the node is
 * placed {@code nodeMinDistance}..{@code nodeMaxDistance} steps away, in the open (never under water) or at the end of
 * a dug tunnel, and the way to it is kept ({@link WayPlanner}). Both work on a snapshot of the copy around the player
 * and stay inside the copy, {@value #MARGIN} blocks from its sides, top and bottom and a quarter of its radius (4
 * blocks at least) from its round edge; what they carve is sealed from the fluids, lava and fire around it.
 * <p>
 * <b>While the player is inside</b> ({@link #tick}, within {@code hollow.level.budgetMillis}):
 * <ul>
 *   <li>the closing ({@link ClosingSchedule}, {@link Closer}): after a grace period the edges close in, faster with
 *   the noise the player makes ({@link #noise}), paused by lures ({@link #lure});</li>
 *   <li>the moving walls ({@link WallShifter});</li>
 *   <li>the soft ground ({@link Mire}): a player who stands still sinks; pulled in over the eyes is the defeat
 *   ({@link Outcomes#defeat});</li>
 *   <li>the edge: a player who gets within a block of the round edge of the copy, over its top (flying, on a pillar)
 *   or under its bottom (dug through) is out ({@link Outcomes#edgeEscape}, which finds the place to come out at);</li>
 *   <li>the node: breaking it is the victory ({@link #nodeBroken}, {@link Outcomes#victory}); a node gone otherwise
 *   comes back;</li>
 *   <li>the player gets the state of the hollow ({@link TremorHollowStatePayload}) every {@value #SYNC_TICKS} ticks
 *   and on changes.</li>
 * </ul>
 * Once an outcome is called (or the event has one, {@link HollowEvent#outcome}) everything stands still. A level that
 * fails ({@link #fail}) stands still too, and {@link HollowLevels} brings its player back.
 */
final class EventLevel {
    /** Stays this far inside the sides, top and bottom of the copy (blocks). */
    private static final int MARGIN = 3;
    /** The planning looks this far above and below the player's feet (blocks). */
    private static final int SNAPSHOT_HEIGHT = 12;
    /** How far around the player open space counts for the widening (blocks). */
    private static final int WIDEN_RADIUS = 8;
    /**
     * The widened cave: radius and height under its middle (some 10 blocks across, 5 high, give or take the wobble of
     * {@link Widening}: some 250 open blocks, well over {@code widenBelow}).
     */
    private static final double CAVE_RADIUS = 5;
    private static final double CAVE_HEIGHT = 5;
    /** How far the closing front wanders in and out (blocks). */
    private static final double FRONT_WOBBLE = 1.5;
    /** Standing within this distance of where the player stopped is standing still (blocks, SPEC 9). */
    private static final double STILL_DISTANCE = 0.25;
    /** How deep the ground softens at most: a little over the eye height of a standing player (blocks). */
    private static final double SINK_DEPTH = 1.75;
    /** The state goes to the player this often at least (ticks)... */
    private static final int SYNC_TICKS = 10;
    /** ...and every this many ticks while the player sinks by {@value #SINK_STEP} or more. */
    private static final int SINK_SYNC_TICKS = 2;
    private static final double SINK_STEP = 0.02;
    /** The node is looked for this often (ticks). */
    private static final int NODE_CHECK_TICKS = 20;

    private enum Stage {
        WIDEN, PLAN, LIGHT, READY
    }

    private final ServerLevel hollow;
    private final HollowEvent event;
    /** Id of the level in {@link TremorHollowStatePayload#event}: new for every swallow while the server runs. */
    private final int id;
    private final long seed;
    private final HollowBox box;
    private final double centreX;
    private final double centreY;
    private final double centreZ;
    /** Horizontal radius of the copy: the edge. */
    private final double boxRadius;
    /** The player's feet on arrival. */
    private final BlockPos start;
    /** Where the planning may change blocks and put the node. */
    private final VoxelGrid.Region region;

    private Stage stage = Stage.WIDEN;
    private VoxelGrid grid;
    private final LongOpenHashSet changedChunks = new LongOpenHashSet();
    private final List<CompletableFuture<?>> light = new ArrayList<>();
    private int openAround;
    private boolean widened;
    private int dug;
    private int filled;
    private BlockPos node;
    private boolean nodeBroken;
    /** The open cells of the way to the node: never filled, never moved into. */
    private LongSet way = new LongOpenHashSet();
    private int wayLength;
    private boolean tunnel;
    private double prepareMillis;
    private String failure;

    private ClosingSchedule schedule;
    private Closer closer;
    private final WallShifter walls = new WallShifter();
    private Mire mire;
    private long ticks;
    private boolean outcomeCalled;

    private long sentTick = Long.MIN_VALUE / 2;
    private float sentSink;
    private int sentBeat;
    private BlockPos sentNode;
    private long workNanos;
    private long worstNanos;

    EventLevel(ServerLevel hollow, HollowEvent event, int id) {
        this.hollow = hollow;
        this.event = event;
        this.id = id;
        box = event.hollowBox();
        centreX = (box.minX() + box.maxX() + 1) / 2.0;
        centreZ = (box.minZ() + box.maxZ() + 1) / 2.0;
        Vec3 arrival = event.toHollow(event.origin().position());
        centreY = arrival.y;
        start = BlockPos.containing(arrival);
        boxRadius = (box.sizeX() - 1) / 2.0;
        seed = LevelNoise.mix(event.player().getMostSignificantBits() ^ event.player().getLeastSignificantBits()
                ^ event.createdGameTime());
        double inner = boxRadius - Math.max(4, boxRadius / 4);
        region = (x, y, z) -> x >= box.minX() + MARGIN && x <= box.maxX() - MARGIN && z >= box.minZ() + MARGIN
                && z <= box.maxZ() - MARGIN && y >= box.minY() + MARGIN && y <= box.maxY() - MARGIN
                && square(x + 0.5 - centreX) + square(z + 0.5 - centreZ) <= inner * inner;
    }

    // ---- before the player arrives ----

    /** One step of the preparation; true once the level is ready (the player may be moved in). */
    boolean prepare() {
        long begin = System.nanoTime();
        try {
            switch (stage) {
                case WIDEN -> widen();
                case PLAN -> plan();
                case LIGHT -> settle();
                case READY -> {
                }
            }
        } finally {
            prepareMillis += (System.nanoTime() - begin) / 1e6;
        }
        return stage == Stage.READY;
    }

    /** Something went wrong: the level stands still for good (its player is brought back by {@link HollowLevels}). */
    void fail(RuntimeException e) {
        failure = e.toString();
        stage = Stage.READY;
        grid = null;
        light.clear();
    }

    private void widen() {
        TremorConfig.HollowLevel config = TremorConfig.COMMON.hollow.level;
        int reach = Math.max(config.nodeMinDistance.get(), config.nodeMaxDistance.get()) + MARGIN;
        grid = GridReader.read(hollow, Math.max(box.minX(), start.getX() - reach),
                Math.max(box.minY(), start.getY() - SNAPSHOT_HEIGHT), Math.max(box.minZ(), start.getZ() - reach),
                Math.min(box.maxX(), start.getX() + reach), Math.min(box.maxY(), start.getY() + SNAPSHOT_HEIGHT),
                Math.min(box.maxZ(), start.getZ() + reach));
        int threshold = config.widenBelow.get();
        openAround = Widening.openSpace(grid, start.getX(), start.getY(), start.getZ(), WIDEN_RADIUS,
                Math.max(threshold, 1));
        if (Widening.needed(openAround, threshold)) {
            Widening.Cave cave = Widening.cave(grid, region, start.getX(), start.getY(), start.getZ(), CAVE_RADIUS,
                    CAVE_HEIGHT, seed);
            change(cave.carve(), cave.fill());
            cave.applyTo(grid);
            widened = true;
        }
        stage = Stage.PLAN;
    }

    private void plan() {
        TremorConfig.HollowLevel config = TremorConfig.COMMON.hollow.level;
        int min = config.nodeMinDistance.get();
        WayPlanner.Plan plan = WayPlanner.plan(grid, region, start.getX(), start.getY(), start.getZ(), min,
                Math.max(min, config.nodeMaxDistance.get()), seed + 1);
        change(plan.carve(), plan.fill());
        BlockPos at = BlockPos.of(plan.node());
        // Only if the planning had nowhere to go at all would it be the player's own cell: then there is no node.
        if (!at.equals(start) && !at.equals(start.above())) {
            hollow.setBlock(at, TremorBlocks.HEART_NODE.get().defaultBlockState(), Materials.FLAGS);
            changedChunks.add(ChunkPos.asLong(at));
            node = at;
        }
        way = new LongOpenHashSet(plan.way());
        wayLength = plan.length();
        tunnel = plan.tunnel();
        grid = null;
        for (long chunk : changedChunks) {
            light.add(hollow.getChunkSource().getLightEngine().waitForPendingTasks(ChunkPos.getX(chunk),
                    ChunkPos.getZ(chunk)));
        }
        stage = Stage.LIGHT;
    }

    /** Waits for the light of the changes, then sets up the running parts with the config of now. */
    private void settle() {
        for (CompletableFuture<?> future : light) {
            if (!future.isDone()) {
                return;
            }
        }
        light.clear();
        TremorConfig.HollowLevel config = TremorConfig.COMMON.hollow.level;
        double edge = boxRadius - 1;
        schedule = new ClosingSchedule(new ClosingSchedule.Params(config.graceSeconds.get() * 20, edge,
                config.minRadius.get(), config.closeSpeed.get(), config.noiseFactor.get(), config.noiseSeconds.get(),
                ticks(config.lurePauseSeconds.get()), ticks(config.lureCooldownSeconds.get()),
                config.lureMinDistance.get(), config.beatSlowTicks.get(), config.beatFastTicks.get()));
        closer = new Closer(new ClosingOrder(box.minX(), box.minZ(), box.maxX(), box.maxZ(), centreX, centreZ, edge,
                FRONT_WOBBLE, seed), box.minY(), box.maxY(), centreX, centreZ);
        int sinkTicks = ticks(config.sinkSeconds.get());
        mire = new Mire(new SinkTracker.Params(ticks(config.stillSeconds.get()), STILL_DISTANCE,
                SINK_DEPTH / sinkTicks, SINK_DEPTH), ticks(config.recoverSeconds.get()));
        stage = Stage.READY;
        Tremor.LOGGER.info("Hollow level: {} ready in {} ms: {} ({} open blocks around the player{}); node {}, {} "
                        + "steps away {}, way of {} blocks", event, String.format(Locale.ROOT, "%.1f", prepareMillis),
                widened ? "widened" : "not widened", openAround,
                widened ? ", " + dug + " dug, " + filled + " filled" : "", node == null ? "none" : text(node),
                wayLength, tunnel ? "through a dug tunnel" : "in the open", way.size());
    }

    /**
     * Empties the cells {@code carve} (air) and fills the cells {@code fill} (floors, sealed fluids) with what is
     * around them.
     */
    private void change(Set<Long> carve, Set<Long> fill) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (long cell : carve) {
            pos.set(cell);
            if (!hollow.getBlockState(pos).isAir()) {
                hollow.setBlock(pos, Materials.AIR, Materials.FLAGS);
                changedChunks.add(ChunkPos.asLong(pos));
                dug++;
            }
        }
        for (long cell : fill) {
            pos.set(cell);
            hollow.setBlock(pos, Materials.floor(hollow, pos), Materials.FLAGS);
            changedChunks.add(ChunkPos.asLong(pos));
            filled++;
        }
    }

    // ---- while the player is inside ----

    /** Whether the level failed ({@link #fail}). */
    boolean failed() {
        return failure != null;
    }

    /** Whether the level plays: ready, the player inside, no outcome yet. */
    boolean running() {
        return stage == Stage.READY && failure == null && !outcomeCalled && event.outcome() == null
                && event.phase() == HollowEvent.Phase.INSIDE;
    }

    /** One tick with the player inside the copy, alive. */
    void tick(ServerPlayer player) {
        if (!running() || !hollow.tickRateManager().runsNormally()) {
            return;
        }
        long begin = System.nanoTime();
        TremorConfig.HollowLevel config = TremorConfig.COMMON.hollow.level;
        ticks++;
        schedule.tick();
        Vec3 at = player.position();
        String out = out(at);
        if (out != null) {
            decided(player, out);
            Outcomes.edgeEscape(player, new tremor.core.math.Vec3(at.x, at.y, at.z));
            return;
        }
        if (ticks % NODE_CHECK_TICKS == 0) {
            keepNode(player);
        }
        if (mire.tick(hollow, player, this, ticks) >= 1) {
            decided(player, "was pulled in");
            Outcomes.defeat(player);
            return;
        }
        int shiftTicks = Math.max(1, ticks(config.wallShiftSeconds.get()));
        if (config.wallShifts.get() > 0 && ticks % shiftTicks == 0) {
            walls.shift(hollow, this, at.add(0, player.getBbHeight() / 2, 0), schedule.radius(),
                    config.wallShifts.get());
        }
        closer.tick(hollow, this, schedule.radius(), at, player.getBbHeight(), ticks,
                begin + (long) (config.budgetMillis.get() * 1e6), config.fillsPerTick.get());
        sync(player, false);
        long spent = System.nanoTime() - begin;
        workNanos += spent;
        worstNanos = Math.max(worstNanos, spent);
    }

    /** The player made a vibration that reached the ground with {@code loudness} (SPEC 9: the noise speeds the closing). */
    void noise(double loudness) {
        if (running()) {
            schedule.noise(loudness);
        }
    }

    /** Something without a player behind it landed at {@code at} (SPEC 9, "Приманки"): a lure, if far enough. */
    void lure(tremor.core.math.Vec3 at) {
        ServerPlayer player = running() ? hollow.getServer().getPlayerList().getPlayer(event.player()) : null;
        if (player != null && schedule.lure(Math.sqrt(player.distanceToSqr(at.x(), at.y(), at.z())))) {
            Tremor.LOGGER.debug("Hollow level: a lure at {} stops the closing of {}", at, event);
        }
    }

    /**
     * The node at {@code pos} was broken by {@code player}; true if it was this level's node. Broken by the event's
     * player, it is the victory; by anybody else, it comes back.
     */
    boolean nodeBroken(ServerPlayer player, BlockPos pos) {
        if (node == null || nodeBroken || !pos.equals(node)) {
            return false;
        }
        if (player.getUUID().equals(event.player()) && stage == Stage.READY && failure == null && !outcomeCalled
                && event.outcome() == null) {
            nodeBroken = true;
            decided(player, "destroyed the node");
            Outcomes.victory(player);
        }
        return true;
    }

    /** The event ended: the player's client drops the state. */
    void ended() {
        ServerPlayer player = hollow.getServer().getPlayerList().getPlayer(event.player());
        if (player != null && sentTick > Long.MIN_VALUE / 2) {
            PacketDistributor.sendToPlayer(player, TremorHollowStatePayload.inactive(id,
                    player.level().getGameTime()));
        }
    }

    /** Where the node is, or null while there is none (not placed yet, broken, or the level failed). */
    BlockPos node() {
        return nodeBroken ? null : node;
    }

    /** Whether the cell is on the way to the node: never filled, never moved into. */
    boolean keepsOpen(long cell) {
        return way.contains(cell);
    }

    boolean isNode(BlockPos pos) {
        return pos.equals(node);
    }

    /**
     * Whether a moving wall may take or leave {@code pos}: inside the copy, inside the part still open (the closing
     * {@code radius}), off the way and the node, {@code near}..{@code far} blocks from {@code player}.
     */
    boolean mayShift(BlockPos pos, Vec3 player, double radius, double near, double far) {
        double distance = Math.sqrt(player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
        return pos.getX() > box.minX() && pos.getX() < box.maxX() && pos.getZ() > box.minZ()
                && pos.getZ() < box.maxZ() && pos.getY() > box.minY() && pos.getY() < box.maxY()
                && Math.hypot(pos.getX() + 0.5 - centreX, pos.getZ() + 0.5 - centreZ) < radius - 1
                && !way.contains(pos.asLong()) && !isNode(pos) && distance >= near && distance <= far;
    }

    /** For {@code /tremor hollow status}. */
    String describe() {
        if (failure != null) {
            return "level: failed (" + failure + "), the player is brought back";
        }
        if (stage != Stage.READY) {
            return "level: preparing (" + stage.name().toLowerCase(Locale.ROOT) + ")";
        }
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                "level #%d: node %s%s, %d steps away %s, way of %d blocks; %s (%d open blocks around)", id,
                node == null ? "none" : text(node), nodeBroken ? " (destroyed)" : "", wayLength,
                tunnel ? "through a dug tunnel" : "in the open", way.size(),
                widened ? "widened: " + dug + " dug, " + filled + " filled" : "not widened", openAround));
        String closing = schedule.graceLeft() > 0
                ? String.format(Locale.ROOT, "grace %.1f s", schedule.graceLeft() / 20.0)
                : schedule.pauseLeft() > 0 ? String.format(Locale.ROOT, "lured, %.1f s", schedule.pauseLeft() / 20.0)
                : schedule.closed() >= 1 ? "closed" : "closing";
        text.append(String.format(Locale.ROOT, "\n  closing radius %.1f of %.1f (min %.1f, %.0f%%): %s, %.2f "
                        + "blocks/s, noise %.1f, %d lures; %d blocks filled, %d columns pending; %d wall blocks moved",
                schedule.radius(), boxRadius - 1, Math.min(TremorConfig.COMMON.hollow.level.minRadius.get(),
                        boxRadius - 1), 100 * schedule.closed(), closing, schedule.speed(), schedule.noiseLevel(),
                schedule.lures(), closer.filled(), closer.pending(), walls.shifted()));
        text.append(String.format(Locale.ROOT, "\n  sink %.2f (still for %.1f s, softened %.2f blocks), %d soft "
                        + "blocks; a beat every %d ticks; %s; work %.3f ms per tick on average, worst %.2f ms; "
                        + "prepared in %.1f ms", mire.sink(), mire.tracker().stillTicks() / 20.0,
                mire.tracker().depth(), mire.size(), schedule.beatTicks(),
                outcomeCalled || event.outcome() != null ? "stopped (outcome)" : running() ? "running" : "waiting",
                ticks == 0 ? 0 : workNanos / 1e6 / ticks, worstNanos / 1e6, prepareMillis));
        return text.toString();
    }

    // ---- internals ----

    /**
     * How the player at {@code at} (feet) is out of the copy, or null if not: within a block of its round edge, with
     * the feet over its top layer (flying, on a pillar), or under the shell below it (dug through). The copy's bottom
     * is closed ({@code TerrainCopier}), so nobody falls out of it.
     */
    private String out(Vec3 at) {
        if (Math.hypot(at.x - centreX, at.z - centreZ) >= boxRadius - 1) {
            return "got to the edge";
        }
        if (at.y >= box.maxY() + 1) {
            return "got out over the top";
        }
        return at.y < box.minY() - 1 ? "got out under the bottom" : null;
    }

    /** An outcome is about to be called: the level stops, and the player gets its last state. */
    private void decided(ServerPlayer player, String what) {
        outcomeCalled = true;
        sync(player, true);
        Tremor.LOGGER.info("Hollow level: {} {} after {} s", event.playerName(), what,
                String.format(Locale.ROOT, "%.1f", ticks / 20.0));
    }

    /** Puts the node back if it is gone (not broken by the player: something else took it). */
    private void keepNode(ServerPlayer player) {
        if (node != null && !nodeBroken && !hollow.getBlockState(node).is(TremorBlocks.HEART_NODE)
                && !player.getBoundingBox().intersects(new AABB(node))) {
            hollow.setBlock(node, TremorBlocks.HEART_NODE.get().defaultBlockState(), Materials.FLAGS);
        }
    }

    /** Sends the state when it is due ({@code force}: now). */
    private void sync(ServerPlayer player, boolean force) {
        BlockPos shown = nodeBroken ? null : node;
        float sink = (float) mire.sink();
        int beat = schedule.beatTicks();
        long since = ticks - sentTick;
        if (!force && since < SYNC_TICKS && Objects.equals(shown, sentNode) && beat == sentBeat
                && (since < SINK_SYNC_TICKS || Math.abs(sink - sentSink) < SINK_STEP)) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new TremorHollowStatePayload(id, true,
                new tremor.core.math.Vec3(centreX, centreY, centreZ), (float) boxRadius, (float) schedule.radius(),
                shown, beat, sink, hollow.getGameTime()));
        sentTick = ticks;
        sentSink = sink;
        sentBeat = beat;
        sentNode = shown;
    }

    private static int ticks(double seconds) {
        return (int) Math.round(seconds * 20);
    }

    private static double square(double v) {
        return v * v;
    }

    private static String text(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}
