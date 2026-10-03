package tremor.debug;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3f;
import tremor.config.TremorConfig;
import tremor.core.graph.SurfaceGraph;
import tremor.core.hearing.Hearing;
import tremor.core.hearing.HearingParams;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.motion.Crawler;
import tremor.core.path.PathSpline;
import tremor.entity.TremorEntity;
import tremor.entity.TremorManager;
import tremor.entity.TremorRuntime;
import tremor.hearing.Perception;
import tremor.hearing.Vibration;
import tremor.world.LevelVoxelView;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Debug visualisation with particles ({@code /tremor debug}, SPEC 14.1), sent by the server only to the players who
 * enabled a {@link DebugView}, every {@value #INTERVAL} ticks while they are within {@value #RANGE} blocks of the
 * entity. Enabled views are kept per player in memory until the server stops.
 * <p>
 * Skin particles sit just outside the surface: voxel centre plus {@value #SKIN_OFFSET} along the smoothed normal.
 * Particles inside rock (dive segments and edges) are only visible in spectator mode.
 * <p>
 * The {@link DebugView#HEARING} view is event-driven instead ({@link #hearing}): every player in the level who enabled
 * it sees every vibration the entity evaluates, wherever the player is.
 */
public final class DebugParticles {
    public static final int INTERVAL = 4;
    public static final double RANGE = 64;

    private static final double SKIN_OFFSET = 0.6;
    private static final double PATH_STEP = 0.5;
    /** Caps the particles of a long route (each one is a packet). */
    private static final int MAX_PATH_PARTICLES = 600;
    private static final int NORMALS_RADIUS = 5;
    private static final int GRAPH_RADIUS = 4;

    private static final DustParticleOptions PATH_SKIN = dust(0.2f, 1f, 0.2f, 0.8f);
    private static final DustParticleOptions PATH_DIVE = dust(1f, 0.15f, 0.15f, 0.8f);
    private static final DustParticleOptions TARGET = dust(1f, 0.85f, 0.1f, 2.5f);
    private static final DustParticleOptions NORMAL = dust(0.2f, 0.9f, 1f, 0.4f);
    private static final DustParticleOptions ENTITY_NORMAL = dust(1f, 0.3f, 1f, 0.7f);
    private static final DustParticleOptions NODE = dust(0.3f, 0.45f, 1f, 0.6f);
    private static final DustParticleOptions EDGE = dust(0.75f, 0.85f, 1f, 0.35f);
    private static final DustParticleOptions DIVE_EDGE = dust(1f, 0.5f, 0.1f, 0.5f);
    /** The hearing particle sits this far above the centre of the voxel the vibration enters through. */
    private static final double HEARING_LIFT = 0.6;

    private static final Map<UUID, EnumSet<DebugView>> VIEWS = new HashMap<>();

    private record Dot(ParticleOptions options, Vec3 at) {
    }

    private DebugParticles() {
    }

    public static void set(ServerPlayer player, DebugView view, boolean on) {
        if (on) {
            VIEWS.computeIfAbsent(player.getUUID(), id -> EnumSet.noneOf(DebugView.class)).add(view);
        } else {
            EnumSet<DebugView> views = VIEWS.get(player.getUUID());
            if (views != null && views.remove(view) && views.isEmpty()) {
                VIEWS.remove(player.getUUID());
            }
        }
    }

    public static boolean isOn(ServerPlayer player, DebugView view) {
        EnumSet<DebugView> views = VIEWS.get(player.getUUID());
        return views != null && views.contains(view);
    }

    // ---- events (registered on the game bus by Tremor, after TremorManager's tick) ----

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (VIEWS.isEmpty() || !(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % INTERVAL != 0) {
            return;
        }
        TremorRuntime runtime = TremorManager.runtime(level);
        TremorEntity entity = runtime == null ? null : runtime.entity();
        if (entity == null || runtime.isPaused() || runtime.error() != null) {
            return;
        }
        Vec3 c = entity.crawler().position();
        Map<DebugView, List<Dot>> dots = new EnumMap<>(DebugView.class);
        for (ServerPlayer player : level.players()) {
            EnumSet<DebugView> views = VIEWS.get(player.getUUID());
            if (views == null || player.distanceToSqr(c.x(), c.y(), c.z()) > RANGE * RANGE) {
                continue;
            }
            for (DebugView view : views) {
                if (view == DebugView.HEARING) {
                    continue; // drawn per vibration
                }
                for (Dot dot : dots.computeIfAbsent(view, v -> build(v, runtime.graph(), entity))) {
                    level.sendParticles(player, dot.options(), true, dot.at().x(), dot.at().y(), dot.at().z(),
                            1, 0, 0, 0, 0);
                }
            }
        }
    }

    public static void onServerStopping(ServerStoppingEvent event) {
        VIEWS.clear();
    }

    /**
     * Hearing view: a vibration the entity of {@code level} evaluated (within the hearing distance). A particle above
     * the voxel it enters through (green heard, red not, size growing with the loudness) and, on the action bar,
     * {@code event L=loudness footing=f d=distance res=average resistance -> perceived / threshold HEARD}; when heard,
     * also {@code anger +added = anger, stage (before -> after if it changed): reaction} (SPEC 8).
     */
    public static void hearing(ServerLevel level, Vibration vibration, Perception perception) {
        if (VIEWS.isEmpty()) {
            return;
        }
        List<ServerPlayer> viewers = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (isOn(player, DebugView.HEARING)) {
                viewers.add(player);
            }
        }
        if (viewers.isEmpty()) {
            return;
        }
        HearingParams params = TremorConfig.COMMON.hearingParams();
        LevelVoxelView view = new LevelVoxelView(level);
        // As the entity heard it: the leaves a rustling source rustles in conduct at its footing.
        double resistance = Hearing.averageResistance(view, vibration.source(), perception.listener(),
                params.sampleStep(), params.minConductivity(), vibration.foliage(view), vibration.footing());
        float size = (float) Math.min(DustParticleOptions.MAX_SCALE, 0.5 + 0.15 * vibration.loudness());
        DustParticleOptions dot = perception.heard() ? dust(0.2f, 1f, 0.2f, size) : dust(1f, 0.15f, 0.15f, size);
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                "%s L=%.1f footing=%.2f d=%.1f res=%.2f -> %.2f / %.2f %s", vibration.event(), vibration.loudness(),
                vibration.footing(), perception.distance(), resistance, perception.perceived(), params.threshold(),
                perception.heard() ? "HEARD" : "not heard"));
        if (vibration.note() != null) {
            text.append(" (").append(vibration.note()).append(')');
        }
        if (perception.heard()) {
            String stage = perception.stage().name().toLowerCase(Locale.ROOT);
            if (perception.stageBefore() != perception.stage()) {
                stage = perception.stageBefore().name().toLowerCase(Locale.ROOT) + " -> " + stage;
            }
            text.append(String.format(Locale.ROOT, ", anger %+.1f = %.1f, %s: %s", perception.angerAdded(),
                    perception.anger(), stage, perception.reaction()));
        }
        Component message = Component.literal(text.toString());
        Vec3 at = vibration.source().add(0, HEARING_LIFT, 0);
        for (ServerPlayer player : viewers) {
            level.sendParticles(player, dot, true, at.x(), at.y(), at.z(), 1, 0, 0, 0, 0);
            player.displayClientMessage(message, true);
        }
    }

    // ---- views ----

    private static List<Dot> build(DebugView view, SurfaceGraph graph, TremorEntity entity) {
        List<Dot> dots = new ArrayList<>();
        switch (view) {
            case PATH -> path(graph, entity, dots);
            case NORMALS -> normals(graph, entity, dots);
            case GRAPH -> graph(graph, entity, dots);
            case HEARING -> throw new IllegalArgumentException("drawn per vibration, see hearing()");
        }
        return dots;
    }

    /** The remaining route every {@value #PATH_STEP} blocks, and a column of big particles over the target. */
    private static void path(SurfaceGraph graph, TremorEntity entity, List<Dot> dots) {
        Crawler crawler = entity.crawler();
        PathSpline spline = crawler.spline();
        if (spline != null) {
            double length = spline.length();
            for (double s = crawler.progress(); s <= length && dots.size() < MAX_PATH_PARTICLES; s += PATH_STEP) {
                Vec3 p = spline.position(s);
                dots.add(spline.isDive(s) ? new Dot(PATH_DIVE, p) : new Dot(PATH_SKIN, onSkin(graph, p)));
            }
        }
        BlockPos target = entity.target();
        if (target != null) {
            Vec3 n = normalOrUp(graph, target.getX(), target.getY(), target.getZ());
            Vec3 center = Vec3.voxelCenter(target.getX(), target.getY(), target.getZ());
            for (int i = 0; i < 3; i++) {
                dots.add(new Dot(TARGET, center.add(n.scale(SKIN_OFFSET + 0.6 * i))));
            }
        }
    }

    /** Short lines along the smoothed normals of nearby nodes, and a longer one for the entity's own normal. */
    private static void normals(SurfaceGraph graph, TremorEntity entity, List<Dot> dots) {
        Crawler crawler = entity.crawler();
        Vec3 c = crawler.position();
        int cx = floor(c.x()), cy = floor(c.y()), cz = floor(c.z());
        int r = NORMALS_RADIUS;
        for (int dy = -r; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int x = cx + dx, y = cy + dy, z = cz + dz;
                    if (dx * dx + dy * dy + dz * dz > r * r || !graph.isNode(x, y, z)) {
                        continue;
                    }
                    Vec3 n = graph.normal(x, y, z);
                    if (n.isNearZero()) {
                        continue;
                    }
                    Vec3 skin = Vec3.voxelCenter(x, y, z).add(n.scale(0.5));
                    for (int k = 0; k < 4; k++) {
                        dots.add(new Dot(NORMAL, skin.add(n.scale(0.1 + 0.25 * k))));
                    }
                }
            }
        }
        Vec3 n = crawler.normal();
        Vec3 skin = c.add(n.scale(0.5));
        for (int k = 0; k < 9; k++) {
            dots.add(new Dot(ENTITY_NORMAL, skin.add(n.scale(0.1 + 0.25 * k))));
        }
    }

    /** Nodes near the entity and the midpoints of their edges, each edge once. */
    private static void graph(SurfaceGraph graph, TremorEntity entity, List<Dot> dots) {
        Vec3 c = entity.crawler().position();
        int cx = floor(c.x()), cy = floor(c.y()), cz = floor(c.z());
        int r = GRAPH_RADIUS;
        for (int dy = -r; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    int x = cx + dx, y = cy + dy, z = cz + dz;
                    if (dx * dx + dy * dy + dz * dz > r * r || !graph.isNode(x, y, z)) {
                        continue;
                    }
                    long a = VoxelPos.pack(x, y, z);
                    Vec3 ca = Vec3.voxelCenter(x, y, z);
                    Vec3 na = graph.normal(x, y, z);
                    dots.add(new Dot(NODE, ca.add(na.scale(SKIN_OFFSET))));
                    graph.forEachEdge(a, (b, length, dive) -> {
                        int bx = VoxelPos.x(b), by = VoxelPos.y(b), bz = VoxelPos.z(b);
                        int ex = bx - cx, ey = by - cy, ez = bz - cz;
                        if (b < a && ex * ex + ey * ey + ez * ez <= r * r) {
                            return; // drawn from the other end
                        }
                        Vec3 mid = ca.lerp(Vec3.voxelCenter(bx, by, bz), 0.5);
                        if (dive) {
                            dots.add(new Dot(DIVE_EDGE, mid));
                        } else {
                            Vec3 n = na.add(graph.normal(bx, by, bz)).normalize();
                            dots.add(new Dot(EDGE, mid.add(n.scale(SKIN_OFFSET))));
                        }
                    });
                }
            }
        }
    }

    /** The point pushed out of the rock along the normal of the nearest node. */
    private static Vec3 onSkin(SurfaceGraph graph, Vec3 p) {
        long node = graph.nearestNode(p, 1);
        if (node == SurfaceGraph.NO_NODE) {
            return p;
        }
        return p.add(graph.normal(VoxelPos.x(node), VoxelPos.y(node), VoxelPos.z(node)).scale(SKIN_OFFSET));
    }

    private static Vec3 normalOrUp(SurfaceGraph graph, int x, int y, int z) {
        Vec3 n = graph.normal(x, y, z);
        return n.isNearZero() ? Vec3.UNIT_Y : n;
    }

    private static DustParticleOptions dust(float r, float g, float b, float scale) {
        return new DustParticleOptions(new Vector3f(r, g, b), scale);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
