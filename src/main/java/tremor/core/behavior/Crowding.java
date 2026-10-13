package tremor.core.behavior;

import java.util.ArrayList;
import java.util.List;

/**
 * Entities do not stay near each other (there is one for each part of the world where players are,
 * {@code spawn.spacing} apart): of two that come within {@code spawn.crowdDistance} of each other (horizontally), one
 * gives way, turning away, calming and sinking into the ground ({@code tremor.entity.TremorMind#yieldTo}). The one that
 * stays is one held by something else (an Awakening took it, a frenzy drives it, its brain is off, it follows a
 * command); else the angrier one, then a command's before a natural one, then the older. One leaving already counts no
 * more. Plain Java.
 */
public final class Crowding {
    /** Angers closer than this count as the same. */
    static final double ANGER_TIE = 1;

    /**
     * @param x       where it is, blocks
     * @param held    taken by something else: it never gives way
     * @param leaving going away already: it counts no more
     */
    public record Entity(int instance, double x, double z, double anger, boolean natural, boolean held,
                         boolean leaving) {
    }

    /** {@code instance} gives way to {@code to}. */
    public record Yield(int instance, int to) {
    }

    private Crowding() {
    }

    /**
     * Who gives way to whom among {@code entities}, each at most once (one that gives way counts no more for the
     * others); none if {@code distance} is not above 0.
     */
    public static List<Yield> yields(List<Entity> entities, double distance) {
        List<Yield> yields = new ArrayList<>();
        if (!(distance > 0)) {
            return yields;
        }
        boolean[] gone = new boolean[entities.size()];
        for (int i = 0; i < entities.size(); i++) {
            gone[i] = entities.get(i).leaving();
        }
        for (int i = 0; i < entities.size(); i++) {
            for (int j = i + 1; j < entities.size() && !gone[i]; j++) {
                Entity a = entities.get(i);
                Entity b = entities.get(j);
                if (gone[j] || Math.hypot(a.x() - b.x(), a.z() - b.z()) > distance) {
                    continue;
                }
                Entity yielder = yielder(a, b);
                if (yielder != null) {
                    yields.add(new Yield(yielder.instance(), (yielder == a ? b : a).instance()));
                    gone[yielder == a ? i : j] = true;
                }
            }
        }
        return yields;
    }

    /** Which of two near each other gives way, or null if neither may (both are held). */
    static Entity yielder(Entity a, Entity b) {
        if (a.held() || b.held()) {
            return a.held() && b.held() ? null : a.held() ? b : a;
        }
        if (Math.abs(a.anger() - b.anger()) >= ANGER_TIE) {
            return a.anger() < b.anger() ? a : b;
        }
        if (a.natural() != b.natural()) {
            return a.natural() ? a : b;
        }
        return a.instance() > b.instance() ? a : b;
    }
}
