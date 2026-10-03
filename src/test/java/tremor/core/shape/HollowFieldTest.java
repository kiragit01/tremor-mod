package tremor.core.shape;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import tremor.core.math.Vec3;

class HollowFieldTest {
    private static final HollowParams P = HollowParams.defaults();
    private static final Vec3 PLAYER = new Vec3(1000.3, 70, -2000.6);
    private static final Vec3 ANCHOR = new Vec3(1003, 71, -2002);
    private static final Vec3 NODE = new Vec3(1020.5, 66.5, -1990.5);
    private static final double TIME = 1234.56;

    private static HollowField field(double breath, List<AwakeningField.Ring> rings) {
        return new HollowField(P, PLAYER, ANCHOR, breath, TIME, rings);
    }

    private static AwakeningField.Ring ring(double age) {
        return new AwakeningField.Ring(NODE, age, P.ring());
    }

    private static double distance(Vec3 a, double x, double y, double z) {
        return Math.sqrt((x - a.x()) * (x - a.x()) + (y - a.y()) * (y - a.y()) + (z - a.z()) * (z - a.z()));
    }

    @Test
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> field(-0.1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> field(Double.NaN, List.of()));
        assertThrows(NullPointerException.class, () -> new HollowField(P, null, ANCHOR, 0.1, 0, List.of()));
        assertThrows(NullPointerException.class, () -> new HollowField(P, PLAYER, null, 0.1, 0, List.of()));
    }

    @Test
    void theGroundHeavesAroundThePlayerOnly() {
        HollowField f = field(0.2, List.of());
        Random random = new Random(5);
        for (int i = 0; i < 3000; i++) {
            double x = PLAYER.x() + random.nextGaussian() * 10, y = PLAYER.y() + random.nextGaussian() * 6;
            double z = PLAYER.z() + random.nextGaussian() * 10;
            double d = distance(PLAYER, x, y, z);
            double expected = 0.2 * HollowShape.near(P.breathRadius(), P.breathFade(), d)
                    * HollowShape.heave(P, TIME, x, y, z);
            assertEquals(expected, f.at(x, y, z), 1e-12);
            assertTrue(f.at(x, y, z) <= f.maxHeight() + 1e-12);
            if (d >= P.breathRadius()) {
                assertEquals(0, f.at(x, y, z), "still beyond the heaving");
            }
        }
        assertEquals(0.2, f.maxHeight(), 1e-12);
        assertEquals(0, field(0, List.of()).at(PLAYER.x(), PLAYER.y() - 0.5, PLAYER.z()));
    }

    @Test
    void theNodesRingsRunOverTheGroundNearThePlayer() {
        double age = (distance(NODE, PLAYER.x(), PLAYER.y(), PLAYER.z()) + 1) / P.ring().speed();
        HollowField f = field(0, List.of(ring(age), ring(age + 0.9), ring(P.ring().duration() + 1)));
        Random random = new Random(9);
        boolean raised = false;
        for (int i = 0; i < 4000; i++) {
            double x = PLAYER.x() + random.nextGaussian() * 12, y = PLAYER.y() + random.nextGaussian() * 6;
            double z = PLAYER.z() + random.nextGaussian() * 12;
            double toPlayer = distance(PLAYER, x, y, z);
            double share = HollowShape.near(P.ringRadius(), P.ringFade(), toPlayer);
            double swell = 1 + P.crestBoost() * HollowShape.crestShare(P, toPlayer);
            double r = distance(NODE, x, y, z);
            double expected = share * swell
                    * (Ripple.height(P.ring(), r, age) + Ripple.height(P.ring(), r, age + 0.9));
            assertEquals(expected, f.at(x, y, z), 1e-12);
            assertEquals(share, f.ringShare(x, y, z), 1e-12);
            raised |= f.at(x, y, z) > BumpShape.RENDER_THRESHOLD;
            assertTrue(f.at(x, y, z) <= f.maxHeight() + 1e-12);
        }
        assertTrue(raised, "a ring is drawn around the player");
        // Far from the player nothing moves, rings or not.
        assertEquals(0, f.at(NODE.x(), NODE.y(), NODE.z() + P.ring().speed() * age - 1));
        assertEquals(0, f.ringShare(PLAYER.x() + P.ringRadius(), PLAYER.y(), PLAYER.z()));
        assertEquals(1, f.ringShare(PLAYER.x(), PLAYER.y(), PLAYER.z()));
        // The bound counts every running ring at its height, swollen, and none that has run out.
        double bound = (1 + P.crestBoost())
                * (2 * P.ring().amplitude() - P.ring().amplitude() * (2 * age + 0.9) / P.ring().duration());
        assertEquals(bound, f.maxHeight(), 1e-12);
    }

    @Test
    void aRingSwellsAsItRunsUnderThePlayer() {
        RippleParams ring = P.ring();
        double d = distance(NODE, PLAYER.x(), PLAYER.y(), PLAYER.z());
        // The leading crest runs right through the player.
        double age = (d + ring.wavelength() / 4) / ring.speed();
        HollowField f = field(0, List.of(new AwakeningField.Ring(NODE, age, ring)));
        double crest = ring.amplitude() * (1 - age / ring.duration());
        assertEquals(crest * (1 + P.crestBoost()), f.at(PLAYER.x(), PLAYER.y(), PLAYER.z()), 1e-9);
        // The same crest 5 blocks to the side, beyond the swell but well within sight: its plain height.
        Vec3 way = PLAYER.sub(NODE).normalize();
        Vec3 side = NODE.add(way.scale(d).add(way.anyPerpendicular().scale(5)).normalize().scale(d));
        assertTrue(distance(PLAYER, side.x(), side.y(), side.z()) > P.crestRadius());
        assertEquals(crest, f.at(side.x(), side.y(), side.z()), 1e-9);
        // A ring from across the copy (35 blocks) is still drawn well above the threshold under the player.
        double far = (35 + ring.wavelength() / 4) / ring.speed();
        double under = ring.amplitude() * (1 - far / ring.duration()) * (1 + P.crestBoost());
        assertTrue(under > 6 * BumpShape.RENDER_THRESHOLD, "under the player: " + under);
    }

    @Test
    void scannedAroundAnAnchorThatFollowsThePlayer() {
        HollowField f = field(0.1, List.of());
        assertEquals(ANCHOR, f.scanCenter());
        assertEquals(P.ringRadius() + P.follow(), f.scanRadius());
        assertEquals(P.scanHeight(), f.scanHeight());
        assertTrue(f.scanFollows());
        assertTrue(f.heaves().isEmpty());
        assertEquals(PLAYER, f.player());
        // Everything the rings can raise around a player within the follow distance of the anchor is scanned.
        assertTrue(f.scanRadius() >= P.follow() + P.ringRadius());
    }

    @Test
    void aheadIsTheHighestTheGroundNearThePlayerGets() {
        HollowField f = field(0.1, List.of());
        assertEquals(P.breathEnd() + P.ringEnd() * (1 + P.crestBoost()), f.ahead(PLAYER.x(), PLAYER.y(), PLAYER.z()),
                1e-12);
        assertEquals(0, f.ahead(PLAYER.x() + P.ringRadius() + 1, PLAYER.y(), PLAYER.z()));
        // Beyond the swell of the crest, both show in full as far as the player sees in the fog (5.5 blocks).
        double beyond = P.crestRadius();
        assertTrue(P.ringRadius() - P.ringFade() >= beyond && P.breathRadius() - P.breathFade() >= beyond);
        assertEquals(P.breathEnd() + P.ringEnd(), f.ahead(PLAYER.x(), PLAYER.y() + beyond, PLAYER.z()), 1e-12);
        assertFalse(f.ahead(PLAYER.x(), PLAYER.y() + 5.5, PLAYER.z()) < BumpShape.RENDER_THRESHOLD);
        // A closed hollow's ring never rises above what was baked ahead for it.
        RippleParams closed = HollowShape.nodeRing(P, 1);
        double age = (distance(NODE, PLAYER.x(), PLAYER.y(), PLAYER.z()) + closed.wavelength() / 4) / closed.speed();
        HollowField g = field(0, List.of(new AwakeningField.Ring(NODE, age, closed)));
        Random random = new Random(11);
        for (int i = 0; i < 2000; i++) {
            double x = PLAYER.x() + random.nextGaussian() * 5, y = PLAYER.y() + random.nextGaussian() * 3;
            double z = PLAYER.z() + random.nextGaussian() * 5;
            assertTrue(g.at(x, y, z) <= g.ahead(x, y, z) + 1e-12);
        }
    }
}
