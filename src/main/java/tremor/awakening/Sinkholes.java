package tremor.awakening;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tremor.Tremor;
import tremor.config.TremorConfig;
import tremor.sound.TremorSounds;
import tremor.world.TremorTags;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Digs the sinkholes of defeats into the real world (SPEC 9 "Поражение", 12: the one lasting change the Awakening makes
 * there): the bowl of a {@link SinkholeShape}, dug column by column as {@link SinkholeRules} allows, the centre first.
 * Each sinkhole holds a ticket on the chunks it touches (and the blocks around it) until it is done ({@link RealArea}),
 * and waits for them to load; the digging takes at most {@value #BUDGET_MILLIS} ms of server time per tick for all of
 * them, at least a column each (SPEC 16). A sinkhole that is no longer wanted (its defeat was called off) is not dug,
 * or no further. Event handlers are registered by {@link tremor.Tremor}; server thread only.
 * <p>
 * What is kept ({@link SinkholeRules.Cell#KEEP}): blocks with a block entity, of {@code #tremor:protected}, that
 * cannot be broken (a destroy speed below 0) or that burn (magma), and every block of the spawn protection (the
 * overworld around the world spawn, a square of the server's spawn protection radius: {@code spawn-protection} of
 * server.properties, 16 blocks in single player and on LAN) whoever the player is. Dug blocks drop nothing, and their
 * neighbours are not updated: nothing next to the sinkhole pops off, falls or flows because of it; only a plant next to
 * a dug block that cannot stay without it drops as usual. Not saved: a server that stops in the middle (a tick or two
 * of digging) leaves the sinkhole as far as it got.
 */
public final class Sinkholes {
    /** Keeps the chunks of a sinkhole loaded while it is dug; one ticket per sinkhole (its id). */
    private static final TicketType<Integer> TICKET = TicketType.create(Tremor.MODID + "_sinkhole", Integer::compare);
    private static final double BUDGET_MILLIS = 1.5;
    /** A sinkhole whose chunks are not loaded after this many ticks is given up (none is dug). */
    private static final int LOAD_TIMEOUT_TICKS = 200;
    /** Digs a block without updating its neighbours (the clients still get it). */
    private static final int DIG_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    private static final List<Job> JOBS = new ArrayList<>();
    /** Id of the next sinkhole; ids are never reused while the server runs. */
    private static int nextId = 1;

    private Sinkholes() {
    }

    /**
     * Starts a sinkhole under {@code top} (the swallow point: its centre column goes down from there) with the size of
     * the config ({@code awakening.sinkholeRadius} at least 1, {@code awakening.sinkholeDepth}). Once it is dug,
     * {@code done} is told where its bottom is: the middle of the lowest open block of its centre column (null if the
     * centre column has none, or the sinkhole had to be given up). While {@code wanted} says no (asked before any
     * digging, and on every tick of it) the sinkhole is called off: it stays as far as it got, and {@code done} is not
     * told.
     */
    static void dig(ServerLevel level, BlockPos top, BooleanSupplier wanted, Consumer<Vec3> done) {
        TremorConfig.Awakening config = TremorConfig.COMMON.awakening;
        SinkholeShape shape = new SinkholeShape(config.sinkholeRadius.get(), config.sinkholeDepth.get(),
                top.asLong() ^ level.getSeed());
        Job job = new Job(nextId++, level, top.immutable(), shape, wanted, done);
        JOBS.add(job);
        Tremor.LOGGER.info("Sinkhole #{} opens at {} in {} (radius {}, depth {})", job.id, top.toShortString(),
                level.dimension().location(), shape.radius(), shape.depth());
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (JOBS.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        long now = event.getServer().getTickCount();
        for (Job job : List.copyOf(JOBS)) {
            boolean over;
            try {
                over = job.step(start, now);
            } catch (RuntimeException e) {
                Tremor.LOGGER.error("Sinkhole #{} failed; it stays as far as it got", job.id, e);
                over = true;
            }
            if (over) {
                JOBS.remove(job);
                try {
                    job.finish();
                } catch (RuntimeException e) {
                    Tremor.LOGGER.error("Sinkhole #{}: what was to follow it failed", job.id, e);
                }
            }
        }
    }

    /** The levels and their tickets are gone; a sinkhole still being dug stays as far as it got. */
    public static void onServerStopped(ServerStoppedEvent event) {
        JOBS.clear();
    }

    /** One sinkhole being dug. */
    private static final class Job {
        final int id;
        final ServerLevel level;
        final BlockPos top;
        final SinkholeShape shape;
        final List<SinkholeShape.Column> columns;
        final BooleanSupplier wanted;
        final Consumer<Vec3> done;
        final long startTick;
        /** The chunks the sinkhole touches, the blocks it looks at around it included. */
        final RealArea area;
        /** Chebyshev radius of the spawn protection around {@link #spawn}; 0 for none. */
        final int spawnRadius;
        final BlockPos spawn;
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        /** Index of the next column to dig. */
        int next;
        int blocks;
        Vec3 bottom;
        /** No longer wanted: given up without telling {@link #done}. */
        boolean calledOff;

        Job(int id, ServerLevel level, BlockPos top, SinkholeShape shape, BooleanSupplier wanted,
            Consumer<Vec3> done) {
            this.id = id;
            this.level = level;
            this.top = top;
            this.shape = shape;
            this.columns = shape.columns();
            this.wanted = wanted;
            this.done = done;
            this.startTick = level.getServer().getTickCount();
            // The rules look two blocks beyond a rim column: at its sides, and at what touches an open side.
            int reach = shape.reach() + 2;
            area = new RealArea(level, TICKET, id, top.getX() - reach, top.getZ() - reach, top.getX() + reach,
                    top.getZ() + reach);
            // As vanilla has it, in single player too (the radius is 16 there).
            spawnRadius = level.dimension() == Level.OVERWORLD
                    ? Math.max(0, level.getServer().getSpawnProtectionRadius()) : 0;
            spawn = level.getSharedSpawnPos();
        }

        /** Digs within the budget of this tick (begun at {@code start}); true once it is done, given up or called off. */
        boolean step(long start, long now) {
            if (!wanted.getAsBoolean()) {
                calledOff = true;
                return true;
            }
            if (!area.ready()) {
                if (now - startTick > LOAD_TIMEOUT_TICKS) {
                    Tremor.LOGGER.warn("Sinkhole #{}: its chunks did not load in {} ticks, given up", id,
                            LOAD_TIMEOUT_TICKS);
                    return true;
                }
                return false;
            }
            while (next < columns.size()) {
                digColumn(columns.get(next++));
                if (System.nanoTime() - start >= BUDGET_MILLIS * 1e6) {
                    return next == columns.size();
                }
            }
            return true;
        }

        /** Done (or given up): the ticket goes, the ground cracks, and {@code done} is told the bottom. */
        void finish() {
            area.release();
            if (blocks > 0) {
                level.playSound(null, top.getX() + 0.5, top.getY(), top.getZ() + 0.5, TremorSounds.CRACK,
                        SoundSource.HOSTILE, (float) TremorConfig.COMMON.transitionVolume.getAsDouble(), 0.8f);
            }
            if (calledOff) {
                Tremor.LOGGER.info("Sinkhole #{} at {} called off: {} blocks dug", id, top.toShortString(), blocks);
                return;
            }
            Tremor.LOGGER.info(String.format(Locale.ROOT, "Sinkhole #%d at %s: %d blocks dug in %d ticks, bottom %s",
                    id, top.toShortString(), blocks, level.getServer().getTickCount() - startTick,
                    bottom == null ? "none" : String.format(Locale.ROOT, "%.1f %.1f %.1f", bottom.x, bottom.y,
                            bottom.z)));
            done.accept(bottom);
        }

        /**
         * Digs one column; its first block dug crumbles visibly, and a plant beside a dug block that cannot stay
         * without it drops. The centre's lowest open block is the bottom.
         */
        private void digColumn(SinkholeShape.Column column) {
            int x = top.getX() + column.dx();
            int z = top.getZ() + column.dz();
            List<Integer> dug = new ArrayList<>();
            int open = SinkholeRules.digColumn(this::cell, x, z, top.getY(), column.depth(), y -> {
                BlockPos pos = new BlockPos(x, y, z);
                if (dug.isEmpty()) {
                    level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(level.getBlockState(pos)));
                }
                dug.add(y);
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), DIG_FLAGS);
            });
            blocks += dug.size();
            for (int y : dug) {
                for (Direction side : SIDES) {
                    BlockPos pos = new BlockPos(x, y, z).relative(side);
                    BlockState state = level.getBlockState(pos);
                    if (cell(pos.getX(), pos.getY(), pos.getZ()) == SinkholeRules.Cell.PLANT
                            && !state.canSurvive(level, pos)) {
                        Block.dropResources(state, level, pos);
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), DIG_FLAGS);
                    }
                }
            }
            if (column.dx() == 0 && column.dz() == 0 && open <= top.getY()) {
                bottom = new Vec3(x + 0.5, open, z + 0.5);
            }
        }

        private SinkholeRules.Cell cell(int x, int y, int z) {
            cursor.set(x, y, z);
            if (level.isOutsideBuildHeight(y) || !level.getWorldBorder().isWithinBounds(cursor)) {
                return SinkholeRules.Cell.KEEP;
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) {
                return SinkholeRules.Cell.KEEP;
            }
            BlockState state = chunk.getBlockState(cursor);
            if (!state.getFluidState().isEmpty()) {
                return SinkholeRules.Cell.FLUID;
            }
            if (state.isAir()) {
                return SinkholeRules.Cell.AIR;
            }
            if (state.hasBlockEntity() || state.is(TremorTags.PROTECTED) || state.getDestroySpeed(level, cursor) < 0
                    || state.is(Blocks.MAGMA_BLOCK) || spawnProtected(x, z)) {
                return SinkholeRules.Cell.KEEP;
            }
            if (state.getCollisionShape(level, cursor).isEmpty()) {
                return SinkholeRules.Cell.PLANT;
            }
            if (!state.isCollisionShapeFullBlock(level, cursor)) {
                return SinkholeRules.Cell.LOOSE;
            }
            return state.getBlock() instanceof Fallable ? SinkholeRules.Cell.FALLING : SinkholeRules.Cell.SOLID;
        }

        /** Whether the column lies in the spawn protection (a square around the world spawn, as vanilla has it). */
        private boolean spawnProtected(int x, int z) {
            return spawnRadius > 0 && Math.max(Math.abs(x - spawn.getX()), Math.abs(z - spawn.getZ())) <= spawnRadius;
        }
    }
}
