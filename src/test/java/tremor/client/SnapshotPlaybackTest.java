package tremor.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleFunction;

import org.junit.jupiter.api.Test;

import tremor.core.behavior.Stage;
import tremor.core.math.Vec3;

class SnapshotPlaybackTest {
    private static final double SPEED = 3.0; // blocks per second
    private static final long SECOND = 1_000_000_000L;

    /** Straight run along +x at {@link #SPEED}; argument: server game time in ticks. */
    private static final DoubleFunction<Vec3> LINE = t -> new Vec3(SPEED * t / 20, 64, 0);
    private static final DoubleFunction<Vec3> LINE_VELOCITY = t -> new Vec3(SPEED, 0, 0);
    /** Circle of radius 6 around the origin at {@link #SPEED}. */
    private static final double RADIUS = 6;
    private static final DoubleFunction<Vec3> CIRCLE = t -> {
        double a = SPEED * t / 20 / RADIUS;
        return new Vec3(RADIUS * Math.cos(a), 64, RADIUS * Math.sin(a));
    };
    private static final DoubleFunction<Vec3> CIRCLE_VELOCITY = t -> {
        double a = SPEED * t / 20 / RADIUS;
        return new Vec3(-SPEED * Math.sin(a), 0, SPEED * Math.cos(a));
    };

    private record Frame(long nanos, double clock, double delay, long newest, Vec3 position) {
    }

    /**
     * Server sends a snapshot every {@code interval} ticks; each arrives {@code latency} plus up to {@code jitter}
     * ticks late (in order, like TCP). The client game time follows the server with an offset that the once-a-second
     * time synchronization sets anew, varying by up to {@code syncJump} ticks. Frames at about {@code fps}, with
     * irregular spacing.
     */
    private static List<Frame> simulate(DoubleFunction<Vec3> path, DoubleFunction<Vec3> velocity, int interval,
                                        double latency, double jitter, int syncJump, double fps, double seconds,
                                        long seed) {
        Random random = new Random(seed);
        SnapshotPlayback playback = new SnapshotPlayback();
        double serverPhase = random.nextDouble() * 0.05; // real seconds of server tick 0
        long startTick = 1000;
        List<double[]> pending = new ArrayList<>(); // {tick, arrival seconds}
        double lastArrival = 0;
        for (long k = startTick; k < startTick + (long) (seconds * 20) + 40; k += interval) {
            double sent = serverPhase + (k - startTick) / 20.0;
            double arrival = Math.max(lastArrival, sent + (latency + random.nextDouble() * jitter) / 20.0);
            lastArrival = arrival;
            pending.add(new double[]{k, arrival});
        }
        List<Frame> frames = new ArrayList<>();
        double offset = -0.5;
        double nextSync = 0;
        int next = 0;
        double t = 0;
        while (t < seconds) {
            t += (0.8 + 0.4 * random.nextDouble()) / fps;
            if (t >= nextSync) {
                offset = -0.5 + random.nextInt(syncJump + 1);
                nextSync += 1.0;
            }
            while (next < pending.size() && pending.get(next)[1] <= t) {
                long tick = (long) pending.get(next)[0];
                playback.accept(7, SnapshotPlayback.Snapshot.of(tick, path.apply(tick), Vec3.UNIT_Y, Vec3.UNIT_X,
                        velocity.apply(tick), 2.0, tick / 20.0, Stage.DORMANT, SnapshotPlayback.NO_RIPPLE),
                        (long) (pending.get(next)[1] * SECOND));
                next++;
            }
            double gameTime = startTick + (t - serverPhase) * 20 + offset;
            long nanos = (long) (t * SECOND);
            SnapshotPlayback.Sample sample = playback.sample(gameTime, nanos, 20, true);
            if (sample != null) {
                frames.add(new Frame(nanos, playback.clock(), playback.delay(), playback.newestTick(),
                        sample.frame().center()));
            }
        }
        return frames;
    }

