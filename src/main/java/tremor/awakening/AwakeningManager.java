package tremor.awakening;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import tremor.Tremor;
import tremor.config.Difficulty;
import tremor.config.TremorConfig;
import tremor.core.behavior.Ambush;
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
 * Runs the Awakenings (SPEC 9; stage 4b: the real-world part), each an {@link Awakening}: at most one per level that
 * is no ambush, and an ambush for every player an entity lying in wait has caught ({@link #ambush}). Event handlers
 * are registered by {@link tremor.Tremor}; server thread only.
 * <ul>
 *   <li><b>Start by itself</b> (SPEC 8 AWAKENING, 9 phase 1): in AWAKENING an entity seeks a player (it goes
 *   for the sounds it hears, faster than hunting, {@link tremor.core.behavior.Seeking}). Each tick after its move,
 *   while it seeks (not leaving, no Awakening running, not in the hollow), the Awakening starts once its bump has
 *   reached a player ({@link AwakeningRules#reaches}: within {@code awakening.reachDistance} horizontally and
 *   {@link AwakeningRules#REACH_HEIGHT} vertically of the feet) who can be taken: alive, in survival mode (not
 *   adventure: the level in the hollow cannot be won without breaking blocks) and in no event of the hollow. Of
 *   several, the one it heard last ({@link #vibration}) if reached, else the nearest
 *   ({@link AwakeningRules#chooseTarget}); the zone is around where that player stands. Reaching nobody within
 *   {@code awakening.seekSeconds}, the entity calms down to HUNTING by itself (its mind); hiding quietly at the peak
 *   lets it pass (it searches only {@code awakening.searchRadius} around the last sound, and its roam keeps away from
 *   every player it could take, {@link #takeable}).</li>
 *   <li><b>By command</b> ({@link #start(ServerPlayer)}, {@code /tremor awaken [player]}, SPEC 14.1): for any living
 *   player who is not a spectator (also in adventure mode), with or without an entity; {@code /tremor awaken stop}
 *   ({@link #stop}) calls it off and gets a swallowed target out of the hollow again (a defeat decided there takes
 *   nobody then, {@link Outcomes}).</li>
 *   <li><b>The entity</b>: a level may have several (one for each part of the world where players are), and runs one
 *   Awakening at a time; another entity that reaches its player meanwhile seeks on until it calms down. An Awakening
 *   takes its own entity ({@link TremorRuntime#absorb}): the one that reached the target, or for a command's the
 *   nearest within {@code spawn.spacing} of the target, also one that comes there meanwhile (a command's spawn;
 *   natural spawns wait near it, {@link #runsNear}): it is the whole area now, not a bump. Every end makes it go deep
 *   ({@link TremorManager#goDeep}): removed, natural spawns within the spacing paused for
 *   {@code awakening.cooldownSeconds}. Awakenings are not saved: an entity loaded as taken by one goes deep at once
 *   ({@link TremorManager#onLevelLoad}).</li>
 *   <li><b>The hollow</b>: a swallowed target's Awakening ends by the outcome of the level in there
 *   ({@link Outcomes}: a victory makes it EMERGING first, once the hill is due, {@link Awakening#won}, and SETTLING
 *   when the victor's event in the hollow ends; a defeat ends it once carried out) or with the target's event in the
 *   hollow ({@link #onHollowEnded}, registered with {@link HollowManager#addEndListener}). The victor moved out into
 *   the hill is rooted there as it arrives ({@link #onPlayerChangedDimension}).</li>
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

    /** What is kept for a level: its Awakenings and whom each of its entities heard last. */
    private static final class LevelState {
        final ServerLevel level;
        /** The running Awakenings, oldest first: at most one that is no ambush, and the ambushes. */
        final List<Awakening> awakenings = new ArrayList<>();
        /** The player whose vibration each entity heard last, by its instance. */
        final Map<Integer, UUID> lastHeard = new HashMap<>();

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
        Awakening taken = targetOf(target.getUUID());
        if (taken != null) {
            throw new Refusal(name + " is being taken by Awakening #" + taken.id + " already");
        }
        Awakening running = running(level);
        if (running != null) {
            throw new Refusal("Awakening #" + running.id + " for " + running.targetName + " runs in "
                    + level.dimension().location() + " already");
        }
        return start(level, target, false, nearest(level, target.getX(), target.getZ()));
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
            // An ambush of the level: the one nearest to whoever runs the command.
            double best = Double.POSITIVE_INFINITY;
            for (Awakening ambush : awakenings(level)) {
                double away = executor == null ? 0 : Math.hypot(ambush.center.x() - executor.getX(),
                        ambush.center.z() - executor.getZ());
                if (away < best) {
                    best = away;
                    awakening = ambush;
                }
            }
        }
        if (awakening == null) {
            return null;
        }
        cancel(awakening, awakening.player(), "stopped by a command");
        return awakening;
    }

    /** Whether an Awakening runs in the level. */
    public static boolean runs(ServerLevel level) {
        return !awakenings(level).isEmpty();
    }

    /**
     * Whether an Awakening runs in the level within {@code reach} blocks (horizontally) of {@code x, z} (natural spawns
     * wait there meanwhile, see {@link NaturalSpawner}: it would take a new entity at once).
     */
    public static boolean runsNear(ServerLevel level, double x, double z, double reach) {
        for (Awakening awakening : awakenings(level)) {
            if (Math.hypot(awakening.center.x() - x, awakening.center.z() - z) <= reach) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code /tremor info} and the debug probe: the level's Awakening (phase, time left, target, zone) and the
     * natural spawn pause after one at {@code x, z}; null if there is neither.
     */
    public static String describe(ServerLevel level, double x, double z) {
        List<Awakening> awakenings = awakenings(level);
        long pause = NaturalSpawner.pauseLeft(level, x, z, tremor.spawn.SpawnPauses.Kind.AWAKENING);
        if (awakenings.isEmpty() && pause == 0) {
            return null;
        }
        List<String> lines = new ArrayList<>(awakenings.size() + 1);
        for (Awakening awakening : awakenings) {
            lines.add(awakening.describe(level.getGameTime()));
        }
        if (pause > 0) {
            lines.add(String.format(Locale.ROOT, "Gone deep after an Awakening near here: no natural spawn for %.0f s",
                    pause / 20.0));
        }
        return String.join("\n", lines);
    }

    /** Whether the level's Awakening takes vibrations now (until its target is in the hollow: step ripples). */
    public static boolean listens(ServerLevel level) {
        for (Awakening awakening : awakenings(level)) {
            if (awakening.rippling()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a vibration at {@code x, z} of the level may concern its Awakening: it takes vibrations, and the point
     * is horizontally within its zone and a margin (the vibration enters the ground at most a few blocks from the
     * player who made it).
     */
    public static boolean listens(ServerLevel level, double x, double z) {
        for (Awakening awakening : awakenings(level)) {
            if (awakening.rippling() && AwakeningRules.inZone(awakening.center, awakening.radius + VIBRATION_MARGIN,
                    new Vec3(x, awakening.center.y(), z))) {
                return true;
            }
        }
        return false;
    }

    /**
     * An entity of the level evaluated a vibration ({@link tremor.hearing.VibrationListener}): if it heard it, its
     * player is the one it heard last (the target an Awakening it starts by itself prefers).
     *
     * @param player     the player behind it, or null (a mob, an item, a projectile...)
     * @param perception what the entity made of it
     */
    public static void heard(ServerLevel level, TremorEntity entity, Player player, Perception perception) {
        if (player != null && perception.heard()) {
            state(level, true).lastHeard.put(entity.instance(), player.getUUID());
        }
    }

    /**
     * A vibration in the level: a running Awakening rings it out over the ground ({@link Awakening#vibration}).
     *
     * @param player the player behind it, or null (a mob, an item, a projectile...)
     */
    public static void vibration(ServerLevel level, Vibration vibration, Player player) {
        if (player == null) {
            return;
        }
        for (Awakening awakening : awakenings(level)) {
            awakening.vibration(vibration, player);
        }
    }

    // ---- events ----

    /** After the entity's tick (registered after {@link TremorManager#onLevelTick}): sees its stage of this tick. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.tickRateManager().runsNormally()) {
            return;
        }
        Quicksand.tick(level);
        Outcomes.leftDrops(level);
        forgetGone(level);
        for (Awakening awakening : awakenings(level)) {
            if (awakening.ended()) {
                continue; // ended by another's tick
            }
            TremorRuntime own = own(level, awakening);
            if (own != null) {
                own.absorb();
            }
            awakening.tick(level.getGameTime());
        }
        catchMore(level);
        if (running(level) == null && !HollowDimension.is(level)) {
            for (TremorRuntime runtime : TremorManager.runtimes(level)) {
                TremorEntity entity = runtime.entity();
                if (entity != null && entity.stage() == Stage.AWAKENING && !entity.leaving() && !entity.absorbed()
                        && awakenBy(level, runtime, entity)) {
                    break; // one at a time
                }
            }
        }
    }

    /**
     * The player part of an event of the hollow is over: an Awakening whose target it swallowed ends with it
     * ({@link AwakeningRules#afterHollow}), unless the hill of a victory is up: EMERGING goes SETTLING
     * ({@link Awakening#settle}: once the victor's screen has come back if the victor came out the normal way, at once
     * otherwise) and SETTLING ends by itself; a victory whose hill is not due yet goes EMERGING and SETTLING now if
     * the victor came out the normal way. An outcome decided in the hollow ends it as that (a victory whose victor is
     * gone before the hill rose, an escape through the edge, normally; also a defeat whose crater was still being dug,
     * the target having logged out meanwhile); else, before the move into the copy, it is a cancellation (the player
     * died, logged out or left the dimension while it got dark, or the move failed), and afterwards
     * {@link Awakening.End#HOLLOW_OVER}.
     */
    public static void onHollowEnded(HollowEvent event, HollowEvent.End why) {
        Awakening awakening = swallowedBy(event);
        if (awakening == null) {
            return;
        }
        long now = awakening.level.getGameTime();
        boolean left = why == HollowEvent.End.LEFT;
        if (awakening.victoryPending() && left && awakening.phase() == TremorAwakeningPayload.Phase.HOLLOW) {
            awakening.emerge(now);
        }
        // Out the normal way, the screen coming back; the hollow ends a logout on the way out as left too.
        ServerPlayer victor = awakening.player();
        boolean seen = left && victor != null && !victor.hasDisconnected() && victor.isAlive();
        switch (awakening.phase()) {
            case EMERGING -> awakening.settle(now, seen);
            case SETTLING -> {
                // Ends once the hill has settled.
            }
            default -> end(awakening, AwakeningRules.afterHollow(event.outcome(),
                    awakening.phase() == TremorAwakeningPayload.Phase.HOLLOW), "the event in the hollow ended ("
                    + why.id() + (event.outcome() == null ? "" : ", " + event.outcome().id()) + ")");
        }
    }

    /**
     * The target of the Awakening that swallowed the player of {@code event} destroyed the node ({@link Outcomes}):
     * it goes EMERGING at the swallow point once the hill is due ({@link Awakening#won}). A no-op for an event no
     * Awakening started ({@code /tremor hollow enter}).
     */
    static void victory(HollowEvent event) {
        Awakening awakening = swallowedBy(event);
        if (awakening != null) {
            Vec3 at = new Vec3(event.origin().position().x, event.origin().position().y,
                    event.origin().position().z);
            awakening.won(at, awakening.level.getGameTime());
        }
    }

    /**
     * A defeat was carried out on the player of {@code event} ({@link Outcomes}): the Awakening that swallowed the
     * player ends as {@link Awakening.End#DEFEAT}; a no-op if there is none (any more).
     */
    static void defeated(HollowEvent event, String detail) {
        Awakening awakening = swallowedBy(event);
        if (awakening != null) {
            end(awakening, Awakening.End.DEFEAT, detail);
        }
    }

    /**
     * Fired before the player is saved: a target not swallowed yet ends its Awakening now, so neither the Darkness
     * nor the root is saved with the player. A swallowed one is the hollow's (its logout ends its event, which ends
     * the Awakening).
     */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        for (Awakening awakening : all()) {
            awakening.forget(id);
            if (awakening.target.equals(id) && awakening.hollowEvent() == null) {
                end(awakening, Awakening.End.CANCELLED, awakening.targetName + " logged out");
            }
        }
    }

    /**
     * The player's client dropped the state (sent again once near). A target moved out of the hollow into the level of
     * its Awakening after a victory is in the hill there: it is rooted at once ({@link Awakening#victorArrived}).
     */
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        forget(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player) {
            Awakening awakening = targetOf(player.getUUID());
            if (awakening != null) {
                awakening.victorArrived(player);
            }
        }
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
            for (Awakening awakening : awakenings(level)) {
                if (awakening.holds(event.getEntity())) {
                    event.setCanceled(true);
                    return;
                }
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
        for (Awakening awakening : all()) {
            end(awakening, Awakening.End.CANCELLED, "the server stops");
        }
        Quicksand.stopAll();
        LEVELS.clear();
    }

    // ---- internals ----

    /** Ends a running Awakening ({@link Awakening#finish}); a no-op for one that is over already. */
    static void end(Awakening awakening, Awakening.End why, String detail) {
        LevelState state = LEVELS.get(awakening.level.dimension());
        if (state == null || !state.awakenings.remove(awakening)) {
            return;
        }
        boolean entityFree = true;
        for (Awakening other : state.awakenings) {
            entityFree &= awakening.entity == 0 || other.entity != awakening.entity;
        }
        awakening.finish(why, detail, entityFree);
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

    /**
     * The entity of a running Awakening: its own, or while it has none (a command's with none near, or its own gone)
     * the nearest within {@code spawn.spacing} of its centre, which it takes from now on; null if there is none.
     */
    private static TremorRuntime own(ServerLevel level, Awakening awakening) {
        if (awakening.entity != 0) {
            TremorRuntime own = TremorManager.byInstance(level, awakening.entity);
            if (own != null) {
                return own;
            }
            awakening.entity = 0;
        }
        if (awakening.ambush) {
            return null; // its own is gone: an ambush takes no other
        }
        TremorRuntime near = nearest(level, awakening.center.x(), awakening.center.z());
        if (near != null) {
            awakening.entity = near.entity().instance();
        }
        return near;
    }

    /**
     * The entity of the part of the world around {@code x, z}: the nearest within {@code spawn.spacing}
     * (horizontally) that no Awakening has taken; null if there is none.
     */
    private static TremorRuntime nearest(ServerLevel level, double x, double z) {
        TremorRuntime nearest = null;
        double best = TremorConfig.COMMON.spawn.spacing.get();
        for (TremorRuntime runtime : TremorManager.runtimes(level)) {
            TremorEntity entity = runtime.entity();
            if (entity != null && !entity.absorbed()) {
                double away = TremorManager.horizontal(entity, x, z);
                if (away <= best) {
                    best = away;
                    nearest = runtime;
                }
            }
        }
        return nearest;
    }

    /**
     * An entity seeks in AWAKENING without an Awakening in the level: once its bump has reached a player who can be
     * taken ({@link AwakeningRules#chooseTarget}), the Awakening starts for that player with it. Returns whether it
     * did.
     */
    private static boolean awakenBy(ServerLevel level, TremorRuntime runtime, TremorEntity entity) {
        Vec3 bump = entity.crawler().position();
        double reach = TremorConfig.COMMON.awakening.reachDistance.get();
        List<AwakeningRules.Candidate> candidates = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            Vec3 feet = new Vec3(player.getX(), player.getY(), player.getZ());
            if (AwakeningRules.reaches(bump, feet, reach)) {
                candidates.add(new AwakeningRules.Candidate(player.getUUID(), feet, takeable(player)));
            }
        }
        if (candidates.isEmpty()) {
            return false;
        }
        LevelState state = state(level, false);
        UUID lastHeard = state == null ? null : state.lastHeard.get(entity.instance());
        UUID chosen = AwakeningRules.chooseTarget(lastHeard, bump, candidates, reach);
        ServerPlayer target = chosen == null ? null : level.getServer().getPlayerList().getPlayer(chosen);
        if (target != null) {
            Tremor.LOGGER.info(String.format(Locale.ROOT, "Tremor #%d in %s has reached %s (%.1f blocks away) in "
                            + "AWAKENING", entity.instance(), level.dimension().location(),
                    target.getGameProfile().getName(), AwakeningRules.horizontalDistance(bump, new Vec3(target.getX(),
                            target.getY(), target.getZ()))));
            start(level, target, true, runtime);
            return true;
        }
        return false;
    }

    /**
     * Whether an Awakening starting by itself may take the player: alive, in survival mode, not in the hollow. Not in
     * adventure mode: there the node cannot be broken nor the ground dug, so the level in the hollow cannot be won.
     * The roam of the seeking entity keeps away from these players (its mind, {@code tremor.entity.TremorMind}).
     */
    public static boolean takeable(ServerPlayer player) {
        return player.isAlive() && player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL
                && HollowManager.event(player) == null && targetOf(player.getUUID()) == null;
    }

    /**
     * An entity lying in wait was sprung ({@code tremor.entity.TremorMind}): each of the {@code targets} who can be
     * taken ({@link #takeable}) is pulled under at once by an ambush of their own ({@link Awakening}: the quicksand,
     * {@code ambush.quicksandSeconds}, then a hollow of their own), around {@code center}. All of them take the entity
     * ({@link TremorRuntime#absorb}), which goes deep when the last of them ends. While one is still in the quicksand,
     * whoever else steps within {@code radius} of the centre is taken too ({@link #catchMore}). Returns how many
     * started.
     */
    public static int ambush(ServerLevel level, TremorRuntime runtime, Vec3 center, double radius,
                             List<ServerPlayer> targets) {
        TremorEntity entity = runtime.entity();
        if (HollowDimension.is(level) || entity == null || entity.absorbed()) {
            return 0;
        }
        int started = 0;
        Quicksand quicksand = new Quicksand(level, center, radius);
        for (ServerPlayer target : targets) {
            if (target.level() == level && takeable(target)) {
                startAmbush(level, target, center, radius, entity.instance(), quicksand);
                started++;
            }
        }
        if (started > 0) {
            runtime.absorb();
        }
        return started;
    }

    /** Whether an ambush that took the entity {@code instance} still pulls its target in. */
    public static boolean pullingWith(ServerLevel level, int instance) {
        for (Awakening awakening : awakenings(level)) {
            if (awakening.entity == instance && awakening.pulling()) {
                return true;
            }
        }
        return false;
    }

    private static void startAmbush(ServerLevel level, ServerPlayer target, Vec3 center, double radius,
                                    int entity, Quicksand quicksand) {
        int ticks = (int) Math.max(1, Math.round(TremorConfig.COMMON.ambush.quicksandSeconds.get() * 20));
        Awakening awakening = new Awakening(nextId++, level, target, center, radius, ticks, quicksand);
        awakening.entity = entity;
        state(level, true).awakenings.add(awakening);
        awakening.begin();
    }

    /**
     * While an ambush is still pulling its target in, whoever else steps onto the place or into its mud is taken too,
     * by an ambush of their own in the same quicksand.
     */
    private static void catchMore(ServerLevel level) {
        for (Awakening awakening : awakenings(level)) {
            if (!awakening.pulling()) {
                continue;
            }
            for (ServerPlayer player : level.players()) {
                // Its own mud only: another ambush's quicksand is no part of this one.
                boolean inMud = awakening.quicksand != null && awakening.quicksand.owns(
                        net.minecraft.core.BlockPos.containing(player.getX(), player.getY() - 0.2, player.getZ()));
                if (takeable(player) && (inMud || Ambush.steppedIn(awakening.center, new Vec3(player.getX(),
                        player.getY(), player.getZ()), awakening.radius))) {
                    startAmbush(level, player, awakening.center, awakening.radius, awakening.entity,
                            awakening.quicksand);
                }
            }
        }
    }

    /**
     * Starts an Awakening for the target with {@code entity} (null: none). It is taken (its stage change to
     * AWAKENING, if it was not there yet, sounds: a roar). The start sounds at the centre: the pull.
     */
    private static Awakening start(ServerLevel level, ServerPlayer target, boolean natural, TremorRuntime entity) {
        return start(level, target, natural,
                Difficulty.current().buildupTicks(TremorConfig.COMMON.awakening.buildupSeconds.get()), entity);
    }

    /**
     * The frenzy over a dropped shard reached the player who dropped it ({@code tremor.entity.Frenzy}): an Awakening
     * starts for them with a build-up of only {@code items.frenzyBuildupTicks}, there is no running from it. False if
     * it cannot start (the target dead, a spectator, in the hollow, or an Awakening already runs in the level).
     */
    public static boolean frenzy(ServerPlayer target, TremorRuntime entity) {
        ServerLevel level = target.serverLevel();
        if (!target.isAlive() || target.isSpectator() || HollowManager.event(target) != null
                || HollowDimension.is(level) || running(level) != null || targetOf(target.getUUID()) != null) {
            return false;
        }
        tremor.hollow.Nightmare.mark(target.getUUID());
        start(level, target, false, Math.max(1, TremorConfig.COMMON.frenzyBuildupTicks.get()), entity);
        return true;
    }

    private static Awakening start(ServerLevel level, ServerPlayer target, boolean natural, int buildupTicks,
                                   TremorRuntime entity) {
        TremorConfig.Awakening config = TremorConfig.COMMON.awakening;
        Awakening awakening = new Awakening(nextId++, level, target, natural, config.radius.get(), buildupTicks);
        state(level, true).awakenings.add(awakening);
        if (entity != null && entity.entity() != null) {
            awakening.entity = entity.entity().instance();
            entity.absorb();
        }
        awakening.begin();
        return awakening;
    }

    /** The level's running Awakening that is no ambush, or null. */
    private static Awakening running(ServerLevel level) {
        for (Awakening awakening : awakenings(level)) {
            if (!awakening.ambush) {
                return awakening;
            }
        }
        return null;
    }

    /** The level's running Awakenings, oldest first (a copy). */
    private static List<Awakening> awakenings(ServerLevel level) {
        LevelState state = state(level, false);
        return state == null ? List.of() : List.copyOf(state.awakenings);
    }

    /** Every running Awakening, of every level (a copy). */
    private static List<Awakening> all() {
        List<Awakening> all = new ArrayList<>();
        for (LevelState state : LEVELS.values()) {
            all.addAll(state.awakenings);
        }
        return all;
    }

    /** The running Awakening that swallowed the player of {@code event} (the event is its), in any level, or null. */
    private static Awakening swallowedBy(HollowEvent event) {
        for (Awakening awakening : all()) {
            if (awakening.hollowEvent() == event) {
                return awakening;
            }
        }
        return null;
    }

    /** The running Awakening whose target the player is, in any level, or null. */
    /** Whether an Awakening has {@code player} for its target (its zone, its quicksand or its hollow). */
    public static boolean taken(net.minecraft.world.entity.player.Player player) {
        return targetOf(player.getUUID()) != null;
    }

    /** Once a minute: who the entities that are gone heard last is forgotten (their numbers never come back). */
    private static void forgetGone(ServerLevel level) {
        LevelState state = state(level, false);
        if (state != null && level.getGameTime() % 1200 == 0) {
            state.lastHeard.keySet().removeIf(instance -> TremorManager.byInstance(level, instance) == null);
        }
    }

    private static Awakening targetOf(UUID player) {
        for (Awakening awakening : all()) {
            if (awakening.target.equals(player)) {
                return awakening;
            }
        }
        return null;
    }

    /** The player's client dropped its Awakening state: every Awakening sends it again when the player is near. */
    private static void forget(UUID player) {
        for (Awakening awakening : all()) {
            awakening.forget(player);
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
