package tremor.client.sound;

import com.mojang.blaze3d.audio.Channel;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.lang.reflect.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.client.event.sound.PlaySoundSourceEvent;
import net.minecraftforge.client.event.sound.PlayStreamingSourceEvent;
import net.minecraftforge.client.event.sound.SoundEngineLoadEvent;
import net.minecraftforge.event.TickEvent;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import tremor.Tremor;
import tremor.effect.TremorEffects;
import tremor.sound.TremorSounds;

/**
 * Struck by a resonance arrow (the effect {@link TremorEffects#RESONANCE}): a concussion of every sound of the game
 * (the user: all of them, breaking and placing, hits, mobs, ambience, music, even the menu's buttons). The ears ring
 * ({@link TremorSounds#RESONANCE_DAZE}) and, through OpenAL's effects (EFX) on every source playing, each sound comes
 * muffled (a low pass on its direct path), with echoes running after it and a slow resonant sweep through it (a
 * flanger), and a sound that is not streamed comes detuned and wavering as it starts. All of it swells in over
 * {@value #SWELL_TICKS} ticks and ebbs away over the last {@value #EBB_TICKS} (nothing at once), and lets go of the
 * sources it took when it is over. Every source the engine has is gone through by its OpenAL name rather than through
 * the engine's channels: the same on every loader, and it takes the sounds already playing too (a loop of ambience,
 * the music). Without EFX only the detuning is left. Client side.
 * <p>
 * Every OpenAL call is made on the engine's sound thread, as the engine makes its own (through its channels' executor,
 * or in the events of a sound starting there): made from the client thread they raced it, a source going between two
 * calls, and an error of ours could be taken for the engine's and drop one of its sounds (the review).
 */
public final class ResonanceDaze {
    /** It swells in over this many ticks and ebbs away over the last this many of the effect. */
    static final int SWELL_TICKS = 6;
    static final int EBB_TICKS = 30;
    /** OpenAL source names are gone through up to the highest one seen and this many beyond (the engine's are low). */
    private static final int SOURCE_MARGIN = 64;
    private static final int MAX_SOURCE = 4096;
    /** At its height the direct path keeps this much of its highs; the echo and the sweep come this loud. */
    private static final float MUFFLED_HF = 0.2f;
    private static final float ECHO_GAIN = 0.9f;
    private static final float SWEEP_GAIN = 0.7f;

    /** How strong it is now, 0..1: set on the client thread, read on the sound thread. */
    private static volatile float strength;
    private static boolean was;
    /** The engine's channels, found once by type (the same on every loader); null if not found. */
    private static ChannelAccess channels;
    private static boolean looked;
    /** The sound engine was loaded anew since the EFX objects were made: they are gone with its old context. */
    private static volatile boolean reloaded;

    // Sound thread only (in apply):
    /** The EFX objects, made once per OpenAL context ({@code context}); 0 while none. */
    private static long context;
    private static int echoSlot, sweepSlot, muffle, sends;
    private static boolean noEfx;
    /** The sources it put through its effects, to let go of at the end (and no others: another mod's may be set). */
    private static final IntOpenHashSet taken = new IntOpenHashSet();
    private static int highest = 256;

