package tremor.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import tremor.Tremor;
import tremor.config.TremorConfig;
import tremor.sound.TremorSounds;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Animals and monsters the bump touches are pulled under and gone (the user's wish after the first play: the entity
 * hunts other creatures too and swallows them whole, without a crater): a taken mob stops (no AI, no physics, no
 * damage), sinks into the ground over {@link #SINK_TICKS} with the dust of the ground it sinks through and a crack,
 * and dies, swallowed by the ground (the damage of the ground's pull), without drops or experience: its death counts
 * (a player: MCA villagers just vanished and could not be brought back; a pet's owner is told). Bosses ({@code #c:bosses}), {@code #tremor:undevourable} and mobs a
 * player rides are never taken. Kept per level in memory: a mob half under when the level is saved simply stays
 * where it was (it is not saved as taken). Server thread only; ticked by {@link #onLevelTick}, registered by
 * {@link Tremor}.
 */
public final class Devour {
    /** Ticks a taken mob takes to sink out of sight. */
    static final int SINK_TICKS = 30;
    /** Entity types the bump never takes. */
    public static final TagKey<EntityType<?>> UNDEVOURABLE = TagKey.create(Registries.ENTITY_TYPE,
            ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "undevourable"));

    /** Per level: the mobs being pulled under and the ticks they have sunk. */
    private static final Map<ServerLevel, Map<Mob, Integer>> TAKEN = new WeakHashMap<>();
    /** The age of an item that never despawns ({@code ItemEntity.setUnlimitedLifetime}). */
    private static final int UNLIMITED_AGE = -32768;
    /** The tag of a mob being pulled under, saved with it if its chunk unloads meanwhile. */
    /** On a mob being pulled under, or a display of a block on its way ({@link PerchBuild}): it does not come back. */
    static final String TAG = "tremor_devoured";

    /** The damage type of the ground's pull ({@code data/tremor/damage_type/swallowed.json}). */
    private static final net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> SWALLOWED =
            net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE,
                    ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "swallowed"));

    /** The mob being killed right now ({@link #swallow}): its drops are cancelled. */
    private static Mob dying;

    private Devour() {
    }

    /**
     * {@code mob}, under the ground, dies swallowed by it: let go of first (the tag off, or a mod bringing it back would
     * have it cancelled as it joins), no experience, its drops cancelled ({@link #onDrops}); one that will not die (a
     * modded mob immune to it) is removed.
     */
    private static void swallow(ServerLevel level, Mob mob) {
        mob.removeTag(TAG);
        mob.setInvulnerable(false);
        mob.skipDropExperience();
        dying = mob;
        try {
            mob.hurt(new net.minecraft.world.damagesource.DamageSource(level.registryAccess()
                    .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(SWALLOWED)), Float.MAX_VALUE);
        } finally {
            dying = null;
        }
        if (mob.isAlive()) {
            mob.discard();
        }
    }

    /** A mob swallowed by the ground ({@link #swallow}) drops nothing. Registered by {@link Tremor}. */
    public static void onDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent event) {
        if (event.getEntity() == dying) {
            event.setCanceled(true);
        }
    }

    /** Whether the bump may take {@code mob}: alive, not taken yet, no boss, not undevourable, ridden by no player. */
    public static boolean takeable(ServerLevel level, Mob mob) {
        if (!mob.isAlive() || mob.getType().is(Tags.EntityTypes.BOSSES) || mob.getType().is(UNDEVOURABLE)
                || spared(mob)) {
            return false;
        }
        for (Entity passenger : mob.getIndirectPassengers()) {
            if (passenger instanceof Player) {
                return false;
            }
        }
        Map<Mob, Integer> taken = TAKEN.get(level);
        return taken == null || !taken.containsKey(mob);
    }

    /**
     * Whether the config spares {@code mob}: its type or a tag of it in {@code contact.spareMobs}, or tamed with
     * {@code contact.spareTamed} (a player: villagers and pets eaten; a datapack tag was beyond most players).
     */
    private static boolean spared(Mob mob) {
        if (TremorConfig.COMMON.spareTamed.get() && mob instanceof net.minecraft.world.entity.OwnableEntity pet
                && pet.getOwnerUUID() != null) {
            return true;
        }
        List<? extends String> entries = TremorConfig.COMMON.spareMobs.get();
        if (entries.isEmpty()) {
            return false;
        }
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        for (String entry : entries) {
            if (entry.startsWith("#")) {
                ResourceLocation tag = ResourceLocation.tryParse(entry.substring(1));
                if (tag != null && mob.getType().is(TagKey.create(Registries.ENTITY_TYPE, tag))) {
                    return true;
                }
            } else if (id.equals(ResourceLocation.tryParse(entry))) {
                return true;
            }
        }
        return false;
    }

    /** Config validator of {@code contact.spareMobs}: an entity id, or {@code #} and a tag id. */
    public static boolean isSpareEntry(Object entry) {
        return entry instanceof String s && ResourceLocation.tryParse(s.startsWith("#") ? s.substring(1) : s) != null;
    }

    /** Whether the bump may take {@code item}: one lying about, not one the mod laid down to stay (never despawns). */
    public static boolean takeable(net.minecraft.world.entity.item.ItemEntity item) {
        return item.isAlive() && item.getAge() != UNLIMITED_AGE;
    }

    /** The bump takes {@code item} at once: it is gone, in the dust of the ground. */
    public static void take(ServerLevel level, net.minecraft.world.entity.item.ItemEntity item) {
        BlockState ground = level.getBlockState(item.blockPosition().below());
        if (!ground.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), item.getX(), item.getY() + 0.1,
                    item.getZ(), 8, 0.2, 0.1, 0.2, 0.05);
        }
        level.playSound(null, item.getX(), item.getY(), item.getZ(), TremorSounds.CRACK.get(), SoundSource.HOSTILE,
                0.5F, 1.4F);
        item.discard();
    }

    /** The bump takes {@code mob}: it stops where it is and starts to sink. */
    public static void take(ServerLevel level, Mob mob) {
        mob.ejectPassengers();
        mob.stopRiding();
        mob.setNoAi(true);
        mob.setInvulnerable(true);
        mob.setDeltaMovement(Vec3.ZERO);
        mob.noPhysics = true;
        mob.setNoGravity(true);
        mob.addTag(TAG);
        TAKEN.computeIfAbsent(level, l -> new IdentityHashMap<>()).put(mob, 0);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), TremorSounds.CRACK.get(), SoundSource.HOSTILE, 1.2F,
                0.8F);
    }

    /** A mob saved while it was pulled under (frozen and invulnerable for good otherwise) does not come back. */
    public static void onEntityJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity().getTags().contains(TAG)) {
            event.setCanceled(true);
        }
    }

    /** Forgets the mobs being pulled under: their levels are gone. */
    public static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        TAKEN.clear();
    }

    /** Sinks the taken mobs of the level a little further, and removes those that are under. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Map<Mob, Integer> taken = TAKEN.get(level);
        if (taken == null || taken.isEmpty()) {
            return;
        }
        double step = 1.0 / SINK_TICKS;
        for (Iterator<Map.Entry<Mob, Integer>> it = taken.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Mob, Integer> entry = it.next();
            Mob mob = entry.getKey();
            int ticks = entry.getValue() + 1;
            if (mob.isRemoved() || mob.level() != level) {
                it.remove();
                continue;
            }
            double height = Math.max(1, mob.getBbHeight());
            mob.setDeltaMovement(Vec3.ZERO);
            mob.setPos(mob.getX(), mob.getY() - height * step, mob.getZ());
            BlockState ground = level.getBlockState(BlockPos.containing(mob.getX(), mob.getY() + height - 0.5,
                    mob.getZ()));
            if (!ground.isAir()) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), mob.getX(),
                        mob.getY() + height, mob.getZ(), 6, mob.getBbWidth() / 2, 0.1, mob.getBbWidth() / 2, 0.05);
            }
            if (ticks >= SINK_TICKS) {
                it.remove();
                swallow(level, mob);
            } else {
                entry.setValue(ticks);
            }
        }
    }
}
