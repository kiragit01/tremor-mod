package tremor.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

/**
 * The menu of the debug probe ({@link ProbeTool}): tabs (the tremor commanded, the right click and the route, the
 * creatures, the events, what is shown), each with rows of small buttons with an item for a picture and their name
 * and what they do in a tooltip, the row's name to their left (the user: the text buttons ran into each other, there
 * will be more and more of them, buttons for everything); under them, always, the report of what is going on
 * ({@link ProbeReport}, copied for a bug report) and Done. Each button runs one of the {@code /tremor} commands; the
 * server answers in the chat, or above the hotbar. The game goes on behind it.
 */
final class ProbeScreen extends Screen {
    private static final int ICON = 20;
    private static final int GAP = 2;
    private static final int ROW = 23;
    private static final int LABEL_GAP = 6;
    private static final int WIDE = 120;

    /** The tabs, by what they hold. */
    enum Tab {
        TREMOR, ROUTE, CREATURES, EVENTS, VIEW;

        Component label() {
            return Component.translatable("tremor.probe.tab." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** The tab open, kept while the game runs. */
    private static Tab tab = Tab.TREMOR;

    /** A button of a row: its picture, its name, what it does, whether it is the one chosen, and its action. */
    private record Entry(ItemStack icon, Component name, Component tip, boolean selected, Button.OnPress press) {
    }

    private record Row(Component label, List<Entry> entries) {
    }

    /** The rows' names, drawn to their left, and where. */
    private final List<Component> labels = new ArrayList<>();
    private final List<int[]> labelAt = new ArrayList<>();
    private int titleY = 6;

    ProbeScreen() {
        super(Component.translatable("tremor.probe.title"));
    }

    /** Opens the menu on {@code on} (autotests). */
    static void open(Tab on) {
        tab = on;
        net.minecraft.client.Minecraft.getInstance().setScreen(new ProbeScreen());
    }

    @Override
    protected void init() {
        labels.clear();
        labelAt.clear();
        List<Row> rows = rows(tab);
        int labelWidth = 0, most = Tab.values().length;
        for (Row row : rows) {
            labelWidth = Math.max(labelWidth, font.width(row.label()));
            most = Math.max(most, row.entries().size());
        }
        int rowsWidth = labelWidth + LABEL_GAP + most * (ICON + GAP) - GAP;
        int blockWidth = Math.max(rowsWidth, 2 * WIDE + GAP);
        int blockHeight = 12 + ROW + 4 + rows.size() * ROW + 6 + ICON;
        int top = Math.max(2, (height - blockHeight) / 2);
        titleY = top;
        int left = (width - blockWidth) / 2;
        int rowsLeft = (width - rowsWidth) / 2;
        // The tabs, under the title.
        int y = top + 12;
        int tabsWidth = Tab.values().length * (ICON + GAP) - GAP;
        int x = (width - tabsWidth) / 2;
        for (Tab each : Tab.values()) {
            addRenderableWidget(new ProbeButton(x, y, ICON, ICON, new ItemStack(tabIcon(each)), each.label(),
                    Component.translatable("tremor.probe.tab." + each.name().toLowerCase(Locale.ROOT) + ".tip")
                            .withStyle(ChatFormatting.GRAY), each == tab, false, button -> {
                        tab = each;
                        rebuildWidgets();
                    }));
            x += ICON + GAP;
        }
        y += ROW + 4;
        for (Row row : rows) {
            labels.add(row.label());
            labelAt.add(new int[]{rowsLeft + labelWidth, y + (ICON - font.lineHeight) / 2 + 1});
            x = rowsLeft + labelWidth + LABEL_GAP;
            for (Entry entry : row.entries()) {
                addRenderableWidget(new ProbeButton(x, y, ICON, ICON, entry.icon(), entry.name(), entry.tip(),
                        entry.selected(), false, entry.press()));
                x += ICON + GAP;
            }
            y += ROW;
        }
        y += 6;
        int wideLeft = (width - (2 * WIDE + GAP)) / 2;
        addRenderableWidget(new ProbeButton(wideLeft, y, WIDE, ICON, new ItemStack(Items.WRITABLE_BOOK),
                Component.translatable("tremor.probe.button.report"), tip("report"), false, true, button -> {
                    ProbeTool.report();
                    onClose();
                }));
        addRenderableWidget(new ProbeButton(wideLeft + WIDE + GAP, y, WIDE, ICON, new ItemStack(Items.OAK_DOOR),
                Component.translatable("tremor.probe.button.done"), null, false, true, button -> onClose()));
        if (left < 0) {
            titleY = 2; // a very narrow screen: the rows run off it; the game's GUI scale makes room
        }
    }

    private static ItemLike tabIcon(Tab tab) {
        return switch (tab) {
            case TREMOR -> tremor.item.TremorItems.SHARD.get();
            case ROUTE -> Items.MAP;
            case CREATURES -> Items.POINTED_DRIPSTONE;
            case EVENTS -> Items.SCULK_CATALYST;
            case VIEW -> Items.SPYGLASS;
        };
    }

    private List<Row> rows(Tab tab) {
        return switch (tab) {
            case TREMOR -> List.of(
                    new Row(Component.translatable("tremor.probe.row.target", ProbeTool.selected < 0
                            ? Component.translatable("tremor.probe.target.nearest")
                            : Component.literal("#" + ProbeTool.selected)), List.of(new Entry(new ItemStack(Items.COMPASS), Component.translatable(
                                    "tremor.probe.button.target.nearest"), tip("target.nearest"), ProbeTool.selected < 0,
                                    button -> {
                                        ProbeTool.select(-1);
                                        rebuildWidgets();
                                    }),
                            new Entry(new ItemStack(Items.ENDER_EYE), Component.translatable(
                                    "tremor.probe.button.target.look"), tip("target.look"), false, button -> {
                                ProbeTool.selectLookedAt();
                                rebuildWidgets();
                            }),
                            new Entry(new ItemStack(Items.SPECTRAL_ARROW), Component.translatable(
                                    "tremor.probe.button.target.next"), tip("target.next"), false, button -> {
                                ProbeTool.turnSelection(1);
                                rebuildWidgets();
                            }),
                            new Entry(new ItemStack(Items.ARROW), Component.translatable(
                                    "tremor.probe.button.target.previous"), tip("target.previous"), false, button -> {
                                ProbeTool.turnSelection(-1);
                                rebuildWidgets();
                            }),
                            command("info", Items.KNOWLEDGE_BOOK, "tremor info"),
                            command("despawn", Items.BONE, "tremor despawn"))),
                    row("look", command("dormant", Items.GRAY_BED, "tremor stage dormant"),
                            command("alert", Items.BELL, "tremor stage alert"),
                            command("hunting", Items.IRON_SWORD, "tremor stage hunting"),
                            command("hold", Items.PACKED_ICE, "tremor hold"),
                            command("ambush", Items.TRIPWIRE_HOOK, "tremor ambush"),
                            command("echo", Items.ECHO_SHARD, "tremor echo")),
                    row("speed", command("slow", Items.LEATHER_BOOTS, "tremor set speed 1.5"),
                            command("normal", Items.IRON_BOOTS, "tremor set reset"),
                            command("fast", Items.GOLDEN_BOOTS, "tremor set speed 6"),
                            command("ai", Items.REDSTONE_TORCH, "tremor ai toggle")),
                    row("difficulty", command("difficulty.calm", Items.LEATHER_HELMET, "tremor difficulty calm"),
                            command("difficulty.normal", Items.IRON_HELMET, "tremor difficulty normal"),
                            command("difficulty.hard", Items.DIAMOND_HELMET, "tremor difficulty hard"),
                            command("difficulty.nightmare", Items.NETHERITE_HELMET, "tremor difficulty nightmare")));
            case ROUTE -> List.of(
                    row("click", mode(ProbeTool.Mode.LURE, Items.LEAD), mode(ProbeTool.Mode.ROUTE, Items.MAP),
                            mode(ProbeTool.Mode.LISTEN, Items.SPYGLASS)),
                    row("route", command("play", Items.MINECART, "tremor route go"),
                            command("loop", Items.CLOCK, "tremor route loop"),
                            command("stop", Items.BARRIER, "tremor route stop"),
                            command("undo", Items.BRUSH, "tremor route undo"),
                            command("clear", Items.TNT, "tremor route clear"),
                            command("show", Items.ENDER_EYE, "tremor route show")));
            case CREATURES -> List.of(
                    row("spawn", kind(ProbeTool.Kind.TREMOR, tremor.item.TremorItems.SHARD.get()),
                            kind(ProbeTool.Kind.CRAWLER, Items.POINTED_DRIPSTONE),
                            kind(ProbeTool.Kind.LISTENER, Items.SCULK_SENSOR)),
                    row("clear", command("despawn_all", Items.LAVA_BUCKET, "tremor despawn all"),
                            command("crawlers_clear", Items.DRIPSTONE_BLOCK, "tremor crawler clear"),
                            command("listeners_clear", Items.SCULK, "tremor listener clear")));
            case EVENTS -> List.of(
                    row("awakening", command("awaken", Items.SCULK_CATALYST, "tremor awaken"),
                            command("swallow", Items.SOUL_SAND, "tremor awaken swallow"),
                            command("awaken_stop", Items.MILK_BUCKET, "tremor awaken stop"),
                            command("crater", Items.TNT_MINECART, "tremor crater")),
                    row("hollow", command("hollow_enter", Items.ENDER_PEARL, "tremor hollow enter"),
                            command("hollow_node", Items.HEART_OF_THE_SEA, "tremor hollow tonode"),
                            command("hollow_tunnel", Items.RAIL, "tremor hollow totunnel"),
                            command("hollow_status", Items.FILLED_MAP, "tremor hollow status"),
                            command("hollow_leave", Items.OAK_DOOR, "tremor hollow leave")),
                    row("finale", command("victory", Items.TOTEM_OF_UNDYING, "tremor hollow outcome victory"),
                            command("edge", Items.FEATHER, "tremor hollow outcome edge"),
                            command("defeat", Items.SKELETON_SKULL, "tremor hollow outcome defeat")),
                    row("marks", command("marks_show", Items.NAME_TAG, "tremor marks"),
                            command("marks_0", Items.WHITE_DYE, "tremor marks @s set 0"),
                            command("marks_1", Items.LIGHT_GRAY_DYE, "tremor marks @s set 1"),
                            command("marks_3", Items.GRAY_DYE, "tremor marks @s set 3"),
                            command("marks_6", Items.BLACK_DYE, "tremor marks @s set 6")));
            case VIEW -> List.of(
                    row("show", panel(), answers(), settings()),
                    row("debug", view("path", Items.STRING), view("normals", Items.ARROW),
                            view("graph", Items.COBWEB), view("hearing", Items.GOAT_HORN)));
        };
    }

    private static Row row(String label, Entry... entries) {
        return new Row(Component.translatable("tremor.probe.row." + label), List.of(entries));
    }

    private static Component tip(String name) {
        return Component.translatable("tremor.probe.tip." + name).withStyle(ChatFormatting.GRAY);
    }

    /** Makes a right click do {@code mode}; the mode chosen is framed. */
    private Entry mode(ProbeTool.Mode mode, ItemLike icon) {
        String name = mode.name().toLowerCase(Locale.ROOT);
        return new Entry(new ItemStack(icon), Component.translatable("tremor.probe.button." + name), tip(name),
                ProbeTool.mode == mode, button -> {
                    ProbeTool.mode = mode;
                    rebuildWidgets();
                });
    }

    /** Makes an Alt + left click call up {@code kind}; the one chosen is framed. */
    private Entry kind(ProbeTool.Kind kind, ItemLike icon) {
        return new Entry(new ItemStack(icon), kind.label(),
                tip("kind." + kind.name().toLowerCase(Locale.ROOT)), ProbeTool.kind == kind, button -> {
                    ProbeTool.kind = kind;
                    rebuildWidgets();
                });
    }

    /** Shows debug view {@code view} or hides it; shown ones are framed. */
    private Entry view(String view, ItemLike icon) {
        return new Entry(new ItemStack(icon), Component.translatable("tremor.probe.button.view." + view),
                tip("view." + view), ProbeTool.views.contains(view), button -> {
                    ProbeTool.toggleView(view);
                    rebuildWidgets();
                });
    }

    /** All the mod's settings: the config folder (Forge 1.20.1 has no screen for them). */
    private Entry settings() {
        return new Entry(new ItemStack(Items.COMPARATOR), Component.translatable("tremor.probe.button.settings"),
                tip("settings"), false, button -> net.minecraft.Util.getPlatform()
                .openFile(net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().toFile()));
    }

    /** Where the panel of the probe shows: with it in hand, always, hidden, and round again. */
    private Entry panel() {
        return new Entry(new ItemStack(Items.OAK_SIGN), ProbeTool.panel.label(), tip("panel"), false, button -> {
            ProbeTool.panel(ProbeTool.panel.next());
            rebuildWidgets();
        });
    }

    /** The short answers of the server in the chat, or above the hotbar. */
    private Entry answers() {
        return new Entry(new ItemStack(ProbeTool.quiet ? Items.PAPER : Items.BOOK),
                Component.translatable("tremor.probe.button.answers." + (ProbeTool.quiet ? "quiet" : "chat")),
                tip("answers"), false, button -> {
                    ProbeTool.quiet(!ProbeTool.quiet);
                    rebuildWidgets();
                });
    }

    /** Runs {@code /command}; its tooltip says which. */
    private static Entry command(String name, ItemLike icon, String command) {
        Component tip = Component.empty().append(tip(name)).append("\n")
                .append(Component.literal("/" + command).withStyle(ChatFormatting.DARK_GRAY));
        return new Entry(new ItemStack(icon), Component.translatable("tremor.probe.button." + name), tip, false,
                button -> ProbeTool.command(command));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics); // 1.20.1 does not dim the world (and the chat) behind a screen by itself
        super.render(graphics, mouseX, mouseY, partialTick);
        Component heading = Component.empty().append(title).append(" — ").append(tab.label());
        graphics.drawCenteredString(font, heading, width / 2, titleY, 0xFFFFFF);
        for (int i = 0; i < labels.size(); i++) {
            int[] at = labelAt.get(i);
            graphics.drawString(font, labels.get(i), at[0] - font.width(labels.get(i)), at[1], 0xB0B0B0);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
