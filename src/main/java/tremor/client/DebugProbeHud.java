package tremor.client;

import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import tremor.Tremor;
import tremor.item.TremorItems;
import tremor.network.TremorDebugPayload;

/**
 * The lines of the debug probe ({@code tremor.debug.DebugProbe}) at the top left of the screen, on a dim backing like
 * vanilla's debug screen, while the player holds the probe and the server keeps sending them; not while the debug
 * screen (F3) is open or the HUD is hidden. Main thread only.
 */
public final class DebugProbeHud {
    /** Lines older than this are not shown (the server stopped sending: no permission, or the probe put away). */
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
    }

    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "debug_probe"),
                DebugProbeHud::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (mc.options.hideGui || mc.getDebugOverlay().showDebugScreen() || player == null || lines.isEmpty()
                || System.nanoTime() - receivedNanos > STALE_NANOS || !(player.getMainHandItem().is(
                TremorItems.DEBUG_PROBE.get()) || player.getOffhandItem().is(TremorItems.DEBUG_PROBE.get()))) {
            return;
        }
        Font font = mc.font;
        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, font.width(line));
        }
        // Narrow screens (a large GUI scale) get the lines smaller rather than cut off.
        float scale = Math.min(1, (graphics.guiWidth() - 2f * MARGIN) / (widest + 2f));
        graphics.pose().pushPose();
        graphics.pose().scale(scale, scale, 1);
        int y = MARGIN;
        for (String line : lines) {
            graphics.fill(MARGIN - 1, y - 1, MARGIN + font.width(line) + 1, y + LINE_HEIGHT - 1, BACKING);
            graphics.drawString(font, line, MARGIN, y, TEXT, false);
            y += LINE_HEIGHT;
        }
        graphics.pose().popPose();
    }
}
