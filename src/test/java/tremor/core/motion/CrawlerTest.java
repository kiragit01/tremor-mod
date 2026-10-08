package tremor.core.motion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tremor.core.math.Clamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.path.Path;
import tremor.core.path.PathSpline;

class CrawlerTest {
    private static final double DT = 0.05;
    private static final double MAX_SPEED = 4;
    private static final double ACCELERATION = 8;
    private static final MotionParams PARAMS = new MotionParams(MAX_SPEED, ACCELERATION, 0.25, 1.5, 0.3);
    private static final Crawler.NormalSource UP = p -> Vec3.UNIT_Y;
    /** Slack for the spline's arc-length parametrization (relative error ~1e-8) and rounding. */
    private static final double SLACK = 1e-6;

    private static void assertVec(Vec3 expected, Vec3 actual, double eps) {
        assertEquals(0, expected.distance(actual), eps, () -> "expected " + expected + " but was " + actual);
    }

    private static double angleDeg(Vec3 a, Vec3 b) {
        return Math.toDegrees(Math.acos(Clamp.clamp(a.normalize().dot(b.normalize()), -1, 1)));
    }

    private static long p(int x, int y, int z) {
        return VoxelPos.pack(x, y, z);
    }

    private static Path path(boolean[] dive, long... nodes) {
        return new Path(nodes, dive, true);
    }

    private static Path path(long... nodes) {
        return path(new boolean[nodes.length - 1], nodes);
    }

    /** Straight line of {@code edges} unit edges along +x from the origin voxel. */
    private static Path straight(int edges) {
        long[] nodes = new long[edges + 1];
        for (int i = 0; i <= edges; i++) {
            nodes[i] = p(i, 0, 0);
        }
        return path(nodes);
    }

    private static Crawler following(Path path) {
        Crawler crawler = new Crawler(path.point(0), Vec3.UNIT_Y);
        crawler.follow(PathSpline.of(path.point(0), path));
        return crawler;
    }

    /**
     * Ticks until arrival, checking the motion invariants on every tick: speed within [0, max], speed change within
     * {@code a·dt}, the move no longer than {@code speed·dt}, position on the spline, forward a unit vector.
     *
     * @return the speed after each tick
     */
    private static List<Double> runToArrival(Crawler crawler, MotionParams params, Crawler.NormalSource normals) {
        PathSpline spline = crawler.spline();
        double previousSpeed = crawler.speed();
        Vec3 previousPosition = crawler.position();
        List<Double> speeds = new ArrayList<>();
        int ticks = 0;
        while (!crawler.arrived()) {
            crawler.tick(DT, params, normals);
            ticks++;
            assertTrue(ticks < 10_000, "never arrives");
            double speed = crawler.speed();
            speeds.add(speed);
            assertTrue(speed >= 0 && speed <= Math.max(params.maxSpeed(), previousSpeed) + 1e-12, "speed " + speed);
            assertTrue(Math.abs(speed - previousSpeed) <= params.acceleration() * DT + 1e-9,
                    "speed " + previousSpeed + " -> " + speed + " at tick " + ticks);
            assertTrue(crawler.position().distance(previousPosition) <= speed * DT + SLACK, "jump at tick " + ticks);
            assertVec(spline.position(crawler.progress()), crawler.position(), 1e-12);
            assertEquals(1, crawler.forward().length(), 1e-9);
            if (speed > 0 && !crawler.arrived()) {
                assertVec(spline.tangent(crawler.progress()).scale(speed), crawler.velocity(), 1e-12);
            }
            previousSpeed = speed;
            previousPosition = crawler.position();
        }
        return speeds;
    }

    @Test
    void constructors() {
        assertThrows(IllegalArgumentException.class, () -> new Crawler(Vec3.ZERO, Vec3.ZERO));
        Crawler crawler = new Crawler(new Vec3(1, 2, 3), new Vec3(0, 3, 0));
        assertEquals(new Vec3(1, 2, 3), crawler.position());
        assertVec(Vec3.UNIT_Y, crawler.normal(), 1e-12);
        assertEquals(1, crawler.forward().length(), 1e-12);
        assertEquals(0, crawler.speed());
        assertSame(Vec3.ZERO, crawler.velocity());
        assertNull(crawler.spline());
        assertFalse(crawler.arrived());
        assertFalse(crawler.diving());

        Crawler restored = new Crawler(Vec3.ZERO, Vec3.UNIT_X, new Vec3(0, 0, -2), 0.7, 12.5);
        assertVec(new Vec3(0, 0, -1), restored.forward(), 1e-12);
        assertEquals(0.7, restored.amplitude());
        assertEquals(12.5, restored.phase());
        assertEquals(1, new Crawler(Vec3.ZERO, Vec3.UNIT_X, Vec3.ZERO, 0, 0).forward().length(), 1e-12);
    }

    @Test
    void withoutAPathItStaysPut() {
        Crawler crawler = new Crawler(new Vec3(0.5, 0.5, 0.5), Vec3.UNIT_Y);
        for (int i = 0; i < 20; i++) {
            crawler.tick(DT, PARAMS, UP);
        }
        assertEquals(new Vec3(0.5, 0.5, 0.5), crawler.position());
        assertEquals(0, crawler.speed());
        assertSame(Vec3.ZERO, crawler.velocity());
        assertEquals(20 * DT, crawler.phase(), 1e-12);
        // The amplitude rises toward the skin amplitude.
        double expected = PARAMS.amplitude() * (1 - Math.exp(-20 * DT / PARAMS.amplitudeSmoothingSeconds()));
        assertEquals(expected, crawler.amplitude(), 1e-12);
    }

    @Test
    void reachesTheEndAndStopsExactly() {
        Path path = straight(20);
        Crawler crawler = following(path);
        PathSpline spline = crawler.spline();
        List<Double> speeds = runToArrival(crawler, PARAMS, UP);
        int ticks = speeds.size();
        // Ramp up by exactly a·dt per tick to the cruise speed, cruise, brake.
        for (int k = 1; k <= 10; k++) {
            assertEquals(k * ACCELERATION * DT, speeds.get(k - 1), 1e-9);
        }
        assertTrue(speeds.contains(MAX_SPEED));
        // Ideal trapezoid: L / v + v / a = 5.5 s; the discrete braking costs at most a couple of ticks.
        assertTrue(ticks >= 108 && ticks <= 114, "ticks " + ticks);
        assertEquals(0, speeds.get(ticks - 1).doubleValue());
        assertTrue(speeds.get(ticks - 2) > 0, "the tick before arrival still moves onto the end");

        assertVec(path.point(20), crawler.position(), 1e-9);
        assertEquals(spline.length(), crawler.progress(), 1e-12);
        assertEquals(0, crawler.speed());
        assertSame(Vec3.ZERO, crawler.velocity());
        assertVec(Vec3.UNIT_X, crawler.forward(), 1e-9);
        for (int i = 0; i < 10; i++) {
            crawler.tick(DT, PARAMS, UP);
            assertTrue(crawler.arrived());
            assertVec(path.point(20), crawler.position(), 1e-9);
            assertEquals(0, crawler.speed());
            assertVec(Vec3.UNIT_X, crawler.forward(), 1e-9);
        }
    }

    @Test
    void speedAndAccelerationLimitsHoldOnAnyPath() {
        long[][] paths = {
                {p(0, 0, 0), p(1, 0, 0)},
                {p(0, 0, 0), p(1, 0, 1), p(2, 1, 1), p(2, 2, 1)},
                {p(0, 0, 0), p(1, 0, 0), p(2, 0, 0), p(3, 1, 0), p(3, 2, 0), p(3, 3, 0), p(2, 4, 0), p(1, 4, 0),
                        p(0, 4, 1), p(-1, 4, 2)},
        };
        for (long[] nodes : paths) {
            Path path = path(nodes);
            for (MotionParams params : new MotionParams[]{PARAMS, new MotionParams(1, 0.5, 0, 1, 0),
                    new MotionParams(10, 3, 0.1, 1, 0.1)}) {
                Crawler crawler = following(path);
                runToArrival(crawler, params, UP);
                assertVec(path.point(path.size() - 1), crawler.position(), 1e-9);
                assertEquals(0, crawler.speed());
            }
        }
    }

