package tremor.client;

import tremor.core.math.Clamp;
import tremor.core.behavior.Stage;
import tremor.core.math.Vec3;
import tremor.core.shape.BumpFrame;

import java.util.Arrays;

/**
 * Smooth playback of the entity's state snapshots (SPEC 6.3: "the client interpolates"). No game classes, so it can
 * be tested without the game; {@link ClientTremor} feeds it. Not thread-safe.
 * <p>
 * Snapshots carry the server game time they were taken at. Playback runs behind the client's copy of that clock by
 * about 1.5 snapshot intervals, more if packets arrive late, so there is almost always a snapshot on either side to
 * interpolate between: position by cubic Hermite with the snapshot velocities, directions by nlerp (slerp for wide
 * angles), amplitude, phase and velocity linearly. Past the newest snapshot the position follows its velocity for at
 * most {@link #MAX_EXTRAPOLATION} ticks, then holds.
 * <p>
 * Discrete state is not blended: the stage is that of the snapshot at or before the playback time, and the ground
 * ripple (SPEC 8, ALERT) is the latest one that has started by then, so its age runs on the playback clock too.
 * <p>
 * The playback clock is smoothed: the client game time is re-synchronized by the server every second and may jump by
 * a tick, and the delay adapts to the measured lateness; both are absorbed by running the clock up to 10% faster or
 * slower for a moment instead of jumping.
 */
final class SnapshotPlayback {
    static final double TICKS_PER_SECOND = 20.0;
    /** {@link Snapshot#rippleStart} when the snapshot knows of no ripple. */
    static final long NO_RIPPLE = Long.MIN_VALUE;
    /** Snapshot spacing (ticks) assumed until measured: playback delay 3 ticks. */
    static final double DEFAULT_SPACING = 2.0;
    /** The playback delay is at least this many snapshot intervals. */
    static final double DELAY_SPACINGS = 1.5;
    /** Delay added on top of that at most to cover late packets, ticks. */
    static final double MAX_JITTER_DELAY = 5.0;
    /** Past the newest (or before the oldest) snapshot the position follows the velocity for at most this, ticks. */
    static final double MAX_EXTRAPOLATION = 2.0;
    /** The playback clock jumps instead of gliding when it is off by more than this, ticks. */
    static final double RESYNC_TICKS = 10.0;

    private static final int CAPACITY = 8;
    private static final double SPACING_SMOOTHING = 0.2;
    private static final double LATENESS_MARGIN = 0.25;
    /** How fast the remembered worst lateness is forgotten, ticks per tick. */
    private static final double LATENESS_DECAY = 0.005;
    /** Arrivals measured later than this (seconds) are not measured at all. */
    private static final double MAX_ARRIVAL_AGE = 0.5;
    /** A longer gap between two snapshots (ticks, or snapshot intervals) starts the buffer over. */
    private static final long MIN_RESTART_GAP = 40;
    private static final double RESTART_GAP_SPACINGS = 3.0;
    /** Moving further than this (blocks) beyond what the snapshot speeds allow is a teleport, not motion. */
    private static final double TELEPORT_SLACK = 4.0;
    /** Without a snapshot for this long (running ticks, or snapshot intervals) the entity is considered gone. */
    private static final double MIN_STALE_TICKS = 100;
    private static final double STALE_SPACINGS = 10;
    /** Relative speed-up of the playback clock per tick it lags behind; at most 1 so it never runs backwards. */
    private static final double CLOCK_GAIN = 1.0 / RESYNC_TICKS;
    private static final double MAX_FRAME_TICKS = 10.0;
    /** Spline tangents are capped at this many chord lengths: no loops when the velocity disagrees with the motion. */
    private static final double MAX_TANGENT_CHORDS = 3.0;
    private static final int MAX_PENDING_ARRIVALS = 4;

