package tremor.core.shape;

import java.util.Objects;

import tremor.core.math.Vec3;

/**
 * Local frame of the bump: its centre {@code c}, the unit surface normal {@code n} and the unit direction of motion
 * {@code f} in the tangent plane. The constructor normalizes and orthogonalizes, so a stored frame is always
 * orthonormal ({@code |n| = |f| = 1}, {@code n·f = 0}).
 *
 * @param center  bump centre in world coordinates
 * @param normal  surface normal; any non-zero length
 * @param forward direction of motion; projected onto the tangent plane. If that projection is degenerate (zero, or
 *                parallel to {@code normal}), {@code normal.anyPerpendicular()} is used instead
 */
public record BumpFrame(Vec3 center, Vec3 normal, Vec3 forward) {
    /** Relative size of the tangential part of {@code forward} below which it counts as parallel to the normal. */
    private static final double DEGENERATE_TANGENT = 1e-9;

    /** @throws IllegalArgumentException if {@code normal} is (almost) zero */
    public BumpFrame {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(forward, "forward");
        Vec3 n = normal.normalize();
        if (n.isNearZero()) {
            throw new IllegalArgumentException("normal must be non-zero: " + normal);
        }
        normal = n;
        Vec3 tangent = forward.projectOnPlane(n);
        double scale = Math.max(1.0, forward.length());
        forward = tangent.length() <= DEGENERATE_TANGENT * scale ? n.anyPerpendicular() : tangent.normalize();
    }

    /**
     * Frame of a bump moving with {@code velocity}: the direction of motion is the velocity projected on the tangent
     * plane. A zero velocity, or one along the normal, falls back to a fixed perpendicular of the normal.
     */
    public static BumpFrame of(Vec3 center, Vec3 normal, Vec3 velocity) {
        return new BumpFrame(center, normal, velocity);
    }

    /** Unit vector across the direction of motion, {@code f × n}; completes the right-handed frame. */
    public Vec3 side() {
        return forward.cross(normal);
    }
}
