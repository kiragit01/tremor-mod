package tremor.client.hollow;

import com.mojang.blaze3d.systems.RenderSystem;
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
 * top of the screen where a boss bar would be: a veined blood-red bar in a frame of stone and roots, under the words
 * "the earth closes in", that closes in from both ends toward a heart in the middle as the time runs out; the bar
 * flares and the heart beats with every beat of the node. In its last quarter the bar and the words tremble and the
 * throb grows (texture {@code textures/gui/hollow_time.png}). When it is gone, the ground takes the player (the defeat).
 * Drawn once the player in a hollow has used some of the time (with the limit off it stays 1 and nothing is drawn).
 * Render thread only.
 */
public final class HollowTimeBar {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "hollow_time");
    /** Width and height of the bar (pixels of the GUI scale), the size of a vanilla boss bar. */
    private static final int WIDTH = 182;
    private static final int HEIGHT = 5;
    /** Top of the bar. */
    private static final int TOP = 16;
    /** The texture: the frame (196 x 13, the bar's window at 7, 4), the empty and the full bar, the hearts. */
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(Tremor.MODID,
            "textures/gui/hollow_time.png");
    private static final int TEXTURE_SIZE = 256;
    private static final int TEXTURE_HEIGHT = 64;
    private static final int FRAME_WIDTH = 196;
    private static final int FRAME_HEIGHT = 13;
    private static final int FRAME_INSET_X = 7;
    private static final int FRAME_INSET_Y = 4;
    private static final int EMPTY_V = 16;
    private static final int FULL_V = 24;
    private static final int HEART_V = 32;
    private static final int HEART_BRIGHT_U = 16;
    private static final int HEART = 11;
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

        RenderSystem.enableBlend();
        // What is gone: the dark under the bar.
        graphics.blit(TEXTURE, x, y, 0, EMPTY_V, WIDTH, HEIGHT, TEXTURE_SIZE, TEXTURE_HEIGHT);
        // What is left closes in from both ends toward the heart in the middle; its veins stay where they are.
        int filled = (int) Math.round(WIDTH * left);
        int cut = (WIDTH - filled) / 2;
        if (filled > 0) {
            graphics.blit(TEXTURE, x + cut, y, cut, FULL_V, filled, HEIGHT, TEXTURE_SIZE, TEXTURE_HEIGHT);
            // The throb: the bar flares up on every beat, more so as the end nears.
            float flare = (float) Math.min(0.6, 0.35 * throb * (1 + 2 * danger));
            if (flare > 0.02F) {
                graphics.fill(x + cut, y, x + cut + filled, y + HEIGHT, (int) (flare * 255) << 24 | 0xFF3020);
            }
        }
        // The frame of stone and roots over it.
        graphics.blit(TEXTURE, x - FRAME_INSET_X, y - FRAME_INSET_Y, 0, 0, FRAME_WIDTH, FRAME_HEIGHT, TEXTURE_SIZE,
                TEXTURE_HEIGHT);
        // The heart in the middle, beating with the node.
        int hx = graphics.guiWidth() / 2 - HEART / 2 + dx;
        int hy = y + HEIGHT / 2 - HEART / 2;
        graphics.blit(TEXTURE, hx, hy, 0, HEART_V, HEART, HEART, TEXTURE_SIZE, TEXTURE_HEIGHT);
        RenderSystem.setShaderColor(1, 1, 1, (float) Math.min(1, throb * (0.6 + danger)));
        graphics.blit(TEXTURE, hx, hy, HEART_BRIGHT_U, HEART_V, HEART, HEART, TEXTURE_SIZE, TEXTURE_HEIGHT);
        RenderSystem.setShaderColor(1, 1, 1, 1);
        RenderSystem.disableBlend();

        Component label = Component.translatable("tremor.hud.hollow_time");
        int textWidth = mc.font.width(label);
        double glow = Math.min(1, 0.25 * throb * (1 + 2 * danger));
        int red = lerp(120, 230, Math.min(1, glow + 0.3 * danger));
        graphics.drawString(mc.font, label, graphics.guiWidth() / 2 - textWidth / 2 + dx,
                y - FRAME_INSET_Y - 10 + dy, color(red, 12, 12), true);
    }

    private static int lerp(int from, int to, double share) {
        return (int) Math.round(from + (to - from) * Mth.clamp(share, 0, 1));
    }

    private static int color(int r, int g, int b) {
        return 0xFF000000 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF);
    }
}
