package tremor.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import tremor.item.TremorItems;
import tremor.network.TremorDebugPayload;

/**
 * The lines of the debug probe ({@code tremor.debug.DebugProbe}) at the top left of the screen, on a dim backing like
 * vanilla's debug screen, while the server keeps sending them (the probe in hand, or the panel pinned; not when it is
 * hidden), with a line on what the multitool does while it is in hand ({@link ProbeTool}); not while the debug screen
 * (F3) is open or the HUD is hidden. Main thread only.
 */
public final class DebugProbeHud {
    /** Lines older than this are not shown (the server stopped sending: the probe put away, or no permission). */
    private static final long STALE_NANOS = 1_000_000_000L;
    private static final int MARGIN = 2;
    private static final int LINE_HEIGHT = 10;
    private static final int BACKING = 0x90505050;
    private static final int TEXT = 0xE0E0E0;

    private static List<String> lines = List.of();
    private static long receivedNanos;

    private DebugProbeHud() {
    }

    public static void accept(TremorDebugPayload payload) {
        lines = payload.lines();
        receivedNanos = System.nanoTime();
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        lines = List.of();
        ProbeTool.forget();
    }

    public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("debug_probe", (gui, graphics, partialTick, width, height) -> render(graphics));
    }

    private static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (mc.options.hideGui || mc.options.renderDebug || player == null) {
            return;
        }
        boolean held = player.getMainHandItem().is(TremorItems.DEBUG_PROBE.get())
                || player.getOffhandItem().is(TremorItems.DEBUG_PROBE.get());
        // Nothing of late: the probe put away, the panel hidden (sent empty, as it is at first), or no permission.
        boolean fresh = !lines.isEmpty() && System.nanoTime() - receivedNanos <= STALE_NANOS;
        if (!fresh && !held) {
            return;
        }
        List<String> shown = new java.util.ArrayList<>();
        if (held) {
            // How to use it shows while it is in hand, the panel hidden or not.
            shown.add(net.minecraft.network.chat.Component.translatable("tremor.probe.hint", ProbeTool.mode.label())
                    .getString());
        }
        if (fresh) {
            shown.addAll(lines);
        }
        Font font = mc.font;
        int widest = 0;
        for (String line : shown) {
            widest = Math.max(widest, font.width(line));
        }
        // Narrow screens (a large GUI scale) get the lines smaller rather than cut off.
        float scale = Math.min(1, (graphics.guiWidth() - 2f * MARGIN) / (widest + 2f));
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1);
        int y = MARGIN;
        for (String line : shown) {
            graphics.fill(MARGIN - 1, y - 1, MARGIN + font.width(line) + 1, y + LINE_HEIGHT - 1, BACKING);
            graphics.drawString(font, line, MARGIN, y, TEXT, false);
            y += LINE_HEIGHT;
        }
        graphics.pose().popPose();
    }
}
