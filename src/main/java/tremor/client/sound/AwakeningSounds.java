package tremor.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import tremor.client.awakening.ClientAwakening;
import tremor.config.TremorConfig;
import tremor.core.math.Vec3;
import tremor.network.TremorAwakeningPayload;
import tremor.network.TremorAwakeningPayload.Phase;
import tremor.network.TremorHollowStatePayload;
import tremor.sound.TremorSounds;

/**
 * What a player inside the zone of an Awakening hears during its build-up in the real world (SPEC 9 phase 1: "остаётся
 * низкий гул и сердцебиение"): a heartbeat that quickens and grows louder as the zone closes, the low
 * {@linkplain HumSound hum} of the ground swelling with it, and the {@link TremorSounds#AWAKEN} sound once when the
 * hill starts to swallow the target. The world itself falls silent around them ({@link WorldSilence}). Timing and
 * loudness are {@link AwakeningTone}'s.
 * <p>
 * Heard by every living player in the zone ({@link ClientAwakening#inZone}) while the Awakening is in
 * {@link Phase#BUILDUP BUILDUP} or {@link Phase#SWALLOWING SWALLOWING}, the target or not; nothing of it outside the
 * zone. All of it is the player's own: the heartbeat and the hum come from nowhere in particular (relative to the
 * listener; their subtitles show no direction, {@link ListenerSubtitles}), only the awakening comes from the hill. It
 * ends when the phase becomes {@link Phase#HOLLOW HOLLOW} or the event ends (the hum dies away within about a second),
 * when the player leaves the zone, and at once when the client level changes: a swallowed target is moved into the
 * hollow, whose sound is that of SPEC 9 phase 2 ({@link HollowSounds}, played from here in place of all this while the
 * player is in a hollow). After a victory in the hollow, the ground rumbles once where the hill rises and the player
 * comes out ({@link Phase#EMERGING EMERGING}), for everybody near. Main thread only.
 */
public final class AwakeningSounds {
    /** Category of all these sounds: the entity's, like its other sounds. */
    static final SoundSource SOURCE = SoundSource.HOSTILE;
    /** Ticks from the moment a player starts hearing an Awakening to the first heartbeat. */
    private static final int FIRST_BEAT_TICKS = 10;
    /**
     * A player who only starts hearing an Awakening this many ticks after the swallowing started (they came into the
     * zone, or the client only learnt of it then) no longer hears the awakening sound.
     */
    private static final int AWAKEN_LATE_TICKS = 40;
    private static final float AWAKEN_VOLUME = 1.0F;
    /** A player who only learns of an emerging this many ticks after it started no longer hears its rumble. */
    private static final int EMERGE_LATE_TICKS = 40;
    /** Volume of the rumble of the emerging hill: heard up to twice the attenuation distance of its sound. */
    private static final float EMERGE_VOLUME = 2.0F;

    private static ClientLevel owner;
    /** Ticks to the next heartbeat; counts down only while the player hears the Awakening. */
    private static int beatCountdown = FIRST_BEAT_TICKS;
    /** Id of the Awakening whose awakening sound was played, null if none. */
    private static Integer awakened;
    /** Id of the Awakening whose emerging rumble was played, null if none. */
    private static Integer rumbled;

    private AwakeningSounds() {
    }

