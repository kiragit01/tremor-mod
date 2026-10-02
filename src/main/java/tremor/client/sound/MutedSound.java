package tremor.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;

import java.util.concurrent.CompletableFuture;

/**
 * A ticking sound of the world started while the world falls silent around the player ({@link WorldSilence}), played
 * in place of the sound itself: everything is the sound's, it goes on ticking and stopping itself, except that its
 * volume is multiplied by the {@linkplain WorldSilence#gain() gain of the silence}. The sound engine sets the volume
 * of a ticking sound from it every tick, so it follows the silence down and back up while it plays, and goes on
 * unchanged once the silence is over.
 * <p>
 * The sound engine knows only this wrapper: stopping the sound itself through the sound manager does not reach it, so
 * {@link WorldSilence} wraps only sounds that stop themselves. A sound louder than 1 (the excess only makes it carry
 * further, the engine plays at most full volume) is muted less near it, and carries less far when it starts muted,
 * since the engine takes its reach from its volume when it starts.
 * <p>
 * A loop that was already playing when the silence started is <i>adopted</i>: played again as a wrapper, and stopped
 * itself. Until the engine has let go of it, the engine still ticks it, so the wrapper does not; the loop is ticked
 * once per tick throughout.
 */
final class MutedSound implements TickableSoundInstance {
    private final TickableSoundInstance sound;
    /** Whether the sound was playing before and is played again here ({@link WorldSilence}'s adoption). */
    private final boolean adopted;
    /** The adopted sound while the engine may still hold (and tick) it; null once it does not. */
    private TickableSoundInstance stillHeld;

    /**
     * @param adopted whether the sound was already playing and is played again as this wrapper; it may then still be
     *                in the engine for a few ticks, and keeps playing (silent at worst) whatever its volume
     */
    MutedSound(TickableSoundInstance sound, boolean adopted) {
        this.sound = sound;
        this.adopted = adopted;
        this.stillHeld = adopted ? sound : null;
    }

    @Override
    public float getVolume() {
        return sound.getVolume() * WorldSilence.gain();
    }

    /**
     * A sound muted to nothing still starts, silent, so that it is heard again when the silence lifts; so does an
     * adopted one, which was playing.
     */
    @Override
    public boolean canStartSilent() {
        return adopted || sound.canStartSilent() || WorldSilence.gain() <= 0;
    }

    @Override
    public boolean isStopped() {
        return sound.isStopped();
    }

    /** Ticks the sound, unless the engine still holds it (adopted) and ticks it itself. */
    @Override
    public void tick() {
        if (stillHeld != null) {
            if (Minecraft.getInstance().getSoundManager().isActive(stillHeld)) {
                return;
            }
            stillHeld = null;
        }
        sound.tick();
    }

    @Override
    public boolean canPlaySound() {
        return sound.canPlaySound();
    }

    @Override
    public ResourceLocation getLocation() {
        return sound.getLocation();
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        return sound.resolve(manager);
    }

    @Override
    public Sound getSound() {
        return sound.getSound();
    }

    @Override
    public SoundSource getSource() {
        return sound.getSource();
    }

    @Override
    public boolean isLooping() {
        return sound.isLooping();
    }

    @Override
    public boolean isRelative() {
        return sound.isRelative();
    }

    @Override
    public int getDelay() {
        return sound.getDelay();
    }

    @Override
    public float getPitch() {
        return sound.getPitch();
    }

    @Override
    public double getX() {
        return sound.getX();
    }

    @Override
    public double getY() {
        return sound.getY();
    }

    @Override
    public double getZ() {
        return sound.getZ();
    }

    @Override
    public Attenuation getAttenuation() {
        return sound.getAttenuation();
    }

    @Override
    public CompletableFuture<AudioStream> getStream(SoundBufferLibrary soundBuffers, Sound sound, boolean looping) {
        return this.sound.getStream(soundBuffers, sound, looping);
    }

    @Override
    public String toString() {
        return "Muted" + sound;
    }
}
