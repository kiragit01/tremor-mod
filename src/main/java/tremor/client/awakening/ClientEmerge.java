package tremor.client.awakening;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.event.TickEvent;
import tremor.client.ClientBlackout;
import tremor.core.shape.AwakeningParams;
import tremor.network.TremorAwakeningPayload;
import tremor.network.TremorAwakeningPayload.Phase;

/**
 * The hill a victor comes out of (SPEC 9 "Победа", the way out mirroring the way in; the Awakening's
 * {@link Phase#EMERGING EMERGING} and {@link Phase#SETTLING SETTLING} phases) as this client shows it: which one, and
 * how high it stands at a moment ({@link EmergeClock}). Everyone near sees it rise while the victor is still in the
 * hollow, stand while the victor is put into it, and settle. The victor's own client learns of it only after the move
 * back, with the screen still dark ({@link ClientBlackout}): there it stands at its full height at once, as high as the
 * hill that swallowed the victor, so the screen comes back on the view it went dark on; it settles once the screen is
 * clear again (and not before the server planned it: the server lets the victor go when it is below the eyes). A hill
 * whose Awakening ends ({@link Phase#ENDED ENDED}) before it settles (called off) settles at once, over
 * {@link AwakeningParams#releaseSeconds} as the zone's ground does; one that settles already runs to its end on this
 * client: the ground ({@link AwakeningGround}) and the rumble ({@code tremor.client.sound.AwakeningSounds}) follow it.
 * <p>
 * Learnt from {@link ClientAwakening} every client tick and whenever it is asked; dropped when another Awakening takes
 * the place of its own there, and when the client level changes. Main thread only.
 */
public final class ClientEmerge {
    private static final double TICKS_PER_SECOND = SharedConstants.TICKS_PER_SECOND;
    private static final EmergeClock clock = new EmergeClock(AwakeningGround.EMERGE_RING_TICKS,
            (int) Math.round(AwakeningGround.PARAMS.releaseSeconds() * TICKS_PER_SECOND));
    private static ClientLevel owner;
    /** The EMERGING or SETTLING state the clock is for (kept once its hill is over, so it is not learnt again). */
    private static TremorAwakeningPayload state;

    private ClientEmerge() {
    }

    /** Learns of a new hill or its settling, and lets a settling that waits start once the screen is clear. */
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        update(Minecraft.getInstance());
    }

    /** The state whose hill this client shows now (its focus is where it stands); null if none. */
    public static TremorAwakeningPayload state() {
        update(Minecraft.getInstance());
        return clock.known() ? state : null;
    }

    /** Peak of the hill at {@code gameTime} (the level's game time plus the partial tick); 0 without one. */
    public static double peak(double gameTime) {
        return clock.peak(AwakeningGround.PARAMS, gameTime);
    }

    /** How fast the peak moves at {@code gameTime}, blocks per second (below 0: settling); 0 without one. */
    public static double speed(double gameTime) {
        return clock.speed(AwakeningGround.PARAMS, gameTime, TICKS_PER_SECOND);
    }

    /** Game time the hill starts rising at on this client; NaN for the victor's (learnt risen), or without one. */
    public static double riseStart() {
        return clock.riseStart();
    }

    /**
     * Game time the hill starts to move on this client: its rise, or for the victor its settling; NaN while that is not
     * known yet, or without one.
     */
    public static double movesFrom() {
        return clock.movesFrom();
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
        boolean dark = ClientBlackout.dark();
        TremorAwakeningPayload current = ClientAwakening.state();
        boolean same = current != null && state != null && state.id() == current.id();
        if (current != null && current.phase() == Phase.EMERGING && !same) {
            state = current;
            clock.learnRise(current.phaseStart(), current.phaseTicks(), victor(mc.player, current) && dark, now);
        } else if (current != null && current.phase() == Phase.SETTLING && (!same || state.phase() != Phase.SETTLING)) {
            if (!same) {
                clock.forget();
            }
            state = current;
            clock.learnSettle(current.phaseStart(), current.phaseTicks(), victor(mc.player, current), now);
        } else if (current != null && state != null && !same) {
            // Another Awakening took the place of this one.
            state = null;
            clock.forget();
        } else if (current == null && state != null) {
            // Ended: called off before it settled, or over.
            clock.callOff(now);
        }
        clock.update(dark, now);
    }

    /** Whether the local player is the one coming out of the hill. */
    private static boolean victor(LocalPlayer player, TremorAwakeningPayload state) {
        return player != null && player.getUUID().equals(state.target());
    }
}
