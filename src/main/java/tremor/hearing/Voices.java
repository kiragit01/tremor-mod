package tremor.hearing;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import tremor.config.TremorConfig;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Players speaking in a voice chat (the user's wish, 2026-10-21: the tremor reacts to talk and to whatever a microphone
 * picks up). The voice chat's plugin ({@code tremor.compat.VoiceChatPlugin}, only loaded with Simple Voice Chat) hands
 * in the level of each packet of a player's voice ({@link #heard}, from the voice chat's own thread); once per tick of
 * the player's level, a player who spoke since is heard as a vibration in the ground under them, like a step
 * ({@link VibrationListener#spoke}), at most every {@value #INTERVAL_TICKS} ticks, as loud as the loudest of it: nothing
 * up to {@code voice.quietDb}, {@code voice.loudness} from {@code voice.loudDb} on, a whisper {@code voice.whisperFactor}
 * of that. Registered on the game bus by {@link tremor.Tremor}.
 */
public final class Voices {
    /** A player is heard speaking at most this often (ticks): a voice comes in packets, 50 a second. */
    static final int INTERVAL_TICKS = 10;
    /** A player not heard for this long (ticks) is forgotten. */
    private static final int FORGET_TICKS = 1200;

    /** The loudest packet of each player's voice since they were last heard: its level (dBFS), and 1 if whispered. */
    private static final Map<UUID, double[]> SPOKEN = new ConcurrentHashMap<>();
    /** When each player may be heard speaking next (game time); the server thread only. */
    private static final Map<UUID, Long> NEXT = new HashMap<>();

    private Voices() {
    }

    /**
     * A packet of a player's voice, from any thread: its level in dBFS ({@link #level}), and whether it was whispered.
     */
    public static void heard(UUID player, double dbfs, boolean whispering) {
        if (player == null || !Double.isFinite(dbfs)) {
            return;
        }
        SPOKEN.merge(player, new double[]{dbfs, whispering ? 1 : 0}, (old, now) -> old[0] >= now[0] ? old : now);
    }

    /** Once per tick of a level: its players who spoke since are heard, each at most every few ticks. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || SPOKEN.isEmpty()) {
            return;
        }
        TremorConfig.Voice config = TremorConfig.COMMON.voice;
        if (!config.enabled.get()) {
            SPOKEN.clear();
            return;
        }
        long now = level.getGameTime();
        NEXT.values().removeIf(next -> next < now - FORGET_TICKS);
        for (ServerPlayer player : level.players()) {
            UUID id = player.getUUID();
            Long next = NEXT.get(id);
            if (next != null && now < next) {
                continue; // what it says meanwhile is kept, the loudest of it
            }
            double[] spoken = SPOKEN.remove(id);
            if (spoken == null || player.isSpectator()) {
                continue;
            }
            boolean whispered = spoken[1] > 0;
            double loudness = loudness(spoken[0], whispered, config.quietDb.get(), config.loudDb.get(),
                    config.loudness.get(), config.whisperFactor.get());
            if (loudness > 0) {
                NEXT.put(id, now + INTERVAL_TICKS);
                VibrationListener.spoke(player, loudness, String.format(Locale.ROOT, "voice %.0f dB%s", spoken[0],
                        whispered ? ", whispered" : ""));
            }
        }
    }

    /** The level of a packet of sound, in dBFS: 0 at full scale, negative below it, -infinity for silence. */
    public static double level(short[] samples) {
        if (samples == null || samples.length == 0) {
            return Double.NEGATIVE_INFINITY;
        }
        double sum = 0;
        for (short s : samples) {
            sum += (double) s * s;
        }
        double rms = Math.sqrt(sum / samples.length);
        return rms <= 0 ? Double.NEGATIVE_INFINITY : 20 * Math.log10(rms / 32768.0);
    }

    /**
     * How loud a voice at {@code dbfs} is as a vibration: nothing up to {@code quietDb}, rising evenly to
     * {@code loudest} at {@code loudDb} and staying there; a whisper {@code whisperFactor} of that.
     */
    public static double loudness(double dbfs, boolean whispering, double quietDb, double loudDb, double loudest,
                                  double whisperFactor) {
        if (!(dbfs > quietDb) || !(loudDb > quietDb)) {
            return 0;
        }
        double share = Math.min(1, (dbfs - quietDb) / (loudDb - quietDb));
        return loudest * share * (whispering ? whisperFactor : 1);
    }
}
