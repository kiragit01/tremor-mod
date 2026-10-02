package tremor.core.motion;

import java.util.Arrays;
import java.util.Objects;

import tremor.core.math.Vec3;
import tremor.core.path.Path;
import tremor.core.path.PathSpline;

/**
 * Kinematic state of the entity on the skin of the world and its motion along a {@link PathSpline} (SPEC 4, 5.5).
 * <p>
 * Following a path: arc-length position {@code s} advances by the current speed; the speed moves toward
 * {@code maxSpeed} by at most {@code acceleration·dt} per tick and is limited so the crawler can brake to a stop
 * exactly at the end ({@code v <= sqrt(2·a·remaining)}). {@code position = spline.position(s)},
 * {@code velocity = tangent·speed}. When it reaches the end it stops and {@link #arrived()} becomes true. Without a
 * path it stays where it is with speed 0. A replan ({@link #follow(Path)}) keeps the motion continuous.
 * <p>
 * The surface normal turns toward {@code normals.normalAt(position)} with exponential smoothing of the angle: each
 * tick by the fraction {@code 1 - exp(-dt/τ)} of it, about {@code n × target} (τ = 0 snaps), so floor to wall is a
 * smooth turn. A target opposite to the normal turns it about {@code n × forward} (any perpendicular if that is
 * degenerate). While diving the normal snaps to the target: the bump is hidden, and a dive joins opposite faces. A
 * ZERO target normal keeps the current one. The forward direction is the direction of motion while moving, and keeps
 * its last value while standing (it is never ZERO), unless {@link #face} turns it in place.
 * <p>
 * The amplitude follows {@code params.amplitude()} with the same kind of smoothing, but stays within
 * {@code |A|·clamp(d / lead, 0, 1)}, {@code d} the arc length to the next dive and
 * {@code lead = 3·τ·max(speed, maxSpeed)}: the bump sinks over the lead, is gone during a dive (SPEC 5.4) and rises
 * only after the exit. Down to that bound it sinks by at most {@code |A|·(1 - exp(-dt/τ))} per tick (the smoothing's
 * own rate from full height), so a dive that appears close ahead (a replan) does not make the bump jump. Phase is the
 * animation clock in seconds and simply accumulates dt.
 * <p>
 * {@link #speed()} is the speed of the last tick's move. The braking uses the discrete form of the limit (the tick's
 * own move plus a stop by {@code a·dt} per tick fits into the remaining length), so the speed never changes by more
 * than {@code a·dt} per tick even at the end: the tick that lands on the end still moves, the next one reports the
 * stop and {@link #arrived()}. {@link #brake()} stops as soon as that limit allows, along the current path.
 * Deterministic; not thread-safe.
 */
public final class Crawler {
    @FunctionalInterface
    public interface NormalSource {
        /** Smoothed surface normal at (the voxel nearest to) the point, or ZERO if unknown. */
        Vec3 normalAt(Vec3 position);
    }

    /** Remaining arc length below which the end counts as reached. */
    private static final double ARRIVE_EPSILON = 1e-9;
    /** A replan takes over a moving crawler only if it heads within 45° of the motion. */
    private static final double TAKE_OVER_COS = Math.sqrt(0.5);
    /** Length of the sinking before a dive: amplitude time constants of travel at cruise speed. */
    private static final double DIVE_LEAD = 3;
    /** Below this {@code |n × target|} (with {@code n·target < 0}) the target counts as opposite. */
    private static final double OPPOSITE_SIN = 1e-6;
    /** A facing direction whose part in the tangent plane is shorter than this (of its length) is along the normal. */
    private static final double ALONG_NORMAL_SIN = 1e-6;

    private Vec3 position;
    private Vec3 normal;
    private Vec3 forward;
    private double speed;
    private double amplitude;
    private double phase;
    private PathSpline spline;
    private double progress;
    private boolean arrived;
    /** Route to start once stopped: a replan that could not take over while moving. Null if none. */
    private Path pending;
    /** Braking to a stop on the current path, which is dropped then ({@link #brake()}). */
    private boolean braking;
    /** Acceleration and dt of the last tick: what a replan between ticks can count on. */
    private double lastAcceleration;
    private double lastDt;
    /** Direction to turn {@link #forward} toward while standing ({@link #face}); null if none. */
    private Vec3 facing;

