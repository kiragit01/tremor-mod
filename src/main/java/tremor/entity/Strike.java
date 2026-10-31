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

    /** A player's fall per tick: the speed down grows by this... */
    static final double GRAVITY = 0.08;
    /** ...and the speed is kept this share of in the air, down and across (as the game moves a player). */
    static final double AIR_DRAG_Y = 0.98;
    static final double AIR_DRAG = 0.91;

    /**
     * Velocity (blocks per tick) that throws a player at {@code from} so they land at {@code to} (feet, height: the
     * ground there; reached on the way down), with {@code lift} upward: as the game moves a player in the air, with
     * nothing pressed; at least fast enough to be {@code clear} blocks across by the time they are back down at their
     * own height (off the top they stand on: then they land farther). The horizontal speed is at most
     * {@code maxSpeed} (then the player lands short).
     */
    static Vec3 aimed(Vec3 from, Vec3 to, double lift, double maxSpeed, double clear) {
        double dx = to.x() - from.x(), dz = to.z() - from.z(), distance = Math.hypot(dx, dz);
        if (distance < 1e-6) {
            return new Vec3(0, lift, 0);
        }
        double speed = Math.min(maxSpeed, Math.max(distance / drift(flightTicks(from.y(), to.y(), lift)),
                clear / drift(flightTicks(from.y(), from.y(), lift))));
        return new Vec3(dx / distance * speed, lift, dz / distance * speed);
    }

    /** Ticks until a player thrown up at {@code lift} from {@code fromY} comes down to {@code toY} (at least 1). */
    static int flightTicks(double fromY, double toY, double lift) {
        double y = fromY, vy = lift;
        int ticks = 0;
        while (ticks < 400) {
            y += vy;
            vy = (vy - GRAVITY) * AIR_DRAG_Y;
            ticks++;
            if (y <= toY && vy < 0) {
                break;
            }
        }
        return ticks;
    }

    /** How far a player goes across in {@code ticks}, per block per tick of the speed thrown with. */
    static double drift(int ticks) {
        return (1 - Math.pow(AIR_DRAG, ticks)) / (1 - AIR_DRAG);
    }

    private static Vec3 horizontal(Vec3 v) {
        return new Vec3(v.x(), 0, v.z());
    }
}
