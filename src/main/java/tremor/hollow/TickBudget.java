package tremor.hollow;

import java.util.function.LongSupplier;

/**
 * Server time that work spread over ticks may take in the current tick (SPEC 16: ticks stay smooth). Plain Java; the
 * clock is passed in so the scheduling can be tested.
 */
public final class TickBudget {
    private final LongSupplier clock;
    private long start;
    private long budget;

    /** @param clock a nanosecond clock such as {@code System::nanoTime} */
    public TickBudget(LongSupplier clock) {
        this.clock = clock;
    }

    /** Starts the budget of a tick: {@code nanos} from now. */
    public void start(long nanos) {
        start = clock.getAsLong();
        budget = nanos;
    }

    public boolean hasTime() {
        return clock.getAsLong() - start < budget;
    }

    /** The clock's reading now. */
    public long now() {
        return clock.getAsLong();
    }
}
