package tremor.hollow;

import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import tremor.hollow.level.HollowLevels;

import java.util.List;
import java.util.Locale;

/**
 * The hollow's debug commands under {@code /tremor} (SPEC 14.1), added by {@link tremor.command.TremorCommands}:
 * <ul>
 *   <li>{@code hollow enter}: starts an event for the executing player where the player stands (the copy, then the
 *   move in);</li>
 *   <li>{@code hollow leave}: moves the executing player back out (an event still copying just stops);</li>
 *   <li>{@code hollow status}: the events, their phases, what the copying and clearing cost, the blocks their
 *   players placed, the level inside (the node and the network of ways to it: steps along the way, blocks in a straight
 *   line, the dead ends, the throat; the closing, the noise, the sinking, what the preparation cost:
 *   {@link HollowLevels#describe}), and who is in the hollow;</li>
 *   <li>{@code hollow tonode}: moves the executing player next to the node of their level (debug, autotests);</li>
 *   <li>{@code hollow walk [steps]}: walks the copy as it is now from the executing player to the node of their level
 *   (the steps on foot, or how close it gets), and moves the player that many steps along the walk (debug, autotests:
 *   the way to the node is open, the closing has not shut it);</li>
 *   <li>{@code restore}: ends every event at once, players back to their exits, all slots cleared.</li>
 * </ul>
 */
public final class HollowCommands {
    private HollowCommands() {
    }

    /** {@code /tremor hollow <enter|leave|status|tonode|walk [steps]>} */
    public static LiteralArgumentBuilder<CommandSourceStack> hollow() {
        return Commands.literal("hollow")
                .then(Commands.literal("enter").executes(HollowCommands::enter))
                .then(Commands.literal("leave").executes(HollowCommands::leave))
                .then(Commands.literal("status").executes(HollowCommands::status))
                .then(Commands.literal("tonode").executes(HollowCommands::toNode))
                .then(Commands.literal("totunnel").executes(ctx -> toTunnel(ctx, -1))
                        .then(Commands.literal("end").executes(ctx -> toTunnel(ctx, END_BLOCKS))
                                .then(Commands.argument("blocks", IntegerArgumentType.integer(1, 1000))
                                        .executes(ctx -> toTunnel(ctx, IntegerArgumentType.getInteger(ctx,
                                                "blocks"))))))
                .then(Commands.literal("walk").executes(ctx -> walk(ctx, 0))
                        .then(Commands.argument("steps", IntegerArgumentType.integer(0, 1000))
                                .executes(ctx -> walk(ctx, IntegerArgumentType.getInteger(ctx, "steps")))));
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
                event.slot())), false);
        return 1;
    }

    /** Blocks before the end of the escape tunnel {@code totunnel end} goes to. */
    private static final int END_BLOCKS = 8;

    /**
     * {@code /tremor hollow totunnel [end [blocks]]}: to the entrance of the escape tunnel, facing along it; or that
     * many blocks before its end ({@value #END_BLOCKS} by default, in its light), facing the end.
     */
    private static int toTunnel(CommandContext<CommandSourceStack> ctx, int fromEnd) throws CommandSyntaxException {
        boolean end = fromEnd > 0;
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        HollowEvent event = HollowManager.event(player);
        java.util.List<net.minecraft.core.BlockPos> tunnel = event == null ? java.util.List.of()
                : HollowLevels.tunnel(event);
        if (tunnel.size() < 2 || !HollowDimension.is(player.serverLevel())) {
            ctx.getSource().sendFailure(Component.literal("No escape tunnel: you are not in a hollow with one"));
            return 0;
        }
        net.minecraft.core.BlockPos from = tunnel.get(end ? Math.max(0, tunnel.size() - 1 - fromEnd) : 0);
        net.minecraft.core.BlockPos toward = tunnel.get(end ? tunnel.size() - 1 : Math.min(3, tunnel.size() - 1));
        float yaw = (float) Math.toDegrees(Math.atan2(-(toward.getX() - from.getX()), toward.getZ() - from.getZ()));
        player.teleportTo(player.serverLevel(), from.getX() + 0.5, from.getY(), from.getZ() + 0.5, yaw, 0);
        ctx.getSource().sendSuccess(() -> Component.literal("At the escape tunnel " + from.toShortString() + ", "
                + tunnel.size() + " blocks to the edge"), false);
        return tunnel.size();
    }

    /**
     * Debug and autotests: moves the player inside the hollow to a free standing spot next to the node of their level,
     * facing it, so that the node can be broken at once.
     */
    private static int toNode(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        HollowEvent event = HollowManager.event(player);
        net.minecraft.core.BlockPos node = event == null ? null : HollowLevels.node(event);
        if (node == null || !HollowDimension.is(player.serverLevel())) {
            ctx.getSource().sendFailure(Component.literal("No node: you are not in a hollow with one"));
            return 0;
        }
        net.minecraft.server.level.ServerLevel level = player.serverLevel();
        net.minecraft.core.BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    net.minecraft.core.BlockPos at = node.offset(dx, dy, dz);
                    if (at.equals(node) || !level.getBlockState(at).getCollisionShape(level, at).isEmpty()
                            || !level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty()
                            || !level.getBlockState(at.below()).isFaceSturdy(level, at.below(),
                            net.minecraft.core.Direction.UP)) {
                        continue;
                    }
                    double score = Math.abs(Math.sqrt(dx * dx + dz * dz) - 1.5) + Math.abs(dy) * 0.5;
                    if (score < bestScore) {
                        bestScore = score;
                        best = at;
                    }
                }
            }
        }
        if (best == null) {
            ctx.getSource().sendFailure(Component.literal("No free spot next to the node at " + node.toShortString()));
            return 0;
        }
        double x = best.getX() + 0.5, y = best.getY(), z = best.getZ() + 0.5;
        double dx = node.getX() + 0.5 - x, dy = node.getY() + 0.5 - (y + player.getEyeHeight()),
                dz = node.getZ() + 0.5 - z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        player.teleportTo(level, x, y, z, yaw, pitch);
        net.minecraft.core.BlockPos to = best;
        ctx.getSource().sendSuccess(() -> Component.literal("Next to the node at " + node.toShortString()
                + ", standing at " + to.toShortString()), false);
        return 1;
    }

    /**
     * Debug and autotests: walks the copy as it is now from the player to the node of their level and moves the player
     * {@code steps} steps along the walk ({@link HollowLevels#walk}).
     */
    private static int walk(CommandContext<CommandSourceStack> ctx, int steps) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        HollowEvent event = HollowManager.event(player);
        if (event == null || !HollowDimension.is(player.serverLevel())) {
            ctx.getSource().sendFailure(Component.literal("You are not in a hollow"));
            return 0;
        }
        String found = HollowLevels.walk(event, player, steps);
        ctx.getSource().sendSuccess(() -> Component.literal(found), false);
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
        ctx.getSource().sendSuccess(() -> Component.literal("Out of the hollow"), false);
        return 1;
    }

    private static int restore(CommandContext<CommandSourceStack> ctx) {
        int ended = HollowManager.restore(ctx.getSource().getServer());
        ctx.getSource().sendSuccess(() -> Component.literal("Hollow restored: " + ended + " event"
                + (ended == 1 ? "" : "s") + " ended, players moved back, slots being cleared"), false);
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
            String level = HollowLevels.describe(event);
            if (level != null) {
                text.append("\n  ").append(level.replace("\n", "\n  "));
            }
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
