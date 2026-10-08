package tremor.core.path;

import tremor.core.math.Clamp;
import java.util.Arrays;
import java.util.Objects;

import tremor.core.math.Vec3;

/**
 * Smooth route for the crawler (SPEC 5.3: "the route is smoothed, Catmull-Rom or similar, so the bump does not jerk
 * on corners"): a centripetal Catmull-Rom spline (alpha = 0.5, no cusps or self-intersections) through
 * {@code start} followed by the voxel centres of {@code path.nodes()[1..]} (the first node is replaced by the actual
 * start position, so a replan mid-motion does not jump). The end points are extended by mirroring the neighbouring
 * control point, unless a start direction is given: then the first segment is a Hermite curve leaving {@code start}
 * along it, so a replan keeps the direction of motion. Parametrized by arc length, which is measured on a lookup table
 * with enough samples per segment for an error well below 1% of the segment length.
 * <p>
 * <b>Lead-in.</b> For a single-node path, and for a start inside rock ({@code startDive}: a replan during a dive),
 * node 0 is kept and {@code start} is prepended: the lead-in {@code start -> nodes[0]} is edge {@code -1}, a dive if
 * {@code startDive}. A replan mid-dive passes a route from the dive's far end, so the rest of the crossing stays a dive.
 * <p>
 * The spline segment between control points {@code i} and {@code i + 1} belongs to the path edge {@code i} (shifted
 * by one with a lead-in). Consecutive control points closer than 1e-6 are merged (the edge between them then has no
 * length on the spline).
 * <p>
 * A path whose nodes all lie at {@code start} gives a spline of length 0 whose position is {@code start}. Immutable.
 */
public final class PathSpline {
    /** Consecutive control points closer than this are merged. */
    private static final double DUPLICATE_EPSILON = 1e-6;
    /** Knot spacing exponent: 0.5 = centripetal. */
    private static final double ALPHA = 0.5;
    /** Arc-length table intervals per segment (with the quadrature below: relative error ~1e-8). */
    private static final int SAMPLES = 32;
    private static final int NEWTON_ITERATIONS = 3;
    /** 5-point Gauss-Legendre nodes and weights on [-1, 1]. */
    private static final double[] GAUSS_X;
    private static final double[] GAUSS_W;

    static {
        double inner = Math.sqrt(5 - 2 * Math.sqrt(10.0 / 7)) / 3, outer = Math.sqrt(5 + 2 * Math.sqrt(10.0 / 7)) / 3;
        double innerW = (322 + 13 * Math.sqrt(70)) / 900, outerW = (322 - 13 * Math.sqrt(70)) / 900;
        GAUSS_X = new double[]{-outer, -inner, 0, inner, outer};
        GAUSS_W = new double[]{outerW, innerW, 128.0 / 225, innerW, outerW};
    }

    private final Path path;
    private final Vec3 start;
    private final int segments;
    /** Path edge index of each spline segment; -1 for the lead-in. */
    private final int[] edge;
    /** Whether each segment is a dive. */
    private final boolean[] dive;
    /** First dive segment at or after each segment, {@code segments} if none; {@code segments + 1} entries. */
    private final int[] nextDive;
    /** Per segment 12 cubic coefficients {@code p(u) = a u³ + b u² + c u + d}: a, b, c, d for x, then y, then z. */
    private final double[] coef;
    /** Arc length at the start of each segment, plus the total at the end. */
    private final double[] segmentStart;
    /** Per segment {@code SAMPLES + 1} arc lengths from the segment start at {@code u = k / SAMPLES}. */
    private final double[] table;

    private PathSpline(Path path, Vec3 start, int[] edge, boolean[] dive, double[] coef, double[] segmentStart,
                       double[] table) {
        this.path = path;
        this.start = start;
        this.segments = edge.length;
        this.edge = edge;
        this.dive = dive;
        this.coef = coef;
        this.segmentStart = segmentStart;
        this.table = table;
        this.nextDive = new int[segments + 1];
        nextDive[segments] = segments;
        for (int j = segments - 1; j >= 0; j--) {
            nextDive[j] = dive[j] ? j : nextDive[j + 1];
        }
    }

    /** The natural spline from {@code start}: no start direction, no dive lead-in. */
    public static PathSpline of(Vec3 start, Path path) {
        return of(start, Vec3.ZERO, path, false);
    }

