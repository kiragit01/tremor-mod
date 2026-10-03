package tremor.client.awakening;

import tremor.core.shape.AwakeningParams;
import tremor.core.shape.AwakeningShape;

/**
 * How high the hill a victor comes out of (SPEC 9 "Победа": the Awakening's EMERGING and SETTLING phases) stands on
 * this client at a moment, and how long it is kept. No game classes, so it can be tested without the game;
 * {@link ClientEmerge} drives it. Not thread-safe. All times are the level's game time (ticks).
 * <p>
 * The way out mirrors the way in. The server starts the hill at the victory, while the victor's screen goes dark in
 * the hollow: it rises over the rise of EMERGING ({@link AwakeningShape#emergeRise}) and stands, as high as the hill
 * that swallowed the victor. The victor is moved into it in the dark, and its client learns of it only then: there the
 * hill is learnt at its full height ({@link #learnRise} with {@code risen}), so the screen comes back on the unreal
 * blocks all around, the view it went dark on. Once the victor is out of the hollow the server sends SETTLING
 * ({@link #learnSettle}), together with the screen coming back: the hill stands until the planned start of its settling
 * and settles then ({@link AwakeningShape#emergeSettle}); on the victor's client not before the screen is clear again
 * ({@link #update}), so the victor sees it stand, then sink. Everybody else sees it rise, stand while the victor is
 * put into it, and settle.
 * <p>
 * A hill whose Awakening is called off before it settles settles at once ({@link #callOff}). A hill is kept until it
 * has settled and the ring that bursts out from under it as it rises has run ({@code ringTicks} from the start of its
 * rise), also after the server has ended the phase. One that stands or waits for the screen for
 * {@link #MAX_WAIT_TICKS} settles by itself.
 */
final class EmergeClock {
    /**
     * A hill that has stood this long (ticks) without its settling known, or that has waited this long for the screen,
     * settles by itself: the server lifts its blackouts and settles its hills long before (the client's own failsafe
     * lifts a blackout after a minute).
     */
    static final long MAX_WAIT_TICKS = 1200;

    private final int ringTicks;
    private final int callOffTicks;
    private boolean known;
    /** Game time the rise starts at; NaN for a hill learnt risen (it is never seen rising). */
    private double riseStart = Double.NaN;
    private int riseTicks;
    /** Game time the hill was learnt at, for {@link #MAX_WAIT_TICKS}. */
    private long learnedAt;
    /** Whether the settling is known. */
    private boolean settleKnown;
    /** The planned start of the settling (the server's). */
    private long settlePlanned;
    private int settleTicks;
    /** Whether the settling waits for the screen to be clear (the victor's client). */
    private boolean waitForScreen;
    /** Game time the settling starts at on this client; NaN while it is unknown or waits for the screen. */
    private double settleStart = Double.NaN;

    /**
     * @param ringTicks    a hill is kept at least this long after its rise started (the ring bursting out from under
     *                     it runs that long)
     * @param callOffTicks a hill whose Awakening is called off before it settles ({@link #callOff}) settles over this
     */
    EmergeClock(int ringTicks, int callOffTicks) {
        this.ringTicks = Math.max(0, ringTicks);
        this.callOffTicks = Math.max(1, callOffTicks);
    }

    /**
     * Learns of a hill that rises from {@code riseStart} over {@code riseTicks} (0 or less: it stands at once) and
     * stands, in place of any other.
     *
     * @param risen the client is the victor's and its screen is dark: the hill is at its full height from now on
     * @param now   game time now
     */
    void learnRise(long riseStart, int riseTicks, boolean risen, long now) {
        forget();
        known = true;
        this.riseStart = risen || riseTicks <= 0 ? Double.NaN : riseStart;
        this.riseTicks = Math.max(0, riseTicks);
        learnedAt = now;
    }

    /**
     * Learns that the hill settles from {@code settleStart} over {@code settleTicks}; it stands until then. A hill not
     * known yet (this client came near, or joined, while it stood) is learnt at its full height.
     *
     * @param waitForScreen the client is the victor's: not before its screen is clear again
     * @param now           game time now
     */
    void learnSettle(long settleStart, int settleTicks, boolean waitForScreen, long now) {
        if (!known) {
            learnRise(now, 0, true, now);
        }
        settleKnown = true;
        settlePlanned = settleStart;
        this.settleTicks = Math.max(1, settleTicks);
        this.waitForScreen = waitForScreen;
        this.settleStart = waitForScreen ? Double.NaN : settleStart;
        learnedAt = now;
    }

