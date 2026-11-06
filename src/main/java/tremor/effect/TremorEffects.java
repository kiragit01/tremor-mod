package tremor.effect;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffect;
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

    /** How long a resonance arrow's strike rings in the ears (ticks). */
    public static final int RESONANCE_TICKS = 80;

    private TremorEffects() {
    }

    public static void register(IEventBus modBus) {
        EFFECTS.register(modBus);
    }

    /** Harmful, amethyst coloured; it does nothing on the server, the client hears it. */
    private static final class Resonance extends MobEffect {
        Resonance() {
            super(MobEffectCategory.HARMFUL, 0xB57CF0);
        }
    }
}