    @Test
    void slowsDownByTheAccelerationLimitWhenMaxSpeedDrops() {
        Crawler crawler = following(straight(60));
        for (int i = 0; i < 40; i++) {
            crawler.tick(DT, PARAMS, UP);
        }
        assertEquals(MAX_SPEED, crawler.speed(), 1e-12);
        MotionParams slow = new MotionParams(1, ACCELERATION, 0.25, 1.5, 0.3);
        double expected = MAX_SPEED;
        for (int i = 0; i < 12; i++) {
            crawler.tick(DT, slow, UP);
            expected = Math.max(1, expected - ACCELERATION * DT);
            assertEquals(expected, crawler.speed(), 1e-9);
        }
        assertEquals(1, crawler.speed(), 1e-12);
    }

    @Test
    void zeroMaxSpeedNeverMoves() {
        Crawler crawler = following(straight(5));
        MotionParams frozen = new MotionParams(0, 1, 0, 1, 0);
        for (int i = 0; i < 20; i++) {
            crawler.tick(DT, frozen, UP);
        }
        assertEquals(0, crawler.progress());
        assertFalse(crawler.arrived());
    }

    @Test
    void normalTurnsSmoothlyFromFloorToWall() {
        // Floor y = 0 toward +x, then up the wall x = 7 (facing -x). The source switches abruptly at x = 7.
        long[] nodes = new long[14];
        for (int x = 0; x <= 6; x++) {
            nodes[x] = p(x, 0, 0);
        }
        for (int y = 1; y <= 7; y++) {
            nodes[6 + y] = p(7, y, 0);
        }
        Path path = path(nodes);
        Vec3 wallNormal = new Vec3(-1, 0, 0);
        Crawler.NormalSource normals = q -> Math.floor(q.x()) >= 7 ? wallNormal : Vec3.UNIT_Y;

        // With smoothing the per-tick turn is at most (1 - exp(-dt/τ)) of the 90° gap.
        double k = 1 - Math.exp(-DT / PARAMS.normalSmoothingSeconds());
        double maxStep = maxNormalStep(path, PARAMS, normals, wallNormal);
        assertTrue(maxStep <= k * 90 + 1e-9, "max step " + maxStep + "°");
        assertTrue(maxStep > 0.5 * k * 90, "max step " + maxStep + "°");

        // Without smoothing it snaps: the source really is discontinuous.
        MotionParams snap = new MotionParams(MAX_SPEED, ACCELERATION, 0, 1.5, 0.3);
        assertEquals(90, maxNormalStep(path, snap, normals, wallNormal), 1e-9);
    }

    private static double maxNormalStep(Path path, MotionParams params, Crawler.NormalSource normals, Vec3 end) {
        Crawler crawler = following(path);
        double maxStep = 0;
        Vec3 previous = crawler.normal();
        for (int i = 0; i < 200; i++) {
            crawler.tick(DT, params, normals);
            assertEquals(1, crawler.normal().length(), 1e-12);
            maxStep = Math.max(maxStep, angleDeg(previous, crawler.normal()));
            previous = crawler.normal();
        }
        assertTrue(crawler.arrived());
        assertVec(end, crawler.normal(), 1e-6);
        return maxStep;
    }

    @Test
    void zeroTargetNormalKeepsTheCurrentOne() {
        Crawler crawler = following(straight(5));
        Vec3 normal = crawler.normal();
        for (int i = 0; i < 10; i++) {
            crawler.tick(DT, PARAMS, q -> Vec3.ZERO);
            assertEquals(normal, crawler.normal());
        }
    }

    @Test
    void amplitudeFadesOnALongDiveAndComesBack() {
        // 4 skin edges, three 4-long dive edges, 12 skin edges.
        List<Long> nodes = new ArrayList<>();
        List<Boolean> dive = new ArrayList<>();
        for (int x = 0; x <= 4; x++) {
            nodes.add(p(x, 0, 0));
        }
        for (int x = 8; x <= 16; x += 4) {
            nodes.add(p(x, 0, 0));
        }
        for (int x = 17; x <= 28; x++) {
            nodes.add(p(x, 0, 0));
        }
        for (int i = 0; i + 1 < nodes.size(); i++) {
            int x = VoxelPos.x(nodes.get(i));
            dive.add(x >= 4 && x < 16);
        }
        boolean[] diveFlags = new boolean[dive.size()];
        for (int i = 0; i < diveFlags.length; i++) {
            diveFlags[i] = dive.get(i);
        }
        Path path = path(diveFlags, nodes.stream().mapToLong(Long::longValue).toArray());
        MotionParams params = new MotionParams(2, 4, 0.25, 1.5, 0.3);
        Crawler crawler = new Crawler(path.point(0), Vec3.UNIT_Y, Vec3.UNIT_X, 1.5, 0);
        crawler.follow(PathSpline.of(path.point(0), path));

        double kAmplitude = 1 - Math.exp(-DT / params.amplitudeSmoothingSeconds());
        double minWhileDiving = Double.POSITIVE_INFINITY;
        boolean dived = false;
        double previous = crawler.amplitude();
        while (!crawler.arrived()) {
            crawler.tick(DT, params, UP);
            double x = crawler.position().x();
            if (x < 4.45 || x > 16.55) {
                assertFalse(crawler.diving(), "diving at x = " + x);
            } else if (x > 4.55 && x < 16.45) {
                assertTrue(crawler.diving(), "not diving at x = " + x);
            }
            if (crawler.diving()) {
                dived = true;
                minWhileDiving = Math.min(minWhileDiving, crawler.amplitude());
            }
            assertTrue(Math.abs(crawler.amplitude() - previous) <= kAmplitude * 1.5 + 1e-12, "amplitude jump");
            previous = crawler.amplitude();
        }
        assertTrue(dived);
        assertTrue(minWhileDiving < 1e-3, "amplitude while diving " + minWhileDiving);
        for (int i = 0; i < 60; i++) {
            crawler.tick(DT, params, UP);
        }
        assertFalse(crawler.diving());
        assertEquals(1.5, crawler.amplitude(), 1e-3);
    }

    @Test
    void phaseAccumulatesDt() {
        Crawler crawler = following(straight(5));
        double sum = 0;
        for (int i = 0; i < 50; i++) {
            double dt = 0.01 + 0.001 * (i % 7);
            crawler.tick(dt, PARAMS, UP);
            sum += dt;
        }
        assertEquals(sum, crawler.phase(), 1e-12);
        crawler.tick(0, PARAMS, UP);
        crawler.tick(-1, PARAMS, UP);
        assertEquals(sum, crawler.phase(), 1e-12);
    }

    @Test
    void replanningMidWayKeepsPositionAndSpeedContinuous() {
        Crawler crawler = following(straight(30));
        for (int i = 0; i < 38; i++) {
            crawler.tick(DT, PARAMS, UP);
        }
        Vec3 before = crawler.position();
        double speed = crawler.speed();
        assertTrue(speed > 3);

        // New route from the voxel the crawler is in, curving off to the side.
        long here = VoxelPos.containing(before);
        int x = VoxelPos.x(here);
        Path detour = path(here, p(x + 1, 0, 1), p(x + 2, 0, 2), p(x + 2, 0, 3), p(x + 2, 0, 4), p(x + 2, 0, 5),
                p(x + 3, 0, 6), p(x + 4, 0, 6), p(x + 5, 0, 6), p(x + 6, 0, 6));
        crawler.follow(PathSpline.of(before, detour));
        assertEquals(before, crawler.position());
        assertEquals(speed, crawler.speed());
        assertFalse(crawler.arrived());
        assertEquals(0, crawler.progress());

        runToArrival(crawler, PARAMS, UP);
        assertVec(detour.point(detour.size() - 1), crawler.position(), 1e-9);
    }

