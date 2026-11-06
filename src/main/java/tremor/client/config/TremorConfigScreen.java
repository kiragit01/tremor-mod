package tremor.client.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.ModConfigSpec;
import tremor.config.TremorConfig;

/**
 * The mod's own settings screen (the user: a big screen of every setting, sliders for the numbers, switches for the
 * rest): the sections of the common and the client config on the left, the settings of the one chosen on the right,
 * each as fits its kind (a slider over its range, on and off, the choices of an enum, a text field; a list is left to
 * the file), its description and default on hover, a button to put it back. A change is saved to the file at once and
 * applied, as an edit in NeoForge's config screen is (a slider's when it is let go). Connected to a server that is not
 * this game's own, the common settings live there: they are shown, not changed. NeoForge's own screen is a button away.
 */
public final class TremorConfigScreen extends Screen {
    private static final String KEY = "tremor.configuration.";
    private static final int SIDE = 150;
    /** The section buttons: this tall, this far apart; scrolled by the wheel when they do not fit. */
    private static final int TAB = 14;
    private static final int TAB_STEP = 15;
    private static final int ROW = 22;

    private final Screen parent;
    private final ModContainer container;
    private final List<Section> sections = new ArrayList<>();
    private int selected;
    private int sideScroll;
    private EditBox search;
    private Rows rows;

    /** A section: the settings under one key at the top of a config (or the loose ones at its top). */
    private record Section(Component title, boolean client, List<Item> items) {
    }

    /** A setting, or a header ({@code value} null) over the settings of a deeper section. */
    private record Item(ModConfigSpec spec, ModConfigSpec.ConfigValue<Object> value, Component name, Component header) {
    }

    public TremorConfigScreen(ModContainer container, Screen parent) {
        super(Component.translatable("tremor.screen.config.title"));
        this.container = container;
        this.parent = parent;
        collect(TremorConfig.COMMON_SPEC, false);
        collect(TremorConfig.CLIENT_SPEC, true);
    }

    /** Opens at section {@code index} with {@code query} typed in the search (the autotest's configscreen step). */
    public TremorConfigScreen at(int index, String query) {
        selected = Math.max(0, Math.min(sections.size() - 1, index));
        this.query = query;
        return this;
    }

    private String query = "";

    // ---- what there is ----

    @SuppressWarnings("unchecked")
    private void collect(ModConfigSpec spec, boolean client) {
        List<Item> loose = new ArrayList<>();
        for (Map.Entry<String, Object> e : spec.getValues().valueMap().entrySet()) {
            if (e.getValue() instanceof UnmodifiableConfig sub) {
                List<Item> items = new ArrayList<>();
                walk(spec, sub, e.getKey(), items);
                sections.add(new Section(name(e.getKey(), e.getKey()).copy()
                        .append(client ? Component.literal(" ⌂").withStyle(ChatFormatting.GRAY) : Component.empty()),
                        client, items));
            } else if (e.getValue() instanceof ModConfigSpec.ConfigValue<?> value) {
                loose.add(new Item(spec, (ModConfigSpec.ConfigValue<Object>) value, valueName(value), null));
            }
        }
        if (!loose.isEmpty()) {
            sections.add(client ? sections.size() : 0, new Section(Component.translatable(client
                    ? "tremor.screen.config.client" : "tremor.screen.config.general"), client, loose));
        }
    }

    @SuppressWarnings("unchecked")
    private void walk(ModConfigSpec spec, UnmodifiableConfig config, String path, List<Item> out) {
        List<Map.Entry<String, Object>> deeper = new ArrayList<>();
        for (Map.Entry<String, Object> e : config.valueMap().entrySet()) {
            if (e.getValue() instanceof ModConfigSpec.ConfigValue<?> value) {
                out.add(new Item(spec, (ModConfigSpec.ConfigValue<Object>) value, valueName(value), null));
            } else if (e.getValue() instanceof UnmodifiableConfig) {
                deeper.add(e);
            }
        }
        for (Map.Entry<String, Object> e : deeper) {
            String p = path + "." + e.getKey();
            out.add(new Item(spec, null, null, name(p, e.getKey())));
            walk(spec, (UnmodifiableConfig) e.getValue(), p, out);
        }
    }

    private static Component name(String path, String fallback) {
        String key = KEY + path;
        return I18n.exists(key) ? Component.translatable(key) : Component.literal(fallback);
    }

    private static Component valueName(ModConfigSpec.ConfigValue<?> value) {
        String key = value.getSpec().getTranslationKey();
        List<String> path = value.getPath();
        return key != null && I18n.exists(key) ? Component.translatable(key)
                : Component.literal(path.get(path.size() - 1));
    }

