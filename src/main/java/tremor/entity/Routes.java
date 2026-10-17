package tremor.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import tremor.core.graph.SurfaceGraph;

/**
 * Routes for filming ({@code /tremor route}, SPEC 14.1): an operator lays out points (the blocks looked at), some of
 * them listening stops, and the nearest entity crawls them in order, once or round and round. It goes from one to the
 * next as by {@code /tremor goto}, its own decisions off ({@code ai off}) and its anger held, so it looks the same all
 * along (set the look first with {@code /tremor stage}); at a listening stop it stands and sends a ring of ripples
 * through the ground for {@value #LISTEN_TICKS} ticks. When the route is over (or stopped) it gets its decisions and its
 * anger back. Kept in memory, never saved. Server thread only; registered by {@link tremor.Tremor}.
 */
public final class Routes {
    /** How long a listening stop lasts (ticks). */
    static final int LISTEN_TICKS = 60;
    /** Who laid out the points of a command run from no player (the console). */
    private static final UUID CONSOLE = new UUID(0, 0);

    /** A point of a route: where to go, and whether to stop and listen there. */
    public record Point(BlockPos pos, boolean listen) {
    }

    /** The points each operator laid out, in order. */
    private static final Map<UUID, List<Point>> LAID = new HashMap<>();
    /** The routes being crawled, by the runtime of the entity crawling. */
    private static final Map<TremorRuntime, Run> RUNS = new HashMap<>();
    /** Operators who see their route and the points the tremors are lured to, as particles. */
    private static final java.util.Set<UUID> SHOWN = new java.util.HashSet<>();
    /** Game ticks between two drawings of what is shown. */
    private static final int SHOW_INTERVAL = 10;
    /** Particles drawn for one operator at most, per drawing. */
    private static final int MAX_SHOWN = 400;
    /** How far from the operator the lure points of the tremors show (blocks). */
    private static final double LURE_RANGE = 160;
    // Particles that glow by themselves, so the route shows in a dark cave as well as by day.
    private static final net.minecraft.core.particles.ParticleOptions POINT =
            net.minecraft.core.particles.ParticleTypes.WAX_OFF;
    private static final net.minecraft.core.particles.ParticleOptions LISTEN =
            net.minecraft.core.particles.ParticleTypes.SCRAPE;
    private static final net.minecraft.core.particles.ParticleOptions NEXT =
            net.minecraft.core.particles.ParticleTypes.WAX_ON;
    private static final net.minecraft.core.particles.ParticleOptions LINE =
            net.minecraft.core.particles.ParticleTypes.ELECTRIC_SPARK;
    private static final net.minecraft.core.particles.ParticleOptions LURE =
            net.minecraft.core.particles.ParticleTypes.FLAME;

    private static final class Run {
        final UUID owner;
        final List<Point> points;
        final boolean loop;
        final boolean aiBefore;
        /** The point it heads for, or is at. */
        int index;
        /** A goto was given for the point at {@link #index}. */
        boolean headed;
        /** It has listened at the point at {@link #index} already. */
        boolean listened;
        int waitTicks;
        /** Points in a row it could not head for: all of them means none can be reached. */
        int failures;

        Run(UUID owner, List<Point> points, boolean loop, boolean aiBefore) {
            this.owner = owner;
            this.points = points;
            this.loop = loop;
            this.aiBefore = aiBefore;
        }
    }

    private Routes() {
    }

    /** The key of whoever runs a command: the player, or the console. */
    public static UUID owner(ServerPlayer player) {
        return player == null ? CONSOLE : player.getUUID();
    }

    /** The points {@code owner} laid out so far. */
    public static List<Point> laid(UUID owner) {
        return List.copyOf(LAID.getOrDefault(owner, List.of()));
    }

    /** Adds a point to the end of the route of {@code owner}; returns how many it has now. */
    public static int add(UUID owner, BlockPos pos, boolean listen) {
        List<Point> points = LAID.computeIfAbsent(owner, key -> new ArrayList<>());
        points.add(new Point(pos.immutable(), listen));
        return points.size();
    }

