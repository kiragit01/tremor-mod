package tremor.client.awakening;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import tremor.client.ClientBlackout;
import tremor.network.TremorAwakeningPayload;
import tremor.network.TremorAwakeningPayload.Phase;

/**
 * The hill a victor comes out of (SPEC 9 "Победа": "возвращается в реальный мир через холм, вылезает из него"; the
 * Awakening's {@link Phase#EMERGING EMERGING} phase) as this client shows it: which one, and when it rises here
 * ({@link EmergeClock}). Everyone near sees it rise when the phase starts, the victor still in the hollow, and the
 * victor appear in it. The victor's own client learns of it only after the move back, with the screen still dark
 * ({@link ClientBlackout}): there the hill waits until the screen is at most {@link #SEEN_OPACITY} black and rises
 * then, around the victor, for the whole planned length of the phase. Either way it runs to its end on this client,
 * also after the server has ended the phase ({@link Phase#ENDED ENDED}): the ground ({@link AwakeningGround}) and the
 * rumble ({@code tremor.client.sound.AwakeningSounds}) follow it.
 * <p>
 * Learnt from {@link ClientAwakening} every client tick and whenever it is asked; dropped when another Awakening takes
 * the place of its own there, and when the client level changes. Main thread only.
 */
public final class ClientEmerge {
    /** The screen counts as seen again once its black is at most this opaque: half way through the fade back. */
    static final double SEEN_OPACITY = 0.5;

    private static final EmergeClock clock = new EmergeClock(AwakeningGround.EMERGE_RING_TICKS);
    private static ClientLevel owner;
    /** The EMERGING state the clock is for (kept once its hill is over, so it is not learnt again); null if none. */
    private static TremorAwakeningPayload state;

    private ClientEmerge() {
    }

    /** Learns of a new emerging, and lets a waiting hill rise once the screen has come back. */
    public static void onClientTick(ClientTickEvent.Post event) {
        update(Minecraft.getInstance());
    }

    /** The EMERGING state whose hill this client shows now, rising or waiting for the screen; null if none. */
    public static TremorAwakeningPayload state() {
        update(Minecraft.getInstance());
        return clock.known() ? state : null;
    }

    /** Whether the hill waits for the screen to come back (it has not started to rise); false without one. */
    public static boolean waiting() {
        return clock.waiting();
    }

    /** Game time the hill starts rising at on this client; NaN while it waits, or without one. */
    public static double start() {
        return clock.start();
    }

    /**
     * Share of the hill's phase that has passed on this client at {@code gameTime} (the level's game time plus the
     * partial tick), 0..1; 0 while it waits, for an open-ended phase, and without one.
     */
    public static double progress(double gameTime) {
        return clock.progress(gameTime);
    }

    private static void update(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level != owner) {
            owner = level;
            state = null;
            clock.forget();
        }
        if (level == null) {
            return;
        }
        long now = level.getGameTime();
        boolean dark = dark(mc);
        TremorAwakeningPayload current = ClientAwakening.state();
        if (current != null && (state == null || state.id() != current.id())) {
            if (current.phase() == Phase.EMERGING) {
                state = current;
                clock.learn(current.phaseStart(), current.phaseTicks(), victor(mc.player, current) && dark, now);
            } else if (state != null) {
                // Another Awakening took the place of this one.
                state = null;
                clock.forget();
            }
        }
        clock.update(dark, now);
    }

    /** Whether the local player is the one coming out of the hill. */
    private static boolean victor(LocalPlayer player, TremorAwakeningPayload state) {
        return player != null && player.getUUID().equals(state.target());
    }

    /** Whether the player cannot see the world yet: the screen too black, or a new level loading. */
    private static boolean dark(Minecraft mc) {
        return ClientBlackout.opacity() > SEEN_OPACITY || mc.screen instanceof ReceivingLevelScreen;
    }
}
