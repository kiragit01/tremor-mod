package tremor.awakening;

import tremor.core.noise.PerlinNoise;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Shape of the sinkhole a defeat leaves in the real world (SPEC 9 "Поражение"): a bowl under the place the player was
 * swallowed, roughened by noise so that it looks torn rather than stamped. Plain Java, unit-tested directly; what is
 * actually dug out of it is up to {@link SinkholeRules}.
 * <p>
 * Each column of the bowl goes some blocks down from the swallow point's height ({@link #depthAt}). The rim lies
 * {@code radius} blocks from the centre, nearer or farther by up to {@value #RIM_ROUGHNESS} of that depending on the
 * direction; a column at the relative distance {@code t} from the centre to the rim (0 at the centre, 1 at the rim)
 * goes {@code depth * (1 - t * t)} blocks down, more or less by up to {@code t *} {@value #DEPTH_ROUGHNESS} of that,
 * rounded, never more than {@code depth}. So the centre goes the full depth and the sides grow steep towards it, and
 * the roughness grows towards the rim, where a real collapse is the least even. The noise follows from the seed: the
 * same seed gives the same sinkhole.
 */
public final class SinkholeShape {
    /** How far (relative to the radius) the rim lies nearer or farther, by direction. */
    static final double RIM_ROUGHNESS = 0.25;
    /** How much (relative to the depth there) a column at the rim goes deeper or shallower; less nearer the centre. */
    static final double DEPTH_ROUGHNESS = 0.3;
    /** Frequency of the rim noise around the circle: about two bulges and two dents. */
    private static final double RIM_FREQUENCY = 1.3;
    /** Frequency of the depth noise over the columns (per block). */
    private static final double DEPTH_FREQUENCY = 0.45;

    private final int radius;
    private final int depth;
    /** Offsets into the (unseeded, 256-periodic) noise, from the seed. */
    private final double rimX, rimZ, depthX, depthZ, layer;

    /**
     * @param radius blocks from the centre to the rim, before the roughness
     * @param depth  blocks the centre goes down
     * @param seed   picks the roughness
     * @throws IllegalArgumentException unless both are at least 1
     */
    public SinkholeShape(int radius, int depth, long seed) {
        if (radius < 1 || depth < 1) {
            throw new IllegalArgumentException("radius " + radius + ", depth " + depth);
        }
        this.radius = radius;
        this.depth = depth;
        SplittableRandom random = new SplittableRandom(seed);
        rimX = random.nextDouble(256);
        rimZ = random.nextDouble(256);
        depthX = random.nextDouble(256);
        depthZ = random.nextDouble(256);
        layer = random.nextDouble(256);
    }

    /** A column of the sinkhole: its offset from the centre and how many blocks it goes down. */
    public record Column(int dx, int dz, int depth) {
    }

    public int radius() {
        return radius;
    }

    public int depth() {
        return depth;
    }

    /** No column lies farther than this from the centre on either axis. */
    public int reach() {
        return (int) Math.ceil(radius * (1 + RIM_ROUGHNESS));
    }

    /** Blocks the column at {@code dx, dz} from the centre goes down below the swallow point's height; 0 outside. */
    public int depthAt(int dx, int dz) {
        if (dx == 0 && dz == 0) {
            return depth;
        }
        double distance = Math.sqrt(dx * dx + dz * dz);
        double angle = Math.atan2(dz, dx);
        double rim = radius * (1 + RIM_ROUGHNESS * PerlinNoise.noise(rimX + RIM_FREQUENCY * Math.cos(angle),
                rimZ + RIM_FREQUENCY * Math.sin(angle), layer));
        double t = distance / rim;
        if (t >= 1) {
            return 0;
        }
        double rough = 1 + DEPTH_ROUGHNESS * t * PerlinNoise.noise(depthX + DEPTH_FREQUENCY * dx,
                depthZ + DEPTH_FREQUENCY * dz, layer + 0.5);
        return (int) Math.min(depth, Math.round(depth * (1 - t * t) * rough));
    }

    /** Every column that goes down at all, the centre first and then outwards (the nearest first). */
    public List<Column> columns() {
        int reach = reach();
        List<Column> columns = new ArrayList<>();
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                int down = depthAt(dx, dz);
                if (down > 0) {
                    columns.add(new Column(dx, dz, down));
                }
            }
        }
        columns.sort(Comparator.comparingInt((Column c) -> c.dx() * c.dx() + c.dz() * c.dz()));
        return columns;
    }
}
