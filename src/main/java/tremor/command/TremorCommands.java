package tremor.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import tremor.awakening.AwakeningCommands;
import tremor.awakening.AwakeningManager;
import tremor.awakening.Craters;
import tremor.config.TremorConfig;
import tremor.core.behavior.DespawnClock;
import tremor.core.behavior.Stage;
import tremor.core.graph.SurfaceGraph;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.motion.Crawler;
import tremor.core.path.Path;
import tremor.core.path.PathSearch;
import tremor.core.path.PathSpline;
import tremor.debug.DebugParticles;
import tremor.debug.DebugView;
import tremor.entity.Param;
import tremor.entity.TremorEntity;
import tremor.entity.TremorManager;
import tremor.entity.TremorRuntime;
import tremor.hollow.HollowCommands;
import tremor.spawn.NaturalSpawner;

import java.util.Locale;
import java.util.UUID;

/**
 * {@code /tremor ...} debug commands (SPEC 14.1), op level 2:
 * <ul>
 *   <li>{@code spawn [pos]}, {@code spawn natural} (a natural spawn for the player at once, SPEC 11),
 *   {@code despawn}, {@code goto [pos]} (without a position: the block looked at, else the feet; heard sounds do
 *   not replace it until it is reached), {@code stop} (forget the target, goto or sound), {@code info} (also the
 *   Awakening of the dimension, and the natural spawn pause after one, with or without an entity);</li>
 *   <li>{@code set} lists the parameters, {@code set <param> <value>} overrides one for the entity,
 *   {@code set reset} restores the config values;</li>
 *   <li>{@code anger <0..100>} sets the anger and the stage it implies, {@code stage <dormant|alert|hunting|awakening>}
 *   jumps to a stage (both with the sound and state update of a stage change, SPEC 8; at the top the entity starts
 *   seeking a player afresh, and the Awakening starts once it has reached one, unlike {@code awaken}, which starts it
 *   at once), {@code ai <on|off>} switches the stage behaviour's decisions (off: the entity only obeys {@code goto}
 *   and {@code stop});</li>
 *   <li>{@code debug <path|normals|graph|hearing> <on|off>} toggles particles (hearing: also the action bar) for the
 *   player.</li>
 *   <li>{@code hollow <enter|leave|status>} and {@code restore}: the hollow, see {@link HollowCommands}.</li>
 *   <li>{@code awaken [player]} and {@code awaken stop}: the Awakening, see {@link AwakeningCommands}.</li>
 *   <li>{@code hollow outcome <victory|edge|defeat>}: ends the level inside the hollow for the player, see
 *   {@link AwakeningCommands}.</li>
 *   <li>{@code crater [pos]}: digs a crater there without an event, see {@link AwakeningCommands}.</li>
 * </ul>
 */
public final class TremorCommands {
    private static final SimpleCommandExceptionType NO_NODE = new SimpleCommandExceptionType(
            Component.literal("No surface the tremor could crawl on there (look at the ground or give a position near it)"));
    private static final SimpleCommandExceptionType NO_ENTITY = new SimpleCommandExceptionType(
            Component.literal("No tremor in this dimension (/tremor spawn)"));
    private static final SimpleCommandExceptionType OFF_SURFACE = new SimpleCommandExceptionType(
            Component.literal("The tremor is not on the surface any more (the ground under it changed); respawn it"));
    private static final DynamicCommandExceptionType UNKNOWN_PARAM = new DynamicCommandExceptionType(
            name -> Component.literal("Unknown parameter " + name + "; one of " + String.join(", ", Param.ids())));
    private static final DynamicCommandExceptionType BAD_VALUE = new DynamicCommandExceptionType(
            message -> Component.literal(String.valueOf(message)));

