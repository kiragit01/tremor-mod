package tremor.spawn;

/**
 * The natural spawn cooldown (SPEC 11: "кулдаун после исчезновения сущности"), kept free of the game so it is
 * unit-tested directly.
 */
final class Cooldown {
    private Cooldown() {
    }

    /**
     * Ticks left at {@code now} of a cooldown of {@code length} ticks that started at {@code since}. 0 once it has
     * run out, if it never started ({@code since} is {@link Long#MIN_VALUE}), and if {@code since} lies in the
     * future: game time never goes back, so such a time is not this world's (a copied data file) and is ignored
     * rather than blocking spawns until then.
     */
    static long remaining(long now, long since, long length) {
        if (since == Long.MIN_VALUE || since > now || length <= 0) {
            return 0;
        }
        // Negative only on overflow, for a start impossibly long ago.
        long elapsed = now - since;
        return elapsed < 0 || elapsed >= length ? 0 : length - elapsed;
    }
}
