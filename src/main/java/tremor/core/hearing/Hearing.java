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
 */
public final class Hearing {
    private Hearing() {
    }

    /**
     * Perceived loudness at {@code listener}; 0 if {@code loudness·footing <= 0} or the distance exceeds
     * {@code params.maxDistance()}.
     */
    public static double perceived(VoxelView view, Vec3 source, Vec3 listener, double loudness, double footing,
                                   HearingParams params) {
        double strength = loudness * footing;
        if (!(strength > 0)) {
            return 0;
        }
        double distance = source.distance(listener);
        if (distance > params.maxDistance()) {
            return 0;
        }
        double resistance = averageResistance(view, source, listener, params.sampleStep(), params.minConductivity());
        return strength / (1 + distance * resistance);
    }

    /**
     * Mean of {@code 1 / max(conductivity, minConductivity)} over {@code n = max(1, ceil(distance / step))} samples at
     * the midpoints of {@code n} equal parts of the segment (each sample reads the voxel containing it). Deterministic.
     */
    public static double averageResistance(VoxelView view, Vec3 from, Vec3 to, double step, double minConductivity) {
        double dx = to.x() - from.x(), dy = to.y() - from.y(), dz = to.z() - from.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int n = Math.max(1, (int) Math.ceil(distance / step));
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double t = (i + 0.5) / n;
            int x = (int) Math.floor(from.x() + dx * t);
            int y = (int) Math.floor(from.y() + dy * t);
            int z = (int) Math.floor(from.z() + dz * t);
            sum += 1.0 / Math.max(view.conductivity(x, y, z), minConductivity);
        }
        return sum / n;
    }
}
