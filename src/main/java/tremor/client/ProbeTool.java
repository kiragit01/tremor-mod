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
 * sneaking opens the menu; a key (unbound at first) pins or hides the panel of the probe. Every action is one of the
 * {@code /tremor} commands, run as the player, so the server checks the permission and answers as for a typed command.
 * Main thread only.
 */
public final class ProbeTool {
    /** How far a right click reaches (blocks). */
    static final double RANGE = 192;
    /** Ticks between two actions of a held right click (the game repeats it every 4 ticks). */
    private static final int REPEAT_TICKS = 10;
    /** Pins the panel of the probe or hides it, whatever is in hand. */
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

    static Mode mode = Mode.LURE;
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
            command("tremor probe toggle");
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

    /** Runs {@code /command} as the player. */
    static void command(String command) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.connection.sendCommand(command);
        }
    }
}
