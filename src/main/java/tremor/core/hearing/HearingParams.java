package tremor.core.hearing;

/**
 * How vibrations travel through the ground to the entity (SPEC 7.2).
 *
 * @param threshold       perceived loudness from which the entity hears a vibration
 * @param maxDistance     vibrations further away than this (blocks) are never heard and not even sampled
 * @param sampleStep      spacing (blocks) of the samples along the source-listener segment
 * @param minConductivity conductivities are clamped to at least this before taking the resistance {@code 1/c}
 */
public record HearingParams(double threshold, double maxDistance, double sampleStep, double minConductivity) {
    public HearingParams {
        if (!(threshold > 0) || !(maxDistance > 0) || !(sampleStep > 0) || !(minConductivity > 0)) {
            throw new IllegalArgumentException(toString());
        }
    }

    public static HearingParams defaults() {
        return new HearingParams(0.05, 96, 0.5, 0.01);
    }
}
