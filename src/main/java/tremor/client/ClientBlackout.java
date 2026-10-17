package tremor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import tremor.Tremor;
import tremor.config.TremorConfig;
import tremor.network.TremorBlackoutPayload;
import tremor.sound.TremorSounds;

/**
 * Screen blackout around moves into and out of the hollow (SPEC 9: the player is swallowed, the screen goes dark, the
 * terrain is copied and the player is moved while it is dark); see {@link TremorBlackoutPayload}. The server decides
 * when it starts and ends; the timing of the fade is {@link BlackoutFade}. Main (render) thread only.
 * <p>
 * The black is drawn once the whole HUD is drawn ({@link RenderGuiEvent.Post} at the lowest priority; with the HUD
 * hidden by F1, once the frame is drawn, {@link #drawWithoutHud}): above every
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
 * <p>
 * The way out over the edge of the hollow ({@link TremorBlackoutPayload#white}) is white instead: the player runs into
 * the light with a rush of air ({@link TremorSounds#ESCAPE}) and comes back into the real world as from a bright day.
 */
public final class ClientBlackout {
    private static final BlackoutFade fade = new BlackoutFade();
    /** The blackout is the white of the way out, not black; set by every fade out, kept by the fade back. */
    private static boolean white;

    private ClientBlackout() {
    }

    public static void accept(TremorBlackoutPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        advance(mc);
        if (payload.dark()) {
            if (payload.white() && !(white && fade.target())) {
                rush(mc);
            }
            white = payload.white();
        }
        fade.set(payload.dark(), payload.fadeTicks());
    }

    /**
     * The rush of air of the way out, once as the white starts: at the listener, not at a place in the hollow, and
     * short, so it is over before the move out of the hollow stops every sound.
     */
    private static void rush(Minecraft mc) {
        mc.getSoundManager().play(new SimpleSoundInstance(TremorSounds.ESCAPE.getId(), SoundSource.HOSTILE,
                TremorConfig.COMMON.transitionVolume.get().floatValue(), 1, SoundInstance.createUnseededRandom(), false,
                0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
    }

    /**
     * Advances the fade once per frame, before anything is drawn, so the HUD and screens agree on the opacity; clears
     * it once there is no client level (a dimension switch replaces the level without ever dropping it) and while the
     * player is dead.
     */
    public static void onRenderFrame(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            drawWithoutHud(Minecraft.getInstance());
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        advance(mc);
        if (mc.level == null || mc.player != null && mc.player.isDeadOrDying()) {
            fade.clear();
        }
    }

    /**
     * With the HUD hidden (F1) and no screen up, Forge 1.20.1 draws no HUD at all, so there is no {@link RenderGuiEvent}:
     * the black is drawn once the frame is, over the world, the way the HUD is drawn (the GUI projection is still set).
     */
    private static void drawWithoutHud(Minecraft mc) {
        if (!mc.options.hideGui || mc.screen != null || mc.level == null || fade.opacity() <= 0) {
            return;
        }
        com.mojang.blaze3d.vertex.PoseStack view = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();
        view.pushPose();
        view.setIdentity();
        view.translate(0, 0, 1000F - net.minecraftforge.client.ForgeHooksClient.getGuiFarPlane());
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
        GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        draw(graphics);
        graphics.flush();
        view.popPose();
        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
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
            graphics.fill(RenderType.guiOverlay(), 0, 0, graphics.guiWidth(), graphics.guiHeight(),
                    alpha << 24 | (white ? 0xFFFFFF : 0));
        }
    }
}
