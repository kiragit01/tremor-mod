package tremor.hearing;

import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import tremor.awakening.AwakeningManager;
import tremor.config.TremorConfig;
import tremor.core.math.Vec3;
import tremor.debug.DebugParticles;
import tremor.entity.TremorEntity;
import tremor.entity.TremorManager;
import tremor.entity.TremorRuntime;
import tremor.world.LevelVoxelView;

import java.util.List;
import java.util.Locale;

/**
 * Server-side sources of vibrations (SPEC 7.1, 7.4): vanilla game events ({@link VanillaGameEvent}, fired for server
 * levels only) and falls ({@link LivingFallEvent}); registered on the game bus by {@link tremor.Tremor}. Each becomes a
 * {@link Vibration} for the entity of the level ({@link TremorRuntime#hear}) and for the level's Awakening, with the
 * player behind it ({@link AwakeningManager#vibration}: who was heard last, the ring waves of steps in its zone, SPEC
 * 9). Nothing is computed for a source that is beyond the hearing distance of the level's entity (or there is none)
 * and not in the zone of a running Awakening. Server thread only.
 * <p>
 * Loudness: the {@code hearing.loudness} config list per game event (events not listed are ignored), then by source
 * ({@code context.sourceEntity()}):
 * <ul>
 *   <li>a sneaking player's step or landing is silent (vanilla sculk ignores both), a sprinting player's step has
 *   {@code sprintStepLoudness};</li>
 *   <li>{@code hit_ground} is a landing only after a drop of {@value SoundRules#MIN_LANDING_DROP} blocks or more
 *   ({@link SoundRules#isLanding}): vanilla posts it after any drop, so also for every step down a stair or a slab,
 *   which is ignored (the walk is heard through its steps);</li>
 *   <li>steps of a mount ridden by a player and of any minecart have {@code mountStepLoudness} (vanilla emits a
 *   minecart's steps while it rolls on rails, every ~1.7 blocks);</li>
 *   <li>other living sources: times {@code mobLoudnessFactor} (0 by default: the entity listens for players, not
 *   cows), except explosions (creepers), which always count fully;</li>
 *   <li>projectiles and other non-living sources count fully, whoever threw them: decoys (SPEC 7.4);</li>
 *   <li>items: every entity emits {@code hit_ground} when it lands after falling; for an item that becomes
 *   {@code item_land} with {@code itemLandLoudness} plus up to 2 for a fast impact, and nothing below
 *   {@value SoundRules#MIN_ITEM_SPEED} blocks per tick (whatever {@code hit_ground} is set to); experience orbs are
 *   ignored;</li>
 *   <li>a fall with fall damage is {@code fall} with {@code fallLoudness} + height (sneaking does not soften it), and
 *   replaces the {@code hit_ground} of the same landing. One landing is one fall, keyed by the root vehicle: when a
 *   pig, strider, minecart or boat falls, vanilla forwards the fall to its riders, whose fall events are then dropped.
 *   Horses (also donkeys, mules, llamas, camels) fire no fall event at all ({@code AbstractHorse.causeFallDamage}
 *   hurts the riders directly), so their {@code hit_ground} after a drop longer than their safe fall distance is the
 *   fall;</li>
 *   <li>a heard explosion adds {@code explosionAngerBonus} anger on top.</li>
 * </ul>
 * Footing ({@link Contact}): steps, landings and falls go into the ground under the source entity (a fall: under the
 * vehicle it rides), a step on a ladder, vine or scaffolding into what it climbs, a step in powder snow into the snow;
 * events that carry a block state (placed or broken block, a projectile hitting a block) into that block; an explosion
 * into the better conductor of its centre voxel and the one below; anything else through the source entity, or the
 * block at the event position if there is none.
 */
public final class VibrationListener {
    /** Sources this much beyond the hearing distance (from the event position) are dropped before anything else. */
    private static final double RANGE_MARGIN = 3;
    /** The game event of a step ({@link #walkingStepLoudness}). */
    private static final ResourceLocation STEP = ResourceLocation.withDefaultNamespace("step");

    private static List<? extends String> parsedFrom;
    private static Object2DoubleOpenHashMap<ResourceLocation> loudness = new Object2DoubleOpenHashMap<>();

    /** The last fall heard, by root vehicle. */
    private static final FallSlot LAST_FALL = new FallSlot();

    private VibrationListener() {
    }

