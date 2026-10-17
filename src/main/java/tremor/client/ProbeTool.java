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
import tremor.item.DebugProbeItem;

/**
 * The debug probe as an operator's multitool on the client ({@link DebugProbeItem}): a right click acts where the
 * player looks (up to {@value #RANGE} blocks), by the mode chosen in its menu ({@link ProbeScreen}); a right click while
 * sneaking opens the menu; a key (unbound at first) hides the panel of the probe or shows it again. Every action is
 * one of the {@code /tremor} commands, run as the player, so the server checks the permission and answers as for a
 * typed command. Main thread only.
 */
public final class ProbeTool {
    /** How far a right click reaches (blocks). */
    static final double RANGE = 192;
    /** Ticks between two actions of a held right click (the game repeats it every 4 ticks). */
    private static final int REPEAT_TICKS = 10;
    /** Hides the panel of the probe, or shows it again where it was, whatever is in hand. */
    static final KeyMapping PANEL = new KeyMapping("key.tremor.probe_panel", InputConstants.UNKNOWN.getValue(),
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

    static Mode mode = Mode.LURE;
    static Panel panel = Panel.HELD;
    /** Whether the short answers of the server go above the hotbar rather than into the chat. */
    static boolean quiet;
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
        Entity eye = mc.getCameraEntity() != null ? mc.getCameraEntity() : player;
        HitResult hit = eye.pick(RANGE, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK || !(hit instanceof BlockHitResult block)) {
            player.displayClientMessage(Component.translatable("tremor.probe.nothing"), true);
            return;
        }
        BlockPos pos = block.getBlockPos();
        String at = pos.getX() + " " + pos.getY() + " " + pos.getZ();
        command(switch (mode) {
            case LURE -> "tremor goto " + at;
            case ROUTE -> "tremor route add " + at;
            case LISTEN -> "tremor route listen " + at;
        });
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
        panel = Panel.HELD;
        shownBefore = Panel.HELD;
        quiet = false;
    }

    /** Runs {@code /command} as the player. */
    static void command(String command) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.connection.sendCommand(command);
        }
    }
}
