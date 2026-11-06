package tremor.client.sound;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import tremor.effect.TremorEffects;
import tremor.sound.TremorSounds;

/**
 * Struck by a resonance arrow (the effect {@link TremorEffects#RESONANCE}, the user: a small concussion of sound): the
 * ears ring ({@link TremorSounds#RESONANCE_DAZE}, once as it begins), and every sound that starts while it lasts
 * starts detuned, lower and wavering (its channel's pitch, set as it starts: the engine reads the pitch of a sound that
 * does not tick only then). Client side.
 */
public final class ResonanceDaze {
    /** Set on the client thread, read on the sound thread. */
    private static volatile boolean dazed;
    private static boolean was;

    private ResonanceDaze() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        boolean now = player != null && player.hasEffect(TremorEffects.RESONANCE);
        if (now && !was) {
            mc.getSoundManager().play(SimpleSoundInstance.forUI(TremorSounds.RESONANCE_DAZE.get(), 1.0f, 0.9f));
        }
        was = now;
        dazed = now;
    }

    /** Sound thread: a sound that does not tick starts detuned while dazed. */
    public static void onSoundStarted(PlaySoundSourceEvent event) {
        detune(event.getSound(), event.getChannel());
    }

    /** Sound thread: a streamed sound (music, a record) starts detuned while dazed. */
    public static void onStreamStarted(PlayStreamingSourceEvent event) {
        detune(event.getSound(), event.getChannel());
    }

    private static void detune(SoundInstance sound, Channel channel) {
        if (!dazed || sound == null || channel == null
                || sound.getLocation().equals(TremorSounds.RESONANCE_DAZE.get().getLocation())) {
            return;
        }
        double t = System.nanoTime() / 1e9;
        double wave = 0.68 + 0.1 * Math.sin(t * 2 * Math.PI * 0.7) + 0.04 * Math.sin(t * 2 * Math.PI * 3.1);
        channel.setPitch((float) Math.max(0.3, sound.getPitch() * wave));
    }
}