    @Test
    void followingAZeroLengthSplineArrivesAtOnce() {
        Crawler crawler = new Crawler(new Vec3(0.5, 0.5, 0.5), Vec3.UNIT_Y);
        crawler.follow(PathSpline.of(new Vec3(0.5, 0.5, 0.5), path(p(0, 0, 0))));
        crawler.tick(DT, PARAMS, UP);
        assertTrue(crawler.arrived());
        assertEquals(0, crawler.speed());
        assertEquals(new Vec3(0.5, 0.5, 0.5), crawler.position());
    }

    @Test
    void stopAndTeleport() {
        Crawler crawler = following(straight(20));
        for (int i = 0; i < 20; i++) {
            crawler.tick(DT, PARAMS, UP);
        }
        Vec3 here = crawler.position();
        crawler.stop();
        assertNull(crawler.spline());
        assertEquals(0, crawler.speed());
        assertEquals(0, crawler.progress());
        assertFalse(crawler.arrived());
        crawler.tick(DT, PARAMS, UP);
        assertEquals(here, crawler.position());
        assertVec(Vec3.UNIT_X, crawler.forward(), 1e-9);

        crawler.follow(PathSpline.of(here, straight(25)));
        crawler.tick(DT, PARAMS, UP);
        crawler.setPosition(new Vec3(5, 6, 7), new Vec3(0, 0, 2));
        assertEquals(new Vec3(5, 6, 7), crawler.position());
        assertVec(Vec3.UNIT_Z, crawler.normal(), 1e-12);
        assertNull(crawler.spline());
        assertEquals(0, crawler.speed());
        crawler.setPosition(new Vec3(1, 1, 1), Vec3.ZERO);
        assertVec(Vec3.UNIT_Z, crawler.normal(), 1e-12);
    }

    @Test
    void deterministic() {
        Path path = path(p(0, 0, 0), p(1, 0, 1), p(2, 1, 1), p(2, 2, 1), p(2, 3, 2), p(3, 4, 2));
        Crawler.NormalSource normals = q -> new Vec3(Math.sin(q.x()), 1, Math.cos(q.z()));
        Crawler a = following(path), b = following(path);
        for (int i = 0; i < 80; i++) {
            a.tick(DT, PARAMS, normals);
            b.tick(DT, PARAMS, normals);
            assertEquals(a.position(), b.position());
            assertEquals(a.normal(), b.normal());
            assertEquals(a.forward(), b.forward());
            assertEquals(a.speed(), b.speed());
            assertEquals(a.amplitude(), b.amplitude());
        }
    }

    // ---- normal through a half turn ---------------------------------------------------------------------------------

    @Test
    void normalTurnsThroughAHalfTurn() {
        double tau = PARAMS.normalSmoothingSeconds();
        Vec3 down = new Vec3(0, -1, 0);
        // Standing with a forward to tip over, and with a forward along the normal (any perpendicular then).
        for (Vec3 forward : new Vec3[]{Vec3.UNIT_X, Vec3.UNIT_Y}) {
            Crawler crawler = new Crawler(Vec3.ZERO, Vec3.UNIT_Y, forward, 0, 0);
            for (int i = 1; i <= 30; i++) {
                crawler.tick(DT, PARAMS, q -> down);
                assertEquals(1, crawler.normal().length(), 1e-12);
                // The angle decays exponentially, as for any other turn.
                assertEquals(180 * Math.exp(-i * DT / tau), angleDeg(crawler.normal(), down), 1e-6, "tick " + i);
                if (forward == Vec3.UNIT_X) {
                    assertTrue(crawler.normal().x() > 0, "tips over the direction of travel");
                    assertEquals(0, crawler.normal().z(), 1e-12);
                }
            }
        }
        // Moving: the target flips at x = 3 and is reached within ~3τ.
        Crawler crawler = following(straight(30));
        Crawler.NormalSource flip = q -> q.x() < 3 ? Vec3.UNIT_Y : down;
        int after = -1;
        while (after < Math.round(3 * tau / DT)) {
            crawler.tick(DT, PARAMS, flip);
            if (crawler.position().x() >= 3) {
                after++;
            }
        }
        assertTrue(angleDeg(crawler.normal(), down) < 10, "angle " + angleDeg(crawler.normal(), down));
    }

    @Test
    void normalSnapsToTheTargetWhileDiving() {
        // Through a slab: the far side faces down; the source switches in the middle of the dive.
        Path path = diveLine(4, 8, 12);
        Vec3 down = new Vec3(0, -1, 0);
        Crawler.NormalSource normals = q -> q.x() < 6.5 ? Vec3.UNIT_Y : down;
        Crawler crawler = following(path);
        boolean snapped = false;
        while (!crawler.arrived()) {
            crawler.tick(DT, PARAMS, normals);
            if (crawler.diving() && crawler.position().x() >= 6.5) {
                assertEquals(down, crawler.normal());
                snapped = true;
            }
        }
        assertTrue(snapped);
        assertEquals(down, crawler.normal());
    }

    // ---- replans: follow(Path) -----------------------------------------------------------------------------------

    /** Ticks with the motion invariants checked on every tick, also across replans and spline changes. */
    private static final class Run {
        final Crawler crawler;
        final MotionParams params;
        final Crawler.NormalSource normals;
        final List<Vec3> positions = new ArrayList<>();
        double speed;
        Vec3 position;
        Vec3 forward;
        int ticks;
        /** Largest change of the forward direction in one tick (degrees) while not crawling slowly. */
        double maxTurn;

        Run(Crawler crawler, MotionParams params, Crawler.NormalSource normals) {
            this.crawler = crawler;
            this.params = params;
            this.normals = normals;
            speed = crawler.speed();
            position = crawler.position();
            forward = crawler.forward();
        }

        void tick() {
            crawler.tick(DT, params, normals);
            ticks++;
            assertTrue(ticks < 20_000, "never arrives");
            double v = crawler.speed();
            assertTrue(v >= 0 && v <= params.maxSpeed() + 1e-12, "speed " + v);
            assertTrue(Math.abs(v - speed) <= params.acceleration() * DT + 1e-9,
                    "speed " + speed + " -> " + v + " at tick " + ticks);
            assertTrue(crawler.position().distance(position) <= v * DT + SLACK, "jump at tick " + ticks);
            assertEquals(1, crawler.forward().length(), 1e-9);
            if (v > 2 * params.acceleration() * DT) {
                maxTurn = Math.max(maxTurn, angleDeg(forward, crawler.forward()));
            }
            speed = v;
            position = crawler.position();
            forward = crawler.forward();
            positions.add(position);
        }

        void ticks(int n) {
            for (int i = 0; i < n; i++) {
                tick();
            }
        }

        void toArrival() {
            while (!crawler.arrived()) {
                tick();
            }
        }
    }

    /** Stopping distance 4 blocks from full speed. */
    private static final MotionParams SLOW_BRAKES = new MotionParams(4, 2, 0.25, 1.5, 0.3);

    /** Straight line along -x from voxel {@code x} to the origin voxel. */
    private static Path home(int x) {
        long[] nodes = new long[x + 1];
        for (int i = 0; i <= x; i++) {
            nodes[i] = p(x - i, 0, 0);
        }
        return path(nodes);
    }

    @Test
    void replanAlongTheMotionTakesOverAtOnceKeepingTheDirection() {
        Crawler crawler = following(straight(30));
        Run run = new Run(crawler, PARAMS, UP);
        run.ticks(38);
        PathSpline old = crawler.spline();
        Vec3 before = crawler.position();
        Vec3 forward = crawler.forward();
        double speed = crawler.speed();
        assertTrue(speed > 3);

        // From the voxel the crawler is in, curving off to the side (37° off the motion at first).
        long here = VoxelPos.containing(before);
        int x = VoxelPos.x(here);
        Path detour = path(here, p(x + 1, 0, 1), p(x + 2, 0, 2), p(x + 2, 0, 3), p(x + 2, 0, 4), p(x + 2, 0, 5),
                p(x + 3, 0, 6), p(x + 4, 0, 6), p(x + 5, 0, 6), p(x + 6, 0, 6));
        crawler.follow(detour);
        assertNotSame(old, crawler.spline());
        assertSame(detour, crawler.spline().path());
        assertNull(crawler.pendingPath());
        assertEquals(before, crawler.position());
        assertEquals(speed, crawler.speed());
        assertEquals(0, crawler.progress());
        assertFalse(crawler.arrived());
        assertVec(forward, crawler.spline().tangent(0), 1e-9);

        run.tick();
        double firstTurn = angleDeg(forward, crawler.forward());
        run.toArrival();
        assertVec(detour.point(detour.size() - 1), crawler.position(), 1e-9);
        // No sharper than the corners of the same route planned from the start (a 37° jump without the start tangent).
        long[] whole = new long[x + detour.size()];
        for (int i = 0; i <= x; i++) {
            whole[i] = p(i, 0, 0);
        }
        System.arraycopy(detour.nodes(), 1, whole, x + 1, detour.size() - 1);
        Run reference = new Run(following(path(whole)), PARAMS, UP);
        reference.toArrival();
        assertTrue(firstTurn <= reference.maxTurn + 1, "first turn " + firstTurn + "°, corners " + reference.maxTurn);
        assertTrue(run.maxTurn <= reference.maxTurn + 1, "max turn " + run.maxTurn + "°, corners " + reference.maxTurn);
    }

