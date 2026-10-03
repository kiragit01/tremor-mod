package tremor.core.shape;

import java.util.List;
import java.util.Objects;

import tremor.core.math.Vec3;

/**
 * One frame of the ground of an Awakening zone in the real world (SPEC 9, phase 1, and the hill a victor comes out of
 * after the hollow): the displacement along the surface normal at a point, the sum of
 * <pre>
 * breath · zoneFade(p) · swell(p)            the zone breathes, fading out at its edge (AwakeningShape#zoneFade), in
 *                                            broad uneven swells (AwakeningShape#swell) so that flat ground heaves
 * + hill · exp(-|p - focus|²/σ²)             the hill under the swallowed player
 * + Σ Ripple.height(ring, |p - origin|, age)  a ring per step
 * </pre>
 * Distances to the hill and to the rings are measured in 3D: on a floor that is the distance along the ground, and in
 * a cave the hill closes the passage from all sides and a ring climbs the walls once it reaches them, each voxel
 * moving along its own normal. Every term is already scaled for the moment of the frame (phase progress, strength of
 * the step, release after the end). Pure math, immutable, allocation-free per query.
 * <p>
 * As a {@link GroundField} it is scanned as the zone plus the reach of the step rings around its centre, and
 * nothing of it follows anybody; the rings show in full wherever they run; a hill that rises or settles
 * ({@code hillSpeed}) shakes dust off its flanks.
 */
public final class AwakeningField implements GroundField {
    /** A ring running out from a step, as it is drawn this frame. */
    public record Ring(Vec3 origin, double ageSeconds, RippleParams params) {
        public Ring {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(params, "params");
        }
    }

    private final AwakeningParams params;
    private final Vec3 center;
    private final double radius;
    private final double breath;
    private final double peakBreath;
    private final Vec3 focus;
    private final double hill;
    /** The hill as a {@link GroundField.Heave} while it rises or settles, else none. */
    private final List<Heave> heaves;
    /** Beyond this squared distance from the focus the hill is below a thousandth of its peak and is left out. */
    private final double hillCutSq;
    private final List<Ring> rings;
    private final double[] ringX, ringY, ringZ;
    /** Squared radius of each ring's front: nothing moves at or beyond it. */
    private final double[] frontSq;
    /** Squared radius of each ring's tail, -1 while the train still covers the origin: nothing moves within it. */
    private final double[] tailSq;
    private final double maxHeight;

    /**
     * @param center     centre of the zone
     * @param radius     horizontal radius of the zone
     * @param breath     height of the breathing well inside the zone this frame ({@link AwakeningShape#breath}, 0 for
     *                   none); must be finite and {@code >= 0}
     * @param peakBreath highest the breathing will get from now on (for baking ahead what will rise); {@code >= 0}
     * @param focus      where the hill rises
     * @param hill       peak of the hill this frame ({@link AwakeningShape#hillPeak}, 0 for none); {@code >= 0}
     * @param rings      the step rings running this frame (inactive ones add nothing)
     * @throws IllegalArgumentException if a height or the radius is negative or not finite
     */
    public AwakeningField(AwakeningParams params, Vec3 center, double radius, double breath, double peakBreath,
                          Vec3 focus, double hill, List<Ring> rings) {
        this(params, center, radius, breath, peakBreath, focus, hill, 0, rings);
    }

    /**
     * The same, with a hill that rises or settles.
     *
     * @param hillSpeed how fast the peak of the hill moves this frame, blocks per second (above 0 rising, below 0
     *                  settling): its flanks shake dust off ({@link #heaves}); must be finite
     * @throws IllegalArgumentException if a height or the radius is negative or not finite, or the speed not finite
     */
    public AwakeningField(AwakeningParams params, Vec3 center, double radius, double breath, double peakBreath,
                          Vec3 focus, double hill, double hillSpeed, List<Ring> rings) {
        this.params = Objects.requireNonNull(params, "params");
        this.center = Objects.requireNonNull(center, "center");
        this.focus = Objects.requireNonNull(focus, "focus");
        requireNonNegative("radius", radius);
        requireNonNegative("breath", breath);
        requireNonNegative("peakBreath", peakBreath);
        requireNonNegative("hill", hill);
        this.radius = radius;
        this.breath = breath;
        this.peakBreath = peakBreath;
        this.hill = hill;
        if (!Double.isFinite(hillSpeed)) {
            throw new IllegalArgumentException("hillSpeed must be finite: " + hillSpeed);
        }
        this.heaves = hillSpeed == 0 ? List.of() : List.of(new Heave(focus, params.hillSigma(), hillSpeed));
        double cut = params.hillSigma() * Math.sqrt(Math.log(1000));
        this.hillCutSq = cut * cut;
        this.rings = List.copyOf(rings);
        int n = this.rings.size();
        ringX = new double[n];
        ringY = new double[n];
        ringZ = new double[n];
        frontSq = new double[n];
        tailSq = new double[n];
        double max = breath + hill;
        for (int i = 0; i < n; i++) {
            Ring ring = this.rings.get(i);
            RippleParams p = ring.params();
            ringX[i] = ring.origin().x();
            ringY[i] = ring.origin().y();
            ringZ[i] = ring.origin().z();
            if (!Ripple.active(p, ring.ageSeconds())) {
                frontSq[i] = 0; // no point is closer than 0: the ring adds nothing
                tailSq[i] = -1;
                continue;
            }
            double front = p.speed() * ring.ageSeconds();
            double tail = front - p.waves() * p.wavelength();
            frontSq[i] = front * front;
            tailSq[i] = tail > 0 ? tail * tail : -1;
            max += p.amplitude() * (1 - ring.ageSeconds() / p.duration());
        }
        this.maxHeight = max;
    }

