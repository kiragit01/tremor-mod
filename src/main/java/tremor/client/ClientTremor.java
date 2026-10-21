package tremor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.TickRateManager;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import tremor.core.behavior.Stage;
import tremor.core.math.Vec3;
import tremor.core.shape.BumpFrame;
import tremor.core.shape.BumpParams;
import tremor.network.TremorShapePayload;
import tremor.network.TremorStatePayload;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Client view of the entities in the current dimension: the latest server snapshots of each (by instance), interpolated
 * for rendering and sound. A dimension may have several entities, {@code spawn.spacing} apart: the client hears of
 * those within the server's sync range and draws them all ({@link #renderStates}); the one nearest to the camera is
 * heard rustling and shown by the seismograph ({@link #presence}). Main (render) thread only.
 * <p>
 * Everything received belongs to the level it arrived in and is dropped when the player logs out or the client
 * level changes (another dimension). Interpolation and its timing: {@link SnapshotPlayback}.
 */
public final class ClientTremor {
    /** The playback of each entity whose state arrives, by instance; dropped once its states stop coming. */
    private static final Map<Integer, SnapshotPlayback> playbacks = new HashMap<>();
    /** The shape of each entity of the level, by instance (sent to the whole dimension, kept until it is gone). */
    private static final Map<Integer, BumpParams> shapes = new HashMap<>();
    /** The leap of each entity as the server last told it, and how it is drawn, by instance. */
    private static final Map<Integer, TremorStatePayload.Leap> leapsTold = new HashMap<>();
    private static final Map<Integer, LeapView> leaps = new HashMap<>();
    /**
     * Over how much of its way (blocks) before the gap and after it the hill pours over: from this far before the edge
     * the hill on the far side starts to grow, and this far after the landing the one it left is gone.
     */
    private static final double LEAP_IN = 2.5;
    private static final double LEAP_OUT = 2.5;
    /** How fast the pouring finishes, or goes back, once the server no longer tells the leap (shares per second). */
    private static final double LEAP_SETTLE = 2;
    private static long lastStatesNanos;

    /**
     * How a leap is drawn: the leap, how far the hill has poured over (0..1, from the tremor's way through it), the
     * height of the hill before it began, and whether the tremor has left the ground it leaves.
     */
    private static final class LeapView {
        TremorStatePayload.Leap leap;
        double share;
        double rest = Double.NaN;
        boolean gone;
    }
    /** Level the received data belongs to, null before anything arrived. */
    private static ClientLevel owner;
    /** The nearest entity as of the latest {@link #renderState} call, null if there is none. */
    private static Presence presence;

    private ClientTremor() {
    }

    public static void acceptState(TremorStatePayload payload) {
        long now = System.nanoTime();
        adopt(Minecraft.getInstance().level);
        if (!payload.present()) {
            playbacks.remove(payload.instance());
            shapes.remove(payload.instance());
            return;
        }
        int rippleAge = payload.rippleAge();
        long rippleStart = rippleAge >= 0 && rippleAge < TremorStatePayload.NO_RIPPLE
                ? payload.gameTime() - rippleAge : SnapshotPlayback.NO_RIPPLE;
        SnapshotPlayback.Snapshot snap = SnapshotPlayback.Snapshot.of(payload.gameTime(), payload.position(),
                payload.normal(), payload.forward(), payload.velocity(), payload.amplitude(), payload.phase(),
                payload.stage(), rippleStart, payload.lurk());
        if (snap != null) {
            playbacks.computeIfAbsent(payload.instance(), instance -> new SnapshotPlayback())
                    .accept(payload.instance(), snap, now);
            if (payload.leap() != null) {
                leapsTold.put(payload.instance(), payload.leap());
            } else {
                leapsTold.remove(payload.instance());
            }
        }
    }

    public static void acceptShape(TremorShapePayload payload) {
        adopt(Minecraft.getInstance().level);
        shapes.put(payload.instance(), payload.params());
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    /**
     * The state this frame of every entity known (whose shape has come too), the nearest to the camera first; empty if
     * there is none. Whether any of it is high enough to be drawn is up to the caller. Call once per frame (the
     * renderer does, also when it draws nothing): this advances every playback and updates {@link #presence()}.
     *
     * @param partialTick partial game tick of the frame
     */
    public static List<RenderState> renderStates(ClientLevel level, float partialTick) {
        if (level == null) {
            presence = null;
            return List.of();
        }
        adopt(level);
        TickRateManager rates = level.tickRateManager();
        boolean running = rates.runsNormally() && !Minecraft.getInstance().isPaused();
        return states(level.getGameTime() + partialTick, rates.tickrate(), running);
    }

    /**
     * Advances every playback to {@code gameTime} (one whose states stopped coming is dropped), sets {@link #presence}
     * to the entity nearest to the camera and returns the render states of all whose shape is known, nearest first.
     */
    private static List<RenderState> states(double gameTime, double tickRate, boolean running) {
        long nanos = System.nanoTime();
        double dt = lastStatesNanos == 0 ? 0 : Math.min(0.1, (nanos - lastStatesNanos) / 1e9);
        lastStatesNanos = nanos;
        net.minecraft.world.phys.Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Vec3 eye = new Vec3(camera.x, camera.y, camera.z);
        List<RenderState> states = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        SnapshotPlayback.Sample best = null;
        int bestInstance = 0;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Iterator<Map.Entry<Integer, SnapshotPlayback>> it = playbacks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, SnapshotPlayback> entry = it.next();
            SnapshotPlayback.Sample sample = entry.getValue().sample(gameTime, nanos, tickRate, running);
            if (sample == null) {
                if (entry.getValue().isEmpty()) {
                    it.remove(); // gone stale: out of the sync range, or gone without word
                }
                continue;
            }
            double distance = sample.frame().center().distance(eye);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = sample;
                bestInstance = entry.getKey();
            }
            BumpParams shape = shapes.get(entry.getKey());
            if (shape != null) {
                int at = 0;
                while (at < distances.size() && distances.get(at) <= distance) {
                    at++;
                }
                distances.add(at, distance);
                // Lying in wait it is round, wide, low and breathing (core Ambush), more so as it settles in.
                BumpParams drawn = shape.lurking(sample.lurk(), tremor.core.behavior.Ambush.SPREAD).withAmplitude(
                        sample.amplitude() * tremor.core.behavior.Ambush.breath(gameTime / 20.0, sample.lurk()));
                RenderState state = new RenderState(sample.frame(), drawn, shape.amplitude(), sample.phase(),
                        entry.getKey(), sample.stage(), sample.rippleAge());
                distances.remove(at); // each hill goes in by its own distance
                for (RenderState part : pour(state, dt)) {
                    double d = part.frame().center().distance(eye);
                    int where = 0;
                    while (where < distances.size() && distances.get(where) <= d) {
                        where++;
                    }
                    distances.add(where, d);
                    states.add(where, part);
                }
            }
        }
        presence = best == null ? null : new Presence(best.frame().center(), running ? best.velocity() : Vec3.ZERO,
                best.amplitude(), best.stage(), bestInstance);
        return states;
    }

    /**
     * The hills of an entity this frame: its own, and while it leaps across a gap (the user's wish) the hill pours over
     * from where it leaves to where it lands. How far it has poured ({@code share}) follows the tremor's way: from
     * {@link #LEAP_IN} blocks before the edge, across the gap, to {@link #LEAP_OUT} blocks after the landing. Before the
     * gap its own hill sinks as one grows where it will land; across the gap one stands at each end, the height going
     * from the first to the second; after it, its own hill rises as the one it left sinks away. Once the server no
     * longer tells the leap, the pouring finishes (or goes back, if it never left) on its own.
     */
    private static List<RenderState> pour(RenderState own, double dt) {
        int instance = own.instance();
        TremorStatePayload.Leap told = leapsTold.get(instance);
        LeapView view = leaps.get(instance);
        if (told == null && view == null) {
            return List.of(own);
        }
        if (view == null) {
            view = new LeapView();
            leaps.put(instance, view);
        }
        if (Double.isNaN(view.rest) || told == null && view.leap == null) {
            view.rest = own.params().amplitude();
        }
        Vec3 c = own.frame().center();
        if (told != null) {
            if (view.leap == null || told.to().distanceSquared(view.leap.to()) > 1e-6) {
                view.leap = told;
                view.gone = false;
                view.share = 0;
                view.rest = Math.max(view.rest, own.params().amplitude());
            }
            Vec3 a = told.from(), b = told.to(), ab = b.sub(a);
            double length = ab.length();
            double t = length < 1e-6 ? 1 : c.sub(a).dot(ab) / (length * length);
            double way = t <= 0 ? -c.distance(a) : t >= 1 ? length + c.distance(b) : t * length;
            view.gone |= t > 0;
            view.share = Math.max(0, Math.min(1, (way + LEAP_IN) / (LEAP_IN + length + LEAP_OUT)));
        } else {
            // No longer told: it finishes pouring if it has left, it goes back if it never did.
            view.share = view.gone ? Math.min(1, view.share + dt * LEAP_SETTLE)
                    : Math.max(0, view.share - dt * LEAP_SETTLE);
            if (view.share <= 0 || view.share >= 1) {
                leaps.remove(instance);
                return List.of(own);
            }
        }
        TremorStatePayload.Leap leap = view.leap;
        double poured = smoothstep(view.share), rest = view.rest;
        Vec3 a = leap.from(), b = leap.to();
        boolean before = !view.gone, across = view.gone && c.sub(a).dot(b.sub(a)) < b.sub(a).dot(b.sub(a));
        List<RenderState> parts = new ArrayList<>(3);
        // Its own hill: the one it leaves before the gap, nothing across it, the one it lands in after it.
        double ownHeight = before ? rest * (1 - poured) : across ? 0 : rest * poured;
        parts.add(withHeight(own, own.frame(), ownHeight, instance));
        if (!before) {
            parts.add(withHeight(own, new BumpFrame(a, leap.fromNormal(), b.sub(a)), rest * (1 - poured),
                    -(instance * 2 + 1)));
        }
        if (before || across) {
            parts.add(withHeight(own, new BumpFrame(b, leap.toNormal(), b.sub(a)), rest * poured,
                    -(instance * 2 + 2)));
        }
        return parts;
    }

    private static RenderState withHeight(RenderState own, BumpFrame frame, double height, int instance) {
        return new RenderState(frame, own.params().withAmplitude(height), own.fullAmplitude(), own.timeSeconds(),
                instance, own.stage(), instance == own.instance() ? own.rippleAgeSeconds() : Double.NaN);
    }

    private static double smoothstep(double x) {
        double t = Math.max(0, Math.min(1, x));
        return t * t * (3 - 2 * t);
    }

    /**
     * The entity nearest to the camera in the current level as of the latest frame, for effects that follow it between
     * frames (the rustle, the seismograph); null if there is none.
     */
    public static Presence presence() {
        return presence;
    }

    /** Binds the received data to {@code level}; anything received in another level is dropped. */
    private static void adopt(ClientLevel level) {
        if (level != owner) {
            if (owner != null) {
                clear();
            }
            owner = level;
        }
    }

    private static void clear() {
        playbacks.clear();
        shapes.clear();
        leapsTold.clear();
        leaps.clear();
        owner = null;
        presence = null;
    }

    /**
     * @param frame            interpolated bump frame (centre on the skin, smoothed normal, forward)
     * @param params           shape with the current (interpolated) amplitude, which may be below the render
     *                         threshold (diving) or negative
     * @param fullAmplitude    the shape's own amplitude, as the server sent it: the bump's full height, before the
     *                         stage factor and the dives scale it into {@code params}
     * @param timeSeconds      animation clock for the jitter
     * @param instance         entity instance, changes on respawn
     * @param stage            aggression stage (SPEC 8)
     * @param rippleAgeSeconds age of the latest ground ripple (SPEC 8, ALERT) on the playback clock: advances
     *                         smoothly between snapshots and stands still while the game is paused; NaN if none has
     *                         started. It keeps growing after the ripple is over; when that is, is up to the renderer.
     */
    public record RenderState(BumpFrame frame, BumpParams params, double fullAmplitude, double timeSeconds,
                              int instance, Stage stage, double rippleAgeSeconds) {
    }

    /**
     * Where the entity is and how it moves, interpolated.
     *
     * @param center    bump centre on the skin
     * @param velocity  blocks per second; zero while the game time stands still (paused, frozen)
     * @param amplitude current height of the bump (low or negative while diving)
     * @param stage     aggression stage (SPEC 8)
     * @param instance  entity instance, changes on respawn
     */
    public record Presence(Vec3 center, Vec3 velocity, double amplitude, Stage stage, int instance) {
    }
}
