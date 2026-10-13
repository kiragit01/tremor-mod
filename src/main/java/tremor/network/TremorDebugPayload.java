package tremor.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import tremor.Tremor;

/**
 * What the debug probe shows its holder ({@code tremor.debug.DebugProbe}): lines of text, made on the server.
 *
 * @param lines at most {@value #MAX_LINES}, each at most {@value #MAX_LINE} characters
 */
public record TremorDebugPayload(List<String> lines) implements CustomPacketPayload {
    public static final Type<TremorDebugPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "debug"));

    public static final StreamCodec<FriendlyByteBuf, TremorDebugPayload> STREAM_CODEC =
            StreamCodec.ofMember(TremorDebugPayload::write, TremorDebugPayload::read);

    public static final int MAX_LINES = 32;
    public static final int MAX_LINE = 512;

    public TremorDebugPayload {
        List<String> kept = new ArrayList<>(Math.min(lines.size(), MAX_LINES));
        for (String line : lines.subList(0, Math.min(lines.size(), MAX_LINES))) {
            kept.add(line.length() > MAX_LINE ? line.substring(0, MAX_LINE) : line);
        }
        lines = List.copyOf(kept);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(lines.size());
        for (String line : lines) {
            buf.writeUtf(line, MAX_LINE);
        }
    }

    private static TremorDebugPayload read(FriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), MAX_LINES);
        List<String> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(buf.readUtf(MAX_LINE));
        }
        return new TremorDebugPayload(lines);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