    /** Takes the last point away; returns how many are left. */
    public static int undo(UUID owner) {
        List<Point> points = LAID.get(owner);
        if (points != null && !points.isEmpty()) {
            points.remove(points.size() - 1);
        }
        return points == null ? 0 : points.size();
    }

    public static void clear(UUID owner) {
        LAID.remove(owner);
    }

    /**
     * {@code runtime}'s entity starts crawling the route of {@code owner} (a copy: laying out more changes nothing
     * now), its decisions off and its anger held. False if the route has no points or there is no entity.
     */
    public static boolean go(TremorRuntime runtime, UUID owner, boolean loop) {
        TremorEntity entity = runtime.entity();
        List<Point> points = laid(owner);
        if (entity == null || points.isEmpty()) {
            return false;
        }
        Run old = RUNS.remove(runtime);
        boolean aiBefore = old != null ? old.aiBefore : entity.aiEnabled();
        RUNS.put(runtime, new Run(owner, points, loop, aiBefore));
        runtime.setAi(false);
        runtime.holdAnger(true);
        return true;
    }

    /** Shows the route of {@code owner} and the lure points, or stops (null: the other way round); returns which. */
    public static boolean show(UUID owner, Boolean on) {
        boolean shown = on != null ? on : !SHOWN.contains(owner);
        if (shown) {
            SHOWN.add(owner);
        } else {
            SHOWN.remove(owner);
        }
        return shown;
    }

    /** How the route of {@code runtime}'s entity goes, for the panel of the probe; null if it crawls none. */
    public static String describe(TremorRuntime runtime) {
        Run run = RUNS.get(runtime);
        if (run == null) {
            return null;
        }
        return String.format(java.util.Locale.ROOT, "Route: %s point %d of %d%s", run.waitTicks > 0 ? "listening at"
                : "going to", run.index + 1, run.points.size(), run.loop ? ", round and round" : ", once");
    }

    /** Whether {@code runtime}'s entity crawls a route. */
    public static boolean running(TremorRuntime runtime) {
        return RUNS.containsKey(runtime);
    }

    /** Stops the route of {@code runtime}'s entity where it is; false if it crawled none. */
    public static boolean stop(TremorRuntime runtime) {
        Run run = RUNS.remove(runtime);
        if (run == null) {
            return false;
        }
        release(runtime, run);
        runtime.stop();
        return true;
    }

