package tremor.spawn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Where the natural spawn of a dimension pauses (SPEC 11, with several entities to a dimension): around the place a
 * naturally spawned entity left ({@code spawn.cooldownSeconds}) and the place one went deep after an Awakening
 * ({@code awakening.cooldownSeconds}, SPEC 9), within {@code spawn.spacing} of it, so a player far off is not kept
 * waiting by what happened to another. A pause read from a save of before 1.1.0 has no place: it holds everywhere.
 * Kept with the dimension's saved data; free of the game, so it is unit-tested directly.
 */
public final class SpawnPauses {
    /** Pauses kept at most: the oldest go first (they have long run out by then). */
    static final int MAX = 64;

    public enum Kind {
        /** A naturally spawned entity left. */
        LEFT,
        /** An Awakening ended: its entity went deep. */
        AWAKENING
    }

    /**
     * @param x        where it happened, blocks; NaN: nowhere in particular (it holds everywhere)
     * @param gameTime when it happened
     */
    public record Pause(Kind kind, double x, double z, long gameTime) {
        public boolean everywhere() {
            return Double.isNaN(x) || Double.isNaN(z);
        }
    }

    private final List<Pause> pauses = new ArrayList<>();

    /** All kept, the oldest first. */
    public List<Pause> all() {
        return Collections.unmodifiableList(pauses);
    }

    public void add(Pause pause) {
        pauses.add(pause);
        if (pauses.size() > MAX) {
            pauses.remove(0);
        }
    }

    /**
     * Ticks left at {@code now} of the longest pause of {@code kind} that holds at {@code x, z}: one that happened
     * within {@code reach} blocks of it (horizontally) or everywhere, each lasting {@code length} ticks; 0 if none.
     */
    public long left(Kind kind, double x, double z, double reach, long now, long length) {
        long left = 0;
        for (Pause pause : pauses) {
            if (pause.kind() == kind && (pause.everywhere() || Math.hypot(pause.x() - x, pause.z() - z) <= reach)) {
                left = Math.max(left, Cooldown.remaining(now, pause.gameTime(), length));
            }
        }
        return left;
    }

    /**
     * Forgets the pauses of {@code kind} that have run out at {@code now}, each lasting {@code length} ticks; returns
     * whether any was.
     */
    public boolean forgetOver(Kind kind, long now, long length) {
        return pauses.removeIf(pause -> pause.kind() == kind && Cooldown.remaining(now, pause.gameTime(), length) == 0
                && pause.gameTime() <= now);
    }
}