    /** @param normal must be non-zero; normalized */
    public Crawler(Vec3 position, Vec3 normal) {
        this(position, normal, Vec3.ZERO, 0, 0);
    }

    /** Restores a saved state (no path). A zero {@code forward} is replaced by a perpendicular of the normal. */
    public Crawler(Vec3 position, Vec3 normal, Vec3 forward, double amplitude, double phase) {
        this.position = Objects.requireNonNull(position, "position");
        this.normal = requireDirection(normal);
        Vec3 f = Objects.requireNonNull(forward, "forward").normalize();
        this.forward = f.isNearZero() ? this.normal.anyPerpendicular() : f;
        this.amplitude = amplitude;
        this.phase = phase;
    }

    public Vec3 position() {
        return position;
    }

    public Vec3 normal() {
        return normal;
    }

    /** Unit direction of travel (perpendicular to the normal is not guaranteed); never ZERO. */
    public Vec3 forward() {
        return forward;
    }

    public Vec3 velocity() {
        return speed == 0 ? Vec3.ZERO : forward.scale(speed);
    }

    public double speed() {
        return speed;
    }

    public double amplitude() {
        return amplitude;
    }

    public double phase() {
        return phase;
    }

    /**
     * Starts following {@code spline} from its beginning as it is, keeping the current speed: no continuity handling,
     * for tests and callers that built the spline from the current state themselves (prefer {@link #follow(Path)}).
     * The position becomes the spline's start. Drops a {@link #pendingPath()}, ends a {@link #brake()} and clears
     * {@link #facing()}.
     */
    public void follow(PathSpline spline) {
        this.spline = Objects.requireNonNull(spline, "spline");
        this.progress = 0;
        this.arrived = false;
        this.pending = null;
        this.braking = false;
        this.facing = null;
        this.position = spline.position(0);
    }

    /**
     * Follows a (re)planned route from where the crawler is, without a jerk (SPEC 5.5: the velocity is pulled toward
     * the path direction with limited acceleration):
     * <ul>
     * <li>standing (or without a path): the route starts here at once;</li>
     * <li>moving, and the route heads within 45° of the motion (or of where the current path was heading anyway, as
     * on a replan at a corner) and is long enough to brake on: it takes over at once, the spline leaving the current
     * position along {@link #forward()}, the speed kept;</li>
     * <li>otherwise (a sharp turn, a reversal, a route shorter than the stopping distance): the crawler brakes along
     * its current path by {@code a·dt} per tick, keeping the route as {@link #pendingPath()}; once stopped it joins
     * the route over the edges of its current path, never cutting across the terrain (see {@link #startPending}),
     * and follows the route from rest.</li>
     * </ul>
     * While diving the route is expected to start at the dive's far end; the way there stays a dive. The speed never
     * changes by more than {@code a·dt} per tick across a replan (the decision uses the last tick's acceleration and
     * dt). Ends a {@link #brake()} and clears {@link #facing()}; a {@link #face} after it, while the route is pending,
     * survives the start of the route, so it turns the front at the route's end as after a route taken over at once.
     */
    public void follow(Path path) {
        Objects.requireNonNull(path, "path");
        facing = null;
        boolean dive = diving();
        if (speed == 0 || spline == null || arrived) {
            follow(PathSpline.of(position, Vec3.ZERO, path, dive));
            return;
        }
        PathSpline next = PathSpline.of(position, forward, path, dive);
        if (canTakeOver(next)) {
            follow(next);
        } else {
            pending = path;
            braking = false;
        }
    }

    /** Drops the path; the crawler stops where it is (speed 0) at once. */
    public void stop() {
        spline = null;
        progress = 0;
        arrived = false;
        pending = null;
        braking = false;
        speed = 0;
    }