    private ResonanceDaze() {
    }

    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        MobEffectInstance effect = player == null ? null : player.getEffect(TremorEffects.RESONANCE.get());
        boolean now = effect != null;
        if (now && !was) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(TremorSounds.RESONANCE_DAZE.get(), 1.0f, 0.9f));
        }
        float target = now ? strength(effect.getDuration(), effect.isInfiniteDuration()) : 0;
        // Swells in step by step, never at once.
        float s = strength + Math.max(-1f / EBB_TICKS, Math.min(1f / SWELL_TICKS, target - strength));
        strength = s;
        if (s > 0 || was) {
            ChannelAccess access = channels(mc);
            if (access != null) {
                access.executeOnChannels(playing -> apply(s));
            }
        }
        was = now || s > 0;
    }

    /** How strong it is with {@code left} ticks of the effect left: full, ebbing away over the last ticks. */
    static float strength(int left, boolean infinite) {
        return infinite ? 1 : Math.min(1, left / (float) EBB_TICKS);
    }

    /** Sound thread: a sound that does not tick starts detuned, and takes the effects at once. */
    public static void onSoundStarted(PlaySoundSourceEvent event) {
        started(event.getSound(), event.getChannel(), true);
    }

    /**
     * Sound thread: a streamed sound (music, a record) takes the effects, but is not detuned: it plays on for minutes
     * after the daze, detuned all the while (the review).
     */
    public static void onStreamStarted(PlayStreamingSourceEvent event) {
        started(event.getSound(), event.getChannel(), false);
    }

    /** The engine was loaded anew (its device or the options changed): the EFX objects are made again. */
    public static void onEngineLoaded(SoundEngineLoadEvent event) {
        reloaded = true;
        looked = false;
    }

    private static void started(SoundInstance sound, Channel channel, boolean detune) {
        float s = strength;
        if (s <= 0 || sound == null || channel == null) {
            return;
        }
        if (detune && !sound.isLooping()
                && !sound.getLocation().equals(TremorSounds.RESONANCE_DAZE.get().getLocation())) {
            double t = System.nanoTime() / 1e9;
            double wave = 1 - s * (0.32 - 0.1 * Math.sin(t * 2 * Math.PI * 0.7) - 0.04 * Math.sin(t * 2 * Math.PI * 3.1));
            channel.setPitch((float) Math.max(0.3, sound.getPitch() * wave));
        }
        apply(s); // a short sound would be over before the next tick
    }

    /** The engine's channels: the field of that type of its sound engine, of the sound manager (no names needed). */
    private static ChannelAccess channels(Minecraft mc) {
        if (!looked) {
            looked = true;
            try {
                channels = (ChannelAccess) fieldOfType(fieldOfType(mc.getSoundManager(), SoundEngine.class),
                        ChannelAccess.class);
            } catch (ReflectiveOperationException | RuntimeException e) {
                channels = null;
                Tremor.LOGGER.warn("Resonance: the sound thread is out of reach ({}), the sounds are only detuned",
                        e.toString());
            }
        }
        return channels;
    }

    private static Object fieldOfType(Object owner, Class<?> type) throws ReflectiveOperationException {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (type.isAssignableFrom(f.getType()) && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    return f.get(owner);
                }
            }
        }
        throw new NoSuchFieldException(type.getSimpleName() + " in " + owner.getClass().getName());
    }

    /**
     * Sound thread: sets the effects to strength {@code s} and puts every playing source through them; at 0 lets go
     * of the sources it took.
     */
    private static synchronized void apply(float s) {
        if (!ready()) {
            return;
        }
        EXTEfx.alAuxiliaryEffectSlotf(echoSlot, EXTEfx.AL_EFFECTSLOT_GAIN, ECHO_GAIN * s);
        if (sweepSlot != 0) {
            EXTEfx.alAuxiliaryEffectSlotf(sweepSlot, EXTEfx.AL_EFFECTSLOT_GAIN, SWEEP_GAIN * s);
        }
        EXTEfx.alFilterf(muffle, EXTEfx.AL_LOWPASS_GAINHF, 1 - (1 - MUFFLED_HF) * s);
        if (s <= 0) {
            for (int source : taken) {
                if (AL10.alIsSource(source)) {
                    set(source, EXTEfx.AL_FILTER_NULL, EXTEfx.AL_EFFECTSLOT_NULL, EXTEfx.AL_EFFECTSLOT_NULL);
                }
            }
            taken.clear();
        } else {
            int top = Math.min(MAX_SOURCE, highest + SOURCE_MARGIN);
            for (int source = 1; source <= top; source++) {
                if (AL10.alIsSource(source)) {
                    // A filter is copied as it is set: set again each time it changes.
                    set(source, muffle, echoSlot, sweepSlot);
                    taken.add(source);
                    highest = Math.max(highest, source);
                }
            }
        }
        AL10.alGetError(); // not to be taken for the engine's own
    }

    private static void set(int source, int filter, int echo, int sweep) {
        AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, filter);
        AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, echo, 0, EXTEfx.AL_FILTER_NULL);
        if (sends > 1 && sweepSlot != 0) {
            AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, sweep, 1, EXTEfx.AL_FILTER_NULL);
        }
    }

    /** Whether the EFX objects are there for the current OpenAL context, made if not; false without EFX. */
    private static boolean ready() {
        if (reloaded) {
            reloaded = false;
            context = 0; // a new context may come at the old one's address, the objects gone with the old
            noEfx = false;
            taken.clear();
        }
        if (noEfx) {
            return false;
        }
        long current = ALC10.alcGetCurrentContext();
        if (current == 0) {
            return false;
        }
        if (current == context) {
            return true;
        }
        long device = ALC10.alcGetContextsDevice(current);
        if (!ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX")) {
            noEfx = true;
            Tremor.LOGGER.info("Resonance: no OpenAL EFX, the sounds are only detuned");
            return false;
        }
        AL10.alGetError(); // an error left over is not taken for ours
        sends = ALC10.alcGetInteger(device, EXTEfx.ALC_MAX_AUXILIARY_SENDS);
        int echo = EXTEfx.alGenEffects();
        EXTEfx.alEffecti(echo, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_ECHO);
        EXTEfx.alEffectf(echo, EXTEfx.AL_ECHO_DELAY, 0.16f);
        EXTEfx.alEffectf(echo, EXTEfx.AL_ECHO_LRDELAY, 0.12f);
        EXTEfx.alEffectf(echo, EXTEfx.AL_ECHO_DAMPING, 0.45f);
        EXTEfx.alEffectf(echo, EXTEfx.AL_ECHO_FEEDBACK, 0.62f);
        EXTEfx.alEffectf(echo, EXTEfx.AL_ECHO_SPREAD, -0.9f);
        echoSlot = EXTEfx.alGenAuxiliaryEffectSlots();
        EXTEfx.alAuxiliaryEffectSloti(echoSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, echo);
        sweepSlot = 0;
        if (sends > 1) {
            int sweep = EXTEfx.alGenEffects();
            EXTEfx.alEffecti(sweep, EXTEfx.AL_EFFECT_TYPE, EXTEfx.AL_EFFECT_FLANGER);
            EXTEfx.alEffecti(sweep, EXTEfx.AL_FLANGER_WAVEFORM, EXTEfx.AL_FLANGER_WAVEFORM_TRIANGLE);
            EXTEfx.alEffectf(sweep, EXTEfx.AL_FLANGER_RATE, 0.45f);
            EXTEfx.alEffectf(sweep, EXTEfx.AL_FLANGER_DEPTH, 0.9f);
            EXTEfx.alEffectf(sweep, EXTEfx.AL_FLANGER_FEEDBACK, 0.75f);
            EXTEfx.alEffectf(sweep, EXTEfx.AL_FLANGER_DELAY, 0.004f);
            sweepSlot = EXTEfx.alGenAuxiliaryEffectSlots();
            EXTEfx.alAuxiliaryEffectSloti(sweepSlot, EXTEfx.AL_EFFECTSLOT_EFFECT, sweep);
        }
        muffle = EXTEfx.alGenFilters();
        EXTEfx.alFilteri(muffle, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
        EXTEfx.alFilterf(muffle, EXTEfx.AL_LOWPASS_GAIN, 1);
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            noEfx = true;
            Tremor.LOGGER.warn("Resonance: OpenAL EFX failed ({}), the sounds are only detuned", error);
            return false;
        }
        context = current;
        taken.clear();
        Tremor.LOGGER.info("Resonance: OpenAL EFX ready, {} sends per source", sends);
        return true;
    }
}
