package tremor.core.hearing;

import tremor.core.VoxelView;
import tremor.core.math.Vec3;

/**
 * Vibration propagation through the ground (SPEC 7.2):
 * <pre>
 * perceived = loudness · footing / (1 + distance · averageResistance)
 * </pre>
 * {@code loudness} is the base loudness of the event (SPEC 7.1 table), {@code footing} the conductivity of the block
 * the source stands on (insulation under the feet: wool 0.1 ... stone 1.2), {@code averageResistance} the mean of
 * {@code 1 / conductivity} over samples along the straight segment from the source to the listener, so rock carries a
 * vibration far, loose ground less and open air almost not at all. Conductivities come from
 * {@link VoxelView#conductivity} (open voxels included: the world adapter gives air and water their own values).
 * <p>
 * A source that rustles in the leaves it stands on (SPEC 7.2 "Шелест": its footing is the rustling factor, louder than
 * stone) is not damped by those leaves, though leaves are insulating: the samples of its way from the source on, as
 * long as they lie in leaves ({@link Foliage}), conduct at its footing. Leaves farther along the way, past anything
 * else, damp as their class: a band of leaves between a source and the listener still muffles it.
 */
public final class Hearing {
    /** The blocks a source can rustle in (leaves, {@code #tremor:rustling}), as its way to the listener sees them. */
    @FunctionalInterface
    public interface Foliage {
        /** Whether the voxel rustles. */
        boolean rustles(int x, int y, int z);
    }

    private Hearing() {
    }

    /**
     * Perceived loudness at {@code listener} of a source that does not rustle; 0 if {@code loudness·footing <= 0} or
     * the distance exceeds {@code params.maxDistance()}.
     */
    public static double perceived(VoxelView view, Vec3 source, Vec3 listener, double loudness, double footing,
                                   HearingParams params) {
        return perceived(view, source, listener, loudness, footing, null, params);
    }

    /**
     * Perceived loudness at {@code listener}; 0 if {@code loudness·footing <= 0} or the distance exceeds
     * {@code params.maxDistance()}.
     *
     * @param foliage the leaves the source rustles in (their leading samples conduct at {@code footing}, see
     *                {@link #averageResistance(VoxelView, Vec3, Vec3, double, double, Foliage, double)}), or null if
     *                it does not rustle
     */
    public static double perceived(VoxelView view, Vec3 source, Vec3 listener, double loudness, double footing,
                                   Foliage foliage, HearingParams params) {
        double strength = loudness * footing;
        if (!(strength > 0)) {
            return 0;
        }
        double distance = source.distance(listener);
        if (distance > params.maxDistance()) {
            return 0;
        }
        double resistance = averageResistance(view, source, listener, params.sampleStep(), params.minConductivity(),
                foliage, footing);
        return strength / (1 + distance * resistance);
    }

    /**
     * Mean of {@code 1 / max(conductivity, minConductivity)} over {@code n = max(1, ceil(distance / step))} samples at
     * the midpoints of {@code n} equal parts of the segment (each sample reads the voxel containing it). Deterministic.
     */
    public static double averageResistance(VoxelView view, Vec3 from, Vec3 to, double step, double minConductivity) {
        return averageResistance(view, from, to, step, minConductivity, null, 0);
    }

    /**
     * As {@link #averageResistance(VoxelView, Vec3, Vec3, double, double)} for a source at {@code from} that rustles
     * in {@code foliage}: the samples from the first one on, as long as each lies in a voxel that rustles, take
     * {@code foliageConductivity} (the source's footing) instead of the view's conductivity; from the first sample
     * outside them on, all take the view's conductivity again (leaves farther on included). A null {@code foliage}:
     * the plain average.
     */
    public static double averageResistance(VoxelView view, Vec3 from, Vec3 to, double step, double minConductivity,
                                           Foliage foliage, double foliageConductivity) {
        double dx = to.x() - from.x(), dy = to.y() - from.y(), dz = to.z() - from.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int n = Math.max(1, (int) Math.ceil(distance / step));
        boolean inFoliage = foliage != null;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double t = (i + 0.5) / n;
            int x = (int) Math.floor(from.x() + dx * t);
            int y = (int) Math.floor(from.y() + dy * t);
            int z = (int) Math.floor(from.z() + dz * t);
            inFoliage = inFoliage && foliage.rustles(x, y, z);
            double conductivity = inFoliage ? foliageConductivity : view.conductivity(x, y, z);
            sum += 1.0 / Math.max(conductivity, minConductivity);
        }
        return sum / n;
    }
}
