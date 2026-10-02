package tremor.core.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import tremor.core.math.Vec3;

class AwakeningFieldTest {
    private static final AwakeningParams P = AwakeningParams.defaults();
    private static final Vec3 CENTER = new Vec3(100.5, 64, -40.5);
    private static final double RADIUS = 30;
    private static final Vec3 FOCUS = new Vec3(108.5, 64, -35.5);

    private static AwakeningField field(double breath, double hill, List<AwakeningField.Ring> rings) {
        return new AwakeningField(P, CENTER, RADIUS, breath, P.breathEnd(), FOCUS, hill, rings);
    }

    private static AwakeningField.Ring ring(Vec3 origin, double age, double strength) {
        return new AwakeningField.Ring(origin, age, AwakeningShape.stepRipple(P, strength));
    }

    private static double horizontal(Vec3 a, double x, double z) {
        return Math.hypot(x - a.x(), z - a.z());
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> field(-0.1, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> field(Double.NaN, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> field(0.2, -1, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new AwakeningField(P, CENTER, -1, 0.2, 0.4, FOCUS, 0, List.of()));
        assertThrows(NullPointerException.class, () -> new AwakeningField.Ring(null, 0, P.stepRipple()));
    }

    @Test
    void theZoneBreathesInSwellsAndStopsAtItsEdge() {
        AwakeningField f = field(0.3, 0, List.of());
        // Floors, walls and ceilings alike: only the horizontal distance and the height off the centre count, times
        // the swell of the place.
        double[][] points = {{0, -0.5, 0}, {20, 9.5, -5}, {-3, -17.5, 12}};
        for (double[] p : points) {
            double x = CENTER.x() + p[0], y = CENTER.y() + p[1], z = CENTER.z() + p[2];
            assertEquals(0.3 * AwakeningShape.swell(x, y, z), f.at(x, y, z), 1e-12);
        }
        assertEquals(0, f.at(CENTER.x() + RADIUS, CENTER.y() - 0.5, CENTER.z()), 1e-12, "still at the edge");
        assertEquals(0, f.at(CENTER.x(), CENTER.y() - 0.5, CENTER.z() - RADIUS - 5), 1e-12, "still outside");
        Random random = new Random(41);
        for (int i = 0; i < 2000; i++) {
            double x = CENTER.x() + random.nextGaussian() * 20, y = CENTER.y() + random.nextGaussian() * 15;
            double z = CENTER.z() + random.nextGaussian() * 20;
            double fade = AwakeningShape.zoneFade(P, RADIUS, horizontal(CENTER, x, z), y - CENTER.y());
            assertEquals(0.3 * fade * AwakeningShape.swell(x, y, z), f.at(x, y, z), 1e-12);
            // The peak bound ignores the swell: it is an upper bound of what will rise.
            assertEquals(P.breathEnd() * fade, f.peakBreath(x, y, z), 1e-12);
            assertTrue(f.at(x, y, z) <= f.peakBreath(x, y, z) / P.breathEnd() * 0.3 + 1e-12);
        }
        assertEquals(0, field(0, 0, List.of()).at(CENTER.x(), CENTER.y(), CENTER.z()), "between two breaths");
    }

    @Test
    void swellsAreBoundedAndUneven() {
        Random random = new Random(45);
        double min = 1, max = 0;
        for (int i = 0; i < 5000; i++) {
            double x = random.nextDouble() * 200 - 100, y = random.nextDouble() * 40, z = random.nextDouble() * 200 - 100;
            double s = AwakeningShape.swell(x, y, z);
            assertTrue(s >= AwakeningShape.SWELL_FLOOR - 1e-12 && s <= 1 + 1e-12, "within [floor, 1]: " + s);
            min = Math.min(min, s);
            max = Math.max(max, s);
        }
        assertTrue(max - min > 0.5, "flat ground heaves unevenly: " + min + ".." + max);
        // Broad: neighbouring blocks differ only a little.
        assertTrue(Math.abs(AwakeningShape.swell(10, 64, 10) - AwakeningShape.swell(11, 64, 10)) < 0.3);
    }

    @Test
    void hillRisesAtTheFocusOnTopOfTheBreathing() {
        double hill = 2.4;
        AwakeningField f = field(0.4, hill, List.of());
        double tolerance = hill / 1000; // the hill is left out where it is below a thousandth of its peak
        Random random = new Random(42);
        for (int i = 0; i < 2000; i++) {
            double x = FOCUS.x() + random.nextGaussian() * 4, y = FOCUS.y() + random.nextGaussian() * 3;
            double z = FOCUS.z() + random.nextGaussian() * 4;
            double breath = 0.4 * AwakeningShape.zoneFade(P, RADIUS, horizontal(CENTER, x, z), y - CENTER.y())
                    * AwakeningShape.swell(x, y, z);
            double d2 = new Vec3(x, y, z).distanceSquared(FOCUS);
            assertEquals(breath + AwakeningShape.hill(hill, P.hillSigma(), d2), f.at(x, y, z), tolerance);
        }
        assertEquals(0.4 * AwakeningShape.swell(FOCUS.x(), FOCUS.y(), FOCUS.z()) + hill,
                f.at(FOCUS.x(), FOCUS.y(), FOCUS.z()), 1e-12);
        // The hill is not faded at the zone's edge: it rises wherever the player is swallowed.
        Vec3 edge = new Vec3(CENTER.x() + RADIUS - 1, CENTER.y(), CENTER.z());
        AwakeningField atEdge = new AwakeningField(P, CENTER, RADIUS, 0, 0, edge, hill, List.of());
        assertEquals(hill, atEdge.at(edge.x(), edge.y(), edge.z()), 1e-12);
    }

    @Test
    void ringsAreRipplesAroundTheStepsIn3d() {
        Vec3 step = new Vec3(95.5, 64, -42.5);
        double age = 0.6, strength = 1.7;
        AwakeningField.Ring ring = ring(step, age, strength);
        AwakeningField f = field(0, 0, List.of(ring));
        Random random = new Random(43);
        boolean raised = false;
        for (int i = 0; i < 4000; i++) {
            double x = step.x() + random.nextGaussian() * 5, y = step.y() + random.nextGaussian() * 3;
            double z = step.z() + random.nextGaussian() * 5;
            double d = Math.sqrt(new Vec3(x, y, z).distanceSquared(step));
            double expected = Ripple.height(ring.params(), d, age);
            assertEquals(expected, f.at(x, y, z), 1e-12, "at distance " + d);
            raised |= expected > BumpShape.RENDER_THRESHOLD;
        }
        assertTrue(raised);
        // The ring is as high as the step was strong.
        double crest = ring.params().speed() * age - ring.params().wavelength() / 4;
        double walkCrest = Ripple.height(P.stepRipple(), crest, age);
        assertEquals(strength * walkCrest, f.at(step.x() + crest, step.y(), step.z()), 1e-12);
        // It climbs a wall it reaches: a point above the ground at the crest distance is raised just as much.
        assertEquals(strength * walkCrest, f.at(step.x(), step.y() + crest, step.z()), 1e-12);
    }

    @Test
    void ringsAndBreathingAddUp() {
        List<AwakeningField.Ring> rings = new ArrayList<>();
        Random random = new Random(44);
        for (int i = 0; i < 12; i++) {
            Vec3 origin = new Vec3(CENTER.x() + random.nextGaussian() * 8, CENTER.y(),
                    CENTER.z() + random.nextGaussian() * 8);
            // Some not started yet, some over, most running.
            rings.add(ring(origin, random.nextDouble() * 2.2 - 0.2, random.nextDouble() * 3.5));
        }
        AwakeningField f = field(0.25, 1.5, rings);
        double tolerance = 1.5 / 1000;
        for (int i = 0; i < 4000; i++) {
            double x = CENTER.x() + random.nextGaussian() * 15, y = CENTER.y() + random.nextGaussian() * 2;
            double z = CENTER.z() + random.nextGaussian() * 15;
            double expected = 0.25 * AwakeningShape.zoneFade(P, RADIUS, horizontal(CENTER, x, z), y - CENTER.y())
                    * AwakeningShape.swell(x, y, z)
                    + AwakeningShape.hill(1.5, P.hillSigma(), new Vec3(x, y, z).distanceSquared(FOCUS));
            for (AwakeningField.Ring r : rings) {
                double d = Math.sqrt(new Vec3(x, y, z).distanceSquared(r.origin()));
                expected += Ripple.height(r.params(), d, r.ageSeconds());
            }
            double h = f.at(x, y, z);
            assertEquals(expected, h, tolerance);
            assertTrue(Math.abs(h) <= f.maxHeight() + 1e-12, "bounded by maxHeight");
        }
    }

    @Test
    void maxHeightBoundsEverything() {
        Vec3 step = new Vec3(102.5, 64, -40.5);
        List<AwakeningField.Ring> rings = List.of(ring(step, 0.3, 3), ring(step, 0.32, 3), ring(step, 5, 1));
        AwakeningField f = field(0.4, 3, rings);
        double running = 0.6 * (1 - 0.3 / 1.5) + 0.6 * (1 - 0.32 / 1.5);
        assertEquals(0.4 + 3 + running, f.maxHeight(), 1e-12);
        Random random = new Random(45);
        for (int i = 0; i < 20000; i++) {
            double x = step.x() + random.nextGaussian() * 6, y = step.y() + random.nextGaussian();
            double z = step.z() + random.nextGaussian() * 6;
            assertTrue(Math.abs(f.at(x, y, z)) <= f.maxHeight());
        }
        assertEquals(0, field(0, 0, List.of()).maxHeight());
    }

    @Test
    void reachCoversTheRingsOfStepsAtTheEdge() {
        AwakeningField f = field(0.3, 0, List.of());
        assertEquals(RADIUS + Ripple.maxRadius(P.stepRipple()), f.reach(), 1e-12);
        assertEquals(CENTER, f.center());
        assertEquals(RADIUS, f.radius());
        assertEquals(P, f.params());
    }
}