    /**
     * A received state.
     *
     * @param tick        server game time it was taken at
     * @param normal      unit surface normal
     * @param forward     unit direction of travel, or zero
     * @param velocity    blocks per second
     * @param phase       animation clock, seconds
     * @param stage       aggression stage
     * @param rippleStart server game time the latest ground ripple started at, {@link #NO_RIPPLE} if none
     */
    record Snapshot(long tick, Vec3 position, Vec3 normal, Vec3 forward, Vec3 velocity, double amplitude,
                    double phase, Stage stage, long rippleStart) {
        /**
         * A snapshot with normalized directions, or null if a value is unusable (non-finite, zero normal, no
         * stage).
         */
        static Snapshot of(long tick, Vec3 position, Vec3 normal, Vec3 forward, Vec3 velocity, double amplitude,
                           double phase, Stage stage, long rippleStart) {
            if (!finite(position) || !finite(normal) || !finite(forward) || !finite(velocity)
                    || !Double.isFinite(amplitude) || !Double.isFinite(phase) || stage == null) {
                return null;
            }
            Vec3 n = normal.normalize();
            if (n.isNearZero()) {
                return null;
            }
            return new Snapshot(tick, position, n, forward.normalize(), velocity, amplitude, phase, stage,
                    rippleStart);
        }

        private static boolean finite(Vec3 v) {
            return Double.isFinite(v.x()) && Double.isFinite(v.y()) && Double.isFinite(v.z());
        }
    }

    /**
     * Interpolated state.
     *
     * @param frame     bump frame
     * @param phase     animation clock, seconds
     * @param velocity  blocks per second; zero while the position holds past the snapshots
     * @param stage     stage of the snapshot at or before the playback time
     * @param rippleAge seconds since the latest ground ripple started, NaN if none has started by the playback time
     */
    record Sample(BumpFrame frame, double amplitude, double phase, Vec3 velocity, Stage stage, double rippleAge) {
    }

    private int instance;
    private final Snapshot[] snapshots = new Snapshot[CAPACITY];
    private int count;

    private double spacing = DEFAULT_SPACING;
    private boolean spacingKnown;
    /** Worst recent lateness of a snapshot against the client clock (ticks, decaying), NaN until measured. */
    private double latePeak = Double.NaN;
    private final long[] arrivalTicks = new long[MAX_PENDING_ARRIVALS];
    private final long[] arrivalNanos = new long[MAX_PENDING_ARRIVALS];
    private int arrivals;

    /** Smoothed playback time in game ticks, NaN until started. */
    private double clock = Double.NaN;
    private long clockNanos;
    private double silentTicks;

    /** Entity instance of the buffered snapshots (or of the last absent state). */
    int instance() {
        return instance;
    }

    /** Adds a snapshot of the entity received at {@code nanos} ({@link System#nanoTime()}). */
    void accept(int instance, Snapshot snap, long nanos) {
        if (count > 0 && instance != this.instance) {
            clearSnapshots();
        }
        this.instance = instance;
        if (count > 0) {
            Snapshot last = snapshots[count - 1];
            long gap = snap.tick() - last.tick();
            long restart = Math.max(MIN_RESTART_GAP, (long) Math.ceil(RESTART_GAP_SPACINGS * spacing));
            if (gap == 0) {
                snapshots[count - 1] = snap;
                return;
            }
            if (gap < 0 && gap >= -restart) {
                return; // older than what we have
            }
            if (gap < 0 || gap > restart || isTeleport(last, snap, gap)) {
                clearSnapshots();
            } else if (spacingKnown) {
                spacing += (gap - spacing) * SPACING_SMOOTHING;
            } else {
                spacing = gap;
                spacingKnown = true;
            }
        }
        if (count == CAPACITY) {
            System.arraycopy(snapshots, 1, snapshots, 0, CAPACITY - 1);
            count--;
        }
        snapshots[count++] = snap;
        silentTicks = 0;
        if (arrivals == MAX_PENDING_ARRIVALS) {
            System.arraycopy(arrivalTicks, 1, arrivalTicks, 0, MAX_PENDING_ARRIVALS - 1);
            System.arraycopy(arrivalNanos, 1, arrivalNanos, 0, MAX_PENDING_ARRIVALS - 1);
            arrivals--;
        }
        arrivalTicks[arrivals] = snap.tick();
        arrivalNanos[arrivals] = nanos;
        arrivals++;
    }

    /** The server says there is no entity. */
    void absent(int instance) {
        clearSnapshots();
        this.instance = instance;
    }