    /**
     * Beats the heart, keeps the hum going, and sounds the awakening when the swallowing starts; in the hollow, its
     * sounds instead ({@link HollowSounds#tick}); the rumble of an emerging hill when it starts.
     */
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ListenerSubtitles.register(mc);
        if (mc.level != owner) {
            owner = mc.level;
            reset();
        }
        TremorHollowStatePayload hollow = HollowSounds.heard(mc);
        if (hollow != null) {
            HollowSounds.tick(mc, hollow);
            beatCountdown = Math.max(beatCountdown, FIRST_BEAT_TICKS);
            return;
        }
        HollowSounds.idle();
        emerge(mc);
        TremorAwakeningPayload state = heard(mc);
        double tension = state == null ? 0 : AwakeningTone.tension(state.phase() == Phase.SWALLOWING,
                ClientAwakening.progress(mc.level.getGameTime()));
        HumSound.update(mc, state != null, tension, 1);
        if (state == null) {
            // The first beat after coming (back) into the zone takes a moment; going in and out at the edge every
            // tick does not beat faster than inside.
            beatCountdown = Math.max(beatCountdown, FIRST_BEAT_TICKS);
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        if (--beatCountdown <= 0) {
            beat(mc, tension);
            beatCountdown = AwakeningTone.beatTicks(tension);
        }
        if (state.phase() == Phase.SWALLOWING && (awakened == null || awakened != state.id())
                && mc.level.getGameTime() - state.phaseStart() <= AWAKEN_LATE_TICKS) {
            awakened = state.id();
            awaken(mc, state.focus());
        }
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        HumSound.stopNow();
        reset();
    }

    /**
     * The Awakening the local player hears now: the current one of their level ({@link ClientAwakening#state}) while
     * it is in {@link Phase#BUILDUP BUILDUP} or {@link Phase#SWALLOWING SWALLOWING} and the player is in its zone,
     * alive; null otherwise.
     */
    static TremorAwakeningPayload heard(Minecraft mc) {
        LocalPlayer player = mc.player;
        TremorAwakeningPayload state = mc.level == null || player == null || player.isDeadOrDying() ? null
                : ClientAwakening.state();
        if (state == null || state.phase() != Phase.BUILDUP && state.phase() != Phase.SWALLOWING) {
            return null;
        }
        return ClientAwakening.inZone(new Vec3(player.getX(), player.getY(), player.getZ())) ? state : null;
    }

    /** One heartbeat, from nowhere in particular (relative to the listener). */
    private static void beat(Minecraft mc, double tension) {
        double volume = AwakeningTone.beatVolume(TremorConfig.CLIENT.heartbeatVolume.get(), tension);
        if (volume > 0) {
            mc.getSoundManager().play(new SimpleSoundInstance(TremorSounds.PULSE.get().getLocation(), SOURCE,
                    (float) volume, (float) AwakeningTone.beatPitch(tension), SoundInstance.createUnseededRandom(),
                    false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
        }
    }

    /**
     * The ground awakens under the hill: from where it rises, as loud everywhere in the zone. At the pitch of its
     * sound event: the engine clamps every pitch to at least 0.5, so sounds.json asks for none below that; a deeper
     * awakening needs a deeper sound file.
     */
    private static void awaken(Minecraft mc, Vec3 focus) {
        mc.getSoundManager().play(new SimpleSoundInstance(TremorSounds.AWAKEN.get().getLocation(), SOURCE,
                AWAKEN_VOLUME, 1, SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE,
                focus.x(), focus.y(), focus.z(), false));
    }

    /**
     * The ground rumbles once where the hill a victor comes out of rises ({@link Phase#EMERGING EMERGING}), for every
     * player who learns of it as it starts, wherever they are: from the hill, fading out with the distance.
     */
    private static void emerge(Minecraft mc) {
        TremorAwakeningPayload state = mc.level == null ? null : ClientAwakening.state();
        if (state == null || state.phase() != Phase.EMERGING || rumbled != null && rumbled == state.id()
                || mc.isPaused() || mc.level.getGameTime() - state.phaseStart() > EMERGE_LATE_TICKS) {
            return;
        }
        rumbled = state.id();
        Vec3 focus = state.focus();
        mc.getSoundManager().play(new SimpleSoundInstance(TremorSounds.RUMBLE.get().getLocation(), SOURCE,
                EMERGE_VOLUME, 1, SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.LINEAR,
                focus.x(), focus.y(), focus.z(), false));
    }

    private static void reset() {
        beatCountdown = FIRST_BEAT_TICKS;
        awakened = null;
        rumbled = null;
    }
}
