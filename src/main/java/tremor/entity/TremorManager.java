package tremor.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import tremor.Tremor;
import tremor.core.graph.SurfaceGraph;
import tremor.network.TremorStatePayload;

import java.util.HashMap;
import java.util.Map;

/**
 * Keeps one {@link TremorRuntime} per server level that has an entity, and routes game events to it. Event handlers
 * are registered on the game bus by {@link tremor.Tremor}. Server thread only.
 */
public final class TremorManager {
    /** A piston moves at most 12 blocks plus its head. */
    private static final int PISTON_REACH = 13;

    private static final Map<ResourceKey<Level>, TremorRuntime> RUNTIMES = new HashMap<>();

    private TremorManager() {
    }

    /** The runtime of the level, or null if it has no entity. */
    public static TremorRuntime runtime(ServerLevel level) {
        TremorRuntime runtime = RUNTIMES.get(level.dimension());
        return runtime != null && runtime.level() == level ? runtime : null;
    }

    /** {@link #spawn(ServerLevel, BlockPos, boolean)} of an entity that does not despawn by itself (a command's). */
    public static TremorEntity spawn(ServerLevel level, BlockPos requested) {
        return spawn(level, requested, false);
    }

    /**
     * Spawns a new entity on the node for {@code requested} ({@link TremorRuntime#snap}), replacing the level's
     * entity if there is one. Returns null (and changes nothing) if there is no node there.
     *
     * @param natural spawned by the world, not by a command: it despawns by itself (SPEC 11)
     */
    public static TremorEntity spawn(ServerLevel level, BlockPos requested, boolean natural) {
        TremorRuntime runtime = runtime(level);
        boolean created = runtime == null;
        if (created) {
            runtime = new TremorRuntime(level, TremorSavedData.get(level));
        }
        long node = runtime.snap(requested);
        if (node == SurfaceGraph.NO_NODE) {
            return null;
        }
        TremorEntity entity = runtime.spawn(node, natural);
        if (created) {
            RUNTIMES.put(level.dimension(), runtime);
        }
        return entity;
    }

    /** Removes the level's entity; false if there was none. */
    public static boolean despawn(ServerLevel level) {
        TremorRuntime runtime = runtime(level);
        if (runtime == null) {
            return false;
        }
        RUNTIMES.remove(level.dimension());
        return runtime.despawn();
    }

    /**
     * The level's entity goes deep at the end of an Awakening (SPEC 9: "сущность уходит глубоко, долгий кулдаун"):
     * removed as by {@link #despawn} if there is one, and natural spawns of the dimension wait
     * {@code awakening.cooldownSeconds} from now ({@link TremorSavedData#awakeningEnded}). Returns whether there was
     * an entity.
     */
    public static boolean goDeep(ServerLevel level) {
        boolean removed = despawn(level);
        TremorSavedData.get(level).awakeningEnded(level.getGameTime());
        return removed;
    }

    /** Sends the entity of the player's level (shape and state), or that there is none. */
    public static void sync(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        TremorRuntime runtime = runtime(level);
        if (runtime != null) {
            runtime.sendTo(player);
        } else {
            PacketDistributor.sendToPlayer(player, TremorStatePayload.absent(TremorSavedData.get(level).lastInstance(),
                    level.getGameTime()));
        }
    }

    // ---- events ----

    /**
     * Takes up the saved entity. One saved while an Awakening had taken it ({@link TremorEntity#absorbed}: the server
     * crashed or was killed during an Awakening, after an autosave) goes deep at once (as at the end of an Awakening,
     * see {@link #goDeep}): Awakenings are not saved (SPEC 9), so the one it was in is gone. One saved in AWAKENING
     * without that was seeking a player; the seeking is not saved either, and it goes on as HUNTING at the calm-down
     * anger ({@link TremorMind}).
     */
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            TremorSavedData data = TremorSavedData.get(level);
            TremorEntity entity = data.entity();
            if (entity != null && entity.absorbed()) {
                long now = level.getGameTime();
                data.remove(now);
                data.awakeningEnded(now);
                Tremor.LOGGER.info("Tremor #{} in {} was saved in an Awakening: it goes deep", entity.instance(),
                        level.dimension().location());
            } else if (entity != null) {
                RUNTIMES.put(level.dimension(), new TremorRuntime(level, data));
            }
        }
    }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && runtime(level) != null) {
            RUNTIMES.remove(level.dimension());
        }
    }

    /** Not while the game is frozen ({@code /tick freeze}; {@code /tick step} still advances it). */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!RUNTIMES.isEmpty() && event.getLevel() instanceof ServerLevel level) {
            TremorRuntime runtime = runtime(level);
            if (runtime != null && level.tickRateManager().runsNormally()) {
                runtime.tick();
            }
        }
    }

    /** Every block update in the level; registered to see cancelled ones too (the block has changed anyway). */
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!RUNTIMES.isEmpty() && event.getLevel() instanceof ServerLevel level) {
            TremorRuntime runtime = runtime(level);
            if (runtime != null) {
                runtime.blockChanged(event.getPos());
            }
        }
    }

    /** No block event reports the terrain of a chunk that loads; the runtime reads what it cached of it again. */
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!RUNTIMES.isEmpty() && event.getLevel() instanceof ServerLevel level) {
            TremorRuntime runtime = runtime(level);
            if (runtime != null) {
                ChunkPos pos = event.getChunk().getPos();
                runtime.chunkLoaded(pos.x, pos.z);
            }
        }
    }

    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!RUNTIMES.isEmpty() && event.getLevel() instanceof ServerLevel level) {
            TremorRuntime runtime = runtime(level);
            if (runtime != null) {
                for (BlockPos pos : event.getAffectedBlocks()) {
                    runtime.blockChanged(pos);
                }
            }
        }
    }

    /** The blocks in front of the piston, as far as it can push. */
    public static void onPistonMoved(PistonEvent.Post event) {
        if (!RUNTIMES.isEmpty() && event.getLevel() instanceof ServerLevel level) {
            TremorRuntime runtime = runtime(level);
            if (runtime != null) {
                Direction direction = event.getDirection();
                BlockPos.MutableBlockPos pos = event.getPos().mutable();
                for (int i = 0; i <= PISTON_REACH; i++) {
                    runtime.blockChanged(pos);
                    pos.move(direction);
                }
            }
        }
    }

    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player);
        }
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player);
        }
    }

    /** Respawning (death, leaving the End) can switch dimension without a PlayerChangedDimensionEvent. */
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            sync(player);
        }
    }

    /** Fired before the levels are saved for the last time: the latest positions get written. */
    public static void onServerStopping(ServerStoppingEvent event) {
        RUNTIMES.values().forEach(TremorRuntime::markDirty);
        RUNTIMES.clear();
    }
}
