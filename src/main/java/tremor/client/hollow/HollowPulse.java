package tremor.client.hollow;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import tremor.core.shape.HollowParams;
import tremor.core.shape.Ripple;
import tremor.network.TremorHollowStatePayload;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * The beats of the node of the hollow the local player is in (SPEC 9 phase 2: "пульсирующий блок-узел ... выдаёт себя
 * сердцебиением и рябью в такт пульсу"), kept by the client at the pace the server sends ({@link
 * TremorHollowStatePayload#beatTicks}, quicker as the hollow closes; {@link PulseClock}). Every beat runs a ring out
 * from the node over the ground ({@link HollowGround}) and sounds the heartbeat at the node
 * ({@code tremor.client.sound.HollowSounds}), both from here, so they keep time together.
 * <p>
 * The pulse runs on the level's game time (it stops while the game is paused or the server stands still) while
 * {@link ClientHollow} knows a node; the first beat comes soon after the client learns of it. A beat is remembered
 * for as long as its ring runs. Everything is dropped when the client level changes. Main thread only.
 */
public final class HollowPulse {
    /** Beats are remembered this long (game ticks): as long as the ring of a beat runs ({@link Ripple#active}). */
    static final int MEMORY_TICKS = (int) Math.ceil(HollowParams.defaults().ring().duration()
            * SharedConstants.TICKS_PER_SECOND) + 1;
    /** At most this many beats are remembered (the oldest go first). */
    static final int MAX_BEATS = 32;
    /**
     * Game time one client tick lets pass for the pulse at most, so that a jump of the clock (the server caught up
     * after a stall) beats once instead of in a burst.
     */
    static final int MAX_STEP_TICKS = 20;

    private static final PulseClock clock = new PulseClock();
    /** Game times of the remembered beats, oldest first. */
    private static final Deque<Long> beats = new ArrayDeque<>();
    private static ClientLevel owner;
    /** Whether the pulse runs: the client knows a node. */
    private static boolean running;
    /** Event of the hollow whose node beats. */
    private static int event;
    /** Game time the pulse was last advanced to. */
    private static long lastTime;
    /** Beats so far, counted on: tells the sounds that a beat came. */
    private static int count;

    private HollowPulse() {
    }

    /** Advances the pulse to the level's game time, and forgets the beats whose rings have run out. */
    public static void onClientTick(ClientTickEvent.Post tick) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != owner) {
            owner = level;
            running = false;
            beats.clear();
        }
        if (level == null) {
            return;
        }
        long now = level.getGameTime();
        while (!beats.isEmpty() && (now - beats.peekFirst() > MEMORY_TICKS || beats.peekFirst() > now)) {
            beats.removeFirst();
        }
        TremorHollowStatePayload state = ClientHollow.state();
        if (state == null || state.node() == null || state.beatTicks() <= 0) {
            running = false;
            return;
        }
        if (!running || state.event() != event) {
            if (state.event() != event) {
                beats.clear();
            }
            running = true;
            event = state.event();
            lastTime = now;
            clock.start(state.beatTicks());
            return;
        }
        long elapsed = Math.min(now - lastTime, MAX_STEP_TICKS);
        lastTime = now;
        if (clock.advance(elapsed, state.beatTicks()) > 0) {
            beats.addLast(now);
            count++;
            while (beats.size() > MAX_BEATS) {
                beats.removeFirst();
            }
        }
    }

    /** Game times of the node's beats whose rings may still run, oldest first. */
    public static List<Long> beats() {
        return List.copyOf(beats);
    }

    /** Beats so far: changes with every beat. */
    public static int count() {
        return count;
    }
}
