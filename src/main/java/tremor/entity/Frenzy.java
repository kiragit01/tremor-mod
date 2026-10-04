package tremor.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import tremor.Tremor;
import tremor.awakening.AwakeningManager;
import tremor.config.TremorConfig;
import tremor.core.behavior.Stage;
import tremor.core.math.Vec3;
import tremor.core.graph.SurfaceGraph;
import tremor.hollow.HollowDimension;
import tremor.item.TremorItems;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The frenzy over a dropped shard (the user's wish: a shard thrown on the ground drives the entity mad). A player
 * tosses a {@code tremor:tremor_shard}: the level's entity (or one that rises some {@value #SPAWN_DISTANCE} blocks
 * away if there is none) roars and rushes to the shard, much faster and taller than when HUNTING
 * ({@code items.frenzySpeedFactor}, {@code frenzyAmplitudeFactor}, {@link TremorEntity#frenzy}), its brain idle; it
 * takes the shard in, then rushes on to the player who dropped it, following them; reaching them starts an Awakening
 * with a build-up too short to run from ({@link AwakeningManager#frenzy}). A frenzy that has not reached the player
 * in {@code items.frenzySeconds}, or whose player died, left or went into the hollow, dies down: the entity hunts on
 * as usual. One frenzy per level; not saved (after a restart the entity is itself again). Server thread only;
 * registered by {@link Tremor}.
 */
public final class Frenzy {
    /** How far from the player an entity rises when there is none in the level (blocks). */
    static final int SPAWN_DISTANCE = 24;
    /** The shard is taken in once the bump is this near it (blocks). */
    private static final double TAKE_DISTANCE = 2.5;
    /** Ticks between two new routes after a moving target. */
    private static final int RETARGET_TICKS = 10;

    private enum Goal { SHARD, PLAYER }

    private static final class State {
        ItemEntity shard;
        UUID player;
        Goal goal = Goal.SHARD;
        long started;
        long lastRoute = -1_000_000;
        long routedTo = SurfaceGraph.NO_NODE;
    }

    private static final Map<ServerLevel, State> FRENZIES = new HashMap<>();

    private Frenzy() {
    }

    /** A player threw a shard on the ground: the frenzy starts (unless one runs in the level already). */
    public static void onItemToss(ItemTossEvent event) {
        ItemEntity item = event.getEntity();
        if (!(item.level() instanceof ServerLevel level) || !(event.getPlayer() instanceof ServerPlayer player)
                || !item.getItem().is(TremorItems.SHARD.get()) || HollowDimension.is(level)
                || FRENZIES.containsKey(level) || player.isSpectator()) {
            return;
        }
        TremorRuntime runtime = TremorManager.runtime(level);
        TremorEntity entity = runtime == null ? null : runtime.entity();
        if (entity == null || entity.absorbed() || entity.leaving()) {
            entity = rise(level, player);
            runtime = TremorManager.runtime(level);
        }
        if (entity == null || runtime == null) {
            return;
        }
        State state = new State();
        state.shard = item;
        state.player = player.getUUID();
        state.started = level.getGameTime();
        FRENZIES.put(level, state);
        entity.setFrenzy(true);
        if (entity.stage().ordinal() < Stage.HUNTING.ordinal()) {
            runtime.forceStage(Stage.HUNTING);
        }
        Vec3 at = entity.crawler().position();
        level.playSound(null, at.x(), at.y(), at.z(), tremor.sound.TremorSounds.AWAKEN.get(), SoundSource.HOSTILE,
                (float) TremorConfig.COMMON.transitionVolume.getAsDouble(), 1.3F);
        Tremor.LOGGER.info("Tremor #{}: a frenzy over the shard {} dropped", entity.instance(),
                player.getGameProfile().getName());
    }

    /** Leads the frenzy of every level on: the route after the shard or the player, the take, the end. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        State state = FRENZIES.get(level);
        if (state == null) {
            return;
        }
        TremorRuntime runtime = TremorManager.runtime(level);
        TremorEntity entity = runtime == null ? null : runtime.entity();
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(state.player);
        long now = level.getGameTime();
        if (entity == null || entity.absorbed() || entity.leaving()) {
            end(level, entity, "the entity is gone");
            return;
        }
        if (player == null || !player.isAlive() || player.level() != level || player.isSpectator()) {
            end(level, entity, "its player is gone");
            return;
        }
        if (now - state.started > TremorConfig.COMMON.frenzySeconds.get() * 20L) {
            end(level, entity, "it died down");
            return;
        }
        if (entity.stage().ordinal() < Stage.HUNTING.ordinal()) {
            runtime.forceStage(Stage.HUNTING);
        }
        Vec3 at = entity.crawler().position();
        if (state.goal == Goal.SHARD) {
            ItemEntity shard = state.shard;
            if (shard == null || !shard.isAlive()) {
                state.goal = Goal.PLAYER; // picked up again, or gone: it goes for the player all the same
            } else if (horizontal(at, shard.getX(), shard.getZ()) <= TAKE_DISTANCE
                    && Math.abs(at.y() - shard.getY()) <= 3) {
                takeIn(level, shard);
                state.goal = Goal.PLAYER;
                state.lastRoute = -1_000_000;
            } else {
                route(runtime, state, now, shard.blockPosition());
                return;
            }
        }
        double reach = TremorConfig.COMMON.awakening.reachDistance.get();
        if (horizontal(at, player.getX(), player.getZ()) <= reach && Math.abs(at.y() - player.getY()) <= 5) {
            entity.setFrenzy(false);
            FRENZIES.remove(level);
            if (!AwakeningManager.frenzy(player)) {
                Tremor.LOGGER.info("Tremor #{}: the frenzy reached {}, but no Awakening could start",
                        entity.instance(), player.getGameProfile().getName());
            }
            return;
        }
        route(runtime, state, now, player.blockPosition().below());
    }

    /** Sends the bump after {@code target}, at most every {@value #RETARGET_TICKS} ticks and when it moved. */
    private static void route(TremorRuntime runtime, State state, long now, BlockPos target) {
        if (now - state.lastRoute < RETARGET_TICKS) {
            return;
        }
        long node = runtime.snap(target);
        if (node == SurfaceGraph.NO_NODE || node == state.routedTo && now - state.lastRoute < 4L * RETARGET_TICKS) {
            return;
        }
        state.lastRoute = now;
        state.routedTo = node;
        runtime.moveTo(node, null);
    }

    /** The bump takes the shard in: it is gone, with a crack and the dust of the ground. */
    private static void takeIn(ServerLevel level, ItemEntity shard) {
        BlockState ground = level.getBlockState(shard.blockPosition().below());
        if (!ground.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), shard.getX(), shard.getY() + 0.2,
                    shard.getZ(), 30, 0.4, 0.2, 0.4, 0.1);
        }
        level.playSound(null, shard.getX(), shard.getY(), shard.getZ(), tremor.sound.TremorSounds.CRACK.get(),
                SoundSource.HOSTILE, 1.5F, 0.6F);
        shard.discard();
    }

    /** An entity rises some way off the player, to answer the shard; null if there is no ground for one. */
    private static TremorEntity rise(ServerLevel level, ServerPlayer player) {
        double yaw = level.getRandom().nextDouble() * Math.PI * 2;
        for (int attempt = 0; attempt < 8; attempt++) {
            double a = yaw + attempt * Math.PI / 4;
            BlockPos at = BlockPos.containing(player.getX() + SPAWN_DISTANCE * Math.cos(a), player.getY(),
                    player.getZ() + SPAWN_DISTANCE * Math.sin(a));
            TremorEntity entity = TremorManager.spawn(level, at, false);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    private static void end(ServerLevel level, TremorEntity entity, String why) {
        FRENZIES.remove(level);
        if (entity != null) {
            entity.setFrenzy(false);
            Tremor.LOGGER.info("Tremor #{}: the frenzy ends ({})", entity.instance(), why);
        }
    }

    private static double horizontal(Vec3 at, double x, double z) {
        return Math.hypot(at.x() - x, at.z() - z);
    }
}
