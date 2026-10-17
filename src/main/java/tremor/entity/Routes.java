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
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
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
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || RUNS.isEmpty() || !(event.level instanceof ServerLevel level)) {
            return;
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
            player.sendSystemMessage(Component.literal(message));
        }
    }
}
