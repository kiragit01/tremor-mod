package tremor.client.render;

import tremor.core.shape.BumpParams;
import tremor.core.shape.BumpShape;
import tremor.core.shape.Ripple;
import tremor.core.shape.RippleParams;

/**
 * How far from the bump centre the renderer has to look: only where a voxel's height can reach
 * {@link BumpShape#RENDER_THRESHOLD}. That is usually far less than {@link BumpParams#influenceRadius()}, which also
 * spans the trail behind it (it only pulls the back down). While a ground ripple runs (SPEC 8, ALERT) it reaches further.
 * Pure math, no game classes.
 */
final class RenderReach {
    /** Halvings of the {@link #rippleReach} bracket: it ends far below a millionth of a block. */
    private static final int BISECTION_STEPS = 50;

    private RenderReach() {
    }

    /**
     * Distance from the bump centre, in its tangent plane, beyond which {@code h < RENDER_THRESHOLD} whatever the
     * jitter does; {@code 0} if {@code h} can never reach the threshold.
     * <p>
     * With {@code bump} the Gaussian of {@link BumpShape} (its trail only pulls it down, by a factor in
     * {@code [1-k, 1]}) and the noise in {@code [-1, 1]}, {@code h <= P·bump}: for {@code A >= 0},
     * {@code P = A(1+ε)}; for {@code A < 0}, {@code P = |A|·max(0, ε-(1-k))} (only the jitter lifts an inverted bump,
     * most easily where its trail has pulled it in). So {@code h >= T} needs {@code bump >= T/P}, and at distance
     * {@code r} {@code bump <= exp(-r²/s²)} with {@code s = max(σfront, σback, σside)}. (The bound keeps a second,
     * trail term {@code Q·exp(-(r-L)²/st²)}, now always 0.)
     */
    static double reach(BumpParams params) {
        Bound bound = Bound.of(params);
        double total = bound.main() + bound.trail();
        if (!(total >= BumpShape.RENDER_THRESHOLD)) {
            return 0;
        }
        double spread = Math.sqrt(Math.log(total / BumpShape.RENDER_THRESHOLD));
        double r = 0;
        if (bound.main() > 0) {
            r = spread * bound.spread();
        }
        if (bound.trail() > 0) {
            r = Math.max(r, bound.lag() + spread * bound.trailSpread());
        }
        return r;
    }

    /**
     * Distance from the bump centre, in its tangent plane, beyond which the bump plus the {@code ripple} centred on
     * it stays below {@code RENDER_THRESHOLD} whatever the jitter and the age of the ripple, capped at
     * {@link Ripple#maxRadius} (the ripple is zero beyond, so further out only {@link #reach} counts); {@code 0} if
     * the two together can never reach the threshold.
     * <p>
     * The ripple is non-zero at distance {@code r} only once its front has passed ({@code age > r/speed}), when its
     * envelope {@code A·(1 - age/duration)} is below {@code A·(1 - r/M)} with {@code M = speed·duration}. Added to
     * the bound of the bump from {@link #reach}, {@code P·exp(-r²/s²) + Q·exp(-max(0, r-L)²/st²)}, that gives a
     * bound that does not grow with {@code r}; this is where it last reaches the threshold (found by bisection, so the
     * bound is below the threshold at the returned distance).
     */
    static double rippleReach(BumpParams params, RippleParams ripple) {
        Bound bump = Bound.of(params);
        double max = Ripple.maxRadius(ripple);
        if (bound(bump, ripple, max) >= BumpShape.RENDER_THRESHOLD) {
            return max;
        }
        if (!(bound(bump, ripple, 0) >= BumpShape.RENDER_THRESHOLD)) {
            return 0;
        }
        double lo = 0, hi = max;
        for (int i = 0; i < BISECTION_STEPS; i++) {
            double mid = 0.5 * (lo + hi);
            if (bound(bump, ripple, mid) >= BumpShape.RENDER_THRESHOLD) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return hi;
    }

    /**
     * Collection radius (distance of a voxel centre from the bump centre, as the surface collector measures it) that
     * holds every voxel {@link #reach} lets rise, and while a ripple runs every voxel {@link #rippleReach} lets rise:
     * the reach, capped by {@link BumpParams#influenceRadius()}, or the ripple's reach if that is further, plus
     * {@code slack} for what changes between collections, widened for voxels up to {@code normalBand} off the tangent
     * plane, and rounded up to a multiple of {@code step}.
     *
     * @param ripple the ground ripple running now, null if none
     */
    static double collectRadius(BumpParams params, RippleParams ripple, double slack, double normalBand,
                                double step) {
        double r = Math.min(params.influenceRadius(), reach(params));
        if (ripple != null) {
            r = Math.max(r, rippleReach(params, ripple));
        }
        r += slack;
        return Math.ceil(Math.sqrt(r * r + normalBand * normalBand) / step) * step;
    }

    /** Upper bound of the bump plus the ripple at distance {@code r} (see {@link #rippleReach}). */
    private static double bound(Bound bump, RippleParams ripple, double r) {
        double rest = 1 - r / Ripple.maxRadius(ripple);
        return bump.at(r) + (rest > 0 ? ripple.amplitude() * rest : 0);
    }

    /**
     * The bound {@code P·exp(-r²/s²) + Q·exp(-max(0, r-L)²/st²)} of the bump's height at distance {@code r}, see
     * {@link #reach}.
     *
     * @param main        {@code P}
     * @param trail       {@code Q}, 0 without a dip
     * @param spread      {@code s}
     * @param trailSpread {@code st}
     * @param lag         {@code L}
     */
    private record Bound(double main, double trail, double spread, double trailSpread, double lag) {
        static Bound of(BumpParams params) {
            double amplitude = params.amplitude(), jitter = params.jitter(), depth = params.trailDepth();
            double main = amplitude >= 0 ? amplitude * (1 + jitter) : -amplitude * Math.max(0, jitter - (1 - depth));
            return new Bound(main, 0,
                    Math.max(params.sigmaFront(), Math.max(params.sigmaBack(), params.sigmaSide())),
                    Math.max(params.trailSigma(), params.sigmaSide()), params.trailLag());
        }

        double at(double r) {
            double h = 0;
            if (main > 0) {
                double u = r / spread;
                h += main * Math.exp(-u * u);
            }
            if (trail > 0) {
                double u = Math.max(0, r - lag) / trailSpread;
                h += trail * Math.exp(-u * u);
            }
            return h;
        }
    }
}
