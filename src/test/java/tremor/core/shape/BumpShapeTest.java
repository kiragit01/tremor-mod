package tremor.core.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;
import tremor.core.math.Vec3;

class BumpShapeTest {
    private static final BumpParams DEFAULTS = BumpParams.defaults();
    /** Floor bump at an arbitrary position, moving along +x. */
    private static final BumpFrame FLOOR = BumpFrame.of(new Vec3(10.25, 64, -7.5), Vec3.UNIT_Y, Vec3.UNIT_X);

    private static double h(BumpParams params, BumpFrame frame, Vec3 p) {
        return BumpShape.height(params, frame, p.x(), p.y(), p.z());
    }

    /** World point at local coordinates (a along forward, b along side, s along normal). */
    private static Vec3 local(BumpFrame frame, double a, double b, double s) {
        return frame.center().add(frame.forward().scale(a)).add(frame.side().scale(b)).add(frame.normal().scale(s));
    }

    private static double expectedAtCenter(BumpParams p) {
        double lag = p.trailLag() / p.trailSigma();
        return p.amplitude() * (1 - p.trailDepth() * Math.exp(-lag * lag));
    }

    private static BumpFrame[] frames() {
        return new BumpFrame[]{
                FLOOR,
                BumpFrame.of(new Vec3(0.5, 30, 0.5), Vec3.UNIT_Y.negate(), new Vec3(0, 0, 1)),     // ceiling
                BumpFrame.of(new Vec3(-3, 12, 8), Vec3.UNIT_X, new Vec3(0, 1, 1)),                // wall facing +x
                BumpFrame.of(new Vec3(100, -20, 3), new Vec3(1, 1, 0), new Vec3(0.3, -0.2, 1)),  // 45° slope
                BumpFrame.of(new Vec3(5, 5, 5), new Vec3(-0.2, 0.3, -1), Vec3.ZERO),             // degenerate velocity
        };
    }

    @Test
    void valueAtCenter() {
        for (BumpFrame frame : frames()) {
            Vec3 c = frame.center();
            assertEquals(expectedAtCenter(DEFAULTS), h(DEFAULTS, frame, c), 1e-12);
        }
        BumpParams noTrail = new BumpParams(1.7, 1, 2, 3, 4, 5, 0, 0);
        assertEquals(1.7, h(noTrail, FLOOR, FLOOR.center()), 1e-12);
        BumpParams custom = new BumpParams(-3, 1, 2, 3, 1.5, 2.5, 0.4, 0);
        assertEquals(expectedAtCenter(custom), h(custom, FLOOR, FLOOR.center()), 1e-12);
    }

    @Test
    void symmetricAcrossTheDirectionOfMotion() {
        for (BumpFrame frame : frames()) {
            for (double a = -12; a <= 12; a += 0.75) {
                for (double b = 0.1; b <= 8; b += 0.65) {
                    double left = h(DEFAULTS, frame, local(frame, a, b, 0));
                    double right = h(DEFAULTS, frame, local(frame, a, -b, 0));
                    assertEquals(left, right, 1e-12, "a=" + a + " b=" + b);
                }
            }
        }
    }

    @Test
    void displacementAlongNormalDoesNotChangeHeight() {
        for (BumpFrame frame : frames()) {
            for (double a = -8; a <= 8; a += 1.3) {
                for (double b = -5; b <= 5; b += 1.1) {
                    double onPlane = h(DEFAULTS, frame, local(frame, a, b, 0));
                    for (double s : new double[]{-3, -0.5, 0.5, 2, 10}) {
                        assertEquals(onPlane, h(DEFAULTS, frame, local(frame, a, b, s)), 1e-12);
                    }
                }
            }
        }
    }

