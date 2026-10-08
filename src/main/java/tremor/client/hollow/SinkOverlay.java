package tremor.client.hollow;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import org.joml.Matrix4f;
import tremor.Tremor;

/**
 * The darkness closing in on a player the soft ground of the hollow pulls in (SPEC 9 phase 2: "Затягивание"): a
 * black shade from the edges of the view, deeper and closer to the middle as the player sinks
 * ({@link SinkCurve#shade} of {@link HollowSink}); nothing while the ground does not pull.
 * <p>
 * A HUD layer right above vanilla's camera overlays (the portal one is the last) (the vignette, the pumpkin, the powder snow), so the crosshair,
 * the hotbar and the rest of the HUD stay above it. Like the blackout of the moves into and out of the hollow, it is
 * part of what the player sees of the world and is drawn with the HUD hidden (F1) too. The shade is a mesh of
 * concentric rings of quads along ellipses of the screen's proportions, each vertex as dark as the curve says at its
 * radius. Render thread only.
 */
public final class SinkOverlay {
    /** Id of the HUD layer. */
    public static final ResourceLocation ID = new ResourceLocation(Tremor.MODID, "hollow_sink");
    /** Segments of each ring; a multiple of 8, so that vertices fall on the screen's corners. */
    private static final int SEGMENTS = 48;
    /** Rings from the clear middle out to the corners. */
    private static final int RINGS = 12;
    /** One more ring reaches this far beyond the corners, at the darkness of the corners. */
    private static final double BEYOND = 1.1;
    /** Opacity below which nothing is drawn (it would round to nothing). */
    private static final double INVISIBLE = 1 / 255.0;

    private static final float[] COS = new float[SEGMENTS + 1];
    private static final float[] SIN = new float[SEGMENTS + 1];

    static {
        for (int i = 0; i <= SEGMENTS; i++) {
            double angle = 2 * Math.PI * i / SEGMENTS;
            COS[i] = (float) Math.cos(angle);
            SIN[i] = (float) Math.sin(angle);
        }
    }

    private SinkOverlay() {
    }

    /** Mod bus: the layer goes right above vanilla's camera overlays (the portal one is the last). */
    public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.PORTAL.id(), ID.getPath(),
                (gui, graphics, partialTick, width, height) -> render(graphics, partialTick));
    }

    private static void render(GuiGraphics graphics, float partialTick) {
        double sink = HollowSink.at(partialTick);
        if (SinkCurve.edge(sink) < INVISIBLE) {
            return;
        }
        // Radius 1 lies on the corners: the ellipse through them has half-axes of the half-screen times √2.
        float ax = (float) (graphics.guiWidth() * 0.5 * Math.sqrt(2));
        float ay = (float) (graphics.guiHeight() * 0.5 * Math.sqrt(2));
        float cx = graphics.guiWidth() * 0.5F, cy = graphics.guiHeight() * 0.5F;
        Matrix4f pose = graphics.pose().last().pose();
        VertexConsumer out = graphics.bufferSource().getBuffer(RenderType.gui());
        double clear = SinkCurve.clear(sink);
        for (int k = 0; k <= RINGS; k++) {
            double inner = k < RINGS ? clear + (1 - clear) * k / RINGS : 1;
            double outer = k < RINGS ? clear + (1 - clear) * (k + 1) / RINGS : BEYOND;
            int innerColor = black(SinkCurve.shade(inner, sink));
            int outerColor = black(SinkCurve.shade(Math.min(outer, 1), sink));
            if (innerColor == 0 && outerColor == 0) {
                continue;
            }
            float ri = (float) inner, ro = (float) outer;
            for (int i = 0; i < SEGMENTS; i++) {
                // Counter-clockwise as seen on the screen, like the quads of GuiGraphics.fill: facing the viewer.
                corner(out, pose, cx + ax * ri * COS[i], cy + ay * ri * SIN[i], innerColor);
                corner(out, pose, cx + ax * ri * COS[i + 1], cy + ay * ri * SIN[i + 1], innerColor);
                corner(out, pose, cx + ax * ro * COS[i + 1], cy + ay * ro * SIN[i + 1], outerColor);
                corner(out, pose, cx + ax * ro * COS[i], cy + ay * ro * SIN[i], outerColor);
            }
        }
        graphics.flush();
    }

    private static void corner(VertexConsumer out, Matrix4f pose, float x, float y, int argb) {
        out.vertex(pose, x, y, 0).color(argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF, argb >>> 24).endVertex();
    }

    /** Black at the opacity, as ARGB. */
    private static int black(double opacity) {
        int alpha = (int) Math.round(Math.max(0, Math.min(1, opacity)) * 255);
        return alpha << 24;
    }
}
