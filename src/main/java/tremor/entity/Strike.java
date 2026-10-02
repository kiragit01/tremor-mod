package tremor.entity;

import tremor.core.behavior.ContactZone;
import tremor.core.math.Vec3;

/** How a player struck by the bump is thrown (SPEC 8 HUNTING: "отбрасывание"), kept free of the game for tests. */
final class Strike {
    /**
     * Below this horizontal share of the push direction (a unit vector) the push counts as vertical: on a wall, a
     * player above or below the bump.
     */
    static final double MIN_HORIZONTAL = 0.5;

    private Strike() {
    }

    /**
     * Velocity (blocks per tick) of a struck player: {@code speed} horizontally, along the horizontal part of
     * {@code push} ({@link ContactZone#push}, perpendicular to the normal); where that is shorter than
     * {@value #MIN_HORIZONTAL}, along the horizontal part of {@code normal} instead (off the wall); and {@code lift}
     * upward. Players walk on floors, so the throw is always made of a horizontal shove and a hop, whatever the
     * surface the bump is on. Both are scaled by {@code 1 - resistance}, as vanilla knockback is by the knockback
     * resistance attribute ({@code resistance} is clamped to 0..1, NaN counts as 0): a fully resistant player gets a
     * zero velocity.
     */
    static Vec3 velocity(Vec3 push, Vec3 normal, double speed, double lift, double resistance) {
        double scale = 1 - (resistance > 0 ? Math.min(1, resistance) : 0);
        Vec3 away = horizontal(push);
        if (away.length() < MIN_HORIZONTAL) {
            away = horizontal(normal);
        }
        away = away.normalize().scale(speed * scale);
        return new Vec3(away.x(), lift * scale, away.z());
    }

    private static Vec3 horizontal(Vec3 v) {
        return new Vec3(v.x(), 0, v.z());
    }
}