    public static void onGameEvent(VanillaGameEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        TremorRuntime runtime = TremorManager.runtime(level);
        if (!hasEntity(runtime) && !AwakeningManager.listens(level)) {
            return;
        }
        GameEvent.Context context = event.getContext();
        Entity cause = context.sourceEntity();
        if (cause != null && cause.isSpectator() || cause instanceof ExperienceOrb) {
            return;
        }
        GameEvent type = event.getVanillaEvent().value();
        net.minecraft.world.phys.Vec3 pos = event.getEventPosition();
        boolean step = type == GameEvent.STEP.value();
        boolean landing = type == GameEvent.HIT_GROUND.value();
        if (cause instanceof ItemEntity item) {
            if (landing) {
                itemLanded(level, runtime, item, pos);
            }
            return;
        }
        String drop = null;
        if (landing && cause != null) {
            if (LAST_FALL.consume(cause.getRootVehicle().getId(), level.getGameTime())) {
                return; // heard as the fall
            }
            if (!SoundRules.isLanding(cause.fallDistance)) {
                return; // a step down: the walk is heard through its steps
            }
            if (cause instanceof AbstractHorse horse && SoundRules.isDamagingFall(horse.fallDistance,
                    horse.getAttributeValue(Attributes.SAFE_FALL_DISTANCE), 1)) {
                // No fall event for horses; the damage multiplier of the block it lands on is not known here.
                fall(level, runtime, horse, horse.fallDistance);
                return;
            }
            drop = String.format(Locale.ROOT, "%.1f blocks", cause.fallDistance);
        }
        ResourceLocation id = key(event.getVanillaEvent());
        double base = id == null ? 0 : table().getDouble(id);
        if (!(base > 0) || !wanted(level, runtime, pos)) {
            return;
        }
        TremorConfig.Common config = TremorConfig.COMMON;
        LevelVoxelView view = new LevelVoxelView(level);
        String name = name(id);
        if (type == GameEvent.EXPLODE.value()) {
            Contact contact = Contact.ofBlast(view, pos);
            emit(level, runtime, new Vibration(name, contact.point(), base, contact.footing(),
                    (float) config.explosionAngerBonus.getAsDouble(), null), cause);
            return;
        }

        double loudness = base;
        String note = null;
        if (cause instanceof Player player) {
            if (step || landing) {
                boolean sneaking = player.isSteppingCarefully() || player.isCrouching();
                boolean sprinting = player.isSprinting();
                loudness = SoundRules.playerMovement(base, landing, sneaking, sprinting,
                        config.sprintStepLoudness.getAsDouble());
                note = sneaking ? "sneaking" : step && sprinting ? "sprinting" : null;
            }
        } else if (cause instanceof AbstractMinecart) {
            if (step) {
                loudness = config.mountStepLoudness.getAsDouble();
                note = "minecart";
            }
        } else if (cause != null && ridden(cause)) {
            if (step) {
                loudness = config.mountStepLoudness.getAsDouble();
                note = "mount";
            }
        } else if (cause instanceof LivingEntity) {
            loudness *= config.mobLoudnessFactor.getAsDouble();
            note = "mob";
            if (!(loudness > 0)) {
                return;
            }
        }

        Contact contact;
        if (cause != null && (step || landing)) {
            contact = Contact.ofEntity(view, cause, landing);
        } else if (context.affectedState() != null || cause == null) {
            contact = Contact.ofBlock(view, pos, context.affectedState());
        } else {
            contact = Contact.ofEntity(view, cause, false);
        }
        emit(level, runtime, new Vibration(name, contact.point(), loudness, contact.footing(), 0,
                join(join(note, drop), contact.note())), cause);
    }

