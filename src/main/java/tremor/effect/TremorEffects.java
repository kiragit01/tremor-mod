package tremor.effect;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import tremor.Tremor;

/**
 * The mod's effects. Resonance (1.4.0, the user): struck by a resonance arrow, the ears ring and every sound starts
 * detuned and wavering for a few seconds (client side, {@code tremor.client.sound.ResonanceDaze}).
 */
public final class TremorEffects {
    private static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(Registries.MOB_EFFECT,
            Tremor.MODID);

    public static final DeferredHolder<MobEffect, MobEffect> RESONANCE = EFFECTS.register("resonance",
            () -> new Resonance());

    /** How long a resonance arrow's strike rings in the ears (ticks; the user: a little longer than 4 s). */
    public static final int RESONANCE_TICKS = 140;
    /** A mob dazed loses what it was after now and then: once a second, at this chance. */
    static final float LOSE_TARGET_CHANCE = 0.35f;
    /** A ringing player sneaking makes a step this often (ticks). */
    static final int RINGING_STEP_TICKS = 10;
    private static final java.util.Map<net.minecraft.world.entity.player.Player, net.minecraft.world.phys.Vec3> LAST =
            new java.util.WeakHashMap<>();

    private TremorEffects() {
    }

    public static void register(IEventBus modBus) {
        EFFECTS.register(modBus);
    }

    /**
     * Harmful, amethyst coloured. The client hears it; on the server a dazed mob now and then loses what it was after
     * and stops where it is, looking about (the user: disoriented), and whatever it is makes more noise
     * ({@code tremor.hearing.VibrationListener}).
     */
    private static final class Resonance extends MobEffect {
        Resonance() {
            super(MobEffectCategory.HARMFUL, 0xB57CF0);
        }

        @Override
        public boolean shouldApplyEffectTickThisTick(int duration, int amplifier) {
            return duration % RINGING_STEP_TICKS == 0;
        }

        @Override
        public boolean applyEffectTick(LivingEntity entity, int amplifier) {
            if (entity.level().isClientSide()) {
                return true;
            }
            // Sneaking makes no step the game hears; ringing, its steps ring anyway (the user: even sneaking a little).
            if (entity instanceof net.minecraft.world.entity.player.Player player) {
                // Where it was at the last of these ticks (its old position is this tick's already on the server).
                net.minecraft.world.phys.Vec3 before = LAST.put(player, player.position());
                if (player.isCrouching() && player.onGround() && before != null
                        && before.subtract(player.position()).horizontalDistance() > 0.15) {
                    player.level().gameEvent(player, net.minecraft.world.level.gameevent.GameEvent.STEP,
                            player.position());
                }
            }
            if (entity instanceof Mob mob && mob.tickCount % 20 < RINGING_STEP_TICKS
                    && mob.getRandom().nextFloat() < LOSE_TARGET_CHANCE) {
                mob.setTarget(null);
                mob.getNavigation().stop();
                float yaw = mob.getYRot() + (mob.getRandom().nextFloat() - 0.5f) * 240;
                mob.getLookControl().setLookAt(mob.getX() - Math.sin(Math.toRadians(yaw)) * 4, mob.getEyeY(),
                        mob.getZ() + Math.cos(Math.toRadians(yaw)) * 4);
            }
            return true;
        }
    }
}
