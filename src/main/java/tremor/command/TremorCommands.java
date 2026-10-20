package tremor.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSource;
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
import tremor.config.Difficulty;
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
import tremor.debug.DebugProbe;
import tremor.debug.DebugView;
import tremor.entity.Param;
import tremor.entity.TremorEntity;
import tremor.entity.TremorManager;
import tremor.entity.Routes;
import tremor.entity.TremorRuntime;
import tremor.hollow.HollowCommands;
import tremor.spawn.NaturalSpawner;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /tremor ...} debug commands (SPEC 14.1), op level 2. A dimension may have several entities (one for each part
 * of the world where players are); the commands act on the one nearest to where they run:
 * <ul>
 *   <li>{@code spawn [pos]} (in place of an entity within {@code spawn.spacing} of the place, else one more),
 *   {@code spawn natural} (a natural spawn for the player at once, SPEC 11), {@code despawn [all]},
 *   {@code goto [pos]} (without a position: the block looked at, else the feet; heard sounds do
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
 *   <li>{@code route <add [pos]|listen [pos]|undo|clear|list|go|loop|stop|show [on|off]>}: a route for filming, see
 *   {@link tremor.entity.Routes}; {@code hold [on|off]} holds the look of the tremor (its anger) without one;
 *   {@code probe <pin|unpin|hide>} shows the panel of the debug probe also without it in hand, only with it, or not
 *   at all; {@code probe quiet [on|off]} puts the short answers above the hotbar ({@link #answer});
 *   {@code ai toggle}; {@code ambush [stop]}: the nearest tremor lies in wait where it is (see
 *   {@link tremor.core.behavior.Ambush}), or no longer.</li>
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
                .then(Commands.literal("despawn").executes(ctx -> despawn(ctx, false))
                        .then(Commands.literal("all").executes(ctx -> despawn(ctx, true))))
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
                        .then(Commands.literal("off").executes(ctx -> ai(ctx, false)))
                        .then(Commands.literal("toggle").executes(ctx -> ai(ctx,
                                !requireRuntime(ctx.getSource()).entity().aiEnabled()))))
                .then(debug())
                .then(route())
                .then(probe())
                .then(hold())
                .then(difficulty())
                .then(Commands.literal("scar")
                        .then(Commands.argument("kind", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        java.util.List.of("rift", "maw", "inverted"), builder))
                                .executes(ctx -> tremor.debug.ScarPreview.run(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "kind")))))
                .then(Commands.literal("ambush").executes(TremorCommands::ambush)
                        .then(Commands.literal("stop").executes(TremorCommands::ambushStop)))
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

    /** {@code /tremor route ...}: points for the nearest entity to crawl, for filming ({@link Routes}). */
    private static LiteralArgumentBuilder<CommandSourceStack> route() {
        return Commands.literal("route")
                .then(Commands.literal("add").executes(ctx -> routeAdd(ctx, null, false))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> routeAdd(ctx, BlockPosArgument.getBlockPos(ctx, "pos"), false))))
                .then(Commands.literal("listen").executes(ctx -> routeAdd(ctx, null, true))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> routeAdd(ctx, BlockPosArgument.getBlockPos(ctx, "pos"), true))))
                .then(Commands.literal("undo").executes(TremorCommands::routeUndo))
                .then(Commands.literal("clear").executes(TremorCommands::routeClear))
                .then(Commands.literal("list").executes(TremorCommands::routeList))
                .then(Commands.literal("go").executes(ctx -> routeGo(ctx, false)))
                .then(Commands.literal("loop").executes(ctx -> routeGo(ctx, true)))
                .then(Commands.literal("stop").executes(TremorCommands::routeStop))
                .then(Commands.literal("show").executes(ctx -> routeShow(ctx, null))
                        .then(Commands.literal("on").executes(ctx -> routeShow(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> routeShow(ctx, false))));
    }

    /** {@code route show [on|off]}: the route and the lure points as particles, for whoever runs it. */
    private static int routeShow(CommandContext<CommandSourceStack> ctx, Boolean on) {
        boolean shown = Routes.show(routeOwner(ctx.getSource()), on);
        answer(ctx.getSource(), () -> Component.literal(shown ? "Route and lure points shown"
                : "Route and lure points hidden"));
        return shown ? 1 : 0;
    }

    /**
     * {@code /tremor probe <pin|unpin|hide>}: the panel of the debug probe also without it in hand, only with it, or
     * not at all; {@code /tremor probe quiet [on|off]}: the short answers of the commands above the hotbar, not in
     * the chat.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> probe() {
        return Commands.literal("probe")
                .then(Commands.literal("pin").executes(ctx -> probePanel(ctx, DebugProbe.Panel.PINNED)))
                .then(Commands.literal("unpin").executes(ctx -> probePanel(ctx, DebugProbe.Panel.HELD)))
                .then(Commands.literal("hide").executes(ctx -> probePanel(ctx, DebugProbe.Panel.HIDDEN)))
                .then(Commands.literal("quiet").executes(ctx -> probeQuiet(ctx, null))
                        .then(Commands.literal("on").executes(ctx -> probeQuiet(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> probeQuiet(ctx, false))));
    }

    private static int probePanel(CommandContext<CommandSourceStack> ctx, DebugProbe.Panel panel)
            throws CommandSyntaxException {
        DebugProbe.panel(ctx.getSource().getPlayerOrException(), panel);
        answer(ctx.getSource(), () -> Component.literal(switch (panel) {
            case HELD -> "Tremor panel shown with the probe in hand";
            case PINNED -> "Tremor panel pinned: shown without the probe in hand too";
            case HIDDEN -> "Tremor panel hidden (/tremor probe unpin shows it again)";
        }));
        return panel.ordinal();
    }

    private static int probeQuiet(CommandContext<CommandSourceStack> ctx, Boolean on) throws CommandSyntaxException {
        boolean quiet = DebugProbe.quiet(ctx.getSource().getPlayerOrException(), on);
        answer(ctx.getSource(), () -> Component.literal(quiet
                ? "Short tremor answers above the hotbar, not in the chat (/tremor probe quiet off)"
                : "Tremor answers in the chat"));
        return quiet ? 1 : 0;
    }

    /** {@code /tremor ambush}: the nearest tremor lies in wait where it is ({@link TremorRuntime#lurk}). */
    private static int ambush(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        TremorRuntime runtime = requireRuntime(ctx.getSource());
        int instance = runtime.entity().instance();
        String refusal = runtime.lurk();
        if (refusal != null) {
            ctx.getSource().sendFailure(Component.literal("Tremor #" + instance + " cannot lie in wait: " + refusal));
            return 0;
        }
        double radius = TremorConfig.COMMON.ambush.triggerRadius.get();
        answer(ctx.getSource(), () -> Component.literal(String.format(Locale.ROOT, "Tremor #%d lies in wait where it "
                + "is; a player in survival mode within %.1f blocks of it springs it", instance, radius)));
        return 1;
    }

    private static int ambushStop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        TremorRuntime runtime = requireRuntime(ctx.getSource());
        int instance = runtime.entity().instance();
        if (!runtime.stopLurking()) {
            ctx.getSource().sendFailure(Component.literal("Tremor #" + instance + " does not lie in wait"));
            return 0;
        }
        answer(ctx.getSource(), () -> Component.literal("Tremor #" + instance + " no longer lies in wait"));
        return 1;
    }

    /** {@code /tremor hold [on|off]}: the look of the nearest tremor (its anger) stays as it is, or not. */
    /**
     * {@code difficulty [calm|normal|hard|nightmare]}: tells how hard the mod is set ({@link Difficulty}), or sets it,
     * saved to the common config.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> difficulty() {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal("difficulty").executes(ctx -> {
            Difficulty level = Difficulty.current();
            ctx.getSource().sendSuccess(() -> Component.literal("Difficulty " + level.summary()), false);
            return level.ordinal();
        });
        for (Difficulty level : Difficulty.values()) {
            node.then(Commands.literal(level.id()).executes(ctx -> setDifficulty(ctx.getSource(), level)));
        }
        return node;
    }

    private static int setDifficulty(CommandSourceStack source, Difficulty level) {
        TremorConfig.COMMON.difficulty.set(level);
        TremorConfig.COMMON_SPEC.save();
        tremor.Tremor.LOGGER.info("{} set the difficulty to {}", source.getTextName(), level.id());
        answer(source, () -> Component.literal("Difficulty " + level.summary()));
        return level.ordinal();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> hold() {
        return Commands.literal("hold")
                .executes(ctx -> hold(ctx, null))
                .then(Commands.literal("on").executes(ctx -> hold(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> hold(ctx, false)));
    }

    private static int hold(CommandContext<CommandSourceStack> ctx, Boolean on) throws CommandSyntaxException {
        TremorRuntime runtime = requireRuntime(ctx.getSource());
        boolean held = on != null ? on : !runtime.angerHeld();
        runtime.holdAnger(held);
        int instance = runtime.entity().instance();
        answer(ctx.getSource(), () -> Component.literal("Tremor #" + instance + (held
                ? ": look held (its anger stays as it is)" : ": look free (its anger changes again)")));
        return held ? 1 : 0;
    }

    private static UUID routeOwner(CommandSourceStack source) {
        return Routes.owner(source.getEntity() instanceof ServerPlayer player ? player : null);
    }

    /** {@code route add|listen [pos]}: the block looked at (or {@code pos}) is the next point. */
    private static int routeAdd(CommandContext<CommandSourceStack> ctx, BlockPos requested, boolean listen) {
        CommandSourceStack source = ctx.getSource();
        BlockPos pos = requested != null ? requested : lookTarget(source);
        int count = Routes.add(routeOwner(source), pos, listen);
        answer(source, () -> Component.literal(String.format(Locale.ROOT, "Route point %d: %s%s", count,
                pos.toShortString(), listen ? ", it stops and listens there" : "")));
        return count;
    }

    private static int routeUndo(CommandContext<CommandSourceStack> ctx) {
        int left = Routes.undo(routeOwner(ctx.getSource()));
        answer(ctx.getSource(), () -> Component.literal("Last route point taken away, " + left + " left"));
        return left;
    }

    private static int routeClear(CommandContext<CommandSourceStack> ctx) {
        Routes.clear(routeOwner(ctx.getSource()));
        answer(ctx.getSource(), () -> Component.literal("Route cleared"));
        return 1;
    }

    private static int routeList(CommandContext<CommandSourceStack> ctx) {
        java.util.List<Routes.Point> points = Routes.laid(routeOwner(ctx.getSource()));
        if (points.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No route: look at the ground and /tremor route add"));
            return 0;
        }
        StringBuilder text = new StringBuilder("Route of " + points.size() + " points:");
        for (int i = 0; i < points.size(); i++) {
            text.append('\n').append(i + 1).append(". ").append(points.get(i).pos().toShortString())
                    .append(points.get(i).listen() ? " (listens)" : "");
        }
        ctx.getSource().sendSuccess(() -> Component.literal(text.toString()), false);
        return points.size();
    }

    /** {@code route go|loop}: the nearest entity crawls the route, once or round and round. */
    private static int routeGo(CommandContext<CommandSourceStack> ctx, boolean loop) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        TremorRuntime runtime = requireRuntime(source);
        if (!Routes.go(runtime, routeOwner(source), loop)) {
            source.sendFailure(Component.literal("No route: look at the ground and /tremor route add"));
            return 0;
        }
        int count = Routes.laid(routeOwner(source)).size();
        answer(source, () -> Component.literal(String.format(Locale.ROOT, "Tremor #%d crawls the route of %d "
                        + "points %s; its own decisions are off and its look held (/tremor route stop)",
                runtime.entity().instance(), count, loop ? "round and round" : "once")));
        return count;
    }

    private static int routeStop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        TremorRuntime runtime = requireRuntime(ctx.getSource());
        int instance = runtime.entity().instance();
        if (!Routes.stop(runtime)) {
            ctx.getSource().sendFailure(Component.literal("Tremor #" + instance + " crawls no route"));
            return 0;
        }
        answer(ctx.getSource(), () -> Component.literal("Tremor #" + instance + " stops its route and is itself "
                + "again"));
        return 1;
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, BlockPos requested) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        TremorEntity entity = TremorManager.spawn(source.getLevel(), requested != null ? requested : lookTarget(source));
        if (entity == null) {
            throw NO_NODE.create();
        }
        Crawler crawler = entity.crawler();
        // Only whoever ran it is told (no broadcast to other operators); the server log keeps it.
        tremor.Tremor.LOGGER.info("{} spawned tremor #{} in {} at {}", source.getTextName(), entity.instance(),
                source.getLevel().dimension().location(), voxel(VoxelPos.containing(crawler.position())));
        int count = TremorManager.runtimes(source.getLevel()).size();
        answer(source, () -> Component.literal(String.format(Locale.ROOT, "Tremor #%d spawned at %s, normal %s; "
                        + "%d in the dimension", entity.instance(), voxel(VoxelPos.containing(crawler.position())),
                vec(crawler.normal()), count)));
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
        answer(source, () -> Component.literal(outcome.message()));
        return outcome.entity().instance();
    }

    /** {@code /tremor despawn [all]}: the nearest entity, or every one of the dimension. */
    private static int despawn(CommandContext<CommandSourceStack> ctx, boolean all) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        if (all) {
            int removed = TremorManager.despawnAll(source.getLevel());
            if (removed == 0) {
                throw NO_ENTITY.create();
            }
            answer(source, () -> Component.literal(removed + (removed == 1 ? " tremor" : " tremors") + " removed"));
            return removed;
        }
        TremorRuntime runtime = requireRuntime(source);
        int instance = runtime.entity().instance();
        TremorManager.despawn(runtime);
        answer(source, () -> Component.literal("Tremor #" + instance + " removed"));
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
        answer(source, () -> Component.literal(String.format(Locale.ROOT,
                "Tremor #%d heading for %s (%.1f blocks away), searching a path...",
                entity.instance(), voxel(goal), distance)));
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        TremorRuntime runtime = requireRuntime(ctx.getSource());
        runtime.stop();
        answer(ctx.getSource(), () -> Component.literal("Tremor #" + runtime.entity().instance()
                + " stopped, no target"));
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
        answer(ctx.getSource(), () -> Component.literal(String.format(Locale.ROOT, "Tremor #%d: %s = %s",
                runtime.entity().instance(), param.id(), number(now))));
        return 1;
    }

    private static int resetParams(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        TremorRuntime runtime = requireRuntime(ctx.getSource());
        runtime.resetParams();
        answer(ctx.getSource(), () -> Component.literal("Tremor #" + runtime.entity().instance()
                + ": parameters reset to the config values"));
        return 1;
    }

    /**
     * The entity (stage, anger, behaviour with the seeking at the top, despawn clock, motion, route, cost), then the
     * Awakening of the dimension and the natural spawn pause after one ({@link AwakeningManager#describe}), and the
     * craters being dug there ({@link Craters#describe}); the latter also without an entity.
     */
    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        String described = AwakeningManager.describe(source.getLevel(), source.getPosition().x,
                source.getPosition().z);
        String craters = Craters.describe(source.getLevel());
        String awakening = described == null ? craters : craters == null ? described : described + "\n" + craters;
        TremorRuntime runtime = TremorManager.nearest(source.getLevel(), source.getPosition().x,
                source.getPosition().y, source.getPosition().z);
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
        for (TremorRuntime other : TremorManager.runtimes(source.getLevel())) {
            TremorEntity more = other.entity();
            if (other != runtime && more != null) {
                Vec3 at = more.crawler().position();
                text.append(String.format(Locale.ROOT, "\nAlso tremor #%d: %s, %.0f blocks away, at %s",
                        more.instance(), more.stage().name().toLowerCase(Locale.ROOT),
                        Math.sqrt(source.getPosition().distanceToSqr(at.x(), at.y(), at.z())), vec(at)));
            }
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
        answer(ctx.getSource(), () -> Component.literal(String.format(Locale.ROOT,
                "Tremor #%d: anger %.1f, stage %s%s", entity.instance(), entity.anger(),
                entity.stage().name().toLowerCase(Locale.ROOT), seeking)));
        return entity.stage().ordinal();
    }

    private static int ai(CommandContext<CommandSourceStack> ctx, boolean on) throws CommandSyntaxException {
        TremorRuntime runtime = requireRuntime(ctx.getSource());
        runtime.setAi(on);
        answer(ctx.getSource(), () -> Component.literal("Tremor #" + runtime.entity().instance() + ": ai "
                + (on ? "on" : "off (it only obeys goto and stop)")));
        return 1;
    }

    private static int debug(CommandContext<CommandSourceStack> ctx, DebugView view, boolean on)
            throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        DebugParticles.set(player, view, on);
        answer(ctx.getSource(), () -> Component.literal("Tremor debug " + view.id() + (on ? " on" : " off")));
        return 1;
    }

    /**
     * A short answer to whoever ran the command: in the chat, or above the hotbar for an operator who wants it quiet
     * ({@code /tremor probe quiet}: the clicks of the multitool do not fill the chat). Long readouts (info, lists) and
     * failures always go to the chat.
     */
    private static void answer(CommandSourceStack source, Supplier<Component> message) {
        if (source.getEntity() instanceof ServerPlayer player && DebugProbe.quiet(player)) {
            source = source.withSource(new ActionBar(player));
        }
        source.sendSuccess(message, false);
    }

    /** Where {@link #answer} puts a quiet answer: above the hotbar of the player. */
    private record ActionBar(ServerPlayer player) implements CommandSource {
        @Override
        public void sendSystemMessage(Component message) {
            player.displayClientMessage(message, true);
        }

        @Override
        public boolean acceptsSuccess() {
            return player.acceptsSuccess();
        }

        @Override
        public boolean acceptsFailure() {
            return player.acceptsFailure();
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }
    }

    /** The entity nearest to where the command runs (a dimension may have several). */
    private static TremorRuntime requireRuntime(CommandSourceStack source) throws CommandSyntaxException {
        TremorRuntime runtime = TremorManager.nearest(source.getLevel(), source.getPosition().x,
                source.getPosition().y, source.getPosition().z);
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
