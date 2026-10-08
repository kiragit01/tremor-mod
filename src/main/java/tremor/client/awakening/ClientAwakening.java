package tremor.client.awakening;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import tremor.core.math.Vec3;
import tremor.network.TremorAwakeningPayload;
import tremor.network.TremorStepRipplePayload;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Client view of the Awakening in the current level (SPEC 9): the last state the server sent and the recent step
 * ripples. Read by the effects (ground breathing and rings, the swallow hill, silence, heartbeat). Main thread only.
 * Everything is dropped on logout and when the client level changes.
 */
public final class ClientAwakening {
    /** Step ripples older than this (game ticks) are forgotten. */
    public static final int RIPPLE_MEMORY_TICKS = 100;
    /** At most this many step ripples are kept (the oldest go first). */
    public static final int MAX_RIPPLES = 32;

    /** A ring wave from a step: where, when (level game time) and how strong. */
    public record StepRipple(Vec3 position, long gameTime, float strength) {
    }

    private static ClientLevel owner;
    private static TremorAwakeningPayload state;
    private static final Deque<StepRipple> ripples = new ArrayDeque<>();

    private ClientAwakening() {
    }

    public static void accept(TremorAwakeningPayload payload) {
        adopt(Minecraft.getInstance().level);
        if (payload.phase() == TremorAwakeningPayload.Phase.ENDED) {
            if (state == null || state.id() == payload.id()) {
                state = null;
                ripples.clear();
            }
            return;
        }
        if (state == null || state.id() != payload.id()) {
            ripples.clear();
        }
        state = payload;
    }

    public static void acceptRipple(TremorStepRipplePayload payload) {
        adopt(Minecraft.getInstance().level);
        if (state == null || state.id() != payload.event()) {
            return;
        }
        ripples.addLast(new StepRipple(payload.position(), payload.gameTime(), payload.strength()));
        while (ripples.size() > MAX_RIPPLES) {
            ripples.removeFirst();
        }
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    /** The current Awakening of this level, or null. */
    public static TremorAwakeningPayload state() {
        adopt(Minecraft.getInstance().level);
        return state;
    }

    /** True if there is an Awakening and the local player is the one it is about. */
    public static boolean isTarget() {
        Minecraft mc = Minecraft.getInstance();
        TremorAwakeningPayload s = state();
        UUID id = mc.player == null ? null : mc.player.getUUID();
        return s != null && id != null && id.equals(s.target());
    }

    /**
     * Fraction of the current phase that has passed, 0..1 (0 for an open-ended phase).
     *
     * @param gameTime level game time with the partial tick
     */
    public static double progress(double gameTime) {
        TremorAwakeningPayload s = state();
        if (s == null || s.phaseTicks() <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(1, (gameTime - s.phaseStart()) / s.phaseTicks()));
    }

    /** True if the point is inside the zone (horizontal distance to the centre within the radius). */
    public static boolean inZone(Vec3 point) {
        TremorAwakeningPayload s = state();
        if (s == null) {
            return false;
        }
        double dx = point.x() - s.center().x(), dz = point.z() - s.center().z();
        return dx * dx + dz * dz <= (double) s.radius() * s.radius();
    }

    /**
     * Step ripples not older than {@link #RIPPLE_MEMORY_TICKS} at {@code gameTime}, oldest first.
     *
     * @param gameTime current level game time
     */
    public static List<StepRipple> ripples(long gameTime) {
        adopt(Minecraft.getInstance().level);
        while (!ripples.isEmpty() && gameTime - ripples.peekFirst().gameTime() > RIPPLE_MEMORY_TICKS) {
            ripples.removeFirst();
        }
        return ripples.isEmpty() ? List.of() : Collections.unmodifiableList(new ArrayList<>(ripples));
    }

    private static void adopt(ClientLevel level) {
        if (level != owner) {
            clear();
            owner = level;
        }
    }

    private static void clear() {
        owner = null;
        state = null;
        ripples.clear();
    }
}
