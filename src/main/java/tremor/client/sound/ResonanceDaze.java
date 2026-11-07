package tremor.client.sound;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.effect.MobEffectInstance;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
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
 * flanger), and detuned and wavering as it starts. All of it swells in over {@value #SWELL_TICKS} ticks and ebbs away
 * over the last {@value #EBB_TICKS} (nothing at once), and lets go of every source when it is over. Every source the
 * engine has is gone through by its OpenAL name rather than through the engine's channels: the same on every loader,
 * and it takes the sounds already playing too (a loop of ambience, the music). Without EFX only the detuning is left.
 * Client side.
 */
public final class ResonanceDaze {
    /** It swells in over this many ticks and ebbs away over the last this many of the effect. */
    static final int SWELL_TICKS = 6;
    static final int EBB_TICKS = 30;
    /** OpenAL source names are gone through up to this. */
    private static final int MAX_SOURCE = 1024;
    /** At its height the direct path keeps this much of its highs; the echo and the sweep come this loud. */
    private static final float MUFFLED_HF = 0.2f;
    private static final float ECHO_GAIN = 0.9f;
    private static final float SWEEP_GAIN = 0.7f;

    /** How strong it is now, 0..1: set on the client thread, read on the sound thread too. */
    private static volatile float strength;
    private static boolean was;
    /** The EFX objects, made once per OpenAL context ({@code context}); 0 while none. */
    private static long context;
    private static int echoSlot, sweepSlot, muffle, sends;
    private static boolean noEfx;

    private ResonanceDaze() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        MobEffectInstance effect = player == null ? null : player.getEffect(TremorEffects.RESONANCE);
        boolean now = effect != null;
        if (now && !was) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(TremorSounds.RESONANCE_DAZE.get(), 1.0f, 0.9f));
        }
        float target = now ? strength(effect.getDuration(), effect.isInfiniteDuration()) : 0;
        // Swells in step by step, never at once.
        float s = strength + Math.max(-1f / EBB_TICKS, Math.min(1f / SWELL_TICKS, target - strength));
        strength = s;
        if (s > 0 || was) {
            apply(s);
        }
        was = now || s > 0;
    }

    /** How strong it is with {@code left} ticks of the effect left: full, ebbing away over the last ticks. */
    static float strength(int left, boolean infinite) {
        return infinite ? 1 : Math.min(1, left / (float) EBB_TICKS);
    }

    /** Sound thread: a sound that does not tick starts detuned, and takes the effects at once. */
    public static void onSoundStarted(PlaySoundSourceEvent event) {
        started(event.getSound(), event.getChannel());
    }

    /** Sound thread: a streamed sound (music, a record) as well. */
    public static void onStreamStarted(PlayStreamingSourceEvent event) {
        started(event.getSound(), event.getChannel());
    }

    private static void started(SoundInstance sound, Channel channel) {
        float s = strength;
        if (s <= 0 || sound == null || channel == null) {
            return;
        }
        if (!sound.getLocation().equals(TremorSounds.RESONANCE_DAZE.get().getLocation())) {
            double t = System.nanoTime() / 1e9;
            double wave = 1 - s * (0.32 - 0.1 * Math.sin(t * 2 * Math.PI * 0.7) - 0.04 * Math.sin(t * 2 * Math.PI * 3.1));
            channel.setPitch((float) Math.max(0.3, sound.getPitch() * wave));
        }
        apply(s); // a short sound would be over before the next tick
    }

    /** Sets the effects to strength {@code s} and puts every playing source through them (none at 0). */
    private static synchronized void apply(float s) {
        if (!ready()) {
            return;
        }
        EXTEfx.alAuxiliaryEffectSlotf(echoSlot, EXTEfx.AL_EFFECTSLOT_GAIN, ECHO_GAIN * s);
        if (sweepSlot != 0) {
            EXTEfx.alAuxiliaryEffectSlotf(sweepSlot, EXTEfx.AL_EFFECTSLOT_GAIN, SWEEP_GAIN * s);
        }
        EXTEfx.alFilterf(muffle, EXTEfx.AL_LOWPASS_GAINHF, 1 - (1 - MUFFLED_HF) * s);
        int filter = s > 0 ? muffle : EXTEfx.AL_FILTER_NULL;
        int echo = s > 0 ? echoSlot : EXTEfx.AL_EFFECTSLOT_NULL;
        int sweep = s > 0 ? sweepSlot : EXTEfx.AL_EFFECTSLOT_NULL;
        for (int source = 1; source <= MAX_SOURCE; source++) {
            if (!AL10.alIsSource(source)) {
                continue;
            }
            // A filter is copied as it is set: set again each time it changes.
            AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, filter);
            AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, echo, 0, EXTEfx.AL_FILTER_NULL);
            if (sends > 1 && sweepSlot != 0) {
                AL11.alSource3i(source, EXTEfx.AL_AUXILIARY_SEND_FILTER, sweep, 1, EXTEfx.AL_FILTER_NULL);
            }
        }
        AL10.alGetError(); // not to be taken for the engine's own
    }

    /** Whether the EFX objects are there for the current OpenAL context, made if not; false without EFX. */
    private static boolean ready() {
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
        Tremor.LOGGER.info("Resonance: OpenAL EFX ready, {} sends per source", sends);
        return true;
    }
}
