package tremor.hollow;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import tremor.config.TremorConfig;

import java.util.List;
import java.util.Locale;

/**
 * The hollow's debug commands under {@code /tremor} (SPEC 14.1), added by {@link tremor.command.TremorCommands}:
 * <ul>
 *   <li>{@code hollow enter}: starts an event for the executing player where the player stands (the copy, then the
 *   move in);</li>
 *   <li>{@code hollow leave}: moves the executing player back out (an event still copying just stops);</li>
 *   <li>{@code hollow status}: the events, their phases, what the copying and clearing cost, the blocks their
 *   players placed, and who is in the hollow;</li>
 *   <li>{@code restore}: ends every event at once, players back to their exits, all slots cleared.</li>
 * </ul>
 */
public final class HollowCommands {
    private HollowCommands() {
    }

    /** {@code /tremor hollow <enter|leave|status>} */
    public static LiteralArgumentBuilder<CommandSourceStack> hollow() {
        return Commands.literal("hollow")
                .then(Commands.literal("enter").executes(HollowCommands::enter))
                .then(Commands.literal("leave").executes(HollowCommands::leave))
                .then(Commands.literal("status").executes(HollowCommands::status));
    }

    /** {@code /tremor restore} */
    public static LiteralArgumentBuilder<CommandSourceStack> restore() {
        return Commands.literal("restore").executes(HollowCommands::restore);
    }

    private static int enter(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        HollowEvent event;
        try {
            event = HollowManager.enter(player);
        } catch (HollowManager.Refusal refusal) {
            ctx.getSource().sendFailure(Component.literal(refusal.getMessage()));
            return 0;
        }
        HollowBox box = event.box();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Into the hollow: copying %d x %d x %d blocks into slot %d", box.sizeX(), box.sizeY(), box.sizeZ(),
                event.slot())), true);
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        try {
            HollowManager.leave(player, null);
        } catch (HollowManager.Refusal refusal) {
            ctx.getSource().sendFailure(Component.literal(refusal.getMessage()));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Out of the hollow"), true);
        return 1;
    }

    private static int restore(CommandContext<CommandSourceStack> ctx) {
        int ended = HollowManager.restore(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("Hollow restored: " + ended + " event"
                + (ended == 1 ? "" : "s") + " ended, players moved back, slots being cleared"), true);
        return ended;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        if (!HollowManager.available(server)) {
            ctx.getSource().sendFailure(Component.literal("The dimension tremor:hollow is missing"));
            return 0;
        }
        List<HollowEvent> events = HollowManager.events(server);
        List<ServerPlayer> inside = HollowDimension.level(server).players();
        long now = server.overworld().getGameTime();
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                "Hollow: %d event%s (at most %d running), %d player%s in it", events.size(),
                events.size() == 1 ? "" : "s", TremorConfig.COMMON.hollow.maxEvents.get(), inside.size(),
                inside.size() == 1 ? "" : "s"));
        for (HollowEvent event : events) {
            HollowBox box = event.box();
            text.append(String.format(Locale.ROOT, "\n%s: %s%s, %.1f s since the start", event.playerName(),
                    event.phase().id(), event.end() == null ? "" : " (" + event.end().id() + ")",
                    (now - event.createdGameTime()) / 20.0));
            text.append(String.format(Locale.ROOT, "\n  from %s %s, slot %d (chunk %d %d, offset %d %d)",
                    event.origin().dimension().location(), vec(event.origin().position()), event.slot(),
                    event.slotChunkX(), event.slotChunkZ(), event.offsetX(), event.offsetZ()));
            text.append(String.format(Locale.ROOT, "\n  box %d x %d x %d (y %d..%d), %d blocks; %d placed by the "
                            + "player", box.sizeX(), box.sizeY(), box.sizeZ(), box.minY(), box.maxY(), box.volume(),
                    event.placed().size()));
            appendJob(text, "copy", event.copyStats, event.phase() == HollowEvent.Phase.COPYING ? event.cursor : null);
            appendJob(text, "clear", event.clearStats, event.phase() == HollowEvent.Phase.CLEARING ? event.cursor : null);
        }
        for (ServerPlayer player : inside) {
            text.append(String.format(Locale.ROOT, "\nIn the hollow: %s at %s%s", player.getGameProfile().getName(),
                    vec(player.position()), HollowManager.insideEvent(player) ? "" : " (no event: moved out)"));
        }
        ctx.getSource().sendSuccess(() -> Component.literal(text.toString()), false);
        return events.size();
    }

    /** One line on a job (copying or clearing) that has started; {@code cursor} is its progress while it runs. */
    private static void appendJob(StringBuilder text, String name, WorkStats stats, PieceCursor cursor) {
        if (stats == null) {
            return;
        }
        text.append("\n  ").append(name).append(": ");
        if (cursor != null && cursor.hasNext()) {
            text.append(String.format(Locale.ROOT, "%d of %d pieces, ", cursor.done(), cursor.total()));
        }
        text.append(String.format(Locale.ROOT, "%d blocks (%d changed, %d light checks) in %d ticks, %.1f ms of "
                        + "server time, worst tick %.2f ms", stats.blocks(), stats.changed(), stats.lightChecks(),
                stats.ticks(), stats.totalMillis(), stats.worstMillis()));
        if (stats.finished()) {
            text.append(String.format(Locale.ROOT, "; light done %d ticks later", stats.lightTicks()));
        } else if (cursor == null) {
            text.append("; stopped");
        } else if (!cursor.hasNext()) {
            text.append("; waiting for the light");
        }
    }

    private static String vec(Vec3 v) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", v.x, v.y, v.z);
    }
}
