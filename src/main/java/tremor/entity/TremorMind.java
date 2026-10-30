package tremor.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import tremor.Tremor;
import tremor.awakening.AwakeningManager;
import tremor.awakening.AwakeningRules;
import tremor.config.Difficulty;
import tremor.config.TremorConfig;
import tremor.core.behavior.Ambush;
import tremor.core.behavior.AngerMeter;
import tremor.core.behavior.BehaviorParams;
import tremor.core.behavior.Brain;
import tremor.core.behavior.BrainWorld;
import tremor.core.behavior.ContactZone;
import tremor.core.behavior.Decision;
import tremor.core.behavior.DespawnClock;
import tremor.core.behavior.KeepAway;
import tremor.core.behavior.Seeking;
import tremor.core.behavior.Stage;
import tremor.core.behavior.SurfacePicker;
import tremor.core.math.Vec3;
import tremor.core.motion.Crawler;
import tremor.core.motion.MotionParams;
import tremor.core.path.Path;
import tremor.core.voxel.LineOfSight;
import tremor.network.TremorStatePayload;
import tremor.sound.TremorSounds;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Stage behaviour of the entity on the server (SPEC 8, 5.6, 11, 13): the anger and its stage ({@link AngerMeter}),
 * the {@link Brain} and the answers to its questions ({@link BrainWorld} over the runtime's surface graph), the
 * ground ripple of a freeze, contact with players and leaving. One per entity, owned by its {@link TremorRuntime},
 * which carries out the brain's orders (routes, stopping). Transient: the anger, the stage, the switches and the
 * despawn clock are saved with the {@link TremorEntity}; the brain and the seeking start afresh after a load. Server
 * thread only.
 * <ul>
 * <li><b>Anger</b>: a heard vibration adds {@code perceived * angerPerLoudness} plus its bonus ({@link #heard});
 * every tick the anger decays, faster once nothing was heard for a while ({@link AngerMeter#tick}; the time since the
 * entity's saved {@code lastHeard}, so a load does not reset it). Every stage change is marked by a sound (SPEC 13,
 * {@link StageTransition}) and sent to the players at once. The {@code behavior} config is read every tick; when it
 * changes the meter (keeping anger and stage) and the brain are built anew.</li>
 * <li><b>Seeking</b> (SPEC 8 AWAKENING; {@link Seeking}): at the top of the anger the entity seeks a player: its brain
 * goes for the last heard sound as when HUNTING (with the AWAKENING speed and bump height), searching only
 * {@code awakening.searchRadius} around it; with nothing to go for it roams as far from every player who can be taken
 * as when DORMANT, on ways that pass none of them within reach ({@link #keepAway}; the route the body plans for such a
 * leg is tested too, {@link #wayClear}), so a player who gets quietly away from the noise is not found by chance. The
 * anger stays at the top (no decay), and the Awakening starts once its bump has reached a player
 * ({@link AwakeningManager} checks that after each tick and takes it, {@link #absorb}). If that has not happened
 * {@code awakening.seekSeconds} after it entered AWAKENING (or a command set AWAKENING again), it calms down to
 * HUNTING at {@link Seeking#calmAnger} (the stage change sighs), and the anger decays from there. Not saved: an
 * entity loaded in AWAKENING was seeking (one an Awakening had taken goes deep at load,
 * {@link TremorManager#onLevelLoad}), and it is HUNTING at the calm-down anger at once.</li>
 * <li><b>Brain</b> ({@link #think}): fed with the sounds heard since the last tick, ticked once per server tick
 * before the move, with the stage after this tick's anger; its decisions go through {@link BrainOrders} to the body.
 * Its wander legs end {@code behavior.minWanderDistance} from the players while DORMANT and seeking, and while ALERT
 * or HUNTING only with {@code behavior.wanderKeepAway} (SPEC 5.6; otherwise those are random, {@link #keepAway}).
 * Not while the brain is switched off ({@code /tremor ai off}) or the entity leaves. A {@code /tremor goto}
 * suspends its decisions, while it still hears and ticks (with "not idle"). A freeze ripples the ground, unless a
 * ripple is still running or the entity dives ({@link RippleTimer}).</li>
 * <li><b>Stage motion</b> ({@link #motion}): speed and bump height times the stage's factors (SPEC 5.5, 8); the
 * crawler smooths the height change.</li>
 * <li><b>Contact</b> (SPEC 8 HUNTING, also AWAKENING; {@link #afterMove}): a player whose body touches the bump
 * ({@link ContactZone}: in its zone and not behind rock), while the bump is at least half up and not diving, is
 * struck (once per cooldown): damage of type {@code tremor:tremor}, thrown away ({@link Strike}), more anger, and the
 * brain hears the player.</li>
 * <li><b>Leaving</b> (SPEC 11, only a naturally spawned entity): once no player was near for {@code farSeconds},
 * or nothing happened (no heard sound, stage DORMANT) for {@code quietSeconds} ({@link DespawnClock}), the entity
 * drops its target and its bump sinks; once it is down (or after {@value #MAX_LEAVING_TICKS} ticks) the runtime
 * removes it. While its chunk is not loaded the clock runs on, and the runtime removes it at once when it is due
 * ({@link #pausedTick}). Any entity leaves when another came too near and this one gives way ({@link #yieldTo}): it
 * turns away and crawls off as its bump goes down, more slowly.</li>
 * <li><b>Ambush</b> ({@link Ambush}; {@link #lookForPrey}): in ALERT or HUNTING, on a floor where quicksand can form
 * ({@code tremor.awakening.Quicksand#fits}), every
 * {@value #LOOK_TICKS} ticks it looks for a player who could be taken and is coming while nobody sees the bump; at
 * {@code ambush.chance} it lies in wait there ({@link #lurk}): the brain waits, the body stands, the bump is low
 * ({@link Ambush#HEIGHT}) and the clients draw it flat, wide and breathing ({@link #lurkShare}). A mob that steps onto
 * it sinks ({@link Devour}); a player who could be taken springs it ({@link #spring}): the quicksand takes everyone
 * on it, each into a hollow of their own ({@link AwakeningManager#ambush}), and the entity with them; its bump keeps
 * the look of lying in wait while the quicksand pulls ({@link #ambushLook}). Lying in wait, what it hears does not
 * bring it to the top of its anger. It gives up
 * after {@code ambush.lurkSeconds}, with nobody near, calmed down or risen to AWAKENING, its brain switched off, a
 * frenzy or a goto; then it does not lie in wait for {@code ambush.cooldownSeconds}. Digging into where it lies in
 * wait enrages it ({@link #groundBroken}). Not saved.</li>
 * <li><b>Awakening</b> (SPEC 9; {@link #absorb}): taken by an Awakening, the entity is the whole area rather than a
 * bump: its brain is suspended, the anger stays at the top, the bump sinks, it strikes nobody, does not leave by
 * itself and hears nothing ({@code awakening}). Until it is removed: every Awakening ends with that
 * ({@link tremor.awakening.AwakeningManager}). That it was taken is saved with it ({@link TremorEntity#absorbed}).</li>
 * </ul>
 */
final class TremorMind {
    /** The damage type of a strike ({@code data/tremor/damage_type/tremor.json}). */
    static final ResourceKey<DamageType> DAMAGE_TYPE = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "tremor"));
    /** The nearest player within this distance is the one wandering shows the bump to (SPEC 5.6). */
    static final double VIEWER_RANGE = 128;
    /** How far up and down from the entity a wander target is looked for in its column. */
    static final int WANDER_VERTICAL_RANGE = 8;
    /**
     * Candidates tried for a wander target: each a column scan and at most one line of sight. Many, so that one seen
     * by the player is found even in a cave, where most points around are out of sight (SPEC 5.6).
     */
    static final int WANDER_ATTEMPTS = 48;
    /** Candidates tried for a search target: each a column scan. */
    static final int SEARCH_ATTEMPTS = 24;
    /**
     * While the entity seeks, the way of a roam leg passes no player who can be taken nearer than
     * {@code awakening.reachDistance} plus this horizontally ({@link #keepAway}): the bump's route through the node
     * centres is smoothed, and it is tested at points up to {@value KeepAway#STEP} blocks apart...
     */
    static final double PASS_MARGIN = 2;
    /** ...where it is at most {@link AwakeningRules#REACH_HEIGHT} plus this above or below the player's feet. */
    static final double PASS_MARGIN_HEIGHT = 1;
    /** Volume of the sound of a ring of ripples ({@link TremorSounds#RIPPLE}): heard up to 16 blocks times this away. */
    static final float RIPPLE_VOLUME = 2;
    /** How often (ticks) {@link #alone} looks for players again. */
    private static final int ALONE_CHECK_TICKS = 20;
    /** A leaving entity is removed once its bump is lower than this (blocks)... */
    static final double SUNK = 0.02;
    /** ...or this many ticks after it started leaving, whatever its bump does. */
    static final int MAX_LEAVING_TICKS = 200;
    /** An entity giving way to another sinks over this long (s), crawling off the other... */
    static final double YIELD_SECONDS = 6;
    /** ...towards a point this far off (blocks), away from the other. */
    static final double YIELD_DISTANCE = 24;
    /** How loud a struck player is to the brain: louder than anything heard, so it goes straight for the player. */
    static final double STRIKE_LOUDNESS = 10;
    private static final float STRIKE_VOLUME = 1.5f;
    /** Pitch of the rumble under the crack of a change to HUNTING. */
    private static final float LOW_PITCH = 0.7f;
    /**
     * Let go by its ambush, it hunts the player who got away for this long without rising to the top of its anger
     * (ticks): a chase, not an Awakening at once.
     */
    static final int CHASE_TICKS = 300;
    /** Ticks between two looks for a player to lie in wait for ({@link #lookForPrey}). */
    static final int LOOK_TICKS = 10;
    /** After a chance not taken it looks again this long later (ticks). */
    static final int RETRY_TICKS = 200;

    /** The {@code behavior} config values last rejected (logged once), or null. */
    private static String rejected;

    private final TremorRuntime runtime;
    private final ServerLevel level;
    private final TremorEntity entity;
    private final BrainWorld world = new World();
    private final BrainOrders orders = new BrainOrders();
    /** Game time of each player's last strike. */
    private final Map<UUID, Long> strikes = new HashMap<>();
    /** The ground ripple of a freeze. */
    private final RippleTimer ripple = new RippleTimer();
    /** The seeking of the AWAKENING stage; its clock is ticked only while no Awakening has taken the entity. */
    private final Seeking seeking = new Seeking();

    private BehaviorParams params;
    private AngerMeter meter;
    private Brain brain;
    /** A vibration was heard since the last tick (eventful for the despawn clock). */
    private boolean heardSinceTick;
    private int leavingTicks;
    /** {@link #alone}: what it found, and when it looked. */
    private boolean alone;
    private long aloneCheckedAt = Long.MIN_VALUE / 2;
    /**
     * Its anger is held where it is ({@code /tremor hold}, a route for filming, {@link Routes}): it neither decays nor
     * grows, so its look stays; it still hears and does what its stage does (a route has its ai off). Not saved.
     */
    private boolean angerHeld;
    /** Ticks it has been giving way to another entity ({@link #yieldTo}), or -1 if it is not. Not saved. */
    private int yieldTicks = -1;
    /** Where it lies in wait (the top of its skin), or null ({@link Ambush}). Not saved. */
    private Vec3 lurkAt;
    /** Game time it lay in wait at. */
    private long lurkSince;
    /** It lies in wait by a command ({@link #lurkNow}): it does not give up for nobody being near. */
    private boolean lurkForced;
    /** Game time before which it does not lie in wait (again). */
    private long lurkAgain;
    /** How far its bump has gone into lying in wait, 0..1, for the clients ({@link Ambush#fade}). */
    private double lurkShare;
    /**
     * It sprang its ambush and the quicksand still pulls somebody in ({@link AwakeningManager#pullingWith}): taken, it
     * keeps the look of lying in wait meanwhile (low, wide, breathing), and its stage changes without a sound.
     */
    private boolean ambushLook;
    /** Game time before which it strikes nobody: let go by an ambush somebody got out of ({@link #freeFromAmbush}). */
    private long graceUntil;
    /** Game time before which its anger does not reach the top: the chase after an escape ({@link #freeFromAmbush}). */
    private long chaseUntil;
    /** Where the feet of each player who could be taken were at the last look ({@link #lookForPrey}). */
    private final Map<UUID, Vec3> feetBefore = new HashMap<>();
    /** A player out of reach on a pillar: guarded from below, stalactites dropped from the ceiling. */
    private final Perch perch;

    TremorMind(TremorRuntime runtime, TremorEntity entity) {
        this.runtime = runtime;
        this.level = runtime.level();
        this.entity = entity;
        this.perch = new Perch(runtime, entity);
        this.params = configParams();
        if (entity.stage() == Stage.AWAKENING && !entity.absorbed()) {
            // Loaded while seeking (a new entity is DORMANT): the seeking is not saved, it calms down at once.
            this.meter = new AngerMeter(params, Seeking.calmAnger(params), Stage.HUNTING);
            Tremor.LOGGER.info(String.format(Locale.ROOT, "Tremor #%d in %s was saved seeking a player in AWAKENING: "
                    + "it hunts on at anger %.0f", entity.instance(), level.dimension().location(), meter.anger()));
        } else {
            this.meter = new AngerMeter(params, entity.anger(), entity.stage());
        }
        // A loaded entity takes the stage the meter reconciles quietly: nothing changed for the players.
        entity.setAnger((float) meter.anger());
        entity.setStage(meter.stage());
        seeking.stage(meter.stage());
        this.brain = newBrain();
    }

    TremorEntity entity() {
        return entity;
    }

    /** {@code player} made a sound at {@code at} that the entity heard. */
    void heardPlayer(UUID player, Vec3 at, long now) {
        perch.heard(player, at, now);
    }

    /** The entity is gone: what it sent out goes with it. */
    void discard() {
        perch.discard();
    }

    /**
     * A vibration the entity heard (SPEC 7.3, 8): adds {@code anger} to the meter and tells the brain (unless it is
     * off). Nothing while the entity leaves.
     *
     * @return what the entity makes of it, for the hearing debug view: {@code ignores it} (DORMANT, too quiet),
     * {@code investigates}, {@code freezes}, {@code hunts}, {@code seeks} (AWAKENING), or why nothing follows
     * ({@code ai off}, {@code goto under way}, {@code leaving}, {@code awakening})
     */
    String heard(Vec3 source, double perceived, double anger) {
        if (entity.leaving()) {
            return "leaving";
        }
        if (entity.absorbed()) {
            return "awakening";
        }
        heardSinceTick = true;
        addAnger(anger); // held, it stays where it is: the look stays, but it still hears and reacts as its stage says
        if (entity.frenzy()) {
            return "frenzy";
        }
        if (!entity.aiEnabled()) {
            return "ai off";
        }
        brain.hear(source, perceived);
        if (entity.targetKind() == TremorEntity.TargetKind.MANUAL) {
            return "goto under way";
        }
        if (lurkAt != null) {
            return "lies in wait";
        }
        if (perch.guarding()) {
            return "guards";
        }
        return switch (meter.stage()) {
            case DORMANT -> perceived >= params.dormantReactLoudness() ? "investigates" : "ignores it";
            case ALERT -> "freezes";
            case HUNTING -> "hunts";
            case AWAKENING -> "seeks";
        };
    }

    /** Whether no player (not a spectator) is within {@value #VIEWER_RANGE} blocks; looked at once a second. */
    private boolean alone(long now) {
        if (now - aloneCheckedAt >= ALONE_CHECK_TICKS || now < aloneCheckedAt) {
            aloneCheckedAt = now;
            Vec3 at = entity.crawler().position();
            alone = true;
            for (ServerPlayer player : level.players()) {
                if (!player.isSpectator()
                        && player.distanceToSqr(at.x(), at.y(), at.z()) <= VIEWER_RANGE * VIEWER_RANGE) {
                    alone = false;
                    break;
                }
            }
        }
        return alone;
    }

    /**
     * The first part of a tick, before the move: the config is taken in, the anger decays (in AWAKENING the seeking
     * runs instead, and once it has run out the entity calms down), and the brain decides with what it heard since the
     * last tick; the body carries out the order.
     */
    void think(long now) {
        BehaviorParams current = configParams();
        if (!current.equals(params)) {
            params = current; // config reload
            Stage before = entity.stage();
            meter = new AngerMeter(params, meter.anger(), meter.stage());
            settle(before);
            brain = newBrain();
            orders.clear();
        }
        if (entity.leaving() || entity.absorbed()) {
            return; // its anger no longer matters, or it stays at the top
        }
        Stage before = entity.stage();
        if (angerHeld) {
            // A route for filming: its look stays as it was set.
        } else if (meter.stage() == Stage.AWAKENING) {
            seeking.stage(Stage.AWAKENING); // running since the entity got there (settle)
            if (seeking.tick(TremorRuntime.TICK_SECONDS, TremorConfig.COMMON.awakening.seekSeconds.get())) {
                calmDown();
            }
        } else {
            meter.tick(TremorRuntime.TICK_SECONDS, secondsSinceHeard(now));
        }
        settle(before);
        if (lurkAt != null) {
            String over = lurkOver(now);
            if (over == null) {
                if (entity.crawler().speed() > 0) {
                    lurkAt = skin(); // still braking: it lies where it comes to a stand
                }
                return; // lying in wait: the brain waits too
            }
            stopLurking(now, over);
        }
        boolean free = entity.aiEnabled() && !entity.frenzy()
                && entity.targetKind() != TremorEntity.TargetKind.MANUAL;
        if (perch.guard(now, meter.stage(), free)) {
            return; // guarding a player out of reach on a pillar: the brain waits
        }
        if (!entity.aiEnabled() || entity.frenzy()) {
            return;
        }
        if (meter.stage() == Stage.DORMANT && entity.targetKind() != TremorEntity.TargetKind.MANUAL && alone(now)) {
            return; // asleep with nobody near: no wandering, no paths to find (a nest of them would cost for nothing)
        }
        if (now % LOOK_TICKS == 0) {
            lookForPrey(now);
            if (lurkAt != null) {
                return;
            }
        }
        boolean manual = entity.targetKind() == TremorEntity.TargetKind.MANUAL;
        boolean idle = !manual && !orders.waiting() && runtime.bodyIdle(entity);
        Decision decision = brain.tick(TremorRuntime.TICK_SECONDS, meter.stage(), entity.crawler().position(), idle,
                world);
        BrainOrders.Order order = orders.next(decision, brain.lastHeardLoudness(), manual,
                perceived -> runtime.mayRetarget(entity, now, perceived));
        switch (order.type()) {
            case GO -> runtime.brainGo(entity, order.point(),
                    order.sound() ? TremorEntity.TargetKind.SOUND : TremorEntity.TargetKind.ROAM, order.perceived(),
                    order.wander());
            case FREEZE -> {
                runtime.freeze(entity, order.point());
                if (ripple.freeze(now, entity.crawler().diving())) {
                    play(TremorSounds.RIPPLE, skin(), RIPPLE_VOLUME, 1);
                }
            }
            case NONE -> {
            }
        }
    }

    /**
     * This tick's motion: the entity's parameters with the stage's speed and bump height (SPEC 5.5, 8); the bump of a
     * leaving entity, or of one taken by an Awakening, sinks to nothing (slowly while it gives way to another, at the
     * speed of a hunt).
     */
    MotionParams motion() {
        MotionParams base = entity.params().motionParams();
        Stage stage = meter.stage();
        boolean yielding = yieldTicks >= 0;
        double amplitude = entity.absorbed() ? (ambushLook ? Ambush.HEIGHT : 0) : entity.leaving() && !yielding ? 0
                : base.amplitude() * TremorConfig.COMMON.amplitudeFactor(stage) * (yielding ? yieldShare() : 1);
        if (lurkAt != null) {
            amplitude = Math.min(amplitude, Ambush.HEIGHT); // lying in wait: low (the clients draw it wide)
        }
        // Giving way, it hurries off whatever its stage.
        double speed = base.maxSpeed() * TremorConfig.COMMON.speedFactor(yielding ? Stage.HUNTING : stage)
                * (level.dimension() == net.minecraft.world.level.Level.NETHER
                ? TremorConfig.COMMON.netherSpeedFactor.getAsDouble() : 1)
                * TremorConfig.COMMON.depth(level, entity.crawler().position().y(),
                TremorConfig.COMMON.depthSpeedFactor);
        double acceleration = base.acceleration();
        if (entity.frenzy()) {
            amplitude *= TremorConfig.COMMON.frenzyAmplitudeFactor.getAsDouble();
            speed *= TremorConfig.COMMON.frenzySpeedFactor.getAsDouble();
            acceleration *= TremorConfig.COMMON.frenzySpeedFactor.getAsDouble();
        }
        return new MotionParams(speed, acceleration,
                base.normalSmoothingSeconds(), amplitude, base.amplitudeSmoothingSeconds());
    }

    /**
     * The second part of a tick, after the move: contact with players, the despawn clock and leaving.
     *
     * @return true once a leaving entity is gone: the runtime removes it
     */
    boolean afterMove(long now) {
        perch.tick(now, motion());
        boolean heard = heardSinceTick;
        heardSinceTick = false;
        if (ambushLook && !AwakeningManager.pullingWith(level, entity.instance())) {
            ambushLook = false; // everybody of its quicksand is under: the look of lying in wait goes
        }
        lurkShare = Ambush.fade(lurkShare, lurkAt != null || ambushLook, TremorRuntime.TICK_SECONDS);
        if (entity.absorbed()) {
            return false; // the Awakening removes it
        }
        if (lurkAt != null && !entity.leaving() && spring(now)) {
            return false; // sprung: the Awakenings of the ambush took it
        }
        if (entity.leaving()) {
            lurkAt = null;
            if (yieldTicks >= 0) {
                yieldTicks++;
            }
            return Math.abs(entity.crawler().amplitude()) < SUNK || ++leavingTicks >= MAX_LEAVING_TICKS;
        }
        Stage stage = meter.stage();
        if (stage == Stage.HUNTING || stage == Stage.AWAKENING) {
            strike(now);
        }
        if (stage != Stage.DORMANT
                && (TremorConfig.COMMON.devourMobs.get() || TremorConfig.COMMON.devourItems.get())) {
            devour();
        }
        if (entity.natural()) {
            TremorConfig.Common config = TremorConfig.COMMON;
            DespawnClock clock = entity.despawnClock();
            clock.tick(TremorRuntime.TICK_SECONDS, playerWithin(config.despawnPlayerDistance.get()),
                    heard || meter.stage() != Stage.DORMANT);
            if (clock.shouldLeave(config.despawnFarSeconds.get(), config.despawnQuietSeconds.get())) {
                leave(clock);
            }
        }
        return false;
    }

    /**
     * A tick while the entity's chunk is not loaded, when the runtime simulates nothing (SPEC 11): the despawn clock of
     * a naturally spawned entity runs on, with nothing happening (it hears nothing then); a player within
     * {@code despawnPlayerDistance} still counts as near, as the chunks around a player may be unloaded with a short
     * view distance. Once the clock says it leaves (or it was leaving already) it is gone at once: nobody can see it
     * sink, and while it is there no other entity spawns naturally in the dimension.
     *
     * @return true once the entity is gone: the runtime removes it
     */
    boolean pausedTick() {
        if (!entity.natural() || entity.absorbed()) {
            return false;
        }
        if (entity.leaving()) {
            return true;
        }
        TremorConfig.Common config = TremorConfig.COMMON;
        DespawnClock clock = entity.despawnClock();
        clock.tick(TremorRuntime.TICK_SECONDS, playerWithin(config.despawnPlayerDistance.get()), false);
        if (!clock.shouldLeave(config.despawnFarSeconds.get(), config.despawnQuietSeconds.get())) {
            return false;
        }
        Tremor.LOGGER.info(String.format(Locale.ROOT, "Tremor #%d in %s leaves while its chunk is not loaded: no "
                        + "player near for %.0f s, nothing happening for %.0f s", entity.instance(),
                level.dimension().location(), clock.farSeconds(), clock.quietSeconds()));
        return true;
    }

    /** Ticks since the latest ripple started, as the state packet carries it ({@link TremorStatePayload#rippleAge}). */
    int rippleAge(long now) {
        return ripple.age(now);
    }

    /**
     * Debug ({@code /tremor anger}): sets the anger and the stage it implies; a stage change is marked as usual. At the
     * top (AWAKENING) the seeking starts afresh.
     */
    void setAnger(double anger) {
        Stage before = entity.stage();
        meter.set(anger);
        settle(before);
        seekAfresh();
    }

    /**
     * Debug ({@code /tremor stage}): jumps to the stage at its threshold anger; the change is marked as usual.
     * AWAKENING starts the seeking afresh.
     */
    void forceStage(Stage stage) {
        Stage before = entity.stage();
        meter.forceStage(stage);
        settle(before);
        seekAfresh();
    }

    /** Holds the anger where it is, or lets it go again ({@link #angerHeld}). */
    void holdAnger(boolean held) {
        angerHeld = held;
    }

    boolean angerHeld() {
        return angerHeld;
    }

    /**
     * A listening stop of a route ({@link Routes}): it stands where it is and sends a ring of ripples through the
     * ground, with its sound, as when it freezes to listen (SPEC 8 ALERT); none while it dives or one still runs.
     */
    void listenNow() {
        long now = level.getGameTime();
        Vec3 at = entity.crawler().position();
        Vec3 ahead = entity.crawler().forward();
        runtime.freeze(entity, new Vec3(at.x() + ahead.x(), at.y() + ahead.y(), at.z() + ahead.z()));
        if (ripple.freeze(now, entity.crawler().diving())) {
            play(TremorSounds.RIPPLE, skin(), RIPPLE_VOLUME, 1);
        }
    }

    /** {@code /tremor ai on|off}: switched back on, the brain starts afresh (it heard nothing meanwhile). */
    void setAi(boolean on) {
        if (on && !entity.aiEnabled()) {
            brain = newBrain();
        }
        entity.setAiEnabled(on);
        orders.clear();
    }

    /** The body was stopped or sent elsewhere by a command: what the brain ordered is forgotten. */
    void clearOrders() {
        orders.clear();
    }

    /**
     * Taken by an Awakening (SPEC 9: "сущность перестаёт быть бугром и становится всей округой"): the anger goes to
     * the top (the stage to AWAKENING, with its sound if it was not there yet) and stays there, the brain is
     * suspended, the bump sinks, and the entity neither strikes nor leaves by itself. A leaving entity stops leaving.
     */
    void absorb() {
        lurkAt = null;
        entity.setAbsorbed(true);
        orders.clear();
        entity.setLeaving(false);
        Stage before = entity.stage();
        meter.forceStage(Stage.AWAKENING);
        settle(before);
    }

    /** Whether an Awakening took the entity ({@link #absorb}). */
    boolean absorbed() {
        return entity.absorbed();
    }

    /**
     * Whether the entity listens hard: ALERT and frozen toward a sound it heard ({@link Brain#frozen}), its ring of
     * ripples running out. Vibrations meanwhile reach it {@code behavior.alertListenFactor} times louder: whoever
     * moves while the ring passes gives themselves away.
     */
    boolean listening() {
        return brain != null && entity.aiEnabled() && entity.stage() == Stage.ALERT && brain.frozen();
    }

    /**
     * Whether a route the body has planned for a wander leg of the brain keeps away from the players
     * ({@link KeepAway#passesClear(Path)} with {@link #keepAway}): while the entity seeks, it passes none who can be
     * taken within reach; in any other stage every route does. One that does not is given up.
     */
    boolean wayClear(Path path) {
        return meter.stage() != Stage.AWAKENING || keepAway(0).passesClear(path);
    }

    /**
     * What the behaviour is doing, e.g. {@code hunting: searching, 12 s left}; while seeking (AWAKENING) also how long
     * until it calms down and the sound it goes for, e.g. {@code awakening: going for the sound; seeking a player
     * (reach 8 blocks), calms down in 23 s; the sound at (12.50, 64.50, -3.50)}.
     */
    String describe() {
        if (entity.absorbed()) {
            return "awakening: it is the whole area, not a bump";
        }
        if (entity.leaving()) {
            return yieldTicks >= 0 ? "giving way to another tremor: turning away and sinking" : "leaving";
        }
        if (entity.frenzy()) {
            return "frenzy: going for the shard or for whoever dropped it";
        }
        if (!entity.aiEnabled()) {
            return "ai off" + seekingState();
        }
        if (lurkAt != null) {
            return String.format(Locale.ROOT, "lies in wait (an ambush) for %.0f s", (level.getGameTime() - lurkSince)
                    / 20.0) + seekingState();
        }
        String state = brain.describe();
        if (orders.waiting()) {
            state += " (waiting for the retarget cooldown)";
        } else if (entity.targetKind() == TremorEntity.TargetKind.MANUAL) {
            state += " (suspended by a goto)";
        }
        return state + seekingState();
    }

    /** The seeking for {@link #describe}, starting with "; ", or nothing while the entity does not seek. */
    private String seekingState() {
        if (!seeking.running()) {
            return "";
        }
        TremorConfig.Awakening config = TremorConfig.COMMON.awakening;
        Vec3 sound = brain.lastHeard();
        return String.format(Locale.ROOT, "; seeking a player (reach %.0f blocks), calms down in %.0f s; %s",
                config.reachDistance.get(), Math.ceil(seeking.secondsLeft(config.seekSeconds.get())),
                sound == null ? "no sound heard yet" : String.format(Locale.ROOT, "the sound at (%.2f, %.2f, %.2f)",
                        sound.x(), sound.y(), sound.z()));
    }

    // ---- seeking ----

    /** A command set the anger to the top again: the seeking starts afresh (it does for a new AWAKENING anyway). */
    private void seekAfresh() {
        if (meter.stage() == Stage.AWAKENING && !entity.absorbed()) {
            seeking.restart();
        }
    }

    /**
     * The seeking has run out (SPEC 8: "Не нашла никого за ~30–40 с — успокаивается до HUNTING"): the anger drops to
     * {@link Seeking#calmAnger}, inside HUNTING; the caller settles the stage change (the ground sighs).
     */
    private void calmDown() {
        meter.set(Seeking.calmAnger(params));
        Tremor.LOGGER.info(String.format(Locale.ROOT, "Tremor #%d in %s reached nobody within %d s in AWAKENING: it "
                        + "calms down to %s at anger %.0f", entity.instance(), level.dimension().location(),
                TremorConfig.COMMON.awakening.seekSeconds.get(), meter.stage().name(), meter.anger()));
    }

    // ---- anger ----

    private void addAnger(double amount) {
        if (angerHeld) {
            return; // its look is held: the anger stays where it is, whatever it hears or strikes
        }
        if (lurkAt != null || level.getGameTime() < chaseUntil) {
            // Lying in wait it keeps still: the player it hears coming is what it waits for, not what makes it rise;
            // after an escape it chases, without rising to the top of its anger at once either.
            amount = Math.min(amount, Math.max(0, params.awakenAt() - 1 - meter.anger()));
        }
        Stage before = entity.stage();
        meter.add(amount);
        settle(before);
    }

    /**
     * Writes the meter to the entity; a stage change is marked by a sound (SPEC 8, 13) and sent to the players.
     * Entering AWAKENING starts the seeking, leaving it stops it.
     */
    private void settle(Stage before) {
        entity.setAnger((float) meter.anger());
        Stage stage = meter.stage();
        seeking.stage(stage);
        if (stage == before) {
            return;
        }
        entity.setStage(stage);
        if (ambushLook) {
            runtime.stageChanged(entity); // taken by its ambush: the quicksand is its sound, no roar
            return;
        }
        float volume = (float) TremorConfig.COMMON.transitionVolume.getAsDouble();
        switch (StageTransition.of(before, stage)) {
            case RUMBLE -> play(TremorSounds.RUMBLE, skin(), volume, 1);
            case CRACK -> {
                play(TremorSounds.CRACK, skin(), volume, 1);
                play(TremorSounds.RUMBLE, skin(), volume, LOW_PITCH);
            }
            // A roar, heard far: the pull of an Awakening sounds when one starts, at the player it takes.
            case AWAKEN -> play(TremorSounds.RAGE, skin(), volume, 1);
            case SIGH -> play(TremorSounds.SIGH, skin(), volume, 1);
            case NONE -> {
            }
        }
        runtime.stageChanged(entity);
    }

    /** Seconds since the entity last heard a sound (its saved {@code lastHeard}); infinite if it never did. */
    private double secondsSinceHeard(long now) {
        TremorEntity.Heard heard = entity.lastHeard();
        return heard == null ? Double.POSITIVE_INFINITY : Math.max(0, now - heard.gameTime()) * TremorRuntime.TICK_SECONDS;
    }

    /** The {@code behavior} config, or the defaults (logged once) if its thresholds contradict each other. */
    private static BehaviorParams configParams() {
        try {
            BehaviorParams params = TremorConfig.COMMON.behaviorParams();
            rejected = null;
            return params;
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().equals(rejected)) {
                rejected = e.getMessage();
                Tremor.LOGGER.warn("Tremor behaviour config is inconsistent ({}): using the defaults", e.getMessage());
            }
            return BehaviorParams.defaults();
        }
    }

    /** Seeded from the instance: the same entity decides the same way for the same input. */
    private Brain newBrain() {
        return new Brain(params, entity.instance());
    }

    // ---- contact ----

    /**
     * Strikes every player the bump touches (SPEC 8, {@link ContactZone} over the live world), each at most once per
     * cooldown. Only players within {@link ContactZone#reach} are tested.
     */
    private void strike(long now) {
        if (now < graceUntil) {
            return; // let go by its ambush a moment ago: not at once at whoever got away
        }
        Crawler crawler = entity.crawler();
        double amplitude = crawler.amplitude();
        double full = entity.params().get(Param.AMPLITUDE) * TremorConfig.COMMON.amplitudeFactor(meter.stage());
        if (crawler.diving() || amplitude < full / 2) {
            return;
        }
        TremorConfig.Common config = TremorConfig.COMMON;
        int cooldown = config.contactCooldownTicks.get();
        if (!strikes.isEmpty()) {
            strikes.values().removeIf(last -> now - last >= cooldown || now < last);
        }
        double radius = config.contactRadius.get();
        Vec3 center = crawler.position();
        for (ServerPlayer player : level.players()) {
            double height = player.getBbHeight();
            double reach = ContactZone.reach(amplitude, radius, height);
            if (player.isSpectator() || player.isCreative() || !player.isAlive()
                    || tremor.awakening.AwakeningManager.taken(player) // another one's prey
                    || player.distanceToSqr(center.x(), center.y(), center.z()) > reach * reach
                    || strikes.containsKey(player.getUUID())) {
                continue;
            }
            Vec3 feet = vec(player.position());
            if (ContactZone.touches(runtime.liveView(), center, crawler.normal(), amplitude, feet, height, radius)) {
                strikes.put(player.getUUID(), now);
                hit(player, feet);
            }
        }
    }

    /**
     * Takes every animal or monster the bump touches ({@link Devour}; {@link ContactZone} over the live world, the bump
     * at least half up and not diving, as for a strike). Not while DORMANT: the lazy bump passes under them.
     */
    private void devour() {
        Crawler crawler = entity.crawler();
        double amplitude = crawler.amplitude();
        double full = entity.params().get(Param.AMPLITUDE) * TremorConfig.COMMON.amplitudeFactor(meter.stage());
        if (crawler.diving() || amplitude < full / 2) {
            return;
        }
        // The taller the bump, the wider it is: what it takes in grows with it (up to two and a half times).
        double radius = TremorConfig.COMMON.contactRadius.get() * Math.max(1, Math.min(2.5, amplitude / 2.0));
        Vec3 center = crawler.position();
        double reach = ContactZone.reach(amplitude, radius, 3);
        AABB box = new AABB(center.x() - reach, center.y() - reach, center.z() - reach, center.x() + reach,
                center.y() + reach, center.z() + reach);
        if (TremorConfig.COMMON.devourMobs.get()) {
            for (Mob mob : level.getEntitiesOfClass(Mob.class, box, m -> Devour.takeable(level, m))) {
                if (ContactZone.touches(runtime.liveView(), center, crawler.normal(), amplitude, vec(mob.position()),
                        mob.getBbHeight(), radius)) {
                    Devour.take(level, mob);
                }
            }
        }
        if (TremorConfig.COMMON.devourItems.get()) {
            for (net.minecraft.world.entity.item.ItemEntity item : level.getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class, box, Devour::takeable)) {
                if (ContactZone.touches(runtime.liveView(), center, crawler.normal(), amplitude, vec(item.position()),
                        item.getBbHeight(), radius)) {
                    Devour.take(level, item);
                }
            }
        }
    }

    /**
     * One strike: damage (type {@link #DAMAGE_TYPE}, coming from the bump; a shield does not block a strike from the
     * ground, the type is in {@code #minecraft:bypasses_shield}), the throw ({@link Strike}, times 1 - the player's
     * knockback resistance), the strike sound, more anger, and the brain hears the player. The bump touched the
     * player, so all but the damage happen also when the damage does not go through (invulnerable after respawning,
     * or still after a recent hit). The throw is sent to the player's client at once, as {@code Player.attack} sends
     * its knockback: it arrives as configured, before a tick of server-side friction (which depends on the block
     * under the player) has slowed it down.
     */
    private void hit(ServerPlayer player, Vec3 feet) {
        TremorConfig.Common config = TremorConfig.COMMON;
        Crawler crawler = entity.crawler();
        Vec3 skin = skin();
        float damage = (float) Difficulty.current().damage(config.contactDamage.getAsDouble());
        boolean hurt = false;
        if (damage > 0) {
            Holder<DamageType> type = level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(DAMAGE_TYPE);
            hurt = player.hurt(new DamageSource(type, new net.minecraft.world.phys.Vec3(skin.x(), skin.y(),
                    skin.z())), damage);
        }
        Vec3 push = ContactZone.push(crawler.position(), crawler.normal(), crawler.forward(), feet);
        Vec3 velocity = Strike.velocity(push, crawler.normal(), config.contactKnockback.getAsDouble(),
                config.contactLift.getAsDouble(), player.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE));
        if (velocity.lengthSquared() > 0) {
            // Replaces the knockback of hurt(), which has not been sent yet (hurtMarked).
            player.setDeltaMovement(velocity.x(), velocity.y(), velocity.z());
            player.connection.send(new ClientboundSetEntityMotionPacket(player));
            player.hurtMarked = false;
        }
        play(TremorSounds.STRIKE, feet, STRIKE_VOLUME, 1);
        Tremor.LOGGER.debug("Tremor #{} strikes {} ({} damage{})", entity.instance(),
                player.getGameProfile().getName(), damage, damage > 0 && !hurt ? ", not taken" : "");
        addAnger(config.contactAnger.getAsDouble());
        if (entity.aiEnabled()) {
            brain.hear(feet, STRIKE_LOUDNESS);
        }
    }

    // ---- leaving ----

    /** Starts leaving (SPEC 11): the target is dropped, the brain stops and the bump sinks; the ground sighs. */
    private void leave(DespawnClock clock) {
        Tremor.LOGGER.info(String.format(Locale.ROOT, "Tremor #%d in %s leaves: no player near for %.0f s, nothing "
                        + "happening for %.0f s", entity.instance(), level.dimension().location(), clock.farSeconds(),
                clock.quietSeconds()));
        entity.setLeaving(true);
        leavingTicks = 0;
        runtime.dropTarget(entity);
        play(TremorSounds.SIGH, skin(), (float) TremorConfig.COMMON.transitionVolume.getAsDouble(), 1);
    }

    /**
     * Another entity came too near ({@code spawn.crowdDistance}, {@link TremorManager}): this one gives way. As when
     * leaving, it drops what it was after, hears nothing more and strikes nobody, and the ground sighs; it turns away
     * from {@code other} (entity number {@code instance}) and hurries off, its bump going down over
     * {@value #YIELD_SECONDS} s until it is under the ground, and is gone. Not saved as such: one saved meanwhile is
     * just leaving after a load.
     */
    void yieldTo(Vec3 other, int instance) {
        if (entity.leaving() || entity.absorbed()) {
            return;
        }
        Vec3 at = entity.crawler().position();
        double dx = at.x() - other.x();
        double dz = at.z() - other.z();
        double length = Math.hypot(dx, dz);
        Tremor.LOGGER.info(String.format(Locale.ROOT, "Tremor #%d in %s gives way to tremor #%d, %.0f blocks off: it "
                + "turns away and sinks", entity.instance(), level.dimension().location(), instance, length));
        entity.setLeaving(true);
        leavingTicks = 0;
        yieldTicks = 0;
        runtime.dropTarget(entity);
        if (length < 1e-6) {
            double angle = level.random.nextDouble() * 2 * Math.PI;
            dx = Math.cos(angle);
            dz = Math.sin(angle);
            length = 1;
        }
        runtime.brainGo(entity, new Vec3(at.x() + dx / length * YIELD_DISTANCE, at.y(),
                at.z() + dz / length * YIELD_DISTANCE), TremorEntity.TargetKind.ROAM, 0, false);
        play(TremorSounds.SIGH, skin(), (float) TremorConfig.COMMON.transitionVolume.getAsDouble(), 1);
    }

    /** How far up the bump of an entity giving way still is: 1 as it starts, 0 after {@value #YIELD_SECONDS} s. */
    private double yieldShare() {
        return Math.max(0, 1 - yieldTicks / (YIELD_SECONDS * 20));
    }

    /** Whether a player (not a spectator) is within {@code range} of the entity. */
    private boolean playerWithin(double range) {
        Vec3 p = entity.crawler().position();
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && player.distanceToSqr(p.x(), p.y(), p.z()) <= range * range) {
                return true;
            }
        }
        return false;
    }

    // ---- world ----

    /**
     * The players a wander leg keeps away from (SPEC 5.6, 8; {@link KeepAway#of}): while the entity seeks
     * (AWAKENING), every player who can be taken ({@link AwakeningManager#takeable}; one who cannot, in creative mode
     * for one, takes none of that care away from the others), the leg ending {@code minDistance} from each and its way
     * passing none of them within reach ({@code awakening.reachDistance} plus {@value #PASS_MARGIN} horizontally,
     * {@link AwakeningRules#REACH_HEIGHT} plus {@value #PASS_MARGIN_HEIGHT} in height); in another stage (DORMANT,
     * ALERT, HUNTING) every player who is not a spectator, only its end {@code minDistance} away (nobody for 0).
     */
    private KeepAway keepAway(double minDistance) {
        Stage stage = meter.stage();
        if (stage != Stage.AWAKENING && minDistance == 0) {
            return KeepAway.NONE;
        }
        List<KeepAway.Player> players = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            players.add(new KeepAway.Player(vec(player.position()), AwakeningManager.takeable(player),
                    player.isSpectator()));
        }
        return KeepAway.of(stage, minDistance, players, TremorConfig.COMMON.awakening.reachDistance.get() + PASS_MARGIN,
                AwakeningRules.REACH_HEIGHT + PASS_MARGIN_HEIGHT);
    }

    /** The brain's questions, answered over the runtime's surface graph ({@link SurfacePicker}). */
    // ---- ambush ----

    /** How far its bump has gone into lying in wait, 0..1, for the clients. */
    double lurkShare() {
        return lurkShare;
    }

    /**
     * {@code /tremor ambush}: lies in wait where it is now, whether anybody comes or not (raised to ALERT if it is
     * DORMANT, half way to HUNTING, so that it does not calm down at once). Returns null, or why it cannot.
     */
    String lurkNow(long now) {
        if (!TremorConfig.COMMON.ambush.enabled.get()) {
            return "ambushes are switched off (ambush.enabled)";
        }
        if (entity.absorbed()) {
            return "it is taken by an Awakening";
        }
        if (entity.leaving()) {
            return "it is leaving";
        }
        if (entity.frenzy()) {
            return "it is in a frenzy";
        }
        if (!entity.aiEnabled()) {
            return "its ai is off (/tremor ai on)";
        }
        if (meter.stage() == Stage.AWAKENING) {
            return "it seeks a player at the top of its anger (AWAKENING)";
        }
        if (!Ambush.mayLurk(Stage.HUNTING, entity.crawler().normal())) {
            return "it is not on a floor (a wall or a ceiling)";
        }
        if (lurkAt != null) {
            return "it lies in wait already";
        }
        if (!tremor.awakening.Quicksand.fits(level, skin(), TremorConfig.COMMON.ambush.triggerRadius.get())) {
            return "no ground for quicksand here (two blocks of air over at least four of solid ground, nothing with "
                    + "a block entity, no fluid)";
        }
        if (meter.stage() == Stage.DORMANT) {
            setAnger((params.alertAt() + params.huntAt()) / 2);
        }
        lurk(now, "a command");
        lurkForced = true;
        return null;
    }

    /**
     * A player broke the block at {@code pos}. Lying in wait, one under or around it ({@link Ambush#steppedIn}, a block
     * wider than what springs it) enrages it: it gives up lying in wait and goes to the top of its anger (AWAKENING, with
     * the roar), seeking the player, whom it hears at once. Returns whether it did.
     */
    boolean groundBroken(BlockPos pos, ServerPlayer player, long now) {
        if (lurkAt == null || !Ambush.steppedIn(lurkAt, new Vec3(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5),
                TremorConfig.COMMON.ambush.triggerRadius.get() + 1)) {
            return false;
        }
        stopLurking(now, player.getGameProfile().getName() + " dug into it");
        setAnger(params.awakenAt());
        brain.hear(vec(player.position()), STRIKE_LOUDNESS);
        Tremor.LOGGER.info("Tremor #{}: enraged, it seeks {}", entity.instance(), player.getGameProfile().getName());
        return true;
    }

    /**
     * Its ambush let it go: the player got out of the quicksand in time (the user: it should not just vanish, that would
     * let them off too lightly). A bump again where it lay, it hunts, going for {@code at} (where the player got away
     * to; null: nowhere in particular), but strikes nobody for {@code graceTicks}. Its anger is a little over the
     * threshold of the hunt, and does not reach the top for {@value #CHASE_TICKS} ticks: a chase, not an Awakening at
     * once.
     */
    void freeFromAmbush(long now, Vec3 at, int graceTicks) {
        ambushLook = false;
        entity.setAbsorbed(false);
        orders.clear();
        setAnger(params.huntAt() + (params.awakenAt() - params.huntAt()) / 4);
        if (at != null) {
            brain.hear(at, STRIKE_LOUDNESS);
        }
        graceUntil = now + graceTicks;
        chaseUntil = now + CHASE_TICKS;
    }

    /** {@code /tremor ambush stop}: stops lying in wait, without the pause after it; whether it lay in wait. */
    boolean stopLurkNow(long now) {
        if (lurkAt == null) {
            return false;
        }
        stopLurking(now, "a command");
        lurkAgain = now;
        return true;
    }

    /** The ambush as the debug probe tells it. */
    String ambushState(long now) {
        TremorConfig.Ambush config = TremorConfig.COMMON.ambush;
        if (!config.enabled.get()) {
            return "switched off (ambush.enabled)";
        }
        if (lurkAt != null) {
            return String.format(Locale.ROOT, "lying in wait at %.0f %.0f %.0f, %.0f of %d s; springs within %.1f "
                            + "blocks", lurkAt.x(), lurkAt.y(), lurkAt.z(), (now - lurkSince) / 20.0,
                    config.lurkSeconds.get(), config.triggerRadius.get());
        }
        if (now < lurkAgain) {
            return String.format(Locale.ROOT, "not again for %.0f s", (lurkAgain - now) / 20.0);
        }
        if (meter.stage() != Stage.ALERT && meter.stage() != Stage.HUNTING) {
            return "only when alert or hunting";
        }
        if (!Ambush.mayLurk(meter.stage(), entity.crawler().normal())) {
            return "not on a wall or a ceiling";
        }
        return String.format(Locale.ROOT, "may lie in wait for a player coming within %.0f blocks unseen (chance "
                + "%.0f%%)", config.senseDistance.get(), Difficulty.current().ambushChance(config.chance.get()) * 100);
    }

    /**
     * Every {@value #LOOK_TICKS} ticks while it may lie in wait ({@link #mayLurkNow}): a player who could be taken is
     * coming ({@link Ambush#coming}, from where the feet were at the last look) and no player near sees the bump
     * ({@link #sees}): at {@code ambush.chance} it lies in wait ({@link #lurk}); after a no it looks again
     * {@value #RETRY_TICKS} ticks later.
     */
    private void lookForPrey(long now) {
        if (!mayLurkNow(now)) {
            feetBefore.clear();
            return;
        }
        TremorConfig.Ambush config = TremorConfig.COMMON.ambush;
        double sense = config.senseDistance.get();
        Vec3 skin = skin();
        Vec3 seen = skin.add(entity.crawler().normal().scale(0.3));
        boolean coming = false;
        Map<UUID, Vec3> feet = new HashMap<>();
        for (ServerPlayer player : level.players()) {
            double dx = player.getX() - skin.x(), dz = player.getZ() - skin.z();
            if (player.isSpectator() || dx * dx + dz * dz > 4 * sense * sense) {
                continue;
            }
            if (sees(player, seen)) {
                feetBefore.clear();
                return; // not in front of anybody
            }
            if (!AwakeningManager.takeable(player) || Math.abs(player.getY() - skin.y()) > sense) {
                continue;
            }
            Vec3 at = vec(player.position());
            Vec3 before = feetBefore.get(player.getUUID());
            feet.put(player.getUUID(), at);
            coming |= before != null && Ambush.coming(skin, before, at, sense);
        }
        feetBefore.clear();
        feetBefore.putAll(feet);
        if (!coming) {
            return;
        }
        if (level.getRandom().nextDouble() >= Difficulty.current().ambushChance(config.chance.get())
                || !tremor.awakening.Quicksand.fits(level, skin, config.triggerRadius.get())) {
            lurkAgain = now + RETRY_TICKS; // a no, or no ground for quicksand here
            return;
        }
        lurk(now, "a player is coming");
    }

    /** Whether it may lie in wait now by itself: switched on, not paused, ALERT or HUNTING on a floor, free. */
    private boolean mayLurkNow(long now) {
        Crawler crawler = entity.crawler();
        return TremorConfig.COMMON.ambush.enabled.get() && lurkAt == null && now >= lurkAgain
                && Ambush.mayLurk(meter.stage(), crawler.normal()) && entity.aiEnabled() && !entity.frenzy()
                && !entity.leaving() && !entity.absorbed() && !angerHeld && !crawler.diving()
                && entity.targetKind() != TremorEntity.TargetKind.MANUAL;
    }

    /** Lies in wait where it is: the brain's orders and the target go, the body stops. */
    private void lurk(long now, String why) {
        lurkAt = skin();
        lurkSince = now;
        lurkForced = false;
        feetBefore.clear();
        runtime.dropTarget(entity);
        Tremor.LOGGER.info(String.format(Locale.ROOT, "Tremor #%d in %s lies in wait at %.1f %.1f %.1f (%s, %s)",
                entity.instance(), level.dimension().location(), lurkAt.x(), lurkAt.y(), lurkAt.z(),
                meter.stage().name().toLowerCase(Locale.ROOT), why));
    }

    /** Why it stops lying in wait now, or null if it goes on. */
    private String lurkOver(long now) {
        TremorConfig.Ambush config = TremorConfig.COMMON.ambush;
        if (!config.enabled.get()) {
            return "ambushes switched off";
        }
        Stage stage = meter.stage();
        if (stage != Stage.ALERT && stage != Stage.HUNTING) {
            return stage == Stage.DORMANT ? "calmed down" : "at the top of its anger";
        }
        if (!entity.aiEnabled()) {
            return "ai off";
        }
        if (entity.frenzy()) {
            return "a frenzy";
        }
        if (entity.targetKind() == TremorEntity.TargetKind.MANUAL) {
            return "a goto";
        }
        if (now - lurkSince >= config.lurkSeconds.get() * 20L) {
            return "nobody came";
        }
        if (lurkForced) {
            return null;
        }
        double reach = config.senseDistance.get() * 1.5;
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && Math.hypot(player.getX() - lurkAt.x(), player.getZ() - lurkAt.z()) <= reach) {
                return null;
            }
        }
        return "nobody near";
    }

    private void stopLurking(long now, String why) {
        Tremor.LOGGER.info("Tremor #{} in {} stops lying in wait: {}", entity.instance(), level.dimension().location(),
                why);
        lurkAt = null;
        lurkAgain = now + TremorConfig.COMMON.ambush.cooldownSeconds.get() * 20L;
    }

    /**
     * Lying in wait, after the move: a mob that has stepped onto it sinks ({@link Devour}), and a player who could be
     * taken springs it: the quicksand takes every such player on it ({@link AwakeningManager#ambush}), and the
     * entity with them. Returns whether it was sprung.
     */
    private boolean spring(long now) {
        double radius = TremorConfig.COMMON.ambush.triggerRadius.get();
        if (TremorConfig.COMMON.devourMobs.get()) {
            AABB box = new AABB(lurkAt.x() - radius, lurkAt.y() - Ambush.STEP_HEIGHT, lurkAt.z() - radius,
                    lurkAt.x() + radius, lurkAt.y() + Ambush.STEP_HEIGHT, lurkAt.z() + radius);
            for (Mob mob : level.getEntitiesOfClass(Mob.class, box, m -> Devour.takeable(level, m))) {
                if (Ambush.steppedIn(lurkAt, vec(mob.position()), radius)) {
                    Devour.take(level, mob);
                }
            }
        }
        List<ServerPlayer> caught = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (AwakeningManager.takeable(player) && Ambush.steppedIn(lurkAt, vec(player.position()), radius)) {
                caught.add(player);
            }
        }
        if (caught.isEmpty()) {
            return false;
        }
        Vec3 at = lurkAt;
        lurkAt = null;
        lurkAgain = now + TremorConfig.COMMON.ambush.cooldownSeconds.get() * 20L;
        ambushLook = true; // before it is taken: no roar
        ambushLook = AwakeningManager.ambush(level, runtime, at, radius, caught) > 0;
        return ambushLook;
    }

    /** Whether {@code player} sees {@code point}: in the view of their look, nothing solid in the way. */
    private boolean sees(ServerPlayer player, Vec3 point) {
        Vec3 eye = vec(player.getEyePosition());
        return Ambush.inView(eye, vec(player.getLookAngle()), point)
                && LineOfSight.clear(runtime.liveView(), eye, point);
    }

    private final class World implements BrainWorld {
        /**
         * A wander leg {@code wanderMinRadius}..{@code wanderMaxRadius} away, keeping away from the players
         * ({@link #keepAway}), preferably seen by the nearest player (not a spectator) within {@value #VIEWER_RANGE}
         * blocks, from at least {@code behavior.minWanderDistance} away whatever the stage keeps (nearer points in
         * sight are not preferred); without such a point, any (the first one that keeps away; none is chosen for
         * being near that player, {@link SurfacePicker#wanderTarget}).
         */
        @Override
        public Vec3 wanderTarget(Vec3 from, double minDistanceToPlayer, RandomGenerator random) {
            TremorConfig.Common config = TremorConfig.COMMON;
            double min = config.wanderMinRadius.get();
            double max = Math.max(min, config.wanderMaxRadius.get());
            return SurfacePicker.wanderTarget(runtime.graph(), from, min, max, WANDER_VERTICAL_RANGE, viewerEye(from),
                    params.minWanderDistance(), keepAway(minDistanceToPlayer), random, WANDER_ATTEMPTS);
        }

        @Override
        public Vec3 searchTarget(Vec3 center, double radius, RandomGenerator random) {
            return SurfacePicker.searchTarget(runtime.graph(), center, radius, random, SEARCH_ATTEMPTS);
        }

        /** Eye of the nearest player (not a spectator) within {@value #VIEWER_RANGE} blocks of {@code from}, or null. */
        private Vec3 viewerEye(Vec3 from) {
            ServerPlayer nearest = null;
            double best = VIEWER_RANGE * VIEWER_RANGE;
            for (ServerPlayer player : level.players()) {
                double d = player.distanceToSqr(from.x(), from.y(), from.z());
                if (!player.isSpectator() && d <= best) {
                    best = d;
                    nearest = player;
                }
            }
            return nearest == null ? null : vec(nearest.getEyePosition());
        }
    }

    // ---- helpers ----

    /** The top of the skin at the entity: half a block out of its voxel centre along the normal. */
    private Vec3 skin() {
        Crawler crawler = entity.crawler();
        return crawler.position().add(crawler.normal().scale(0.5));
    }

    private void play(Holder<SoundEvent> sound, Vec3 at, float volume, float pitch) {
        level.playSound(null, at.x(), at.y(), at.z(), sound, SoundSource.HOSTILE, volume, pitch);
    }

    private static Vec3 vec(net.minecraft.world.phys.Vec3 v) {
        return new Vec3(v.x, v.y, v.z);
    }
}
