package tremor.entity;

import tremor.core.graph.SurfaceGraph;
import tremor.core.math.VoxelPos;
import tremor.hearing.SoundRules;

/**
 * What {@link TremorRuntime} remembers about following heard sounds (SPEC 7.3), kept free of the game so it is
 * unit-tested directly.
 * <ul>
 * <li><b>Retarget cooldown.</b> A heard sound replaces the followed one only after {@code retargetCooldownTicks}, or
 * if it is {@link SoundRules#LOUDER} times louder ({@link SoundRules#mayRetarget}). Giving up on a sound target (no
 * path) does not end that: the cooldown runs on from the give-up as if the sound were still followed, so the next
 * step of the same player is no reason to search again at once.</li>
 * <li><b>Unreachable sounds.</b> A failed search for a sound (no node closer to it is reachable from the start) is
 * remembered: for {@link #UNREACHABLE_TICKS} ticks, a sound whose node lies within {@link #UNREACHABLE_RADIUS} blocks
 * of that goal is not searched for again from the same start node. Without that, a player walking where the entity
 * cannot get cost a full search on almost every heard step. Any terrain change forgets it (a way may have
 * opened).</li>
 * </ul>
 */
final class SoundPursuit {
    /** How long (ticks) a failed sound search keeps sounds near its goal from being searched for again. */
    static final int UNREACHABLE_TICKS = 100;
    /** Goals within this distance (blocks, between voxel centres) of a failed one count as unreachable too. */
    static final int UNREACHABLE_RADIUS = 4;

    /** Game time of the last switch to a heard sound or of giving one up, and how loud that sound was. */
    private long lastRetarget = Long.MIN_VALUE / 4;
    private double followedPerceived;
    /** The sound target was given up: until the cooldown has run out, a new sound counts as a switch. */
    private boolean gaveUp;
    /** The last failed search for a sound: start and goal node, and when; NO_NODE if none is remembered. */
    private long failedStart = SurfaceGraph.NO_NODE, failedGoal = SurfaceGraph.NO_NODE;
    private long failedAt;

    /** Forgets everything (a new entity). */
    void reset() {
        lastRetarget = Long.MIN_VALUE / 4;
        followedPerceived = 0;
        gaveUp = false;
        forgetFailure();
    }

    /**
     * Whether a sound heard at {@code now} may become the target: {@link SoundRules#mayRetarget}, where a sound
     * target given up counts as still followed (the cooldown runs from the give-up).
     *
     * @param followingSound the entity heads for a heard sound
     */
    boolean mayRetarget(boolean followingSound, long now, int cooldown, double perceived) {
        return SoundRules.mayRetarget(followingSound || gaveUp, now - lastRetarget, cooldown, perceived,
                followedPerceived);
    }

    /** A heard sound was taken up at {@code now}: the cooldown starts, and it is the loudness to beat. */
    void retargeted(long now, double perceived) {
        lastRetarget = now;
        followedPerceived = perceived;
    }

    /** A new target was set (a sound's or a manual one), or the entity was stopped: no give-up is pending. */
    void targetSet() {
        gaveUp = false;
    }

    /** The sound target was given up at {@code now}: the cooldown restarts from here. */
    void gaveUp(long now) {
        lastRetarget = now;
        gaveUp = true;
    }

    /** A search for a sound from {@code start} to {@code goal} failed at {@code now}. */
    void searchFailed(long start, long goal, long now) {
        failedStart = start;
        failedGoal = goal;
        failedAt = now;
    }

    /**
     * Whether a search from {@code start} to {@code goal} would just repeat a recent failure: same start node, the
     * goal within {@link #UNREACHABLE_RADIUS} of the failed one, less than {@link #UNREACHABLE_TICKS} ago.
     */
    boolean knownUnreachable(long start, long goal, long now) {
        if (failedStart == SurfaceGraph.NO_NODE || start != failedStart || now - failedAt >= UNREACHABLE_TICKS
                || now < failedAt) {
            return false;
        }
        long dx = VoxelPos.x(goal) - VoxelPos.x(failedGoal);
        long dy = VoxelPos.y(goal) - VoxelPos.y(failedGoal);
        long dz = VoxelPos.z(goal) - VoxelPos.z(failedGoal);
        return dx * dx + dy * dy + dz * dz <= (long) UNREACHABLE_RADIUS * UNREACHABLE_RADIUS;
    }

    /** The terrain (or what the graph makes of it) changed: a failed search may succeed now. */
    void forgetFailure() {
        failedStart = failedGoal = SurfaceGraph.NO_NODE;
    }
}
