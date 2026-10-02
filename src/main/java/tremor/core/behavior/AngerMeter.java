package tremor.core.behavior;

import java.util.Locale;
import java.util.Objects;

/**
 * The anger scale 0..{@code awakenAt} and the stage it maps to (SPEC 8), with hysteresis so the stage does not flicker
 * around a threshold. Rising: the stage is the highest one whose threshold the anger has reached. Falling: a stage is
 * kept until the anger drops below its threshold minus {@code hysteresis}.
 * <p>
 * Thresholds: DORMANT 0, ALERT {@code alertAt}, HUNTING {@code huntAt}, AWAKENING {@code awakenAt}. Falling is
 * evaluated stage by stage: once the current stage is left, the stage below it is in turn kept unless the anger is
 * also below <em>its</em> threshold minus {@code hysteresis}, and so on. A big drop can thus leave several stages at
 * once, and always ends in the same stage as a slow decay to the same anger would (with the SPEC numbers: HUNTING
 * dropped to 23 is ALERT, dropped to 21 is DORMANT).
 */
public final class AngerMeter {
    private final BehaviorParams params;
    private double anger;
    private Stage stage;

    /**
     * @param anger initial anger, clamped to [0, awakenAt] (NaN counts as 0)
     * @param stage the stage stored together with it (e.g. restored from a save); it is reconciled with the anger by
     *              the usual rules (raised if the anger reached a higher threshold, lowered if the anger is below the
     *              hysteresis band). Null derives the stage from the anger alone.
     */
    public AngerMeter(BehaviorParams params, double anger, Stage stage) {
        this.params = Objects.requireNonNull(params, "params");
        this.anger = clamp(anger);
        this.stage = stage == null ? stageFor(this.anger) : settle(stage, this.anger);
    }

    public double anger() {
        return anger;
    }

    public Stage stage() {
        return stage;
    }

    /** Anger at which {@code stage} is reached when rising: 0, alertAt, huntAt or awakenAt. */
    public double threshold(Stage stage) {
        return switch (stage) {
            case DORMANT -> 0;
            case ALERT -> params.alertAt();
            case HUNTING -> params.huntAt();
            case AWAKENING -> params.awakenAt();
        };
    }

    /** Adds (or with a negative amount removes) anger, clamped to [0, awakenAt]; returns the new stage. */
    public Stage add(double amount) {
        if (Double.isNaN(amount)) {
            return stage;
        }
        anger = clamp(anger + amount);
        stage = settle(stage, anger);
        return stage;
    }

    /** Debug: sets the anger and the stage it implies directly (no hysteresis). */
    public void set(double anger) {
        this.anger = clamp(anger);
        this.stage = stageFor(this.anger);
    }

    /** Debug: jumps to a stage, setting the anger to that stage's threshold (0 for DORMANT). */
    public void forceStage(Stage stage) {
        Objects.requireNonNull(stage, "stage");
        this.anger = threshold(stage);
        this.stage = stage;
    }

    /**
     * Decay for {@code dt} seconds: {@code decayPerSecond}, times {@code quietDecayFactor} once
     * {@code secondsSinceHeard >= quietAfterSeconds}. Returns the new stage.
     */
    public Stage tick(double dt, double secondsSinceHeard) {
        if (!(dt > 0)) {
            return stage;
        }
        double rate = params.decayPerSecond();
        if (secondsSinceHeard >= params.quietAfterSeconds()) {
            rate *= params.quietDecayFactor();
        }
        return add(-rate * dt);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "anger %.1f (%s)", anger, stage);
    }

    private double clamp(double value) {
        return value > 0 ? Math.min(value, params.awakenAt()) : 0;
    }

    /** Highest stage whose threshold {@code value} has reached. */
    private Stage stageFor(double value) {
        Stage[] stages = Stage.values();
        for (int i = stages.length - 1; i > 0; i--) {
            if (value >= threshold(stages[i])) {
                return stages[i];
            }
        }
        return Stage.DORMANT;
    }

    /** The stage after the anger became {@code value} while the stage was {@code current}. */
    private Stage settle(Stage current, double value) {
        Stage raw = stageFor(value);
        if (raw.ordinal() >= current.ordinal()) {
            return raw;
        }
        Stage s = current;
        while (s != Stage.DORMANT && value < threshold(s) - params.hysteresis()) {
            s = Stage.values()[s.ordinal() - 1];
        }
        return s;
    }
}
