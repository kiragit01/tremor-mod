package tremor.entity;

import tremor.network.TremorStatePayload;

/**
 * The instances of the small creatures the clients draw as bumps of their own (the crawlers, the listeners and their
 * signals): counted down from {@link TremorStatePayload#CRAWLER_BASE}, apart from the tremors'.
 */
final class MinorIds {
    private static int next;
    private static int nextPulse;

    private MinorIds() {
    }

    static int next() {
        int instance = TremorStatePayload.CRAWLER_BASE - next;
        next = (next + 1) % (TremorStatePayload.CRAWLER_RANGE / 2);
        return instance;
    }

    /** An instance for a resonance arrow's pulse ({@link TremorStatePayload#pulse}). */
    static int nextPulse() {
        int instance = TremorStatePayload.PULSE_BASE - nextPulse;
        nextPulse = (nextPulse + 1) % (TremorStatePayload.CRAWLER_RANGE / 2);
        return instance;
    }
}
