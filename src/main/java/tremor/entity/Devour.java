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
 * (a player: MCA villagers just vanished and could not be brought back; a pet's owner is told). A mob that matters to
 * someone (named, tamed, a villager: {@link Belly#keeps}) does not die: it is kept alive in the {@link Belly} as it
 * was when taken, and only taken out of the world, with a sound of its own and a line in the chat in place of a death message. Bosses ({@code #c:bosses}), {@code #tremor:undevourable}, invulnerable mobs and mobs a
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
    /**
     * Persistent data of an item a player dropped by dying, saved with it: the game time of the death. The bump leaves
     * the item for {@code contact.spareDeathDropsSeconds} from then on ({@link DeathDrops}; 0 by default: not at all).
     */
    static final String DEATH_DROP = Tremor.MODID + ":death_drop";
    /**
     * Persistent data of an item a player dropped, thrown or by dying: the player's id. The bump keeps such a stack in
     * the {@link Belly} rather than destroying it.
     */
    static final String DROPPED_BY = Tremor.MODID + ":dropped_by";
    /** On a mob being pulled under that is kept in the {@link Belly} (as it was when taken): it is not killed. */
    private static final String KEPT_TAG = "tremor_kept";

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
        if (mob.getTags().contains(KEPT_TAG)) {
            spare(level, mob);
            return;
        }
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

    /**
     * {@code mob}, under the ground and kept in the {@link Belly}, leaves the world alive: no death, so no drops, no
     * experience, no death message or death sound; the clients still hear its hurt as they did, then
     * {@link TremorSounds#BELLY_KEEP}, those it matters to are told ({@link #tellTaken}), the lead drops as it did
     * on a death, and a villager lets go of its bed and workplace as a dying one does.
     */
    private static void spare(ServerLevel level, Mob mob) {
        level.broadcastDamageEvent(mob, new net.minecraft.world.damagesource.DamageSource(level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(SWALLOWED)));
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), TremorSounds.BELLY_KEEP.get(), SoundSource.HOSTILE,
                1.0F, 1.0F);
        tellTaken(level, mob);
        if (mob.isLeashed()) {
            mob.dropLeash(true, true);
        }
        if (mob instanceof net.minecraft.world.entity.npc.Villager villager) {
            villager.releasePoi(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME);
            villager.releasePoi(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE);
            villager.releasePoi(net.minecraft.world.entity.ai.memory.MemoryModuleType.POTENTIAL_JOB_SITE);
            villager.releasePoi(net.minecraft.world.entity.ai.memory.MemoryModuleType.MEETING_POINT);
        }
        mob.discard();
    }

    /**
     * In place of a death message: a pet's owner is told, if online, wherever they are (as the game tells of a pet's
     * death); for any other kept mob every player within 32 blocks.
     */
    private static void tellTaken(ServerLevel level, Mob mob) {
        net.minecraft.network.chat.Component line = net.minecraft.network.chat.Component.translatable(
                "tremor.belly.taken", mob.getDisplayName());
        if (mob instanceof net.minecraft.world.entity.OwnableEntity pet && pet.getOwnerUUID() != null) {
            net.minecraft.server.level.ServerPlayer owner = level.getServer().getPlayerList()
                    .getPlayer(pet.getOwnerUUID());
            if (owner != null) {
                owner.sendSystemMessage(line);
            }
            return;
        }
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            if (player.distanceToSqr(mob) <= 32 * 32) {
                player.sendSystemMessage(line);
            }
        }
    }

    /** A mob swallowed by the ground ({@link #swallow}) drops nothing. Registered by {@link Tremor}. */
    public static void onDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent event) {
        if (event.getEntity() == dying) {
            event.setCanceled(true);
        }
    }

    /**
     * Whether the bump may take {@code mob}: alive, not taken yet, no boss, not undevourable, ridden by no player, and
     * not invulnerable (a server's NPC with {@code Invulnerable:1b} sank and died: {@link #swallow} takes the flag off).
     */
    public static boolean takeable(ServerLevel level, Mob mob) {
        if (!mob.isAlive() || mob.isInvulnerable() || mob.getType().is(Tags.EntityTypes.BOSSES)
                || mob.getType().is(UNDEVOURABLE) || spared(mob)) {
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

    /**
     * What a player drops by dying the bump can leave alone for a while, as the server's owner sets it
     * ({@code contact.spareDeathDropsSeconds}; the user: 0 by default, each server decides): each item is stamped with
     * the time of the death, and not taken while that is less than the setting ago ({@link #takeable}); after that it
     * is an item like any other. With 0 a bump that strikes a player dead eats all they carried in that very tick, and
     * the things of any death near are gone as soon as it comes by. Lowest priority: the drops are what will lie in
     * the world, whatever the listeners before (the crater's, the hollow's, other mods') took of them or added. The
     * stamp is the entity's, not the stack's: a stamped stack that merges into a bigger one of the same item lying
     * there is no longer told from it (the game tells nobody of a merge). Registered by {@link Tremor}.
     */
    public static void onPlayerDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent event) {
        if (event.getEntity() instanceof Player player) {
            long died = player.level().getGameTime();
            for (net.minecraft.world.entity.item.ItemEntity drop : event.getDrops()) {
                drop.getPersistentData().putLong(DEATH_DROP, died);
                drop.getPersistentData().putUUID(DROPPED_BY, player.getUUID());
            }
        }
    }

    /**
     * ...and so with what the menus of a dead player throw out where they died, as the player respawns or leaves: the
     * stack on the cursor and the crafting grid, which are no part of the death's drops. Their time runs from when
     * they are thrown out. Whatever a player throws, dead or alive, is stamped with who threw it ({@link #DROPPED_BY}):
     * every drop of a player but the death's goes through this event. Registered by {@link Tremor}.
     */
    public static void onToss(net.neoforged.neoforge.event.entity.item.ItemTossEvent event) {
        event.getEntity().getPersistentData().putUUID(DROPPED_BY, event.getPlayer().getUUID());
        if (!event.getPlayer().isAlive()) {
            event.getEntity().getPersistentData().putLong(DEATH_DROP, event.getPlayer().level().getGameTime());
        }
    }

    /**
     * Whether the bump may take {@code item}: one lying about, not one the mod laid down to stay (never despawns) nor,
     * for a while, what a player dropped by dying ({@link #onPlayerDrops}, {@link #onToss}).
     */
    public static boolean takeable(net.minecraft.world.entity.item.ItemEntity item) {
        return item.isAlive() && item.getAge() != UNLIMITED_AGE && !ofFreshDeath(item);
    }

    /** Whether {@code item} was dropped by a player who died no longer ago than the bump leaves such things. */
    private static boolean ofFreshDeath(net.minecraft.world.entity.item.ItemEntity item) {
        long ticks = TremorConfig.COMMON.spareDeathDropsSeconds.get() * 20L;
        if (ticks <= 0) {
            return false; // the default: nothing is left
        }
        net.minecraft.nbt.CompoundTag data = item.getPersistentData();
        return data.contains(DEATH_DROP)
                && DeathDrops.spared(data.getLong(DEATH_DROP), item.level().getGameTime(), ticks);
    }

    /**
     * The bump takes {@code item} at once: it is gone, in the dust of the ground; kept in the {@link Belly} if a player
     * dropped it ({@link #DROPPED_BY}, or the death stamp of 1.4.1, which did not name the player), destroyed else.
     */
    public static void take(ServerLevel level, net.minecraft.world.entity.item.ItemEntity item) {
        BlockState ground = level.getBlockState(item.blockPosition().below());
        if (!ground.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground), item.getX(), item.getY() + 0.1,
                    item.getZ(), 8, 0.2, 0.1, 0.2, 0.05);
        }
        level.playSound(null, item.getX(), item.getY(), item.getZ(), TremorSounds.CRACK.get(), SoundSource.HOSTILE,
                0.5F, 1.4F);
        net.minecraft.nbt.CompoundTag data = item.getPersistentData();
        if (data.hasUUID(DROPPED_BY) || data.contains(DEATH_DROP)) {
            Belly.keep(level, item, data.hasUUID(DROPPED_BY) ? data.getUUID(DROPPED_BY) : null);
        }
        item.discard();
    }

    /** The bump takes {@code mob}: it stops where it is and starts to sink (kept first, as it is, if it matters). */
    public static void take(ServerLevel level, Mob mob) {
        mob.ejectPassengers();
        mob.stopRiding();
        if (Belly.keeps(mob)) {
            Belly.keep(level, mob);
            mob.addTag(KEPT_TAG);
        }
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
