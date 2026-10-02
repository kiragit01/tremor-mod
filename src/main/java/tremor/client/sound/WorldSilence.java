package tremor.client.sound;

import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SelectMusicEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import tremor.Tremor;
import tremor.config.TremorConfig;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The world falls silent around a player in the zone of an Awakening (SPEC 9 phase 1: "Тишина: внутри зоны у игрока
 * приглушаются все звуки мира (мобы, погода, музыка), остаётся низкий гул и сердцебиение"). While the player hears the
 * Awakening ({@link AwakeningSounds#heard}), every sound of the world fades to the configured floor
 * ({@code sound.silenceFloor}) over {@link AwakeningTone#SILENCE_FADE_SECONDS}, and comes back as slowly once they no
 * longer do; {@link AwakeningTone#silenceGain} is the curve. The mod's own sounds (the hum and heartbeat that remain,
 * the entity's) and those of the menu ({@link SoundSource#MASTER}) are left alone. No sound is kept from playing.
 * <ul>
 *   <li><b>Sounds that do not tick</b> (nearly all: mobs, steps, blocks, weather, cave sounds, records, note blocks,
 *   music): a sound that starts while the silence is on starts at the volume the engine gave it times the gain of the
 *   silence ({@link #onSoundStarted}). The channel of every such sound, also of those that were playing before, is set
 *   to that every tick ({@link #onClientTick}), and back to the engine's volume when the silence is over. The sound
 *   engine has no public access to its channels; its map of them is read by reflection. If that is not possible, it
 *   is logged once: a sound then keeps the volume it started with.</li>
 *   <li><b>Music</b> is such a sound: the playing track fades with the rest and plays on at the floor (unheard at a
 *   floor of 0), and comes back with the rest; a new one may start in the silence, at the floor. Only if the channels
 *   cannot be reached is the playing track stopped once the silence starts ({@link #onSelectMusic}; the music manager
 *   then waits its usual pause before the next one).</li>
 *   <li><b>Ticking sounds</b> (loops that follow something: the underwater ambience, a minecart, bees, the Nether's
 *   loops; sounds bound to an entity): the engine sets their volume every tick from the sound, so one of vanilla's
 *   that starts while the silence is on plays as a {@link MutedSound}, which follows the silence ({@link #onPlaySound}).
 *   One of vanilla's loops that was already playing when the silence started is played again from its start as a
 *   {@link MutedSound} in its place ({@link #adopt}; through the channels, so only if those can be reached). Those of
 *   other mods are not reached and keep their volume, and so does a short ticking sound that was already playing.</li>
 * </ul>
 * A level change and a logout stop all sounds (the sound engine does it); the silence is then dropped at once, before
 * a sound of the next level starts. Main thread, but for {@link #onSoundStarted} and {@link #onStreamStarted}.
 */
public final class WorldSilence {
    private static final double TICK_SECONDS = 1.0 / AwakeningTone.TICKS_PER_SECOND;
    /**
     * Field of {@link SoundEngine} that maps every playing sound to its channel, by its Mojang name (the runtime names
     * in NeoForge 1.21.1).
     */
    private static final String CHANNELS_FIELD = "instanceToChannel";

    private static ClientLevel owner;
    /** Whether the local player hears an Awakening now, so the world is to be silent. */
    private static boolean silenced;
    /** Depth of the silence, 0..1 ({@link AwakeningTone#silenceDepth}). */
    private static double depth;
    /** Whether the silence is on: the player is in it, or it is still lifting. Also read on the sound thread. */
    private static volatile boolean on;
    /** Gain of the sounds of the world now: 1 when there is no silence. Also read on the sound thread. */
    private static volatile float gain = 1;
    /** The sound engine, as the last sound played showed it; every playing sound was played through it. */
    private static SoundEngine engine;
    private static boolean channelsResolved;
    /** The field of the engine's map of playing sounds to their channels, null if it cannot be reached. */
    private static Field channelsField;
    /** Whether some channel was turned down and may need to be set back. */
    private static boolean channelsTurned;
    /** The track this silence stopped (it stays the music manager's current one for a few ticks). */
    private static SoundInstance stoppedMusic;
    /**
     * Loops that were playing when the silence started and were played again as a {@link MutedSound} ({@link #adopt}),
     * or could not be, while the engine still holds them; by identity.
     */
    private static final Set<SoundInstance> adopted = Collections.newSetFromMap(new IdentityHashMap<>());

    private WorldSilence() {
    }

    /** Gain of the sounds of the world now, the multiplier of a {@link MutedSound}'s volume. */
    static float gain() {
        return gain;
    }

    /**
     * Moves the silence toward the player's situation, and sets the channels of the sounds that do not tick to it
     * (the engine sets their volume only when they start and when a volume setting changes, so this also puts the
     * silence back after such a change); vanilla's loops that were playing before are muted from now on.
     */
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != owner) {
            owner = mc.level;
            reset();
        }
        boolean heard = AwakeningSounds.heard(mc) != null;
        double floor = heard || depth > 0 ? TremorConfig.CLIENT.silenceFloor.get() : 1;
        silenced = heard && floor < 1;
        if (!mc.isPaused()) {
            depth = AwakeningTone.silenceDepth(depth, silenced, TICK_SECONDS);
        }
        gain = (float) AwakeningTone.silenceGain(depth, floor);
        on = silenced || depth > 0;
        if (on || channelsTurned || !adopted.isEmpty()) {
            Map<SoundInstance, ChannelAccess.ChannelHandle> channels = channels();
            if (channels != null) {
                turnDown(channels);
                channelsTurned = depth > 0;
                adopted.removeIf(sound -> !channels.containsKey(sound));
                if (on && !mc.isPaused()) {
                    adopt(mc.getSoundManager(), channels);
                }
            }
        }
    }

    /**
     * Game bus, lowest priority (so it sees the sound other mods settled on): while the silence is on, plays a ticking
     * sound of the world as a {@link MutedSound}. The engine then knows only the wrapper, so only vanilla's ticking
     * sounds are wrapped, which stop themselves; another mod might stop its own through the sound manager, which would
     * then miss it and could leave it looping for good.
     */
    public static void onPlaySound(PlaySoundEvent event) {
        engine = event.getEngine();
        SoundInstance sound = event.getSound();
        if (!on || sound == null || Minecraft.getInstance().level != owner || !ofTheWorld(sound)) {
            return;
        }
        if (sound instanceof TickableSoundInstance ticking && !(sound instanceof MutedSound) && vanilla(sound)) {
            event.setSound(new MutedSound(ticking, false));
        }
    }

    /** Sound thread: a sound that does not tick starts muted while the silence is on. */
    public static void onSoundStarted(PlaySoundSourceEvent event) {
        start(event.getSound(), event.getChannel());
    }

    /** Sound thread: a streamed sound that does not tick (music, a record) starts muted while the silence is on. */
    public static void onStreamStarted(PlayStreamingSourceEvent event) {
        start(event.getSound(), event.getChannel());
    }

    /**
     * Game bus, cancelled events included: only if the channels cannot be reached (so the music cannot fade), stops
     * the playing track once the silence starts; the music manager then waits its usual pause before the next one.
     * The event is where the manager shows its current track.
     */
    public static void onSelectMusic(SelectMusicEvent event) {
        SoundInstance playing = event.getPlayingMusic();
        if (playing == null || playing == stoppedMusic || !silenced || channels() != null) {
            return;
        }
        stoppedMusic = playing;
        Minecraft.getInstance().getSoundManager().stop(playing);
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    /**
     * The client level is about to be replaced or dropped: the silence is dropped before any sound of the next level
     * starts (the engine stops all sounds of this one).
     */
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            reset();
        }
    }

    /**
     * Right after the engine started the channel of a sound (on the sound thread, in the same task): one that does not
     * tick gets its volume times the gain of the silence, before anything of it is heard.
     */
    private static void start(SoundInstance sound, Channel channel) {
        if (on && turnable(sound)) {
            channel.setVolume(volume(sound));
        }
    }

    /** Sets the channel of every sound that does not tick to its volume in the silence. */
    private static void turnDown(Map<SoundInstance, ChannelAccess.ChannelHandle> channels) {
        try {
            for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> entry : channels.entrySet()) {
                SoundInstance sound = entry.getKey();
                if (turnable(sound)) {
                    float volume = volume(sound);
                    entry.getValue().execute(channel -> channel.setVolume(volume));
                }
            }
        } catch (RuntimeException e) {
            channelsField = null;
            unreachable(e);
        }
    }

    /**
     * Plays each of vanilla's loops of the world that was playing before the silence started again as a
     * {@link MutedSound}, from its start, and stops the loop itself once the wrapper plays (if it gets no channel, the
     * loop is left as it is). The wrapper does not tick the loop while the engine still holds it, so the loop goes on
     * ticking once per tick, and stopping itself as before. A short ticking sound is left to end by itself; one that
     * loops by playing again after a delay would be played again by the engine, and is left too.
     */
    private static void adopt(SoundManager manager, Map<SoundInstance, ChannelAccess.ChannelHandle> channels) {
        List<TickableSoundInstance> loops = null;
        try {
            for (SoundInstance sound : channels.keySet()) {
                if (sound instanceof TickableSoundInstance ticking && !(sound instanceof MutedSound)
                        && sound.isLooping() && sound.getDelay() <= 0 && !ticking.isStopped()
                        && ofTheWorld(sound) && vanilla(sound) && !adopted.contains(sound)) {
                    if (loops == null) {
                        loops = new ArrayList<>();
                    }
                    loops.add(ticking);
                }
            }
        } catch (RuntimeException e) {
            channelsField = null;
            unreachable(e);
            return;
        }
        if (loops == null) {
            return;
        }
        for (TickableSoundInstance loop : loops) {
            adopted.add(loop);
            MutedSound muted = new MutedSound(loop, true);
            manager.play(muted);
            if (manager.isActive(muted)) {
                manager.stop(loop);
            }
        }
    }

    /** A sound whose channel is set here: one of the world that does not tick (the engine sets those itself). */
    private static boolean turnable(SoundInstance sound) {
        return !(sound instanceof TickableSoundInstance) && ofTheWorld(sound);
    }

    /** A sound the silence is about: not the mod's own, not the menu's. */
    private static boolean ofTheWorld(SoundInstance sound) {
        return !Tremor.MODID.equals(sound.getLocation().getNamespace()) && sound.getSource() != SoundSource.MASTER;
    }

    /** One of vanilla's sound classes, which stop themselves (so they may play as a {@link MutedSound}). */
    private static boolean vanilla(SoundInstance sound) {
        return sound.getClass().getName().startsWith("net.minecraft.");
    }

    /** The volume the engine gives the sound ({@code SoundEngine.calculateVolume}), times the gain of the silence. */
    private static float volume(SoundInstance sound) {
        float category = Minecraft.getInstance().options.getSoundSourceVolume(sound.getSource());
        return Mth.clamp(sound.getVolume() * category, 0.0F, 1.0F) * gain;
    }

    /** The engine's map of playing sounds to their channels (read only on the main thread, like the engine). */
    @SuppressWarnings("unchecked")
    private static Map<SoundInstance, ChannelAccess.ChannelHandle> channels() {
        if (!channelsResolved) {
            channelsResolved = true;
            try {
                Field field = SoundEngine.class.getDeclaredField(CHANNELS_FIELD);
                if (!Map.class.isAssignableFrom(field.getType())) {
                    throw new NoSuchFieldException(CHANNELS_FIELD + " is a " + field.getType().getName());
                }
                field.setAccessible(true);
                channelsField = field;
            } catch (ReflectiveOperationException | RuntimeException e) {
                unreachable(e);
            }
        }
        if (channelsField == null || engine == null) {
            return null;
        }
        try {
            return (Map<SoundInstance, ChannelAccess.ChannelHandle>) channelsField.get(engine);
        } catch (ReflectiveOperationException | RuntimeException e) {
            channelsField = null;
            unreachable(e);
            return null;
        }
    }

    private static void unreachable(Exception e) {
        Tremor.LOGGER.warn("Awakening silence: cannot reach the sound engine's channels ({}); a sound keeps the volume "
                + "it started with while the world falls silent", e.toString());
    }

    /** Back to no silence at once: the sound engine has stopped every sound. */
    private static void reset() {
        silenced = false;
        depth = 0;
        on = false;
        gain = 1;
        channelsTurned = false;
        stoppedMusic = null;
        adopted.clear();
    }
}
