package tremor.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.TickEvent;
import tremor.Tremor;
import tremor.awakening.AwakeningManager;
import tremor.config.Difficulty;
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
 * tosses a {@code tremor:tremor_shard}: the ground takes it at once (the user's wish, 1.2.2: it could be picked up
 * again before the entity came): nobody can pick it up, and where it lands a little mound swallows it ({@link
 * #SWALLOW_TICKS}; the clients draw the mound over the shard the server marks with no gravity,
 * {@code tremor.client.ShardMounds}); it is gone for good. And the entity of that part of the world (the nearest within
 * {@code spawn.spacing}; or one that rises some {@value #SPAWN_DISTANCE} blocks away if there is none and the
 * dimension has fewer than {@code spawn.maxEntities}, else the nearest from however far; none if an Awakening has
 * taken the one near) roars and rushes to the player who dropped it, much faster and taller than when HUNTING
 * ({@code items.frenzySpeedFactor}, {@code frenzyAmplitudeFactor}, {@link TremorEntity#frenzy}), its brain idle,
 * following them; reaching them starts an Awakening
 * with a build-up too short to run from ({@link AwakeningManager#frenzy}). A frenzy that has not reached the player
 * in {@code items.frenzySeconds}, or whose player died, left or went into the hollow, dies down: the entity hunts on
 * as usual. One frenzy per level; not saved (after a restart the entity is itself again). Server thread only;
 * registered by {@link Tremor}.
 */
public final class Frenzy {
    /** How far from the player an entity rises when there is none in the level (blocks). */
    static final int SPAWN_DISTANCE = 24;
    /** The ground swallows a thrown shard this long after it lands (ticks), under a little mound. */
    static final int SWALLOW_TICKS = 30;
    /** A shard still in the air after this long (ticks) is swallowed where it is. */
    private static final int FLIGHT_TICKS = 60;
    /** Ticks between two new routes after a moving target. */
    private static final int RETARGET_TICKS = 10;

    private static final class State {
        TremorRuntime runtime;
        UUID player;
        long started;
        long lastRoute = -1_000_000;
        long routedTo = SurfaceGraph.NO_NODE;
    }

    private static final Map<ServerLevel, State> FRENZIES = new HashMap<>();

    /** A thrown shard the ground takes: when it was thrown, and when it landed (or -1 while in the air). */
    private static final class Swallow {
        final ItemEntity shard;
        final long thrown;
        long landed = -1;

        Swallow(ItemEntity shard, long thrown) {
            this.shard = shard;
            this.thrown = thrown;
        }
    }

    private static final Map<ServerLevel, java.util.List<Swallow>> SWALLOWS = new HashMap<>();

    private Frenzy() {
    }

    /** A player threw a shard on the ground: the frenzy starts (unless one runs in the level already). */
    public static void onItemToss(ItemTossEvent event) {
        ItemEntity item = event.getEntity();
        if (!(item.level() instanceof ServerLevel level) || !(event.getPlayer() instanceof ServerPlayer player)
                || !item.getItem().is(TremorItems.SHARD.get()) || HollowDimension.is(level) || player.isSpectator()) {
            return;
        }
        // The ground takes it, whatever comes of the frenzy: nobody gets it back.
        item.setNeverPickUp();
        SWALLOWS.computeIfAbsent(level, l -> new java.util.ArrayList<>()).add(new Swallow(item, level.getGameTime()));
        if (FRENZIES.containsKey(level)) {
            return; // one frenzy at a time
        }
        TremorRuntime runtime = TremorManager.nearest(level, player.getX(), player.getY(), player.getZ());
        TremorEntity entity = runtime == null ? null : runtime.entity();
        boolean near = entity != null && TremorManager.horizontal(entity, player.getX(), player.getZ())
                <= TremorConfig.COMMON.spawn.spacing.get();
        if (near && entity.absorbed()) {
            return; // an Awakening has this part of the world already
        }
        if (entity == null || entity.leaving() || !near) {
            // One leaving near is replaced by the risen one; else a new one only below the cap (it counts, and it
            // leaves by itself as a natural one does), and at the cap the nearest comes from however far.
            if (near || TremorManager.runtimes(level).size()
                    < Difficulty.current().maxTremors(TremorConfig.COMMON.spawn.maxEntities.get())) {
                entity = rise(level, player);
                runtime = entity == null ? null : TremorManager.byInstance(level, entity.instance());
            } else if (entity == null || entity.absorbed() || entity.leaving()) {
                return;
            }
        }
        if (entity == null || runtime == null) {
            return;
        }
        State state = new State();
        state.runtime = runtime;
        state.player = player.getUUID();
        state.started = level.getGameTime();
        FRENZIES.put(level, state);
        entity.setFrenzy(true);
        if (entity.stage().ordinal() < Stage.HUNTING.ordinal()) {
            runtime.forceStage(Stage.HUNTING);
        }
        Vec3 at = entity.crawler().position();
        level.playSound(null, at.x(), at.y(), at.z(), tremor.sound.TremorSounds.AWAKEN.get(), SoundSource.HOSTILE,
                TremorConfig.COMMON.transitionVolume.get().floatValue(), 1.3F);
        Tremor.LOGGER.info("Tremor #{}: a frenzy over the shard {} dropped", entity.instance(),
                player.getGameProfile().getName());
    }

    /** Leads the frenzy of every level on: the route after the player, the end; and the ground takes the shards. */
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.level instanceof ServerLevel level)) {
            return;
        }
        swallow(level);
        State state = FRENZIES.get(level);
        if (state == null) {
            return;
        }
        TremorRuntime runtime = state.runtime;
        TremorEntity entity = runtime.entity();
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
        double reach = TremorConfig.COMMON.awakening.reachDistance.get();
        if (horizontal(at, player.getX(), player.getZ()) <= reach && Math.abs(at.y() - player.getY()) <= 5) {
            entity.setFrenzy(false);
            FRENZIES.remove(level);
            if (!AwakeningManager.frenzy(player, runtime)) {
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

    /**
     * The ground takes the thrown shards of the level: one that has landed (or flown long enough) is marked with no
     * gravity, which the clients draw a little mound over, and {@link #SWALLOW_TICKS} later it is gone.
     */
    private static void swallow(ServerLevel level) {
        java.util.List<Swallow> swallows = SWALLOWS.get(level);
        if (swallows == null) {
            return;
        }
        long now = level.getGameTime();
        for (java.util.Iterator<Swallow> it = swallows.iterator(); it.hasNext(); ) {
            Swallow s = it.next();
            ItemEntity shard = s.shard;
            if (!shard.isAlive()) {
                it.remove();
            } else if (s.landed < 0) {
                if (shard.onGround() || now - s.thrown >= FLIGHT_TICKS) {
                    s.landed = now;
                    shard.setNoGravity(true);
                    shard.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                }
            } else if (now - s.landed >= SWALLOW_TICKS) {
                takeIn(level, shard);
                it.remove();
            }
        }
        if (swallows.isEmpty()) {
            SWALLOWS.remove(level);
        }
    }

    /** The ground takes the shard in: it is gone, with a crack and the dust of the ground. */
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

    /**
     * An entity rises some way off the player, to answer the shard (in place of one leaving near there); it spawned
     * naturally as far as leaving goes: it despawns by itself. Null if there is no ground for one.
     */
    private static TremorEntity rise(ServerLevel level, ServerPlayer player) {
        double yaw = level.getRandom().nextDouble() * Math.PI * 2;
        for (int attempt = 0; attempt < 8; attempt++) {
            double a = yaw + attempt * Math.PI / 4;
            BlockPos at = BlockPos.containing(player.getX() + SPAWN_DISTANCE * Math.cos(a), player.getY(),
                    player.getZ() + SPAWN_DISTANCE * Math.sin(a));
            TremorEntity entity = TremorManager.spawn(level, at, true);
            if (entity != null) {
                return entity;
            }
        }
        return null;
    }

    /** Forgets the frenzies and the shards the ground was taking: their levels are gone. */
    public static void onServerStopped(net.minecraftforge.event.server.ServerStoppedEvent event) {
        FRENZIES.clear();
        SWALLOWS.clear();
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
