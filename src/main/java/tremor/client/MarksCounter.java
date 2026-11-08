package tremor.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import tremor.Tremor;
import tremor.network.TremorMarksPayload;

/**
 * The marks a hushing charm took off, told under the middle of the screen (the user: not a plain line over the hotbar; it comes and
 * goes softly, and the counter is rolled down): "Marks" and the count, its wheel turning down from what it was to what
 * is left, a touch past and back, a soft tick at each number ({@link Odometer}); it moves as the marks' notice in the
 * hollow does ({@link NoticeMotion}). With no mark to take, it says so. Main (render) thread only.
 */
public final class MarksCounter {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "marks_counter");
    /** How long it is shown (ticks), and with no mark to take. */
    private static final double LENGTH = 80;
    private static final double LENGTH_NONE = 55;
    /** The wheel starts to turn once it is in, and turns this long, more for more marks; a touch past and back. */
    private static final double ROLL_START = 9;
    private static final double ROLL_TICKS = 14;
    private static final double ROLL_PER_MARK = 4;
    private static final double ROLL_OVERSHOOT = 1.1;
    /** Its place under the middle of the screen, in sight and clear of the chat, the action bar and the hotbar. */
    private static final int BELOW_MIDDLE = 26;
    /** A wheel's line, a digit's width (pixels), the gap after the word. */
    private static final int LINE = 10;
    private static final int DIGIT = 6;
    private static final int GAP = 4;
    private static final int COLOR = 0xE03434; // the marks' red, a little brighter: it is told in the open too

    private static int from, to;
    /** When it started (game time), NaN while there is none; a new one starts on the next frame. */
    private static double start = Double.NaN;
    private static boolean pending;
    /** The number last ticked, so each number passed ticks once. */
    private static int ticked = Integer.MIN_VALUE;

    private MarksCounter() {
    }

    /** Over the action bar, under the screens. */
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.OVERLAY_MESSAGE, ID, MarksCounter::render);
    }

    public static void accept(TremorMarksPayload payload) {
        from = payload.before();
        to = payload.after();
        pending = true;
        ticked = from;
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            start = Double.NaN;
            pending = false;
            return;
        }
        double time = mc.level.getGameTime() + delta.getGameTimeDeltaPartialTick(false);
        if (pending) {
            start = time;
            pending = false;
        }
        if (Double.isNaN(start)) {
            return;
        }
        boolean none = from == to;
        double length = none ? LENGTH_NONE : LENGTH, t = time - start;
        if (t > length) {
            start = Double.NaN;
            return;
        }
        NoticeMotion.Pose pose = NoticeMotion.at(Math.max(0, t), length, time);
        if (mc.options.hideGui || pose.alpha() < 0.05) {
            return; // the font draws a nearly clear colour opaque
        }
        graphics.pose().pushPose();
        graphics.pose().translate(graphics.guiWidth() / 2f, graphics.guiHeight() / 2f + BELOW_MIDDLE + (float) pose.dy(), 0);
        graphics.pose().scale((float) pose.scale(), (float) pose.scale(), 1);
        int alpha = (int) Math.round(pose.alpha() * 255) << 24;
        if (none) {
            Component text = Component.translatable("tremor.marks.none");
            graphics.drawString(mc.font, text, -mc.font.width(text) / 2, 0, alpha | COLOR, true);
        } else {
            double roll = ROLL_TICKS + ROLL_PER_MARK * Math.min(4, Math.abs(from - to));
            double u = Math.max(0, Math.min(1, (t - ROLL_START) / roll));
            double value = from + (to - from) * NoticeMotion.backOut(u, ROLL_OVERSHOOT);
            int shown = (int) Math.round(Math.max(0, value));
            if (shown != ticked) {
                ticked = shown;
                mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1.7f, 0.25f));
            }
            Component label = Component.translatable("tremor.marks.counter");
            int places = Odometer.places(Math.max(from, to));
            int labelWidth = mc.font.width(label), width = labelWidth + GAP + places * DIGIT - 1;
            graphics.drawString(mc.font, label, -width / 2, 0, alpha | COLOR, true);
            int x0 = -width / 2 + labelWidth + GAP;
            for (Odometer.Glyph g : Odometer.glyphs(value, places)) {
                // A digit fades as its wheel turns it out of the window, and in as it turns it in.
                double fade = pose.alpha() * Math.pow(1 - Math.abs(g.offset()), 1.5);
                if (fade < 0.05) {
                    continue;
                }
                graphics.pose().pushPose();
                graphics.pose().translate(0, (float) (g.offset() * LINE), 0);
                graphics.drawString(mc.font, String.valueOf(g.digit()), x0 + (places - 1 - g.place()) * DIGIT, 0,
                        (int) Math.round(fade * 255) << 24 | COLOR, true);
                graphics.pose().popPose();
            }
        }
        graphics.pose().popPose();
    }
}
