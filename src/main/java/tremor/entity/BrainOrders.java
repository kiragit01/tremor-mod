package tremor.entity;

import tremor.core.behavior.Brain;
import tremor.core.behavior.Decision;
import tremor.core.math.Vec3;

import java.util.Objects;
import java.util.Set;
import java.util.function.DoublePredicate;

/**
 * Turns the {@link Brain}'s decisions (SPEC 8) into orders for the body ({@link TremorRuntime}), kept free of the game
 * so it is unit-tested directly. One instance per brain; fed once per server tick with that tick's decision.
 * <ul>
 * <li><b>GO to a sound</b> (reasons {@code hunt}, {@code creep}, {@code investigate}): becomes a sound target
 * ({@link TremorEntity.TargetKind#SOUND}), which is subject to the retarget cooldown ({@link SoundPursuit}). A GO the
 * cooldown holds back is not lost: it waits as the {@linkplain #waiting() pending} goal and is ordered on the first
 * tick the cooldown allows it, unless a later decision replaces or cancels it first. While it waits, the body counts
 * as busy for the brain (the route of that GO is "being planned").</li>
 * <li><b>Any other GO</b> (wander and search legs): a {@link TremorEntity.TargetKind#ROAM} target, ordered at once;
 * it drops a pending sound GO. A wander leg (reason {@code wander}) is marked as one: its route must keep away from
 * the players ({@link TremorMind#wayClear}).</li>
 * <li><b>FREEZE</b>: ordered when the freeze starts and whenever its facing changes (a new sound during the freeze);
 * the brain repeats it on every tick of the freeze, and those repetitions order nothing. It drops a pending sound
 * GO.</li>
 * <li><b>STAY</b>: orders nothing (a pending sound GO is still ordered once the cooldown allows it); it ends a
 * freeze.</li>
 * <li><b>Suspended</b> (a {@code /tremor goto} is under way): every decision is dropped, and so is what was
 * pending.</li>
 * </ul>
 */
final class BrainOrders {
    /** {@link Decision#reason()}s of a GO toward a heard sound. */
    private static final Set<String> SOUND_REASONS = Set.of("hunt", "creep", "investigate");
    /** {@link Decision#reason()} of a GO on a wander leg. */
    private static final String WANDER_REASON = "wander";

    /** What the body is ordered to do. */
    enum Type {
        /** Nothing new. */
        NONE,
        /** Head for {@link Order#point()}. */
        GO,
        /** Stop and turn toward {@link Order#point()}. */
        FREEZE
    }

    /**
     * An order for the body.
     *
     * @param type      what to do
     * @param point     GO: the destination; FREEZE: what to face; null for NONE
     * @param sound     GO: toward a heard sound (a sound target), else a wander or search leg
     * @param wander    GO: a wander leg (not a search leg, nor a sound)
     * @param perceived GO toward a sound: how loud the brain heard it (for the retarget cooldown); 0 otherwise
     */
    record Order(Type type, Vec3 point, boolean sound, boolean wander, double perceived) {
        static final Order NONE = new Order(Type.NONE, null, false, false, 0);
    }

    /** A sound GO held back by the retarget cooldown, or null. */
    private Order pending;
    /** Facing of the freeze under way, or null if there is none. */
    private Vec3 freezeFacing;

    /**
     * The order for this tick's decision.
     *
     * @param decision    this tick's decision of the brain
     * @param loudness    loudness of the brain's last heard sound ({@link Brain#lastHeardLoudness()})
     * @param suspended   a manual goto is under way: the decision is dropped
     * @param mayRetarget whether a sound GO of the given loudness may be ordered now (the retarget cooldown,
     *                    {@link SoundPursuit#mayRetarget})
     */
    Order next(Decision decision, double loudness, boolean suspended, DoublePredicate mayRetarget) {
        Objects.requireNonNull(decision, "decision");
        if (suspended) {
            clear();
            return Order.NONE;
        }
        switch (decision.action()) {
            case GO -> {
                freezeFacing = null;
                if (!SOUND_REASONS.contains(decision.reason())) {
                    pending = null;
                    return new Order(Type.GO, decision.target(), false, WANDER_REASON.equals(decision.reason()), 0);
                }
                pending = new Order(Type.GO, decision.target(), true, false, loudness);
            }
            case FREEZE -> {
                pending = null;
                boolean fresh = !decision.facing().equals(freezeFacing);
                freezeFacing = decision.facing();
                return fresh ? new Order(Type.FREEZE, freezeFacing, false, false, 0) : Order.NONE;
            }
            case STAY -> freezeFacing = null;
        }
        if (pending != null && mayRetarget.test(pending.perceived())) {
            Order go = pending;
            pending = null;
            return go;
        }
        return Order.NONE;
    }

    /** Whether a sound GO waits for the retarget cooldown. */
    boolean waiting() {
        return pending != null;
    }

    /** Forgets the pending GO and the freeze (the body was stopped or given another target). */
    void clear() {
        pending = null;
        freezeFacing = null;
    }
}
