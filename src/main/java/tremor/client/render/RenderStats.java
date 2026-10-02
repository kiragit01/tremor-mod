package tremor.client.render;

import java.util.Arrays;

/**
 * Per-frame cost counters of the deformation renderer and the smoothness of the bump's motion, read by benchmarks
 * (dev autotest). Render thread only.
 * <p>
 * Smoothness: the speed of the drawn bump centre is measured between consecutive frames (displacement / real time).
 * Interpolation that jerks (snapping to snapshots, clock jumps, stalls) shows up as a large spread of these speeds.
 */
public final class RenderStats {
    /** Frame-to-frame speeds kept for percentiles; later frames still count in the mean and maximum. */
    private static final int MAX_SAMPLES = 1 << 16;
    /** Frames whose centre moved less than this (blocks/s) count as standing still and are left out. */
    private static final double MIN_SPEED = 0.05;
    /** Frames closer together than this are skipped: their timing noise would dominate the speed. */
    private static final long MIN_FRAME_NANOS = 200_000;

    private static long frames;
    private static long nanos;
    private static long copies;
    private static long vertices;

    private static boolean hasLast;
    private static double lastX, lastY, lastZ;
    private static long lastNanos;
    private static int motionFrames;
    private static double speedSum;
    private static double speedMax;
    private static double[] speeds = new double[1024];
    private static double lastSpeed = Double.NaN;
    private static double[] jumps = new double[1024];
    private static int jumpCount;

    private RenderStats() {
    }

    /** Called once per rendered frame in which the renderer had something to draw. */
    public static void record(long frameNanos, int frameCopies, int frameVertices) {
        frames++;
        nanos += frameNanos;
        copies += frameCopies;
        vertices += frameVertices;
    }

    /** Called once per frame with the interpolated bump centre that frame was drawn with. */
    public static void recordCenter(double x, double y, double z, long nowNanos) {
        if (hasLast) {
            long dt = nowNanos - lastNanos;
            if (dt < MIN_FRAME_NANOS) {
                return; // keep the previous sample as the reference
            }
            double dx = x - lastX, dy = y - lastY, dz = z - lastZ;
            double speed = Math.sqrt(dx * dx + dy * dy + dz * dz) / (dt / 1e9);
            if (speed >= MIN_SPEED) {
                if (!Double.isNaN(lastSpeed) && jumpCount < MAX_SAMPLES) {
                    if (jumpCount == jumps.length) {
                        jumps = Arrays.copyOf(jumps, jumps.length * 2);
                    }
                    jumps[jumpCount++] = Math.abs(speed - lastSpeed);
                }
                lastSpeed = speed;
                if (motionFrames < MAX_SAMPLES) {
                    if (motionFrames == speeds.length) {
                        speeds = Arrays.copyOf(speeds, speeds.length * 2);
                    }
                    speeds[motionFrames] = speed;
                }
                motionFrames++;
                speedSum += speed;
                speedMax = Math.max(speedMax, speed);
            } else {
                lastSpeed = Double.NaN;
            }
        }
        hasLast = true;
        lastX = x;
        lastY = y;
        lastZ = z;
        lastNanos = nowNanos;
    }

    public static void reset() {
        frames = 0;
        nanos = 0;
        copies = 0;
        vertices = 0;
        hasLast = false;
        motionFrames = 0;
        speedSum = 0;
        speedMax = 0;
        lastSpeed = Double.NaN;
        jumpCount = 0;
    }

    public static Snapshot snapshot() {
        Motion motion = motion();
        if (frames == 0) {
            return new Snapshot(0, 0, 0, 0, motion);
        }
        return new Snapshot(frames, nanos / 1e6 / frames, (double) copies / frames, (double) vertices / frames, motion);
    }

    private static Motion motion() {
        int n = Math.min(motionFrames, MAX_SAMPLES);
        if (n == 0) {
            return new Motion(0, 0, 0, 0, 0, 0, 0, 0);
        }
        double mean = speedSum / motionFrames;
        double var = 0;
        for (int i = 0; i < n; i++) {
            double d = speeds[i] - mean;
            var += d * d;
        }
        double[] sorted = Arrays.copyOf(speeds, n);
        Arrays.sort(sorted);
        double p01 = sorted[(int) Math.floor(0.01 * (n - 1))];
        double p99 = sorted[(int) Math.ceil(0.99 * (n - 1))];
        double jumpP99 = 0, jumpMax = 0;
        if (jumpCount > 0) {
            double[] sortedJumps = Arrays.copyOf(jumps, jumpCount);
            Arrays.sort(sortedJumps);
            jumpP99 = sortedJumps[(int) Math.ceil(0.99 * (jumpCount - 1))] / mean;
            jumpMax = sortedJumps[jumpCount - 1] / mean;
        }
        return new Motion(motionFrames, mean, Math.sqrt(var / n) / mean, p01 / mean, p99 / mean, speedMax / mean,
                jumpP99, jumpMax);
    }

    /**
     * @param frames      frames in which the renderer drew something
     * @param avgMillis   average CPU time spent in the renderer per such frame
     * @param avgCopies   average number of block copies drawn per frame
     * @param avgVertices average number of vertices emitted per frame
     * @param motion      smoothness of the bump's motion over the same frames
     */
    public record Snapshot(long frames, double avgMillis, double avgCopies, double avgVertices, Motion motion) {
        public Snapshot(long frames, double avgMillis, double avgCopies, double avgVertices) {
            this(frames, avgMillis, avgCopies, avgVertices, new Motion(0, 0, 0, 0, 0, 0, 0, 0));
        }
    }

    /**
     * Frame-to-frame speed of the drawn bump centre, over frames in which it moved. A perfectly smooth glide at
     * constant speed has {@code cv = 0} and all ratios 1. Speeding up and braking spread the speeds out too; jerks
     * (snaps, stalls) are what makes the speed change a lot from one frame to the next ({@code jumpP99},
     * {@code jumpMax}), while a ramp over a second changes it by a tiny fraction per frame.
     *
     * @param frames    frames in which the centre moved
     * @param meanSpeed blocks per second
     * @param cv        standard deviation / mean
     * @param p01       1st percentile / mean
     * @param p99       99th percentile / mean
     * @param max       maximum / mean
     * @param jumpP99   99th percentile of |speed change between consecutive frames| / mean
     * @param jumpMax   largest speed change between consecutive frames / mean
     */
    public record Motion(long frames, double meanSpeed, double cv, double p01, double p99, double max,
                         double jumpP99, double jumpMax) {
    }
}
