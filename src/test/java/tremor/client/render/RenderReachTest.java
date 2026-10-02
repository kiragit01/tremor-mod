package tremor.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import tremor.core.math.Vec3;
import tremor.core.shape.BumpFrame;
import tremor.core.shape.BumpParams;
import tremor.core.shape.BumpShape;
import tremor.core.shape.Ripple;
import tremor.core.shape.RippleParams;

class RenderReachTest {
    private static final double T = BumpShape.RENDER_THRESHOLD;
    private static final BumpParams DEFAULTS = BumpParams.defaults();
    /** Floor bump moving along +x: local a is +x, b (side) is +z. */
    private static final BumpFrame FLOOR = BumpFrame.of(new Vec3(10.25, 64, -7.5), Vec3.UNIT_Y, Vec3.UNIT_X);

    /** Larger of the bump and trail Gaussians at local (a, b): what {@link BumpShape} fades the jitter with. */
    private static double envelope(BumpParams p, double a, double b) {
        double side = b * b / (p.sigmaSide() * p.sigmaSide());
        double sigmaA = a >= 0 ? p.sigmaFront() : p.sigmaBack();
        double bump = Math.exp(-(a * a / (sigmaA * sigmaA) + side));
        if (p.trailDepth() == 0) {
            return bump;
        }
        double t = (a + p.trailLag()) / p.trailSigma();
        return Math.max(bump, Math.exp(-(t * t + side)));
    }

    /** Highest height any jitter value can give at local (a, b) of {@link #FLOOR}. */
    private static double highest(BumpParams p, double a, double b) {
        Vec3 c = FLOOR.center();
        double base = BumpShape.height(p, FLOOR, c.x() + a, c.y(), c.z() + b);
        return base + p.jitter() * Math.abs(p.amplitude()) * envelope(p, a, b);
    }

    /** Farthest grid point (polar, around the centre) where the bump can reach the threshold; -1 if none. */
    private static double farthestRaised(BumpParams p, double maxR, int steps) {
        double farthest = -1;
        for (int i = 0; i <= steps; i++) {
            double r = maxR * i / steps;
            for (int j = 0; j < 360; j += 2) {
                double angle = Math.toRadians(j);
                if (highest(p, r * Math.cos(angle), r * Math.sin(angle)) >= T) {
                    farthest = Math.max(farthest, r);
                }
            }
        }
        return farthest;
    }

    private static BumpParams randomParams(Random random) {
        double amplitude = (random.nextDouble() * 2 - 1) * 5;
        double depth = random.nextInt(4) == 0 ? 0 : random.nextDouble() * 1.2;
        double jitter = random.nextInt(4) == 0 ? 0 : random.nextDouble() * 1.5;
        return new BumpParams(amplitude, 0.5 + 3.5 * random.nextDouble(), 0.5 + 3.5 * random.nextDouble(),
                0.5 + 3.5 * random.nextDouble(), 6 * random.nextDouble(), 0.5 + 3.5 * random.nextDouble(),
                depth, jitter);
    }

    @Test
    void defaultsReachOnlyTheMainBump() {
        // ε < k: the trailing dip never rises, only the main bump (widest σ = σback) counts.
        double expected = 2.6 * Math.sqrt(Math.log(2.0 * 1.06 / T));
        assertEquals(expected, RenderReach.reach(DEFAULTS), 1e-12);
        assertTrue(RenderReach.reach(DEFAULTS) < 0.4 * DEFAULTS.influenceRadius());
    }

    @Test
    void envelopeMatchesTheShape() {
        // Guards highest(): the jitter of BumpShape never exceeds ε·|A|·envelope.
        Random random = new Random(21);
        for (int i = 0; i < 20_000; i++) {
            BumpParams p = i < 5000 ? DEFAULTS : randomParams(random);
            double a = random.nextGaussian() * 6, b = random.nextGaussian() * 4;
            Vec3 c = FLOOR.center();
            double x = c.x() + a, y = c.y() + random.nextGaussian(), z = c.z() + b;
            double t = random.nextDouble() * 500;
            double diff = BumpShape.height(p, FLOOR, x, y, z, t) - BumpShape.height(p, FLOOR, x, y, z);
            assertTrue(Math.abs(diff) <= p.jitter() * Math.abs(p.amplitude()) * envelope(p, a, b) + 1e-12,
                    p + " at a=" + a + " b=" + b);
        }
    }

    @Test
    void nothingBeyondTheReachCanRise() {
        Random random = new Random(22);
        int raised = 0;
        for (int i = 0; i < 120; i++) {
            BumpParams p = i == 0 ? DEFAULTS : randomParams(random);
            double reach = RenderReach.reach(p);
            double maxR = 1.3 * Math.max(reach, p.influenceRadius()) + 1;
            double farthest = farthestRaised(p, maxR, 120);
            assertTrue(farthest <= reach + 1e-9, p + ": rises at " + farthest + ", reach " + reach);
            if (farthest >= 0) {
                raised++;
            }
        }
        assertTrue(raised > 60, "most random bumps should rise somewhere: " + raised);
    }

