package tremor.core.behavior;

/**
 * When the entity leaves (SPEC 11: "Сущность уходит, если игрок далеко дольше X минут или если долго ничего не
 * происходит"). Two timers fed once per server tick: {@link #farSeconds()}, how long no player has been near, and
 * {@link #quietSeconds()}, how long nothing eventful has happened (eventful: a heard sound, a stage above DORMANT;
 * the caller decides). Not thread-safe.
 */
public final class DespawnClock {
    /** Tolerance for timers fed with sums of tick lengths. */
    private static final double EPS = 1e-9;

    private double farSeconds;
    private double quietSeconds;

    public DespawnClock() {
    }

    /** Restores saved timers (negative or NaN values count as 0). */
    public DespawnClock(double farSeconds, double quietSeconds) {
        this.farSeconds = farSeconds > 0 ? farSeconds : 0;
        this.quietSeconds = quietSeconds > 0 ? quietSeconds : 0;
    }

    /**
     * One tick of {@code dt} seconds ({@code dt <= 0} adds no time but still resets): a player near resets
     * {@link #farSeconds()}, otherwise it grows by {@code dt}; an eventful tick resets {@link #quietSeconds()},
     * otherwise it grows by {@code dt}.
     */
    public void tick(double dt, boolean playerNear, boolean eventful) {
        double step = dt > 0 ? dt : 0;
        farSeconds = playerNear ? 0 : farSeconds + step;
        quietSeconds = eventful ? 0 : quietSeconds + step;
    }

    /** Seconds since a player was last near (0 while one is). */
    public double farSeconds() {
        return farSeconds;
    }

    /** Seconds since the last eventful tick (0 on one). */
    public double quietSeconds() {
        return quietSeconds;
    }

    /**
     * True once no player has been near for {@code maxFarSeconds}, or nothing has happened for
     * {@code maxQuietSeconds} (an infinite limit never triggers).
     */
    public boolean shouldLeave(double maxFarSeconds, double maxQuietSeconds) {
        return farSeconds >= maxFarSeconds - EPS || quietSeconds >= maxQuietSeconds - EPS;
    }

    /** Both timers back to 0 (a fresh spawn). */
    public void reset() {
        farSeconds = 0;
        quietSeconds = 0;
    }
}
