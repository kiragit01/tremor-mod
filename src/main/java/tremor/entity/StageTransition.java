package tremor.entity;

import tremor.core.behavior.Stage;

/**
 * The sound that marks a stage change (SPEC 8: "Переходы между стадиями сопровождаются звуком", 13), kept free of the
 * game so it is unit-tested directly. A rise is marked by the stage it reaches, however many it skips; any drop by a
 * sigh of the ground.
 */
enum StageTransition {
    /** The stage did not change. */
    NONE,
    /** Up to ALERT: the rock rumbles. */
    RUMBLE,
    /** Up to HUNTING: the rock cracks, over a low rumble. */
    CRACK,
    /** Up to AWAKENING. */
    AWAKEN,
    /** Down to any lower stage: the ground sighs. */
    SIGH;

    static StageTransition of(Stage from, Stage to) {
        if (from == to) {
            return NONE;
        }
        if (to.ordinal() < from.ordinal()) {
            return SIGH;
        }
        return switch (to) {
            case DORMANT -> NONE; // not reached by a rise
            case ALERT -> RUMBLE;
            case HUNTING -> CRACK;
            case AWAKENING -> AWAKEN;
        };
    }
}
