package tremor.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import tremor.Tremor;

/**
 * The mod's effects. Resonance (1.4.0, the user): struck by a resonance arrow, the ears ring and every sound starts
 * detuned and wavering for a few seconds (client side, {@code tremor.client.sound.ResonanceDaze}).
 */
public final class TremorEffects {
    private static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(ForgeRegistries.MOB_EFFECTS,
            Tremor.MODID);

    public static final RegistryObject<MobEffect> RESONANCE = EFFECTS.register("resonance", Resonance::new);

    /** How long a resonance arrow's strike rings in the ears (ticks; the user: a little longer than 4 s). */
    public static final int RESONANCE_TICKS = 140;
    /** A mob dazed loses what it was after now and then: once a second, at this chance. */
    static final float LOSE_TARGET_CHANCE = 0.35f;
    /** A ringing player sneaking makes a step this often (ticks). */
    static final int RINGING_STEP_TICKS = 10;
    private static final java.util.Map<net.minecraft.world.entity.player.Player, Last> LAST =
            new java.util.WeakHashMap<>();

    /** Where a ringing player was at a tick of the effect, and when (their tick count). */
    private record Last(net.minecraft.world.phys.Vec3 at, int tick) {
    }

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
        public boolean isDurationEffectTick(int duration, int amplifier) {
            return duration % RINGING_STEP_TICKS == 0;
        }

        @Override
        public void applyEffectTick(LivingEntity entity, int amplifier) {
            if (entity.level().isClientSide()) {
                return;
            }
            // Sneaking makes no step the game hears; ringing, its steps ring anyway (the user: even sneaking a little).
            if (entity instanceof net.minecraft.world.entity.player.Player player) {
                // Where it was at the last of these ticks (its old position is this tick's already on the server).
                Last before = LAST.put(player, new Last(player.position(), player.tickCount));
                // A place of the last daze, long ago (or before a respawn), tells nothing (the review).
                boolean recent = before != null && player.tickCount - before.tick() > 0
                        && player.tickCount - before.tick() <= 2 * RINGING_STEP_TICKS;
                if (player.isCrouching() && player.onGround() && recent
                        && before.at().subtract(player.position()).horizontalDistance() > 0.15) {
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
        }
    }
}
