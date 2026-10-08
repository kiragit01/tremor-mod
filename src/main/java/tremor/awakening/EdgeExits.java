package tremor.awakening;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import tremor.Tremor;
import tremor.hollow.HollowBox;
import tremor.hollow.HollowEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Finds where a player who got out through the edge of the hollow comes out in the real world (SPEC 9 "Побег"): a safe
 * standing spot near the matching place with a way to the swallow point ({@link EdgeExitRules}), searched in the copied
 * box and a margin around it, never in the footprint of the crater that opens at the swallow point once the player is
 * out ({@link Craters#footprint}; the search goes round it if need be, and as far out as it reaches, whatever the size
 * of the copy). The real chunks there are seldom loaded while their player is in the hollow, so a search
 * holds a ticket on them ({@link RealArea}) and waits for them, at most {@value #LOAD_TIMEOUT_TICKS} ticks (then, and
 * without a spot, the player comes out at the swallow point). Event handlers are registered by {@link tremor.Tremor};
 * server thread only. Not saved: a server that stops meanwhile ends the event anyway, and its player is back at the
 * swallow point on the next login.
 */
public final class EdgeExits {
    private static final TicketType<Integer> TICKET = TicketType.create(Tremor.MODID + "_exit", Integer::compare);
    /** A search whose chunks are not loaded after this many ticks is given up: out at the swallow point. */
    private static final int LOAD_TIMEOUT_TICKS = 200;
    /** The search goes this far beyond the copied box on every side (blocks). */
    private static final int MARGIN = EdgeExitRules.AROUND + 2;

    private static final List<Job> JOBS = new ArrayList<>();
    /** Id of the next search; ids are never reused while the server runs. */
    private static int nextId = 1;

    private EdgeExits() {
    }

    /**
     * Finds the exit for the player of {@code event} who reached the edge at {@code reached} (mapped into the real
     * world, in {@code level}) and tells {@code done} where its feet go (the middle of the bottom of the spot's block),
     * or null for the swallow point: at once if the chunks are loaded, else once they are. No exit lies within
     * {@code avoid} blocks (horizontally) of the swallow point (0: none avoided).
     */
    static void find(ServerLevel level, HollowEvent event, Vec3 reached, int avoid, Consumer<Vec3> done) {
        Job job = new Job(nextId++, level, event, reached, avoid, done);
        if (job.area.ready()) {
            job.finish(true);
        } else {
            JOBS.add(job);
        }
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (JOBS.isEmpty()) {
            return;
        }
        long now = event.getServer().getTickCount();
        for (Job job : List.copyOf(JOBS)) {
            boolean ready = false;
            boolean over;
            try {
                ready = job.area.ready();
                over = ready || now - job.startTick > LOAD_TIMEOUT_TICKS;
            } catch (RuntimeException e) {
                Tremor.LOGGER.error("Edge exit #{} failed", job.id, e);
                over = true;
            }
            if (over) {
                JOBS.remove(job);
                if (!ready) {
                    Tremor.LOGGER.warn("Edge exit #{}: the chunks did not load in {} ticks, out at the swallow point",
                            job.id, LOAD_TIMEOUT_TICKS);
                }
                try {
                    job.finish(ready);
                } catch (RuntimeException e) {
                    Tremor.LOGGER.error("Edge exit #{}: getting the player out failed", job.id, e);
                }
            }
        }
    }

    /** The levels and their tickets are gone; so are the events (their players come back out on login). */
    public static void onServerStopped(ServerStoppedEvent event) {
        JOBS.clear();
    }

    /** One search. */
    private static final class Job {
        final int id;
        final HollowEvent event;
        final Vec3 reached;
        final Consumer<Vec3> done;
        final long startTick;
        /**
         * Where the search and the way to the swallow point may go: the copied box and the margin, and the columns
         * searched around the footprint avoided, in the world.
         */
        final HollowBox within;
        /** The footprint no exit lies in, or null. */
        final EdgeExitRules.Avoid avoid;
        final RealArea area;
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        Job(int id, ServerLevel level, HollowEvent event, Vec3 reached, int avoid, Consumer<Vec3> done) {
            this.id = id;
            this.event = event;
            this.reached = reached;
            this.done = done;
            this.startTick = level.getServer().getTickCount();
            HollowBox box = event.box();
            BlockPos origin = event.origin().blockPos();
            this.avoid = avoid > 0 ? new EdgeExitRules.Avoid(origin.getX(), origin.getZ(), avoid) : null;
            // The spots just outside the footprint and the columns around them.
            int ring = avoid > 0 ? avoid + EdgeExitRules.AROUND + 2 : 0;
            within = new HollowBox(Math.min(box.minX() - MARGIN, origin.getX() - ring),
                    Math.max(level.getMinBuildHeight(), box.minY() - EdgeExitRules.DOWN - 1),
                    Math.min(box.minZ() - MARGIN, origin.getZ() - ring),
                    Math.max(box.maxX() + MARGIN, origin.getX() + ring),
                    Math.min(level.getMaxBuildHeight() - 1, box.maxY() + EdgeExitRules.UP + 2),
                    Math.max(box.maxZ() + MARGIN, origin.getZ() + ring));
            area = new RealArea(level, TICKET, id, within.minX(), within.minZ(), within.maxX(), within.maxZ());
        }

        /** Tells {@code done} the exit: searched if the chunks are {@code loaded}, else the swallow point. */
        void finish(boolean loaded) {
            Vec3 exit = null;
            try {
                exit = loaded ? search() : null;
            } catch (RuntimeException e) {
                Tremor.LOGGER.error("Edge exit #{} failed, out at the swallow point", id, e);
            } finally {
                area.release();
            }
            done.accept(exit);
        }

        /**
         * The exit of the search: the place reached, its height brought into the copied box (a player falling out of
         * the bottom of the copy, or high over it), and the swallow point (the blocks of the player's feet and head
         * there) as the place the way must lead to.
         */
        private Vec3 search() {
            HollowBox box = event.box();
            int x = Mth.floor(reached.x);
            int y = Mth.clamp(Mth.floor(reached.y), box.minY(), box.maxY());
            int z = Mth.floor(reached.z);
            BlockPos origin = event.origin().blockPos();
            List<EdgeExitRules.Spot> targets = List.of(
                    new EdgeExitRules.Spot(origin.getX(), origin.getY(), origin.getZ()),
                    new EdgeExitRules.Spot(origin.getX(), origin.getY() + 1, origin.getZ()));
            EdgeExitRules.Spot spot = EdgeExitRules.choose(this::cell, x, y, z, within, targets, avoid);
            Tremor.LOGGER.info(String.format(Locale.ROOT, "Edge exit #%d for %s: reached %d %d %d, %s", id,
                    event.playerName(), x, Mth.floor(reached.y), z, spot == null
                            ? "no safe spot with a way to the swallow point near, out there"
                            : "out at " + spot.x() + " " + spot.y() + " " + spot.z()));
            return spot == null ? null : new Vec3(spot.x() + 0.5, spot.y(), spot.z() + 0.5);
        }

        private EdgeExitRules.Cell cell(int x, int y, int z) {
            ServerLevel level = area.level();
            cursor.set(x, y, z);
            if (level.isOutsideBuildHeight(y) || !level.getWorldBorder().isWithinBounds(cursor)) {
                return EdgeExitRules.Cell.SOLID;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) {
                return EdgeExitRules.Cell.SOLID;
            }
            BlockState state = chunk.getBlockState(cursor);
            FluidState fluid = state.getFluidState();
            if (fluid.is(FluidTags.LAVA) || hurts(state)) {
                return EdgeExitRules.Cell.DANGER;
            }
            VoxelShape shape = state.getCollisionShape(level, cursor);
            if (!fluid.isEmpty()) {
                return shape.isEmpty() ? EdgeExitRules.Cell.WATER : EdgeExitRules.Cell.SOLID;
            }
            if (shape.isEmpty()) {
                return EdgeExitRules.Cell.OPEN;
            }
            return shape.max(Direction.Axis.Y) <= 1 ? EdgeExitRules.Cell.FLOOR : EdgeExitRules.Cell.SOLID;
        }
    }

    /** Whether a block hurts, burns or holds a player in it or on it. */
    private static boolean hurts(BlockState state) {
        return state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.COBWEB) || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.POINTED_DRIPSTONE);
    }
}