    @Test
    void reversalBrakesAlongTheOldPathThenTurnsBack() {
        Path line = straight(40);
        Crawler crawler = following(line);
        Run run = new Run(crawler, SLOW_BRAKES, UP);
        run.ticks(60);
        assertEquals(4, crawler.speed(), 1e-12);
        PathSpline old = crawler.spline();
        double x0 = crawler.position().x();

        Path home = home(VoxelPos.x(VoxelPos.containing(crawler.position())));
        crawler.follow(home);
        assertSame(old, crawler.spline());
        assertSame(home, crawler.pendingPath());
        assertEquals(4, crawler.speed());
        assertFalse(crawler.arrived());

        // Brakes along the old path by a·dt per tick, still heading +x, then the route starts where it stopped.
        double expected = 4;
        double previousX = x0;
        while (crawler.pendingPath() != null) {
            run.tick();
            expected = Math.max(0, expected - SLOW_BRAKES.acceleration() * DT);
            assertEquals(expected, crawler.speed(), 1e-9);
            assertTrue(crawler.position().x() >= previousX);
            assertVec(Vec3.UNIT_X, crawler.forward(), 1e-9);
            assertFalse(crawler.arrived());
            previousX = crawler.position().x();
        }
        assertEquals(0, crawler.speed());
        assertNotSame(old, crawler.spline());
        // The discrete stop from 4 b/s at 2 b/s²: 3.9, 3.8, ... 0.1 b/s for one tick each.
        assertEquals(x0 + 3.9, crawler.position().x(), 1e-6);

        run.toArrival();
        assertVec(line.point(0), crawler.position(), 1e-9);
        assertVec(new Vec3(-1, 0, 0), crawler.forward(), 1e-9);
        assertTrue(run.maxTurn < 1e-6, "turned only while (almost) standing: " + run.maxTurn);
    }

    @Test
    void routeShorterThanTheStoppingDistanceOvershootsAndComesBack() {
        for (boolean oneNode : new boolean[]{false, true}) {
            Crawler crawler = following(straight(40));
            Run run = new Run(crawler, SLOW_BRAKES, UP);
            run.ticks(60);
            double x0 = crawler.position().x();
            int x = VoxelPos.x(VoxelPos.containing(crawler.position()));
            // The next node ahead, or the node just passed (a one-node route: the goal is the nearest node).
            Path goal = oneNode ? path(p(x, 0, 0)) : path(p(x, 0, 0), p(x + 1, 0, 0));
            crawler.follow(goal);
            assertSame(goal, crawler.pendingPath());
            run.toArrival();
            assertVec(goal.point(goal.size() - 1), crawler.position(), 1e-9);
            double maxX = run.positions.stream().mapToDouble(Vec3::x).max().orElseThrow();
            assertEquals(x0 + 3.9, maxX, 1e-6);
        }
    }

    @Test
    void retraceAroundACornerStaysOnTheOldPath() {
        // Floor y = 0 toward +x, then up the wall x = 7: braking from 4 b/s takes it around the corner and up the wall.
        long[] nodes = new long[14];
        for (int x = 0; x <= 6; x++) {
            nodes[x] = p(x, 0, 0);
        }
        for (int y = 1; y <= 7; y++) {
            nodes[6 + y] = p(7, y, 0);
        }
        Path up = path(nodes);
        Crawler crawler = following(up);
        Run run = new Run(crawler, SLOW_BRAKES, UP);
        run.ticks(45);
        assertEquals(4, crawler.speed(), 1e-12);
        Path home = home(VoxelPos.x(VoxelPos.containing(crawler.position())));
        crawler.follow(home);
        assertSame(home, crawler.pendingPath());
        run.toArrival();
        assertVec(home.point(home.size() - 1), crawler.position(), 1e-9);
        double maxY = run.positions.stream().mapToDouble(Vec3::y).max().orElseThrow();
        assertTrue(maxY > 2.5, "went up the wall to y = " + maxY);
        // Back down the wall and around the corner, never straight across the air of the corner.
        for (Vec3 q : run.positions) {
            assertTrue(distanceToPolyline(q, up) < 0.5, q + " is off the path");
        }
    }

    @Test
    void replanningAtEveryTickAlongTheSameRouteNeverBrakesOnTheWay() {
        // Along +x, a 90° turn to +z, then a 45° turn: a search that keeps finding the same route, as on terrain
        // changes near the path. The server sends it from the nearest of the next few nodes.
        List<Long> list = new ArrayList<>();
        for (int x = 0; x <= 8; x++) {
            list.add(p(x, 0, 0));
        }
        for (int z = 1; z <= 6; z++) {
            list.add(p(8, 0, z));
        }
        for (int i = 1; i <= 5; i++) {
            list.add(p(8 + i, 0, 6 + i));
        }
        Path route = path(list.stream().mapToLong(Long::longValue).toArray());
        Run reference = new Run(following(route), PARAMS, UP);
        reference.toArrival();

        Crawler crawler = new Crawler(route.point(0), Vec3.UNIT_Y);
        crawler.follow(route);
        Run run = new Run(crawler, PARAMS, UP);
        int first = 0;
        while (true) {
            run.tick();
            if (crawler.arrived()) {
                break;
            }
            Vec3 at = crawler.position();
            for (int i = first + 1; i < Math.min(route.size(), first + 4); i++) {
                if (at.distanceSquared(route.point(i)) <= at.distanceSquared(route.point(first))) {
                    first = i;
                }
            }
            crawler.follow(new Path(Arrays.copyOfRange(route.nodes(), first, route.size()),
                    Arrays.copyOfRange(route.dive(), first, route.size() - 1), true));
            if (crawler.speed() == PARAMS.maxSpeed()) {
                assertNull(crawler.pendingPath(), "braked for a replan at tick " + run.ticks);
            }
        }
        assertVec(route.point(route.size() - 1), crawler.position(), 1e-9);
        assertTrue(Math.abs(run.ticks - reference.ticks) <= 4, run.ticks + " ticks, without replans " + reference.ticks);
        // The corners are as sharp as without replans (centripetal Catmull-Rom turns ~50° per tick at a 90° voxel
        // corner at 4 b/s), not sharper.
        assertTrue(run.maxTurn <= reference.maxTurn + 1, "max turn " + run.maxTurn + "°, without " + reference.maxTurn);
    }

    @Test
    void randomReplansNeverChangeTheSpeedByMoreThanTheAccelerationLimit() {
        Random random = new Random(11);
        MotionParams params = new MotionParams(5, 3, 0.25, 1.5, 0.3);
        Crawler crawler = new Crawler(new Vec3(0.5, 0.5, 0.5), Vec3.UNIT_Y);
        Run run = new Run(crawler, params, UP);
        int pending = 0, rounds = 400;
        for (int round = 0; round < rounds; round++) {
            crawler.follow(randomFloorWalk(random, VoxelPos.containing(crawler.position()), 1 + random.nextInt(16)));
            if (crawler.pendingPath() != null) {
                pending++;
            }
            run.ticks(1 + random.nextInt(30));
        }
        run.toArrival();
        assertTrue(pending > rounds / 10 && pending < rounds * 9 / 10, "pending " + pending + " of " + rounds);
    }

