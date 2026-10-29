package tremor.compat;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import tremor.Tremor;
import tremor.hearing.Voices;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The plugin of Tremor for Simple Voice Chat, found by the voice chat through its annotation and never loaded without
 * it (nothing else in the mod refers to it): every packet of a player's voice is decoded and its level handed to
 * {@link Voices}, which makes the tremor hear them speak. One decoder per player (Opus keeps state from one packet to
 * the next), reset at the end of each talk and closed when they leave. Runs on the voice chat's thread.
 */
@ForgeVoicechatPlugin
public final class VoiceChatPlugin implements VoicechatPlugin {
    private volatile VoicechatApi api;
    private final Map<UUID, OpusDecoder> decoders = new ConcurrentHashMap<>();

    @Override
    public String getPluginId() {
        return Tremor.MODID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        this.api = api;
        Tremor.LOGGER.info("Simple Voice Chat found: the tremor hears players speak");
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
        registration.registerEvent(PlayerDisconnectedEvent.class, this::onDisconnected);
    }

    private void onMicrophone(MicrophonePacketEvent event) {
        VoicechatConnection sender = event.getSenderConnection();
        VoicechatApi voicechat = api;
        if (sender == null || voicechat == null || sender.getPlayer() == null) {
            return;
        }
        UUID player = sender.getPlayer().getUuid();
        byte[] opus = event.getPacket().getOpusEncodedData();
        OpusDecoder decoder = decoders.computeIfAbsent(player, id -> voicechat.createDecoder());
        short[] samples;
        // The voice chat's packet thread decodes, a disconnect closes: never both at once (a native decoder).
        synchronized (decoder) {
            if (decoder.isClosed()) {
                return;
            }
            if (opus == null || opus.length == 0) {
                decoder.resetState(); // the end of a talk
                return;
            }
            samples = decoder.decode(opus);
        }
        Voices.heard(player, Voices.level(samples), event.getPacket().isWhispering());
    }

    private void onDisconnected(PlayerDisconnectedEvent event) {
        OpusDecoder decoder = decoders.remove(event.getPlayerUuid());
        if (decoder != null) {
            synchronized (decoder) {
                if (!decoder.isClosed()) {
                    decoder.close();
                }
            }
        }
    }
}