    /**
     * The Awakening is gone before the hill settled (called off, or the server is gone): it settles from {@code now}
     * over {@code callOffTicks}. A hill that settles already goes on as it does.
     */
    void callOff(long now) {
        if (known && !settleKnown) {
            learnSettle(now, callOffTicks, false, now);
        }
    }

    /**
     * Advances to {@code now}: a settling that waits for the screen starts once the screen is no longer {@code dark}
     * (at the earliest when planned); a hill that stood or waited too long settles; a hill that has settled (and whose
     * ring has run) is dropped.
     */
    void update(boolean dark, long now) {
        if (!known) {
            return;
        }
        if (!settleKnown) {
            if (now - learnedAt > MAX_WAIT_TICKS) {
                callOff(now);
            }
            return;
        }
        if (Double.isNaN(settleStart)) {
            if (!dark || now - learnedAt > MAX_WAIT_TICKS) {
                settleStart = Math.max(settlePlanned, now);
            }
            return;
        }
        boolean ringOver = Double.isNaN(riseStart) || now >= riseStart + ringTicks;
        if (now >= settleStart + settleTicks && ringOver) {
            forget();
        }
    }

    /** Drops the hill. */
    void forget() {
        known = false;
        riseStart = Double.NaN;
        settleKnown = false;
        waitForScreen = false;
        settleStart = Double.NaN;
    }

    /** Whether there is a hill, rising, standing or settling. */
    boolean known() {
        return known;
    }

    /** Whether the settling is known and waits for the screen; false without a hill. */
    boolean waiting() {
        return known && settleKnown && Double.isNaN(settleStart);
    }

    /** Game time the hill starts rising at; NaN for a hill learnt risen, or without one. */
    double riseStart() {
        return riseStart;
    }

    /** Game time the hill starts settling at on this client; NaN while that is unknown or waits, or without one. */
    double settleStart() {
        return settleStart;
    }

    /**
     * Game time the hill starts to move on this client: its rise, for a client that sees it rise, else its settling
     * (the victor's); NaN while neither is known, or without a hill.
     */
    double movesFrom() {
        return Double.isNaN(riseStart) ? settleStart : riseStart;
    }

    /**
     * Peak of the hill at {@code gameTime} (with the partial tick): rising ({@link AwakeningShape#emergeRise}), standing
     * at {@link AwakeningParams#hillHeight}, or settling ({@link AwakeningShape#emergeSettle}); 0 without a hill.
     */
    double peak(AwakeningParams p, double gameTime) {
        if (!known) {
            return 0;
        }
        if (!Double.isNaN(settleStart) && gameTime >= settleStart) {
            return settleShare(p) * AwakeningShape.emergeSettle(p, (gameTime - settleStart) / settleTicks);
        }
        return risen(p, gameTime);
    }

    /**
     * How fast the peak moves at {@code gameTime}, blocks per second ({@code ticksPerSecond} game ticks to the
     * second): above 0 while the hill rises, below 0 while it settles, 0 while it stands and without a hill.
     */
    double speed(AwakeningParams p, double gameTime, double ticksPerSecond) {
        if (!known) {
            return 0;
        }
        if (!Double.isNaN(settleStart) && gameTime >= settleStart) {
            return settleShare(p) * AwakeningShape.emergeSettleRate(p, (gameTime - settleStart) / settleTicks)
                    * ticksPerSecond / settleTicks;
        }
        if (Double.isNaN(riseStart)) {
            return 0;
        }
        return AwakeningShape.emergeRiseRate(p, (gameTime - riseStart) / riseTicks) * ticksPerSecond / riseTicks;
    }

    /** Peak of the hill risen (or rising) to {@code gameTime}, before it settles. */
    private double risen(AwakeningParams p, double gameTime) {
        if (Double.isNaN(riseStart)) {
            return p.hillHeight();
        }
        return AwakeningShape.emergeRise(p, (gameTime - riseStart) / riseTicks);
    }

    /**
     * Share of its full height the hill settles from: 1 once it has risen (always, unless it is called off while it
     * still rises: then it settles from where it got to, without a jump).
     */
    private double settleShare(AwakeningParams p) {
        return p.hillHeight() > 0 ? risen(p, settleStart) / p.hillHeight() : 0;
    }
}
