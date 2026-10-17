package tremor.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.level.PistonEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.TickEvent;
import tremor.Tremor;
import tremor.config.TremorConfig;
import tremor.core.behavior.Crowding;
import tremor.core.graph.SurfaceGraph;
import tremor.core.math.Vec3;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps one {@link TremorRuntime} per entity of each server level (several to a level since 1.1.0: one for each part
 * of the world where players are, {@code spawn.spacing} apart; two that come near each other do not stay so,
 * {@link #crowd}), and routes game events to them. Event handlers are registered on the game bus by
 * {@link tremor.Tremor}. Server thread only.
 */
public final class TremorManager {
    /** A piston moves at most 12 blocks plus its head. */
    private static final int PISTON_REACH = 13;
    /** Game ticks between two looks for entities too near each other ({@link #crowd}). */
    private static final int CROWD_INTERVAL = 20;

    private static final Map<ResourceKey<Level>, List<TremorRuntime>> RUNTIMES = new HashMap<>();

    private TremorManager() {
    }

    /** The runtimes of the level's entities (a copy: a tick may remove one), none if it has no entity. */
    public static List<TremorRuntime> runtimes(ServerLevel level) {
        List<TremorRuntime> runtimes = RUNTIMES.get(level.dimension());
        return runtimes == null || runtimes.isEmpty() || runtimes.get(0).level() != level ? List.of()
                : List.copyOf(runtimes);
    }

    /** The runtime whose entity is nearest to {@code x, y, z}, or null if the level has no entity. */
    public static TremorRuntime nearest(ServerLevel level, double x, double y, double z) {
        TremorRuntime nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (TremorRuntime runtime : runtimes(level)) {
            TremorEntity entity = runtime.entity();
            if (entity != null) {
                double d = entity.crawler().position().distance(new Vec3(x, y, z));
                if (d < best) {
                    best = d;
                    nearest = runtime;
                }
            }
        }
        return nearest;
    }

    /** The runtime of the level's entity number {@code instance}, or null if there is no such entity. */
    public static TremorRuntime byInstance(ServerLevel level, int instance) {
        for (TremorRuntime runtime : runtimes(level)) {
            if (runtime.entity() != null && runtime.entity().instance() == instance) {
                return runtime;
            }
        }
        return null;
    }

    /** Horizontal distance from the entity to {@code x, z} (blocks). */
    public static double horizontal(TremorEntity entity, double x, double z) {
        Vec3 at = entity.crawler().position();
        return Math.hypot(at.x() - x, at.z() - z);
    }

    /** {@link #spawn(ServerLevel, BlockPos, boolean)} of an entity that does not despawn by itself (a command's). */
    public static TremorEntity spawn(ServerLevel level, BlockPos requested) {
        return spawn(level, requested, false);
    }

    /** {@link #spawn(ServerLevel, BlockPos, boolean, boolean)} in place of an entity near there. */
    public static TremorEntity spawn(ServerLevel level, BlockPos requested, boolean natural) {
        return spawn(level, requested, natural, true);
    }

    /**
     * Spawns a new entity on the node for {@code requested} ({@link TremorRuntime#snap}). If {@code replace}, it
     * replaces the nearest entity within {@code spawn.spacing} of that place (horizontally), unless an Awakening has
     * taken that one; else it is one more in the level. Returns null (and changes nothing) if there is no node there.
     *
     * @param natural spawned by the world, not by a command: it despawns by itself (SPEC 11)
     * @param replace false for the natural spawn, which never takes the entity of another part of the world (it checked
     *                that there is none near the player)
     */
    public static TremorEntity spawn(ServerLevel level, BlockPos requested, boolean natural, boolean replace) {
        TremorRuntime runtime = null;
        double best = replace ? TremorConfig.COMMON.spawn.spacing.get() : 0;
        for (TremorRuntime near : runtimes(level)) {
            TremorEntity entity = near.entity();
            if (entity != null && !entity.absorbed()) {
                double d = horizontal(entity, requested.getX() + 0.5, requested.getZ() + 0.5);
                if (d < best) {
                    best = d;
                    runtime = near;
                }
            }
        }
        boolean created = runtime == null;
        if (created) {
            runtime = new TremorRuntime(level, TremorSavedData.get(level), null);
        }
        long node = runtime.snap(requested);
        if (node == SurfaceGraph.NO_NODE) {
            return null;
        }
        TremorEntity entity = runtime.spawn(node, natural);
        if (created) {
            RUNTIMES.computeIfAbsent(level.dimension(), key -> new ArrayList<>()).add(runtime);
        }
        return entity;
    }

    /** Removes the entity of {@code runtime}; false if it had none. */
    public static boolean despawn(TremorRuntime runtime) {
        List<TremorRuntime> runtimes = RUNTIMES.get(runtime.level().dimension());
        if (runtimes != null) {
            runtimes.remove(runtime);
        }
        return runtime.despawn();
    }

    /** Removes every entity of the level; returns how many there were. */
    public static int despawnAll(ServerLevel level) {
        int removed = 0;
        for (TremorRuntime runtime : runtimes(level)) {
            removed += despawn(runtime) ? 1 : 0;
        }
        return removed;
    }

    /**
     * An Awakening at {@code x, z} ended: its entity, number {@code instance}, goes deep (SPEC 9: "сущность уходит
     * глубоко, долгий кулдаун"), removed as by {@link #despawn} if it is still there (0: the Awakening had none), and
     * natural spawns within {@code spawn.spacing} of the place wait {@code awakening.cooldownSeconds} from now
     * ({@link TremorSavedData#awakeningEnded}). Returns whether the entity was there.
     */
    public static boolean goDeep(ServerLevel level, int instance, double x, double z) {
        TremorRuntime runtime = instance == 0 ? null : byInstance(level, instance);
        boolean removed = runtime != null && despawn(runtime);
        TremorSavedData.get(level).awakeningEnded(x, z, level.getGameTime());
        return removed;
    }

    /** Sends every entity of the player's level (shape and state) to the player. */
    public static void sync(ServerPlayer player) {
        for (TremorRuntime runtime : runtimes(player.serverLevel())) {
            runtime.sendTo(player);
        }
    }

    // ---- events ----

    /**
     * Takes up the saved entities. One saved while an Awakening had taken it ({@link TremorEntity#absorbed}: the
     * server crashed or was killed during an Awakening, after an autosave) goes deep at once (as at the end of an
     * Awakening, see {@link #goDeep}): Awakenings are not saved (SPEC 9), so the one it was in is gone. One saved in
     * AWAKENING without that was seeking a player; the seeking is not saved either, and it goes on as HUNTING at the
     * calm-down anger ({@link TremorMind}).
     */
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            TremorSavedData data = TremorSavedData.get(level);
            List<TremorRuntime> runtimes = new ArrayList<>();
            long now = level.getGameTime();
            for (TremorEntity entity : List.copyOf(data.entities())) {
                if (entity.absorbed()) {
                    Vec3 at = entity.crawler().position();
                    data.remove(entity, now);
                    data.awakeningEnded(at.x(), at.z(), now);
                    Tremor.LOGGER.info("Tremor #{} in {} was saved in an Awakening: it goes deep", entity.instance(),
                            level.dimension().location());
                } else {
                    runtimes.add(new TremorRuntime(level, data, entity));
                }
            }
            if (runtimes.isEmpty()) {
                RUNTIMES.remove(level.dimension());
            } else {
                RUNTIMES.put(level.dimension(), runtimes);
            }
        }
    }

    /** Game bus: a player broke a block; an entity lying in wait there is enraged ({@link TremorRuntime#groundBroken}). */
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getPlayer() instanceof ServerPlayer player) {
            for (TremorRuntime runtime : runtimes(level)) {
                runtime.groundBroken(event.getPos(), player);
            }
        }
    }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            List<TremorRuntime> runtimes = RUNTIMES.get(level.dimension());
            if (runtimes != null && (runtimes.isEmpty() || runtimes.get(0).level() == level)) {
                RUNTIMES.remove(level.dimension());
            }
        }
    }

    /** Not while the game is frozen ({@code /tick freeze}; {@code /tick step} still advances it). */
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!RUNTIMES.isEmpty() && event.level instanceof ServerLevel level) {
            for (TremorRuntime runtime : runtimes(level)) {
                runtime.tick();
            }
            if (level.getGameTime() % CROWD_INTERVAL == 0) {
                crowd(runtimes(level));
            }
        }
    }

    /**
     * Entities do not stay near each other: of two within {@code spawn.crowdDistance} of each other (horizontally),
     * one gives way, turning away and sinking into the ground ({@link Crowding}, {@link TremorRuntime#yieldTo}). Held
     * by something else, one stays: an Awakening took it, a frenzy drives it, its brain is off or it follows a
     * {@code goto}.
     */
    private static void crowd(List<TremorRuntime> runtimes) {
        if (runtimes.size() < 2) {
            return;
        }
        List<Crowding.Entity> entities = new ArrayList<>();
        Map<Integer, TremorRuntime> byInstance = new HashMap<>();
        for (TremorRuntime runtime : runtimes) {
            TremorEntity entity = runtime.entity();
            if (entity != null) {
                Vec3 at = entity.crawler().position();
                boolean held = entity.absorbed() || entity.frenzy() || !entity.aiEnabled()
                        || entity.targetKind() == TremorEntity.TargetKind.MANUAL;
                entities.add(new Crowding.Entity(entity.instance(), at.x(), at.z(), entity.anger(), entity.natural(),
                        held, entity.leaving()));
                byInstance.put(entity.instance(), runtime);
            }
        }
        for (Crowding.Yield yield : Crowding.yields(entities, TremorConfig.COMMON.spawn.crowdDistance.get())) {
            byInstance.get(yield.instance()).yieldTo(byInstance.get(yield.to()).entity());
        }
    }

    /** Every block update in the level; registered to see cancelled ones too (the block has changed anyway). */
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!RUNTIMES.isEmpty() && event.getLevel() instanceof ServerLevel level) {
            for (TremorRuntime runtime : runtimes(level)) {
                runtime.blockChanged(event.getPos());
            }
        }
    }

    /** No block event reports the terrain of a chunk that loads; the runtimes read what they cached of it again. */
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!RUNTIMES.isEmpty() && event.getLevel() instanceof ServerLevel level) {
            ChunkPos pos = event.getChunk().getPos();
            for (TremorRuntime runtime : runtimes(level)) {
                runtime.chunkLoaded(pos.x, pos.z);
            }
        }
    }

    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!RUNTIMES.isEmpty() && event.getLevel() instanceof ServerLevel level) {
            for (TremorRuntime runtime : runtimes(level)) {
                for (BlockPos pos : event.getAffectedBlocks()) {
                    runtime.blockChanged(pos);
                }
            }
        }
    }

    /** The blocks in front of the piston, as far as it can push. */
    public static void onPistonMoved(PistonEvent.Post event) {
        if (!RUNTIMES.isEmpty() && event.getLevel() instanceof ServerLevel level) {
            Direction direction = event.getDirection();
            for (TremorRuntime runtime : runtimes(level)) {
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
        RUNTIMES.values().forEach(runtimes -> runtimes.forEach(TremorRuntime::markDirty));
        RUNTIMES.clear();
    }
}