    /**
     * Fired in {@code LivingEntity.causeFallDamage}, before the {@code hit_ground} of the same landing (on both sides);
     * for a rider also when its vehicle falls (forwarded by {@code Entity.causeFallDamage}), right after the vehicle's
     * own event if it has one.
     */
    public static void onLivingFall(LivingFallEvent event) {
        LivingEntity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level) || entity.isSpectator()) {
            return;
        }
        TremorRuntime runtime = TremorManager.runtime(level);
        if (!hasEntity(runtime) && !AwakeningManager.listens(level)) {
            return;
        }
        if (LAST_FALL.isMarked(entity.getRootVehicle().getId(), level.getGameTime())) {
            return; // a rider's share of a fall already heard
        }
        float distance = event.getDistance();
        if (!SoundRules.isDamagingFall(distance, entity.getAttributeValue(Attributes.SAFE_FALL_DISTANCE),
                event.getDamageMultiplier())) {
            return; // no fall damage: an ordinary landing
        }
        fall(level, runtime, entity, distance);
    }

    /**
     * A fall with fall damage of {@code entity} or of the vehicle it rides: heard from the ground under the root
     * vehicle, and that landing is marked as heard.
     */
    private static void fall(ServerLevel level, TremorRuntime runtime, LivingEntity entity, float distance) {
        TremorConfig.Common config = TremorConfig.COMMON;
        double loudness = SoundRules.fall(config.fallLoudness.getAsDouble(), distance);
        String note = null;
        if (ridden(entity)) {
            note = "mount";
        } else if (!(entity instanceof Player)) {
            loudness *= config.mobLoudnessFactor.getAsDouble();
            note = "mob";
        }
        if (!(loudness > 0)) {
            return;
        }
        Entity root = entity.getRootVehicle();
        LAST_FALL.mark(root.getId(), level.getGameTime());
        if (!wanted(level, runtime, root.position())) {
            return;
        }
        Contact contact = Contact.ofEntity(new LevelVoxelView(level), root, true);
        emit(level, runtime, new Vibration("fall", contact.point(), loudness, contact.footing(), 0,
                join(note, String.format(Locale.ROOT, "%.1f blocks", distance))), entity);
    }

    /**
     * An item's {@code hit_ground}: posted inside {@code Entity.move} before the landing zeroes the vertical motion, so
     * the motion is still the impact speed.
     */
    private static void itemLanded(ServerLevel level, TremorRuntime runtime, ItemEntity item,
                                   net.minecraft.world.phys.Vec3 pos) {
        double speed = -item.getDeltaMovement().y;
        double loudness = SoundRules.itemLanding(TremorConfig.COMMON.itemLandLoudness.getAsDouble(), speed);
        if (!(loudness > 0) || !wanted(level, runtime, pos)) {
            return;
        }
        Contact contact = Contact.ofEntity(new LevelVoxelView(level), item, true);
        emit(level, runtime, new Vibration("item_land", contact.point(), loudness, contact.footing(), 0,
                String.format(Locale.ROOT, "%.2f b/t", speed)), item);
    }

    /**
     * The vibration goes to the level's entity, if there is one, then to the level's Awakening with the player behind
     * it ({@link #playerBehind}).
     *
     * @param cause what made it, or null
     */
    private static void emit(ServerLevel level, TremorRuntime runtime, Vibration vibration, Entity cause) {
        Perception perception = runtime == null ? null : runtime.hear(vibration);
        if (perception != null) {
            DebugParticles.hearing(level, vibration, perception);
        }
        AwakeningManager.vibration(level, vibration, playerBehind(cause), perception);
    }

    private static boolean hasEntity(TremorRuntime runtime) {
        return runtime != null && runtime.entity() != null;
    }

    /**
     * Whether a source at {@code pos} concerns anybody: the level's entity, if the source is within its hearing
     * distance (plus a margin), or the level's Awakening, if the source lies in its zone
     * ({@link AwakeningManager#listens(ServerLevel, double, double)}).
     */
    private static boolean wanted(ServerLevel level, TremorRuntime runtime, net.minecraft.world.phys.Vec3 pos) {
        TremorEntity entity = runtime == null ? null : runtime.entity();
        if (entity != null) {
            Vec3 c = entity.crawler().position();
            double range = TremorConfig.COMMON.hearingMaxDistance.getAsDouble() + RANGE_MARGIN;
            if (pos.distanceToSqr(c.x(), c.y(), c.z()) <= range * range) {
                return true;
            }
        }
        return AwakeningManager.listens(level, pos.x, pos.z);
    }

    /**
     * The player behind a vibration of {@code cause}: the player itself, or a player riding it or riding with it (a
     * mount, a minecart, a boat); null for anything else (mobs, items, projectiles, explosions) and for no cause.
     */
    private static Player playerBehind(Entity cause) {
        if (cause == null) {
            return null;
        }
        if (cause instanceof Player player) {
            return player;
        }
        for (Entity passenger : cause.getRootVehicle().getIndirectPassengers()) {
            if (passenger instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    /**
     * Loudness of a walking step in the config ({@code minecraft:step} of {@code hearing.loudness}; 0 if steps are
     * not heard): the measure of the Awakening's step ripples (SPEC 9).
     */
    public static double walkingStepLoudness() {
        return table().getDouble(STEP);
    }

    /** A vehicle or mount with a player on board. */
    private static boolean ridden(Entity entity) {
        return entity.hasPassenger(passenger -> passenger instanceof Player);
    }

    /** Event id to loudness, parsed again whenever the config list changes. */
    private static Object2DoubleOpenHashMap<ResourceLocation> table() {
        List<? extends String> entries = TremorConfig.COMMON.loudness.get();
        if (entries != parsedFrom) {
            Object2DoubleOpenHashMap<ResourceLocation> table = new Object2DoubleOpenHashMap<>();
            LoudnessTable.parse(entries).forEach((id, value) -> {
                ResourceLocation location = ResourceLocation.tryParse(id);
                if (location != null) {
                    table.put(location, value.doubleValue());
                }
            });
            loudness = table;
            parsedFrom = entries;
        }
        return loudness;
    }

    private static ResourceLocation key(Holder<GameEvent> event) {
        if (event instanceof Holder.Reference<GameEvent> reference) {
            return reference.key().location();
        }
        return event.unwrapKey().map(ResourceKey::location).orElse(null);
    }

    /** The path for the minecraft namespace, else the full id. */
    private static String name(ResourceLocation id) {
        return ResourceLocation.DEFAULT_NAMESPACE.equals(id.getNamespace()) ? id.getPath() : id.toString();
    }

    private static String join(String a, String b) {
        return a == null ? b : b == null ? a : a + ", " + b;
    }
}
