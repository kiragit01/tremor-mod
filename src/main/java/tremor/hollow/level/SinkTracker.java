package tremor.hollow.level;

/**
 * The pull of the soft ground on one player (SPEC 9, "Затягивание": "если игрок стоит на месте дольше X секунд, земля
 * под ним «размягчается» — он медленно проваливается (замедление + опускание), нужно двигаться"), one game tick at a
 * time. Plain Java.
 * <p>
 * The player stands still while staying within {@code stillDistance} blocks (horizontally) of where the player
 * stopped: small jitters do not count as moving, pressing against a wall does not either. After {@code stillTicks}
 * of standing still on the ground, the ground under the player softens: the softening {@link #depth} grows by
 * {@code depthPerTick} blocks per tick up to {@code maxDepth}, layer by layer ({@link #softness(double, int)}: the block
 * the player stands on first, then the ones under it). Moving on starts over at 0; what softened already stays until it
 * sets again (the game turns it back into the copied block a while after the player is off it).
 */
public final class SinkTracker {
    /** The softest {@code tremor:mire}: no collision left ({@code softness / 8} of the block is gone). */
    public static final int MAX_SOFTNESS = 8;

    /**
     * @param stillTicks    standing still this long starts the softening
     * @param stillDistance moving less than this from where the player stopped is standing still (blocks)
     * @param depthPerTick  how fast the softening goes down (blocks per tick)
     * @param maxDepth      how deep it goes at most (blocks)
     */
    public record Params(int stillTicks, double stillDistance, double depthPerTick, double maxDepth) {
    }

    private final Params params;
    private boolean anchored;
    private double anchorX;
    private double anchorZ;
    private int stillFor;
    private double depth;

    public SinkTracker(Params params) {
        this.params = params;
    }

    /**
     * One tick with the player at {@code x, z}; {@code grounded}: on ground that can soften (or already soft), not in
     * the air, swimming or on a block that never softens.
     */
    public void update(double x, double z, boolean grounded) {
        double dx = x - anchorX;
        double dz = z - anchorZ;
        if (!anchored || dx * dx + dz * dz > params.stillDistance() * params.stillDistance()) {
            anchored = true;
            anchorX = x;
            anchorZ = z;
            stillFor = 0;
            depth = 0;
            return;
        }
        stillFor++;
        if (stillFor >= params.stillTicks() && grounded) {
            depth = Math.min(params.maxDepth(), depth + params.depthPerTick());
        }
    }

    /** Whether the player has stood still long enough for the ground to soften. */
    public boolean still() {
        return anchored && stillFor >= params.stillTicks();
    }

    /** Ticks the player has stood still. */
    public int stillTicks() {
        return stillFor;
    }

    /** How deep the ground under the player has softened since the player stopped (blocks). */
    public double depth() {
        return depth;
    }

    /**
     * The softness the softening {@code depth} gives the {@code layer}-th block under the player (0: the block the
     * player stands on), 0 (still holds) .. {@link #MAX_SOFTNESS} (none of it left); -1 while the softening has not
     * reached that block.
     */
    public static int softness(double depth, int layer) {
        double into = depth - layer;
        if (!(into > 0)) {
            return -1;
        }
        return (int) Math.min(MAX_SOFTNESS, Math.floor(into * MAX_SOFTNESS));
    }

    /**
     * {@link #softness(double, int)} for a column whose top block was {@code missing} short of a full block (a slab:
     * 0.5, soul sand: 0.125): the softening starts that far into it, and the top block never stands higher than it was
     * (its softness is at least the eighths that were missing, rounded up).
     */
    public static int softness(double depth, double missing, int layer) {
        int softness = softness(depth + missing, layer);
        if (layer == 0 && softness >= 0) {
            softness = Math.max(softness, (int) Math.ceil(missing * MAX_SOFTNESS - 1e-9));
        }
        return Math.min(MAX_SOFTNESS, softness);
    }

    /**
     * The softness of the lowest block that softens when the one under it cannot (a floor one block thick over a cave,
     * the bottom of the copy): its last eighth holds, so the player is not dropped through.
     */
    public static int held(int softness) {
        return Math.min(softness, MAX_SOFTNESS - 1);
    }

    /**
     * How deep a player is pulled in: 0 with the feet at {@code surfaceY} (the top of the ground before it softened)
     * or above, 1 once the eyes ({@code eyeHeight} above the feet: a standing player's, whatever the pose) are at it or
     * below. With {@code surfaceY} the {@link #depth} and {@code feetY} 0, how far the softening got.
     */
    public static double sink(double surfaceY, double feetY, double eyeHeight) {
        return Math.max(0, Math.min(1, (surfaceY - feetY) / eyeHeight));
    }
}