    /** Whether the common settings live on a server that is not this game's own (they are shown, not changed). */
    private static boolean remote() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.getSingleplayerServer() == null;
    }

    // ---- the screen ----

    @Override
    protected void init() {
        int top = 32;
        int fits = Math.max(1, (height - top - 34) / TAB_STEP);
        sideScroll = Math.max(0, Math.min(sideScroll, sections.size() - fits));
        for (int i = sideScroll; i < Math.min(sections.size(), sideScroll + fits); i++) {
            int index = i;
            Component title = sections.get(i).title();
            Button b = Button.builder(Component.literal(font.plainSubstrByWidth(title.getString(), SIDE - 18)),
                    btn -> select(index)).bounds(6, top + (i - sideScroll) * TAB_STEP, SIDE - 10, TAB).build();
            b.setTooltip(Tooltip.create(title));
            b.active = i != selected;
            addRenderableWidget(b);
        }
        search = new EditBox(font, SIDE + 4, 8, width - SIDE - 12, 16, Component.translatable("tremor.screen.config.search"));
        search.setHint(Component.translatable("tremor.screen.config.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(query);
        search.setResponder(text -> fill());
        addRenderableWidget(search);
        rows = new Rows(minecraft, width - SIDE - 4, height - top - 32, top, ROW);
        rows.setX(SIDE);
        addRenderableWidget(rows);
        addRenderableWidget(Button.builder(Component.translatable("tremor.screen.config.files"),
                b -> minecraft.setScreen(new ConfigurationScreen(container, this)))
                .bounds(width / 2 - 154, height - 26, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 + 4, height - 26, 150, 20).build());
        fill();
    }

    private void select(int index) {
        selected = index;
        query = "";
        rebuildWidgets();
    }

    /** The rows of the chosen section, or of every section whose settings match the search. */
    private void fill() {
        rows.clear();
        String query = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        boolean remote = remote();
        if (query.isEmpty()) {
            Section s = sections.get(selected);
            if (!s.client() && remote) {
                rows.add(new HeaderRow(Component.translatable("tremor.screen.config.remote")
                        .withStyle(ChatFormatting.GOLD)));
            }
            for (Item item : s.items()) {
                rows.add(item.value() == null ? new HeaderRow(item.header())
                        : new ValueRow(item, !s.client() && remote, null));
            }
        } else {
            for (Section s : sections) {
                for (Item item : s.items()) {
                    if (item.value() != null && (item.name().getString().toLowerCase(Locale.ROOT).contains(query)
                            || String.join(".", item.value().getPath()).toLowerCase(Locale.ROOT).contains(query))) {
                        rows.add(new ValueRow(item, !s.client() && remote, s.title()));
                    }
                }
            }
        }
        rows.setScrollAmount(0);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < SIDE) {
            int before = sideScroll;
            sideScroll = Math.max(0, sideScroll - (int) Math.signum(scrollY) * 2);
            if (sideScroll != before) {
                query = search.getValue();
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, title, 8, 12, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        query = search.getValue();
        minecraft.setScreen(parent);
    }

    // ---- saving ----

    /** Sets {@code item} to {@code value} if its spec takes it: saved to the file and applied at once. */
    private static void commit(Item item, Object value) {
        if (value == null || !item.value().getSpec().test(value)) {
            return;
        }
        item.value().set(value);
        item.spec().save();
    }

    private static Tooltip tooltip(Item item) {
        ModConfigSpec.ValueSpec spec = item.value().getSpec();
        MutableComponent text = item.name().copy().withStyle(ChatFormatting.YELLOW);
        if (spec.getComment() != null && !spec.getComment().isBlank()) {
            text.append("\n").append(Component.literal(spec.getComment()).withStyle(ChatFormatting.GRAY));
        }
        text.append("\n").append(Component.translatable("tremor.screen.config.default", show(spec.getDefault()))
                .withStyle(ChatFormatting.DARK_GRAY));
        if (spec.restartType() != ModConfigSpec.RestartType.NONE) {
            text.append("\n").append(Component.translatable("tremor.screen.config.restart")
                    .withStyle(ChatFormatting.RED));
        }
        return Tooltip.create(text);
    }

    private static String show(Object value) {
        if (value instanceof Double d) {
            return trim(d);
        }
        return String.valueOf(value);
    }

    private static String trim(double d) {
        String s = String.format(Locale.ROOT, "%.3f", d);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    // ---- the widgets ----

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AbstractWidget widget(Item item, int w, int h) {
        ModConfigSpec.ConfigValue<Object> value = item.value();
        Object current = value.getRaw();
        ModConfigSpec.ValueSpec spec = value.getSpec();
        AbstractWidget widget;
        if (current instanceof Boolean b) {
            widget = CycleButton.onOffBuilder(b).displayOnlyValue()
                    .create(0, 0, w, h, Component.empty(), (btn, v) -> commit(item, v));
        } else if (current instanceof Enum<?> e) {
            Enum[] values = e.getDeclaringClass().getEnumConstants();
            widget = CycleButton.<Enum>builder(v -> Component.literal(v.name().toLowerCase(Locale.ROOT)))
                    .withValues(values).withInitialValue(e).displayOnlyValue()
                    .create(0, 0, w, h, Component.empty(), (btn, v) -> commit(item, v));
        } else if (current instanceof Number n && spec.getRange() != null) {
            ModConfigSpec.Range<?> range = spec.getRange();
            widget = new ValueSlider(item, w, h, ((Number) range.getMin()).doubleValue(),
                    ((Number) range.getMax()).doubleValue(), n);
        } else if (current instanceof String s) {
            EditBox box = new EditBox(font, 0, 0, w, h, Component.empty());
            box.setMaxLength(256);
            box.setValue(s);
            box.setResponder(text -> commit(item, text));
            widget = box;
        } else {
            Button b = Button.builder(Component.translatable("tremor.screen.config.list"), btn -> {
            }).bounds(0, 0, w, h).build();
            b.active = false;
            widget = b;
        }
        widget.setTooltip(tooltip(item));
        return widget;
    }

    /** A slider over a number's range, on a log scale when it spans much; saved as it is let go. */
    private static final class ValueSlider extends AbstractSliderButton {
        private final Item item;
        private final double min;
        private final double max;
        private final boolean whole;
        private final boolean log;

        ValueSlider(Item item, int w, int h, double min, double max, Number current) {
            super(0, 0, w, h, Component.empty(), 0);
            this.item = item;
            this.min = min;
            this.max = max;
            this.whole = current instanceof Integer || current instanceof Long;
            this.log = min > 0 && max / min > 100;
            this.value = toSlider(current.doubleValue());
            updateMessage();
        }

        private double toSlider(double v) {
            double c = Math.max(min, Math.min(max, v));
            double s = log ? (Math.log(c) - Math.log(min)) / (Math.log(max) - Math.log(min)) : (c - min) / (max - min);
            return Double.isFinite(s) ? s : 0;
        }

        private double number() {
            double v = log ? Math.exp(Math.log(min) + value * (Math.log(max) - Math.log(min))) : min + value * (max - min);
            if (whole) {
                return Math.round(v);
            }
            double step = Math.pow(10, Math.floor(Math.log10(Math.max(Math.abs(v), 1e-9))) - 2);
            return Math.max(min, Math.min(max, Math.round(v / step) * step));
        }

        private Object typed() {
            Object current = item.value().getRaw();
            double n = number();
            if (current instanceof Integer) {
                return (int) n;
            }
            if (current instanceof Long) {
                return (long) n;
            }
            return n;
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(whole ? String.valueOf((long) number()) : trim(number())));
        }

        @Override
        protected void applyValue() {
            // saved as it is let go (onRelease), not on every step of a drag
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            commit(item, typed());
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            boolean used = super.keyPressed(keyCode, scanCode, modifiers);
            if (used) {
                commit(item, typed());
            }
            return used;
        }
    }

    // ---- the list ----

    private final class Rows extends ContainerObjectSelectionList<Row> {
        Rows(Minecraft mc, int width, int height, int y, int itemHeight) {
            super(mc, width, height, y, itemHeight);
        }

        void clear() {
            clearEntries();
        }

        void add(Row row) {
            addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return Math.min(360, width - 16);
        }

        @Override
        protected int getScrollbarPosition() {
            return getX() + width - 6;
        }
    }

    private abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
    }

    private final class HeaderRow extends Row {
        private final Component text;

        HeaderRow(Component text) {
            this.text = text.copy().withStyle(ChatFormatting.BOLD);
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovered, float partialTick) {
            g.drawString(font, text, left, top + 7, 0xE0C0FF);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    private final class ValueRow extends Row {
        private final Item item;
        private final AbstractWidget widget;
        private final Button reset;
        private final String label;

        /** {@code section}: the section's name before the setting's (a search lists settings of every section). */
        ValueRow(Item item, boolean readOnly, Component section) {
            this.item = item;
            this.label = (section == null ? "" : section.getString() + ": ") + item.name().getString();
            this.widget = widget(item, 150, 18);
            this.reset = Button.builder(Component.literal("↺"), b -> {
                commit(item, item.value().getSpec().getDefault());
                fill();
            }).bounds(0, 0, 18, 18).build();
            reset.setTooltip(Tooltip.create(Component.translatable("tremor.screen.config.reset")));
            widget.active = !readOnly;
            reset.active = !readOnly;
        }

        @Override
        public void render(GuiGraphics g, int index, int top, int left, int width, int height, int mouseX, int mouseY,
                           boolean hovered, float partialTick) {
            g.drawString(font, font.plainSubstrByWidth(label, width - 178), left, top + 6, 0xFFFFFF);
            widget.setX(left + width - 172);
            widget.setY(top);
            widget.render(g, mouseX, mouseY, partialTick);
            reset.setX(left + width - 18);
            reset.setY(top);
            reset.render(g, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(widget, reset);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(widget, reset);
        }
    }
}
