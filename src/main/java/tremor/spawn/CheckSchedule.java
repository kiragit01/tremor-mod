package tremor.spawn;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import tremor.core.math.Vec3;

/**
 * When each player of one level gets a natural spawn check (SPEC 11: "Шанс проверки раз в N секунд на игрока"), kept
 * free of the game so it is unit-tested directly.
 * <p>
 * A player seen for the first time is checked {@code grace} ticks later at the earliest, then every
 * {@code interval} ticks. The {@link NaturalSpawner} drops a level's schedule while nobody is in it and forgets a
 * player who left it, so joining, the level loading and arriving from another dimension all count as a first sight.
 * Players are spread over the interval: a newcomer's checks go to the middle of the largest gap between the check
 * ticks (modulo the interval) of those already scheduled ({@link #firstCheck}), so no two players share a tick while
 * there are fewer of them than ticks in the interval. A change of the interval (the config edited in game) applies at
 * once: the time left to the next check of every player checked so far is scaled to the new interval
 * ({@link #rescale}), which keeps their order and spread (only checks less than old / new interval ticks apart may come
 * to share a tick); a player still in its grace keeps its first check. A change of the grace applies to newcomers.
 * The schedule also remembers where each player was at the last check (the displacement since then is a
 * {@link Heading} source).
 */
final class CheckSchedule {
    private final Map<UUID, Entry> entries = new HashMap<>();
    /** The interval the next checks of the checked players were set with; 0 before the first {@link #due}. */
    private int interval;

    /**
     * Tick of the next check, whether the player had a check (before, the next is its first one, after the grace) and
     * position at the last one (null before the first).
     */
    private static final class Entry {
        long next;
        boolean checked;
        Vec3 position;

        Entry(long next) {
            this.next = next;
        }
    }

    /**
     * Whether the player's check is due at {@code now}; if so, the next one is {@code interval} ticks later. A player
     * not scheduled yet gets the {@link #firstCheck} from {@code now + grace}. An {@code interval} other than the one
     * of the last call first {@link #rescale}s the schedule.
     *
     * @throws IllegalArgumentException unless {@code interval >= 1} and {@code grace >= 0}
     */
    boolean due(UUID player, long now, int interval, int grace) {
        if (interval < 1 || grace < 0) {
            throw new IllegalArgumentException("interval " + interval + ", grace " + grace);
        }
        if (interval != this.interval) {
            rescale(now, interval);
        }
        Entry entry = entries.get(player);
        if (entry == null) {
            long[] scheduled = new long[entries.size()];
            int i = 0;
            for (Entry other : entries.values()) {
                scheduled[i++] = other.next;
            }
            entry = new Entry(firstCheck(now + grace, interval, scheduled));
            entries.put(player, entry);
        }
        if (now < entry.next) {
            return false;
        }
        entry.next = now + interval;
        entry.checked = true;
        return true;
    }

    /**
     * Moves the next checks of the checked players from the old interval to {@code interval}: a wait of {@code w > 0}
     * ticks from {@code now} (at most the old interval) becomes {@code floor(w * interval / old)}, so each comes within
     * the new interval, in the same order. Players not checked yet keep their first check (the grace is no part of
     * the interval).
     */
    private void rescale(long now, int interval) {
        if (this.interval > 0) {
            for (Entry entry : entries.values()) {
                long wait = entry.next - now;
                if (entry.checked && wait > 0) {
                    entry.next = now + wait * interval / this.interval;
                }
            }
        }
        this.interval = interval;
    }

    /**
     * Records where a scheduled player is at a check and returns where it was at the previous one (null if this is
     * the first).
     *
     * @throws IllegalArgumentException if the player is not scheduled
     */
    Vec3 swapPosition(UUID player, Vec3 position) {
        Entry entry = entries.get(player);
        if (entry == null) {
            throw new IllegalArgumentException("not scheduled: " + player);
        }
        Vec3 previous = entry.position;
        entry.position = position;
        return previous;
    }

    /** Where the player was at the last check, or null (not scheduled, or not checked yet). */
    Vec3 lastPosition(UUID player) {
        Entry entry = entries.get(player);
        return entry == null ? null : entry.position;
    }

    /** Number of scheduled players. */
    int size() {
        return entries.size();
    }

    /** Forgets every player not in {@code present}: they get a grace again when they come back. */
    void retain(Collection<UUID> present) {
        entries.keySet().retainAll(present);
    }

    /**
     * The first check tick of a new player: the first tick from {@code earliest} on whose phase (tick modulo
     * {@code interval}) is the middle of the largest gap between the phases of the {@code scheduled} next checks,
     * gaps counted around the interval; on a tie the gap that wraps around (from the latest phase to the earliest),
     * else the earliest one. With nobody scheduled, {@code earliest} itself.
     */
    static long firstCheck(long earliest, int interval, long[] scheduled) {
        if (scheduled.length == 0) {
            return earliest;
        }
        long[] phases = new long[scheduled.length];
        for (int i = 0; i < scheduled.length; i++) {
            phases[i] = Math.floorMod(scheduled[i], (long) interval);
        }
        Arrays.sort(phases);
        long gapStart = phases[phases.length - 1];
        long gap = phases[0] + interval - gapStart;
        for (int i = 1; i < phases.length; i++) {
            long g = phases[i] - phases[i - 1];
            if (g > gap) {
                gap = g;
                gapStart = phases[i - 1];
            }
        }
        long phase = Math.floorMod(gapStart + gap / 2, (long) interval);
        return earliest + Math.floorMod(phase - earliest, (long) interval);
    }
}
