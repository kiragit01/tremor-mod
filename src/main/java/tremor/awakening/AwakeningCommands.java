package tremor.awakening;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * The Awakening's debug commands under {@code /tremor} (SPEC 14.1), added by {@link tremor.command.TremorCommands}:
 * <ul>
 *   <li>{@code awaken [player]}: starts an Awakening for the player (default: the executing one) where the player
 *   stands, with or without an entity in the dimension ({@link AwakeningManager#start(ServerPlayer)});</li>
 *   <li>{@code awaken stop}: calls off the Awakening of the dimension, or the one the executing player is the target
 *   of; a swallowed target comes back out of the hollow ({@link AwakeningManager#stop}).</li>
 * </ul>
 * {@code /tremor info} shows the running one.
 */
public final class AwakeningCommands {
    private AwakeningCommands() {
    }

    /** {@code /tremor awaken [player|stop]} */
    public static LiteralArgumentBuilder<CommandSourceStack> awaken() {
        return Commands.literal("awaken")
                .executes(ctx -> start(ctx, ctx.getSource().getPlayerOrException()))
                .then(Commands.literal("stop").executes(AwakeningCommands::stop))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> start(ctx, EntityArgument.getPlayer(ctx, "player"))));
    }

    private static int start(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        Awakening awakening;
        try {
            awakening = AwakeningManager.start(target);
        } catch (AwakeningManager.Refusal refusal) {
            ctx.getSource().sendFailure(Component.literal(refusal.getMessage()));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Awakening #%d for %s: a zone of %.0f blocks around %.1f %.1f %.1f, %.0f s to get out",
                awakening.id, awakening.targetName, awakening.radius, awakening.center.x(), awakening.center.y(),
                awakening.center.z(), awakening.phaseSeconds())), true);
        return awakening.id;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        Awakening awakening = AwakeningManager.stop(ctx.getSource().getLevel(), ctx.getSource().getPlayer());
        if (awakening == null) {
            ctx.getSource().sendFailure(Component.literal("No Awakening here"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Awakening #" + awakening.id + " for "
                + awakening.targetName + " stopped"), true);
        return awakening.id;
    }
}
