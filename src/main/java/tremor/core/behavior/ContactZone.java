package tremor.core.behavior;

import java.util.Objects;

import tremor.core.VoxelView;
import tremor.core.math.Vec3;
import tremor.core.voxel.LineOfSight;

/**
 * Contact of the bump with a player (SPEC 8 HUNTING: "контакт бугра с игроком: урон + отбрасывание"). The bump is
 * modelled as a cylinder standing on the skin: its axis is the surface normal through {@code center} (the centre of
 * the surface voxel the entity is in, so the skin is half a block above it), its radius is {@code radius}, and it
 * reaches from the centre plane up to {@code amplitude + 1.5} blocks above the centre: the skin ({@code +0.5}), the top
 * of the raised bump ({@code +0.5 + amplitude}) and up to a block above that.
 * <p>
 * A player touches the bump if a point of their body is in the cylinder and on the open side of the skin. The body is
 * the vertical segment from the feet up by the player's height, sampled at its bottom, middle and top (each end
 * {@value #BODY_INSET} blocks inside): a bump on a wall or a ceiling that reaches the torso or the head strikes as
 * well as one on the floor that reaches the feet. "On the open side": the line of sight ({@link LineOfSight}) from the
 * axis {@value #SIGHT_LIFT} blocks above the skin (inside the raised bump) to the body point passes through no solid
 * or unknown voxel but the bump's own, so a bump never strikes through a wall, a floor or a ceiling, not even one a
 * block thick whose far side is within the cylinder.
 */
public final class ContactZone {
    /** How far above the top of the bump ({@code amplitude} along the normal) the zone reaches. */
    private static final double ABOVE = 1.5;
    /**
     * The lowest and highest body points tested are this far inside the body: the lowest is off the ground the player
     * stands on, which would otherwise be an end voxel of its line of sight (one a line of sight may pass).
     */
    static final double BODY_INSET = 0.1;
    /**
     * The line of sight to a body point starts on the axis this far above the skin: a normal tilted off the grid does
     * not make it run along the skin next to the bump.
     */
    static final double SIGHT_LIFT = 0.25;

    private ContactZone() {
    }

    /**
     * Whether a player {@code height} blocks tall with feet at {@code feet} (the bottom centre of their box) touches
     * the bump. With {@code n} the normalized {@code normal}, a body point {@code p} (at {@code feet.y + BODY_INSET},
     * {@code feet.y + height / 2} and {@code feet.y + height - BODY_INSET}), {@code d = p - center},
     * {@code along = d·n} and {@code tangential = |d - n·along|}: true iff {@code amplitude > 0} and for one of them
     * {@code tangential <= radius}, {@code 0 <= along <= amplitude + 1.5}, and the segment from
     * {@code center + n·(0.5 + SIGHT_LIFT)} to {@code p} is {@linkplain LineOfSight#clear clear} in {@code view} with
     * the voxel containing {@code center} counted as open. A hidden or sunken bump ({@code amplitude <= 0}, e.g. while
     * diving) touches nobody; so does a ZERO normal.
     */
    public static boolean touches(VoxelView view, Vec3 center, Vec3 normal, double amplitude, Vec3 feet,
                                  double height, double radius) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(feet, "feet");
        Vec3 n = Objects.requireNonNull(normal, "normal").normalize();
        if (!(amplitude > 0) || n.isNearZero()) {
            return false;
        }
        double low = Math.min(BODY_INSET, height / 2);
        double[] heights = {low, height / 2, Math.max(height - BODY_INSET, height / 2)};
        Vec3 sight = center.add(n.scale(0.5 + SIGHT_LIFT));
        VoxelView open = null;
        for (double h : heights) {
            Vec3 p = feet.add(0, h, 0);
            Vec3 d = p.sub(center);
            double along = d.dot(n);
            double tangential = d.sub(n.scale(along)).length();
            if (!(tangential <= radius && along >= 0 && along <= amplitude + ABOVE)) {
                continue;
            }
            if (open == null) {
                open = new OwnVoxelOpen(view, floor(center.x()), floor(center.y()), floor(center.z()));
            }
            if (LineOfSight.clear(open, sight, p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * How far from {@code center} the feet of a player {@code height} blocks tall may be and still touch a bump
     * {@code amplitude} high ({@link #touches}): {@code radius + amplitude + 1.5 + height}. Players further away need
     * not be tested.
     */
    public static double reach(double amplitude, double radius, double height) {
        return radius + Math.max(0, amplitude) + ABOVE + Math.max(0, height);
    }

    /**
     * Direction to push a touching player in: the unit direction from {@code center} toward {@code feet} in the
     * tangent plane of {@code normal}; where the feet are right above the centre, {@code forward} projected onto
     * the plane (the bump shoves the player ahead of it); if that is degenerate too, some perpendicular of the
     * normal. Always a unit vector perpendicular to the normal.
     *
     * @throws IllegalArgumentException if {@code normal} is ZERO
     */
    public static Vec3 push(Vec3 center, Vec3 normal, Vec3 forward, Vec3 feet) {
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(forward, "forward");
        Objects.requireNonNull(feet, "feet");
        Vec3 n = Objects.requireNonNull(normal, "normal").normalize();
        if (n.isNearZero()) {
            throw new IllegalArgumentException("normal must be non-zero: " + normal);
        }
        Vec3 away = feet.sub(center).projectOnPlane(n).normalize();
        if (!away.isNearZero()) {
            return away;
        }
        Vec3 ahead = forward.projectOnPlane(n).normalize();
        return ahead.isNearZero() ? n.anyPerpendicular() : ahead;
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    /** {@code view} with the bump's own voxel open (and known): the line of sight starts inside the bump. */
    private record OwnVoxelOpen(VoxelView view, int x, int y, int z) implements VoxelView {
        private boolean own(int vx, int vy, int vz) {
            return vx == x && vy == y && vz == z;
        }

        @Override
        public boolean isSolid(int vx, int vy, int vz) {
            return !own(vx, vy, vz) && view.isSolid(vx, vy, vz);
        }

        @Override
        public boolean isKnown(int vx, int vy, int vz) {
            return own(vx, vy, vz) || view.isKnown(vx, vy, vz);
        }

        @Override
        public float conductivity(int vx, int vy, int vz) {
            return view.conductivity(vx, vy, vz);
        }

        @Override
        public boolean isProtected(int vx, int vy, int vz) {
            return view.isProtected(vx, vy, vz);
        }
    }
}
