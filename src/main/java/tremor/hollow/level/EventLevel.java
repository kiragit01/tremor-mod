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
import tremor.hollow.TickBudget;
import tremor.network.TremorHollowStatePayload;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * The level of one event of the hollow (SPEC 9, phase 2): what is played out in the copy around the swallowed player.
 * Runtime only (an event does not survive a restart). Server thread only.
 * <p>
 * <b>Before the player arrives</b> ({@link #prepare}, while the screen is still dark, within the
 * {@code hollow.budgetMillis} per tick that the copying and clearing of all events share, as it finishes the copy; then
 * the light engine is waited for): a snapshot of the copy around the player is read ({@link GridReader}, a chunk column
 * at a time); a tight place around the player is widened into a cave some 16 blocks across ({@link Widening}); the
 * network of ways is planned on the snapshot ({@link WayPlanner}, a part at a time): the node under the ground
 * {@code nodeMinDistance}..{@code nodeMaxDistance} steps away, the way to it (from a throat on open ground) kept open
 * for good, {@code minDeadEnds}..{@code maxDeadEnds} dead ends, walled corridors where they pass through caves; then
 * the blocks are written, a batch at a time. All of it stays inside the copy, {@value #MARGIN} blocks from its sides,
 * top and bottom and a quarter of its radius (4 blocks at least) from its round edge (a seal or a wall a block further
 * out at most); what is carved is sealed from the fluids, lava and fire around it. The time it took is logged.
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
    /** The planning looks this far above the player's feet (blocks)... */
    private static final int SNAPSHOT_ABOVE = 12;
    /** ...and this far below: the node goes under the ground, on open ground some 10 blocks down. */
    private static final int SNAPSHOT_BELOW = 20;
    /** How far around the player open space counts for the widening (blocks). */
    private static final int WIDEN_RADIUS = 8;
    /**
     * The widened cave: radius and height under its middle (some 16 blocks across, 6 high, give or take the wobble of
     * {@link Widening}: some 700 open blocks, well over {@code widenBelow}).
     */
    private static final double CAVE_RADIUS = 8;
    private static final double CAVE_HEIGHT = 6;
    /** Blocks written between two looks at the clock. */
    private static final int CLOCK_EVERY = 32;
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
    /** {@code /tremor hollow walk} walks up to this many times the longest way planned ({@code nodeMaxDistance}). */
    private static final int WALK_LENGTHS = 4;

    private enum Stage {
        READ, WIDEN, PLAN, WRITE, LIGHT, READY
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

    private Stage stage = Stage.READ;
    private GridReader reader;
    private VoxelGrid grid;
    private WayPlanner planner;
    /** What the widening changes, until it is merged with the plan of the network. */
    private Widening.Cave cave;
    /** The cells to empty, then the cells to fill; {@link #written} of them are written so far. */
    private long[] carving;
    private long[] filling;
    private int written;
    private final LongOpenHashSet changedChunks = new LongOpenHashSet();
    private final List<CompletableFuture<?>> light = new ArrayList<>();
    private int openAround;
    private boolean widened;
    /** How big the widened cave is, for the log and the status; empty if there is none. */
    private String caveSize = "";
    private int dug;
    private int filled;
    private BlockPos node;
    private boolean nodeBroken;
    /** The open cells of the way to the node: never filled, never moved into. */
    private LongSet way = new LongOpenHashSet();
    /** The network as planned; null until then. */
    private WayPlanner.Plan plan;
    /** The steps to the node it was planned for ({@code nodeMinDistance}..{@code nodeMaxDistance} then). */
    private int minLength;
    private int maxLength;
    /** The box of the snapshot the planning read (bounds inclusive), for {@link #walk}; null until it is read. */
    private BlockPos snapshotMin;
    private BlockPos snapshotMax;
    private double prepareMillis;
    private int prepareTicks;
    private int planSteps;
    /** Server time each stage of the preparation took (nanoseconds), by {@link Stage#ordinal}. */
    private final long[] stageNanos = new long[Stage.values().length];
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

    /**
     * One tick of the preparation: parts of it while {@code budget} has time (the tick's {@code hollow.budgetMillis},
     * shared with the copying and the clearing of every event and the preparation of the others), at least one; true
     * once the level is ready (the player may be moved in).
     */
    boolean prepare(TickBudget budget) {
        long begin = System.nanoTime();
        if (stage != Stage.READY) {
            prepareTicks++;
        }
        try {
            do {
                Stage part = stage;
                long from = System.nanoTime();
                switch (stage) {
                    case READ -> read();
                    case WIDEN -> widen();
                    case PLAN -> plan();
                    case WRITE -> write(budget);
                    case LIGHT -> settle();
                    case READY -> {
                    }
                }
                stageNanos[part.ordinal()] += System.nanoTime() - from;
            } while (stage != Stage.LIGHT && stage != Stage.READY && budget.hasTime());
        } finally {
            prepareMillis += (System.nanoTime() - begin) / 1e6;
        }
        return stage == Stage.READY;
    }

    /** Something went wrong: the level stands still for good (its player is brought back by {@link HollowLevels}). */
    void fail(RuntimeException e) {
        failure = e.toString();
        stage = Stage.READY;
        reader = null;
        grid = null;
        planner = null;
        cave = null;
        carving = null;
        filling = null;
        light.clear();
    }

    /** Reads the next chunk column of the snapshot. */
    private void read() {
        if (reader == null) {
            TremorConfig.HollowLevel config = TremorConfig.COMMON.hollow.level;
            int reach = Math.max(config.nodeMinDistance.get(), config.nodeMaxDistance.get()) + MARGIN;
            snapshotMin = new BlockPos(Math.max(box.minX(), start.getX() - reach),
                    Math.max(box.minY(), start.getY() - SNAPSHOT_BELOW), Math.max(box.minZ(), start.getZ() - reach));
            snapshotMax = new BlockPos(Math.min(box.maxX(), start.getX() + reach),
                    Math.min(box.maxY(), start.getY() + SNAPSHOT_ABOVE), Math.min(box.maxZ(), start.getZ() + reach));
            reader = new GridReader(hollow, snapshotMin.getX(), snapshotMin.getY(), snapshotMin.getZ(),
                    snapshotMax.getX(), snapshotMax.getY(), snapshotMax.getZ());
        }
        if (reader.step()) {
            grid = reader.grid();
            reader = null;
            stage = Stage.WIDEN;
        }
    }

    /** Widens a tight place into a cave (in the snapshot; the blocks are written with the network's). */
    private void widen() {
        TremorConfig.HollowLevel config = TremorConfig.COMMON.hollow.level;
        int threshold = config.widenBelow.get();
        openAround = Widening.openSpace(grid, start.getX(), start.getY(), start.getZ(), WIDEN_RADIUS,
                Math.max(threshold, 1));
        if (Widening.needed(openAround, threshold)) {
            cave = Widening.cave(grid, region, start.getX(), start.getY(), start.getZ(), CAVE_RADIUS, CAVE_HEIGHT,
                    seed);
            cave.applyTo(grid);
            widened = true;
            caveSize = size(cave.carve());
        }
        minLength = config.nodeMinDistance.get();
        maxLength = Math.max(minLength, config.nodeMaxDistance.get());
        planner = new WayPlanner(grid, region, start.getX(), start.getY(), start.getZ(), new WayPlanner.Params(
                minLength, maxLength, config.nodeMinStraight.get(), config.minDeadEnds.get(),
                Math.max(config.minDeadEnds.get(), config.maxDeadEnds.get())), seed + 1);
        stage = Stage.PLAN;
    }

    /**
     * A part of the planning of the network; once it is done, what the cave and the network change is merged into the
     * blocks to write: a cell the cave filled and the network empties is empty, and the other way round.
     */
    private void plan() {
        planSteps++;
        if (!planner.step()) {
            return;
        }
        plan = planner.plan();
        planner = null;
        grid = null;
        Set<Long> carve = new LinkedHashSet<>();
        Set<Long> fill = new LinkedHashSet<>();
        if (cave != null) {
            carve.addAll(cave.carve());
            fill.addAll(cave.fill());
            cave = null;
        }
        carve.removeAll(plan.fill());
        fill.removeAll(plan.carve());
        carve.addAll(plan.carve());
        fill.addAll(plan.fill());
        carving = carve.stream().mapToLong(Long::longValue).toArray();
        filling = fill.stream().mapToLong(Long::longValue).toArray();
        way = new LongOpenHashSet(plan.way());
        stage = Stage.WRITE;
    }

    /**
     * Empties the cells to carve (air), then fills the cells to fill (floors, walls, sealed fluids) with what is around
     * them, while {@code budget} has time; once all are written, places the node and waits for the light.
     */
    private void write(TickBudget budget) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int n = 0; written < carving.length + filling.length; n++) {
            if (n > 0 && n % CLOCK_EVERY == 0 && !budget.hasTime()) {
                return;
            }
            if (written < carving.length) {
                pos.set(carving[written]);
                if (!hollow.getBlockState(pos).isAir()) {
                    hollow.setBlock(pos, Materials.AIR, Materials.FLAGS);
                    changedChunks.add(ChunkPos.asLong(pos));
                    dug++;
                }
            } else {
                pos.set(filling[written - carving.length]);
                hollow.setBlock(pos, Materials.floor(hollow, pos), Materials.FLAGS);
                changedChunks.add(ChunkPos.asLong(pos));
                filled++;
            }
            written++;
        }
        carving = null;
        filling = null;
        BlockPos at = BlockPos.of(plan.node());
        // Only if the planning had nowhere to go at all would it be the player's own cell: then there is no node.
        if (!at.equals(start) && !at.equals(start.above())) {
            hollow.setBlock(at, TremorBlocks.HEART_NODE.get().defaultBlockState(), Materials.FLAGS);
            changedChunks.add(ChunkPos.asLong(at));
            node = at;
        }
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
        Tremor.LOGGER.info("Hollow level: {} ready in {} ms of server time over {} ticks (reading {} ms, widening {} "
                        + "ms, planning {} ms in {} parts, writing {} ms); {}; {}", event,
                String.format(Locale.ROOT, "%.1f", prepareMillis), prepareTicks, millis(Stage.READ),
                millis(Stage.WIDEN), millis(Stage.PLAN), planSteps, millis(Stage.WRITE), network(), changes());
    }

    /** Server time the stage of the preparation took, for the log. */
    private String millis(Stage part) {
        return String.format(Locale.ROOT, "%.1f", stageNanos[part.ordinal()] / 1e6);
    }

    /** The node and the network, for the log and the status. */
    private String network() {
        BlockPos mouth = BlockPos.of(plan.mouth());
        String throat = plan.throat() ? String.format(Locale.ROOT, "a throat down from the surface at %s (%.1f blocks "
                + "from the player)", text(mouth), Math.hypot(mouth.getX() - start.getX(), mouth.getZ() - start.getZ()))
                : "no throat";
        String length = plan.length() < 0 ? "NOT reached on foot"
                : plan.length() < minLength ? String.format(Locale.ROOT, "%d steps away along the way, SHORT (fewer "
                + "than nodeMinDistance %d)", plan.length(), minLength)
                : plan.length() > maxLength ? String.format(Locale.ROOT, "%d steps away along the way, LONG (more "
                + "than nodeMaxDistance %d)", plan.length(), maxLength)
                : plan.length() + " steps away along the way";
        return String.format(Locale.ROOT, "node %s%s, %s, %.1f blocks in a straight line "
                        + "(%.1f from the nearest place of the start)%s%s; %s; %d dead ends (%d out of the start, %d "
                        + "decoy throats); way of %d blocks (%d tries)", node == null ? "none" : text(node),
                nodeBroken ? " (destroyed)" : "", length, plan.straight(), plan.clearance(),
                plan.covered() ? ", under the ground" : ", NOT under the ground (nowhere deep enough)",
                plan.stuck() == 0 ? "" : ", STUCK: " + plan.stuck() + " places walked to without a way on to it",
                throat, plan.branches(), plan.fromStart(), plan.decoys(), way.size(), plan.tries());
    }

    /** What the preparation changed, for the log and the status. */
    private String changes() {
        return String.format(Locale.ROOT, "%s (%d open blocks around the player)%s; %d blocks dug, %d filled",
                widened ? "widened" : "not widened", openAround, caveSize, dug, filled);
    }

    /** The size of the widened cave ({@code cells}: what it digs), for {@link #changes}. */
    private static String size(Set<Long> cells) {
        if (cells.isEmpty()) {
            return "";
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long cell : cells) {
            minX = Math.min(minX, CellKey.x(cell));
            maxX = Math.max(maxX, CellKey.x(cell));
            minY = Math.min(minY, CellKey.y(cell));
            maxY = Math.max(maxY, CellKey.y(cell));
            minZ = Math.min(minZ, CellKey.z(cell));
            maxZ = Math.max(maxZ, CellKey.z(cell));
        }
        return String.format(Locale.ROOT, " into a cave of %d blocks, %d x %d across and %d high", cells.size(),
                maxX - minX + 1, maxZ - minZ + 1, maxY - minY + 1);
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
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "level #%d: %s\n  %s", id, network(),
                changes()));
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
                        + "prepared in %.1f ms over %d ticks (planning %s ms)", mire.sink(),
                mire.tracker().stillTicks() / 20.0, mire.tracker().depth(), mire.size(), schedule.beatTicks(),
                outcomeCalled || event.outcome() != null ? "stopped (outcome)" : running() ? "running" : "waiting",
                ticks == 0 ? 0 : workNanos / 1e6 / ticks, worstNanos / 1e6, prepareMillis, prepareTicks,
                millis(Stage.PLAN)));
        return text.toString();
    }

    /**
     * Debug ({@code /tremor hollow walk}): walks the copy as it is now (the box the planning read, read afresh, the
     * moving walls and the closing included) from where {@code player} stands to the node, the way the planning walks
     * ({@link WayPlanner#landing}), up to {@value #WALK_LENGTHS} times {@code nodeMaxDistance} steps; then moves the
     * player {@code steps} steps along that walk (0: not at all, at most to the cell before the node), facing the step
     * after. What it found, for the command.
     */
    String walk(ServerPlayer player, int steps) {
        if (node == null || nodeBroken || stage != Stage.READY || failure != null || snapshotMin == null) {
            return "No node to walk to";
        }
        BlockPos feet = player.blockPosition();
        if (feet.equals(node)) {
            return "You are at the node";
        }
        if (feet.getX() < snapshotMin.getX() || feet.getY() < snapshotMin.getY() || feet.getZ() < snapshotMin.getZ()
                || feet.getX() > snapshotMax.getX() || feet.getY() > snapshotMax.getY()
                || feet.getZ() > snapshotMax.getZ()) {
            return "You stand outside the part of the copy the level was planned in";
        }
        GridReader read = new GridReader(hollow, snapshotMin.getX(), snapshotMin.getY(), snapshotMin.getZ(),
                snapshotMax.getX(), snapshotMax.getY(), snapshotMax.getZ());
        while (!read.step()) {
            // A debug command: all at once.
        }
        VoxelGrid now = read.grid();
        now.carve(node.asLong());
        WayPlanner.Walk walk = WayPlanner.distances(now, feet.getX(), feet.getY(), feet.getZ(),
                WALK_LENGTHS * maxLength);
        int length = walk.distance(node.getX(), node.getY(), node.getZ());
        if (length < 0) {
            BlockPos closest = feet;
            for (int i = 0; i < walk.reached(); i++) {
                BlockPos at = BlockPos.of(key(now, walk.order()[i]));
                if (at.distSqr(node) < closest.distSqr(node)) {
                    closest = at;
                }
            }
            return String.format(Locale.ROOT, "No way on foot from %s to the node at %s within %d steps: %d cells "
                            + "reached, the closest %s, %.1f blocks from it", text(feet), text(node),
                    WALK_LENGTHS * maxLength, walk.reached(), text(closest), Math.sqrt(closest.distSqr(node)));
        }
        List<BlockPos> path = new ArrayList<>();
        for (int at = now.index(node.getX(), node.getY(), node.getZ()); at >= 0; at = walk.parent()[at]) {
            path.add(0, BlockPos.of(key(now, at)));
        }
        String found = String.format(Locale.ROOT, "The node at %s is %d steps on foot from %s (%.1f blocks in a "
                + "straight line)", text(node), length, text(feet), Math.sqrt(feet.distSqr(node)));
        if (steps <= 0) {
            return found;
        }
        int to = Math.min(steps, length - 1);
        BlockPos stand = path.get(to);
        BlockPos next = path.get(to + 1);
        float yaw = (float) Math.toDegrees(Math.atan2(-(next.getX() - stand.getX()), next.getZ() - stand.getZ()));
        player.teleportTo(hollow, stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, yaw, 10);
        return found + String.format(Locale.ROOT, "; moved %d steps to %s, %d left", to, text(stand), length - to);
    }

    // ---- internals ----

    /** The cell of {@code grid} at the index {@code index}, as a {@link CellKey}. */
    private static long key(VoxelGrid grid, int index) {
        int sizeX = grid.maxX() - grid.minX() + 1;
        int sizeZ = grid.maxZ() - grid.minZ() + 1;
        return CellKey.of(grid.minX() + index % sizeX, grid.minY() + index / sizeX / sizeZ,
                grid.minZ() + index / sizeX % sizeZ);
    }

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
