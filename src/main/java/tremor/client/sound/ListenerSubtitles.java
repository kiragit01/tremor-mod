package tremor.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.components.SubtitleOverlay;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.world.phys.Vec3;
import tremor.Tremor;

import java.lang.reflect.Field;

/**
 * Shows the subtitles of the mod's sounds that come from nowhere in particular (relative to the listener: the
 * heartbeat and the hum of an Awakening) without a direction. Vanilla's subtitle overlay takes the coordinates of a
 * relative sound for a place in the world, so it would point its arrow at the world's origin, and turn it as the
 * player moves. The sounds stay relative, so they are heard right in the middle; for each one the overlay is told of
 * it once more, at the listener: of the places a subtitle was heard at, the overlay points to the nearest, and one at
 * the listener has no direction (while the player moves on, it falls a little behind until the next beat).
 * <p>
 * Registered with the sound manager as a listener of played sounds ({@link #register}), so it hears of exactly the
 * sounds the overlay hears of. The overlay is the HUD's own and not public; it is read by reflection (the runtime
 * names in NeoForge 1.21.1 are Mojang's). If that is not possible, it is logged once and vanilla's arrow stays. Main
 * thread only.
 */
final class ListenerSubtitles implements SoundEventListener {
    /** Field of {@link Gui} that holds the subtitle overlay, by its Mojang name. */
    private static final String OVERLAY_FIELD = "subtitleOverlay";
    private static final ListenerSubtitles INSTANCE = new ListenerSubtitles();

    private static boolean registered;
    private static boolean overlayResolved;
    /** The field of the HUD's subtitle overlay, null if it cannot be reached. */
    private static Field overlayField;

    private ListenerSubtitles() {
    }

    /** Starts listening to the sounds played, once (the sound manager keeps its listeners for good). */
    static void register(Minecraft mc) {
        if (!registered) {
            registered = true;
            mc.getSoundManager().addListener(INSTANCE);
        }
    }

    @Override
    public void onPlaySound(SoundInstance sound, WeighedSoundEvents accessor, float range) {
        if (!sound.isRelative() || accessor.getSubtitle() == null
                || !Tremor.MODID.equals(sound.getLocation().getNamespace())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.showSubtitles().get()) {
            return;
        }
        SubtitleOverlay overlay = overlay(mc);
        if (overlay != null) {
            Vec3 at = mc.getSoundManager().getListenerTransform().position();
            overlay.onPlaySound(new SimpleSoundInstance(sound.getLocation(), sound.getSource(), 0, 1,
                    SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, at.x, at.y, at.z,
                    false), accessor, range);
        }
    }

    private static SubtitleOverlay overlay(Minecraft mc) {
        if (!overlayResolved) {
            overlayResolved = true;
            try {
                Field field = Gui.class.getDeclaredField(OVERLAY_FIELD);
                if (!SubtitleOverlay.class.isAssignableFrom(field.getType())) {
                    throw new NoSuchFieldException(OVERLAY_FIELD + " is a " + field.getType().getName());
                }
                field.setAccessible(true);
                overlayField = field;
            } catch (ReflectiveOperationException | RuntimeException e) {
                unreachable(e);
            }
        }
        if (overlayField == null || mc.gui == null) {
            return null;
        }
        try {
            return (SubtitleOverlay) overlayField.get(mc.gui);
        } catch (ReflectiveOperationException | RuntimeException e) {
            overlayField = null;
            unreachable(e);
            return null;
        }
    }

    private static void unreachable(Exception e) {
        Tremor.LOGGER.warn("Awakening sounds: cannot reach the subtitle overlay ({}); the subtitles of the heartbeat "
                + "and the hum point toward the world's origin", e.toString());
    }
}
