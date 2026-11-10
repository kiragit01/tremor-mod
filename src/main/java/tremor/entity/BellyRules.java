package tremor.entity;

import java.util.List;

/**
 * What the tremor keeps of what it devours, and how much ({@link Belly}): kept free of the game for tests. A mob that
 * matters to someone is kept alive, any other is digested; a world keeps at most so many of each kind, the oldest going
 * first when there is no more room.
 */
final class BellyRules {
    /** Mobs a world's belly holds at most. */
    static final int MAX_MOBS = 64;
    /** Item stacks a world's belly holds at most. */
    static final int MAX_ITEMS = 256;

    private BellyRules() {
    }

    /**
     * Whether a devoured mob is kept: never a player; else one with a name, a tamed one with an owner, a villager or
     * another merchant, or one whose type a modpack put in {@code #tremor:belly_keeps}.
     */
    static boolean keepsMob(boolean player, boolean named, boolean owned, boolean merchant, boolean tagged) {
        return !player && (named || owned || merchant || tagged);
    }

    /** Adds {@code entry} as the newest of {@code entries} and lets the oldest go while there are more than {@code max}. */
    static <T> void add(List<T> entries, T entry, int max) {
        entries.add(entry);
        while (entries.size() > max) {
            entries.remove(0);
        }
    }
}
