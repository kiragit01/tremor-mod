package tremor.hollow;

import java.util.Locale;

/**
 * How the level inside the hollow ended for the player of an event (SPEC 9 "Исходы"), once decided
 * ({@link HollowManager#decide}). Kept with the event apart from {@link HollowEvent.End}, which tells how the player
 * part of the event came to an end (left, logged out, died...): an outcome stays what it is whatever happens after it.
 */
public enum HollowOutcome {
    /** The player destroyed the node: back out at the swallow point (SPEC 9 "Победа"). */
    VICTORY,
    /** The player got to the edge before it closed: out at the matching place of the real world (SPEC 9 "Побег"). */
    EDGE_ESCAPE,
    /** The soft ground pulled the player in: a crater at the swallow point (SPEC 9 "Поражение"). */
    DEFEAT;

    public String id() {
        return name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