    /** After the entity's tick: every route of the level goes on. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (RUNS.isEmpty() && SHOWN.isEmpty() || !(event.getLevel() instanceof ServerLevel level)
                || !level.tickRateManager().runsNormally()) {
            return;
        }
        if (!SHOWN.isEmpty() && level.getGameTime() % SHOW_INTERVAL == 0) {
            draw(level);
        }
        for (Iterator<Map.Entry<TremorRuntime, Run>> it = RUNS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<TremorRuntime, Run> entry = it.next();
            TremorRuntime runtime = entry.getKey();
            if (runtime.level() != level) {
                continue;
            }
            String over = step(runtime, entry.getValue());
            if (over != null) {
                it.remove();
                if (runtime.entity() != null) {
                    release(runtime, entry.getValue());
                }
                tell(level, entry.getValue().owner, over);
            }
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        RUNS.clear();
        LAID.clear();
        SHOWN.clear();
    }

    /**
     * To each operator who asked for it ({@link #show}), in their level: their route (each point a column of white
     * glow, a listening point teal, the one a tremor heads for orange; the way between them a line of sparks) and the
     * points the tremors near them are lured to by {@code /tremor goto} (a column of flames).
     */
    private static void draw(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            if (!SHOWN.contains(player.getUUID())) {
                continue;
            }
            int[] budget = {MAX_SHOWN};
            List<Point> points = LAID.getOrDefault(player.getUUID(), List.of());
            BlockPos heading = null;
            for (Map.Entry<TremorRuntime, Run> entry : RUNS.entrySet()) {
                if (entry.getValue().owner.equals(player.getUUID()) && entry.getKey().level() == level) {
                    heading = entry.getValue().points.get(entry.getValue().index).pos();
                }
            }
            for (int i = 0; i < points.size(); i++) {
                Point point = points.get(i);
                column(level, player, point.pos(), point.pos().equals(heading) ? NEXT
                        : point.listen() ? LISTEN : POINT, 3, budget);
                if (i + 1 < points.size()) {
                    line(level, player, point.pos(), points.get(i + 1).pos(), budget);
                }
            }
            for (TremorRuntime runtime : TremorManager.runtimes(level)) {
                TremorEntity entity = runtime.entity();
                BlockPos target = entity == null ? null : entity.target();
                if (target != null && entity.targetKind() == TremorEntity.TargetKind.MANUAL && !RUNS.containsKey(runtime)
                        && player.distanceToSqr(target.getX(), target.getY(), target.getZ()) < LURE_RANGE * LURE_RANGE) {
                    column(level, player, target, LURE, 4, budget);
                }
            }
        }
    }

    /** A column of {@code height} particles over the block at {@code pos}. */
    private static void column(ServerLevel level, ServerPlayer player, BlockPos pos,
                               net.minecraft.core.particles.ParticleOptions dust, int height, int[] budget) {
        for (int k = 0; k < height && budget[0]-- > 0; k++) {
            level.sendParticles(player, dust, true, pos.getX() + 0.5, pos.getY() + 1.3 + 0.5 * k, pos.getZ() + 0.5, 1,
                    0, 0, 0, 0);
        }
    }

    /** A line of sparks a block apart from one point to the next. */
    private static void line(ServerLevel level, ServerPlayer player, BlockPos from, BlockPos to, int[] budget) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        int steps = (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz));
        for (int k = 1; k < steps && budget[0]-- > 0; k++) {
            double t = (double) k / steps;
            level.sendParticles(player, LINE, true, from.getX() + 0.5 + dx * t, from.getY() + 1.2 + dy * t,
                    from.getZ() + 0.5 + dz * t, 1, 0, 0, 0, 0);
        }
    }

    /** One tick of a route; returns why it is over, or null while it goes on. */
    private static String step(TremorRuntime runtime, Run run) {
        TremorEntity entity = runtime.entity();
        if (entity == null || entity.absorbed() || entity.leaving()) {
            return "Route over: the tremor is gone";
        }
        if (run.waitTicks > 0) {
            run.waitTicks--;
            return null;
        }
        if (run.headed) {
            if (entity.target() != null) {
                return null; // still on its way
            }
            run.headed = false; // there, or it could not get there
            if (run.points.get(run.index).listen() && !run.listened) {
                run.listened = true;
                run.waitTicks = LISTEN_TICKS;
                runtime.listenHere();
                return null;
            }
            if (!advance(run)) {
                return "Route done: tremor #" + entity.instance() + " crawled all " + run.points.size() + " points";
            }
        }
        long node = runtime.snap(run.points.get(run.index).pos());
        if (node != SurfaceGraph.NO_NODE && runtime.moveTo(node, null)) {
            run.headed = true;
            run.failures = 0;
        } else if (++run.failures >= run.points.size()) {
            return "Route over: tremor #" + entity.instance() + " cannot get to any of its points";
        } else if (!advance(run)) {
            return "Route done: tremor #" + entity.instance() + " crawled what it could";
        }
        return null;
    }

    /** On to the next point (the first again on a loop); false at the end of a route crawled once. */
    private static boolean advance(Run run) {
        run.listened = false;
        if (++run.index < run.points.size()) {
            return true;
        }
        run.index = 0;
        return run.loop;
    }

    private static void release(TremorRuntime runtime, Run run) {
        runtime.holdAnger(false);
        runtime.setAi(run.aiBefore);
    }

    private static void tell(ServerLevel level, UUID owner, String message) {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
        if (player != null) {
            // Above the hotbar for an operator who wants it quiet (/tremor probe quiet).
            player.displayClientMessage(Component.literal(message), tremor.debug.DebugProbe.quiet(player));
        }
    }
}