    @Test
    void frontFallsOffFasterThanBack() {
        BumpParams p = new BumpParams(2, 1.6, 2.6, 2.2, 4, 3, 0, 0); // no trail: compare the main bump alone
        for (double a = 0.5; a <= 6; a += 0.5) {
            double front = h(p, FLOOR, local(FLOOR, a, 0, 0));
            double back = h(p, FLOOR, local(FLOOR, -a, 0, 0));
            assertTrue(front < back, "a=" + a + ": front " + front + " back " + back);
        }
        // Also with the default trail, just behind the peak.
        assertTrue(h(DEFAULTS, FLOOR, local(FLOOR, 1.5, 0, 0)) < h(DEFAULTS, FLOOR, local(FLOOR, -1.5, 0, 0)));
    }

    @Test
    void peakIsAtCenterAndMonotoneOnBothSides() {
        BumpParams p = new BumpParams(2, 1.6, 2.6, 2.2, 4, 3, 0, 0);
        double prev = h(p, FLOOR, FLOOR.center());
        for (double a = 0.25; a <= 6; a += 0.25) {
            double v = h(p, FLOOR, local(FLOOR, a, 0, 0));
            assertTrue(v < prev);
            prev = v;
        }
    }

    @Test
    void trailIsNegativeBehindTheBump() {
        double lag = DEFAULTS.trailLag();
        double atTrail = h(DEFAULTS, FLOOR, local(FLOOR, -lag, 0, 0));
        assertTrue(atTrail < 0, "h at a=-L is " + atTrail);
        // Main bump there: A·exp(-(L/σback)²) ≈ 0.19; trail: k·A ≈ 0.6.
        double expected = DEFAULTS.amplitude() * (Math.exp(-Math.pow(lag / DEFAULTS.sigmaBack(), 2)) - DEFAULTS.trailDepth());
        assertEquals(expected, atTrail, 1e-12);
        for (double a = -lag - 1.5; a <= -lag + 0.5; a += 0.5) {
            assertTrue(h(DEFAULTS, FLOOR, local(FLOOR, a, 0, 0)) < 0, "a=" + a);
        }
        // No dip ahead of the bump.
        for (double a = 0; a <= 12; a += 0.5) {
            assertTrue(h(DEFAULTS, FLOOR, local(FLOOR, a, 0, 0)) >= -1e-3 * DEFAULTS.amplitude(), "a=" + a);
        }
    }

    @Test
    void negligibleBeyondInfluenceRadius() {
        BumpParams[] paramSets = {
                DEFAULTS,
                DEFAULTS.scaled(0.5),
                DEFAULTS.scaled(1.7),
                new BumpParams(3, 1, 4, 2, 2, 2, 0.4, 0),
                new BumpParams(-2, 2, 2, 2, 6, 1, 0.2, 0),
                new BumpParams(1.5, 1.2, 1.2, 3, 3, 2, 0, 0),
        };
        for (BumpParams p : paramSets) {
            double r = p.influenceRadius();
            for (BumpFrame frame : frames()) {
                for (int i = 0; i < 360; i += 5) {
                    double angle = Math.toRadians(i);
                    for (double dist : new double[]{r, r * 1.01, r * 1.5, r * 3}) {
                        double a = dist * Math.cos(angle), b = dist * Math.sin(angle);
                        double v = h(p, frame, local(frame, a, b, 0.7));
                        assertTrue(Math.abs(v) < 1e-3 * Math.abs(p.amplitude()),
                                p + " at a=" + a + " b=" + b + ": " + v);
                    }
                }
            }
        }
    }

    @Test
    void zeroJitterGivesDeterministicHeight() {
        BumpParams p = new BumpParams(2, 1.6, 2.6, 2.2, 4, 3, 0.3, 0);
        Random random = new Random(11);
        for (int i = 0; i < 2000; i++) {
            double x = FLOOR.center().x() + random.nextGaussian() * 5;
            double y = FLOOR.center().y() + random.nextGaussian() * 2;
            double z = FLOOR.center().z() + random.nextGaussian() * 5;
            double t = random.nextDouble() * 1000;
            assertEquals(BumpShape.height(p, FLOOR, x, y, z), BumpShape.height(p, FLOOR, x, y, z, t));
        }
    }