    /** Random 8-neighbour walk on the floor y = 0 from {@code start}, never stepping straight back. */
    private static Path randomFloorWalk(Random random, long start, int size) {
        long[] nodes = new long[size];
        nodes[0] = start;
        int x = VoxelPos.x(start), z = VoxelPos.z(start), px = 0, pz = 0;
        for (int i = 1; i < size; i++) {
            int dx, dz;
            do {
                dx = random.nextInt(3) - 1;
                dz = random.nextInt(3) - 1;
            } while (dx == 0 && dz == 0 || dx == -px && dz == -pz);
            x += dx;
            z += dz;
            px = dx;
            pz = dz;
            nodes[i] = p(x, 0, z);
        }
        return path(nodes);
    }

    @Test
    void stopDropsAPendingRoute() {
        Crawler crawler = following(straight(40));
        new Run(crawler, SLOW_BRAKES, UP).ticks(60);
        crawler.follow(home(3));
        assertNotNull(crawler.pendingPath());
        crawler.stop();
        assertNull(crawler.pendingPath());
        crawler.tick(DT, SLOW_BRAKES, UP);
        assertEquals(0, crawler.speed());
    }

    // ---- replans during a dive -----------------------------------------------------------------------------------

    /** Skin edges along +x up to voxel {@code from}, one dive edge to voxel {@code to}, skin edges up to {@code end}. */
    private static Path diveLine(int from, int to, int end) {
        List<Long> nodes = new ArrayList<>();
        for (int x = 0; x <= from; x++) {
            nodes.add(p(x, 0, 0));
        }
        for (int x = to; x <= end; x++) {
            nodes.add(p(x, 0, 0));
        }
        boolean[] dive = new boolean[nodes.size() - 1];
        dive[from] = true;
        return path(dive, nodes.stream().mapToLong(Long::longValue).toArray());
    }

    private static Path straightFrom(int from, int to) {
        long[] nodes = new long[to - from + 1];
        for (int x = from; x <= to; x++) {
            nodes[x - from] = p(x, 0, 0);
        }
        return path(nodes);
    }

    @Test
    void replanMidDiveKeepsTheDiveUpToTheExit() {
        Path path = diveLine(4, 8, 14);
        MotionParams params = new MotionParams(2, 4, 0.25, 1.5, 0.3);
        for (boolean oneNode : new boolean[]{false, true}) {
            Crawler crawler = new Crawler(path.point(0), Vec3.UNIT_Y, Vec3.UNIT_X, 1.5, 0);
            crawler.follow(path);
            Run run = new Run(crawler, params, UP);
            while (crawler.position().x() < 6) {
                run.tick();
            }
            assertTrue(crawler.diving());
            assertEquals(0, crawler.amplitude());

            // The server replans from the dive's far end: the rest of the route, or just that node as the goal.
            Path rest = oneNode ? path(p(8, 0, 0)) : straightFrom(8, 14);
            crawler.follow(rest);
            assertNull(crawler.pendingPath());
            assertSame(rest, crawler.spline().path());
            assertTrue(crawler.diving());
            assertEquals(-1, crawler.spline().segment(0));
            while (!crawler.arrived()) {
                run.tick();
                double x = crawler.position().x();
                if (x < 8.45) {
                    assertTrue(crawler.diving(), "surfaced inside the rock at x = " + x);
                    assertEquals(0, crawler.amplitude(), "amplitude at x = " + x);
                } else if (x > 8.55) {
                    assertFalse(crawler.diving());
                }
            }
            assertVec(rest.point(rest.size() - 1), crawler.position(), 1e-9);
            assertTrue(oneNode || crawler.amplitude() > 1, "risen after the exit: " + crawler.amplitude());
        }
    }

    // ---- the bump sinks before a dive ------------------------------------------------------------------------------

    @Test
    void shortDiveHidesTheBump() {
        // The default motion: 3 b/s, 3 b/s², amplitude time constant 0.35 s; a 1-block dive from x = 13 to x = 14.
        MotionParams params = new MotionParams(3, 3, 0.25, 1.5, 0.35);
        Path path = diveLine(13, 14, 26);
        Crawler crawler = new Crawler(path.point(0), Vec3.UNIT_Y, Vec3.UNIT_X, 1.5, 0);
        crawler.follow(path);
        Run run = new Run(crawler, params, UP);
        double k = 1 - Math.exp(-DT / params.amplitudeSmoothingSeconds());
        double lead = 3 * params.amplitudeSmoothingSeconds() * params.maxSpeed();
        double atMiddle = Double.NaN, previous = crawler.amplitude();
        while (!crawler.arrived()) {
            run.tick();
            double x = crawler.position().x(), amplitude = crawler.amplitude();
            assertTrue(Math.abs(amplitude - previous) <= 1.5 * k + 1e-12, "amplitude jump at x = " + x);
            if (x < 13.5 - lead - 0.01) {
                assertEquals(1.5, amplitude, 1e-12, "sinking too early at x = " + x);
            }
            if (Math.abs(x - 14) < 0.08) {
                assertEquals(3, crawler.speed(), 1e-12);
                atMiddle = amplitude;
            }
            if (crawler.diving()) {
                assertEquals(0, amplitude, "visible while diving at x = " + x);
            }
            previous = amplitude;
        }
        assertTrue(atMiddle < 0.05 * 1.5, "amplitude in the middle of the dive " + atMiddle);
        assertEquals(1.5, crawler.amplitude(), 0.15);
    }

