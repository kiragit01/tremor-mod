package tremor.awakening;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import tremor.Tremor;
import tremor.config.TremorConfig;
import tremor.core.behavior.Stage;
import tremor.core.math.Vec3;
import tremor.entity.TremorEntity;
import tremor.entity.TremorManager;
import tremor.entity.TremorRuntime;
import tremor.hearing.Perception;
import tremor.hearing.Vibration;
import tremor.hollow.HollowDimension;
import tremor.hollow.HollowEvent;
import tremor.hollow.HollowManager;
import tremor.network.TremorAwakeningPayload;
import tremor.spawn.NaturalSpawner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Runs the Awakenings (SPEC 9; stage 4b: the real-world part): at most one per level, each an {@link Awakening}.
 * Event handlers are registered by {@link tremor.Tremor}; server thread only.
 * <ul>
 *   <li><b>Start by itself</b> (SPEC 8 AWAKENING): once the level's entity is in AWAKENING (and not leaving) with no
 *   Awakening running, it takes a player ({@link AwakeningRules#chooseTarget}): the one it heard last
 *   ({@link #vibration}), if alive in survival or adventure mode, in no event of the hollow and within the hearing
 *   distance of it; else the nearest such player. With nobody to take, nothing starts: the entity hunts on (its
 *   AWAKENING behaves as HUNTING) and it is tried again every tick.</li>
 *   <li><b>By command</b> ({@link #start(ServerPlayer)}, {@code /tremor awaken [player]}, SPEC 14.1): for any living
 *   player who is not a spectator, with or without an entity; {@code /tremor awaken stop} ({@link #stop}) calls it
 *   off and gets a swallowed target out of the hollow again.</li>
 *   <li><b>The entity</b>: while an Awakening runs in its level, the level's entity (also one spawned meanwhile, by a
 *   command: natural spawns wait, {@link #runs}) is taken by it ({@link TremorRuntime#absorb}): it is the whole area
 *   now, not a bump. Every end makes it go deep ({@link TremorManager#goDeep}): removed, natural spawns of the
 *   dimension paused for {@code awakening.cooldownSeconds}. Awakenings are not saved: an entity loaded in AWAKENING goes deep at once
 *   ({@link TremorManager#onLevelLoad}).</li>
 *   <li><b>The hollow</b>: a swallowed target's Awakening ends with the target's event in the hollow
 *   ({@link #onHollowEnded}, registered with {@link HollowManager#addEndListener}).</li>
 *   <li><b>Players</b>: one who logs out, changes dimension or respawns has dropped the state on the client; it is
 *   sent again once the player is near. A target who logs out before being swallowed ends it at once (before the
 *   player is saved, without the Darkness); once swallowed, the hollow ends its event, and that ends it. A target who
 *   dies before being in the hollow ends it on the next tick ({@link #onLivingDeath}), also after an immediate
 *   respawn. A player who logs in with an Awakening's Darkness left over (a crash) loses it
 *   ({@link #onPlayerLoggedIn}). A rooted target (and the vehicle it is kept on) takes no fall damage
 *   ({@link #onLivingFall}).</li>
 * </ul>
 * Not while the game is frozen ({@code /tick freeze}): the phases are timed in game time, which stands still then.
 */
public final class AwakeningManager {
    /** Vibrations up to this far (blocks, horizontally) beyond the zone may come from a player in it. */
    private static final double VIBRATION_MARGIN = 3;

    private static final Map<ResourceKey<Level>, LevelState> LEVELS = new HashMap<>();
    /** Id of the next Awakening; ids are never reused while the server runs. */
    private static int nextId = 1;

    /** What is kept for a level: its Awakening and whom its entity heard last. */
    private static final class LevelState {
        final ServerLevel level;
        /** The running Awakening, or null. */
        Awakening awakening;
        /** The player whose vibration the entity heard last, and that entity's instance; null before. */
        UUID lastHeard;
        int lastHeardInstance;
        /** Instance of the entity last logged as having nobody to take in AWAKENING; 0 for none. */
        int loggedWaiting;

        LevelState(ServerLevel level) {
            this.level = level;
        }
    }

    /** Why an Awakening could not start ({@link #start(ServerPlayer)}); the message is for the player. */
    static final class Refusal extends Exception {
        Refusal(String message) {
            super(message);
        }
    }

    private AwakeningManager() {
    }

    // ---- API ----

    /**
     * {@code /tremor awaken [player]}: starts an Awakening for {@code target} where the target stands, with or without
     * an entity in the level (one there is taken; one in a lower stage is raised to AWAKENING with its sound).
     *
     * @throws Refusal if the target is dead, a spectator, in the hollow or in an event of it, or an Awakening runs in
     *                 the target's level already
     */
    static Awakening start(ServerPlayer target) throws Refusal {
        ServerLevel level = target.serverLevel();
        String name = target.getGameProfile().getName();
        if (HollowDimension.is(level)) {
            throw new Refusal("No Awakening in the hollow");
        }
        if (!target.isAlive()) {
            throw new Refusal(name + " is dead");
        }
        if (target.isSpectator()) {
            throw new Refusal(name + " is a spectator");
        }
        if (HollowManager.event(target) != null) {
            throw new Refusal(name + " is in an event of the hollow");
        }
        Awakening running = running(level);
        if (running != null) {
            throw new Refusal("Awakening #" + running.id + " for " + running.targetName + " runs in "
                    + level.dimension().location() + " already");
        }
        return start(level, target, false);
    }

    /**
     * {@code /tremor awaken stop}: ends the Awakening of {@code level}, else the one {@code executor} is the target of
     * (when run from inside the hollow), as cancelled; a target being swallowed or in the hollow is brought back out
     * ({@link #cancel}). Returns it, or null if there is none.
     *
     * @param executor the player running the command, or null
     */
    static Awakening stop(ServerLevel level, ServerPlayer executor) {
        Awakening awakening = running(level);
        if (awakening == null && executor != null) {
            awakening = targetOf(executor.getUUID());
        }
        if (awakening == null) {
            return null;
        }
        cancel(awakening, awakening.player(), "stopped by a command");
        return awakening;
    }

    /** Whether an Awakening runs in the level (natural spawns wait meanwhile, see {@link NaturalSpawner}). */
    public static boolean runs(ServerLevel level) {
        return running(level) != null;
    }

    /**
     * {@code /tremor info}: the level's Awakening (phase, time left, target, zone) and the natural spawn pause after
     * the last one; null if there is neither.
     */
    public static String describe(ServerLevel level) {
        Awakening awakening = running(level);
        long pause = NaturalSpawner.awakeningCooldown(level);
        if (awakening == null && pause == 0) {
            return null;
        }
        List<String> lines = new ArrayList<>(2);
        if (awakening != null) {
            lines.add(awakening.describe(level.getGameTime()));
        }
        if (pause > 0) {
            lines.add(String.format(Locale.ROOT, "Gone deep after an Awakening: no natural spawn for %.0f s",
                    pause / 20.0));
        }
        return String.join("\n", lines);
    }

    /** Whether the level's Awakening takes vibrations now (until its target is in the hollow: step ripples). */
    public static boolean listens(ServerLevel level) {
        Awakening awakening = running(level);
        return awakening != null && awakening.rippling();
    }

    /**
     * Whether a vibration at {@code x, z} of the level may concern its Awakening: it takes vibrations, and the point
     * is horizontally within its zone and a margin (the vibration enters the ground at most a few blocks from the
     * player who made it).
     */
    public static boolean listens(ServerLevel level, double x, double z) {
        Awakening awakening = running(level);
        return awakening != null && awakening.rippling() && AwakeningRules.inZone(awakening.center,
                awakening.radius + VIBRATION_MARGIN, new Vec3(x, awakening.center.y(), z));
    }

    /**
     * A vibration in the level, as the hearing evaluated it ({@link tremor.hearing.VibrationListener}): if the entity
     * heard it, its player is the one heard last (the target an Awakening starting by itself prefers); a running
     * Awakening rings it out over the ground ({@link Awakening#vibration}).
     *
     * @param player     the player behind it, or null (a mob, an item, a projectile...)
     * @param perception what the entity made of it, or null if it was not evaluated
     */
    public static void vibration(ServerLevel level, Vibration vibration, Player player, Perception perception) {
        if (player == null) {
            return;
        }
        if (perception != null && perception.heard()) {
            TremorRuntime runtime = TremorManager.runtime(level);
            TremorEntity entity = runtime == null ? null : runtime.entity();
            if (entity != null) {
                LevelState state = state(level, true);
                state.lastHeard = player.getUUID();
                state.lastHeardInstance = entity.instance();
            }
        }
        Awakening awakening = running(level);
        if (awakening != null) {
            awakening.vibration(vibration, player);
        }
    }

    // ---- events ----

    /** After the entity's tick (registered after {@link TremorManager#onLevelTick}): sees its stage of this tick. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.tickRateManager().runsNormally()) {
            return;
        }
        TremorRuntime runtime = TremorManager.runtime(level);
        TremorEntity entity = runtime == null ? null : runtime.entity();
        Awakening awakening = running(level);
        if (awakening != null) {
            if (entity != null) {
                runtime.absorb();
            }
            awakening.tick(level.getGameTime());
        } else if (entity != null && entity.stage() == Stage.AWAKENING && !entity.leaving()
                && !HollowDimension.is(level)) {
            awakenBy(level, entity);
        }
    }

    /**
     * The player part of an event of the hollow is over: an Awakening whose target it swallowed ends with it. Before
     * the move into the copy that is a cancellation (the player died, logged out or left the dimension while it got
     * dark, or the move failed), afterwards {@link Awakening.End#HOLLOW_OVER}.
     */
    public static void onHollowEnded(HollowEvent event, HollowEvent.End why) {
        for (LevelState state : List.copyOf(LEVELS.values())) {
            Awakening awakening = state.awakening;
            if (awakening != null && awakening.hollowEvent() == event) {
                end(awakening, awakening.phase() == TremorAwakeningPayload.Phase.HOLLOW ? Awakening.End.HOLLOW_OVER
                        : Awakening.End.CANCELLED, "the event in the hollow ended (" + why.id() + ")");
            }
        }
    }

    /**
     * Fired before the player is saved: a target not swallowed yet ends its Awakening now, so neither the Darkness
     * nor the root is saved with the player. A swallowed one is the hollow's (its logout ends its event, which ends
     * the Awakening).
     */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        for (LevelState state : List.copyOf(LEVELS.values())) {
            Awakening awakening = state.awakening;
            if (awakening == null) {
                continue;
            }
            awakening.forget(id);
            if (awakening.target.equals(id) && awakening.hollowEvent() == null) {
                end(awakening, Awakening.End.CANCELLED, awakening.targetName + " logged out");
            }
        }
    }

    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        forget(event.getEntity().getUUID());
    }

    /** Respawning may change the dimension without a PlayerChangedDimensionEvent. */
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        forget(event.getEntity().getUUID());
    }

    /**
     * A target who dies before being in the hollow ends its Awakening (cancelled; a copy for it stops) on the next tick
     * of its level ({@link Awakening#targetDied}), not here in the middle of whatever killed it. That is before the
     * player can respawn into the level: with {@code doImmediateRespawn} the client asks for that at once, between two
     * ticks. Registered at the lowest priority: a death another listener cancelled is none.
     */
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Awakening awakening = targetOf(player.getUUID());
            if (awakening != null && !awakening.swallowed()) {
                awakening.targetDied();
            }
        }
    }

    /** A rooted target, or the vehicle it is kept on, takes no fall damage: the fall does not happen. */
    public static void onLivingFall(LivingFallEvent event) {
        if (event.getEntity().level() instanceof ServerLevel level) {
            Awakening awakening = running(level);
            if (awakening != null && awakening.holds(event.getEntity())) {
                event.setCanceled(true);
            }
        }
    }

    /**
     * A player saved in an Awakening's Darkness (the server crashed or was killed while it ran) loses that Darkness on
     * login ({@link Awakening#clearLeftoverDarkness}); Awakenings are not saved, so it is no target any more.
     */
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && targetOf(player.getUUID()) == null
                && Awakening.clearLeftoverDarkness(player)) {
            Tremor.LOGGER.info("Awakening: took away the Darkness {} was saved with (no Awakening runs for them)",
                    player.getGameProfile().getName());
        }
    }

    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LevelState state = LEVELS.get(level.dimension());
            if (state != null && state.level == level) {
                LEVELS.remove(level.dimension());
            }
        }
    }

    /**
     * Ends every Awakening (cancelled) while the players are still there, before the entity's runtimes are dropped:
     * the targets lose the Darkness and the root before they are saved, and each entity goes deep in the saved data.
     */
    public static void onServerStopping(ServerStoppingEvent event) {
        for (LevelState state : List.copyOf(LEVELS.values())) {
            if (state.awakening != null) {
                end(state.awakening, Awakening.End.CANCELLED, "the server stops");
            }
        }
        LEVELS.clear();
    }

    // ---- internals ----

    /** Ends a running Awakening ({@link Awakening#finish}); a no-op for one that is over already. */
    static void end(Awakening awakening, Awakening.End why, String detail) {
        LevelState state = LEVELS.get(awakening.level.dimension());
        if (state == null || state.awakening != awakening) {
            return;
        }
        state.awakening = null;
        awakening.finish(why, detail);
    }

    /**
     * Ends a running Awakening as cancelled and gets its target out of the event of the hollow it started: a copy
     * being made stops, a target in the hollow is brought back out ({@link HollowManager#leave}).
     *
     * @param player the target if online (also respawned or in another dimension), else null
     */
    static void cancel(Awakening awakening, ServerPlayer player, String detail) {
        HollowEvent event = awakening.hollowEvent();
        // Ended first, so the end of the hollow event that follows is no longer its.
        end(awakening, Awakening.End.CANCELLED, detail);
        if (player != null && event != null && (event.phase() == HollowEvent.Phase.COPYING
                || event.phase() == HollowEvent.Phase.ENTERING || event.phase() == HollowEvent.Phase.INSIDE)) {
            try {
                HollowManager.leave(player, null);
            } catch (HollowManager.Refusal refusal) {
                Tremor.LOGGER.warn("Awakening #{}: could not get {} out of the hollow: {}", awakening.id,
                        awakening.targetName, refusal.getMessage());
            }
        }
    }

    /** The level's entity is in AWAKENING without an Awakening: it takes a player, if there is one to take. */
    private static void awakenBy(ServerLevel level, TremorEntity entity) {
        LevelState state = state(level, true);
        Vec3 at = entity.crawler().position();
        List<AwakeningRules.Candidate> candidates = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            candidates.add(new AwakeningRules.Candidate(player.getUUID(), at.distance(new Vec3(player.getX(),
                    player.getY(), player.getZ())), takeable(player)));
        }
        double hearing = TremorConfig.COMMON.hearingMaxDistance.getAsDouble();
        UUID chosen = AwakeningRules.chooseTarget(state.lastHeardInstance == entity.instance() ? state.lastHeard : null,
                candidates, hearing);
        ServerPlayer target = chosen == null ? null : level.getServer().getPlayerList().getPlayer(chosen);
        if (target != null) {
            start(level, target, true);
        } else if (state.loggedWaiting != entity.instance()) {
            state.loggedWaiting = entity.instance();
            Tremor.LOGGER.info(String.format(Locale.ROOT, "Tremor #%d in %s is in AWAKENING but has nobody to take "
                            + "(no living player in survival or adventure within %.0f blocks): it hunts until somebody "
                            + "comes", entity.instance(), level.dimension().location(), hearing));
        }
    }

    /** Whether an Awakening starting by itself may take the player: alive, survival or adventure, not in the hollow. */
    private static boolean takeable(ServerPlayer player) {
        return player.isAlive() && player.gameMode.isSurvival() && HollowManager.event(player) == null;
    }

    /**
     * Starts an Awakening for the target. The level's entity, if any, is taken (its stage change to AWAKENING, if it
     * was not there yet, sounds); without one, the start sounds at the centre.
     */
    private static Awakening start(ServerLevel level, ServerPlayer target, boolean natural) {
        TremorConfig.Awakening config = TremorConfig.COMMON.awakening;
        Awakening awakening = new Awakening(nextId++, level, target, natural, config.radius.get(),
                config.buildupSeconds.get() * 20);
        state(level, true).awakening = awakening;
        TremorRuntime runtime = TremorManager.runtime(level);
        boolean entity = runtime != null && runtime.entity() != null;
        if (entity) {
            runtime.absorb();
        }
        awakening.begin(!entity);
        return awakening;
    }

    /** The level's running Awakening, or null. */
    private static Awakening running(ServerLevel level) {
        LevelState state = state(level, false);
        return state == null ? null : state.awakening;
    }

    /** The running Awakening whose target the player is, in any level, or null. */
    private static Awakening targetOf(UUID player) {
        for (LevelState state : LEVELS.values()) {
            if (state.awakening != null && state.awakening.target.equals(player)) {
                return state.awakening;
            }
        }
        return null;
    }

    /** The player's client dropped its Awakening state: every Awakening sends it again when the player is near. */
    private static void forget(UUID player) {
        for (LevelState state : LEVELS.values()) {
            if (state.awakening != null) {
                state.awakening.forget(player);
            }
        }
    }

    /** The level's state (one of an earlier level instance is dropped); null if there is none, unless created. */
    private static LevelState state(ServerLevel level, boolean create) {
        LevelState state = LEVELS.get(level.dimension());
        if (state != null && state.level != level) {
            LEVELS.remove(level.dimension());
            state = null;
        }
        if (state == null && create) {
            state = new LevelState(level);
            LEVELS.put(level.dimension(), state);
        }
        return state;
    }
}
