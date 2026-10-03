package tremor.core.behavior;

import java.util.Objects;

/**
 * The seeking of the AWAKENING stage (SPEC 8: "Событие «Пробуждение» начинается, только когда она добралась до
 * игрока"; "Не нашла никого за ~30–40 с — успокаивается до HUNTING"). At the top of its anger the entity goes for the
 * last heard sound as a HUNTING one does ({@link Brain}), faster, with its anger held at the top; the Awakening starts
 * only once its bump has reached a player (the body checks that). This is the clock of that: it starts when the entity
 * enters AWAKENING and runs out {@code seekSeconds} later, when the entity calms down to HUNTING at
 * {@link #calmAnger}, from where the anger decays as usual. New sounds do not wind it back: hiding quietly at the
 * peak outlasts it. Fed once per server tick; not thread-safe.
 */
public final class Seeking {
    /** Tolerance for timers fed with sums of tick lengths. */
    private static final double EPS = 1e-9;

    /** The entity is in AWAKENING and seeking. */
    private boolean running;
    /** Seconds since it started seeking. */
    private double seconds;

    /**
     * The entity's stage is now {@code stage}: entering AWAKENING starts the clock from 0, any other stage stops it.
     * While the stage stays AWAKENING the clock runs on.
     */
    public void stage(Stage stage) {
        Objects.requireNonNull(stage, "stage");
        if (stage != Stage.AWAKENING) {
            running = false;
            seconds = 0;
        } else if (!running) {
            restart();
        }
    }

    /** Starts the clock from 0 (a command that sets AWAKENING again starts the seeking afresh). */
    public void restart() {
        running = true;
        seconds = 0;
    }

    /** Whether the entity is seeking: in AWAKENING, and the clock has not run out. */
    public boolean running() {
        return running;
    }

    /** Seconds since the seeking started; 0 while not seeking. */
    public double seconds() {
        return seconds;
    }

    /**
     * Seconds left until the clock runs out ({@code seekSeconds} after the start), never negative; 0 while not
     * seeking.
     */
    public double secondsLeft(double seekSeconds) {
        return running ? Math.max(0, seekSeconds - seconds) : 0;
    }

    /**
     * One tick of {@code dt} seconds while seeking ({@code dt <= 0} adds no time). Returns true on the tick the clock
     * runs out, {@code seekSeconds} after the start (at once for {@code seekSeconds <= 0}): the entity calms down now;
     * the clock stops (until the entity enters AWAKENING again). False while not seeking.
     */
    public boolean tick(double dt, double seekSeconds) {
        if (!running) {
            return false;
        }
        seconds += dt > 0 ? dt : 0;
        if (seconds >= seekSeconds - EPS) {
            running = false;
            seconds = 0;
            return true;
        }
        return false;
    }

    /**
     * The anger an entity that has reached nobody calms down to: halfway between {@code huntAt} and {@code awakenAt},
     * well inside HUNTING (with the SPEC numbers 80, so it takes some more noise to be back at the top, and a while of
     * quiet to drop below HUNTING).
     */
    public static double calmAnger(BehaviorParams params) {
        return params.huntAt() + (params.awakenAt() - params.huntAt()) / 2;
    }
}