    @Test
    void diveAppearingCloseAheadSinksWithoutAJump() {
        Crawler crawler = new Crawler(new Vec3(0.5, 0.5, 0.5), Vec3.UNIT_Y, Vec3.UNIT_X, 1.5, 0);
        crawler.follow(straight(30));
        Run run = new Run(crawler, PARAMS, UP);
        run.ticks(38);
        assertEquals(1.5, crawler.amplitude(), 1e-9);
        // A replan with a dive starting about a block ahead.
        int x = VoxelPos.x(VoxelPos.containing(crawler.position()));
        Path ahead = diveLine(1, 2, 20);
        long[] nodes = ahead.nodes().clone();
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = VoxelPos.offset(nodes[i], x, 0, 0);
        }
        crawler.follow(path(ahead.dive(), nodes));
        assertNull(crawler.pendingPath());
        double k = 1 - Math.exp(-DT / PARAMS.amplitudeSmoothingSeconds());
        double previous = crawler.amplitude();
        while (!crawler.arrived()) {
            run.tick();
            assertTrue(Math.abs(crawler.amplitude() - previous) <= 1.5 * k + 1e-12, "amplitude jump");
            if (crawler.diving() && crawler.position().x() > x + 2) {
                assertEquals(0, crawler.amplitude());
            }
            previous = crawler.amplitude();
        }
    }

    // ---- brake(): a stop within the acceleration limit -------------------------------------------------------------

    /** Distance from {@code q} to the nearest point of {@code spline} (sampled every 0.001 blocks). */
    private static double distanceToSpline(Vec3 q, PathSpline spline) {
        double best = Double.POSITIVE_INFINITY;
        for (double s = 0; s <= spline.length() + 1e-9; s += 0.001) {
            best = Math.min(best, q.distance(spline.position(s)));
        }
        return best;
    }

    @Test
    void brakeSlowsByTheAccelerationLimitAndStopsOnThePath() {
        Path line = straight(40);
        Crawler crawler = following(line);
        Run run = new Run(crawler, SLOW_BRAKES, UP);
        run.ticks(60);
        assertEquals(4, crawler.speed(), 1e-12);
        PathSpline old = crawler.spline();
        double x0 = crawler.position().x();
        crawler.brake();
        assertTrue(crawler.braking());
        assertSame(old, crawler.spline());
        assertEquals(4, crawler.speed());

        double expected = 4;
        while (crawler.braking()) {
            run.tick();
            expected = Math.max(0, expected - SLOW_BRAKES.acceleration() * DT);
            assertEquals(expected, crawler.speed(), 1e-9);
            assertFalse(crawler.arrived());
            if (crawler.braking()) {
                assertSame(old, crawler.spline());
                assertVec(old.position(crawler.progress()), crawler.position(), 1e-12);
                assertVec(Vec3.UNIT_X, crawler.forward(), 1e-9);
            }
        }
        // Stopped: the path is dropped as by stop(), and it stays there.
        assertNull(crawler.spline());
        assertNull(crawler.pendingPath());
        assertFalse(crawler.arrived());
        assertEquals(0, crawler.speed());
        assertSame(Vec3.ZERO, crawler.velocity());
        // The discrete stop from 4 b/s at 2 b/s²: 3.9, 3.8, ... 0.1 b/s for one tick each.
        assertEquals(x0 + 3.9, crawler.position().x(), 1e-6);
        assertVec(Vec3.UNIT_X, crawler.forward(), 1e-9);
        Vec3 here = crawler.position();
        run.ticks(10);
        assertEquals(here, crawler.position());
    }

    @Test
    void brakeAroundACornerNeverLeavesThePath() {
        // At cruise on the floor 1.5 blocks before the corner: the stop (3.9 blocks) is up the wall.
        Path up = floorThenWall(6, 12);
        Crawler crawler = following(up);
        Run run = new Run(crawler, SLOW_BRAKES, UP);
        while (crawler.position().x() < 5) {
            run.tick();
        }
        assertEquals(4, crawler.speed(), 1e-12);
        PathSpline old = crawler.spline();
        crawler.brake();
        int braked = run.ticks;
        double previous = crawler.progress();
        while (crawler.braking()) {
            run.tick(); // the speed changes by at most a·dt per tick
            if (crawler.braking()) {
                assertSame(old, crawler.spline());
                assertTrue(crawler.progress() >= previous);
                assertVec(old.position(crawler.progress()), crawler.position(), 1e-12);
                previous = crawler.progress();
            }
        }
        assertEquals(0, crawler.speed());
        assertTrue(crawler.position().y() > 1.5, "stopped at " + crawler.position());
        assertTrue(distanceToSpline(crawler.position(), old) < 1e-3, "stopped off the path: " + crawler.position());
        // From 4 b/s at 2 b/s²: 40 ticks, the last one reporting the stop.
        assertEquals(40, run.ticks - braked);
    }

    @Test
    void brakeBeforeADiveGoesThroughAndStopsAfterIt() {
        // A dive from x = 4 to x = 8 (arc length 4 to 8 from the start). Braking from 4 b/s at 8 b/s² takes 0.9
        // blocks, which from arc length 3.5 would end inside the rock.
        Path path = diveLine(4, 8, 20);
        Crawler crawler = following(path);
        Run run = new Run(crawler, PARAMS, UP);
        while (crawler.progress() < 3.5) {
            run.tick();
        }
        assertEquals(4, crawler.speed(), 1e-12);
        assertFalse(crawler.diving());
        PathSpline old = crawler.spline();
        crawler.brake();
        boolean dived = false;
        while (crawler.braking()) {
            run.tick();
            if (crawler.braking()) {
                assertVec(old.position(crawler.progress()), crawler.position(), 1e-12);
            }
            if (crawler.diving()) {
                dived = true;
                assertTrue(crawler.speed() > 0, "stopped inside the rock at " + crawler.position());
            }
        }
        assertTrue(dived);
        assertFalse(crawler.diving());
        double x = crawler.position().x();
        // It brakes as soon as the stop falls after the exit (x = 8.5): within one tick's move of it.
        assertTrue(x >= 8.5 - 1e-9 && x <= 8.5 + PARAMS.maxSpeed() * DT + 1e-9, "stopped at x = " + x);
    }

    @Test
    void brakeInsideADiveStopsAfterTheExit() {
        Path path = diveLine(4, 8, 20);
        MotionParams params = new MotionParams(2, 4, 0.25, 1.5, 0.3);
        Crawler crawler = following(path);
        Run run = new Run(crawler, params, UP);
        while (crawler.position().x() < 6) {
            run.tick();
        }
        assertTrue(crawler.diving());
        crawler.brake();
        while (crawler.braking()) {
            run.tick();
            if (crawler.diving()) {
                assertTrue(crawler.speed() > 0, "stopped inside the rock at " + crawler.position());
            }
        }
        double x = crawler.position().x();
        assertTrue(x >= 8.5 - 1e-9 && x <= 8.5 + params.maxSpeed() * DT + 1e-9, "stopped at x = " + x);
    }

    @Test
    void brakeNearTheEndOfThePathStopsAtItsEnd() {
        // Already braking for the end: the end of the path (a node) limits the speed as when following it.
        Path path = straight(3);
        Crawler crawler = following(path);
        Run run = new Run(crawler, SLOW_BRAKES, UP);
        while (crawler.progress() < 2.5) {
            run.tick();
        }
        assertTrue(crawler.speed() > 0);
        crawler.brake();
        while (crawler.braking()) {
            run.tick();
        }
        assertVec(path.point(3), crawler.position(), 1e-9);
        assertEquals(0, crawler.speed());
    }

    @Test
    void brakeDropsAPendingRouteAndAFollowEndsIt() {
        Crawler crawler = following(straight(40));
        Run run = new Run(crawler, SLOW_BRAKES, UP);
        run.ticks(60);
        PathSpline old = crawler.spline();
        crawler.follow(home(3));
        assertNotNull(crawler.pendingPath());
        crawler.brake();
        assertNull(crawler.pendingPath());
        assertTrue(crawler.braking());
        assertSame(old, crawler.spline());
        run.ticks(5);
        assertTrue(crawler.braking());

        // A route straight on takes over at once while braking, and is followed to its end.
        Vec3 here = crawler.position();
        int x = VoxelPos.x(VoxelPos.containing(here));
        Path on = straightFrom(x, x + 15);
        crawler.follow(on);
        assertFalse(crawler.braking());
        assertNull(crawler.pendingPath());
        assertSame(on, crawler.spline().path());
        run.toArrival();
        assertVec(on.point(on.size() - 1), crawler.position(), 1e-9);
    }

    @Test
    void brakeWhileStandingStopsAtOnce() {
        Crawler crawler = following(straight(5));
        crawler.brake();
        assertFalse(crawler.braking());
        assertNull(crawler.spline());
        assertEquals(0, crawler.speed());

        Crawler none = new Crawler(new Vec3(0.5, 0.5, 0.5), Vec3.UNIT_Y);
        none.brake();
        assertFalse(none.braking());
        none.tick(DT, PARAMS, UP);
        assertEquals(new Vec3(0.5, 0.5, 0.5), none.position());

        // At the end of a path: the path is dropped.
        Crawler arrived = following(straight(2));
        new Run(arrived, PARAMS, UP).toArrival();
        Vec3 end = arrived.position();
        arrived.brake();
        assertNull(arrived.spline());
        assertFalse(arrived.arrived());
        assertEquals(end, arrived.position());
    }

    @Test
    void randomBrakesAndReplansNeverChangeTheSpeedByMoreThanTheAccelerationLimit() {
        Random random = new Random(5);
        MotionParams params = new MotionParams(5, 3, 0.25, 1.5, 0.3);
        Crawler crawler = new Crawler(new Vec3(0.5, 0.5, 0.5), Vec3.UNIT_Y);
        Run run = new Run(crawler, params, UP);
        int brakes = 0;
        for (int round = 0; round < 300; round++) {
            if (random.nextInt(3) == 0) {
                crawler.brake();
                brakes++;
            } else {
                crawler.follow(randomFloorWalk(random, VoxelPos.containing(crawler.position()),
                        1 + random.nextInt(16)));
            }
            run.ticks(1 + random.nextInt(30));
        }
        assertTrue(brakes > 50, "brakes " + brakes);
    }

    // ---- a pending route joins the old path over real edges ---------------------------------------------------

    /** Floor y = 0 along +x up to voxel {@code floorEnd}, then up the wall x = floorEnd + 1 to {@code top}. */
    private static Path floorThenWall(int floorEnd, int top) {
        long[] nodes = new long[floorEnd + 1 + top];
        for (int x = 0; x <= floorEnd; x++) {
            nodes[x] = p(x, 0, 0);
        }
        for (int y = 1; y <= top; y++) {
            nodes[floorEnd + y] = p(floorEnd + 1, y, 0);
        }
        return path(nodes);
    }

    /** Off the skin: farther than this from every edge the crawler may use (a cut across a corner is ~1 block). */
    private static final double OFF_SKIN = 0.75;

    /** Every point of {@code spline} (sampled finely) lies within {@link #OFF_SKIN} of the edges of {@code paths}. */
    private static void assertOnTheSkin(PathSpline spline, Path... paths) {
        double worst = 0;
        Vec3 at = spline.position(0);
        for (double s = 0; s <= spline.length() + 1e-9; s += 0.02) {
            Vec3 q = spline.position(s);
            double d = distanceToPolylines(q, paths);
            if (d > worst) {
                worst = d;
                at = q;
            }
        }
        assertTrue(worst < OFF_SKIN, "the spline leaves the skin: " + worst + " from the edges at " + at);
    }

    @Test
    void routeFromBehindJoinsOverTheOldEdgesWhenBrakingEndsOnTheSameEdge() {
        // Slowly up the wall; the route comes on edge (7,3,0) -> (7,4,0) and the crawler stops on that same edge. The
        // search started back on the floor: the route home starts at (5,0,0), around the corner.
        Path up = floorThenWall(6, 8);
        MotionParams slow = new MotionParams(1, 4, 0.25, 1.5, 0.3);
        Crawler crawler = following(up);
        Run run = new Run(crawler, slow, UP);
        while (crawler.position().y() < 3.7) {
            run.tick();
        }
        PathSpline old = crawler.spline();
        int edge = old.segment(crawler.progress());
        assertEquals(p(7, 3, 0), up.node(edge));
        Path home = home(5);
        crawler.follow(home);
        assertSame(home, crawler.pendingPath());
        while (crawler.pendingPath() != null) {
            run.tick();
            if (crawler.pendingPath() != null) {
                assertEquals(edge, old.segment(crawler.progress()), "braking left the edge");
            }
        }
        assertNotSame(old, crawler.spline());
        assertOnTheSkin(crawler.spline(), up, home);
        run.toArrival();
        assertVec(home.point(home.size() - 1), crawler.position(), 1e-9);
        for (Vec3 q : run.positions) {
            assertTrue(distanceToPolylines(q, up, home) < OFF_SKIN, q + " is off the skin");
        }
    }

    @Test
    void routeStartingBehindThePendingEdgeJoinsOverTheOldEdges() {
        // At cruise up the wall: the route comes on edge (7,1,0) -> (7,2,0), braking takes the crawler ~4 blocks
        // further up. The route starts at (3,0,0): its search started on the floor, several nodes before that edge.
        Path up = floorThenWall(6, 12);
        Crawler crawler = following(up);
        Run run = new Run(crawler, SLOW_BRAKES, UP);
        while (crawler.position().y() < 1.7) {
            run.tick();
        }
        assertEquals(4, crawler.speed(), 1e-12);
        PathSpline old = crawler.spline();
        int from = old.segment(crawler.progress());
        assertEquals(p(7, 1, 0), up.node(from));
        Path home = home(3);
        crawler.follow(home);
        assertSame(home, crawler.pendingPath());
        int to = from;
        while (crawler.pendingPath() != null) {
            run.tick();
            if (crawler.pendingPath() != null) {
                to = old.segment(crawler.progress());
            }
        }
        assertTrue(to > from + 2, "stopped on edge " + to + ", the route came on " + from);
        assertOnTheSkin(crawler.spline(), up, home);
        run.toArrival();
        assertVec(home.point(home.size() - 1), crawler.position(), 1e-9);
        for (Vec3 q : run.positions) {
            assertTrue(distanceToPolylines(q, up, home) < OFF_SKIN, q + " is off the skin");
        }
    }

    @Test
    void routeOnFromTheNodeAheadGoesThroughThatNode() {
        // Slowly up the wall, stopping short of (7,4,0); the route turns there, sideways along the wall. The crawler
        // goes on to that node and turns there, as the edges do, instead of cutting straight to the next one.
        Path up = floorThenWall(6, 8);
        MotionParams slow = new MotionParams(1, 4, 0.25, 1.5, 0.3);
        Crawler crawler = following(up);
        Run run = new Run(crawler, slow, UP);
        while (crawler.position().y() < 4) {
            run.tick();
        }
        long ahead = p(7, 4, 0);
        assertEquals(p(7, 3, 0), up.node(crawler.spline().segment(crawler.progress())));
        Path turn = path(ahead, p(7, 4, 1), p(7, 4, 2), p(7, 4, 3));
        crawler.follow(turn);
        assertSame(turn, crawler.pendingPath());
        while (crawler.pendingPath() != null) {
            run.tick();
        }
        PathSpline next = crawler.spline();
        double closest = Double.POSITIVE_INFINITY;
        for (double s = 0; s <= next.length(); s += 0.001) {
            closest = Math.min(closest, next.position(s).distance(VoxelPos.center(ahead)));
        }
        assertTrue(closest < 1e-3, "passes " + closest + " from the node ahead");
        assertOnTheSkin(next, up, turn);
        run.toArrival();
        assertVec(turn.point(turn.size() - 1), crawler.position(), 1e-9);
    }

    @Test
    void routeOnFromTheEndOfALeadInGoesThroughThatNode() {
        // Heading for a single node (the spline is just the lead-in to it), a route turning sideways there comes.
        Path line = straight(20);
        MotionParams slow = new MotionParams(1, 4, 0.25, 1.5, 0.3);
        Crawler crawler = following(line);
        Run run = new Run(crawler, slow, UP);
        while (crawler.position().x() < 3) {
            run.tick();
        }
        long node = p(6, 0, 0);
        crawler.follow(path(node));
        assertNull(crawler.pendingPath());
        assertEquals(-1, crawler.spline().segment(crawler.progress()));
        while (crawler.position().x() < 5.6) {
            run.tick();
        }
        Path turn = path(node, p(6, 0, 1), p(6, 0, 2), p(6, 0, 3));
        crawler.follow(turn);
        assertSame(turn, crawler.pendingPath());
        while (crawler.pendingPath() != null) {
            run.tick();
        }
        PathSpline next = crawler.spline();
        double closest = Double.POSITIVE_INFINITY;
        for (double s = 0; s <= next.length(); s += 0.001) {
            closest = Math.min(closest, next.position(s).distance(VoxelPos.center(node)));
        }
        assertTrue(closest < 1e-3, "passes " + closest + " from the node");
        assertOnTheSkin(next, line, turn);
        run.toArrival();
        assertVec(turn.point(turn.size() - 1), crawler.position(), 1e-9);
    }

    @Test
    void routeBackFromTheNodeAheadTurnsWhereTheCrawlerStopped() {
        // Slowly along +x, on the edge 9 -> 10. The route starts at node 10 ahead but heads straight back over that
        // edge: no U-turn at node 10, the crawler turns back where it stopped (before reaching it).
        Path line = straight(20);
        MotionParams slow = new MotionParams(1, 4, 0.25, 1.5, 0.3);
        Crawler crawler = following(line);
        Run run = new Run(crawler, slow, UP);
        while (crawler.position().x() < 10.2) {
            run.tick();
        }
        Path back = home(10);
        crawler.follow(back);
        assertSame(back, crawler.pendingPath());
        double maxX = Double.NEGATIVE_INFINITY;
        while (!crawler.arrived()) {
            run.tick();
            maxX = Math.max(maxX, crawler.position().x());
        }
        assertTrue(maxX < 10.45, "went on to " + maxX);
        assertVec(line.point(0), crawler.position(), 1e-9);
    }

    // ---- face (SPEC 8: an ALERT entity turns toward a sound) ------------------------------------------------------

    /** A standing crawler on the floor heading +x. */
    private static Crawler standingTowardPlusX() {
        return new Crawler(new Vec3(0.5, 0.5, 0.5), Vec3.UNIT_Y, Vec3.UNIT_X, 0, 0);
    }

    @Test
    void faceTurnsTheForwardDirectionInPlaceTowardTheTangentPart() {
        Crawler crawler = standingTowardPlusX();
        Vec3 position = crawler.position();
        // Up and to +z: only the tangent part (+z) counts.
        Vec3 direction = new Vec3(0, 5, 3);
        crawler.face(direction);
        assertSame(direction, crawler.facing());
        for (int i = 0; i < 100; i++) {
            crawler.tick(DT, PARAMS, UP);
            assertEquals(position, crawler.position());
            assertEquals(0, crawler.speed());
            assertSame(Vec3.ZERO, crawler.velocity());
            assertEquals(1, crawler.forward().length(), 1e-12);
            assertEquals(0, crawler.forward().y(), 1e-12, "stays in the tangent plane");
        }
        assertVec(Vec3.UNIT_Z, crawler.forward(), 1e-6);
        assertSame(direction, crawler.facing(), "kept until cleared");
    }

    @Test
    void faceTurnsWithTheSmoothingOfTheNormal() {
        Crawler crawler = standingTowardPlusX();
        crawler.face(new Vec3(0, 0, -2));
        double tau = PARAMS.normalSmoothingSeconds();
        for (int i = 1; i <= 10; i++) {
            crawler.tick(DT, PARAMS, UP);
            assertEquals(90 * Math.exp(-i * DT / tau), angleDeg(new Vec3(0, 0, -1), crawler.forward()), 1e-9);
        }
        // A time constant of 0 snaps.
        Crawler snapping = standingTowardPlusX();
        snapping.face(new Vec3(1, 0, 1));
        snapping.tick(DT, new MotionParams(MAX_SPEED, ACCELERATION, 0, 1.5, 0.3), UP);
        assertVec(new Vec3(1, 0, 1).normalize(), snapping.forward(), 1e-12);
    }

    @Test
    void faceUsesTheTangentPlaneOfTheCurrentNormal() {
        // On a wall facing +x, heading up: a direction toward -x and +z turns the front toward +z.
        Crawler crawler = new Crawler(new Vec3(0.5, 3.5, 0.5), Vec3.UNIT_X, Vec3.UNIT_Y, 0, 0);
        crawler.face(new Vec3(-4, 0, 1));
        for (int i = 0; i < 100; i++) {
            crawler.tick(DT, PARAMS, p -> Vec3.UNIT_X);
        }
        assertVec(Vec3.UNIT_Z, crawler.forward(), 1e-6);
    }

    @Test
    void faceBehindTurnsAboutTheNormalRightHanded() {
        Crawler crawler = standingTowardPlusX();
        crawler.face(new Vec3(-1, 0, 0));
        double k = 1 - Math.exp(-DT / PARAMS.normalSmoothingSeconds());
        crawler.tick(DT, PARAMS, UP);
        double turned = Math.toRadians(180 * k);
        // About +y by a positive angle: +x goes toward -z.
        assertVec(new Vec3(Math.cos(turned), 0, -Math.sin(turned)), crawler.forward(), 1e-9);
        for (int i = 0; i < 100; i++) {
            crawler.tick(DT, PARAMS, UP);
            assertEquals(0, crawler.forward().y(), 1e-12);
            assertTrue(crawler.forward().z() <= 1e-12, "keeps turning the same way");
        }
        assertVec(new Vec3(-1, 0, 0), crawler.forward(), 1e-6);
    }

    @Test
    void faceAlongTheNormalOrZeroLeavesTheForwardDirection() {
        for (Vec3 direction : new Vec3[]{Vec3.UNIT_Y, new Vec3(0, -3, 0), Vec3.ZERO}) {
            Crawler crawler = standingTowardPlusX();
            crawler.face(direction);
            for (int i = 0; i < 10; i++) {
                crawler.tick(DT, PARAMS, UP);
            }
            assertEquals(Vec3.UNIT_X, crawler.forward(), "facing " + direction);
        }
    }

    @Test
    void followAndBrakeClearTheFacingStopKeepsIt() {
        Crawler crawler = standingTowardPlusX();
        crawler.face(Vec3.UNIT_Z);
        crawler.stop();
        assertEquals(Vec3.UNIT_Z, crawler.facing());
        crawler.follow(straight(3));
        assertNull(crawler.facing());

        crawler.face(Vec3.UNIT_Z);
        crawler.follow(PathSpline.of(crawler.position(), straight(3)));
        assertNull(crawler.facing());

        crawler.face(Vec3.UNIT_Z);
        crawler.brake();
        assertNull(crawler.facing());
        crawler.face(Vec3.UNIT_Z);
        crawler.brake(); // standing: stops at once, still clears
        assertNull(crawler.facing());
    }

    @Test
    void faceHasNoEffectWhileMovingOrBrakingAndTurnsOnceStanding() {
        Crawler crawler = following(straight(20));
        crawler.face(Vec3.UNIT_Z);
        Run run = new Run(crawler, PARAMS, UP);
        while (true) {
            run.tick();
            if (crawler.arrived()) {
                break;
            }
            assertVec(Vec3.UNIT_X, crawler.forward(), 1e-9);
        }
        // Arrived (from the tick that arrives on): no path left to follow, so the kept direction turns the front.
        run.ticks(100);
        assertVec(Vec3.UNIT_Z, crawler.forward(), 1e-6);
        assertSame(Vec3.ZERO, crawler.velocity());

        Crawler braking = following(straight(40));
        Run brakingRun = new Run(braking, SLOW_BRAKES, UP);
        brakingRun.ticks(60);
        braking.brake();
        braking.face(Vec3.UNIT_Z);
        while (true) {
            brakingRun.tick();
            if (!braking.braking()) {
                break;
            }
            assertVec(Vec3.UNIT_X, braking.forward(), 1e-9);
        }
        brakingRun.ticks(100);
        assertVec(Vec3.UNIT_Z, braking.forward(), 1e-6);
    }

    @Test
    void faceWhileARouteIsPendingTurnsTheFrontAtTheEndOfTheRoute() {
        // An ALERT freeze at speed: a route to the node just passed (too short to brake on) goes pending, then face.
        Crawler crawler = following(straight(40));
        Run run = new Run(crawler, SLOW_BRAKES, UP);
        run.ticks(60);
        Path goal = path(p(VoxelPos.x(VoxelPos.containing(crawler.position())), 0, 0));
        crawler.follow(goal);
        assertSame(goal, crawler.pendingPath());
        Vec3 direction = new Vec3(0, 1, 2);
        crawler.face(direction);
        while (crawler.pendingPath() != null) {
            run.tick();
            assertVec(Vec3.UNIT_X, crawler.forward(), 1e-9);
        }
        assertSame(direction, crawler.facing(), "kept across the start of the route");
        // Back to the goal along -x (no effect while moving), then the front turns toward +z in place.
        while (!crawler.arrived()) {
            run.tick();
            if (!crawler.arrived() && crawler.speed() > 0) {
                assertVec(new Vec3(-1, 0, 0), crawler.forward(), 1e-9);
            }
        }
        assertVec(goal.point(0), crawler.position(), 1e-9);
        run.ticks(100);
        assertVec(Vec3.UNIT_Z, crawler.forward(), 1e-6);
        assertVec(goal.point(0), crawler.position(), 1e-9);
        assertSame(Vec3.ZERO, crawler.velocity());
    }

    private static double distanceToPolylines(Vec3 q, Path... paths) {
        double best = Double.POSITIVE_INFINITY;
        for (Path path : paths) {
            best = Math.min(best, path.size() == 1 ? q.distance(path.point(0)) : distanceToPolyline(q, path));
        }
        return best;
    }

    private static double distanceToPolyline(Vec3 q, Path path) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i + 1 < path.size(); i++) {
            Vec3 a = path.point(i), ab = path.point(i + 1).sub(a);
            double t = Clamp.clamp(q.sub(a).dot(ab) / ab.lengthSquared(), 0, 1);
            best = Math.min(best, q.distance(a.add(ab.scale(t))));
        }
        return best;
    }
}
