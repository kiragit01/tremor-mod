package tremor.client.awakening;

/**
 * When the hill a victor comes out of (SPEC 9 "Победа": the Awakening's EMERGING phase) rises on this client, and how
 * long it is kept. No game classes, so it can be tested without the game; {@link ClientEmerge} drives it. Not
 * thread-safe. All times are the level's game time (ticks).
 * <p>
 * The hill runs on the server's clock, from the start of the phase, unless the client learns of the phase while the
 * victor cannot see: the server starts the phase at the victory, while the victor's screen goes dark in the hollow,
 * moves the victor back to the swallow point only after that fade and lets the screen come back once the client has
 * had the terrain for a while, by when the hill would have risen and half settled unseen. Such a hill waits for the
 * screen ({@link #update}) and starts rising then, for the whole planned length of the phase. A hill is kept until it
 * has run for that length, but at least {@code minTicks} (what else belongs to it, the ring bursting out from under it,
 * runs that long), also after the server has ended the phase.
 */
final class EmergeClock {
    /**
     * A hill that has waited this long for the screen (ticks) is dropped unseen: the server lifts its blackouts long
     * before (as the client's own failsafe does after a minute).
     */
    static final long MAX_WAIT_TICKS = 1200;

    private final int minTicks;
    private boolean known;
    private long phaseStart;
    private int phaseTicks;
    private long learnedAt;
    /** Game time the hill starts rising at on this client; NaN while it waits for the screen. */
    private double start = Double.NaN;

    /** @param minTicks a hill is kept at least this long after it started rising, however short its phase */
    EmergeClock(int minTicks) {
        this.minTicks = Math.max(0, minTicks);
    }

    /**
     * Learns of an emerging that started at {@code phaseStart} for {@code phaseTicks} (0 or less: open-ended, a flat
     * hill), in place of any other.
     *
     * @param waitForScreen the client is the victor's and its screen is dark: the hill waits until it can be seen
     * @param now           game time now
     */
    void learn(long phaseStart, int phaseTicks, boolean waitForScreen, long now) {
        known = true;
        this.phaseStart = phaseStart;
        this.phaseTicks = Math.max(0, phaseTicks);
        learnedAt = now;
        start = waitForScreen ? Double.NaN : phaseStart;
    }

    /**
     * Advances to {@code now}: a hill waiting for the screen starts rising once the screen is no longer {@code dark}
     * (at the earliest when its phase started), or is dropped after {@link #MAX_WAIT_TICKS}; a hill that has run its
     * course is dropped.
     */
    void update(boolean dark, long now) {
        if (!known) {
            return;
        }
        if (Double.isNaN(start)) {
            if (!dark) {
                start = Math.max(phaseStart, now);
            } else if (now - learnedAt > MAX_WAIT_TICKS) {
                forget();
            }
        } else if (now >= start + Math.max(phaseTicks, minTicks)) {
            forget();
        }
    }

    /** Drops the hill. */
    void forget() {
        known = false;
        start = Double.NaN;
    }

    /** Whether there is a hill, rising or still waiting for the screen. */
    boolean known() {
        return known;
    }

    /** Whether the hill waits for the screen; false without a hill. */
    boolean waiting() {
        return known && Double.isNaN(start);
    }

    /** Game time the hill starts rising at on this client; NaN while it waits, or without a hill. */
    double start() {
        return start;
    }

    /** Planned length of its phase (ticks); 0 for an open-ended one. */
    int phaseTicks() {
        return phaseTicks;
    }

    /**
     * Share of the hill's phase that has passed on this client at {@code gameTime} (with the partial tick), 0..1; 0
     * while it waits for the screen, for an open-ended phase, and without a hill.
     */
    double progress(double gameTime) {
        if (!known || Double.isNaN(start) || phaseTicks <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(1, (gameTime - start) / phaseTicks));
    }
}
