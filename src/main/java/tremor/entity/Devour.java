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
import tremor.sound.TremorSounds;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Animals and monsters the bump touches are pulled under and gone (the user's wish after the first play: the entity
 * hunts other creatures too and swallows them whole, without a crater): a taken mob stops (no AI, no physics, no
 * damage), sinks into the ground over {@link #SINK_TICKS} with the dust of the ground it sinks through and a crack,
 * and is removed without drops or experience. Bosses ({@code #c:bosses}), {@code #tremor:undevourable} and mobs a
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

    private Devour() {
    }

    /** Whether the bump may take {@code mob}: alive, not taken yet, no boss, not undevourable, ridden by no player. */
    static boolean takeable(ServerLevel level, Mob mob) {
        if (!mob.isAlive() || mob.getType().is(Tags.EntityTypes.BOSSES) || mob.getType().is(UNDEVOURABLE)) {
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

    /** The bump takes {@code mob}: it stops where it is and starts to sink. */
    static void take(ServerLevel level, Mob mob) {
        mob.ejectPassengers();
        mob.stopRiding();
        mob.setNoAi(true);
        mob.setInvulnerable(true);
        mob.setDeltaMovement(Vec3.ZERO);
        mob.noPhysics = true;
        mob.setNoGravity(true);
        TAKEN.computeIfAbsent(level, l -> new IdentityHashMap<>()).put(mob, 0);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), TremorSounds.CRACK.get(), SoundSource.HOSTILE, 1.2F,
                0.8F);
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
                mob.discard();
                it.remove();
            } else {
                entry.setValue(ticks);
            }
        }
    }
}
