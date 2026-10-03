package tremor.awakening;

import tremor.core.noise.PerlinNoise;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Shape of the crater a defeat, or an escape through the edge, leaves in the real world (SPEC 9 "Исходы", 12): an
 * irregular funnel under the place the player was swallowed. Plain Java, unit-tested directly; what is actually dug
 * out of it is up to {@link CraterRules}.
 * <p>
 * Seen from above, the rim lies {@code radius} blocks from the centre, nearer or farther by up to
 * {@value #RIM_ROUGHNESS} of that depending on the direction (smooth noise around the circle, {@link #roughness}: a
 * few bulges and dents, and smaller notches and tongues on them).
 * Each column goes some blocks down below the swallow point's height ({@link #depthAt}). The wall drops from the rim
 * towards the middle {@value #WALL_SLOPE} blocks per block on average, steepest at the rim (its profile is
 * {@code 1 - (1 - u)^}{@value #WALL_POWER} of the way across it, {@code u} from 0 at the rim to 1 at its foot), so no
 * slope of single-block steps leads up or down it: a player needs blocks, or stairs dug into it. Inside its foot lies
 * the bottom, a shallow bowl that goes from the foot ({@value #BOWL} of the depth higher than the middle, bent by
 * noise by up to {@value #DEPTH_ROUGHNESS} of it by direction) down to the full {@code depth} in the middle. A wall
 * that would be wider than the rim is far takes the whole radius (a narrow, deep crater: a steep cone). The bottom and
 * the foot of the wall are covered by loose rubble ({@link #rubbleAt}: one or two layers, uneven).
 * <p>
 * The noise follows from the seed: the same seed gives the same crater. No column lies farther than {@link #reach}
 * from the centre on either axis.
 */
public final class CraterShape {
    /** How far (relative to the radius) the rim lies nearer or farther, by direction, at most. */
    static final double RIM_ROUGHNESS = 0.25;
    /** Blocks the wall drops per block towards the middle, on average across it. */
    static final double WALL_SLOPE = 3.0;
    /** Exponent of the wall's profile: the larger, the steeper it is at the rim and the gentler at its foot. */
    static final double WALL_POWER = 1.6;
    /** How much (relative to the depth) the foot of the wall lies higher than the middle of the bottom. */
    static final double BOWL = 0.12;
    /** How much (relative to it) the foot of the wall lies deeper or shallower, by direction. */
    static final double DEPTH_ROUGHNESS = 0.1;
    /** Every column inside the rim goes at least this deep (or the whole depth, if less): the rim is a drop. */
    static final int RIM_DROP = 3;
    /** The rubble covers the bottom and goes this many blocks up the foot of the wall. */
    static final double TALUS = 1.5;
    /** Frequency of the rim noise around the circle: a few bulges and dents. */
    private static final double RIM_FREQUENCY = 1.3;
    /** Frequency of the noise of the depth of the foot around the circle. */
    private static final double FOOT_FREQUENCY = 0.9;
    /**
     * The noise around the circle ({@link #roughness}): a finer octave this many times as frequent (smaller notches and
     * tongues on the bulges), weighing {@value #FINE_WEIGHT} of it, the sum amplified by {@value #ROUGHNESS_GAIN} (the
     * noise seldom goes far from 0) and cut at -1 and 1.
     */
    private static final double FINE_FREQUENCY = 1.85;
    private static final double FINE_WEIGHT = 0.3;
    private static final double ROUGHNESS_GAIN = 2.2;
    /** Moves the finer octave far from the coarse one in the (256-periodic) noise. */
    private static final double FINE_OFFSET = 128;
    /** Frequency of the rubble noise over the columns (per block). */
    private static final double RUBBLE_FREQUENCY = 0.45;
    /** Above this rubble noise a column of the bottom gets a second layer. */
    private static final double SECOND_LAYER = 0.1;

    private final int radius;
    private final int depth;
    /** Offsets into the (unseeded, 256-periodic) noise, from the seed. */
    private final double rimX, rimZ, footX, footZ, rubbleX, rubbleZ, layer;

    /**
     * @param radius blocks from the centre to the rim, before the roughness
     * @param depth  blocks the middle goes down
     * @param seed   picks the roughness
     * @throws IllegalArgumentException unless both are at least 1
     */
    public CraterShape(int radius, int depth, long seed) {
        if (radius < 1 || depth < 1) {
            throw new IllegalArgumentException("radius " + radius + ", depth " + depth);
        }
        this.radius = radius;
        this.depth = depth;
        SplittableRandom random = new SplittableRandom(seed);
        rimX = random.nextDouble(256);
        rimZ = random.nextDouble(256);
        footX = random.nextDouble(256);
        footZ = random.nextDouble(256);
        rubbleX = random.nextDouble(256);
        rubbleZ = random.nextDouble(256);
        layer = random.nextDouble(256);
    }

    /**
     * A column of the crater: its offset from the centre, how many blocks it goes down below the swallow point's height
     * ({@link #depthAt}) and how many layers of rubble cover it ({@link #rubbleAt}).
     */
    public record Column(int dx, int dz, int depth, int rubble) {
    }

    public int radius() {
        return radius;
    }

    public int depth() {
        return depth;
    }

    /** No column lies farther than this from the centre on either axis (the rim at its farthest). */
    public int reach() {
        return reach(radius);
    }

    /** {@link #reach} of a crater of {@code radius} blocks, whatever its seed. */
    public static int reach(int radius) {
        return (int) Math.ceil(radius * (1 + RIM_ROUGHNESS));
    }

    /**
     * Blocks the column at {@code dx, dz} from the centre goes down below the swallow point's height (0 outside the
     * rim, {@code depth} in the middle, never more): its lowest open block is that many blocks lower than the block the
     * player's feet were in.
     */
    public int depthAt(int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return depth;
        }
        Profile profile = profile(dx, dz);
        if (profile.distance >= profile.rim) {
            return 0;
        }
        double down;
        if (profile.distance <= profile.footRadius) {
            double s = profile.distance / profile.footRadius;
            down = profile.foot + (depth - profile.foot) * (1 - s * s);
        } else {
            double u = (profile.rim - profile.distance) / profile.wall;
            // A cone (no bottom inside the foot) goes the full depth at its tip.
            down = (profile.footRadius > 0 ? profile.foot : depth) * (1 - Math.pow(1 - u, WALL_POWER));
        }
        return (int) Math.min(depth, Math.max(RIM_DROP, Math.round(down)));
    }

    /**
     * Layers of loose rubble on the column at {@code dx, dz} (0, 1 or 2): one or two on the bottom and up to
     * {@value #TALUS} blocks up the foot of the wall, uneven by noise; none elsewhere, nor outside the crater.
     */
    public int rubbleAt(int dx, int dz) {
        // Never so much that the column is less than the rim's drop deep.
        int room = depthAt(dx, dz) - Math.min(depth, RIM_DROP);
        if (room <= 0) {
            return 0;
        }
        Profile profile = profile(dx, dz);
        if (profile.distance > Math.max(0, profile.footRadius) + TALUS) {
            return 0;
        }
        double noise = PerlinNoise.noise(rubbleX + RUBBLE_FREQUENCY * dx, rubbleZ + RUBBLE_FREQUENCY * dz,
                layer + 0.75);
        return Math.min(room, noise > SECOND_LAYER ? 2 : 1);
    }

    /** Every column that goes down at all, the centre first and then outwards (the nearest first). */
    public List<Column> columns() {
        int reach = reach();
        List<Column> columns = new ArrayList<>();
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                int down = depthAt(dx, dz);
                if (down > 0) {
                    columns.add(new Column(dx, dz, down, rubbleAt(dx, dz)));
                }
            }
        }
        columns.sort(Comparator.comparingInt((Column c) -> c.dx() * c.dx() + c.dz() * c.dz()));
        return columns;
    }

    /** The rim, the wall and the foot in the direction of {@code dx, dz}, and how far that column is. */
    private Profile profile(int dx, int dz) {
        double angle = Math.atan2(dz, dx);
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double rim = radius * (1 + RIM_ROUGHNESS * roughness(rimX, rimZ, layer, RIM_FREQUENCY, cos, sin));
        double foot = depth * (1 - BOWL) * (1 + DEPTH_ROUGHNESS * roughness(footX, footZ, layer + 0.5,
                FOOT_FREQUENCY, cos, sin));
        foot = Math.max(Math.min(1, depth), Math.min(depth, foot));
        double wall = Math.min(rim, Math.max(1, foot / WALL_SLOPE));
        return new Profile(Math.sqrt(dx * dx + dz * dz), rim, foot, wall, rim - wall);
    }

    /**
     * Smooth noise from -1 to 1 in the direction {@code cos, sin}: two octaves of the noise on circles around
     * {@code x, z} (in its plane {@code w}), the coarse one of radius {@code frequency}, amplified and cut.
     */
    private static double roughness(double x, double z, double w, double frequency, double cos, double sin) {
        double coarse = PerlinNoise.noise(x + frequency * cos, z + frequency * sin, w);
        double fine = PerlinNoise.noise(x + FINE_OFFSET + FINE_FREQUENCY * frequency * cos,
                z + FINE_OFFSET + FINE_FREQUENCY * frequency * sin, w);
        double noise = ROUGHNESS_GAIN * ((1 - FINE_WEIGHT) * coarse + FINE_WEIGHT * fine);
        return Math.max(-1, Math.min(1, noise));
    }

    /**
     * The crater in one direction: {@code distance} of the column from the centre, the {@code rim}, the {@code foot}'s
     * depth, the width of the {@code wall} and where its foot lies ({@code footRadius}: 0 for a cone).
     */
    private record Profile(double distance, double rim, double foot, double wall, double footRadius) {
    }
}