    /**
     * Advances the playback clock to this frame and returns the state at it, or null if there is nothing to show.
     * Call once per frame (more calls are harmless).
     *
     * @param gameTime client game time of the frame, ticks including the partial tick
     * @param nanos    {@link System#nanoTime()} of the frame
     * @param tickRate game ticks per second
     * @param running  false while the game time stands still (paused, frozen)
     */
    Sample sample(double gameTime, long nanos, double tickRate, boolean running) {
        double elapsed = 0;
        if (running && !Double.isNaN(clock)) {
            elapsed = Clamp.clamp((nanos - clockNanos) * 1e-9 * tickRate, 0.0, MAX_FRAME_TICKS);
        }
        clockNanos = nanos;
        measureArrivals(gameTime, nanos, tickRate, running);
        double t = advanceClock(gameTime, elapsed);
        if (count == 0) {
            return null;
        }
        silentTicks += elapsed;
        if (silentTicks > Math.max(MIN_STALE_TICKS, STALE_SPACINGS * spacing)) {
            clearSnapshots();
            return null;
        }
        return interpolate(t);
    }

    /** Current playback time (ticks), NaN before the first {@link #sample}. */
    double clock() {
        return clock;
    }

    /** Target delay of the playback behind the client game time, ticks. */
    double delay() {
        double base = DELAY_SPACINGS * spacing;
        if (Double.isNaN(latePeak)) {
            return base;
        }
        return Clamp.clamp(spacing + latePeak + LATENESS_MARGIN, base, base + MAX_JITTER_DELAY);
    }

    /** Game time of the newest buffered snapshot; only meaningful if there is one. */
    long newestTick() {
        return snapshots[count - 1].tick();
    }

    boolean isEmpty() {
        return count == 0;
    }

    void clearSnapshots() {
        Arrays.fill(snapshots, null);
        count = 0;
        arrivals = 0;
        silentTicks = 0;
    }

    /** Forgets everything, including the measured timing (new level or connection). */
    void clear() {
        clearSnapshots();
        spacing = DEFAULT_SPACING;
        spacingKnown = false;
        latePeak = Double.NaN;
        clock = Double.NaN;
    }

    private Sample interpolate(double t) {
        Snapshot first = snapshots[0], last = snapshots[count - 1];
        if (t >= last.tick() || t <= first.tick()) {
            // Outside the buffer: follow the velocity for a moment, then hold.
            boolean after = t >= last.tick();
            Snapshot s = after ? last : first;
            double offset = t - s.tick();
            double dt = after ? Math.min(offset, MAX_EXTRAPOLATION) : Math.max(offset, -MAX_EXTRAPOLATION);
            Vec3 position = s.position().add(s.velocity().scale(dt / TICKS_PER_SECOND));
            double phase = s.phase() + dt * (after ? phaseRate() : 1 / TICKS_PER_SECOND);
            Vec3 velocity = dt == offset ? s.velocity() : Vec3.ZERO; // zero once the position holds
            return new Sample(new BumpFrame(position, s.normal(), s.forward()), s.amplitude(), phase, velocity,
                    s.stage(), rippleAge(t, s.rippleStart()));
        }
        int i = count - 2;
        while (snapshots[i].tick() > t) {
            i--;
        }
        Snapshot a = snapshots[i], b = snapshots[i + 1];
        double span = b.tick() - a.tick();
        double u = (t - a.tick()) / span;
        Vec3 position = hermite(a, b, span, u);
        Vec3 normal = blend(a.normal(), b.normal(), u, a.forward().cross(a.normal()));
        Vec3 forward = blend(a.forward(), b.forward(), u, normal);
        double amplitude = a.amplitude() + (b.amplitude() - a.amplitude()) * u;
        double dp = b.phase() - a.phase();
        double phase;
        if (dp >= 0 && dp <= 4 * span / TICKS_PER_SECOND + 1) {
            phase = a.phase() + dp * u;
        } else {
            phase = b.phase() + (t - b.tick()) / TICKS_PER_SECOND; // the server clock was reset or wrapped
        }
        Vec3 velocity = a.velocity().lerp(b.velocity(), u);
        // A ripple that started after a is known from b only; a newer snapshot cannot add one that b missed.
        long rippleStart = b.rippleStart() != NO_RIPPLE && b.rippleStart() <= t ? b.rippleStart() : a.rippleStart();
        return new Sample(new BumpFrame(position, normal, forward), amplitude, phase, velocity, a.stage(),
                rippleAge(t, rippleStart));
    }

    /** Seconds from {@code start} (ticks, or {@link #NO_RIPPLE}) to playback time {@code t}, NaN if not yet begun. */
    private static double rippleAge(double t, long start) {
        return start == NO_RIPPLE || start > t ? Double.NaN : (t - start) / TICKS_PER_SECOND;
    }

