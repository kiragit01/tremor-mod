package tremor.client;

import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.InputEvent;
import tremor.item.DebugProbeItem;

/**
 * The debug probe as an operator's multitool on the client ({@link DebugProbeItem}): a right click acts where the
 * player looks (up to {@value #RANGE} blocks), by the mode chosen in its menu ({@link ProbeScreen}), and with no tremor
 * near calls one up there; a right click while sneaking opens the menu; Alt + a left click calls up one more of the
 * kind chosen (a tremor, a stalactite crawler, a listener), Shift + the wheel chooses it (the user: no typing); a key
 * (unbound at first) hides the panel of the probe or shows it again. Every action is one of the {@code /tremor}
 * commands, run as the player, so the server checks the permission and answers as for a typed command. Main thread
 * only.
 */
public final class ProbeTool {
    /** How far a right click reaches (blocks). */
    static final double RANGE = 192;
    /** Ticks between two actions of a held right click (the game repeats it every 4 ticks). */
    private static final int REPEAT_TICKS = 10;
    /** Hides the panel of the probe, or shows it again where it was, whatever is in hand. */
    static final KeyMapping PANEL = new KeyMapping("key.tremor.probe_panel", InputConstants.UNKNOWN.getValue(),
            "key.categories.tremor");
    /** Held with a left click: one more of the kind chosen (left Alt at first, rebindable: Alt + click on a laptop). */
    public static final KeyMapping ADD = new KeyMapping("key.tremor.probe_add", InputConstants.KEY_LALT,
            "key.categories.tremor");

    /** What a right click does. */
    enum Mode {
        /** The nearest tremor goes there ({@code /tremor goto}). */
        LURE,
        /** A route point ({@code /tremor route add}). */
        ROUTE,
        /** A point where it stops and listens ({@code /tremor route listen}). */
        LISTEN;

        Component label() {
            return Component.translatable("tremor.probe.mode." + name().toLowerCase(Locale.ROOT));
        }
    }

    /** Where the panel of the probe shows, as last asked of the server, which forgets it too when the player leaves. */
    enum Panel {
        /** With the probe in hand ({@code /tremor probe unpin}). */
        HELD("unpin"),
        /** Also without it ({@code /tremor probe pin}). */
        PINNED("pin"),
        /** Not at all ({@code /tremor probe hide}). */
        HIDDEN("hide");

        private final String command;

        Panel(String command) {
            this.command = command;
        }

        Component label() {
            return Component.translatable("tremor.probe.button.panel." + name().toLowerCase(Locale.ROOT));
        }

        Panel next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** Who an Alt + left click calls up there; Shift + the wheel turns through them. */
    enum Kind {
        TREMOR("tremor spawn %s add"),
        CRAWLER("tremor crawler spawn %s"),
        LISTENER("tremor listener spawn %s");

        private final String command;

        Kind(String command) {
            this.command = command;
        }

        Component label() {
            return Component.translatable("tremor.probe.kind." + name().toLowerCase(Locale.ROOT));
        }

        Kind turn(int by) {
            int n = values().length;
            return values()[((ordinal() + by) % n + n) % n];
        }
    }

    static Mode mode = Mode.LURE;
    static Kind kind = Kind.TREMOR;
    static Panel panel = Panel.HIDDEN;
    /** Whether the short answers of the server go above the hotbar rather than into the chat (at first they do). */
    static boolean quiet = true;
    /** Where the panel showed before the key hid it. */
    private static Panel shownBefore = Panel.HELD;
    private static long lastUse = Long.MIN_VALUE / 2;

    private ProbeTool() {
    }

    public static void init() {
        DebugProbeItem.onUse = ProbeTool::use;
    }

    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(PANEL);
        event.register(ADD);
    }

    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        while (PANEL.consumeClick()) {
            panel(panel == Panel.HIDDEN ? shownBefore : Panel.HIDDEN);
        }
    }

    /** A right click with the probe ({@link DebugProbeItem#onUse}). */
    static void use(boolean sneaking) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        long now = mc.level.getGameTime();
        if (now >= lastUse && now - lastUse < REPEAT_TICKS) {
            return;
        }
        lastUse = now;
        // The sneak key itself: the player counts as sneaking only from its next tick on.
        if (sneaking || mc.options.keyShift.isDown()) {
            mc.setScreen(new ProbeScreen());
            return;
        }
        String at = aimed(mc, player);
        if (at == null) {
            return;
        }
        if (mode == Mode.LURE && ClientTremor.presence() == null) {
            command("tremor spawn " + at); // no tremor near to lure: one comes up there (the user)
            return;
        }
        command(switch (mode) {
            case LURE -> "tremor goto " + at;
            case ROUTE -> "tremor route add " + at;
            case LISTEN -> "tremor route listen " + at;
        });
    }

    /** The block looked at, as command coordinates; null (and the player told) if there is none in reach. */
    private static String aimed(Minecraft mc, LocalPlayer player) {
        Entity eye = mc.getCameraEntity() != null ? mc.getCameraEntity() : player;
        HitResult hit = eye.pick(RANGE, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult block)) {
            player.displayClientMessage(Component.translatable("tremor.probe.nothing"), true);
            return null;
        }
        BlockPos pos = block.getBlockPos();
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** Whether the player holds the probe in the main hand, no screen open. */
    private static boolean holding(Minecraft mc) {
        return mc.player != null && mc.screen == null
                && mc.player.getMainHandItem().getItem() instanceof DebugProbeItem;
    }

    /** Shift + the wheel with the probe in hand: the kind an Alt + left click calls up, not the hotbar slot. */
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        double delta = event.getScrollDelta();
        if (!holding(mc) || !mc.options.keyShift.isDown() || delta == 0) {
            return;
        }
        event.setCanceled(true);
        kind = kind.turn(delta > 0 ? -1 : 1);
        mc.player.displayClientMessage(Component.translatable("tremor.probe.kind_chosen", kind.label()), true);
    }

    /** Alt + a left click with the probe in hand: one more of the kind chosen where the player looks. */
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (!event.isAttack() || !holding(mc) || !ADD.isDown()) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        String at = aimed(mc, mc.player);
        if (at != null) {
            command(String.format(Locale.ROOT, kind.command, at));
        }
    }

    /** Shows the panel of the probe {@code where} ({@code /tremor probe unpin|pin|hide}). */
    static void panel(Panel where) {
        if (where == Panel.HIDDEN && panel != Panel.HIDDEN) {
            shownBefore = panel;
        }
        panel = where;
        command("tremor probe " + where.command);
    }

    /** Puts the short answers of the server above the hotbar, or back into the chat ({@code /tremor probe quiet}). */
    static void quiet(boolean on) {
        quiet = on;
        command("tremor probe quiet " + (on ? "on" : "off"));
    }

    /** Leaving a world or a server: the panel and the answers as at first, as the server has them then. */
    static void forget() {
        panel = Panel.HIDDEN;
        shownBefore = Panel.HELD;
        quiet = true;
    }

    /** Runs {@code /command} as the player. */
    static void command(String command) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.connection.sendCommand(command);
        }
    }
}
