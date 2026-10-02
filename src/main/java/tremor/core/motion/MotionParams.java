package tremor.core.motion;

/**
 * How the crawler moves (SPEC 5.5).
 *
 * @param maxSpeed                  cruise speed along the path, blocks per second
 * @param acceleration              speed change limit, blocks per second²; also used to brake before the end
 * @param normalSmoothingSeconds    time constant of the exponential smoothing of the surface normal
 * @param amplitude                 bump height while on the skin; 0 while diving
 * @param amplitudeSmoothingSeconds time constant of the amplitude changes (surfacing / diving)
 */
public record MotionParams(double maxSpeed, double acceleration, double normalSmoothingSeconds, double amplitude,
                           double amplitudeSmoothingSeconds) {
    public MotionParams {
        if (!(maxSpeed >= 0) || !(acceleration > 0) || !(normalSmoothingSeconds >= 0) || !Double.isFinite(amplitude)
                || !(amplitudeSmoothingSeconds >= 0)) {
            throw new IllegalArgumentException(toString());
        }
    }
}
