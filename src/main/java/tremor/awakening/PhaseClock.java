package tremor.awakening;

/**
 * Timing of a phase of an Awakening (SPEC 9) in level game time, as {@code TremorAwakeningPayload} carries it to the
 * clients ({@code phaseStart}, {@code phaseTicks}). No Minecraft classes, so it is unit-tested directly.
 *
 * @param start game time the phase started at
 * @param ticks planned length of the phase; 0 for an open-ended phase, which is never over
 */
public record PhaseClock(long start, int ticks) {
    public PhaseClock {
        if (ticks < 0) {
            throw new IllegalArgumentException("negative phase length " + ticks);
        }
    }

    /** Ticks since the start at {@code now}; 0 before it. */
    public long elapsed(long now) {
        return Math.max(0, now - start);
    }

    /** Ticks left at {@code now} until the planned end; 0 once it is over, and always for an open-ended phase. */
    public long remaining(long now) {
        return ticks == 0 ? 0 : Math.max(0, ticks - elapsed(now));
    }

    /** Whether the planned length has passed at {@code now}; never for an open-ended phase. */
    public boolean over(long now) {
        return ticks > 0 && elapsed(now) >= ticks;
    }
}
