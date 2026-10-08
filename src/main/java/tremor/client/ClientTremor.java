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

/**
 * Client view of the entity in the current dimension: the latest server snapshots, interpolated for rendering and
 * sound. Main (render) thread only.
 * <p>
 * Everything received belongs to the level it arrived in and is dropped when the player logs out or the client
 * level changes (another dimension). Interpolation and its timing: {@link SnapshotPlayback}.
 */
public final class ClientTremor {
    private static final SnapshotPlayback playback = new SnapshotPlayback();
    /** Level the received data belongs to, null before anything arrived. */
    private static ClientLevel owner;
    private static BumpParams shape;
    private static int shapeInstance;
    /** The entity as of the latest {@link #renderState} call, null if there is none. */
    private static Presence presence;

    private ClientTremor() {
    }

    public static void acceptState(TremorStatePayload payload) {
        long now = System.nanoTime();
        adopt(Minecraft.getInstance().level);
        if (!payload.present()) {
            playback.absent(payload.instance());
            return;
        }
        int rippleAge = payload.rippleAge();
        long rippleStart = rippleAge >= 0 && rippleAge < TremorStatePayload.NO_RIPPLE
                ? payload.gameTime() - rippleAge : SnapshotPlayback.NO_RIPPLE;
        SnapshotPlayback.Snapshot snap = SnapshotPlayback.Snapshot.of(payload.gameTime(), payload.position(),
                payload.normal(), payload.forward(), payload.velocity(), payload.amplitude(), payload.phase(),
                payload.stage(), rippleStart);
        if (snap != null) {
            playback.accept(payload.instance(), snap, now);
        }
    }

    public static void acceptShape(TremorShapePayload payload) {
        adopt(Minecraft.getInstance().level);
        shape = payload.params();
        shapeInstance = payload.instance();
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    /**
     * The entity's state this frame, or null if there is no entity (or nothing known yet). Whether any of it is high
     * enough to be drawn is up to the caller. Call once per frame (the renderer does, also when it draws nothing):
     * this advances the playback and updates {@link #presence()}.
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
        SnapshotPlayback.Sample sample = playback.sample(level.getGameTime() + partialTick, System.nanoTime(),
                SharedConstants.TICKS_PER_SECOND, running);
        presence = sample == null ? null : new Presence(sample.frame().center(),
                running ? sample.velocity() : Vec3.ZERO, sample.amplitude(), sample.stage(), playback.instance());
        if (sample == null || shape == null || shapeInstance != playback.instance()) {
            return null;
        }
        return new RenderState(sample.frame(), shape.withAmplitude(sample.amplitude()), shape.amplitude(),
                sample.phase(), playback.instance(), sample.stage(), sample.rippleAge());
    }

    /**
     * The entity in the current level as of the latest frame, for effects that follow it between frames (the
     * rustle); null if there is none.
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
        playback.clear();
        owner = null;
        shape = null;
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
