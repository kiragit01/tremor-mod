package tremor.core.behavior;

import tremor.core.math.Vec3;

/**
 * What the {@link Brain} wants the body to do this tick.
 *
 * @param action what to do
 * @param target where to go (GO), null otherwise
 * @param facing what to turn the front of the bump toward (FREEZE), null otherwise
 * @param reason short tag for debugging: wander, investigate, freeze, creep, hunt, search, ...
 */
public record Decision(Action action, Vec3 target, Vec3 facing, String reason) {
    public enum Action {
        /** Keep doing whatever it does (follow the current route to its end, or stand). */
        STAY,
        /** Go to {@code target} (the body replans only if it differs from the current goal). */
        GO,
        /** Stop right here and turn toward {@code facing}. */
        FREEZE
    }

    public static Decision stay(String reason) {
        return new Decision(Action.STAY, null, null, reason);
    }

    public static Decision go(Vec3 target, String reason) {
        return new Decision(Action.GO, target, null, reason);
    }

    public static Decision freeze(Vec3 facing, String reason) {
        return new Decision(Action.FREEZE, null, facing, reason);
    }
}
