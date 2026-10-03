package tremor.awakening;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
import java.util.function.Consumer;

/**
 * Digs the sinkholes of defeats into the real world (SPEC 9 "Поражение", 12: the one lasting change the Awakening makes
 * there): the bowl of a {@link SinkholeShape}, dug column by column as {@link SinkholeRules} allows, the centre first.
 * Each sinkhole holds a ticket on the chunks it touches (and the blocks around it) until it is done, and waits for
 * them to load; the digging takes at most {@value #BUDGET_MILLIS} ms of server time per tick for all of them, at least
 * a column each (SPEC 16). Event handlers are registered by {@link tremor.Tremor}; server thread only.
 * <p>
 * What is kept ({@link SinkholeRules.Cell#KEEP}): blocks with a block entity, of {@code #tremor:protected} or that
 * cannot be broken (a destroy speed below 0), and every block of a dedicated server's spawn protection (the overworld
 * around the world spawn, {@code spawn-protection} of server.properties) whoever the player is. Dug blocks drop
 * nothing; their neighbours are updated as after any block change. Not saved: a server that stops in the middle (a
 * tick or two of digging) leaves the sinkhole as far as it got.
 */
public final class Sinkholes {
    /** Keeps the chunks of a sinkhole loaded while it is dug; one ticket per sinkhole (its id). */
    private static final TicketType<Integer> TICKET = TicketType.create(Tremor.MODID + "_sinkhole", Integer::compare);
    private static final double BUDGET_MILLIS = 1.5;
    /** A sinkhole whose chunks are not loaded after this many ticks is given up (none is dug). */
    private static final int LOAD_TIMEOUT_TICKS = 200;

    private static final List<Job> JOBS = new ArrayList<>();
    /** Id of the next sinkhole; ids are never reused while the server runs. */
    private static int nextId = 1;

    private Sinkholes() {
    }

    /**
     * Starts a sinkhole under {@code top} (the swallow point: its centre column goes down from there) with the size of
     * the config ({@code awakening.sinkholeRadius} at least 1, {@code awakening.sinkholeDepth}). Once it is dug,
     * {@code done} is told where its bottom is: the middle of the lowest open block of its centre column (null if the
     * centre column has none, or the sinkhole had to be given up).
     */
    static void dig(ServerLevel level, BlockPos top, Consumer<Vec3> done) {
        TremorConfig.Awakening config = TremorConfig.COMMON.awakening;
        SinkholeShape shape = new SinkholeShape(config.sinkholeRadius.get(), config.sinkholeDepth.get(),
                top.asLong() ^ level.getSeed());
        Job job = new Job(nextId++, level, top.immutable(), shape, done);
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
        final Consumer<Vec3> done;
        final long startTick;
        /** The chunks the sinkhole touches, its blocks' neighbours included. */
        final int minChunkX, maxChunkX, minChunkZ, maxChunkZ;
        final ChunkPos ticketChunk;
        final int ticketDistance;
        /** Chebyshev radius of the spawn protection around {@link #spawn}; 0 for none. */
        final int spawnRadius;
        final BlockPos spawn;
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        /** Index of the next column to dig. */
        int next;
        int blocks;
        Vec3 bottom;
        boolean ticketHeld;

        Job(int id, ServerLevel level, BlockPos top, SinkholeShape shape, Consumer<Vec3> done) {
            this.id = id;
            this.level = level;
            this.top = top;
            this.shape = shape;
            this.columns = shape.columns();
            this.done = done;
            this.startTick = level.getServer().getTickCount();
            int reach = shape.reach() + 1;
            minChunkX = (top.getX() - reach) >> 4;
            maxChunkX = (top.getX() + reach) >> 4;
            minChunkZ = (top.getZ() - reach) >> 4;
            maxChunkZ = (top.getZ() + reach) >> 4;
            ticketChunk = new ChunkPos(top);
            ticketDistance = Math.max(Math.max(ticketChunk.x - minChunkX, maxChunkX - ticketChunk.x),
                    Math.max(ticketChunk.z - minChunkZ, maxChunkZ - ticketChunk.z));
            MinecraftServer server = level.getServer();
            spawnRadius = server.isDedicatedServer() && level.dimension() == Level.OVERWORLD
                    ? Math.max(0, server.getSpawnProtectionRadius()) : 0;
            spawn = level.getSharedSpawnPos();
        }

        /** Digs within the budget of this tick (begun at {@code start}); true once it is done or given up. */
        boolean step(long start, long now) {
            if (!ticketHeld) {
                level.getChunkSource().addRegionTicket(TICKET, ticketChunk, ticketDistance, id);
                ticketHeld = true;
            }
            if (!loaded()) {
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
            if (ticketHeld) {
                level.getChunkSource().removeRegionTicket(TICKET, ticketChunk, ticketDistance, id);
                ticketHeld = false;
            }
            if (blocks > 0) {
                level.playSound(null, top.getX() + 0.5, top.getY(), top.getZ() + 0.5, TremorSounds.CRACK,
                        SoundSource.HOSTILE, (float) TremorConfig.COMMON.transitionVolume.getAsDouble(), 0.8f);
            }
            Tremor.LOGGER.info(String.format(Locale.ROOT, "Sinkhole #%d at %s: %d blocks dug in %d ticks, bottom %s",
                    id, top.toShortString(), blocks, level.getServer().getTickCount() - startTick,
                    bottom == null ? "none" : String.format(Locale.ROOT, "%.1f %.1f %.1f", bottom.x, bottom.y,
                            bottom.z)));
            done.accept(bottom);
        }

        /** Digs one column; its first block dug crumbles visibly. The centre's lowest open block is the bottom. */
        private void digColumn(SinkholeShape.Column column) {
            int x = top.getX() + column.dx();
            int z = top.getZ() + column.dz();
            int[] dugHere = {0};
            int open = SinkholeRules.digColumn(this::cell, x, z, top.getY(), column.depth(), y -> {
                BlockPos pos = new BlockPos(x, y, z);
                if (dugHere[0]++ == 0) {
                    level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(level.getBlockState(pos)));
                }
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            });
            blocks += dugHere[0];
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
                    || spawnProtected(x, z)) {
                return SinkholeRules.Cell.KEEP;
            }
            return SinkholeRules.Cell.SOLID;
        }

        /** Whether the column lies in the spawn protection (a square around the world spawn, as vanilla has it). */
        private boolean spawnProtected(int x, int z) {
            return spawnRadius > 0 && Math.max(Math.abs(x - spawn.getX()), Math.abs(z - spawn.getZ())) <= spawnRadius;
        }

        /** Whether every chunk the sinkhole touches is loaded, entities included (the things land on its bottom). */
        private boolean loaded() {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                for (int x = minChunkX; x <= maxChunkX; x++) {
                    LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                    if (chunk == null || !chunk.getFullStatus().isOrAfter(FullChunkStatus.FULL)
                            || !level.areEntitiesLoaded(ChunkPos.asLong(x, z))) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
