package tremor.hollow;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The nightmare hollow (the user's wish: a shard thrown on the ground must be a fatal mistake, not a farm). A player an
 * Awakening takes after a frenzy over a dropped shard ({@code tremor.entity.Frenzy}) is marked; the level of their
 * hollow ({@code tremor.hollow.level.EventLevel}) is then far harder: the node {@value #NODE_FACTOR} times as far,
 * no grace before the closing, which comes {@value #CLOSE_FACTOR} times as fast, the soft ground quicker, only
 * {@value #SECONDS} seconds, and the edge is no way out but the end. Its outcomes ({@code tremor.awakening.Outcomes})
 * give no shards for a victory, and a defeat kills, whatever {@code awakening.lethal} says, and takes everything the
 * player carries with it: nothing is left on the bottom of the crater. The mark goes with the end of the event. In
 * memory only (an event does not outlive a restart). Server thread only.
 */
public final class Nightmare {
    /** The node lies this many times as far along the way. */
    public static final double NODE_FACTOR = 1.5;
    /** The closing comes this many times as fast. */
    public static final double CLOSE_FACTOR = 4;
    /** The time in the hollow (seconds). */
    public static final double SECONDS = 30;
    /** The soft ground starts after this long standing still, and pulls the player in over this long (seconds). */
    public static final double STILL_SECONDS = 1.5;
    public static final double SINK_SECONDS = 5;

    private static final Set<UUID> MARKED = new HashSet<>();

    private Nightmare() {
    }

    /** The next hollow of {@code player} is a nightmare. */
    public static void mark(UUID player) {
        MARKED.add(player);
    }

    /** Whether the hollow of {@code player} is (or will be) a nightmare. */
    public static boolean is(UUID player) {
        return MARKED.contains(player);
    }

    /** The nightmare of {@code player} is over (its event ended, or its Awakening was called off). */
    public static void clear(UUID player) {
        MARKED.remove(player);
    }
}
