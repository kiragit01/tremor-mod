package tremor.client.sound;

import tremor.core.behavior.Stage;
import tremor.core.shape.BumpShape;

/**
 * Volume, pitch and hearing range of the rustle of the moving bump (SPEC 13: "Громкость и тон шороха зависят от
 * скорости и стадии"), as plain functions; {@link RustleSound} plays it. No game classes.
 */
final class RustleTone {
    /** Speed (blocks per second) from which the rustle is at full volume; below, the volume falls linearly to 0. */
    static final double FULL_SPEED = 4.0;
    /**
     * Height of the bump (blocks) from which the rustle is at full volume; below, it fades linearly to silence at
     * {@link BumpShape#RENDER_THRESHOLD}, where the bump is no longer drawn: a diving bump goes quiet.
     */
    static final double FULL_AMPLITUDE = 0.3;
    /** Pitch added at {@link #FULL_SPEED}, linearly from 0 at rest. */
    static final double SPEED_PITCH = 0.15;
    /** Time constant (seconds) with which the played volume and pitch follow their targets, so they never jump. */
    static final double EASING_SECONDS = 0.2;
    /**
     * How much further (blocks) than the hearing range a playing rustle goes on, so a listener at the edge does not
     * start and stop it every tick ({@link #inHearing}).
     */
    static final double HEARING_MARGIN = 4.0;

    private RustleTone() {
    }

    /** Volume factor of the stage: a lazy wanderer is quiet, an alerted one creeps, a hunter is loud. */
    static double stageVolume(Stage stage) {
        return switch (stage) {
            case DORMANT -> 0.5;
            case ALERT -> 0.35;
            case HUNTING, AWAKENING -> 1.0;
        };
    }

    /** Base pitch of the stage: the angrier, the higher. */
    static double stagePitch(Stage stage) {
        return switch (stage) {
            case DORMANT -> 0.7;
            case ALERT -> 0.8;
            case HUNTING -> 1.0;
            case AWAKENING -> 1.1;
        };
    }

    /**
     * Target volume: {@code base · stageVolume · share(speed / FULL_SPEED) · share(height above the threshold /
     * (FULL_AMPLITUDE - threshold))}, with {@code share} clamping to {@code [0, 1]}.
     *
     * @param base      the configured volume, as far as it can count ({@link #base})
     * @param speed     blocks per second
     * @param amplitude current height of the bump, blocks
     */
    static double volume(double base, Stage stage, double speed, double amplitude) {
        double height = (amplitude - BumpShape.RENDER_THRESHOLD) / (FULL_AMPLITUDE - BumpShape.RENDER_THRESHOLD);
        return base * stageVolume(stage) * share(speed / FULL_SPEED) * share(height);
    }

    /**
     * The configured volume as far as it can count: the sound engine clamps a sound's volume times the volume of its
     * category (Hostile Creatures) to 1, so beyond that a louder setting would only raise the quieter stages and
     * flatten the differences between the stages. Capped at {@code 1/categoryVolume}, the loudest stage reaches full
     * volume at most and every stage keeps its share of it; a muted category ({@code <= 0}) leaves it as it is.
     */
    static double base(double configured, double categoryVolume) {
        return categoryVolume > 0 ? Math.min(configured, 1 / categoryVolume) : configured;
    }

    /**
     * Whether the rustle is to play for a listener {@code distance} blocks away: a new one starts within
     * {@code range}, the attenuation distance of its sound (it is silent beyond anyway), and a {@code playing} one goes
     * on up to {@link #HEARING_MARGIN} further. A listener who comes back into range so hears a new sound start, and
     * sees its subtitle, which shows a sound only when it starts and only within that distance of where it started.
     */
    static boolean inHearing(double distance, double range, boolean playing) {
        return playing ? distance <= range + HEARING_MARGIN : distance < range;
    }

    /** Target pitch: the stage's, plus up to {@link #SPEED_PITCH} with the speed. */
    static double pitch(Stage stage, double speed) {
        return stagePitch(stage) + SPEED_PITCH * share(speed / FULL_SPEED);
    }

    /** {@code current} eased toward {@code target} over {@code dtSeconds} (exponentially, {@link #EASING_SECONDS}). */
    static double approach(double current, double target, double dtSeconds) {
        return target + (current - target) * Math.exp(-dtSeconds / EASING_SECONDS);
    }

    /** {@code x} clamped to {@code [0, 1]}; NaN counts as 0. */
    private static double share(double x) {
        return x > 0 ? Math.min(x, 1) : 0;
    }
}
