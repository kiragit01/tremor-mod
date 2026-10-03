package tremor.hollow;

/**
 * What of a player's has left the copy of the player's event (SPEC 12: nothing of the player's is lost in the hollow):
 * {@link HollowManager} brings it back to the event's drop site at once. Plain Java.
 */
final class Strays {
    private Strays() {
    }

    /**
     * Whether something at {@code x, y, z} (a position, not a block) has left the copy {@code box}: it is past the
     * shell one block thick around the copy on a side, at any height (there is nothing to land on there: it would fall
     * through the empty hollow), or under the shell below it (dug through). Over the copy within its sides is not out:
     * what flies up comes down again.
     */
    static boolean left(HollowBox box, double x, double y, double z) {
        return x < box.minX() - 1 || x >= box.maxX() + 2 || z < box.minZ() - 1 || z >= box.maxZ() + 2
                || y < box.minY() - 1;
    }
}
