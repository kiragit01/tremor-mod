package tremor.client.hollow;

/**
 * The black fog of the hollow (SPEC 9 phase 2: "в изнанке чёрный туман — видно ~5 блоков"), as plain functions of the
 * fog distance the client config gives ({@code hollowFogDistance}, about 5.5 blocks) and of how far the hollow has
 * closed ({@code closeness}, 0..1): where the fog ends, everything further being black ({@link #far}), where it
 * starts ({@link #near}, also over a fog that is already there), and how far around the player the moving ground is
 * worth drawing at all ({@link #reach}). {@link HollowFog} applies it. No game classes.
 * <p>
 * The fog thickens with the distance from the eye as vanilla's shaders draw any fog: clear up to {@link #near}, black
 * from {@link #far} on, and in between a smoothstep, gentle at both ends (half black halfway). It starts about a
 * tenth of the way out (0.5 of 5.5 blocks), so the player's own surroundings are already dim, and it closes in by up
 * to {@link #CLOSED_SHARE} as the hollow closes, with the rest of the pressure.
 */
final class FogCurve {
    /** The fog starts at this share of the way to where it ends: 0.5 blocks of 5.5. */
    static final double NEAR_SHARE = 1.0 / 11;
    /** Share of the configured distance the fog keeps once the hollow has closed; linear in between. */
    static final double CLOSED_SHARE = 0.85;
    /**
     * The moving ground is drawn this much further out than the configured fog distance (it fades out over the last
     * few blocks of that, {@link tremor.core.shape.HollowParams#ringFade}), so that nothing of it is cut where the
     * player can still see...
     */
    static final double REACH_MARGIN = 3.5;
    /** ...but not nearer than this... */
    static final double MIN_REACH = 6;
    /** ...nor further: a thin fog set in the config does not make the whole copy move every frame. */
    static final double MAX_REACH = 20;

    private FogCurve() {
    }

    /**
     * Distance (blocks) from which on everything is black: {@code configured}, closing in linearly to
     * {@code CLOSED_SHARE·configured} with the closeness (clamped to 0..1, NaN counts as 0).
     */
    static double far(double configured, double closeness) {
        return configured * (1 - (1 - CLOSED_SHARE) * share(closeness));
    }

    /** Distance (blocks) up to which nothing is fogged: {@link #NEAR_SHARE} of {@code far}. */
    static double near(double far) {
        return far * NEAR_SHARE;
    }

    /**
     * Where the fog that ends at {@code far} starts over a fog that is already there and starts at {@code otherNear}:
     * the nearer of the two starts, so that a thicker fog is kept (lava, powder snow, blindness and darkness start
     * at 0 or beyond), but never a negative start, nor beyond {@code far}. A negative start is the water's (-8 blocks,
     * paired with an end some 20 blocks out or more): with the short end of the hollow it would make a fog far
     * thicker than either, more than half black at the eye; so under water the fog of the hollow is as in the air.
     * NaN counts as no other fog.
     */
    static double near(double far, double otherNear) {
        double near = near(far);
        if (otherNear >= 0 && otherNear < near) {
            near = otherNear;
        }
        return Math.min(near, far);
    }

    /**
     * How far around the player the heaving ground and the node's rings are drawn for a fog that ends at
     * {@code configured} (before it closes in): {@code configured + REACH_MARGIN} within
     * {@code [MIN_REACH, MAX_REACH]}. It does not change while the hollow closes, so the region scanned for the
     * ground does not either.
     */
    static double reach(double configured) {
        double reach = configured + REACH_MARGIN;
        return reach > MIN_REACH ? Math.min(reach, MAX_REACH) : MIN_REACH;
    }

    /** {@code x} clamped to {@code [0, 1]}; NaN counts as 0. */
    private static double share(double x) {
        return x > 0 ? Math.min(x, 1) : 0;
    }
}