    /**
     * Stops as soon as the acceleration limit allows (SPEC 5.5), without leaving the current path: the speed drops by
     * {@code a·dt} per tick along it (or faster where the path ends sooner), and once it is 0 the path is
     * dropped as by {@link #stop()}. Never inside rock: while the stop would fall on a dive (also when the crawler
     * is in one), it goes on at the usual speed, and brakes once the stop falls after the dive. Drops a
     * {@link #pendingPath()} and clears {@link #facing()} (call {@link #face} after it); a later {@link #follow} ends
     * the braking. Without a path, at its end, or standing outside a dive, it stops at once.
     */
    public void brake() {
        pending = null;
        facing = null;
        if (spline == null || arrived || speed == 0 && !diving()) {
            stop();
            return;
        }
        braking = true;
    }

    /**
     * Turns the front of the bump toward {@code direction} while standing (SPEC 8: an ALERT entity freezes and turns
     * toward a sound); null clears it. On every {@link #tick} that leaves the crawler without a path to follow (none,
     * or arrived; the tick that arrives or ends a braking included) and not braking, after the normal has turned,
     * {@link #forward()} turns toward the projection of the direction onto the tangent plane of the current normal,
     * by the fraction {@code 1 - exp(-dt/τ)} of the angle like the normal ({@code τ = normalSmoothingSeconds}, 0
     * snaps). A projection opposite to the forward direction turns it about the normal (right-handed); a direction
     * along the normal (no projection) leaves it as it is. The crawler stays in place: {@link #velocity()} remains
     * ZERO. While a path is followed or braked on the direction is kept but has no
     * effect; {@link #follow} and {@link #brake()} clear it, {@link #stop()} and {@link #setPosition} keep it, and so
     * does the start of a {@link #pendingPath()} once the crawler has stopped for it.
     */
    public void face(Vec3 direction) {
        facing = direction;
    }

    /** The direction set by {@link #face}, or null if none. */
    public Vec3 facing() {
        return facing;
    }

    /** True while braking to a stop after {@link #brake()}. */
    public boolean braking() {
        return braking;
    }

    /**
     * The path being followed (while braking for a {@link #pendingPath()} or after {@link #brake()}, the one braked
     * on), or null.
     */
    public PathSpline spline() {
        return spline;
    }

    /** The route that starts once the crawler has stopped (see {@link #follow(Path)}), or null. */
    public Path pendingPath() {
        return pending;
    }

    /** Arc length travelled on the current spline. */
    public double progress() {
        return progress;
    }

    /** True once the end of the current path has been reached (until the next {@link #follow}); false without one. */
    public boolean arrived() {
        return arrived;
    }

    /** True while the crawler is on a dive segment of its path (not at its very end). */
    public boolean diving() {
        return spline != null && progress < spline.length() && spline.isDive(progress);
    }

    /** Teleports (e.g. on spawn / goto from far away): drops the path, speed 0. A zero normal keeps the current one. */
    public void setPosition(Vec3 position, Vec3 normal) {
        this.position = Objects.requireNonNull(position, "position");
        Vec3 n = Objects.requireNonNull(normal, "normal").normalize();
        if (!n.isNearZero()) {
            this.normal = n;
        }
        stop();
    }

    /** Advances the simulation by {@code dt} seconds; {@code dt <= 0} does nothing. */
    public void tick(double dt, MotionParams params, NormalSource normals) {
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(normals, "normals");
        if (!(dt > 0)) {
            return;
        }
        lastAcceleration = params.acceleration();
        lastDt = dt;
        phase += dt;
        move(dt, params);
        double k = blend(dt, params.normalSmoothingSeconds());
        turnNormal(normals.normalAt(position), k);
        if (facing != null && (spline == null || arrived) && !braking) {
            turnForward(k);
        }
        updateAmplitude(dt, params);
    }

