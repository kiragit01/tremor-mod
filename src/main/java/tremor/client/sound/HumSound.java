package tremor.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundSource;
import tremor.config.TremorConfig;
import tremor.sound.TremorSounds;

/**
 * The low hum of the ground around a player in the zone of an Awakening (SPEC 9 phase 1: "остаётся низкий гул"): a
 * loop of {@link TremorSounds#HUM}, relative to the listener, swelling and rising a little in pitch with the tension of
 * the build-up ({@link AwakeningTone#humVolume}, {@link AwakeningTone#humPitch}) and eased so that it never jumps. At
 * most one plays.
 * <p>
 * {@link AwakeningSounds} sets what it should sound like every client tick ({@link #update}) and starts one when the
 * player hears the Awakening and none plays. It dies away by itself once the player no longer hears it (out of the
 * zone, the event over or in the hollow); it stops at once when the client level changes (the sound engine stops all
 * sounds then anyway), on logout, and when the player mutes hostile sounds. A sound that did not get to play is
 * retried after a while, not every tick.
 */
final class HumSound extends AbstractTickableSoundInstance {
    /** Seconds per client tick: the step of the easing. */
    private static final double TICK_SECONDS = 1.0 / AwakeningTone.TICKS_PER_SECOND;
    /** Volumes below this count as silent. */
    private static final double SILENT = 0.005;
    /**
     * Ticks to wait before starting again after a hum that did not play (no free channel, or another mod replaced
     * it), so a sound that cannot play is not retried every tick.
     */
    private static final int RETRY_TICKS = 40;

    /** The hum now, null if none plays. */
    private static HumSound current;
    private static int retryDelay;
    /** Volume the hum moves toward: 0 once the player does not hear the Awakening. */
    private static double targetVolume;
    /** Pitch the hum moves toward; kept while it dies away. */
    private static double targetPitch = 1;

    private final ClientLevel level;
    private double gain;
    private double tone;

    private HumSound(ClientLevel level) {
        super(TremorSounds.HUM.get(), AwakeningSounds.SOURCE, SoundInstance.createUnseededRandom());
        this.level = level;
        this.looping = true;
        this.delay = 0;
        this.volume = 0;
        this.tone = targetPitch;
        this.pitch = (float) tone;
        this.relative = true;
        this.attenuation = Attenuation.NONE;
    }

    /**
     * Sets what the hum should sound like: {@code heard} whether the player hears the Awakening now, {@code tension}
     * its tension. Forgets a hum that has stopped, stops one that is muted or left behind in another level, and
     * starts one when the player hears the Awakening and none plays.
     */
    static void update(Minecraft mc, boolean heard, double tension) {
        targetVolume = heard ? AwakeningTone.humVolume(TremorConfig.CLIENT.humVolume.get(), tension) : 0;
        if (heard) {
            targetPitch = AwakeningTone.humPitch(tension);
        }
        SoundManager sounds = mc.getSoundManager();
        if (retryDelay > 0) {
            retryDelay--;
        }
        boolean audible = audible(mc);
        if (current != null) {
            if (current.level != mc.level) {
                sounds.stop(current);
                current = null;
            } else if (current.isStopped()) {
                current = null;
            } else if (!audible) {
                // Muted: once the engine has dropped its channel for a muted category it still counts it as active,
                // so it would block a new one for good (as with the rustle).
                current.stop();
                current = null;
            } else if (!sounds.isActive(current)) {
                current = null; // it never played, or the sound engine dropped it
                retryDelay = RETRY_TICKS;
            }
        }
        if (current == null && mc.level != null && retryDelay == 0 && audible && targetVolume >= SILENT) {
            current = new HumSound(mc.level);
            sounds.play(current);
        }
    }

    /** Stops the hum at once (logout; leaving a level stops all sounds anyway). */
    static void stopNow() {
        if (current != null) {
            Minecraft.getInstance().getSoundManager().stop(current);
            current = null;
        }
        retryDelay = 0;
        targetVolume = 0;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (isStopped()) {
            return;
        }
        if (Minecraft.getInstance().level != level) {
            stop();
            return;
        }
        gain = AwakeningTone.humApproach(gain, targetVolume, TICK_SECONDS);
        tone = AwakeningTone.humApproach(tone, targetPitch, TICK_SECONDS);
        if (targetVolume < SILENT && gain < SILENT) {
            stop();
            return;
        }
        volume = (float) gain;
        pitch = (float) tone;
    }

    /** Whether the player hears hostile sounds at all; a start would not play otherwise. */
    private static boolean audible(Minecraft mc) {
        return mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0
                && mc.options.getSoundSourceVolume(AwakeningSounds.SOURCE) > 0;
    }
}
