package tremor.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import tremor.client.ClientTremor;
import tremor.config.TremorConfig;
import tremor.core.math.Vec3;
import tremor.sound.TremorSounds;

/**
 * The rustle of the ground under the moving bump (SPEC 13): a looping sound at the interpolated bump centre of the
 * entity in the current level ({@link ClientTremor#presence()}), its volume and pitch following the bump's speed,
 * height and stage ({@link RustleTone}), eased so that they never jump. It is heard only within the attenuation
 * distance of the sound event (assets/tremor/sounds.json: 20 blocks); the sound engine stretches that distance by a
 * volume above 1 at the start, so every sound starts silent and fades in.
 * <p>
 * {@link #onClientTick} starts one once the rustle of the entity becomes audible (it starts moving, surfaces, or
 * appears) within that distance of the listener. A sound fades out and stops itself when it has been silent for a
 * moment, or when its entity is gone, respawned or in a level the player left; it stops at once when the listener is
 * out of hearing ({@link RustleTone#inHearing}) or the player mutes hostile sounds. A new one starts with the next
 * audible movement in hearing, and shows its subtitle again. Logging out stops it at once (leaving a level stops all
 * sounds anyway). Main thread only.
 */
public final class RustleSound extends AbstractTickableSoundInstance {
    private static final SoundSource SOURCE = SoundSource.HOSTILE;
    /** Seconds per client tick: the step of the easing. */
    private static final double TICK_SECONDS = 1.0 / 20;
    /** Volumes below this count as silent. */
    private static final double SILENT = 0.005;
    /** A sound silent for this many ticks while its entity is still there stops (a stand, a dive). */
    private static final int QUIET_TICKS = 20;
    /**
     * Ticks to wait before starting again after a sound that did not play (no free channel, or another mod replaced
     * it), so a sound that cannot play is not retried every tick.
     */
    private static final int RETRY_TICKS = 40;
    /** Picks the sound whose attenuation distance {@link #hearingRange} reads, reseeded for every read. */
    private static final RandomSource RANGE_PICK = RandomSource.create(0);

    /** The sound of the entity now, null if none plays. */
    private static RustleSound current;
    private static int retryDelay;

    private final ClientLevel level;
    private final int instance;
    private double gain;
    private double tone;
    private int quietTicks;

    private RustleSound(ClientLevel level, ClientTremor.Presence presence) {
        super(TremorSounds.RUSTLE.get(), SOURCE, SoundInstance.createUnseededRandom());
        this.level = level;
        this.instance = presence.instance();
        this.looping = true;
        this.delay = 0;
        this.volume = 0;
        this.tone = RustleTone.pitch(presence.stage(), presence.velocity().length());
        this.pitch = (float) tone;
        moveTo(presence.center());
    }

    /**
     * Starts the rustle of the entity in the current level once it is audible, the listener is in hearing and none
     * plays for it; forgets a sound that has stopped, and stops one that is out of hearing or muted.
     */
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        SoundManager sounds = mc.getSoundManager();
        if (retryDelay > 0) {
            retryDelay--;
        }
        boolean audible = audible(mc);
        net.minecraft.world.phys.Vec3 listener = sounds.getListenerTransform().position();
        double range = hearingRange(sounds);
        if (current != null) {
            if (current.level != mc.level) {
                sounds.stop(current);
                current = null;
            } else if (current.isStopped()) {
                current = null;
            } else if (!audible || !RustleTone.inHearing(
                    Math.sqrt(listener.distanceToSqr(current.x, current.y, current.z)), range, true)) {
                // Muted: once the engine has dropped its channel for a muted category it still counts it as active,
                // so it would block a new one for good. Out of hearing it is silent anyway, and a new one shows its
                // subtitle again.
                current.stop();
                current = null;
            } else if (!sounds.isActive(current)) {
                current = null; // it never played, or the sound engine dropped it
                retryDelay = RETRY_TICKS;
            }
        }
        ClientTremor.Presence presence = mc.level == null ? null : ClientTremor.presence();
        if (presence == null || retryDelay > 0 || current != null && current.instance == presence.instance()
                || !audible || !(targetVolume(mc, presence) >= SILENT)) {
            return;
        }
        Vec3 c = presence.center();
        if (RustleTone.inHearing(Math.sqrt(listener.distanceToSqr(c.x(), c.y(), c.z())), range, false)) {
            current = new RustleSound(mc.level, presence);
            sounds.play(current);
        }
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (current != null) {
            Minecraft.getInstance().getSoundManager().stop(current);
            current = null;
        }
        retryDelay = 0;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (isStopped()) {
            // Stopped by onClientTick. The engine stops its channel after this tick and then drops it, or, if a muted
            // category has already taken the channel, keeps ticking it until it clears all its sounds.
            return;
        }
        ClientTremor.Presence presence = followed();
        double target = 0;
        if (presence != null) {
            moveTo(presence.center());
            target = targetVolume(Minecraft.getInstance(), presence);
            double speed = presence.velocity().length();
            tone = RustleTone.approach(tone, RustleTone.pitch(presence.stage(), speed), TICK_SECONDS);
        }
        gain = RustleTone.approach(gain, target, TICK_SECONDS);
        quietTicks = gain < SILENT && target < SILENT ? quietTicks + 1 : 0;
        if (presence == null ? gain < SILENT : quietTicks >= QUIET_TICKS) {
            stop();
            return;
        }
        volume = (float) gain;
        pitch = (float) tone;
    }

    /** The entity this sound belongs to, or null once it is gone, respawned, or in a level the player left. */
    private ClientTremor.Presence followed() {
        ClientTremor.Presence presence = ClientTremor.presence();
        if (presence == null || presence.instance() != instance || Minecraft.getInstance().level != level) {
            return null;
        }
        return presence;
    }

    private void moveTo(Vec3 position) {
        x = position.x();
        y = position.y();
        z = position.z();
    }

    private static double targetVolume(Minecraft mc, ClientTremor.Presence presence) {
        double base = RustleTone.base(TremorConfig.CLIENT.rustleVolume.get(), mc.options.getSoundSourceVolume(SOURCE));
        return RustleTone.volume(base, presence.stage(), presence.velocity().length(), presence.amplitude());
    }

    /** Whether the player hears hostile sounds at all; a start would not play otherwise. */
    private static boolean audible(Minecraft mc) {
        return mc.options.getSoundSourceVolume(SoundSource.MASTER) > 0 && mc.options.getSoundSourceVolume(SOURCE) > 0;
    }

    /**
     * Distance (blocks) from the listener within which the rustle is heard: the attenuation distance of its sound
     * (sounds.json, so a resource pack may change it), which the engine fixes when a sound starts silent; the same
     * sound of the event every time if it has several.
     */
    private static double hearingRange(SoundManager sounds) {
        WeighedSoundEvents events = sounds.getSoundEvent(TremorSounds.RUSTLE.get().getLocation());
        if (events == null) {
            return SoundManager.EMPTY_SOUND.getAttenuationDistance();
        }
        RANGE_PICK.setSeed(0);
        return events.getSound(RANGE_PICK).getAttenuationDistance();
    }
}