    private void move(double dt, MotionParams params) {
        if (spline == null || arrived) {
            speed = 0;
            return;
        }
        double a = params.acceleration();
        double length = spline.length();
        double remaining = Math.max(0, length - progress);
        if (pending != null) {
            // The speed respects the braking limit of the current path, so braking by a·dt always fits on it.
            speed = Math.min(Math.max(0, speed - a * dt), brakingSpeed(remaining, a, dt));
            advance(dt);
            if (speed == 0) {
                startPending();
            }
            return;
        }
        if (braking) {
            double v = Math.max(0, speed - a * dt);
            // Where braking on from here ends. Past the end of the path, the end (a node) stops it first.
            double stopAt = progress + stoppingDistance(v, a, dt);
            if (stopAt < length && spline.isDive(stopAt)) {
                v = cruise(params, dt); // never stop inside the rock: on through the dive, braking after it
            }
            speed = Math.min(v, brakingSpeed(remaining, a, dt));
            advance(dt);
            if (speed == 0) {
                stop();
            }
            return;
        }
        if (remaining <= ARRIVE_EPSILON) {
            progress = length;
            position = spline.position(length);
            arrived = true;
            speed = 0;
            return;
        }
        speed = Math.min(cruise(params, dt), brakingSpeed(remaining, a, dt));
        advance(dt);
    }

    /** This tick's speed on the way to the cruise speed: changed toward it by at most {@code a·dt}. */
    private double cruise(MotionParams params, double dt) {
        double max = params.maxSpeed(), step = params.acceleration() * dt;
        return speed < max ? Math.min(speed + step, max) : Math.max(speed - step, max);
    }

    private void advance(double dt) {
        progress = Math.min(progress + speed * dt, spline.length());
        position = spline.position(progress);
        if (speed > 0) {
            Vec3 t = spline.tangent(progress);
            if (!t.isNearZero()) {
                forward = t;
            }
        }
    }

    /** Whether {@code next} (built from here along {@link #forward}) can replace the current path at this speed. */
    private boolean canTakeOver(PathSpline next) {
        if (speed - lastAcceleration * lastDt > brakingSpeed(next.length(), lastAcceleration, lastDt)) {
            return false; // too short to stop on
        }
        Vec3 chord = next.startChord();
        Vec3 heading = chord.normalize();
        if (heading.isNearZero()) {
            return true; // already at its end, and slow enough to stop within this tick
        }
        double turn = forward.dot(heading);
        if (turn > TAKE_OVER_COS) {
            return true;
        }
        // Where the current path heads over the same distance: following a turn it was about to make is no turn.
        Vec3 ahead = spline.position(Math.min(progress + chord.length(), spline.length())).sub(position).normalize();
        return turn > 0 && ahead.dot(heading) > TAKE_OVER_COS;
    }

