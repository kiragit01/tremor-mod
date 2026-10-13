package tremor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.SharedConstants;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import tremor.core.behavior.Stage;
import tremor.core.math.Vec3;
import tremor.core.shape.BumpFrame;
import tremor.core.shape.BumpParams;
import tremor.network.TremorShapePayload;
import tremor.network.TremorStatePayload;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Client view of the entities in the current dimension: the latest server snapshots of each (by instance), interpolated
 * for rendering and sound. A dimension may have several entities, {@code spawn.spacing} apart: the client hears of
 * those within the server's sync range, and the one nearest to the camera is the one drawn, heard rustling and shown
 * by the seismograph ({@link #renderState}, {@link #presence}). Main (render) thread only.
 * <p>
 * Everything received belongs to the level it arrived in and is dropped when the player logs out or the client
 * level changes (another dimension). Interpolation and its timing: {@link SnapshotPlayback}.
 */
public final class ClientTremor {
    /** The playback of each entity whose state arrives, by instance; dropped once its states stop coming. */
    private static final Map<Integer, SnapshotPlayback> playbacks = new HashMap<>();
    /** The shape of each entity of the level, by instance (sent to the whole dimension, kept until it is gone). */
    private static final Map<Integer, BumpParams> shapes = new HashMap<>();
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
                payload.stage(), rippleStart);
        if (snap != null) {
            playbacks.computeIfAbsent(payload.instance(), instance -> new SnapshotPlayback())
                    .accept(payload.instance(), snap, now);
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
     * The state this frame of the entity nearest to the camera, or null if there is none (or nothing known yet).
     * Whether any of it is high enough to be drawn is up to the caller. Call once per frame (the renderer does, also
     * when it draws nothing): this advances every playback and updates {@link #presence()}.
     *
     * @param partialTick partial game tick of the frame
     */
    public static RenderState renderState(ClientLevel level, float partialTick) {
        if (level == null) {
            presence = null;
            return null;
        }
        adopt(level);
        boolean running = !Minecraft.getInstance().isPaused();
        return nearest(level.getGameTime() + partialTick, SharedConstants.TICKS_PER_SECOND, running);
    }

    /**
     * Advances every playback to {@code gameTime} (one whose states stopped coming is dropped), sets {@link #presence}
     * to the entity nearest to the camera and returns its render state (null if there is none, or its shape is not
     * known).
     */
    private static RenderState nearest(double gameTime, double tickRate, boolean running) {
        long nanos = System.nanoTime();
        net.minecraft.world.phys.Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Vec3 eye = new Vec3(camera.x, camera.y, camera.z);
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
        }
        presence = best == null ? null : new Presence(best.frame().center(), running ? best.velocity() : Vec3.ZERO,
                best.amplitude(), best.stage(), bestInstance);
        BumpParams shape = best == null ? null : shapes.get(bestInstance);
        if (shape == null) {
            return null;
        }
        return new RenderState(best.frame(), shape.withAmplitude(best.amplitude()), shape.amplitude(), best.phase(),
                bestInstance, best.stage(), best.rippleAge());
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
