package tremor.awakening;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import tremor.network.TremorNetwork;
import tremor.Tremor;
import tremor.config.TremorConfig;
import tremor.core.math.Vec3;
import tremor.core.shape.AwakeningParams;
import tremor.core.shape.AwakeningShape;
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
 * ended by {@link AwakeningManager}, ticked by it with the level. Not saved (an entity loaded as taken by one goes
 * deep, see {@link TremorManager#onLevelLoad}). The phases ({@link Phase}, as the clients get them):
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
 *   ends it when the target's event in the hollow ends ({@link End#EDGE_ESCAPED}), a defeat once the crater is
 *   there and the target dead or on the way out ({@link End#DEFEAT}). Without an outcome it ends with the target's
 *   event in the hollow, whatever ended that ({@link End#HOLLOW_OVER}). After a victory ({@link #won}) it stays until
 *   the hill is due ({@link AwakeningRules#emergeDelay}: risen when the victor is moved out).</li>
 *   <li>EMERGING, after a victory ({@link #emerge}; SPEC 9 "Победа", the way out mirrors the way in): at the swallow
 *   point (the focus) a hill rises over {@link AwakeningRules#emergeRiseTicks} ({@code awakening.emergeTicks}), as high
 *   as the one that swallowed the target, and stands. Everybody near sees it rise; the target, whose screen went dark
 *   in the hollow, is put into it there, by the move out of the hollow, and rooted at once ({@link #victorArrived}),
 *   so the target's screen comes back on the view it went dark on: the unreal blocks of the hill all around. It stands
 *   until the target's event in the hollow ends ({@link #settle}).</li>
 *   <li>SETTLING ({@link AwakeningRules#emergeSettleTicks}): the hill stands on until the target's screen has come
 *   back ({@link AwakeningRules#settleStart}: at once if the target is gone, logged out or dead), then settles, shaking
 *   off dust, and lets the target out: the root holds while the hill is over the target's eyes
 *   ({@link AwakeningRules#overEyes}) and lets go then (also as soon as the target dies, logs out or leaves the
 *   level). It ends ({@link End#VICTORY}) once the hill has settled, but not before the target has been sent the
 *   phase (so a slow client does not miss it), or is offline or away.</li>
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
        /** The soft ground pulled the target in: a crater at the swallow point (SPEC 9 "Поражение"). */
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
    /** The shape of the hills, as the clients draw them. */
    private static final AwakeningParams SHAPE = AwakeningParams.defaults();

    final int id;
    final ServerLevel level;
    /** The player it is about. */
    final UUID target;
    /** The target's player object at the start: another one with the same UUID is the target respawned. */
    private final ServerPlayer instance;
    final String targetName;
    /** Started by itself (the entity seeking in AWAKENING reached the target) rather than by {@code /tremor awaken}. */
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
    /**
     * The target moved out of the hollow into the hill of a victory and rooted there ({@link #victorArrived}), until
     * let go; null otherwise.
     */
    private ServerPlayer victor;
    /** The victor has been rooted in the hill ({@link #victorArrived}): never again in this Awakening. */
    private boolean victorRooted;
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
        return phase == Phase.HOLLOW || phase == Phase.EMERGING || phase == Phase.SETTLING
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
     * @param sound marks the start by the AWAKEN sound at the centre (when no entity made it just now by rising to
     *              AWAKENING: there was none, or it had been seeking in AWAKENING for more than a moment)
     */
    void begin(boolean sound) {
        if (sound) {
            level.playSound(null, center.x(), center.y(), center.z(), TremorSounds.AWAKEN.get(), SoundSource.HOSTILE,
                    TremorConfig.COMMON.transitionVolume.get().floatValue(), 1);
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
            // Settles once the target's event in the hollow ends (onHollowEnded).
            case EMERGING -> holdVictor(now);
            case SETTLING -> {
                holdVictor(now);
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
     * ({@link AwakeningRules#emergeDelay}: so that it has risen by then; at once if it already is): EMERGING begins then
     * ({@link #emerge}). The target is let go and out of the Darkness now, if still held (the victory came the tick it
     * was moved in).
     */
    void won(Vec3 at, long now) {
        if (phase != Phase.HOLLOW && phase != Phase.SWALLOWING) {
            return;
        }
        root.release();
        ServerPlayer player = player();
        if (player != null) {
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

    /**
     * EMERGING begins after a victory ({@link #won}): the hill rises at the focus now, over
     * {@link AwakeningRules#emergeRiseTicks}, and stands until it settles ({@link #settle}).
     */
    void emerge(long now) {
        emergeAt = NO_VICTORY;
        setPhase(Phase.EMERGING, now, AwakeningRules.emergeRiseTicks(TremorConfig.COMMON.awakening.emergeTicks.get()));
        Tremor.LOGGER.info("Awakening #{}: the hill at {} rises for {} to come out of", id, text(focus), targetName);
    }

    /**
     * The target's event in the hollow is over while the hill of EMERGING stands ({@link AwakeningManager}): SETTLING
     * begins. The hill settles once the target sees again ({@code screenComingBack}: the target came out the normal
     * way, and the screen comes back over {@code hollow.fadeTicks} from now), or at once if the target is gone (logged
     * out, died, brought out by a command), but not before it has risen ({@link AwakeningRules#settleStart}). The
     * clients learn of it now, the target's together with its screen coming back: until then the hill stands on.
     */
    void settle(long now, boolean screenComingBack) {
        if (phase != Phase.EMERGING) {
            return;
        }
        long start = AwakeningRules.settleStart(now, clock.start() + clock.ticks(),
                TremorConfig.COMMON.hollow.fadeTicks.get(), screenComingBack);
        int ticks = AwakeningRules.emergeSettleTicks(TremorConfig.COMMON.awakening.emergeTicks.get());
        setPhase(Phase.SETTLING, start, ticks);
        Tremor.LOGGER.info(String.format(Locale.ROOT, "Awakening #%d: the hill at %s settles in %.1f s, over %.1f s%s",
                id, text(focus), seconds(start - now), seconds(ticks), screenComingBack ? ""
                        : " (" + targetName + " did not come out the normal way)"));
    }

    /**
     * The target has just been moved into this level ({@link AwakeningManager#onPlayerChangedDimension}): coming out
     * of the hollow after a victory, it is in the hill at the swallow point, its screen still dark, and is rooted
     * there now, before its client can move it. Only before the hill settles, and only the first time.
     */
    void victorArrived(ServerPlayer player) {
        if (victorRooted || root.rooted() || player.level() != level || !player.isAlive()
                || !(phase == Phase.EMERGING || phase == Phase.HOLLOW && victoryPending())) {
            return;
        }
        victor = player;
        victorRooted = true;
        root.start(player);
        Tremor.LOGGER.info("Awakening #{}: {} is in the hill at {}, held", id, targetName,
                text(vec(player.position())));
    }

    /**
     * Once a tick while the hill of a victory is up: holds the rooted victor, or lets the victor go once the settling
     * hill is no longer over the victor's eyes ({@link AwakeningRules#overEyes}), or once the victor is gone (dead,
     * logged out or respawned, in another level).
     */
    private void holdVictor(long now) {
        if (victor == null) {
            return;
        }
        String gone = !victor.isAlive() ? "died" : victor != player() || victor.hasDisconnected() ? "logged out"
                : victor.level() != level ? "left the dimension" : null;
        if (gone != null) {
            root.release();
            victor = null;
            Tremor.LOGGER.info("Awakening #{}: {} {} in the hill, let go", id, targetName, gone);
            return;
        }
        if (phase == Phase.SETTLING && now >= clock.start()) {
            double peak = AwakeningShape.emergeSettle(SHAPE, (double) clock.elapsed(now) / clock.ticks());
            if (!AwakeningRules.overEyes(focus, vec(victor.position()), peak, SHAPE.hillSigma(),
                    victor.getEyeHeight())) {
                root.release();
                victor = null;
                Tremor.LOGGER.info(String.format(Locale.ROOT, "Awakening #%d: the hill is below the eyes of %s "
                        + "%.1f s into its settling, let go", id, targetName, seconds(clock.elapsed(now))));
                return;
            }
        }
        root.hold(victor);
    }

    /**
     * Whether the victor is done with the hill as far as the server goes: out of the hollow and sent the SETTLING
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
                TremorNetwork.sendToPlayer(receiver, ripple);
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
        tremor.hollow.Nightmare.clear(target); // whatever came of it, the next hollow is an ordinary one
        long now = level.getGameTime();
        root.release();
        victor = null;
        ServerPlayer player = player();
        if (player != null) {
            undarken(player);
        }
        TremorAwakeningPayload ended = new TremorAwakeningPayload(id, center, (float) radius, Phase.ENDED, now, 0,
                target, focus);
        // Got away (or called off): the long sound of the start breaks off at once and no sigh follows, so nothing
        // goes on rumbling after the player is out.
        boolean quiet = why == End.ESCAPED || why == End.CANCELLED;
        for (UUID receiver : recipients) {
            ServerPlayer online = level.getServer().getPlayerList().getPlayer(receiver);
            if (online != null) {
                TremorNetwork.sendToPlayer(online, ended);
                if (quiet) {
                    online.connection.send(new ClientboundStopSoundPacket(TremorSounds.AWAKEN.getId(),
                            SoundSource.HOSTILE));
                }
            }
        }
        recipients.clear();
        if (!quiet) {
            level.playSound(null, focus.x(), focus.y(), focus.z(), TremorSounds.SIGH.get(), SoundSource.HOSTILE,
                    TremorConfig.COMMON.transitionVolume.get().floatValue(), 1);
        }
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
            case EMERGING -> text.append(clock.over(now)
                    ? String.format(Locale.ROOT, "won: the hill at %s stands until %s comes out of the hollow",
                    text(focus), targetName)
                    : String.format(Locale.ROOT, "won: the hill at %s rises, %.1f s left", text(focus),
                    seconds(clock.remaining(now))));
            case SETTLING -> text.append(now < clock.start()
                    ? String.format(Locale.ROOT, "won: the hill at %s stands, settles in %.1f s", text(focus),
                    seconds(clock.start() - now))
                    : String.format(Locale.ROOT, "won: the hill at %s settles, %.1f s left", text(focus),
                    seconds(clock.remaining(now))));
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
        root.drag(player, TremorConfig.COMMON.awakening.buildupDrag.get()
                * Math.min(1, clock.elapsed(now) / (double) Math.max(1, clock.ticks())));
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
            root.release();
            if (player != null) {
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

    /** Goes into {@code phase}, starting at game time {@code start} (now, or a little ahead for SETTLING). */
    private void setPhase(Phase phase, long start, int ticks) {
        this.phase = phase;
        clock = new PhaseClock(start, ticks);
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
                TremorNetwork.sendToPlayer(player, payload);
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