    @Test
    void jitteredHeightBeyondTheReachStaysBelowTheThreshold() {
        Random random = new Random(23);
        for (int i = 0; i < 200; i++) {
            BumpParams p = randomParams(random);
            double reach = RenderReach.reach(p);
            BumpFrame frame = BumpFrame.of(
                    new Vec3(random.nextGaussian() * 100, random.nextGaussian() * 50, random.nextGaussian() * 100),
                    new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()),
                    new Vec3(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()));
            Vec3 c = frame.center(), n = frame.normal(), f = frame.forward(), s = frame.side();
            for (int k = 0; k < 2000; k++) {
                double r = reach * (1 + random.nextDouble() * 0.5) + 1e-6;
                double angle = random.nextDouble() * 2 * Math.PI;
                double along = (random.nextDouble() * 2 - 1) * 3;
                Vec3 q = c.add(f.scale(r * Math.cos(angle))).add(s.scale(r * Math.sin(angle))).add(n.scale(along));
                double h = BumpShape.height(p, frame, q.x(), q.y(), q.z(), random.nextDouble() * 500);
                assertTrue(h < T, p + " rises to " + h + " at " + r + ", reach " + reach);
            }
        }
    }

    @Test
    void reachIsCloseToWhereTheBumpRises() {
        // Without a dip the widest side of the bump rises exactly as far as the reach.
        BumpParams noDip = new BumpParams(2.0, 1.6, 2.6, 2.2, 4.0, 3.0, 0, 0.06);
        double reach = RenderReach.reach(noDip);
        assertTrue(farthestRaised(noDip, reach * 1.2, 600) >= 0.98 * reach);
        // The dip pulls the back in, so the defaults rise as far as the side: still within a block of the reach.
        reach = RenderReach.reach(DEFAULTS);
        assertTrue(farthestRaised(DEFAULTS, reach * 1.2, 600) >= reach - 1);
    }

    @Test
    void trailCountsOnlyWhenTheJitterCanLiftIt() {
        BumpParams calm = new BumpParams(2.0, 1.6, 2.6, 2.2, 4.0, 3.0, 0.3, 0.3);
        assertEquals(2.6 * Math.sqrt(Math.log(2.0 * 1.3 / T)), RenderReach.reach(calm), 1e-12);
        BumpParams shaky = new BumpParams(2.0, 1.6, 2.6, 2.2, 4.0, 3.0, 0.3, 0.5);
        double reach = RenderReach.reach(shaky);
        assertEquals(4.0 + 3.0 * Math.sqrt(Math.log(2.0 * (1.5 + 0.2) / T)), reach, 1e-12);
        assertTrue(highest(shaky, -4.0, 0) >= T, "the jitter lifts the dip");
    }

    @Test
    void negativeAmplitudeReachesTheRaisedDip() {
        BumpParams inverted = DEFAULTS.withAmplitude(-2.0);
        double reach = RenderReach.reach(inverted);
        assertTrue(reach >= inverted.trailLag());
        Vec3 c = FLOOR.center();
        assertTrue(BumpShape.height(inverted, FLOOR, c.x() - inverted.trailLag(), c.y(), c.z()) >= T);
        // Without a dip and with mild jitter an inverted bump never rises.
        assertEquals(0, RenderReach.reach(new BumpParams(-2.0, 1.6, 2.6, 2.2, 4.0, 3.0, 0, 0.5)));
    }

    @Test
    void flatOrFaintBumpReachesNothing() {
        assertEquals(0, RenderReach.reach(DEFAULTS.withAmplitude(0)));
        assertEquals(0, RenderReach.reach(new BumpParams(0.04, 1.6, 2.6, 2.2, 4.0, 3.0, 0.3, 0.2)));
        assertTrue(RenderReach.reach(DEFAULTS.withAmplitude(T)) > 0, "jitter can lift a bump at the threshold");
    }

    @Test
    void collectRadiusCoversTheReachAndTheNormalBand() {
        assertEquals(7.0, RenderReach.collectRadius(DEFAULTS, null, 1.5, 2.5, 0.5), 1e-12);
        // Capped by the influence radius (13 for the defaults' lengths).
        assertEquals(15.0, RenderReach.collectRadius(DEFAULTS.withAmplitude(1e12), null, 1.5, 2.5, 0.5), 1e-12);
        Random random = new Random(24);
        for (int i = 0; i < 1000; i++) {
            BumpParams p = randomParams(random);
            double radius = RenderReach.collectRadius(p, null, 1.5, 2.5, 0.5);
            double r = Math.min(p.influenceRadius(), RenderReach.reach(p)) + 1.5;
            double needed = Math.sqrt(r * r + 2.5 * 2.5);
            assertTrue(radius >= needed && radius < needed + 0.5, p + ": " + radius);
            assertEquals(0, radius % 0.5, 1e-12);
        }
    }

    private static RippleParams randomRipple(Random random) {
        return new RippleParams(random.nextDouble() * 0.6, 1 + random.nextDouble() * 9, 0.5 + random.nextDouble() * 4,
                1 + random.nextInt(4), 0.5 + random.nextDouble() * 4);
    }

    /** Highest the ripple gets at distance {@code r} over its whole life (ages on a fine grid). */
    private static double highestRipple(RippleParams ripple, double r) {
        double best = 0;
        int steps = 2000;
        for (int i = 0; i <= steps; i++) {
            best = Math.max(best, Ripple.height(ripple, r, ripple.duration() * i / steps));
        }
        return best;
    }

    @Test
    void nothingBeyondTheRippleReachCanRise() {
        Random random = new Random(25);
        int extended = 0;
        for (int i = 0; i < 60; i++) {
            BumpParams p = i == 0 ? DEFAULTS : randomParams(random);
            RippleParams ripple = i == 0 ? RippleParams.defaults() : randomRipple(random);
            double reach = RenderReach.reach(p), rippleReach = RenderReach.rippleReach(p, ripple);
            double limit = Math.max(reach, rippleReach);
            assertTrue(rippleReach <= Ripple.maxRadius(ripple), "capped at the ripple's farthest front");
            double maxR = 1.2 * Math.max(limit, Ripple.maxRadius(ripple)) + 1;
            for (int k = 0; k <= 150; k++) {
                double r = maxR * k / 150;
                double ring = highestRipple(ripple, r);
                for (int j = 0; j < 360; j += 6) {
                    double angle = Math.toRadians(j);
                    double h = highest(p, r * Math.cos(angle), r * Math.sin(angle)) + ring;
                    assertTrue(h < T || r <= limit + 1e-9,
                            p + ", " + ripple + ": rises to " + h + " at " + r + ", reach " + limit);
                }
            }
            if (rippleReach > reach) {
                extended++;
            }
        }
        assertTrue(extended > 20, "a ripple should often reach beyond the bump: " + extended);
    }

    @Test
    void rippleAloneReachesWhereItsEnvelopeFallsToTheThreshold() {
        RippleParams ripple = RippleParams.defaults();
        BumpParams flat = DEFAULTS.withAmplitude(0);
        double reach = RenderReach.rippleReach(flat, ripple);
        assertEquals(Ripple.maxRadius(ripple) * (1 - T / ripple.amplitude()), reach, 1e-9);
        assertTrue(reach > 12, "the default rings run out far: " + reach);
        // The crests run a quarter wavelength behind the front, so they rise almost as far.
        double farthest = 0;
        for (int k = 0; k <= 600; k++) {
            double r = reach * k / 600;
            if (highestRipple(ripple, r) >= T) {
                farthest = r;
            }
        }
        assertTrue(farthest >= reach - ripple.wavelength() / 2, "rises up to " + farthest + " of " + reach);
        // With the default bump on top it reaches at least as far.
        assertTrue(RenderReach.rippleReach(DEFAULTS, ripple) >= reach);
    }

    @Test
    void rippleReachIsZeroOrCappedAtTheExtremes() {
        RippleParams faint = RippleParams.defaults().withAmplitude(0.01);
        assertEquals(0, RenderReach.rippleReach(DEFAULTS.withAmplitude(0), faint));
        RippleParams ripple = RippleParams.defaults();
        // The bump alone rises beyond the ripple's farthest front (reach 15.9 > 15).
        assertEquals(Ripple.maxRadius(ripple), RenderReach.rippleReach(DEFAULTS.withAmplitude(1e15), ripple));
    }

    @Test
    void collectRadiusCoversTheRippleWhileItRuns() {
        RippleParams ripple = RippleParams.defaults();
        double radius = RenderReach.collectRadius(DEFAULTS, ripple, 1.5, 2.5, 0.5);
        double r = RenderReach.rippleReach(DEFAULTS, ripple) + 1.5;
        double needed = Math.sqrt(r * r + 2.5 * 2.5);
        assertTrue(radius >= needed && radius < needed + 0.5, "radius " + radius);
        assertTrue(radius > RenderReach.collectRadius(DEFAULTS, null, 1.5, 2.5, 0.5));
        // An ALERT bump (0.85 of its full height) with its ripple, faded alike, stays within the default ripple's
        // farthest front: the cost of the collection is bounded by it.
        double alert = 0.85;
        double alerted = RenderReach.collectRadius(DEFAULTS.withAmplitude(alert * DEFAULTS.amplitude()),
                ripple.withAmplitude(alert * ripple.amplitude()), 1.5, 2.5, 0.5);
        assertTrue(alerted <= radius && alerted <= Ripple.maxRadius(ripple), "radius " + alerted);
        // A faint ripple only adds to the tail of the bump.
        RippleParams faint = ripple.withAmplitude(0.01);
        assertTrue(RenderReach.collectRadius(DEFAULTS, faint, 1.5, 2.5, 0.5)
                <= RenderReach.collectRadius(DEFAULTS, null, 1.5, 2.5, 0.5) + 0.5);
    }
}
