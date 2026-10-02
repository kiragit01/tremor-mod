package tremor.entity;

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
import net.minecraft.world.entity.ai.attributes.Attributes;
import tremor.Tremor;
import tremor.config.TremorConfig;
import tremor.core.behavior.AngerMeter;
import tremor.core.behavior.BehaviorParams;
import tremor.core.behavior.Brain;
import tremor.core.behavior.BrainWorld;
import tremor.core.behavior.ContactZone;
import tremor.core.behavior.Decision;
import tremor.core.behavior.DespawnClock;
import tremor.core.behavior.Stage;
import tremor.core.behavior.SurfacePicker;
import tremor.core.math.Vec3;
import tremor.core.motion.Crawler;
import tremor.core.motion.MotionParams;
import tremor.network.TremorStatePayload;
import tremor.sound.TremorSounds;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Stage behaviour of the entity on the server (SPEC 8, 5.6, 11, 13): the anger and its stage ({@link AngerMeter}),
 * the {@link Brain} and the answers to its questions ({@link BrainWorld} over the runtime's surface graph), the
 * ground ripple of a freeze, contact with players and leaving. One per entity, owned by its {@link TremorRuntime},
 * which carries out the brain's orders (routes, stopping). Transient: the anger, the stage, the switches and the
 * despawn clock are saved with the {@link TremorEntity}; the brain starts afresh after a load. Server thread only.
 * <ul>
 * <li><b>Anger</b>: a heard vibration adds {@code perceived * angerPerLoudness} plus its bonus ({@link #heard});
 * every tick the anger decays, faster once nothing was heard for a while ({@link AngerMeter#tick}; the time since the
 * entity's saved {@code lastHeard}, so a load does not reset it). Every stage change is marked by a sound (SPEC 13,
 * {@link StageTransition}) and sent to the players at once. The {@code behavior} config is read every tick; when it
 * changes the meter (keeping anger and stage) and the brain are built anew.</li>
 * <li><b>Brain</b> ({@link #think}): fed with the sounds heard since the last tick, ticked once per server tick
 * before the move, with the stage after this tick's anger; its decisions go through {@link BrainOrders} to the body.
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
 * ({@link #pausedTick}).</li>
 * <li><b>Awakening</b> (SPEC 9; {@link #absorb}): taken by an Awakening, the entity is the whole area rather than a
 * bump: its brain is suspended, the anger stays at the top, the bump sinks, it strikes nobody, does not leave by
 * itself and hears nothing ({@code awakening}). Until it is removed: every Awakening ends with that
 * ({@link tremor.awakening.AwakeningManager}).</li>
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
    /** A leaving entity is removed once its bump is lower than this (blocks)... */
    static final double SUNK = 0.02;
    /** ...or this many ticks after it started leaving, whatever its bump does. */
    static final int MAX_LEAVING_TICKS = 200;
    /** How loud a struck player is to the brain: louder than anything heard, so it goes straight for the player. */
    static final double STRIKE_LOUDNESS = 10;
    private static final float STRIKE_VOLUME = 1.5f;
    /** Pitch of the rumble under the crack of a change to HUNTING. */
    private static final float LOW_PITCH = 0.7f;

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

    private BehaviorParams params;
    private AngerMeter meter;
    private Brain brain;
    /** A vibration was heard since the last tick (eventful for the despawn clock). */
    private boolean heardSinceTick;
    private int leavingTicks;
    /** Taken by an Awakening ({@link #absorb}). Not saved: an entity loaded in AWAKENING goes deep at once. */
    private boolean absorbed;

    TremorMind(TremorRuntime runtime, TremorEntity entity) {
        this.runtime = runtime;
        this.level = runtime.level();
        this.entity = entity;
        this.params = configParams();
        this.meter = new AngerMeter(params, entity.anger(), entity.stage());
        // A loaded entity takes the stage the meter reconciles quietly: nothing changed for the players.
        entity.setAnger((float) meter.anger());
        entity.setStage(meter.stage());
        this.brain = newBrain();
    }

    TremorEntity entity() {
        return entity;
    }

    /**
     * A vibration the entity heard (SPEC 7.3, 8): adds {@code anger} to the meter and tells the brain (unless it is
     * off). Nothing while the entity leaves.
     *
     * @return what the entity makes of it, for the hearing debug view: {@code ignores it} (DORMANT, too quiet),
     * {@code investigates}, {@code freezes}, {@code hunts}, or why nothing follows ({@code ai off},
     * {@code goto under way}, {@code leaving}, {@code awakening})
     */
    String heard(Vec3 source, double perceived, double anger) {
        if (entity.leaving()) {
            return "leaving";
        }
        if (absorbed) {
            return "awakening";
        }
        heardSinceTick = true;
        addAnger(anger);
        if (!entity.aiEnabled()) {
            return "ai off";
        }
        brain.hear(source, perceived);
        if (entity.targetKind() == TremorEntity.TargetKind.MANUAL) {
            return "goto under way";
        }
        return switch (meter.stage()) {
            case DORMANT -> perceived >= params.dormantReactLoudness() ? "investigates" : "ignores it";
            case ALERT -> "freezes";
            case HUNTING, AWAKENING -> "hunts";
        };
    }

    /**
     * The first part of a tick, before the move: the config is taken in, the anger decays, and the brain decides
     * with what it heard since the last tick; the body carries out the order.
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
        if (entity.leaving() || absorbed) {
            return; // its anger no longer matters, or it stays at the top
        }
        Stage before = entity.stage();
        meter.tick(TremorRuntime.TICK_SECONDS, secondsSinceHeard(now));
        settle(before);
        if (!entity.aiEnabled()) {
            return;
        }
        boolean manual = entity.targetKind() == TremorEntity.TargetKind.MANUAL;
        boolean idle = !manual && !orders.waiting() && runtime.bodyIdle(entity);
        Decision decision = brain.tick(TremorRuntime.TICK_SECONDS, meter.stage(), entity.crawler().position(), idle,
                world);
        BrainOrders.Order order = orders.next(decision, brain.lastHeardLoudness(), manual,
                perceived -> runtime.mayRetarget(entity, now, perceived));
        switch (order.type()) {
            case GO -> runtime.brainGo(entity, order.point(),
                    order.sound() ? TremorEntity.TargetKind.SOUND : TremorEntity.TargetKind.ROAM, order.perceived());
            case FREEZE -> {
                runtime.freeze(entity, order.point());
                ripple.freeze(now, entity.crawler().diving());
            }
            case NONE -> {
            }
        }
    }

    /**
     * This tick's motion: the entity's parameters with the stage's speed and bump height (SPEC 5.5, 8); the bump of a
     * leaving entity, or of one taken by an Awakening, sinks to nothing.
     */
    MotionParams motion() {
        MotionParams base = entity.params().motionParams();
        Stage stage = meter.stage();
        double amplitude = entity.leaving() || absorbed ? 0
                : base.amplitude() * TremorConfig.COMMON.amplitudeFactor(stage);
        return new MotionParams(base.maxSpeed() * TremorConfig.COMMON.speedFactor(stage), base.acceleration(),
                base.normalSmoothingSeconds(), amplitude, base.amplitudeSmoothingSeconds());
    }

    /**
     * The second part of a tick, after the move: contact with players, the despawn clock and leaving.
     *
     * @return true once a leaving entity is gone: the runtime removes it
     */
    boolean afterMove(long now) {
        boolean heard = heardSinceTick;
        heardSinceTick = false;
        if (absorbed) {
            return false; // the Awakening removes it
        }
        if (entity.leaving()) {
            return Math.abs(entity.crawler().amplitude()) < SUNK || ++leavingTicks >= MAX_LEAVING_TICKS;
        }
        Stage stage = meter.stage();
        if (stage == Stage.HUNTING || stage == Stage.AWAKENING) {
            strike(now);
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
        if (!entity.natural() || absorbed) {
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

    /** Debug ({@code /tremor anger}): sets the anger and the stage it implies; a stage change is marked as usual. */
    void setAnger(double anger) {
        Stage before = entity.stage();
        meter.set(anger);
        settle(before);
    }

    /** Debug ({@code /tremor stage}): jumps to the stage at its threshold anger; the change is marked as usual. */
    void forceStage(Stage stage) {
        Stage before = entity.stage();
        meter.forceStage(stage);
        settle(before);
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
        absorbed = true;
        orders.clear();
        entity.setLeaving(false);
        forceStage(Stage.AWAKENING);
    }

    /** Whether an Awakening took the entity ({@link #absorb}). */
    boolean absorbed() {
        return absorbed;
    }

    /** What the behaviour is doing, e.g. {@code hunting: searching, 12 s left}. */
    String describe() {
        if (absorbed) {
            return "awakening: it is the whole area, not a bump";
        }
        if (entity.leaving()) {
            return "leaving";
        }
        if (!entity.aiEnabled()) {
            return "ai off";
        }
        String state = brain.describe();
        if (orders.waiting()) {
            state += " (waiting for the retarget cooldown)";
        } else if (entity.targetKind() == TremorEntity.TargetKind.MANUAL) {
            state += " (suspended by a goto)";
        }
        return state;
    }

    // ---- anger ----

    private void addAnger(double amount) {
        Stage before = entity.stage();
        meter.add(amount);
        settle(before);
    }

    /** Writes the meter to the entity; a stage change is marked by a sound (SPEC 8, 13) and sent to the players. */
    private void settle(Stage before) {
        entity.setAnger((float) meter.anger());
        Stage stage = meter.stage();
        if (stage == before) {
            return;
        }
        entity.setStage(stage);
        float volume = (float) TremorConfig.COMMON.transitionVolume.getAsDouble();
        switch (StageTransition.of(before, stage)) {
            case RUMBLE -> play(TremorSounds.RUMBLE, skin(), volume, 1);
            case CRACK -> {
                play(TremorSounds.CRACK, skin(), volume, 1);
                play(TremorSounds.RUMBLE, skin(), volume, LOW_PITCH);
            }
            case AWAKEN -> play(TremorSounds.AWAKEN, skin(), volume, 1);
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
        float damage = (float) config.contactDamage.getAsDouble();
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

    /** The brain's questions, answered over the runtime's surface graph ({@link SurfacePicker}). */
    private final class World implements BrainWorld {
        /**
         * A wander leg {@code wanderMinRadius}..{@code wanderMaxRadius} away, preferably seen by the nearest player
         * (not a spectator) within {@value #VIEWER_RANGE} blocks; without one, any.
         */
        @Override
        public Vec3 wanderTarget(Vec3 from, double minDistanceToPlayer, RandomGenerator random) {
            TremorConfig.Common config = TremorConfig.COMMON;
            double min = config.wanderMinRadius.get();
            double max = Math.max(min, config.wanderMaxRadius.get());
            return SurfacePicker.wanderTarget(runtime.graph(), from, min, max, WANDER_VERTICAL_RANGE, viewerEye(from),
                    minDistanceToPlayer, random, WANDER_ATTEMPTS);
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
