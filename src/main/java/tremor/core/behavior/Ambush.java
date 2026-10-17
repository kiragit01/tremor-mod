package tremor.core.behavior;

import tremor.core.math.Vec3;

/**
 * The ambush (the user's idea after 1.1.2): in ALERT or HUNTING, on a floor, an entity that senses a player coming
 * who does not see it lies in wait. It stops and flattens, its bump wide and low and breathing, so that only a careful
 * player sees the ground move; whoever steps onto it is pulled under as into quicksand (the swallowing of an
 * Awakening without its build-up, each player into a hollow of their own), and a mob sinks. The rules, pure; the
 * entity's mind keeps the state ({@code tremor.entity.TremorMind}), the clients draw the look
 * ({@link tremor.core.shape.BumpParams#lurking}).
 */
public final class Ambush {
    /** A floor: the normal of the skin at most about 37 degrees from straight up (not a wall, not a ceiling). */
    public static final double FLOOR_NORMAL_Y = 0.8;
    /** Feet count as stepped in at most this far above or below the skin (blocks). */
    public static final double STEP_HEIGHT = 1.5;
    /** A player comes nearer by at least this (blocks, horizontally) between two looks to count as coming... */
    public static final double MIN_CLOSING = 0.5;
    /** ...or walks at least this far between them within half the sensing distance. */
    public static final double MIN_WALK = 0.5;
    /** A player looking within this angle of a point would see it, if nothing is in the way. */
    public static final double VIEW_COS = Math.cos(Math.toRadians(70));
    /** How long the bump takes to flatten and widen, and to rise and narrow again (seconds). */
    public static final double FADE_SECONDS = 1.5;
    /** Height of the bump lying in wait (blocks), before its breathing. */
    public static final double HEIGHT = 0.3;
    /** Lying in wait, the bump is round and this many times as wide as its widest. */
    public static final double SPREAD = 1.6;
    /** The breathing of the bump lying in wait: its height swings by this share of itself... */
    public static final double BREATH = 0.45;
    /** ...once in this long (seconds). */
    public static final double BREATH_PERIOD = 3.2;

    private Ambush() {
    }

    /** Whether an entity in {@code stage} may lie in wait on a skin with this {@code normal}: ALERT or HUNTING, a floor. */
    public static boolean mayLurk(Stage stage, Vec3 normal) {
        return (stage == Stage.ALERT || stage == Stage.HUNTING) && normal.y() >= FLOOR_NORMAL_Y;
    }

    /**
     * Whether a player whose feet went from {@code before} to {@code now} comes for the bump at {@code bump}: within
     * {@code sense} blocks of it (horizontally), nearer by {@link #MIN_CLOSING}, or walking within half of that.
     */
    public static boolean coming(Vec3 bump, Vec3 before, Vec3 now, double sense) {
        double was = horizontal(bump, before);
        double is = horizontal(bump, now);
        if (is > sense) {
            return false;
        }
        return was - is >= MIN_CLOSING || is <= sense / 2 && horizontal(before, now) >= MIN_WALK;
    }

    /** Whether {@code point} is in the view of an eye at {@code eye} looking along {@code look} (a unit vector). */
    public static boolean inView(Vec3 eye, Vec3 look, Vec3 point) {
        Vec3 to = point.sub(eye);
        double length = to.length();
        return length < 1e-6 || look.dot(to) / length >= VIEW_COS;
    }

    /** Whether feet at {@code feet} are on the ambush lying at {@code center} (its skin), {@code radius} wide. */
    public static boolean steppedIn(Vec3 center, Vec3 feet, double radius) {
        return horizontal(center, feet) <= radius && Math.abs(feet.y() - center.y()) <= STEP_HEIGHT;
    }

    /**
     * How far the bump has gone into its look of lying in wait, {@code share} (0..1) a moment ago and
     * {@code seconds} later: towards 1 while it lies in wait, back to 0 otherwise, over {@link #FADE_SECONDS}.
     */
    public static double fade(double share, boolean lurking, double seconds) {
        double step = seconds / FADE_SECONDS;
        return lurking ? Math.min(1, share + step) : Math.max(0, share - step);
    }

    /**
     * The factor of the bump's height for its breathing at {@code seconds} on any steady clock, {@code share} of the
     * way into lying in wait: 1 while it does not lie in wait.
     */
    public static double breath(double seconds, double share) {
        if (!(share > 0)) {
            return 1;
        }
        return 1 + Math.min(1, share) * BREATH * Math.sin(2 * Math.PI * seconds / BREATH_PERIOD);
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }
}
