package tremor.client.hollow;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import tremor.Tremor;
import tremor.network.TremorHollowStatePayload;

/**
 * The time the local player has left in the hollow ({@link TremorHollowStatePayload#breath}, SPEC 9), drawn at the
 * top of the screen where a boss bar would be: a blood-red bar under the words "the earth closes in" that closes in
 * from both ends toward the middle as the time runs out, throbbing with every beat of the node. In its last quarter
 * the bar and the words tremble and the throb grows. When it is gone, the ground takes the player (the defeat).
 * Drawn once the player in a hollow has used some of the time (with the limit off it stays 1 and nothing is drawn).
 * Render thread only.
 */
public final class HollowTimeBar {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "hollow_time");
    /** Width and height of the bar (pixels of the GUI scale), the size of a vanilla boss bar. */
    private static final int WIDTH = 182;
    private static final int HEIGHT = 5;
    /** Top of the bar. */
    private static final int TOP = 14;
    /** From this share of the time left down, the bar and the words tremble. */
    private static final double TREMBLE_BELOW = 0.25;

    private HollowTimeBar() {
    }

    /** Puts the bar above the boss bars' layer, at the top of the screen. */
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.BOSS_OVERLAY, ID, HollowTimeBar::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null) {
            return;
        }
        TremorHollowStatePayload state = ClientHollow.state();
        if (state == null || !(state.breath() < 1)) {
            return;
        }
        double left = Mth.clamp(state.breath(), 0, 1);
        double time = mc.level.getGameTime() + delta.getGameTimeDeltaPartialTick(false);
        // A throb on every beat of the node: sharp at the beat, fading before the next.
        int beat = Math.max(1, state.beatTicks());
        double throb = Math.exp(-6 * ((time % beat) / beat));
        double danger = 1 - left;
        int dx = 0;
        int dy = 0;
        if (left < TREMBLE_BELOW) {
            long tick = (long) time;
            dx = (int) ((tick * 7919) % 3) - 1;
            dy = (int) ((tick * 104729) % 3) - 1;
        }
        int x = graphics.guiWidth() / 2 - WIDTH / 2 + dx;
        int y = TOP + dy;

        // Frame and the dark of what is gone.
        graphics.fill(x - 1, y - 1, x + WIDTH + 1, y + HEIGHT + 1, 0xFF000000);
        graphics.fill(x, y, x + WIDTH, y + HEIGHT, 0xFF1C0404);
        // What is left closes in from both ends.
        int filled = (int) Math.round(WIDTH * left);
        int from = x + (WIDTH - filled) / 2;
        double glow = Math.min(1, 0.25 * throb * (1 + 2 * danger));
        int top = color(lerp(150, 255, glow), lerp(20, 90, glow), lerp(20, 60, glow));
        int bottom = color(lerp(70, 140, glow), 0, 0);
        graphics.fillGradient(from, y, from + filled, y + HEIGHT, top, bottom);
        // The edges it closes in from, darker.
        if (filled > 2) {
            graphics.fill(from, y, from + 1, y + HEIGHT, 0xFF3A0000);
            graphics.fill(from + filled - 1, y, from + filled, y + HEIGHT, 0xFF3A0000);
        }

        Component label = Component.translatable("tremor.hud.hollow_time");
        int textWidth = mc.font.width(label);
        int red = lerp(120, 230, Math.min(1, glow + 0.3 * danger));
        graphics.drawString(mc.font, label, graphics.guiWidth() / 2 - textWidth / 2 + dx, y - 10 + dy,
                color(red, 12, 12), true);
    }

    private static int lerp(int from, int to, double share) {
        return (int) Math.round(from + (to - from) * Mth.clamp(share, 0, 1));
    }

    private static int color(int r, int g, int b) {
        return 0xFF000000 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF);
    }
}
