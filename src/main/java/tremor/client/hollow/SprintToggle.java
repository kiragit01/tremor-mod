package tremor.client.hollow;

/**
 * What a toggled sprint is to be once the lock on running in the hollow lets go ({@link HollowStride}), kept tick by
 * tick: on if it was on when the lock took it, turned over by every press of the sprint key under the lock (the press
 * is the toggle it always is; only the sprint does not start), and off after a death there, as vanilla's respawn turns
 * every toggle off. No game classes; main thread only.
 */
final class SprintToggle {
    /** Whether the lock held at the last tick. */
    private boolean holding;
    /** Whether the toggle is to be on once the lock lets go. */
    private boolean on;

    /**
     * A tick in which the lock holds. {@code turnedOff}: the toggle was on (as the lock takes it, or pressed on since)
     * and the lock has just turned it off; {@code dead}: the player is dying.
     */
    void held(boolean turnedOff, boolean dead) {
        if (!holding) {
            holding = true;
            on = false;
        }
        if (turnedOff) {
            on = !on;
        }
        if (dead) {
            on = false;
        }
    }

    /**
     * A tick in which the lock does not hold: whether the toggle is to be turned back on now. Only at the first such
     * tick after the lock held, and only if it is to be on, the sprint key is in toggle mode ({@code toggleMode}) and
     * not toggled on already ({@code onNow}).
     */
    boolean letGo(boolean toggleMode, boolean onNow) {
        if (!holding) {
            return false;
        }
        holding = false;
        return toggleMode && on && !onNow;
    }
}
