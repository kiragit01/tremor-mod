package tremor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.renderer.RenderType;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import tremor.Tremor;
import tremor.network.TremorBlackoutPayload;

/**
 * Screen blackout around moves into and out of the hollow (SPEC 9: the player is swallowed, the screen goes dark, the
 * terrain is copied and the player is moved while it is dark); see {@link TremorBlackoutPayload}. The server decides
 * when it starts and ends; the timing of the fade is {@link BlackoutFade}. Main (render) thread only.
 * <p>
 * The black is drawn once the whole HUD is drawn ({@link RenderGuiEvent.Post} at the lowest priority): above every
 * HUD layer, vanilla's and those of other mods whenever they were registered, and above what other mods draw after
 * the HUD at a higher priority; in place of the HUD when another mod hides all of it. So it covers the world and the
 * whole HUD (also with the HUD hidden by F1). It is also drawn over the loading screen of a dimension change
 * ({@link ReceivingLevelScreen}, also when another mod replaces it with a subclass for its dimension), so the move
 * itself happens in the dark. Every other screen (pause menu, inventory, chat, death screen) is drawn above it and
 * stays usable; toasts and vanilla's saving indicator, drawn after screens, show above it too.
 * <p>
 * The blackout belongs to the connection, not to a level: it survives the level change of a dimension switch and is
 * cleared when the player logs out or the client level is dropped without a new one (back to the configuration
 * phase). The player is never left in the dark by a server that does not lift it: it is cleared at once while the
 * player is dead (on the death screen) and on the respawn after a death, and it lifts by itself after a minute of
 * running clock without a payload ({@link BlackoutFade#FAILSAFE_TICKS}).
 */
public final class ClientBlackout {
    private static final BlackoutFade fade = new BlackoutFade();

    private ClientBlackout() {
    }

    public static void accept(TremorBlackoutPayload payload) {
        advance(Minecraft.getInstance());
        fade.set(payload.dark(), payload.fadeTicks());
    }

    /**
     * Advances the fade once per frame, before anything is drawn, so the HUD and screens agree on the opacity; clears
     * it once there is no client level (a dimension switch replaces the level without ever dropping it) and while the
     * player is dead.
     */
    public static void onRenderFrame(RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        advance(mc);
        if (mc.level == null || mc.player != null && mc.player.isDeadOrDying()) {
            fade.clear();
        }
    }

    /** Game bus, lowest priority: the black above the whole HUD. */
    public static void onRenderGui(RenderGuiEvent.Post event) {
        draw(event.getGuiGraphics());
    }

    /**
     * Game bus, lowest priority, cancelled events included: another mod cancelled the HUD, so there is no
     * {@link RenderGuiEvent.Post}; the black still covers the world.
     */
    public static void onRenderGuiCancelled(RenderGuiEvent.Pre event) {
        if (event.isCanceled()) {
            draw(event.getGuiGraphics());
        }
    }

    /** The black over the loading screen of a dimension change; other screens stay above the black. */
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (event.getScreen() instanceof ReceivingLevelScreen) {
            draw(event.getGuiGraphics());
        }
    }

    /**
     * A new player: on the respawn after a death the blackout is cleared. A dimension change makes a new player too,
     * from a living one (as vanilla tells them apart in {@code ClientPacketListener.handleRespawn}); the black stays.
     */
    public static void onRespawn(ClientPlayerNetworkEvent.Clone event) {
        if (event.getOldPlayer().isDeadOrDying()) {
            fade.clear();
        }
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        fade.clear();
    }

    /** Opacity of the black over the screen as of the last frame, 0 (none) to 1 (fully black). */
    public static double opacity() {
        return fade.opacity();
    }

    /**
     * Whether the black covers the screen at all as of the last frame, or a new level is loading under it (the move
     * itself): the player does not see the world clearly yet.
     */
    public static boolean dark() {
        return fade.opacity() > 0 || levelLoading(Minecraft.getInstance());
    }

    private static void advance(Minecraft mc) {
        if (fade.update(System.nanoTime(), mc.isPaused(), levelLoading(mc))) {
            Tremor.LOGGER.warn("Hollow: the server left the screen black for {} s; lifting it",
                    Math.round(BlackoutFade.FAILSAFE_TICKS / BlackoutFade.TICKS_PER_SECOND));
        }
    }

    /** The "Loading terrain" screen of a dimension change (or of joining) is up: the new level is not drawn yet. */
    private static boolean levelLoading(Minecraft mc) {
        return mc.screen instanceof ReceivingLevelScreen;
    }

    private static void draw(GuiGraphics graphics) {
        int alpha = (int) Math.round(fade.opacity() * 255);
        if (alpha > 0) {
            // As vanilla's sleep overlay: no depth test, so it covers whatever was drawn before it.
            graphics.fill(RenderType.guiOverlay(), 0, 0, graphics.guiWidth(), graphics.guiHeight(), alpha << 24);
        }
    }
}
