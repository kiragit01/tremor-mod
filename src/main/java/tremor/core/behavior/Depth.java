package tremor.core.behavior;

/**
 * How deep the entity is, for how fierce it is: no effect at or above {@code shallowY}, all of it at or below
 * {@code deepY}, in a straight line between. A factor of {@code full} at full depth is 1 at the surface.
 */
public final class Depth {
    private Depth() {
    }

    /** Share of the depth effect at height {@code y}, 0..1 (0 when the band is empty or upside down). */
    public static double share(double y, double shallowY, double deepY) {
        if (!(shallowY > deepY)) {
            return 0;
        }
        return Math.max(0, Math.min(1, (shallowY - y) / (shallowY - deepY)));
    }

    /** The factor at a share of the depth effect: 1 + (full - 1) × share. */
    public static double factor(double full, double share) {
        return 1 + (full - 1) * share;
    }
}
