package tremor.client;

/**
 * Opacity of the screen blackout around a move into or out of the hollow (SPEC 9) over time. No game classes, so it
 * can be tested without the game; {@link ClientBlackout} drives it. Not thread-safe.
 * <p>
 * The fade runs on a clock of nominal client ticks (20 per second of real time) that is advanced from the frame time,
 * so it moves smoothly from frame to frame at any frame rate. The fade itself is linear: a level runs from 0 (clear)
 * to 1 (black) at {@code 1 / fadeTicks} per tick, and the opacity drawn is that level eased in and out (smoothstep),
 * so the screen starts and stops darkening softly. A new target part-way through a fade carries on from the current
 * level at the new rate, so the screen never jumps, and every fade ends at most {@code fadeTicks} ticks of running
 * clock after it was set.
 * <p>
 * The clock holds while the game is paused, like vanilla's own fades (titles, the sleep overlay), which run on
 * client ticks that the pause stops. It also holds while a new level is loading (the "Loading terrain" screen of a
 * dimension change), so a fade back starts only once the new world can be seen; a fade to black is finished at once
 * when a level starts loading, so the loading screen is never seen at all.
 * <p>
 * Failsafe: the server lifts every blackout it starts (its longest dark spells are bounded, see
 * {@link #FAILSAFE_TICKS}), but a blackout it never lifts, by a bug or a lost event, must not leave the player in the
 * dark for good. So a blackout that has been the target for {@link #FAILSAFE_TICKS} ticks of running clock since the
 * latest {@link #set} fades back by itself over {@link #FAILSAFE_FADE_TICKS} ticks. A new {@link #set} to black
 * starts the count again, so a server that is still busy can keep the screen black by sending it again.
 */
final class BlackoutFade {
    static final double TICKS_PER_SECOND = 20.0;
    /**
     * Running clock (60 s) after which a blackout lifts by itself. The server's own limits keep a dark spell within
     * 20 s plus the copying of the terrain: the fade (up to 200 ticks, {@code hollow.fadeTicks}) or the loading of the
     * slot (given up after 200 ticks), then at most 200 ticks until the client has the terrain after the move. The
     * copying takes a few ticks at the default sizes; the margin is for a larger copy on a smaller time budget.
     */
    static final int FAILSAFE_TICKS = 1200;
    /** Length of the fade back when the failsafe lifts a blackout. */
    static final int FAILSAFE_FADE_TICKS = 20;
    private static final double TICKS_PER_NANO = TICKS_PER_SECOND / 1e9;
    private static final long FAILSAFE_NANOS = Math.round(FAILSAFE_TICKS / TICKS_PER_NANO);

    /** 0 = clear, 1 = black; linear in time while fading. */
    private double level;
    private boolean dark;
    /** Change of {@link #level} per tick; infinite for a change at once. */
    private double rate = Double.POSITIVE_INFINITY;
    /** Running clock since the latest {@link #set} in nanoseconds (exact), counted while {@link #dark} only. */
    private long darkNanos;
    /** Time of the latest {@link #update}; meaningless before the first one. */
    private long lastNanos;
    private boolean started;

    /**
     * Advances the fade to {@code nanos} (a {@link System#nanoTime()} reading, not earlier than the previous one).
     * Call every frame, and before {@link #set}, so a new fade starts from the opacity the screen has right now.
     *
     * @param paused       the game is paused: the fade holds
     * @param levelLoading a new level is loading: a fade to black is finished at once, a fade back holds
     * @return true if the failsafe lifted the blackout in this update (it fades back from the moment it was due, so
     *         the timeline does not depend on the frames)
     */
    boolean update(long nanos, boolean paused, boolean levelLoading) {
        long elapsed = started ? nanos - lastNanos : 0;
        lastNanos = nanos;
        started = true;
        if (levelLoading && dark) {
            level = 1;
            return false;
        }
        if (paused || levelLoading || elapsed <= 0) {
            return false;
        }
        if (dark && darkNanos + elapsed >= FAILSAFE_NANOS) {
            long due = FAILSAFE_NANOS - darkNanos;
            advance(due);
            set(false, FAILSAFE_FADE_TICKS);
            advance(elapsed - due);
            return true;
        }
        if (dark) {
            darkNanos += elapsed;
        }
        advance(elapsed);
        return false;
    }

    /**
     * Fades to black ({@code dark}) or back to clear over {@code fadeTicks} ticks, from wherever the fade is now;
     * {@code fadeTicks <= 0} changes at once.
     */
    void set(boolean dark, int fadeTicks) {
        this.dark = dark;
        darkNanos = 0;
        if (fadeTicks > 0) {
            rate = 1.0 / fadeTicks;
        } else {
            rate = Double.POSITIVE_INFINITY;
            level = dark ? 1 : 0;
        }
    }

    /** Clear at once and target clear; the clock keeps running. */
    void clear() {
        set(false, 0);
    }

    /** Opacity of the black over the screen this frame, 0 (none) to 1 (fully black). */
    double opacity() {
        return level * level * (3 - 2 * level);
    }

    /** Moves the level {@code nanos} (not negative) of running clock towards the target. */
    private void advance(long nanos) {
        if (nanos > 0) {
            double step = rate * (nanos * TICKS_PER_NANO);
            level = dark ? Math.min(1, level + step) : Math.max(0, level - step);
        }
    }
}
