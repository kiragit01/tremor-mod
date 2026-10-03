package tremor.client.hollow;

import tremor.core.shape.RippleParams;

/**
 * Where a ring of the node runs near the player and what it stirs up there (SPEC 9 phase 2: "по волне видно,
 * откуда она пришла"), as plain functions; {@link HollowWake} spawns the dust and counts the passes. No game classes.
 * <p>
 * The leading crest of a ring is a sphere around the node, of radius {@link #crest}. Within the fog only a cap of it
 * shows: the part within the sight distance of the player, a disc of radius {@link #capRadius} around the point of the
 * sphere nearest the player, small while the crest comes into sight, widest as it runs through the player, small
 * again as it goes; dust comes down from the ceilings and out of the walls along that cap. On the floor the crest is
 * the circle in which the sphere cuts the plane of the player's feet ({@link #floorRadius}), of which the arc within
 * sight shows ({@link #arcHalfAngle}): dust rises off the floor along that arc, right where the crest runs, even when
 * the node lies far below or above the player. How much dust is {@link #dustCount}'s, and the moment the crest runs
 * through the player is a pass ({@link #passes}).
 */
final class WakeCurve {
    /**
     * Clouds of dust per tick off the floor along the crest of a ring at its full height (strength 1) while it runs
     * through the player: fewer while it comes into sight and goes, and for a lower ring.
     */
    static final double FLOOR_DUST_PER_TICK = 8;
    /** Dust per tick down from the ceilings and out of the walls along the crest, likewise. */
    static final double SURFACE_DUST_PER_TICK = 4;
    /** Particles kicked up around the player's feet as the crest of a ring at its full height runs under them. */
    static final double PUFF = 8;

    private WakeCurve() {
    }

    /** Radius of the leading crest {@code ageSeconds} after the beat: a quarter wavelength behind the front. */
    static double crest(RippleParams ring, double ageSeconds) {
        return ring.speed() * ageSeconds - ring.wavelength() / 4;
    }

    /**
     * Whether the leading crest runs through a point {@code distance} from the node between the ages
     * {@code fromAge} and {@code toAge} (seconds, {@code fromAge < toAge}): {@code crest(from) < distance <=
     * crest(to)}, while the ring runs.
     */
    static boolean passes(RippleParams ring, double fromAge, double toAge, double distance) {
        return toAge >= 0 && toAge < ring.duration() && crest(ring, fromAge) < distance
                && distance <= crest(ring, toAge);
    }

    /**
     * Radius of the cap of a crest sphere of radius {@code crest} around a node {@code distance} from the player that
     * lies within {@code sight} of the player: {@code sqrt(sight² - (distance - crest)²)}, as measured across the
     * sphere at the point nearest the player; 0 if none of it is in sight (or a value is NaN).
     */
    static double capRadius(double sight, double distance, double crest) {
        double off = distance - crest;
        double r2 = sight * sight - off * off;
        return crest > 0 && r2 > 0 ? Math.sqrt(r2) : 0;
    }

    /**
     * Radius of the circle in which a crest sphere of radius {@code crest} cuts a horizontal plane {@code height}
     * blocks above (or, negative, below) the node: {@code sqrt(crest² - height²)}, centred straight above or below the
     * node; 0 if the sphere does not reach the plane (or a value is NaN).
     */
    static double floorRadius(double crest, double height) {
        double r2 = crest * crest - height * height;
        return crest > 0 && r2 > 0 ? Math.sqrt(r2) : 0;
    }

    /**
     * Half the angle (radians, about the centre of a circle of {@code radius}) of the arc of the circle that lies
     * within {@code sight} of a point {@code toPlayer} from the centre in the circle's plane, the arc being centred on
     * the direction of that point: the angles {@code a} with {@code radius² + toPlayer² - 2·radius·toPlayer·cos(a) <=
     * sight²}. π if all of the circle is within sight, 0 if none of it is (or a value is NaN). Its length is
     * {@code 2·radius·arcHalfAngle}: up to some {@code 2·sight} as the crest runs through the player.
     */
    static double arcHalfAngle(double radius, double toPlayer, double sight) {
        if (!(radius > 0) || !(sight > 0) || !(toPlayer >= 0)) {
            return 0;
        }
        double cos = (radius * radius + toPlayer * toPlayer - sight * sight) / (2 * radius * toPlayer);
        if (cos <= -1) {
            return Math.PI; // also a player right above or below the node, within sight of the whole circle
        }
        return cos < 1 ? Math.acos(cos) : 0; // also a player above or below the node out of sight of it (cos is +inf)
    }

    /**
     * Particles to spawn this tick along the part of a crest within sight: {@code perTick} times the ring's
     * {@code strength} (clamped to 0..1) times the share of the sight that part spans ({@code extent / sight},
     * clamped: {@code extent} is the radius of the cap, or half the length of the arc on the floor) times
     * {@code share} (the particle setting), rounded down or up at random so that it is right on average.
     *
     * @param uniform a random number in {@code [0, 1)}
     */
    static int dustCount(double perTick, double strength, double extent, double sight, double share, double uniform) {
        double expected = perTick * clamp(strength) * clamp(extent / sight) * clamp(share);
        return expected > 0 ? (int) Math.floor(expected + uniform) : 0;
    }

    /**
     * Particles of the puff at the player's feet as a crest of {@code strength} runs under them: {@link #PUFF} times
     * the strength and the share, rounded at random as {@link #dustCount}.
     */
    static int puffCount(double strength, double share, double uniform) {
        double expected = PUFF * clamp(strength) * clamp(share);
        return expected > 0 ? (int) Math.floor(expected + uniform) : 0;
    }

    /** {@code x} clamped to {@code [0, 1]}; NaN counts as 0. */
    private static double clamp(double x) {
        return x > 0 ? Math.min(x, 1) : 0;
    }
}