    /** Cubic Hermite between two snapshots using their velocities (blocks/s) as tangents. */
    private static Vec3 hermite(Snapshot a, Snapshot b, double span, double u) {
        Vec3 p0 = a.position(), p1 = b.position();
        double limit = MAX_TANGENT_CHORDS * p0.distance(p1);
        Vec3 m0 = capLength(a.velocity().scale(span / TICKS_PER_SECOND), limit);
        Vec3 m1 = capLength(b.velocity().scale(span / TICKS_PER_SECOND), limit);
        double u2 = u * u, u3 = u2 * u;
        double h00 = 2 * u3 - 3 * u2 + 1, h10 = u3 - 2 * u2 + u, h01 = -2 * u3 + 3 * u2, h11 = u3 - u2;
        return new Vec3(
                h00 * p0.x() + h10 * m0.x() + h01 * p1.x() + h11 * m1.x(),
                h00 * p0.y() + h10 * m0.y() + h01 * p1.y() + h11 * m1.y(),
                h00 * p0.z() + h10 * m0.z() + h01 * p1.z() + h11 * m1.z());
    }

    private static Vec3 capLength(Vec3 v, double max) {
        double len = v.length();
        return len > max ? v.scale(max / len) : v;
    }

    /**
     * Direction between unit vectors {@code a} and {@code b}: nlerp up to 90 degrees apart, slerp beyond, and a turn
     * about {@code axis} (made perpendicular to {@code a}) when they are opposite. Zero only if both are zero.
     */
    static Vec3 blend(Vec3 a, Vec3 b, double u, Vec3 axis) {
        double dot = a.dot(b);
        if (dot >= 0) {
            return a.lerp(b, u).normalize();
        }
        double angle = Math.acos(Math.max(-1, dot));
        double sin = Math.sin(angle);
        if (sin > 1e-3) {
            return a.scale(Math.sin((1 - u) * angle) / sin).add(b.scale(Math.sin(u * angle) / sin)).normalize();
        }
        Vec3 k = axis.projectOnPlane(a).normalize();
        if (k.isNearZero()) {
            k = a.anyPerpendicular();
        }
        double phi = angle * u;
        return a.scale(Math.cos(phi)).add(k.cross(a).scale(Math.sin(phi))).normalize();
    }

    /** Phase advance per tick between the two newest snapshots, or real time if that looks wrong. */
    private double phaseRate() {
        if (count >= 2) {
            Snapshot a = snapshots[count - 2], b = snapshots[count - 1];
            double rate = (b.phase() - a.phase()) / (b.tick() - a.tick());
            if (rate >= 0 && rate <= 4 / TICKS_PER_SECOND) {
                return rate;
            }
        }
        return 1 / TICKS_PER_SECOND;
    }

    /** Moves the playback clock on by {@code elapsed} ticks, gliding toward {@code gameTime - delay}. */
    private double advanceClock(double gameTime, double elapsed) {
        double target = gameTime - delay();
        if (Double.isNaN(clock) || Math.abs(target - clock) > RESYNC_TICKS) {
            clock = target;
        } else {
            double error = target - (clock + elapsed);
            clock += elapsed * (1 + CLOCK_GAIN * Clamp.clamp(error, -RESYNC_TICKS, RESYNC_TICKS));
        }
        return clock;
    }

    /**
     * How late (against the client game time) the snapshots received since the last frame arrived. Measured here,
     * after the client ticks of this frame ran and with any game time the server synchronized in the meantime.
     */
    private void measureArrivals(double gameTime, long nanos, double tickRate, boolean running) {
        for (int i = 0; i < arrivals; i++) {
            double age = (nanos - arrivalNanos[i]) * 1e-9;
            if (!running || age > MAX_ARRIVAL_AGE) {
                continue;
            }
            double lateness = Clamp.clamp(gameTime - age * tickRate - arrivalTicks[i], -RESYNC_TICKS, RESYNC_TICKS);
            latePeak = Double.isNaN(latePeak) ? lateness : Math.max(lateness, latePeak - LATENESS_DECAY * spacing);
        }
        arrivals = 0;
    }

    private static boolean isTeleport(Snapshot a, Snapshot b, long gap) {
        double speed = Math.max(a.velocity().length(), b.velocity().length()) / TICKS_PER_SECOND;
        double reach = TELEPORT_SLACK + 2 * speed * gap;
        return a.position().distanceSquared(b.position()) > reach * reach;
    }
}
