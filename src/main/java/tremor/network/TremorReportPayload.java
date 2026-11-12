package tremor.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import tremor.Tremor;

/**
 * The server's part of a report for a bug report ({@code tremor.debug.DebugReport}), asked for with the debug probe:
 * text the client adds its own part to and copies to the clipboard ({@code tremor.client.ProbeReport}).
 *
 * @param text at most {@value #MAX} characters
 */
public record TremorReportPayload(String text) implements CustomPacketPayload {
    public static final Type<TremorReportPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "report"));

    public static final StreamCodec<FriendlyByteBuf, TremorReportPayload> STREAM_CODEC =
            StreamCodec.ofMember(TremorReportPayload::write, TremorReportPayload::read);

    public static final int MAX = 30000;

    public TremorReportPayload {
        text = text.length() > MAX ? text.substring(0, MAX) : text;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(text, MAX);
    }

    private static TremorReportPayload read(FriendlyByteBuf buf) {
        return new TremorReportPayload(buf.readUtf(MAX));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
