package tremor.sound;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import tremor.Tremor;

/**
 * The mod's sound events (SPEC 13), with sounds of its own (assets/tremor/sounds.json; synthesised by
 * {@code tools/sounds/synth.py}); a resource pack can replace any of them by id.
 */
public final class TremorSounds {
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, Tremor.MODID);

    /** Low rumble of the rock: the entity grows alert. */
    public static final DeferredHolder<SoundEvent, SoundEvent> RUMBLE = register("entity.tremor.rumble");
    /**
     * A listening entity sends a ring of ripples through the ground (SPEC 8, ALERT): one at the start of each ring, so
     * the rings are heard as they are seen.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> RIPPLE = register("entity.tremor.ripple");
    /**
     * The entity rises to its peak, AWAKENING (SPEC 13): a roar heard far, wherever it is. The pull of an Awakening
     * itself is {@link #AWAKEN}, at the player it takes.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> RAGE = register("entity.tremor.rage");
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
     * A rush of air into the light: the player gets out of the hollow over its edge (SPEC 9 "Побег"), as the screen
     * turns white ({@code tremor.client.ClientBlackout}); played by the client, so the move out does not cut it short.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> ESCAPE = register("entity.tremor.escape");
    /** The ground lets the player go: the node is destroyed, an Awakening won (SPEC 9 "Победа"). */
    public static final DeferredHolder<SoundEvent, SoundEvent> RELEASE = register("entity.tremor.release");
    /**
     * Low hum of the ground around a player in the zone of an awakening, played as a loop (SPEC 9: "остаётся низкий
     * гул"); so a loopable sound, unlike the short {@link #RUMBLE}.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> HUM = register("entity.tremor.hum");
    /**
     * The soft ground of the hollow pulling a player in (SPEC 9: "Затягивание"): a slow squelch and creak, played
     * again and again while the player sinks.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> PULL = register("entity.tremor.pull");
    /**
     * A faint, deep thump of the ground under a player in the hollow as the ring of a beat of the node runs under them
     * (SPEC 9 phase 2), unlike the {@link #PULSE} of the node itself.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> THUMP = register("entity.tremor.thump");

    private TremorSounds() {
    }
    /** A resonance arrow drawn: its head starts to ring. */
    public static final DeferredHolder<SoundEvent, SoundEvent> RESONANCE_DRAW = register("item.tremor.resonance_arrow.draw");
    /** A resonance arrow flying: a whirring ring. */
    public static final DeferredHolder<SoundEvent, SoundEvent> RESONANCE_FLIGHT = register("item.tremor.resonance_arrow.flight");
    /** A resonance arrow bursts into its pulse. */
    public static final DeferredHolder<SoundEvent, SoundEvent> RESONANCE_HIT = register("item.tremor.resonance_arrow.hit");
    /** Struck by a resonance arrow: the ears ring. */
    public static final DeferredHolder<SoundEvent, SoundEvent> RESONANCE_DAZE = register("entity.tremor.resonance.daze");
    /** A listener hears and sends its signal. */
    public static final DeferredHolder<SoundEvent, SoundEvent> LISTENER_PING = register("entity.tremor.listener.ping");
    /** A listener breathes. */
    public static final DeferredHolder<SoundEvent, SoundEvent> LISTENER_BREATH = register("entity.tremor.listener.breath");
    /** A listener's signal reaches its tremor. */
    public static final DeferredHolder<SoundEvent, SoundEvent> LISTENER_RELAY = register("entity.tremor.listener.relay");
    /** Rock drawn out of a ceiling by a stalactite crawler. */
    public static final DeferredHolder<SoundEvent, SoundEvent> CRAWLER_GRIND = register("entity.tremor.crawler.grind");
    /** A crawler creeping over the ceiling: a soft scrape of stone (no step: the user). */
    public static final DeferredHolder<SoundEvent, SoundEvent> CRAWLER_CREEP = register("entity.tremor.crawler.creep");
    /** A crawler crackling before it drops its stalactites, its last snap as they come off. */
    public static final DeferredHolder<SoundEvent, SoundEvent> CRAWLER_CRACKLE = register("entity.tremor.crawler.crackle");
    /** A crawler going away into the rock, driven off by a light or its block broken: quiet. */
    public static final DeferredHolder<SoundEvent, SoundEvent> CRAWLER_SINK = register("entity.tremor.crawler.sink");
    /** A frozen tremor drawing in before its ring goes out: a low hum rising. */
    public static final DeferredHolder<SoundEvent, SoundEvent> ECHO_CHARGE = register("entity.tremor.echo.charge");
    /** Its echo found the player who hears it: a dull knock from below (to them only). */
    public static final DeferredHolder<SoundEvent, SoundEvent> ECHO_FOUND = register("entity.tremor.echo.found");
    /** A digesting wave of the hollow running at the player: a rumble swelling to a crunch as it reaches them. */
    public static final DeferredHolder<SoundEvent, SoundEvent> DIGEST_WAVE = register("entity.tremor.digest.wave");

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Tremor.MODID, path);
        return SOUNDS.register(path, () -> SoundEvent.createVariableRangeEvent(id));
    }
}
