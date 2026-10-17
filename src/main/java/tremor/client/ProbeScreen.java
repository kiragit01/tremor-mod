package tremor.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The menu of the debug probe ({@link ProbeTool}): what a right click does, the look of the nearest tremor (its stage,
 * held or not), its speed, its route for filming (play once, round and round, stop, undo, clear), and what is shown
 * (the route and the lure points on the ground, the panel with the probe in hand, always or hidden, the answers in the
 * chat or above the hotbar, its own decisions on or off). Each button runs one of the {@code /tremor} commands; the
 * server answers in the chat, or above the hotbar. The game goes on behind it.
 */
final class ProbeScreen extends Screen {
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int ROW = 34;
    private static final int TOP = 30;
    private static final int MAX_BUTTON_WIDTH = 96;

    /** A button of a row: its label, what it does, and whether it can be pressed (not the mode chosen). */
    private record Entry(Component label, Button.OnPress press, boolean active) {
    }

    /** The labels of the rows, drawn above them, and where. */
    private final List<Component> labels = new ArrayList<>();
    private final List<Integer> labelY = new ArrayList<>();

    ProbeScreen() {
        super(Component.translatable("tremor.probe.title"));
    }

    @Override
    protected void init() {
        labels.clear();
        labelY.clear();
        int y = TOP;
        row(y, "click", mode(ProbeTool.Mode.LURE), mode(ProbeTool.Mode.ROUTE), mode(ProbeTool.Mode.LISTEN));
        y += ROW;
        row(y, "look", command("dormant", "tremor stage dormant"), command("alert", "tremor stage alert"),
                command("hunting", "tremor stage hunting"), command("hold", "tremor hold"),
                command("ambush", "tremor ambush"));
        y += ROW;
        row(y, "speed", command("slow", "tremor set speed 1.5"), command("normal", "tremor set reset"),
                command("fast", "tremor set speed 6"));
        y += ROW;
        row(y, "route", command("play", "tremor route go"), command("loop", "tremor route loop"),
                command("stop", "tremor route stop"), command("undo", "tremor route undo"),
                command("clear", "tremor route clear"));
        y += ROW;
        row(y, "show", command("show", "tremor route show"), panel(), answers(),
                command("ai", "tremor ai toggle"));
        y += ROW;
        int width = 100;
        addRenderableWidget(Button.builder(Component.translatable("tremor.probe.button.done"), button -> onClose())
                .bounds((this.width - width) / 2, y, width, BUTTON_HEIGHT).build());
    }

    /** A row of buttons under its label, centred, as wide as the screen allows. */
    private void row(int y, String label, Entry... entries) {
        labels.add(Component.translatable("tremor.probe.row." + label));
        labelY.add(y);
        int count = entries.length;
        int width = Math.min(MAX_BUTTON_WIDTH, (this.width - 2 * GAP - (count - 1) * GAP) / count);
        int x = (this.width - (count * width + (count - 1) * GAP)) / 2;
        for (Entry entry : entries) {
            Button button = Button.builder(entry.label(), entry.press())
                    .bounds(x, y + 11, width, BUTTON_HEIGHT).build();
            button.active = entry.active();
            addRenderableWidget(button);
            x += width + GAP;
        }
    }

    /** Makes a right click do {@code mode}; the mode chosen shows as a button that cannot be pressed. */
    private Entry mode(ProbeTool.Mode mode) {
        return new Entry(Component.translatable("tremor.probe.button." + mode.name().toLowerCase(java.util.Locale.ROOT)),
                button -> {
                    ProbeTool.mode = mode;
                    rebuildWidgets();
                }, ProbeTool.mode != mode);
    }

    /** Where the panel of the probe shows: with it in hand, always, hidden, and round again. */
    private Entry panel() {
        return new Entry(ProbeTool.panel.label(), button -> {
            ProbeTool.panel(ProbeTool.panel.next());
            rebuildWidgets();
        }, true);
    }

    /** The short answers of the server in the chat, or above the hotbar. */
    private Entry answers() {
        return new Entry(Component.translatable("tremor.probe.button.answers." + (ProbeTool.quiet ? "quiet" : "chat")),
                button -> {
                    ProbeTool.quiet(!ProbeTool.quiet);
                    rebuildWidgets();
                }, true);
    }

    /** Runs {@code /command}. */
    private static Entry command(String name, String command) {
        return new Entry(Component.translatable("tremor.probe.button." + name), button -> ProbeTool.command(command),
                true);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        for (int i = 0; i < labels.size(); i++) {
            graphics.drawCenteredString(font, labels.get(i), width / 2, labelY.get(i), 0xA0A0A0);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