    @Test
    void jitterIsBoundedAndAnimated() {
        BumpParams p = DEFAULTS.withAmplitude(-2.5);
        double bound = p.jitter() * Math.abs(p.amplitude());
        Random random = new Random(12);
        double maxDiff = 0;
        boolean changesOverTime = false;
        for (int i = 0; i < 20_000; i++) {
            double x = FLOOR.center().x() + random.nextGaussian() * 6;
            double y = FLOOR.center().y() + random.nextGaussian() * 3;
            double z = FLOOR.center().z() + random.nextGaussian() * 6;
            double t = random.nextDouble() * 600;
            double base = BumpShape.height(p, FLOOR, x, y, z);
            double diff = BumpShape.height(p, FLOOR, x, y, z, t) - base;
            assertTrue(Math.abs(diff) <= bound, "jitter " + diff + " exceeds " + bound);
            maxDiff = Math.max(maxDiff, Math.abs(diff));
            if (Math.abs(BumpShape.height(p, FLOOR, x, y, z, t + 0.3) - base - diff) > 1e-6) {
                changesOverTime = true;
            }
        }
        assertTrue(maxDiff > 0.3 * bound, "jitter should be visible, max " + maxDiff);
        assertTrue(changesOverTime, "jitter should move with time");
    }

    @Test
    void jitterFadesOutWithTheBump() {
        double r = DEFAULTS.influenceRadius();
        for (double angle = 0; angle < 2 * Math.PI; angle += 0.1) {
            double x = FLOOR.center().x() + Math.cos(angle) * r;
            double z = FLOOR.center().z() + Math.sin(angle) * r;
            for (double t = 0; t < 30; t += 0.7) {
                double diff = BumpShape.height(DEFAULTS, FLOOR, x, FLOOR.center().y(), z, t)
                        - BumpShape.height(DEFAULTS, FLOOR, x, FLOOR.center().y(), z);
                assertTrue(Math.abs(diff) < 1e-3 * DEFAULTS.amplitude(), "jitter at the rim: " + diff);
            }
        }
    }

    @Test
    void wallAndCeilingFramesBehaveLikeTheFloor() {
        // The shape only depends on local coordinates, so every orientation gives the same profile.
        for (BumpFrame frame : frames()) {
            for (double a = -10; a <= 6; a += 0.8) {
                for (double b = -6; b <= 6; b += 0.9) {
                    double expected = h(DEFAULTS, FLOOR, local(FLOOR, a, b, 0));
                    assertEquals(expected, h(DEFAULTS, frame, local(frame, a, b, 0.3)), 1e-12,
                            frame + " a=" + a + " b=" + b);
                }
            }
        }
        // Ceiling bump moving along +z: a point below the ceiling centre and ahead of it is in front.
        BumpFrame ceiling = BumpFrame.of(new Vec3(0.5, 30, 0.5), Vec3.UNIT_Y.negate(), new Vec3(0, 0, 1));
        assertTrue(BumpShape.height(DEFAULTS, ceiling, 0.5, 29.5, 2.5) < BumpShape.height(DEFAULTS, ceiling, 0.5, 29.5, -1.5));
        // Wall facing +x: height does not depend on x (the normal axis).
        BumpFrame wall = BumpFrame.of(new Vec3(-3, 12, 8), Vec3.UNIT_X, new Vec3(0, 1, 0));
        assertEquals(BumpShape.height(DEFAULTS, wall, -3, 13, 9), BumpShape.height(DEFAULTS, wall, -1, 13, 9), 1e-12);
        assertTrue(BumpShape.height(DEFAULTS, wall, -3, 13, 9) > BumpShape.RENDER_THRESHOLD);
    }
}
