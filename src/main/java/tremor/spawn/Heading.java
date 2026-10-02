package tremor.spawn;

import java.util.Locale;

import tremor.core.behavior.SurfacePicker;
import tremor.core.math.Vec3;

/**
 * Which way a player goes, for the route strip of {@link SurfacePicker#spawnPoint} (SPEC 11:
 * "вблизи его маршрута"); kept free of the game so it is unit-tested directly. The server knows a walking player's
 * motion only from the client's move packets (its {@code deltaMovement} stays about zero), so the first that is
 * available of:
 * <ol>
 *   <li>{@link Source#MOVEMENT}: the horizontal part of the motion of the last move packet
 *   ({@code ServerPlayer#getKnownMovement}), from {@value #MIN_MOVEMENT} blocks per tick on;</li>
 *   <li>{@link Source#DISPLACEMENT}: the horizontal displacement since the player's last spawn check, from
 *   {@value #MIN_DISPLACEMENT} blocks on;</li>
 *   <li>{@link Source#FACING}: the horizontal direction the player faces. A standing player most likely goes on
 *   that way, and the strip there lies in view.</li>
 * </ol>
 *
 * @param direction horizontal unit vector, null for {@link Source#NONE}
 * @param source    where it comes from
 */
record Heading(Vec3 direction, Source source) {
    /** Slowest motion per tick taken as walking (sneaking is about 0.065). */
    static final double MIN_MOVEMENT = 0.01;
    /** Shortest displacement since the last check taken as a route. */
    static final double MIN_DISPLACEMENT = 2.0;
    /** Shortest horizontal part of the facing vector that still gives a direction. */
    static final double MIN_FACING = 1e-6;

    enum Source {
        MOVEMENT, DISPLACEMENT, FACING, NONE;

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The heading from the player's motion of the last move packet, displacement since the last check and facing
     * direction; each may be null (unknown).
     */
    static Heading of(Vec3 movement, Vec3 displacement, Vec3 facing) {
        Vec3 direction = horizontal(movement, MIN_MOVEMENT);
        if (direction != null) {
            return new Heading(direction, Source.MOVEMENT);
        }
        direction = horizontal(displacement, MIN_DISPLACEMENT);
        if (direction != null) {
            return new Heading(direction, Source.DISPLACEMENT);
        }
        direction = horizontal(facing, MIN_FACING);
        return direction != null ? new Heading(direction, Source.FACING) : new Heading(null, Source.NONE);
    }

    /**
     * Whether a spawn candidate with voxel centre {@code p} lies near the way ahead of a player at {@code feet}
     * ({@link SurfacePicker#nearRoute} along this direction); never without a direction.
     */
    boolean passes(Vec3 feet, Vec3 p, double halfWidth) {
        return direction != null && SurfacePicker.nearRoute(feet, direction, p, halfWidth);
    }

    /** The horizontal part of {@code v} as a unit vector, if it is at least {@code min} long; else null. */
    private static Vec3 horizontal(Vec3 v, double min) {
        if (v == null) {
            return null;
        }
        double length = Math.hypot(v.x(), v.z());
        return length >= min ? new Vec3(v.x() / length, 0, v.z() / length) : null;
    }
}
