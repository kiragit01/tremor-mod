package tremor.entity;

/**
 * How long the bump leaves what a player dropped by dying: as long after the death as the server's owner sets
 * ({@code contact.spareDeathDropsSeconds}; the user: 0 by default, no time at all), time to come back for it; after
 * that the things are items like any other. Kept free of the game for tests; the time of the death is on the item
 * ({@link Devour}).
 */
final class DeathDrops {
    private DeathDrops() {
    }

    /**
     * Whether an item a player dropped by dying at the game time {@code died} is still left alone at {@code now}: for
     * {@code ticks} ticks from the death on, so never with 0. A death that lies in the future (the world's time was
     * set back) spares nothing: such an item would be left for ever.
     */
    static boolean spared(long died, long now, long ticks) {
        long since = now - died;
        return since >= 0 && since < ticks;
    }
}
