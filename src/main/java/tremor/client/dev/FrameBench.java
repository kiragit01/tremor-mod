package tremor.client.dev;

import java.util.Arrays;
import java.util.Locale;
import tremor.client.render.RenderStats;

/**
 * One {@code bench} step: frame intervals (start of a frame to start of the next, so ticking, rendering, blit and
 * buffer swap are all included) over a fixed number of client ticks, plus {@link RenderStats} over exactly the same
 * frames. The first {@link #WARMUP_FRAMES} frames after the step starts are skipped.
 */
final class FrameBench {
    static final int WARMUP_FRAMES = 5;

    final String label;
    final int ticks;

    private int warmupLeft = WARMUP_FRAMES;
    private int ticksLeft;
    private boolean measuring;
    private boolean stopRequested;
    private boolean done;
    private boolean incomplete;
    private long frameStart;
    private long[] intervals = new long[1024];
    private int count;
    private RenderStats.Snapshot stats = new RenderStats.Snapshot(0, 0, 0, 0);

    FrameBench(String label, int ticks) {
        this.label = label;
        this.ticks = ticks;
        this.ticksLeft = ticks;
    }

    /** Called at the start of every frame ({@code RenderFrameEvent.Pre}). */
    void onFrameStart(long now) {
        if (done) {
            return;
        }
        if (!measuring) {
            if (warmupLeft-- > 0) {
                return;
            }
            measuring = true;
            frameStart = now;
            RenderStats.reset();
            return;
        }
        if (count == intervals.length) {
            intervals = Arrays.copyOf(intervals, count * 2);
        }
        intervals[count++] = now - frameStart;
        frameStart = now;
        if (stopRequested) {
            stats = RenderStats.snapshot();
            done = true;
        }
    }

    /** Called once per client tick; the window closes at the first frame start after the last tick. */
    void onTick() {
        if (measuring && !stopRequested && --ticksLeft <= 0) {
            stopRequested = true;
        }
    }

    /** Ends the bench with whatever was collected (no frames are being rendered, or the run is aborting). */
    void forceFinish() {
        if (!done) {
            stats = RenderStats.snapshot();
            incomplete = true;
            done = true;
        }
    }

    boolean isDone() {
        return done;
    }

    boolean isMeasuring() {
        return measuring;
    }

    Result result() {
        long[] sorted = Arrays.copyOf(intervals, count);
        Arrays.sort(sorted);
        long total = 0;
        for (long v : sorted) {
            total += v;
        }
        double seconds = total / 1e9;
        return new Result(label, ticks, count, seconds, total == 0 ? 0 : count / seconds,
                count == 0 ? 0 : total / 1e6 / count, percentile(sorted, 0.50), percentile(sorted, 0.95),
                percentile(sorted, 0.99), count == 0 ? 0 : sorted[count - 1] / 1e6, stats, incomplete);
    }

    /** Nearest-rank percentile in milliseconds. */
    private static double percentile(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(p * sorted.length);
        return sorted[Math.max(0, Math.min(sorted.length - 1, rank - 1))] / 1e6;
    }

    record Result(String label, int ticks, int frames, double seconds, double avgFps, double meanMs, double p50Ms,
                  double p95Ms, double p99Ms, double maxMs, RenderStats.Snapshot renderer, boolean incomplete) {
        String csvRow() {
            return String.format(Locale.ROOT, "%s,%d,%d,%.3f,%.1f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%.4f,%.1f,%.1f",
                    label.replace(',', '_'), ticks, frames, seconds, avgFps, meanMs, p50Ms, p95Ms, p99Ms, maxMs,
                    renderer.frames(), renderer.avgMillis(), renderer.avgCopies(), renderer.avgVertices());
        }
    }
}
