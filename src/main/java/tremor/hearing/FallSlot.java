package tremor.hearing;

/**
 * The last fall heard, so that one landing is heard once (no Minecraft classes, unit-tested directly). A landing is
 * keyed by the id of the root vehicle of what fell (the entity itself if it rides nothing) and the game time. All of
 * it happens within one {@code checkFallDamage} call of that root: {@code fallOn} fires the root's fall event, then
 * the ones its riders are forwarded ({@code Entity.causeFallDamage}), then the root posts {@code hit_ground}; so one
 * slot is enough.
 */
final class FallSlot {
    private int root;
    private long time = Long.MIN_VALUE;

    /** A fall of (or onto) {@code root} was heard at {@code time}. */
    void mark(int root, long time) {
        this.root = root;
        this.time = time;
    }

    /** Whether that landing was heard already: a rider's forwarded fall is then dropped. */
    boolean isMarked(int root, long time) {
        return time != Long.MIN_VALUE && this.time == time && this.root == root;
    }

    /** Whether that landing was heard as a fall; its {@code hit_ground}, the last part of it, clears the slot. */
    boolean consume(int root, long time) {
        if (!isMarked(root, time)) {
            return false;
        }
        this.time = Long.MIN_VALUE;
        return true;
    }
}
