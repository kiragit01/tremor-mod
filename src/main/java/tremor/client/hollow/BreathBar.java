package tremor.client.hollow;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import tremor.Tremor;
import tremor.network.TremorHollowStatePayload;

/**
 * The air the local player has left in the hollow ({@link TremorHollowStatePayload#breath}, SPEC 9: under the ground
 * the player suffocates), drawn as the vanilla air bubbles over the food bar: ten bubbles that burst one by one, the
 * last one bursting while its share runs out. Drawn once the player in a hollow has used some air (with the limit
 * off the breath stays 1 and nothing is drawn), and only where the game can hurt the player. Render thread only.
 */
public final class BreathBar {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "hollow_breath");
    private static final ResourceLocation AIR = ResourceLocation.withDefaultNamespace("hud/air");
    private static final ResourceLocation AIR_BURSTING = ResourceLocation.withDefaultNamespace("hud/air_bursting");
    /** Bubbles in the bar, as in vanilla. */
    private static final int BUBBLES = 10;
    /** The last bubble shows bursting once less than this share of it is left. */
    private static final double BURSTING = 0.25;

    private BreathBar() {
    }

    /** Puts the bar right above the vanilla air bar, so that it stacks on the right side like the other bars. */
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.AIR_LEVEL, ID, BreathBar::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.gameMode == null || !mc.gameMode.canHurtPlayer()) {
            return;
        }
        TremorHollowStatePayload state = ClientHollow.state();
        if (state == null || !(state.breath() < 1)) {
            return;
        }
        double left = Mth.clamp(state.breath(), 0, 1) * BUBBLES;
        int whole = Mth.ceil(left);
        boolean bursting = whole > 0 && left - (whole - 1) < BURSTING;
        int right = graphics.guiWidth() / 2 + 91;
        int top = graphics.guiHeight() - mc.gui.rightHeight;
        RenderSystem.enableBlend();
        for (int i = 0; i < whole; i++) {
            graphics.blitSprite(bursting && i == whole - 1 ? AIR_BURSTING : AIR, right - i * 8 - 9, top, 9, 9);
        }
        RenderSystem.disableBlend();
        mc.gui.rightHeight += 10;
    }
}
