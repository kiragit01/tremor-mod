package tremor.client.sound;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * A long sound that ends before its time fades out instead of breaking off: its channel is turned down over a few
 * ticks, then it stops (the pull of an Awakening the player got away from, {@code ClientAwakening}). Reaches the
 * channels as {@link WorldSilence} does; without them the sound stops at once. Main thread only.
 */
public final class SoundFades {
    /** Sounds fading out: ticks left, ticks in all. */
    private static final Map<SoundInstance, int[]> FADING = new IdentityHashMap<>();

    private SoundFades() {
    }

    /** Fades every playing instance of {@code sound} out over {@code ticks} client ticks. */
    public static void fadeOut(ResourceLocation sound, int ticks) {
        Map<SoundInstance, ChannelAccess.ChannelHandle> channels = WorldSilence.channels();
        if (channels == null) {
            Minecraft.getInstance().getSoundManager().stop(sound, null);
            return;
        }
        for (SoundInstance instance : channels.keySet()) {
            if (instance.getLocation().equals(sound)) {
                FADING.putIfAbsent(instance, new int[] {ticks, ticks});
            }
        }
    }

    /** Game bus: turns each fading sound down a step, and stops it at the end of its fade. */
    public static void onClientTick(ClientTickEvent.Post event) {
        if (FADING.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Map<SoundInstance, ChannelAccess.ChannelHandle> channels = WorldSilence.channels();
        for (Iterator<Map.Entry<SoundInstance, int[]>> it = FADING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<SoundInstance, int[]> entry = it.next();
            SoundInstance sound = entry.getKey();
            int[] ticks = entry.getValue();
            ChannelAccess.ChannelHandle handle = channels == null ? null : channels.get(sound);
            if (handle == null || --ticks[0] <= 0) {
                mc.getSoundManager().stop(sound);
                it.remove();
                continue;
            }
            // As the engine sets it (SoundEngine.calculateVolume), times what is left of the fade.
            float volume = Mth.clamp(sound.getVolume() * mc.options.getSoundSourceVolume(sound.getSource()), 0, 1)
                    * ticks[0] / ticks[1];
            handle.execute((Channel channel) -> channel.setVolume(volume));
        }
    }

    /** Forgets every fade (the sounds stop with the level anyway). */
    static void clear() {
        FADING.clear();
    }
}