    private TremorCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("tremor")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("spawn")
                        .executes(ctx -> spawn(ctx, null))
                        .then(Commands.literal("natural").executes(TremorCommands::spawnNatural))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> spawn(ctx, BlockPosArgument.getBlockPos(ctx, "pos")))))
                .then(Commands.literal("despawn").executes(TremorCommands::despawn))
                .then(Commands.literal("goto")
                        .executes(ctx -> goTo(ctx, null))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> goTo(ctx, BlockPosArgument.getBlockPos(ctx, "pos")))))
                .then(Commands.literal("stop").executes(TremorCommands::stop))
                .then(Commands.literal("set")
                        .executes(TremorCommands::listParams)
                        .then(Commands.literal("reset").executes(TremorCommands::resetParams))
                        .then(Commands.argument("param", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Param.ids(), builder))
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                                        .executes(ctx -> setParam(ctx, StringArgumentType.getString(ctx, "param"),
                                                DoubleArgumentType.getDouble(ctx, "value"))))))
                .then(Commands.literal("info").executes(TremorCommands::info))
                .then(Commands.literal("anger")
                        .then(Commands.argument("anger", DoubleArgumentType.doubleArg(0, TremorEntity.MAX_ANGER))
                                .executes(ctx -> anger(ctx, DoubleArgumentType.getDouble(ctx, "anger")))))
                .then(stage())
                .then(Commands.literal("ai")
                        .then(Commands.literal("on").executes(ctx -> ai(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> ai(ctx, false))))
                .then(debug())
                .then(HollowCommands.hollow())
                .then(HollowCommands.restore())
                // Merged into the hollow branch above: hollow outcome <victory|edge|defeat>.
                .then(AwakeningCommands.hollowOutcome())
                .then(AwakeningCommands.awaken())
                .then(AwakeningCommands.crater()));
    }

    /** {@code /tremor stage <dormant|alert|hunting|awakening>} */
    private static LiteralArgumentBuilder<CommandSourceStack> stage() {
        LiteralArgumentBuilder<CommandSourceStack> command = Commands.literal("stage");
        for (Stage stage : Stage.values()) {
            command.then(Commands.literal(stage.name().toLowerCase(Locale.ROOT)).executes(ctx -> stage(ctx, stage)));
        }
        return command;
    }

    /** {@code /tremor debug <path|normals|graph|hearing> <on|off>} */
    private static LiteralArgumentBuilder<CommandSourceStack> debug() {
        LiteralArgumentBuilder<CommandSourceStack> debug = Commands.literal("debug");
        for (DebugView view : DebugView.values()) {
            debug.then(Commands.literal(view.id())
                    .then(Commands.literal("on").executes(ctx -> debug(ctx, view, true)))
                    .then(Commands.literal("off").executes(ctx -> debug(ctx, view, false))));
        }
        return debug;
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, BlockPos requested) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        TremorEntity entity = TremorManager.spawn(source.getLevel(), requested != null ? requested : lookTarget(source));
        if (entity == null) {
            throw NO_NODE.create();
        }
        Crawler crawler = entity.crawler();
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Tremor #%d spawned at %s, normal %s",
                entity.instance(), voxel(VoxelPos.containing(crawler.position())), vec(crawler.normal()))), true);
        return entity.instance();
    }

    /** {@code /tremor spawn natural}: {@link NaturalSpawner#spawnNow} for the executing player. */
    private static int spawnNatural(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        NaturalSpawner.Outcome outcome = NaturalSpawner.spawnNow(source.getPlayerOrException());
        if (outcome.entity() == null) {
            source.sendFailure(Component.literal(outcome.message()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(outcome.message()), true);
        return outcome.entity().instance();
    }

    private static int despawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (!TremorManager.despawn(ctx.getSource().getLevel())) {
            throw NO_ENTITY.create();
        }
        ctx.getSource().sendSuccess(() -> Component.literal("Tremor removed"), true);
        return 1;
    }

    private static int goTo(CommandContext<CommandSourceStack> ctx, BlockPos requested) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        TremorRuntime runtime = requireRuntime(source);
        long goal = runtime.snap(requested != null ? requested : lookTarget(source));
        if (goal == SurfaceGraph.NO_NODE) {
            throw NO_NODE.create();
        }
        UUID requester = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : null;
        if (!runtime.moveTo(goal, requester)) {
            throw OFF_SURFACE.create();
        }
        TremorEntity entity = runtime.entity();
        double distance = entity.crawler().position().distance(VoxelPos.center(goal));
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Tremor #%d heading for %s (%.1f blocks away), searching a path...",
                entity.instance(), voxel(goal), distance)), true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        TremorRuntime runtime = requireRuntime(ctx.getSource());
        runtime.stop();
        ctx.getSource().sendSuccess(() -> Component.literal("Tremor #" + runtime.entity().instance()
                + " stopped, no target"), true);
        return 1;
    }

    private static int listParams(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        TremorEntity entity = requireRuntime(ctx.getSource()).entity();
        StringBuilder text = new StringBuilder("Tremor #" + entity.instance() + " parameters (* = set for this entity):");
        for (Param param : Param.values()) {
            text.append(String.format(Locale.ROOT, "\n  %s = %s%s  [%s..%s]", param.id(),
                    number(entity.params().get(param)), entity.params().isOverridden(param) ? " *" : "",
                    number(param.min()), number(param.max())));
        }
        ctx.getSource().sendSuccess(() -> Component.literal(text.toString()), false);
        return Param.values().length;
    }

    private static int setParam(CommandContext<CommandSourceStack> ctx, String name, double value)
            throws CommandSyntaxException {
        Param param = Param.byId(name);
        if (param == null) {
            throw UNKNOWN_PARAM.create(name);
        }
        TremorRuntime runtime = requireRuntime(ctx.getSource());
        try {
            runtime.setParam(param, value);
        } catch (IllegalArgumentException e) {
            throw BAD_VALUE.create(e.getMessage());
        }
        double now = runtime.entity().params().get(param);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Tremor #%d: %s = %s",
                runtime.entity().instance(), param.id(), number(now))), true);
        return 1;
    }

    private static int resetParams(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        TremorRuntime runtime = requireRuntime(ctx.getSource());
        runtime.resetParams();
        ctx.getSource().sendSuccess(() -> Component.literal("Tremor #" + runtime.entity().instance()
                + ": parameters reset to the config values"), true);
        return 1;
    }

    /**
     * The entity (stage, anger, behaviour with the seeking at the top, despawn clock, motion, route, cost), then the
     * Awakening of the dimension and the natural spawn pause after one ({@link AwakeningManager#describe}), and the
     * craters being dug there ({@link Craters#describe}); the latter also without an entity.
     */
    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        String described = AwakeningManager.describe(source.getLevel());
        String craters = Craters.describe(source.getLevel());
        String awakening = described == null ? craters : craters == null ? described : described + "\n" + craters;
        TremorRuntime runtime = TremorManager.runtime(source.getLevel());
        if (runtime == null || runtime.entity() == null) {
            if (awakening == null) {
                throw NO_ENTITY.create();
            }
            source.sendSuccess(() -> Component.literal("No tremor in " + source.getLevel().dimension().location()
                    + "\n" + awakening), false);
            return 0;
        }
        TremorEntity entity = runtime.entity();
        Crawler crawler = entity.crawler();
        long node = runtime.currentNode();
        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.ROOT, "Tremor #%d in %s: stage %s%s", entity.instance(),
                source.getLevel().dimension().location(), entity.stage().name().toLowerCase(Locale.ROOT),
                runtime.isPaused() ? " (paused: its chunk is not loaded)" : ""));
        text.append(String.format(Locale.ROOT, "\nAnger %.1f of %.0f; last heard: %s", entity.anger(),
                TremorEntity.MAX_ANGER, heard(entity.lastHeard(), source.getLevel().getGameTime())));
        text.append("\nBehaviour: ").append(runtime.behavior()).append(entity.aiEnabled() ? "" : " (ai off)");
        if (entity.natural()) {
            DespawnClock clock = entity.despawnClock();
            text.append(String.format(Locale.ROOT, "\nNatural%s; no player near for %.0f of %.0f s, nothing "
                            + "happening for %.0f of %.0f s", entity.leaving() ? ", leaving" : "", clock.farSeconds(),
                    TremorConfig.COMMON.despawnFarSeconds.get(), clock.quietSeconds(),
                    TremorConfig.COMMON.despawnQuietSeconds.get()));
        } else {
            text.append("\nSpawned by a command: does not despawn by itself");
        }
        text.append(String.format(Locale.ROOT, "\nPosition %s, node %s", vec(crawler.position()),
                node == SurfaceGraph.NO_NODE ? "none" : voxel(node)));
        text.append(String.format(Locale.ROOT, "\nNormal %s, forward %s", vec(crawler.normal()), vec(crawler.forward())));
        // The stage's cruise speed and bump height (SPEC 8).
        text.append(String.format(Locale.ROOT, "\nSpeed %.2f of %.2f b/s, amplitude %.2f of %.2f%s", crawler.speed(),
                entity.params().get(Param.SPEED) * TremorConfig.COMMON.speedFactor(entity.stage()),
                crawler.amplitude(), entity.params().get(Param.AMPLITUDE) * (entity.leaving() || runtime.absorbed()
                        ? 0 : TremorConfig.COMMON.amplitudeFactor(entity.stage())),
                crawler.diving() ? ", diving" : ""));
        BlockPos target = entity.target();
        PathSearch search = runtime.search();
        text.append("\nTarget ").append(target == null ? "none" : target.toShortString() + " ("
                        + entity.targetKind().name().toLowerCase(Locale.ROOT) + ")").append(", search ")
                .append(search == null ? "idle" : search.status().name().toLowerCase(Locale.ROOT) + " ("
                        + search.expanded() + " nodes expanded)");
        PathSpline spline = crawler.spline();
        if (spline == null) {
            text.append("\nNo path");
        } else {
            Path path = spline.path();
            int dives = 0;
            for (boolean dive : path.dive()) {
                dives += dive ? 1 : 0;
            }
            text.append(String.format(Locale.ROOT, "\nPath %.1f blocks (%.1f left), %d segments, %d dives, %s, arrived %s",
                    spline.length(), spline.length() - crawler.progress(), path.size() - 1, dives,
                    path.complete() ? "complete" : "partial", crawler.arrived() ? "yes" : "no"));
        }
        text.append(String.format(Locale.ROOT, "\nCache %d sections, graph %d sections; tick %.3f ms average, %.3f ms max "
                        + "(last %d ticks)", runtime.cache().sectionCount(), runtime.graph().sectionCount(),
                runtime.averageTickMillis(), runtime.maxTickMillis(), runtime.measuredTicks()));
        if (runtime.error() != null) {
            text.append("\nStopped after an error: ").append(runtime.error());
        }
        if (awakening != null) {
            text.append('\n').append(awakening);
        }
        source.sendSuccess(() -> Component.literal(text.toString()), false);
        return entity.instance();
    }

    private static int anger(CommandContext<CommandSourceStack> ctx, double anger) throws CommandSyntaxException {
        TremorRuntime runtime = requireRuntime(ctx.getSource());
        runtime.setAnger(anger);
        return reportAnger(ctx, runtime.entity());
    }

    private static int stage(CommandContext<CommandSourceStack> ctx, Stage stage) throws CommandSyntaxException {
        TremorRuntime runtime = requireRuntime(ctx.getSource());
        runtime.forceStage(stage);
        return reportAnger(ctx, runtime.entity());
    }

    /**
     * {@code Tremor #1: anger 25.0, stage alert}, at the top with the seeking ({@code ..., stage awakening: seeking a
     * player for 35 s}); returns the stage's ordinal.
     */
    private static int reportAnger(CommandContext<CommandSourceStack> ctx, TremorEntity entity) {
        String seeking = entity.stage() == Stage.AWAKENING && !entity.absorbed() ? String.format(Locale.ROOT,
                ": seeking a player for %d s", TremorConfig.COMMON.awakening.seekSeconds.get()) : "";
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Tremor #%d: anger %.1f, stage %s%s", entity.instance(), entity.anger(),
                entity.stage().name().toLowerCase(Locale.ROOT), seeking)), true);
        return entity.stage().ordinal();
    }

    private static int ai(CommandContext<CommandSourceStack> ctx, boolean on) throws CommandSyntaxException {
        TremorRuntime runtime = requireRuntime(ctx.getSource());
        runtime.setAi(on);
        ctx.getSource().sendSuccess(() -> Component.literal("Tremor #" + runtime.entity().instance() + ": ai "
                + (on ? "on" : "off (it only obeys goto and stop)")), true);
        return 1;
    }

    private static int debug(CommandContext<CommandSourceStack> ctx, DebugView view, boolean on)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        DebugParticles.set(player, view, on);
        ctx.getSource().sendSuccess(() -> Component.literal("Tremor debug " + view.id() + (on ? " on" : " off")), false);
        return 1;
    }

    private static TremorRuntime requireRuntime(CommandSourceStack source) throws CommandSyntaxException {
        TremorRuntime runtime = TremorManager.runtime(source.getLevel());
        if (runtime == null || runtime.entity() == null) {
            throw NO_ENTITY.create();
        }
        return runtime;
    }

    /** {@code step at (x, y, z), perceived 0.18 (threshold 0.10), 3.2 s ago}, or {@code nothing}. */
    private static String heard(TremorEntity.Heard heard, long now) {
        if (heard == null) {
            return "nothing";
        }
        return String.format(Locale.ROOT, "%s at %s, perceived %.2f (threshold %.2f), %.1f s ago", heard.event(),
                vec(heard.position()), heard.perceived(), TremorConfig.COMMON.hearingThreshold.getAsDouble(),
                (now - heard.gameTime()) / 20.0);
    }

    private static String voxel(long packed) {
        return VoxelPos.x(packed) + " " + VoxelPos.y(packed) + " " + VoxelPos.z(packed);
    }

    private static String vec(Vec3 v) {
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x(), v.y(), v.z());
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.format(Locale.ROOT, "%.0f", value)
                : String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "");
    }

    /** The block the command source is looking at, or its feet position if it is not an entity / sees nothing. */
    private static BlockPos lookTarget(CommandSourceStack source) {
        Entity entity = source.getEntity();
        if (entity != null) {
            HitResult hit = entity.pick(96.0, 1.0f, false);
            if (hit.getType() == HitResult.Type.BLOCK) {
                return ((BlockHitResult) hit).getBlockPos();
            }
        }
        return BlockPos.containing(source.getPosition());
    }
}
