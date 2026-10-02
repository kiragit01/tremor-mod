package tremor.sound;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import tremor.Tremor;

/**
 * The mod's sound events (SPEC 13). For now they are placeholders that point at vanilla sound files
 * (assets/tremor/sounds.json); a resource pack can replace any of them by id.
 */
public final class TremorSounds {
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, Tremor.MODID);

    /** Low rumble of the rock: the entity grows alert. */
    public static final DeferredHolder<SoundEvent, SoundEvent> RUMBLE = register("entity.tremor.rumble");
    /** Rock cracking: the entity starts hunting. */
    public static final DeferredHolder<SoundEvent, SoundEvent> CRACK = register("entity.tremor.crack");
    /** The ground "sighs": the entity calms down, or sinks away. */
    public static final DeferredHolder<SoundEvent, SoundEvent> SIGH = register("entity.tremor.sigh");
    /** Rustle of the ground while the bump moves (volume and pitch follow speed and stage). */
    public static final DeferredHolder<SoundEvent, SoundEvent> RUSTLE = register("entity.tremor.rustle");
    /** The bump strikes a player. */
    public static final DeferredHolder<SoundEvent, SoundEvent> STRIKE = register("entity.tremor.strike");
    /** Pulse of the heart node during the awakening (stage 4). */
    public static final DeferredHolder<SoundEvent, SoundEvent> PULSE = register("entity.tremor.pulse");
    /** The awakening begins (stage 4). */
    public static final DeferredHolder<SoundEvent, SoundEvent> AWAKEN = register("entity.tremor.awaken");
    /**
     * Low hum of the ground around a player in the zone of an awakening, played as a loop (SPEC 9: "остаётся низкий
     * гул"); so a loopable sound, unlike the short {@link #RUMBLE}.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> HUM = register("entity.tremor.hum");

    private TremorSounds() {
    }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Tremor.MODID, path);
        return SOUNDS.register(path, () -> SoundEvent.createVariableRangeEvent(id));
    }
}
