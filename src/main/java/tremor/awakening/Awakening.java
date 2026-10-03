package tremor.awakening;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import tremor.Tremor;
import tremor.config.TremorConfig;
import tremor.core.math.Vec3;
import tremor.entity.TremorManager;
import tremor.hearing.Vibration;
import tremor.hearing.VibrationListener;
import tremor.hollow.HollowEvent;
import tremor.hollow.HollowManager;
import tremor.network.TremorAwakeningPayload;
import tremor.network.TremorAwakeningPayload.Phase;
import tremor.network.TremorStepRipplePayload;
import tremor.sound.TremorSounds;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * One Awakening (SPEC 9) in a level: the entity has become the whole area around one player, the target. Created and
 * ended by {@link AwakeningManager}, ticked by it with the level. Not saved (an entity loaded in AWAKENING goes deep,
 * see {@link TremorManager#onLevelLoad}). The phases ({@link Phase}, as the clients get them):
 * <ol>
 *   <li>BUILDUP (SPEC 9 phase 1, "нарастание", {@code awakening.buildupSeconds}): the zone is a vertical cylinder of
 *   {@code awakening.radius} around where the target stood at the start ({@link AwakeningRules#inZone}). The clients
 *   draw the silence and the breathing of the ground; every vibration a player in the zone makes rings out over the
 *   ground ({@link #vibration}). For the last third the target is in vanilla Darkness, which pulses ({@link #darken}:
 *   short and renewed, so it does not outlive the Awakening; a longer Darkness from elsewhere is left alone). The
 *   target escapes by getting out of the zone (SPEC 9 "Побег": {@link End#ESCAPED}); one who dies (also one who
 *   respawns at once, {@link #targetDied}), logs out or leaves the dimension ends it ({@link End#CANCELLED}).</li>
 *   <li>SWALLOWING ({@code awakening.swallowTicks}): a hill rises under the target, at the focus (where the target
 *   stood when the phase began); the clients draw it. The target is rooted ({@link Root}) and stays in the Darkness.
 *   Then the target is swallowed ({@link HollowManager#enter}): the screen goes dark while the terrain is copied, and
 *   the target is moved into the copy. Until that move the phase goes on (the hill stays up) and the root holds; a
 *   target who dies, logs out or leaves the dimension meanwhile ends it ({@link End#CANCELLED}; the copy stops). If
 *   the hollow refuses (no free slot...), it ends ({@link End#CANCELLED}).</li>
 *   <li>HOLLOW: the target is in the hollow, released and out of the Darkness; for everyone else the ground is smooth,
 *   as if nobody had been there. The level in there ends in an outcome ({@link Outcomes}). An escape through the edge
 *   ends it when the target's event in the hollow ends ({@link End#EDGE_ESCAPED}), a defeat once the sinkhole is
 *   there and the target dead or on the way out ({@link End#DEFEAT}). Without an outcome it ends with the target's
 *   event in the hollow, whatever ended that ({@link End#HOLLOW_OVER}). After a victory ({@link #won}) it stays until
 *   the hill is due ({@link AwakeningRules#emergeDelay}: at its highest when the victor is moved out).</li>
 *   <li>EMERGING ({@code awakening.emergeTicks}), after a victory ({@link #emerge}): at the swallow point (the focus)
 *   a hill rises, the target comes out of it (put there after the fade out of the hollow) and it settles; then it ends
 *   ({@link End#VICTORY}), whatever the target's event in the hollow does meanwhile, but not before the target is out
 *   of the hollow and has been sent the phase (so a long fade out of the hollow, or a slow client, does not miss
 *   it), or is offline or away.</li>
 * </ol>
 * Sync: every player of the level within {@value #SYNC_MARGIN} blocks of the zone (horizontally) gets the state
 * ({@link TremorAwakeningPayload}) once, players coming that near (also by joining or changing dimension) on the next
 * tick, and everyone who has it gets each phase change and the end. Server thread only.
 */
final class Awakening {
    /** Why an Awakening ended. */
    enum End {
        /** The target got out of the zone during the buildup (SPEC 9 "Побег"). */
        ESCAPED,
        /** The target's event in the hollow is over without an outcome (logged out, died, {@code /tremor restore}). */
        HOLLOW_OVER,
        /** The target destroyed the node and came out of the hill (SPEC 9 "Победа"). */
        VICTORY,
        /** The target got out through the edge of the hollow before it closed (SPEC 9 "Побег"). */
        EDGE_ESCAPED,
        /** The soft ground pulled the target in: a sinkhole at the swallow point (SPEC 9 "Поражение"). */
        DEFEAT,
        /**
         * Called off: the target died, logged out or left the dimension before getting into the hollow, the hollow
         * refused or failed before the move, {@code /tremor awaken stop}, the server stopped.
         */
        CANCELLED;

        String id() {
            return name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    /** Players this far beyond the zone (horizontally from its edge) get its state. */
    static final double SYNC_MARGIN = 96;
    /** Players this far beyond the zone (horizontally from its edge) get its step ripples. */
    static final double RIPPLE_MARGIN = 64;
    /**
     * Mark in a player's persistent data (saved with the player): the player is in an Awakening's Darkness. Set while
     * it is, so that a player saved with it (the server crashed or was killed, and the Awakening could not take it
     * away) loses it at the next login ({@link #clearLeftoverDarkness}).
     */
    private static final String DARKNESS_MARK = Tremor.MODID + ":awakening_darkness";
    /** {@link #darknessEnd} while no Darkness is given. */
    private static final long NO_DARKNESS = Long.MIN_VALUE;
    /** {@link #emergeAt} without a victory (or once EMERGING). */
    private static final long NO_VICTORY = Long.MIN_VALUE;
    private static final double TICKS_PER_SECOND = 20;

    final int id;
    final ServerLevel level;
    /** The player it is about. */
    final UUID target;
    /** The target's player object at the start: another one with the same UUID is the target respawned. */
    private final ServerPlayer instance;
    final String targetName;
    /** Started by itself (the entity reached AWAKENING) rather than by {@code /tremor awaken}. */
    final boolean natural;
    final Vec3 center;
    final double radius;
    /** Game time it started at. */
    final long startTime;

    private Phase phase = Phase.BUILDUP;
    private PhaseClock clock;
    /** Where the hill rises: the target's position when SWALLOWING began; the centre before. */
    private Vec3 focus;
    /** The target's event in the hollow, once swallowed; null before. */
    private HollowEvent hollowEvent;
    private final Root root = new Root();
    /** Game time the Darkness given last ends at; {@link #NO_DARKNESS} if none was given or it was taken away. */
    private long darknessEnd = NO_DARKNESS;
    /** The target died ({@link #targetDied}); it ends on the next tick. */
    private boolean died;
    /** Game time the hill of a victory is due at ({@link #won}); {@link #NO_VICTORY} without a victory. */
    private long emergeAt = NO_VICTORY;
    /** Players who have the state (sent since they entered the level). */
    private final Set<UUID> recipients = new HashSet<>();
    /** Ended ({@link #finish}). */
    private boolean over;

    /**
     * Starts in BUILDUP around the target's position; nothing is sent before {@link #begin}.
     *
     * @param buildupTicks length of the buildup
     */
    Awakening(int id, ServerLevel level, ServerPlayer target, boolean natural, double radius, int buildupTicks) {
        this.id = id;
        this.level = level;
        this.target = target.getUUID();
        this.instance = target;
        this.targetName = target.getGameProfile().getName();
        this.natural = natural;
        this.center = vec(target.position());
        this.radius = radius;
        this.startTime = level.getGameTime();
        this.focus = center;
        this.clock = new PhaseClock(startTime, buildupTicks);
    }

    Phase phase() {
        return phase;
    }

    /** Planned length of the current phase in seconds; 0 if it is open-ended. */
    double phaseSeconds() {
        return seconds(clock.ticks());
    }

    /** The target's event in the hollow once swallowed, else null. */
    HollowEvent hollowEvent() {
        return hollowEvent;
    }

    /** The target if online (wherever it is), else null. */
    ServerPlayer player() {
        return level.getServer().getPlayerList().getPlayer(target);
    }

    /** Whether vibrations ring out over the ground now: until the target is in the hollow. */
    boolean rippling() {
        return phase == Phase.BUILDUP || phase == Phase.SWALLOWING;
    }

    /** Whether the target is (or should be) in the hollow: moved into the copy, or past that. */
    boolean swallowed() {
        return phase == Phase.HOLLOW || phase == Phase.EMERGING
                || hollowEvent != null && hollowEvent.phase().inHollow();
    }

    /** Whether the entity is held by the root now: the rooted target, or the vehicle it is kept on. */
    boolean holds(Entity entity) {
        return root.rooted() && (entity.getUUID().equals(target) || root.holds(entity));
    }

    /**
     * The target died before being swallowed ({@link AwakeningManager#onLivingDeath}): it ends as cancelled on the next
     * tick, even if the player has respawned by then (with {@code doImmediateRespawn} the client asks for that at once,
     * and the new player could otherwise be taken for the target).
     */
    void targetDied() {
        died = true;
    }

    /**
     * Sends the state to the players near and logs the start.
     *
     * @param sound marks the start by the AWAKEN sound at the centre (when no entity was there to make it)
     */
    void begin(boolean sound) {
        if (sound) {
            level.playSound(null, center.x(), center.y(), center.z(), TremorSounds.AWAKEN, SoundSource.HOSTILE,
                    (float) TremorConfig.COMMON.transitionVolume.getAsDouble(), 1);
        }
        sync(true);
        Tremor.LOGGER.info(String.format(Locale.ROOT, "Awakening #%d in %s (%s) for %s: zone of %.0f blocks around %s, "
                        + "%.1f s to get out", id, level.dimension().location(), natural ? "natural" : "command",
                targetName, radius, text(center), phaseSeconds()));
    }

    /** One level tick (the game is not frozen). */
    void tick(long now) {
        switch (phase) {
            case BUILDUP -> buildup(now);
            case SWALLOWING -> swallowing(now);
            case HOLLOW -> {
                // Ends by an outcome (Outcomes) or with the target's event in the hollow (onHollowEnded).
                if (victoryPending() && now >= emergeAt) {
                    emerge(now);
                }
            }
            case EMERGING -> {
                if (clock.over(now) && victorOut()) {
                    AwakeningManager.end(this, End.VICTORY, targetName + " came out of the ground at "
                            + text(focus));
                }
            }
        }
        if (!over) {
            sync(false);
        }
    }

    /**
     * The target destroyed the node (SPEC 9 "Победа", {@link Outcomes#victory}): the hill rises at {@code at} (the
     * swallow point), where the target is put after the fade out of the hollow, once it is due
     * ({@link AwakeningRules#emergeDelay}; at once if it already is): EMERGING begins then ({@link #emerge}). The target
     * is let go and out of the Darkness now, if still held (the victory came the tick it was moved in).
     */
    void won(Vec3 at, long now) {
        if (phase != Phase.HOLLOW && phase != Phase.SWALLOWING) {
            return;
        }
        ServerPlayer player = player();
        if (player != null) {
            root.release(player);
            undarken(player);
        }
        focus = at;
        emergeAt = now + AwakeningRules.emergeDelay(TremorConfig.COMMON.hollow.fadeTicks.get(),
                TremorConfig.COMMON.awakening.emergeTicks.get());
        Tremor.LOGGER.info("Awakening #{}: {} destroyed the node, the hill at {} rises in {} s", id, targetName,
                text(focus), seconds(emergeAt - now));
        if (now >= emergeAt) {
            emerge(now);
        }
    }

    /** Whether the target won ({@link #won}) and the hill is not up yet. */
    boolean victoryPending() {
        return emergeAt != NO_VICTORY;
    }

    /** EMERGING begins after a victory ({@link #won}): the hill rises at the focus now. */
    void emerge(long now) {
        emergeAt = NO_VICTORY;
        setPhase(Phase.EMERGING, now, TremorConfig.COMMON.awakening.emergeTicks.get());
        Tremor.LOGGER.info("Awakening #{}: the hill at {} rises and lets {} out in {} s", id, text(focus), targetName,
                phaseSeconds());
    }

    /**
     * Whether the victor is done with the hill as far as the server goes: out of the hollow and sent the EMERGING
     * phase (its client runs the hill to the end by itself), or offline, in another level or far away (the phase is
     * not sent there).
     */
    private boolean victorOut() {
        ServerPlayer player = player();
        if (player == null) {
            return true;
        }
        if (hollowEvent != null && hollowEvent.phase().inHollow()) {
            return false;
        }
        return recipients.contains(target) || player.level() != level || !near(player, radius + SYNC_MARGIN);
    }

    /**
     * A vibration in the level (SPEC 9 "Рябь от шагов"): one made by a player in the zone (that player's step,
     * landing, fall, a block broken or placed...) rings out over the ground from where it entered it, as strong as
     * {@link AwakeningRules#rippleStrength} (nothing below {@link AwakeningRules#MIN_RIPPLE_STRENGTH}); sent to the
     * players within {@value #RIPPLE_MARGIN} blocks of the zone. Only while {@link #rippling}.
     *
     * @param player the player behind it (see {@link VibrationListener})
     */
    void vibration(Vibration vibration, Player player) {
        if (!rippling() || !AwakeningRules.inZone(center, radius, vec(player.position()))) {
            return;
        }
        float strength = AwakeningRules.rippleStrength(vibration.loudness(), vibration.footing(),
                VibrationListener.walkingStepLoudness());
        if (strength < AwakeningRules.MIN_RIPPLE_STRENGTH) {
            return;
        }
        TremorStepRipplePayload ripple = new TremorStepRipplePayload(id, vibration.source(), level.getGameTime(),
                strength);
        double reach = radius + RIPPLE_MARGIN;
        for (ServerPlayer receiver : level.players()) {
            if (near(receiver, reach)) {
                PacketDistributor.sendToPlayer(receiver, ripple);
            }
        }
    }

    /** The player's client dropped the state (it logged out, or its level changed): sent again when near. */
    void forget(UUID player) {
        recipients.remove(player);
    }

    /**
     * Ends it (called by {@link AwakeningManager#end} only): the target is released and out of the Darkness, those
     * who have the state are told it is over, the ground sighs (SPEC 13) where it all was, and the entity of the level
     * goes deep ({@link TremorManager#goDeep}: removed, natural spawns paused for {@code awakening.cooldownSeconds}).
     */
    void finish(End why, String detail) {
        over = true;
        long now = level.getGameTime();
        ServerPlayer player = player();
        if (player != null) {
            root.release(player);
            undarken(player);
        }
        TremorAwakeningPayload ended = new TremorAwakeningPayload(id, center, (float) radius, Phase.ENDED, now, 0,
                target, focus);
        for (UUID receiver : recipients) {
            ServerPlayer online = level.getServer().getPlayerList().getPlayer(receiver);
            if (online != null) {
                PacketDistributor.sendToPlayer(online, ended);
            }
        }
        recipients.clear();
        level.playSound(null, focus.x(), focus.y(), focus.z(), TremorSounds.SIGH, SoundSource.HOSTILE,
                (float) TremorConfig.COMMON.transitionVolume.getAsDouble(), 1);
        boolean entity = TremorManager.goDeep(level);
        Tremor.LOGGER.info(String.format(Locale.ROOT, "Awakening #%d in %s ended (%s) after %.1f s: %s; %s, no "
                        + "natural spawn for %d s", id, level.dimension().location(), why.id(),
                seconds(now - startTime), detail, entity ? "the tremor went deep" : "no tremor there",
                TremorConfig.COMMON.awakening.cooldownSeconds.get()));
    }

    /** {@code /tremor info}: the phase, the time left, the target and the zone. */
    String describe(long now) {
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT, "Awakening #%d (%s) for %s: ", id,
                natural ? "natural" : "command", targetName));
        switch (phase) {
            case BUILDUP -> text.append(String.format(Locale.ROOT, "buildup, %.1f of %.1f s left%s",
                    seconds(clock.remaining(now)), phaseSeconds(),
                    clock.elapsed(now) >= AwakeningRules.darknessStart(clock.ticks()) ? ", in the darkness" : ""));
            case SWALLOWING -> text.append(hollowEvent == null
                    ? String.format(Locale.ROOT, "swallowing at %s, %.1f s left", text(focus),
                    seconds(clock.remaining(now)))
                    : String.format(Locale.ROOT, "swallowed at %s, the hollow is %s", text(focus),
                    hollowEvent.phase().id()));
            case HOLLOW -> text.append(String.format(Locale.ROOT, "in the hollow for %.1f s (its event: %s%s)%s",
                    seconds(clock.elapsed(now)), hollowEvent.phase().id(),
                    hollowEvent.outcome() == null ? "" : ", " + hollowEvent.outcome().id(), victoryPending()
                            ? String.format(Locale.ROOT, "; won, the hill rises in %.1f s",
                            seconds(Math.max(0, emergeAt - now))) : ""));
            case EMERGING -> text.append(String.format(Locale.ROOT, "won: coming out of the hill at %s, %.1f s left",
                    text(focus), seconds(clock.remaining(now))));
        }
        ServerPlayer player = player();
        String where = player == null ? "offline" : player.level() != level ? "in " + player.level().dimension()
                .location() : String.format(Locale.ROOT, "%.1f blocks from the centre",
                AwakeningRules.horizontalDistance(center, vec(player.position())));
        Entity vehicle = root.vehicle();
        text.append(String.format(Locale.ROOT, "\n  zone of %.0f blocks around %s; %s %s%s; %d player%s it",
                radius, text(center), targetName, where, !root.rooted() ? "" : vehicle == null ? ", rooted"
                        : ", rooted on " + vehicle.getName().getString(), recipients.size(),
                recipients.size() == 1 ? " has" : "s have"));
        return text.toString();
    }

    // ---- phases ----

    private void buildup(long now) {
        ServerPlayer player = player();
        String gone = gone(player);
        if (gone != null) {
            AwakeningManager.end(this, End.CANCELLED, targetName + " " + gone);
            return;
        }
        Vec3 at = vec(player.position());
        if (!AwakeningRules.inZone(center, radius, at)) {
            AwakeningManager.end(this, End.ESCAPED, String.format(Locale.ROOT, "%s got %.1f blocks from the centre",
                    targetName, AwakeningRules.horizontalDistance(center, at)));
            return;
        }
        if (clock.elapsed(now) >= AwakeningRules.darknessStart(clock.ticks())) {
            darken(player);
        }
        if (clock.over(now)) {
            focus = at;
            root.start(player);
            setPhase(Phase.SWALLOWING, now, TremorConfig.COMMON.awakening.swallowTicks.get());
            Tremor.LOGGER.info("Awakening #{}: {} did not get out, the ground swallows them at {}", id, targetName,
                    text(focus));
        }
    }

    private void swallowing(long now) {
        ServerPlayer player = player();
        if (hollowEvent != null && hollowEvent.phase().inHollow()) {
            // Moved into the copy.
            if (player != null) {
                root.release(player);
                undarken(player);
            }
            setPhase(Phase.HOLLOW, now, 0);
            return;
        }
        String gone = gone(player);
        if (gone != null) {
            // Also while the copy is made (the screen going dark): the copy stops then.
            AwakeningManager.cancel(this, player, targetName + " " + gone);
            return;
        }
        root.hold(player);
        darken(player);
        if (hollowEvent == null && clock.over(now)) {
            try {
                hollowEvent = HollowManager.enter(player);
            } catch (HollowManager.Refusal refusal) {
                Tremor.LOGGER.warn("Awakening #{}: {} cannot be swallowed into the hollow: {}", id, targetName,
                        refusal.getMessage());
                AwakeningManager.end(this, End.CANCELLED, "the hollow refused: " + refusal.getMessage());
            }
        }
    }

    private void setPhase(Phase phase, long now, int ticks) {
        this.phase = phase;
        clock = new PhaseClock(now, ticks);
        sync(true);
    }

    /**
     * Why the target cannot go on before being swallowed (died, also if respawned since; logged out; in another
     * dimension), or null.
     */
    private String gone(ServerPlayer player) {
        if (died) {
            return "died";
        }
        if (player == null) {
            return "logged out";
        }
        if (!player.isAlive()) {
            return "died";
        }
        if (player.level() != level) {
            return "left the dimension";
        }
        // A death the event did not report: a new player object comes only from a respawn.
        return player != instance ? "died" : null;
    }

    // ---- darkness ----

    /**
     * Keeps the target in the Darkness (as a warden gives it; it pulses for as long as it lasts): one of
     * {@value AwakeningRules#DARKNESS_TICKS} ticks, renewed once {@value AwakeningRules#DARKNESS_RENEW_TICKS} or fewer
     * are left. A longer Darkness the target has (a warden's, a command's) is left as it is.
     */
    private void darken(ServerPlayer player) {
        MobEffectInstance current = player.getEffect(MobEffects.DARKNESS);
        if (current == null || current.endsWithin(AwakeningRules.DARKNESS_RENEW_TICKS)) {
            player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, AwakeningRules.DARKNESS_TICKS, 0, false,
                    false));
            darknessEnd = level.getGameTime() + AwakeningRules.DARKNESS_TICKS;
            player.getPersistentData().putBoolean(DARKNESS_MARK, true);
        }
    }

    /**
     * Takes the Darkness given away again ({@link AwakeningRules#ownDarkness}), but not one that lasts longer: that is
     * somebody else's (a warden's given again meanwhile, a command's).
     */
    private void undarken(ServerPlayer player) {
        if (darknessEnd == NO_DARKNESS) {
            return;
        }
        MobEffectInstance current = player.getEffect(MobEffects.DARKNESS);
        if (current != null && AwakeningRules.ownDarkness(current.getDuration(), darknessEnd, level.getGameTime())) {
            player.removeEffect(MobEffects.DARKNESS);
        }
        darknessEnd = NO_DARKNESS;
        player.getPersistentData().remove(DARKNESS_MARK);
    }

    /**
     * For a player logging in who is no running Awakening's target: if the player was saved in an Awakening's Darkness
     * (the server crashed or was killed, so the Awakening could not take it away), that Darkness goes, but not a longer
     * one (somebody else's). Returns whether one was taken away.
     */
    static boolean clearLeftoverDarkness(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(DARKNESS_MARK)) {
            return false;
        }
        data.remove(DARKNESS_MARK);
        MobEffectInstance current = player.getEffect(MobEffects.DARKNESS);
        if (current == null || !current.endsWithin(AwakeningRules.DARKNESS_TICKS)) {
            return false;
        }
        player.removeEffect(MobEffects.DARKNESS);
        return true;
    }

    // ---- sync ----

    /**
     * Sends the state: on a phase change ({@code all}) to everyone who has it and every player near; otherwise only
     * to players near who do not have it yet.
     */
    private void sync(boolean all) {
        TremorAwakeningPayload payload = null;
        double reach = radius + SYNC_MARGIN;
        for (ServerPlayer player : level.players()) {
            boolean has = recipients.contains(player.getUUID());
            if (all ? has || near(player, reach) : !has && near(player, reach)) {
                if (payload == null) {
                    payload = new TremorAwakeningPayload(id, center, (float) radius, phase, clock.start(),
                            clock.ticks(), target, focus);
                }
                PacketDistributor.sendToPlayer(player, payload);
                recipients.add(player.getUUID());
            }
        }
    }

    /** Whether the player is within {@code reach} of the centre, horizontally. */
    private boolean near(ServerPlayer player, double reach) {
        return AwakeningRules.inZone(center, reach, vec(player.position()));
    }

    private static double seconds(long ticks) {
        return ticks / TICKS_PER_SECOND;
    }

    private static Vec3 vec(net.minecraft.world.phys.Vec3 v) {
        return new Vec3(v.x, v.y, v.z);
    }

    private static String text(Vec3 v) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", v.x(), v.y(), v.z());
    }
}