    /**
     * Starts the pending route where the crawler stopped: on edge {@code to} of the current path, from its node
     * {@code to} (or, for {@code to = -1}, from the start of the lead-in) to node {@code to + 1}, the node ahead. The
     * crawler joins the route over real edges, so it never cuts across the terrain (as straight through the air in
     * front of a floor/wall corner, or through the rock of a ledge):
     * <ul>
     * <li>walking back from the node ahead over the nodes of the current path, the first one the route goes through
     * is the join; the crawler goes on to the node ahead if that is the join, else back over the edges it passed down
     * to the join (their dive flags carry over), and follows the route after it. However far braking took it, and
     * also when the route starts behind the edge it came on (its search started earlier);</li>
     * <li>a route that heads back over the edge the crawler is on, from the node ahead, joins at the start of that
     * edge: the crawler turns where it stopped, not at the node ahead;</li>
     * <li>a route through none of them is reached from the path node nearest to its start, by one more (skin)
     * edge.</li>
     * </ul>
     * Keeps {@link #facing()}.
     */
    private void startPending() {
        Path route = pending;
        pending = null;
        Path old = spline.path();
        int to = spline.segment(progress);
        // A spline of length 0 (one node, where the crawler is) reports edge 0: its only node counts as ahead.
        int ahead = Math.min(to + 1, old.size() - 1);
        int join = -1, at = -1;
        for (int i = ahead; i >= 0 && at < 0; i--) {
            join = i;
            at = indexOf(route, old.node(i));
        }
        if (at >= 0 && join == ahead && to >= 0 && to < ahead && at + 1 < route.size()
                && route.node(at + 1) == old.node(to)) {
            join = to;
            at++;
        }
        if (at < 0) {
            join = nearest(old, ahead, route.point(0));
        }
        // PathSpline.of replaces the first node by the position: it is the start of the edge the crawler is on.
        PathBuilder way = new PathBuilder(ahead - join + 3 + route.size());
        boolean leadInDive = false;
        if (join < ahead) {
            // Back over the edges passed (then to = ahead - 1 >= 0).
            way.start(old.node(ahead));
            for (int i = to; i >= join; i--) {
                way.add(old.dive()[i], old.node(i));
            }
        } else if (to >= 0 && to < ahead) {
            way.start(old.node(to));
            way.add(old.dive()[to], old.node(ahead));
        } else if (to < 0 && diving()) {
            // On a dive lead-in: PathSpline keeps node 0 and makes the way there a dive again.
            leadInDive = true;
            way.start(old.node(0));
        } else {
            // On a skin lead-in, whose start is no node (node 0 twice stands for it), or at the only node.
            way.start(old.node(ahead));
            if (to < 0) {
                way.add(false, old.node(0));
            }
        }
        if (at < 0) {
            way.add(false, route.node(0));
            at = 0;
        }
        for (int i = at + 1; i < route.size(); i++) {
            way.add(route.dive()[i - 1], route.node(i));
        }
        // A facing set while the route was pending is meant for the end of the route: the start keeps it.
        Vec3 keep = facing;
        follow(PathSpline.of(position, Vec3.ZERO, way.build(route.complete()), leadInDive));
        facing = keep;
    }

    /** Index of the node of {@code path} up to {@code last} nearest to {@code point}; the later one on a tie. */
    private static int nearest(Path path, int last, Vec3 point) {
        int best = last;
        double bestDistance = path.point(last).distanceSquared(point);
        for (int i = last - 1; i >= 0; i--) {
            double d = path.point(i).distanceSquared(point);
            if (d < bestDistance) {
                best = i;
                bestDistance = d;
            }
        }
        return best;
    }

    /** Nodes and dive flags of a path, appended one edge at a time. */
    private static final class PathBuilder {
        private final long[] nodes;
        private final boolean[] dive;
        private int size;

        PathBuilder(int capacity) {
            nodes = new long[capacity];
            dive = new boolean[capacity];
        }

        void start(long node) {
            nodes[size++] = node;
        }

        /** Appends the edge from the last node to {@code node}. */
        void add(boolean diveEdge, long node) {
            dive[size - 1] = diveEdge;
            nodes[size++] = node;
        }

        Path build(boolean complete) {
            return new Path(Arrays.copyOf(nodes, size), Arrays.copyOf(dive, size - 1), complete);
        }
    }

    private static int indexOf(Path path, long node) {
        for (int i = 0; i < path.size(); i++) {
            if (path.node(i) == node) {
                return i;
            }
        }
        return -1;
    }

    /** Turns the normal toward {@code target} by the fraction {@code k} of the angle between them (a slerp). */
    private void turnNormal(Vec3 target, double k) {
        if (target == null) {
            return;
        }
        Vec3 t = target.normalize();
        if (t.isNearZero()) {
            return;
        }
        if (k >= 1 || diving()) {
            normal = t;
            return;
        }
        double cos = normal.dot(t);
        Vec3 axis = normal.cross(t);
        double sin = axis.length();
        if (sin < OPPOSITE_SIN) {
            if (cos > 0) {
                normal = t;
                return;
            }
            // Opposite: n × t has no direction (and blending would stay put). Tip over the direction of travel.
            axis = normal.cross(forward);
            if (axis.lengthSquared() < 1e-6) {
                axis = normal.anyPerpendicular();
            }
        }
        normal = rotate(normal, axis.normalize(), Math.atan2(sin, cos) * k);
    }