    /** Speed between consecutive frames, relative to the true speed. */
    private static double[] relativeSpeedRange(List<Frame> frames, int from) {
        double min = Double.MAX_VALUE, max = 0;
        for (int i = Math.max(1, from); i < frames.size(); i++) {
            Frame a = frames.get(i - 1), b = frames.get(i);
            double speed = a.position().distance(b.position()) / ((b.nanos() - a.nanos()) / (double) SECOND);
            min = Math.min(min, speed / SPEED);
            max = Math.max(max, speed / SPEED);
        }
        return new double[]{min, max};
    }

    private static int firstFrameAfter(List<Frame> frames, double seconds) {
        long start = frames.getFirst().nanos();
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).nanos() - start >= seconds * SECOND) {
                return i;
            }
        }
        return frames.size();
    }

    @Test
    void steadyMotionIsSmoothDespiteJitterAndClockSync() {
        for (long seed = 1; seed <= 20; seed++) {
            List<Frame> frames = simulate(LINE, LINE_VELOCITY, 2, 0.3, 1.0, 1, 144, 10, seed);
            double[] range = relativeSpeedRange(frames, firstFrameAfter(frames, 1.0));
            assertTrue(range[0] > 0.8 && range[1] < 1.2,
                    "seed " + seed + ": speed between frames " + range[0] + ".." + range[1] + " of the true speed");
            for (Frame f : frames) {
                assertEquals(0, f.position().z(), 1e-9);
                assertEquals(64, f.position().y(), 1e-9);
            }
        }
    }

    @Test
    void noSnapOnTheFirstSnapshots() {
        for (long seed = 1; seed <= 20; seed++) {
            List<Frame> frames = simulate(LINE, LINE_VELOCITY, 2, 0.3, 1.0, 1, 144, 3, seed);
            double nominal = SPEED / 144 * 1.2;
            for (int i = 1; i < frames.size(); i++) {
                double step = frames.get(i - 1).position().distance(frames.get(i).position());
                assertTrue(step < 1.5 * nominal, "seed " + seed + " frame " + i + ": step " + step);
            }
        }
    }

    @Test
    void adaptiveDelayKeepsPlaybackInsideTheBuffer() {
        for (long seed = 1; seed <= 20; seed++) {
            List<Frame> frames = simulate(LINE, LINE_VELOCITY, 2, 0.3, 1.0, 1, 144, 10, seed);
            int starved = 0, counted = 0;
            for (int i = firstFrameAfter(frames, 2.0); i < frames.size(); i++) {
                counted++;
                if (frames.get(i).clock() > frames.get(i).newest()) {
                    starved++;
                }
            }
            assertTrue(starved <= counted / 100, "seed " + seed + ": " + starved + " of " + counted
                    + " frames past the newest snapshot");
        }
    }

    @Test
    void curvedPathIsFollowedClosely() {
        List<Frame> frames = simulate(CIRCLE, CIRCLE_VELOCITY, 2, 0.3, 1.0, 1, 144, 10, 42);
        for (Frame f : frames) {
            double r = Math.hypot(f.position().x(), f.position().z());
            assertEquals(RADIUS, r, 0.01);
        }
        double[] range = relativeSpeedRange(frames, firstFrameAfter(frames, 1.0));
        assertTrue(range[0] > 0.8 && range[1] < 1.2, "speed " + range[0] + ".." + range[1]);
    }

    @Test
    void delayFollowsTheSnapshotSpacing() {
        List<Frame> frames = simulate(LINE, LINE_VELOCITY, 2, 0, 0, 0, 60, 3, 3);
        assertEquals(3.0, frames.getLast().delay(), 0.3);
        frames = simulate(LINE, LINE_VELOCITY, 5, 0, 0, 0, 60, 5, 3);
        assertEquals(7.5, frames.getLast().delay(), 0.6);
        double[] range = relativeSpeedRange(frames, firstFrameAfter(frames, 1.0));
        assertTrue(range[0] > 0.8 && range[1] < 1.2, "speed " + range[0] + ".." + range[1]);
    }

    private static SnapshotPlayback.Snapshot snap(long tick, Vec3 position, Vec3 forward, Vec3 velocity) {
        return SnapshotPlayback.Snapshot.of(tick, position, Vec3.UNIT_Y, forward, velocity, 2.0, tick / 20.0,
                Stage.DORMANT, SnapshotPlayback.NO_RIPPLE);
    }

    @Test
    void holdsWhilePausedAndAfterTheNewestSnapshot() {
        SnapshotPlayback p = new SnapshotPlayback();
        p.accept(1, snap(100, new Vec3(0, 0, 0), Vec3.UNIT_X, new Vec3(3, 0, 0)), 0);
        p.accept(1, snap(102, new Vec3(0.3, 0, 0), Vec3.UNIT_X, new Vec3(3, 0, 0)), 0);
        SnapshotPlayback.Sample first = p.sample(103, 0, 20, true);
        assertNotNull(first);
        SnapshotPlayback.Sample paused = p.sample(103, SECOND, 20, false);
        assertEquals(first.frame().center(), paused.frame().center());
        // Long after the newest snapshot: extrapolated by at most 2 ticks, then held.
        SnapshotPlayback.Sample late = null;
        for (int i = 1; i <= 40; i++) {
            late = p.sample(103 + i * 0.5, SECOND + i * SECOND / 40, 20, true);
        }
        assertNotNull(late);
        assertEquals(0.3 + 3.0 * 2 / 20, late.frame().center().x(), 1e-9);
    }

    @Test
    void teleportIsNotInterpolated() {
        SnapshotPlayback p = new SnapshotPlayback();
        p.accept(1, snap(100, new Vec3(0, 0, 0), Vec3.UNIT_X, Vec3.ZERO), 0);
        p.sample(100, 0, 20, true);
        p.accept(1, snap(102, new Vec3(50, 0, 0), Vec3.UNIT_X, Vec3.ZERO), SECOND / 10);
        for (int i = 0; i <= 20; i++) {
            SnapshotPlayback.Sample s = p.sample(102 + i * 0.25, SECOND / 10 + i * SECOND / 80, 20, true);
            assertNotNull(s);
            assertEquals(50, s.frame().center().x(), 1e-9);
        }
    }

    @Test
    void newInstanceAndAbsentStateResetTheBuffer() {
        SnapshotPlayback p = new SnapshotPlayback();
        p.accept(1, snap(100, new Vec3(0, 0, 0), Vec3.UNIT_X, Vec3.ZERO), 0);
        p.accept(1, snap(102, new Vec3(0.1, 0, 0), Vec3.UNIT_X, Vec3.ZERO), 0);
        p.accept(2, snap(104, new Vec3(1, 0, 0), Vec3.UNIT_X, Vec3.ZERO), 0);
        assertEquals(2, p.instance());
        SnapshotPlayback.Sample s = p.sample(103, 0, 20, true);
        assertEquals(1, s.frame().center().x(), 1e-9);
        p.absent(2);
        assertTrue(p.isEmpty());
        assertNull(p.sample(104, SECOND / 20, 20, true));
    }

    @Test
    void olderSnapshotsAreIgnored() {
        SnapshotPlayback p = new SnapshotPlayback();
        p.accept(1, snap(100, new Vec3(0, 0, 0), Vec3.UNIT_X, Vec3.ZERO), 0);
        p.accept(1, snap(102, new Vec3(0.1, 0, 0), Vec3.UNIT_X, Vec3.ZERO), 0);
        p.accept(1, snap(98, new Vec3(-9, 0, 0), Vec3.UNIT_X, Vec3.ZERO), 0);
        assertEquals(102, p.newestTick());
        SnapshotPlayback.Sample s = p.sample(100, 0, 20, true); // playback at 97: before the oldest kept
        assertEquals(0, s.frame().center().x(), 1e-9);
    }

    @Test
    void staleEntityDisappears() {
        SnapshotPlayback p = new SnapshotPlayback();
        p.accept(1, snap(100, new Vec3(0, 0, 0), Vec3.UNIT_X, Vec3.ZERO), 0);
        assertNotNull(p.sample(100, 0, 20, true));
        SnapshotPlayback.Sample s = null;
        for (int i = 1; i <= 60; i++) { // 6 s of running game time without a snapshot
            s = p.sample(100 + i * 2, i * SECOND / 10, 20, true);
        }
        assertNull(s);
        assertTrue(p.isEmpty());
    }

    @Test
    void reversedDirectionTurnsInsteadOfBreaking() {
        SnapshotPlayback p = new SnapshotPlayback();
        p.accept(1, snap(100, new Vec3(0, 0, 0), Vec3.UNIT_X, Vec3.ZERO), 0);
        p.accept(1, snap(102, new Vec3(0, 0, 0), Vec3.UNIT_X.negate(), Vec3.ZERO), 0);
        p.sample(100, 0, 20, true); // playback starts 3 ticks behind
        boolean sideways = false;
        for (int i = 1; i <= 32; i++) {
            SnapshotPlayback.Sample s = p.sample(100 + i * 0.25, i * SECOND / 80, 20, true);
            Vec3 f = s.frame().forward();
            assertEquals(1, f.length(), 1e-9);
            assertEquals(0, f.y(), 1e-9);
            sideways |= Math.abs(f.z()) > 0.9;
        }
        assertTrue(sideways, "the forward direction should turn through the side");
    }

    @Test
    void blendHandlesOppositeAndWideAngles() {
        Vec3 up = Vec3.UNIT_Y, down = Vec3.UNIT_Y.negate();
        Vec3 mid = SnapshotPlayback.blend(up, down, 0.5, Vec3.UNIT_X);
        assertEquals(1, mid.length(), 1e-9);
        assertEquals(0, mid.dot(up), 1e-9);
        Vec3 wide = SnapshotPlayback.blend(Vec3.UNIT_X, new Vec3(-1, 0, 1).normalize(), 0.5, up);
        assertEquals(1, wide.length(), 1e-9);
        assertEquals(Math.cos(Math.toRadians(67.5)), wide.dot(Vec3.UNIT_X), 1e-9);
        assertFalse(SnapshotPlayback.blend(Vec3.UNIT_X, Vec3.UNIT_Z, 0.3, up).isNearZero());
    }

    @Test
    void unusableSnapshotsAreRejected() {
        long none = SnapshotPlayback.NO_RIPPLE;
        assertNull(SnapshotPlayback.Snapshot.of(0, Vec3.ZERO, Vec3.ZERO, Vec3.UNIT_X, Vec3.ZERO, 1, 0, Stage.ALERT,
                none));
        assertNull(SnapshotPlayback.Snapshot.of(0, new Vec3(Double.NaN, 0, 0), Vec3.UNIT_Y, Vec3.UNIT_X, Vec3.ZERO,
                1, 0, Stage.ALERT, none));
        assertNull(SnapshotPlayback.Snapshot.of(0, Vec3.ZERO, Vec3.UNIT_Y, Vec3.UNIT_X, Vec3.ZERO, 1, 0, null, none));
        SnapshotPlayback.Snapshot s = SnapshotPlayback.Snapshot.of(0, Vec3.ZERO, new Vec3(0, 2, 0), new Vec3(3, 0, 0),
                Vec3.ZERO, 1, 0, Stage.ALERT, none);
        assertNotNull(s);
        assertEquals(Vec3.UNIT_Y, s.normal());
        assertEquals(Vec3.UNIT_X, s.forward());
    }

    /** A standing entity at tick {@code tick} with this stage and ripple. */
    private static SnapshotPlayback.Snapshot state(long tick, Stage stage, long rippleStart) {
        return SnapshotPlayback.Snapshot.of(tick, new Vec3(0, 64, 0), Vec3.UNIT_Y, Vec3.UNIT_X, Vec3.ZERO, 2.0,
                tick / 20.0, stage, rippleStart);
    }

    /**
     * Steps 80 frames per second of real time with the client game time from tick 103 over the snapshots accepted
     * so far (all at real time 0), collecting the playback clock and the sample of each frame.
     */
    private static List<Object[]> play(SnapshotPlayback p, int frames) {
        List<Object[]> out = new ArrayList<>();
        for (int i = 0; i < frames; i++) {
            SnapshotPlayback.Sample s = p.sample(103 + i * 0.25, i * SECOND / 80, 20, true);
            out.add(new Object[]{p.clock(), s});
        }
        return out;
    }

    @Test
    void rippleAgeRunsOnThePlaybackClock() {
        SnapshotPlayback p = new SnapshotPlayback();
        long none = SnapshotPlayback.NO_RIPPLE;
        p.accept(1, state(100, Stage.ALERT, none), 0);
        p.accept(1, state(102, Stage.ALERT, 101), 0); // b reveals a ripple that started between a and b
        p.accept(1, state(104, Stage.ALERT, 101), 0);
        boolean before = false, during = false;
        for (Object[] f : play(p, 80)) {
            double clock = (double) f[0];
            double age = ((SnapshotPlayback.Sample) f[1]).rippleAge();
            if (clock < 101) {
                before = true;
                assertTrue(Double.isNaN(age), "not started yet at " + clock + ": " + age);
            } else {
                during = true;
                assertEquals((clock - 101) / 20, age, 1e-9);
            }
        }
        assertTrue(before && during);
        // Paused: the age stands still with the clock.
        double clock = p.clock();
        double age = p.sample(103 + 80 * 0.25, 5 * SECOND, 20, false).rippleAge();
        assertEquals(clock, p.clock());
        assertEquals((clock - 101) / 20, age, 1e-9);
    }

    @Test
    void aNewerSnapshotWithoutRippleEndsItAtItsTick() {
        SnapshotPlayback p = new SnapshotPlayback();
        p.accept(1, state(100, Stage.ALERT, 90), 0);
        p.accept(1, state(102, Stage.ALERT, SnapshotPlayback.NO_RIPPLE), 0);
        p.accept(1, state(104, Stage.ALERT, SnapshotPlayback.NO_RIPPLE), 0);
        boolean running = false, ended = false;
        for (Object[] f : play(p, 80)) {
            double clock = (double) f[0];
            double age = ((SnapshotPlayback.Sample) f[1]).rippleAge();
            if (clock < 102) {
                running = true;
                assertEquals((clock - 90) / 20, age, 1e-9);
            } else {
                ended = true;
                assertTrue(Double.isNaN(age), "ended at 102, still " + age + " at " + clock);
            }
        }
        assertTrue(running && ended);
    }

    @Test
    void stageSwitchesAtTheSnapshotThatReportsIt() {
        SnapshotPlayback p = new SnapshotPlayback();
        p.accept(1, state(100, Stage.DORMANT, SnapshotPlayback.NO_RIPPLE), 0);
        p.accept(1, state(102, Stage.ALERT, SnapshotPlayback.NO_RIPPLE), 0);
        p.accept(1, state(104, Stage.HUNTING, SnapshotPlayback.NO_RIPPLE), 0);
        boolean[] seen = new boolean[Stage.values().length];
        for (Object[] f : play(p, 80)) {
            double clock = (double) f[0];
            Stage stage = ((SnapshotPlayback.Sample) f[1]).stage();
            Stage expected = clock < 102 ? Stage.DORMANT : clock < 104 ? Stage.ALERT : Stage.HUNTING;
            assertEquals(expected, stage, "at " + clock);
            seen[stage.ordinal()] = true;
        }
        assertTrue(seen[Stage.DORMANT.ordinal()] && seen[Stage.ALERT.ordinal()] && seen[Stage.HUNTING.ordinal()]);
    }

    @Test
    void velocityIsInterpolatedAndZeroOnceTheBumpHolds() {
        SnapshotPlayback p = new SnapshotPlayback();
        p.accept(1, snap(100, new Vec3(0, 0, 0), Vec3.UNIT_X, new Vec3(3, 0, 0)), 0);
        p.accept(1, snap(102, new Vec3(0.2, 0, 0), Vec3.UNIT_X, new Vec3(1, 0, 0)), 0);
        boolean between = false, held = false;
        for (Object[] f : play(p, 80)) {
            double clock = (double) f[0];
            Vec3 v = ((SnapshotPlayback.Sample) f[1]).velocity();
            if (clock > 100 && clock < 102) {
                between = true;
                assertEquals(3 - 2 * (clock - 100) / 2, v.x(), 1e-9);
            } else if (clock > 102 + SnapshotPlayback.MAX_EXTRAPOLATION) {
                held = true;
                assertEquals(Vec3.ZERO, v);
            } else if (clock >= 102) {
                assertEquals(1, v.x(), 1e-9); // following the newest velocity for a moment
            }
        }
        assertTrue(between && held);
    }
}