    /**
     * @param startDirection direction the spline leaves {@code start} in (normalized here), or ZERO for the natural
     *                       Catmull-Rom start
     * @param startDive      {@code start} is inside rock on the way to {@code nodes[0]}: keep node 0 and make the
     *                       lead-in a dive (see the class comment)
     */
    public static PathSpline of(Vec3 start, Vec3 startDirection, Path path, boolean startDive) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(path, "path");
        Vec3 direction = Objects.requireNonNull(startDirection, "startDirection").normalize();
        // Control points with near-duplicates merged; runEnd[j] = last path node merged into point j (-1: the start
        // before node 0, with a lead-in).
        int n = path.size();
        boolean leadIn = startDive || n == 1;
        int first = leadIn ? 0 : 1;
        Vec3[] points = new Vec3[n + 1];
        int[] runEnd = new int[n + 1];
        points[0] = start;
        runEnd[0] = first - 1;
        int m = 1;
        for (int i = first; i < n; i++) {
            Vec3 p = path.point(i);
            if (p.distance(points[m - 1]) > DUPLICATE_EPSILON) {
                points[m] = p;
                runEnd[m] = i;
                m++;
            } else {
                runEnd[m - 1] = i;
            }
        }
        int segments = m - 1;
        int[] edge = Arrays.copyOf(runEnd, segments);
        boolean[] dive = new boolean[segments];
        for (int j = 0; j < segments; j++) {
            dive[j] = edge[j] < 0 ? startDive : path.dive()[edge[j]];
        }
        double[] coef = new double[segments * 12];
        double[] segmentStart = new double[segments + 1];
        double[] table = new double[segments * (SAMPLES + 1)];
        PathSpline spline = new PathSpline(path, start, edge, dive, coef, segmentStart, table);
        for (int j = 0; j < segments; j++) {
            Vec3 p1 = points[j];
            Vec3 p2 = points[j + 1];
            Vec3 p0 = j > 0 ? points[j - 1] : p1.add(p1.sub(p2));
            Vec3 p3 = j + 2 < m ? points[j + 2] : p2.add(p2.sub(p1));
            spline.fitSegment(j, p0, p1, p2, p3, j == 0 && !direction.isNearZero() ? direction : null);
            int base = j * (SAMPLES + 1);
            for (int k = 0; k < SAMPLES; k++) {
                table[base + k + 1] = table[base + k] + spline.arcLength(j, (double) k / SAMPLES,
                        (double) (k + 1) / SAMPLES);
            }
            segmentStart[j + 1] = segmentStart[j] + table[base + SAMPLES];
        }
        return spline;
    }

    public Path path() {
        return path;
    }

    /** Total arc length. */
    public double length() {
        return segmentStart[segments];
    }

    /** Point at arc length {@code s}, clamped to [0, length]. */
    public Vec3 position(double s) {
        if (segments == 0) {
            return start;
        }
        int j = segmentAt(s);
        return point(j, parameterAt(j, s));
    }

    /** Unit tangent at arc length {@code s} (forward direction); for a zero-length spline {@link Vec3#ZERO}. */
    public Vec3 tangent(double s) {
        if (segments == 0) {
            return Vec3.ZERO;
        }
        int j = segmentAt(s);
        Vec3 t = derivative(j, parameterAt(j, s)).normalize();
        // At a cusp (a path turning straight back on itself) the derivative vanishes: use the segment's chord.
        return t.isNearZero() ? point(j, 1).sub(point(j, 0)).normalize() : t;
    }

    /** Vector from the start to the next control point (where the route heads first); ZERO for length 0. */
    public Vec3 startChord() {
        return segments == 0 ? Vec3.ZERO : point(0, 1).sub(start);
    }

    /**
     * Index {@code i} of the path edge {@code nodes[i] -> nodes[i+1]} that arc length {@code s} lies on, {@code -1} on
     * the lead-in. A control point belongs to the edge that starts there, the end of the spline to the last edge with
     * a length; 0 for a spline of length 0.
     */
    public int segment(double s) {
        return segments == 0 ? 0 : edge[segmentAt(s)];
    }

    /** Whether arc length {@code s} lies on a dive edge or a dive lead-in (the bump is hidden there). */
    public boolean isDive(double s) {
        return segments > 0 && dive[segmentAt(s)];
    }

    /**
     * Arc length where the next dive begins: {@code s} itself if it lies on a dive, the start of the first dive
     * segment after it otherwise, {@link Double#POSITIVE_INFINITY} if there is none or {@code s} is at or past the end.
     */
    public double nextDiveStart(double s) {
        if (!(s < length())) {
            return Double.POSITIVE_INFINITY;
        }
        double from = Math.max(s, 0);
        int j = segmentAt(from);
        int k = nextDive[j];
        return k == segments ? Double.POSITIVE_INFINITY : k == j ? from : segmentStart[k];
    }

    // ---- internals -------------------------------------------------------------------------------------------------

    /** The last segment starting at or before {@code s}. Requires {@code segments > 0}. */
    private int segmentAt(double s) {
        int lo = 0, hi = segments - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (segmentStart[mid] <= s) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** Curve parameter {@code u} of segment {@code j} at global arc length {@code s} (clamped to the segment). */
    private double parameterAt(int j, double s) {
        int base = j * (SAMPLES + 1);
        double l = Clamp.clamp(s - segmentStart[j], 0, table[base + SAMPLES]);
        int lo = 0, hi = SAMPLES - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (table[base + mid] <= l) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        double u0 = (double) lo / SAMPLES, u1 = (double) (lo + 1) / SAMPLES;
        double l0 = table[base + lo], l1 = table[base + lo + 1];
        double u = l1 > l0 ? u0 + (u1 - u0) * (l - l0) / (l1 - l0) : u0;
        // Newton on the exact arc length: the linear guess is off wherever the speed varies within the interval.
        for (int iteration = 0; iteration < NEWTON_ITERATIONS; iteration++) {
            double speed = speed(j, u);
            if (speed < 1e-12) {
                break;
            }
            double next = Clamp.clamp(u - (l0 + arcLength(j, u0, u) - l) / speed, u0, u1);
            if (Math.abs(next - u) < 1e-15) {
                break;
            }
            u = next;
        }
        return u;
    }

    /** Arc length of segment {@code j} between parameters {@code from <= to}, by Gauss-Legendre quadrature. */
    private double arcLength(int j, double from, double to) {
        double half = 0.5 * (to - from), mid = 0.5 * (to + from);
        double sum = 0;
        for (int i = 0; i < GAUSS_X.length; i++) {
            sum += GAUSS_W[i] * speed(j, mid + half * GAUSS_X[i]);
        }
        return sum * half;
    }

    /**
     * Fits segment {@code j} from {@code p1} to {@code p2}: the centripetal Catmull-Rom tangents (Barry-Goldman, in
     * knot units) at both ends, rescaled to {@code u} in [0, 1] and written as a cubic Hermite polynomial. A non-null
     * {@code startDirection} (unit) replaces the tangent at {@code p1} by {@code startDirection·|p2 - p1|}.
     */
    private void fitSegment(int j, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, Vec3 startDirection) {
        double d01 = Math.pow(p1.distance(p0), ALPHA);
        double d12 = Math.pow(p2.distance(p1), ALPHA);
        double d23 = Math.pow(p3.distance(p2), ALPHA);
        Vec3 m1 = startDirection != null ? startDirection.scale(p2.distance(p1))
                : p1.sub(p0).scale(1 / d01).sub(p2.sub(p0).scale(1 / (d01 + d12))).add(p2.sub(p1).scale(1 / d12))
                .scale(d12);
        Vec3 m2 = p2.sub(p1).scale(1 / d12).sub(p3.sub(p1).scale(1 / (d12 + d23))).add(p3.sub(p2).scale(1 / d23))
                .scale(d12);
        fitAxis(j * 12, p1.x(), p2.x(), m1.x(), m2.x());
        fitAxis(j * 12 + 4, p1.y(), p2.y(), m1.y(), m2.y());
        fitAxis(j * 12 + 8, p1.z(), p2.z(), m1.z(), m2.z());
    }

    private void fitAxis(int c, double p1, double p2, double m1, double m2) {
        coef[c] = 2 * (p1 - p2) + m1 + m2;
        coef[c + 1] = 3 * (p2 - p1) - 2 * m1 - m2;
        coef[c + 2] = m1;
        coef[c + 3] = p1;
    }

    private double cubic(int c, double u) {
        return ((coef[c] * u + coef[c + 1]) * u + coef[c + 2]) * u + coef[c + 3];
    }

    private double cubicDerivative(int c, double u) {
        return (3 * coef[c] * u + 2 * coef[c + 1]) * u + coef[c + 2];
    }

    private Vec3 point(int j, double u) {
        int c = j * 12;
        return new Vec3(cubic(c, u), cubic(c + 4, u), cubic(c + 8, u));
    }

    /** {@code |dp/du|} of segment {@code j}, without allocating. */
    private double speed(int j, double u) {
        int c = j * 12;
        double dx = cubicDerivative(c, u), dy = cubicDerivative(c + 4, u), dz = cubicDerivative(c + 8, u);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** {@code dp/du} of segment {@code j}. */
    private Vec3 derivative(int j, double u) {
        int c = j * 12;
        return new Vec3(cubicDerivative(c, u), cubicDerivative(c + 4, u), cubicDerivative(c + 8, u));
    }
}
