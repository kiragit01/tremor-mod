package tremor.awakening;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import tremor.config.TremorConfig;
import tremor.core.math.Vec3;
import tremor.hollow.HollowEvent;
import tremor.hollow.HollowManager;
import tremor.hollow.HollowOutcome;

import java.util.Locale;

/**
 * The Awakening's debug commands under {@code /tremor} (SPEC 14.1), added by {@link tremor.command.TremorCommands}:
 * <ul>
 *   <li>{@code awaken [player]}: starts an Awakening for the player (default: the executing one) where the player
 *   stands, with or without an entity in the dimension ({@link AwakeningManager#start(ServerPlayer)}); also for a
 *   player in adventure mode, whom a natural one never takes (the feedback says the node cannot be broken then);</li>
 *   <li>{@code awaken stop}: calls off the Awakening of the dimension, or the one the executing player is the target
 *   of; a swallowed target comes back out of the hollow ({@link AwakeningManager#stop});</li>
 *   <li>{@code hollow outcome <victory|edge|defeat>}: ends the level inside the hollow for the executing player, who
 *   must be alive in the copy, as the level would ({@link Outcomes}); {@code edge} gets out where the player stands
 *   (at a safe spot near the matching place of the real world, else at the swallow point).
 *   It joins the {@code hollow} branch of {@link tremor.hollow.HollowCommands} (brigadier merges the two).</li>
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

    /** {@code /tremor hollow outcome <victory|edge|defeat>} */
    public static LiteralArgumentBuilder<CommandSourceStack> hollowOutcome() {
        return Commands.literal("hollow").then(Commands.literal("outcome")
                .then(Commands.literal("victory").executes(ctx -> outcome(ctx, HollowOutcome.VICTORY)))
                .then(Commands.literal("edge").executes(ctx -> outcome(ctx, HollowOutcome.EDGE_ESCAPE)))
                .then(Commands.literal("defeat").executes(ctx -> outcome(ctx, HollowOutcome.DEFEAT))));
    }

    private static int outcome(CommandContext<CommandSourceStack> ctx, HollowOutcome outcome)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = player.getGameProfile().getName();
        String message;
        try {
            message = switch (outcome) {
                case VICTORY -> {
                    HollowEvent event = Outcomes.win(player);
                    yield "Victory: " + name + " comes out of the ground at " + text(event.origin().position());
                }
                case EDGE_ESCAPE -> {
                    net.minecraft.world.phys.Vec3 reached = Outcomes.escape(player, new Vec3(player.getX(),
                            player.getY(), player.getZ()));
                    yield "Escape through the edge: " + name + " comes out at a safe spot near " + text(reached)
                            + " (else at the swallow point)";
                }
                case DEFEAT -> {
                    HollowEvent event = Outcomes.lose(player);
                    TremorConfig.Awakening config = TremorConfig.COMMON.awakening;
                    yield "Defeat: " + (config.sinkholeRadius.get() == 0 ? "no sinkhole (awakening.sinkholeRadius 0)"
                            : "a sinkhole opens at " + text(event.origin().position())) + "; then " + name
                            + (config.lethal.get() ? " dies (unless a totem or creative mode saves them)"
                            : " comes out on its bottom, weakened");
                }
            };
        } catch (HollowManager.Refusal refusal) {
            ctx.getSource().sendFailure(Component.literal(refusal.getMessage()));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static int start(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        Awakening awakening;
        try {
            awakening = AwakeningManager.start(target);
        } catch (AwakeningManager.Refusal refusal) {
            ctx.getSource().sendFailure(Component.literal(refusal.getMessage()));
            return 0;
        }
        boolean adventure = target.gameMode.getGameModeForPlayer() == GameType.ADVENTURE;
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Awakening #%d for %s: a zone of %.0f blocks around %.1f %.1f %.1f, %.0f s to get out%s",
                awakening.id, awakening.targetName, awakening.radius, awakening.center.x(), awakening.center.y(),
                awakening.center.z(), awakening.phaseSeconds(), adventure ? ". Note: " + awakening.targetName
                        + " is in adventure mode, so in the hollow the node cannot be broken nor the ground dug: only "
                        + "the edge is a way out (a natural Awakening never takes such a player)" : "")), true);
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

    private static String text(net.minecraft.world.phys.Vec3 v) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", v.x, v.y, v.z);
    }
}
