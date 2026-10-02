package tremor.client.render;

import tremor.core.shape.AwakeningShape;

/**
 * Soft edge of the render budget while an Awakening is drawn (SPEC 16: "при превышении — уменьшать детализацию на
 * дистанции"). The deformation is kept nearest to the camera first; when the budget runs out at {@code cut} blocks,
 * the kept ground lowers smoothly over the last {@link #SOFT_EDGE} blocks before it instead of ending in a step of
 * the full breathing height. Pure math, no game classes.
 */
final class RenderBudget {
    /** Width of the soft edge, blocks. */
    static final double SOFT_EDGE = 4;

    private RenderBudget() {
    }

    /**
     * Share of its height the deformation keeps {@code distance} blocks from the camera when the budget ran out at
     * {@code cut}: {@code smoothstep((cut - distance)/SOFT_EDGE)}, 1 up to {@code cut - SOFT_EDGE}, 0 from {@code cut}.
     */
    static double fade(double distance, double cut) {
        return AwakeningShape.smoothstep((cut - distance) / SOFT_EDGE);
    }
}
