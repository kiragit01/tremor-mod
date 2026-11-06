package tremor.effect;

import net.minecraft.world.effect.MobEffect;
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
