package tremor.hollow.level;

/**
 * How far the hollow has closed (SPEC 9, "Смыкание": "края изнанки постепенно закрываются... Шум игрока ускоряет
 * смыкание (слух работает и здесь) — искать узел выгоднее крадучись. Приманки (брошенный предмет) отвлекают часть
 * смыкания"), one game tick at a time. Plain Java.
 * <p>
 * After a grace period the closing radius shrinks from {@code startRadius} (the edge) towards {@code minRadius} at
 * {@code baseSpeed} plus {@code noiseFactor} times the noise the player made. The noise is the loudness the ground got
 * from the player's steps, landings, digging... per second, smoothed: each vibration adds its loudness divided by
 * {@code noiseSeconds}, and the sum fades away with that time constant, so steady walking with steps of loudness
 * {@code L} every {@code T} seconds settles at {@code L / T}. A sneaking player makes no steps. A lure (an item or a
 * projectile landing at least {@code lureMinDistance} blocks from the player) pauses the closing for
 * {@code lurePauseTicks}, at most once every {@code lureCooldownTicks}. The pulse of the node quickens from
 * {@code beatSlow} to {@code beatFast} ticks per beat as the hollow closes.
 */
public final class ClosingSchedule {
    /**
     * @param graceTicks        ticks before the closing starts
     * @param startRadius       radius at the start: the edge of the hollow (blocks)
     * @param minRadius         the radius never goes below this (blocks)
     * @param baseSpeed         blocks per second without noise
     * @param noiseFactor       blocks per second added per unit of noise (loudness per second)
     * @param noiseSeconds      time constant of the noise (seconds)
     * @param lurePauseTicks    a lure stops the closing for this long
     * @param lureCooldownTicks a lure after another one within this many ticks does nothing
     * @param lureMinDistance   a landing closer to the player than this (blocks) is no lure
     * @param beatSlow          ticks between beats of the node at the start
     * @param beatFast          ticks between beats once the hollow closed to {@code minRadius}
     */
    public record Params(int graceTicks, double startRadius, double minRadius, double baseSpeed, double noiseFactor,
                         double noiseSeconds, int lurePauseTicks, int lureCooldownTicks, double lureMinDistance,
                         int beatSlow, int beatFast) {
    }

    private final Params params;
    private final double minRadius;
    private final double decay;
    private double radius;
    private double noise;
    private long tick;
    private long pausedUntil;
    private long lastLure = Long.MIN_VALUE / 2;
    private int lures;

    public ClosingSchedule(Params params) {
        this.params = params;
        radius = params.startRadius();
        minRadius = Math.min(params.minRadius(), params.startRadius());
        decay = Math.exp(-1 / (Math.max(params.noiseSeconds(), 0.05) * 20));
    }

    /** One game tick. */
    public void tick() {
        tick++;
        noise *= decay;
        if (tick <= params.graceTicks() || tick <= pausedUntil) {
            return;
        }
        radius = Math.max(minRadius, radius - speed() / 20);
    }

    /** The player made a vibration of {@code loudness} (as it enters the ground: times the footing). */
    public void noise(double loudness) {
        if (loudness > 0) {
            noise += loudness / Math.max(params.noiseSeconds(), 0.05);
        }
    }

    /**
     * Something landed {@code distance} blocks from the player; true if it was a lure that paused the closing (far
     * enough, and the last lure is long enough ago).
     */
    public boolean lure(double distance) {
        if (distance < params.lureMinDistance() || tick - lastLure < params.lureCooldownTicks()) {
            return false;
        }
        lastLure = tick;
        lures++;
        pausedUntil = Math.max(pausedUntil, tick + params.lurePauseTicks());
        return true;
    }

    /** The current closing radius (blocks from the centre, horizontally). */
    public double radius() {
        return radius;
    }

    /** The closing speed now, blocks per second (also during the grace and pauses, when it does not apply). */
    public double speed() {
        return params.baseSpeed() + params.noiseFactor() * noise;
    }

    /** The smoothed noise, loudness per second. */
    public double noiseLevel() {
        return noise;
    }

    /** Ticks left of the grace period. */
    public long graceLeft() {
        return Math.max(0, params.graceTicks() - tick);
    }

    /** Ticks left of a lure's pause (0 if not paused). */
    public long pauseLeft() {
        return Math.max(0, pausedUntil - tick);
    }

    /** Lures that paused the closing so far. */
    public int lures() {
        return lures;
    }

    /** How far the closing got, 0 (at the edge) .. 1 (at the minimum radius). */
    public double closed() {
        double span = params.startRadius() - minRadius;
        return span <= 0 ? 1 : (params.startRadius() - radius) / span;
    }

    /** Ticks between two beats of the node now. */
    public int beatTicks() {
        return (int) Math.round(params.beatSlow() + (params.beatFast() - params.beatSlow()) * closed());
    }
}
