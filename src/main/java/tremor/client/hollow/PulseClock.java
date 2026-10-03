package tremor.client.hollow;

/**
 * The beat of the node as the client keeps it (SPEC 9 phase 2: the node "выдаёт себя сердцебиением и рябью в такт
 * пульсу"): a phase running from one beat to the next at the pace the server gives ({@code beatTicks}, quicker as the
 * hollow closes). A change of pace changes how soon the next beat comes smoothly, instead of restarting the wait.
 * Plain Java, no game classes.
 */
final class PulseClock {
    /** Ticks from the start of the pulse (the client learns of the node) to its first beat, at most. */
    static final int FIRST_BEAT_TICKS = 10;
    /** Slack for the rounding of the phase, so that a beat due on a whole tick comes on that tick. */
    private static final double EPSILON = 1e-9;

    /** Share of the way from the last beat to the next, {@code [0, 1)}. */
    private double phase;

    /**
     * Starts over: the first beat comes after {@link #FIRST_BEAT_TICKS} at the pace of {@code beatTicks} (at least
     * 1), or after a whole beat if that is shorter.
     */
    void start(int beatTicks) {
        phase = Math.max(0, 1 - (double) FIRST_BEAT_TICKS / Math.max(1, beatTicks));
    }

    /**
     * Lets {@code elapsedTicks} of game time pass at {@code beatTicks} per beat (at least 1).
     *
     * @return how many beats fell into that time; 0 if no time passed (or it went back)
     */
    int advance(long elapsedTicks, int beatTicks) {
        if (elapsedTicks <= 0) {
            return 0;
        }
        phase += (double) elapsedTicks / Math.max(1, beatTicks);
        int beats = (int) Math.floor(phase + EPSILON);
        phase = Math.max(0, phase - beats);
        return beats;
    }

    /** Share of the way from the last beat to the next, {@code [0, 1)}. */
    double phase() {
        return phase;
    }
}
