package tremor.entity;

import tremor.core.shape.RippleParams;
import tremor.network.TremorStatePayload;

/**
 * When the ground ripple of an ALERT freeze starts (SPEC 8: "по земле вокруг идёт мелкая рябь"), kept free of the game
 * for tests. The client keeps one ripple, the latest start the state packet carries, and draws it for
 * {@link RippleParams#duration()} seconds ({@link #RUN_TICKS} ticks). A freeze starts a ripple only once the last one
 * has run out, so every ripple runs its rings all the way out: under steady noise (a walking player orders a freeze
 * at every step) the ground shows one ripple after another, not one restarted at every step, whose rings would never
 * get past the bump. None starts while the entity dives: it is hidden under the skin then (SPEC 5.4), and so must be
 * the place it is at. Server thread only.
 */
final class RippleTimer {
    /** How long a ripple runs, in ticks: as long as the client draws it. */
    static final long RUN_TICKS = Math.round(RippleParams.defaults().duration() / TremorRuntime.TICK_SECONDS);
    /** {@link #start} when there was no ripple. */
    private static final long NEVER = Long.MIN_VALUE;

    /** Game time the latest ripple started, or {@link #NEVER}. */
    private long start = NEVER;

    /**
     * A freeze at game time {@code now}: starts a ripple unless one is running or the entity dives.
     *
     * @return whether a ripple started
     */
    boolean freeze(long now, boolean diving) {
        if (diving || running(now)) {
            return false;
        }
        start = now;
        return true;
    }

    /** Whether the latest ripple is still running at {@code now}. */
    boolean running(long now) {
        return start != NEVER && now >= start && now - start < RUN_TICKS;
    }

    /**
     * Ticks since the latest ripple started, as the state packet carries it ({@link TremorStatePayload#rippleAge}):
     * {@link TremorStatePayload#NO_RIPPLE} if none started (or it starts later, or it is that old or older).
     */
    int age(long now) {
        if (start == NEVER || now < start || now - start >= TremorStatePayload.NO_RIPPLE) {
            return TremorStatePayload.NO_RIPPLE;
        }
        return (int) (now - start);
    }
}
