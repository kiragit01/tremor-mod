package tremor.hollow;

/**
 * The plain decisions about a player's own blocks in the hollow (SPEC 12: the copies yield nothing, and nothing of the
 * player is lost there), taken by {@link HollowRules} from the state of the world. Plain Java, so they are tested
 * without the game.
 * <p>
 * What a player places in the area of the player's event is recorded as the player's
 * ({@link HollowManager#markPlaced}): it drops when broken and is given back when the event ends. The record follows
 * the block: it is looked at again at the end of every tick in which the block changed ({@link #afterTick}), and
 * dropped once nothing of the player is left there.
 */
final class PlayerBlocks {

    private PlayerBlocks() {
    }

    /** What a block a player places in the hollow takes the place of. */
    enum Replaced {
        /** Air, or a block of the copy that any block replaces (grass, water): the new block is the player's. */
        NOTHING,
        /** A block of the player (placing into it, or a tool, bone meal, wax used on it): it stays the player's. */
        PLAYERS,
        /**
         * Any other block of the copy: the placing would change it or merge into it (a slab onto its slab, wax on its
         * copper, bone meal on its sapling), and what came out would be part copy, part the player's.
         */
        COPY
    }

    /** Why a position of the hollow is looked at again at the end of the tick. */
    enum Change {
        /** A player placed a block there. */
        PLACED,
        /**
         * A player used an item there that places without a placing event: a bucket poured, a lily pad or frogspawn
         * put on water.
         */
        USED,
        /** The player's block there changed: broken, burnt, fallen, picked up, grown, waterlogged... */
        CHANGED
    }

    /** What becomes of the record of a position. */
    enum Outcome {
        /** The block there is (still) the player's: recorded, with the block now there. */
        MARK,
        /** Nothing of the player is there any more. */
        UNMARK,
        /** As it is. */
        NONE
    }

    /**
     * Whether a player may place a block in the hollow: only in the area of the player's own running event (what is
     * built beyond it would outlive the clearing of the slot), never an unplaceable one, and never into a block of
     * the copy ({@link Replaced#COPY}).
     */
    static boolean mayPlace(boolean inOwnEvent, boolean unplaceable, Replaced replaced) {
        return inOwnEvent && !unplaceable && replaced != Replaced.COPY;
    }

    /**
     * What the record does with a position at the end of a tick in which it changed.
     *
     * @param marked         the position is recorded as the player's now
     * @param unchanged      the block there is the very state it was before the placing or the use (not used for
     *                       {@link Change#CHANGED})
     * @param holdsPlayers   what is there now can be the player's: not air, fire, a moving piston or a flowing fluid
     * @param sourceOfPoured what is there now is a source of the fluid a bucket poured ({@link Change#USED})
     */
    static Outcome afterTick(Change change, boolean marked, boolean unchanged, boolean holdsPlayers,
                             boolean sourceOfPoured) {
        return switch (change) {
            // A placing put back as it was (another mod cancelled it after it was recorded) left the copy there.
            case PLACED -> unchanged || !holdsPlayers ? Outcome.UNMARK : Outcome.MARK;
            // What the use changed is the player's. A bucket poured into a source of the same fluid of the copy
            // changes nothing, yet that source is the player's now: it holds the bucket the player emptied.
            case USED -> holdsPlayers && (!unchanged || sourceOfPoured) ? Outcome.MARK : changed(marked, holdsPlayers);
            case CHANGED -> changed(marked, holdsPlayers);
        };
    }

    private static Outcome changed(boolean marked, boolean holdsPlayers) {
        if (!marked) {
            return Outcome.NONE;
        }
        return holdsPlayers ? Outcome.MARK : Outcome.UNMARK;
    }
}
