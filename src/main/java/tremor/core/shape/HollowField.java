package tremor.core.shape;

import java.util.List;
import java.util.Objects;

import tremor.core.math.Vec3;

/**
 * One frame of the ground of the hollow around the player (SPEC 9 phase 2): the displacement along the surface
 * normal at a point, the sum of
 * <pre>
 * breath · near(|p - player|, breathRadius) · heave(p, t)        the walls and the floor around the player heave
 *                                                               out of step ("пространство ходит ходуном")
 * + near(|p - player|, ringRadius) · (1 + crestBoost · crestShare(|p - player|))
 *       · Σ Ripple.height(ring, |p - origin|, age)              a ring from the node on every beat, its crest
 *                                                               swelling as it passes the player
 * </pre>
 * ({@link HollowShape#near}, {@link HollowShape#heave}, {@link HollowShape#crestShare}). Distances are measured in 3D,
 * so the rings climb the walls and run along the ceilings as well, each voxel moving along its own normal: in the fog
 * of the hollow the player sees a wave come from one side, swell around and under them, and run on. Every term is
 * already scaled for the moment of the frame (how far the hollow has closed, the settling after the end). Pure math,
 * immutable, allocation-free per query.
 * <p>
 * As a {@link GroundField} it is scanned in a cylinder around an anchor near the player (which follows the player,
 * {@link HollowParams#follow}) wide enough for the rings around the player wherever the player is within the follow
 * distance of the anchor; the rings show and kick up dust only around the player.
 */
public final class HollowField implements GroundField {
    private final HollowParams params;
    private final Vec3 player;
    private final Vec3 anchor;
    private final double breath;
    private final double timeSeconds;
    private final double breathReachSq;
    private final double ringReachSq;
    private final List<AwakeningField.Ring> rings;
    private final double[] ringX, ringY, ringZ;
    /** Squared radius of each ring's front: nothing moves at or beyond it. */
    private final double[] frontSq;
    /** Squared radius of each ring's tail, -1 while the train still covers the origin: nothing moves within it. */
    private final double[] tailSq;
    private final double maxHeight;

    /**
     * @param player      where the player is this frame
     * @param anchor      centre of the region scanned for the ground (near the player, see {@link HollowParams#follow})
     * @param breath      height of the heaving this frame ({@link HollowShape#breathAmplitude}, 0 for none); finite
     *                    and {@code >= 0}
     * @param timeSeconds clock of the heaving ({@link HollowShape#heave})
     * @param rings       the node's rings running this frame (inactive ones add nothing)
     * @throws IllegalArgumentException if the breath is negative or not finite
     */
    public HollowField(HollowParams params, Vec3 player, Vec3 anchor, double breath, double timeSeconds,
                       List<AwakeningField.Ring> rings) {
        this.params = Objects.requireNonNull(params, "params");
        this.player = Objects.requireNonNull(player, "player");
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        if (!(breath >= 0) || !Double.isFinite(breath)) {
            throw new IllegalArgumentException("breath must be finite and >= 0: " + breath);
        }
        this.breath = breath;
        this.timeSeconds = timeSeconds;
        this.breathReachSq = params.breathRadius() * params.breathRadius();
        this.ringReachSq = params.ringRadius() * params.ringRadius();
        this.rings = List.copyOf(rings);
        int n = this.rings.size();
        ringX = new double[n];
        ringY = new double[n];
        ringZ = new double[n];
        frontSq = new double[n];
        tailSq = new double[n];
        double max = breath;
        for (int i = 0; i < n; i++) {
            AwakeningField.Ring ring = this.rings.get(i);
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
            max += p.amplitude() * (1 - ring.ageSeconds() / p.duration()) * (1 + params.crestBoost());
        }
        this.maxHeight = max;
    }

    @Override
    public double at(double x, double y, double z) {
        double px = x - player.x(), py = y - player.y(), pz = z - player.z();
        double d2 = px * px + py * py + pz * pz;
        if (d2 >= ringReachSq && d2 >= breathReachSq) {
            return 0;
        }
        double distance = Math.sqrt(d2);
        double h = 0;
        if (breath > 0 && d2 < breathReachSq) {
            h += breath * HollowShape.near(params.breathRadius(), params.breathFade(), distance)
                    * HollowShape.heave(params, timeSeconds, x, y, z);
        }
        if (d2 < ringReachSq && ringX.length > 0) {
            double share = HollowShape.near(params.ringRadius(), params.ringFade(), distance);
            if (share > 0) {
                double sum = 0;
                for (int i = 0, n = ringX.length; i < n; i++) {
                    double dx = x - ringX[i], dy = y - ringY[i], dz = z - ringZ[i];
                    double r2 = dx * dx + dy * dy + dz * dz;
                    if (r2 >= frontSq[i] || r2 <= tailSq[i]) {
                        continue; // ahead of the front or behind the train: still
                    }
                    AwakeningField.Ring ring = rings.get(i);
                    sum += Ripple.height(ring.params(), Math.sqrt(r2), ring.ageSeconds());
                }
                if (sum != 0) {
                    h += share * (1 + params.crestBoost() * HollowShape.crestShare(params, distance)) * sum;
                }
            }
        }
        return h;
    }

    /**
     * Upper bound of {@code |at(p)|} anywhere: the heave and every running ring at full height, its crest swollen by
     * all of {@link HollowParams#crestBoost}.
     */
    @Override
    public double maxHeight() {
        return maxHeight;
    }

    /**
     * The highest heave and ring of a closed hollow (the ring with its crest swollen as it is near the player), as far
     * as they reach around the player now: the ground the player is about to walk into is baked ahead.
     */
    @Override
    public double ahead(double x, double y, double z) {
        double distance = distance(x, y, z);
        return params.breathEnd() * HollowShape.near(params.breathRadius(), params.breathFade(), distance)
                + params.ringEnd() * (1 + params.crestBoost() * HollowShape.crestShare(params, distance))
                * HollowShape.near(params.ringRadius(), params.ringFade(), distance);
    }

    /** The anchor. */
    @Override
    public Vec3 scanCenter() {
        return anchor;
    }

    /** The reach of the rings around the player plus the follow distance. */
    @Override
    public double scanRadius() {
        return params.ringRadius() + params.follow();
    }

    /** {@link HollowParams#scanHeight}. */
    @Override
    public int scanHeight() {
        return params.scanHeight();
    }

    /** The region follows the player. */
    @Override
    public boolean scanFollows() {
        return true;
    }

    /** The node's rings of this frame, as given. */
    @Override
    public List<AwakeningField.Ring> rings() {
        return rings;
    }

    /** {@code near(|p - player|, ringRadius)}: the rings show only around the player. */
    @Override
    public double ringShare(double x, double y, double z) {
        return HollowShape.near(params.ringRadius(), params.ringFade(), distance(x, y, z));
    }

    /** Nothing rises or settles as a mound in the hollow. */
    @Override
    public List<Heave> heaves() {
        return List.of();
    }

    public HollowParams params() {
        return params;
    }

    /** Where the player is this frame. */
    public Vec3 player() {
        return player;
    }

    private double distance(double x, double y, double z) {
        double dx = x - player.x(), dy = y - player.y(), dz = z - player.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
