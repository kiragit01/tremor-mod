package tremor.client.awakening;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import tremor.sound.TremorSounds;
import tremor.core.math.Vec3;
import tremor.network.TremorAwakeningPayload;
import tremor.network.TremorStepRipplePayload;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client view of the Awakenings in the current level (SPEC 9): the last state the server sent of each, and their
 * recent step ripples. A level may have several at once (an ambush for each player caught, beside one that is no
 * ambush); the effects follow one ({@link #state}): the one the local player is the target of, else the nearest. Read
 * by the effects (ground breathing and rings, the swallow hill or the quicksand, silence, heartbeat). Main thread
 * only. Everything is dropped on logout and when the client level changes.
 */
public final class ClientAwakening {
    /** Ticks the sound of the start of an Awakening takes to fade out when the player got away. */
    private static final int PULL_FADE_TICKS = 10;
    /** Step ripples older than this (game ticks) are forgotten. */
    public static final int RIPPLE_MEMORY_TICKS = 100;
    /** At most this many step ripples are kept for each Awakening (the oldest go first). */
    public static final int MAX_RIPPLES = 32;

    /** A ring wave from a step: where, when (level game time) and how strong. */
    public record StepRipple(Vec3 position, long gameTime, float strength) {
    }

    private static ClientLevel owner;
    /** The running Awakenings of this level as last sent, by id, in the order they came. */
    private static final Map<Integer, TremorAwakeningPayload> states = new LinkedHashMap<>();
    /** The recent step ripples of each, by its id. */
    private static final Map<Integer, Deque<StepRipple>> ripples = new HashMap<>();

    private ClientAwakening() {
    }

    public static void accept(TremorAwakeningPayload payload) {
        adopt(Minecraft.getInstance().level);
        if (payload.phase() == TremorAwakeningPayload.Phase.ENDED) {
            TremorAwakeningPayload was = states.remove(payload.id());
            ripples.remove(payload.id());
            if (was != null && was.phase() == TremorAwakeningPayload.Phase.BUILDUP) {
                // Got away (or called off) during the build-up: the long sound of the start fades out.
                tremor.client.sound.SoundFades.fadeOut(TremorSounds.AWAKEN.getId(), PULL_FADE_TICKS);
            }
            return;
        }
        states.put(payload.id(), payload);
    }

    public static void acceptRipple(TremorStepRipplePayload payload) {
        adopt(Minecraft.getInstance().level);
        if (!states.containsKey(payload.event())) {
            return;
        }
        Deque<StepRipple> list = ripples.computeIfAbsent(payload.event(), id -> new ArrayDeque<>());
        list.addLast(new StepRipple(payload.position(), payload.gameTime(), payload.strength()));
        while (list.size() > MAX_RIPPLES) {
            list.removeFirst();
        }
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    /**
     * The Awakening of this level the effects follow: the one the local player is the target of, else the one whose
     * centre is nearest to the player (horizontally); null if there is none.
     */
    public static TremorAwakeningPayload state() {
        adopt(Minecraft.getInstance().level);
        if (states.size() <= 1) {
            return states.isEmpty() ? null : states.values().iterator().next();
        }
        LocalPlayer player = Minecraft.getInstance().player;
        TremorAwakeningPayload nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (TremorAwakeningPayload s : states.values()) {
            if (player == null || player.getUUID().equals(s.target())) {
                return s;
            }
            double away = Math.hypot(s.center().x() - player.getX(), s.center().z() - player.getZ());
            if (away < best) {
                best = away;
                nearest = s;
            }
        }
        return nearest;
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
     * Step ripples of the Awakening the effects follow ({@link #state}) not older than {@link #RIPPLE_MEMORY_TICKS}
     * at {@code gameTime}, oldest first.
     *
     * @param gameTime current level game time
     */
    public static List<StepRipple> ripples(long gameTime) {
        TremorAwakeningPayload s = state();
        Deque<StepRipple> list = s == null ? null : ripples.get(s.id());
        if (list == null) {
            return List.of();
        }
        while (!list.isEmpty() && gameTime - list.peekFirst().gameTime() > RIPPLE_MEMORY_TICKS) {
            list.removeFirst();
        }
        return list.isEmpty() ? List.of() : Collections.unmodifiableList(new ArrayList<>(list));
    }

    private static void adopt(ClientLevel level) {
        if (level != owner) {
            clear();
            owner = level;
        }
    }

    private static void clear() {
        owner = null;
        states.clear();
        ripples.clear();
    }
}
