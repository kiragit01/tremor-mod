package tremor.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * The server's part of a report for a bug report ({@code tremor.debug.DebugReport}), asked for with the debug probe:
 * text the client adds its own part to and copies to the clipboard ({@code tremor.client.ProbeReport}).
 *
 * @param text at most {@value #MAX} characters
 */
public record TremorReportPayload(String text) implements TremorPayload {
    public static final int MAX = 30000;

    public TremorReportPayload {
        text = text.length() > MAX ? text.substring(0, MAX) : text;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(text, MAX);
    }

    static TremorReportPayload read(FriendlyByteBuf buf) {
        return new TremorReportPayload(buf.readUtf(MAX));
    }
}