    /** Displacement along the surface normal at the point. */
    @Override
    public double at(double x, double y, double z) {
        double h = 0;
        if (breath > 0) {
            double dx = x - center.x(), dz = z - center.z();
            h += breath * AwakeningShape.zoneFade(params, radius, Math.sqrt(dx * dx + dz * dz), y - center.y())
                    * AwakeningShape.swell(x, y, z);
        }
        if (hill > 0) {
            double dx = x - focus.x(), dy = y - focus.y(), dz = z - focus.z();
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < hillCutSq) {
                h += AwakeningShape.hill(hill, params.hillSigma(), d2);
            }
        }
        for (int i = 0, n = ringX.length; i < n; i++) {
            double dx = x - ringX[i], dy = y - ringY[i], dz = z - ringZ[i];
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 >= frontSq[i] || d2 <= tailSq[i]) {
                continue; // ahead of the front or behind the train: still
            }
            Ring ring = rings.get(i);
            h += Ripple.height(ring.params(), Math.sqrt(d2), ring.ageSeconds());
        }
        return h;
    }

    /** Upper bound of {@code |at(p)|} anywhere: the breath, the hill and every running ring at full height. */
    @Override
    public double maxHeight() {
        return maxHeight;
    }

    /** {@link #peakBreath}: the breathing grows over the build-up, the rest comes and goes too fast to bake ahead. */
    @Override
    public double ahead(double x, double y, double z) {
        return peakBreath(x, y, z);
    }

    /**
     * Highest the breathing alone will lift the point from now on: {@code peakBreath·zoneFade(p)}. Ground where this
     * reaches the render threshold is going to rise.
     */
    public double peakBreath(double x, double y, double z) {
        double dx = x - center.x(), dz = z - center.z();
        return peakBreath * AwakeningShape.zoneFade(params, radius, Math.sqrt(dx * dx + dz * dz), y - center.y());
    }

    public AwakeningParams params() {
        return params;
    }

    public Vec3 center() {
        return center;
    }

    public double radius() {
        return radius;
    }

    /**
     * Horizontal distance from the centre within which the field can move the ground: the zone plus the farthest a
     * step ring runs out from a step at its edge ({@link Ripple#maxRadius} of {@link AwakeningParams#stepRipple}).
     * The hill rises under a player inside the zone.
     */
    public double reach() {
        return radius + Ripple.maxRadius(params.stepRipple());
    }

    /** The step rings of this frame, as given. */
    @Override
    public List<Ring> rings() {
        return rings;
    }

    /** The zone's centre. */
    @Override
    public Vec3 scanCenter() {
        return center;
    }

    /** {@link #reach}. */
    @Override
    public double scanRadius() {
        return reach();
    }

    /** {@link AwakeningParams#verticalReach}. */
    @Override
    public int scanHeight() {
        return params.verticalReach();
    }

    /** The zone stays where it started. */
    @Override
    public boolean scanFollows() {
        return false;
    }

    /** The rings run at their full height everywhere. */
    @Override
    public double ringShare(double x, double y, double z) {
        return 1;
    }

    /**
     * The hill while it rises or settles ({@code hillSpeed != 0}), as far out as it stands at {@code 1/e} of its peak
     * ({@link AwakeningParams#hillSigma}); none otherwise.
     */
    @Override
    public List<Heave> heaves() {
        return heaves;
    }

    private static void requireNonNegative(String name, double value) {
        if (!(value >= 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite and >= 0: " + value);
        }
    }
}
