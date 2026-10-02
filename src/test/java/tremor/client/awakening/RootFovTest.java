package tremor.client.awakening;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RootFovTest {
    /** Vanilla's walking speed of a player (abilities) and base movement speed (attribute). */
    private static final double WALK = 0.1;

    @Test
    void attributeValueSumsLikeTheGame() {
        assertEquals(0.1, RootFov.attributeValue(0.1, 0, 0, 1), 1e-12);
        // Sprinting (+30% total) and Speed II (+40% of the base).
        assertEquals(0.1 * 1.4 * 1.3, RootFov.attributeValue(0.1, 0, 0.4, 1.3), 1e-12);
        assertEquals((0.1 + 0.05) * 2 * 0.5, RootFov.attributeValue(0.1, 0.05, 1, 0.5), 1e-12);
        assertEquals(0, RootFov.attributeValue(0.1, 0, 0.4, 0), "the root's 1 + (-1)");
    }

    @Test
    void rootedViewIsTheFreeView() {
        // Vanilla with the root: speed 0 halves the modifier.
        double rooted = RootFov.speedFactor(0, WALK);
        assertEquals(0.5, rooted, 1e-12);
        assertEquals(1, RootFov.unrooted(rooted, 0, WALK, WALK), 1e-12, "a plain walker keeps a modifier of 1");
        // Flying (1.1) and a drawn bow (0.85) stay; a Speed II player keeps its wider view.
        double free = 0.1 * 1.4;
        double expected = 1.1 * 0.85 * RootFov.speedFactor(free, WALK);
        assertEquals(expected, RootFov.unrooted(1.1 * 0.85 * rooted, 0, free, WALK), 1e-12);
    }

    @Test
    void leftAloneWhereVanillaLeftTheSpeedOut() {
        assertEquals(0.7, RootFov.unrooted(0.7, 0, WALK, 0), "walking speed 0: vanilla ignores the speed");
        assertEquals(0.7, RootFov.unrooted(0.7, 0, WALK, Double.NaN));
        assertEquals(0.7, RootFov.unrooted(0.7, 0, Double.POSITIVE_INFINITY, WALK));
    }

    @Test
    void correctionKeepsTheSettingAndOtherHandlers() {
        double fov = 0.5, unrooted = 1;
        // FOV effects at 100%: the event starts from the modifier itself.
        assertEquals(1, RootFov.corrected(fov, 1, fov, unrooted), 1e-12);
        // At 0% the event starts from 1 and the root has no effect to undo.
        assertEquals(1, RootFov.corrected(1, 0, fov, unrooted), 1e-12);
        // At 50%: lerp(0.5, 1, 0.5) = 0.75 becomes lerp(0.5, 1, 1) = 1.
        assertEquals(1, RootFov.corrected(0.75, 0.5, fov, unrooted), 1e-12);
        // Another handler's change (here -0.1) is kept.
        assertEquals(0.9, RootFov.corrected(0.4, 1, fov, unrooted), 1e-12);
    }
}