    /** Turns the forward direction toward the tangent part of {@link #facing} by the fraction {@code k} of the turn. */
    private void turnForward(double k) {
        Vec3 d = facing.normalize();
        Vec3 t = d.projectOnPlane(normal);
        if (t.length() < ALONG_NORMAL_SIN) {
            return; // along the normal (or ZERO): no direction to turn to
        }
        t = t.normalize();
        if (k >= 1) {
            forward = t;
            return;
        }
        double cos = forward.dot(t);
        Vec3 axis = forward.cross(t);
        double sin = axis.length();
        if (sin < OPPOSITE_SIN) {
            if (cos > 0) {
                forward = t;
                return;
            }
            axis = normal; // opposite: turn about the normal, which keeps the forward direction in the tangent plane
        }
        forward = rotate(forward, axis.normalize(), Math.atan2(sin, cos) * k);
    }

    /** Rotates {@code v} about the unit {@code axis} by {@code angle} radians (Rodrigues' formula). */
    private static Vec3 rotate(Vec3 v, Vec3 axis, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        return v.scale(cos).add(axis.cross(v).scale(sin)).add(axis.scale(axis.dot(v) * (1 - cos))).normalize();
    }

    private void updateAmplitude(double dt, MotionParams params) {
        double full = params.amplitude();
        double tau = params.amplitudeSmoothingSeconds();
        double k = blend(dt, tau);
        double next = amplitude + (full - amplitude) * k;
        double bound = Math.abs(full) * visibility(params);
        double step = tau > 0 ? Math.abs(full) * k : Double.POSITIVE_INFINITY;
        if (next > bound) {
            next = Math.max(bound, Math.min(next, amplitude - step));
        } else if (next < -bound) {
            next = Math.min(-bound, Math.max(next, amplitude + step));
        }
        amplitude = next;
    }

    /** Share of the amplitude that may show: 0 while diving, rising linearly to 1 over the lead before a dive. */
    private double visibility(MotionParams params) {
        if (spline == null || arrived) {
            return 1;
        }
        if (diving()) {
            return 0;
        }
        double toDive = spline.nextDiveStart(progress) - progress;
        double lead = DIVE_LEAD * params.amplitudeSmoothingSeconds() * Math.max(speed, params.maxSpeed());
        return lead > 0 ? Math.clamp(toDive / lead, 0, 1) : toDive > 0 ? 1 : 0;
    }

    /**
     * Highest speed {@code v} for this tick from which the crawler can still stop by the end: moving {@code v·dt} now
     * and then {@code v - a·dt}, {@code v - 2a·dt}, ... covers {@code (v² + a·dt·v) / 2a}, which must not exceed the
     * remaining length. Always {@code <= sqrt(2·a·remaining)}.
     */
    private static double brakingSpeed(double remaining, double a, double dt) {
        double adt = a * dt;
        return 0.5 * (Math.sqrt(adt * adt + 8 * a * remaining) - adt);
    }

    /**
     * Arc length of a stop from speed {@code v}, this tick's move included: {@code v·dt}, then {@code (v - a·dt)·dt},
     * and so on while positive (the discrete braking summed up exactly).
     */
    private static double stoppingDistance(double v, double a, double dt) {
        if (!(v > 0)) {
            return 0;
        }
        double adt = a * dt;
        double ticks = Math.ceil(v / adt);
        return dt * (ticks * v - adt * ticks * (ticks - 1) / 2);
    }

    /** Exponential smoothing weight for one tick: {@code 1 - exp(-dt/τ)}, 1 (snap) for {@code τ = 0}. */
    private static double blend(double dt, double tau) {
        return tau > 0 ? 1 - Math.exp(-dt / tau) : 1;
    }

    private static Vec3 requireDirection(Vec3 v) {
        Vec3 n = Objects.requireNonNull(v, "normal").normalize();
        if (n.isNearZero()) {
            throw new IllegalArgumentException("normal must be non-zero: " + v);
        }
        return n;
    }
}
