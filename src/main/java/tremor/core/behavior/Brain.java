package tremor.core.behavior;

import java.util.Locale;
import java.util.Objects;
import java.util.SplittableRandom;

import tremor.core.math.Vec3;

/**
 * Stage behaviour of the entity (SPEC 8, 5.6), as a deterministic state machine fed with what it hears and where it
 * is; the body (server runtime) carries out the {@link Decision}s.
 * <ul>
 *   <li><b>DORMANT</b>: wanders lazily (pauses of about {@code wanderPauseSeconds}, targets from
 *   {@link BrainWorld#wanderTarget} kept {@code minWanderDistance} from the players). Goes after a heard sound only
 *   if it is at least {@code dormantReactLoudness} loud ("investigate").</li>
 *   <li><b>ALERT</b>: on every heard sound it freezes and turns toward it for {@code alertFreezeSeconds}; then creeps
 *   toward the last heard position. With nothing heard for {@code alertLoseInterestSeconds} it goes back to
 *   wandering (the anger meanwhile decays faster, see {@link AngerMeter}).</li>
 *   <li><b>HUNTING</b>: goes straight for each heard sound; arriving with nothing new heard it searches around the
 *   last sound ({@link BrainWorld#searchTarget}, radius {@code huntSearchRadius}) for {@code huntSearchSeconds}, then
 *   wanders.</li>
 *   <li><b>AWAKENING</b>: seeking a player ({@link Seeking}): behaves like HUNTING (goes for each heard sound, searches
 *   around the last one), except that it searches only {@code seekSearchRadius} around the sound, and with no sound
 *   to go for it wanders as far from the players as DORMANT does (the world keeps those legs and their ways away from
 *   the players who can be taken, {@link BrainWorld#wanderTarget}): a player who makes a noise, then gets quietly a
 *   little farther from it than that radius plus the reach before the entity is there, and keeps still, is not found
 *   by chance (SPEC 8: "Затаиться и переждать пик — рабочая стратегия"; a search point is the centre of a surface
 *   voxel, up to half a voxel diagonal past the radius). The body is faster and its bump higher, and the Awakening
 *   starts once it has reached a player, or the entity calms down to HUNTING when the seeking runs out.</li>
 * </ul>
 * ALERT and HUNTING wandering is random (SPEC 5.6): its legs may end near a player, as a warden roams, unless
 * {@code wanderKeepAway} keeps them {@code minWanderDistance} away too. Going for a sound and searching around it are
 * no wander legs and keep away from nobody.
 * Deterministic for a given seed and input sequence.
 *
 * <h2>Contract with the body</h2>
 * <ul>
 *   <li>Each server tick: {@link #hear} for every sound heard since the last tick, then one {@link #tick}, with the
 *   stage the {@link AngerMeter} has <em>after</em> this tick's sounds were added (so the sound that makes the entity
 *   ALERT is already reacted to as ALERT).</li>
 *   <li>Several sounds before one tick: only the loudest counts (ties: the latest). A heard sound is consumed by the
 *   next tick, and it always updates {@link #lastHeard()} and resets {@link #secondsSinceHeard()}, even when the
 *   stage ignores it (a weak sound while DORMANT).</li>
 *   <li><b>GO is emitted once</b>, on the tick the destination is decided (a new sound, a new wander or search leg,
 *   the end of a freeze, or a stage change that re-targets); on the following ticks the brain says STAY, meaning
 *   "keep following that route". A new decision emits GO even if the target equals the current one.</li>
 *   <li><b>FREEZE is repeated on every tick</b> of the freeze (stand still, face {@code facing}).</li>
 *   <li>{@code idle} must be false while the route of the last GO is being planned or followed, and true once the
 *   body has no route (arrived, could not plan one, gave up, or stopped by a FREEZE). The brain takes "idle" after a
 *   GO as "arrived".</li>
 * </ul>
 *
 * <h2>Details</h2>
 * <ul>
 *   <li>Wander pauses are drawn uniformly from [0.5, 1.5) x {@code wanderPauseSeconds} and run only while the body is
 *   idle (a route still being followed finishes first). After the pause the brain asks for a wander target; if there
 *   is none it waits another pause. A DORMANT investigation, once arrived, goes back to wandering (pause first).</li>
 *   <li>{@code minWanderDistance} is passed to {@link BrainWorld#wanderTarget} while DORMANT (SPEC 5.6) and while
 *   AWAKENING (seeking); ALERT and HUNTING wandering passes it only with {@code wanderKeepAway}, else 0.</li>
 *   <li>Giving up a chase (ALERT losing interest, a HUNTING search running out, dropping to DORMANT): an idle entity
 *   starts wandering with a pause; a moving one asks for a wander leg right away, so the chase route is replaced (if
 *   no wander target is found, it finishes its route and then pauses).</li>
 *   <li>ALERT loses interest {@code alertLoseInterestSeconds} after the last heard sound (the freeze counts), while
 *   creeping or while listening at the sound (idle after the creep).</li>
 *   <li>HUNTING searches until {@code huntSearchSeconds} have passed since the last heard sound (time spent going to
 *   the sound counts); a search leg is asked from {@link BrainWorld#searchTarget} each time the body is idle again,
 *   within {@code huntSearchRadius} of the sound ({@code seekSearchRadius} while AWAKENING); if none is found, it asks
 *   again after {@link #SEARCH_RETRY_SECONDS} (still idle).</li>
 * </ul>
 *
 * <h2>Stage changes</h2>
 * The brain keeps its activity across a stage change where it still makes sense and converts it otherwise:
 * <ul>
 *   <li>to DORMANT: wandering and investigating go on; any chase (frozen, creeping, listening, hunting, searching)
 *   is given up as above.</li>
 *   <li>to ALERT: wandering goes on; investigating or hunting becomes creeping toward the last heard position (no new
 *   GO if that is the current target); searching becomes listening (the current search leg is finished).</li>
 *   <li>to HUNTING or AWAKENING: frozen goes straight for the last sound; investigating or creeping becomes hunting
 *   (no new GO if the target is the same); listening starts the search; wandering goes for the last heard sound if
 *   it is younger than {@code huntSearchSeconds}, otherwise keeps wandering.</li>
 * </ul>
 */
public final class Brain {
    /** A HUNTING search that found no search point asks again after this long. */
    public static final double SEARCH_RETRY_SECONDS = 1.0;

    /** Tolerance for timers fed with sums of tick lengths. */
    private static final double EPS = 1e-9;

    private enum Mode {
        /** Wandering: waiting (idle) before the next leg. */
        PAUSE,
        /** Wandering: following a wander leg. */
        WANDER,
        /** DORMANT: going to a loud sound. */
        INVESTIGATE,
        /** ALERT: standing, facing the last sound. */
        FREEZE,
        /** ALERT: going to the last heard position. */
        CREEP,
        /** ALERT: standing at the sound after the creep. */
        LISTEN,
        /** HUNTING: going to the last heard sound. */
        HUNT,
        /** HUNTING: search legs around the last heard sound. */
        SEARCH
    }

    private final BehaviorParams params;
    private final SplittableRandom random;

    private Vec3 pendingPosition;
    private double pendingLoudness;

    private Vec3 lastHeard;
    private double lastHeardLoudness;
    private double secondsSinceHeard = Double.POSITIVE_INFINITY;

    /** Stage of the last tick; null before the first one. */
    private Stage stage;
    private Mode mode = Mode.PAUSE;
    /** PAUSE: pause left; FREEZE: freeze left; SEARCH: wait before asking for a search point again. */
    private double timer;
    /** Target of the last GO; null after a FREEZE (the body stopped) or before any GO. */
    private Vec3 goal;

    public Brain(BehaviorParams params, long seed) {
        this.params = Objects.requireNonNull(params, "params");
        this.random = new SplittableRandom(seed);
        this.timer = drawPause();
    }

    /** A sound the entity heard (perceived >= the hearing threshold) since the last tick. */
    public void hear(Vec3 position, double perceived) {
        Objects.requireNonNull(position, "position");
        if (Double.isNaN(perceived)) {
            return;
        }
        if (pendingPosition == null || perceived >= pendingLoudness) {
            pendingPosition = position;
            pendingLoudness = perceived;
        }
    }

    /**
     * Decides for this tick.
     *
     * @param dt       seconds since the last tick
     * @param stage    current stage (from the {@link AngerMeter})
     * @param position current position of the entity
     * @param idle     true if the body has no route left to follow (arrived, stopped, or never had one)
     */
    public Decision tick(double dt, Stage stage, Vec3 position, boolean idle, BrainWorld world) {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(world, "world");
        double step = dt > 0 ? dt : 0;
        secondsSinceHeard += step;
        boolean heard = pendingPosition != null;
        if (heard) {
            lastHeard = pendingPosition;
            lastHeardLoudness = pendingLoudness;
            secondsSinceHeard = 0;
            pendingPosition = null;
        }
        this.stage = stage;
        return switch (stage) {
            case DORMANT -> dormant(step, heard, position, idle, world);
            case ALERT -> alert(step, heard, position, idle, world);
            case HUNTING, AWAKENING -> hunting(step, heard, position, idle, world);
        };
    }

    /** Short description of the current state for /tremor info, e.g. "searching, 12 s left". */
    public String describe() {
        String prefix = (stage == null ? Stage.DORMANT : stage).name().toLowerCase(Locale.ROOT) + ": ";
        return prefix + switch (mode) {
            case PAUSE -> "wandering, pause " + seconds(timer) + " s";
            case WANDER -> "wandering";
            case INVESTIGATE -> "investigating a sound";
            case FREEZE -> "frozen " + seconds(timer) + " s";
            case CREEP -> "creeping to the sound";
            case LISTEN -> "listening, loses interest in "
                    + seconds(params.alertLoseInterestSeconds() - secondsSinceHeard) + " s";
            case HUNT -> "going for the sound";
            case SEARCH -> "searching, "
                    + (long) Math.ceil(Math.max(0, params.huntSearchSeconds() - secondsSinceHeard) - EPS) + " s left";
        };
    }

    /**
     * Whether the brain stands frozen, turned toward a sound it heard while ALERT (the ring of ripples runs out from
     * it then): it listens harder meanwhile ({@code behavior.alertListenFactor}).
     */
    public boolean frozen() {
        return mode == Mode.FREEZE;
    }

    /** Position of the last heard sound (whatever the stage did with it); null if nothing was heard yet. */
    public Vec3 lastHeard() {
        return lastHeard;
    }

    /** Perceived loudness of the last heard sound; 0 if nothing was heard yet. */
    public double lastHeardLoudness() {
        return lastHeard == null ? 0 : lastHeardLoudness;
    }

    /** Seconds (sum of tick lengths) since the last heard sound; infinite if nothing was heard yet. */
    public double secondsSinceHeard() {
        return secondsSinceHeard;
    }

    private Decision dormant(double dt, boolean heard, Vec3 position, boolean idle, BrainWorld world) {
        if (heard && lastHeardLoudness >= params.dormantReactLoudness()) {
            mode = Mode.INVESTIGATE;
            return go(lastHeard, "investigate");
        }
        switch (mode) {
            case FREEZE, CREEP, LISTEN, HUNT, SEARCH -> {
                return calmDown(idle, position, world);
            }
            case INVESTIGATE -> {
                if (!idle) {
                    return Decision.stay("investigate");
                }
                startPause();
                return wander(0, true, position, world);
            }
            default -> {
                return wander(dt, idle, position, world);
            }
        }
    }

    private Decision alert(double dt, boolean heard, Vec3 position, boolean idle, BrainWorld world) {
        if (heard) {
            mode = Mode.FREEZE;
            timer = params.alertFreezeSeconds();
            goal = null;
            return Decision.freeze(lastHeard, "freeze");
        }
        switch (mode) {
            case INVESTIGATE, HUNT -> mode = Mode.CREEP;
            case SEARCH -> mode = Mode.LISTEN;
            default -> {
            }
        }
        switch (mode) {
            case FREEZE -> {
                timer -= dt;
                if (timer > EPS) {
                    return Decision.freeze(lastHeard, "freeze");
                }
                mode = Mode.CREEP;
                return go(lastHeard, "creep");
            }
            case CREEP -> {
                if (lostInterest()) {
                    return calmDown(idle, position, world);
                }
                if (!lastHeard.equals(goal)) {
                    return go(lastHeard, "creep");
                }
                if (!idle) {
                    return Decision.stay("creep");
                }
                mode = Mode.LISTEN;
                return Decision.stay("listen");
            }
            case LISTEN -> {
                if (lostInterest()) {
                    return calmDown(idle, position, world);
                }
                return Decision.stay("listen");
            }
            default -> {
                return wander(dt, idle, position, world);
            }
        }
    }

    /** HUNTING, and AWAKENING (seeking), whose search is narrower ({@code seekSearchRadius}). */
    private Decision hunting(double dt, boolean heard, Vec3 position, boolean idle, BrainWorld world) {
        boolean seeking = stage == Stage.AWAKENING;
        if (heard) {
            mode = Mode.HUNT;
            return go(lastHeard, "hunt");
        }
        switch (mode) {
            case INVESTIGATE, CREEP, FREEZE -> mode = Mode.HUNT;
            case LISTEN -> startSearch();
            case PAUSE, WANDER -> {
                if (lastHeard != null && !searchOver()) {
                    mode = Mode.HUNT;
                }
            }
            default -> {
            }
        }
        if (mode == Mode.HUNT) {
            if (!lastHeard.equals(goal)) {
                return go(lastHeard, "hunt");
            }
            if (!idle) {
                return Decision.stay("hunt");
            }
            startSearch();
            dt = 0;
        }
        if (mode == Mode.SEARCH) {
            if (searchOver()) {
                return calmDown(idle, position, world);
            }
            if (!idle) {
                return Decision.stay("search");
            }
            timer -= dt;
            if (timer <= EPS) {
                Vec3 target = world.searchTarget(lastHeard,
                        seeking ? params.seekSearchRadius() : params.huntSearchRadius(), random);
                if (target != null) {
                    timer = 0;
                    return go(target, "search");
                }
                timer = SEARCH_RETRY_SECONDS;
            }
            return Decision.stay("search");
        }
        return wander(dt, idle, position, world);
    }

    /**
     * Lazy wandering; {@link #mode} is PAUSE or WANDER. The pause runs only while idle. A leg keeps as far from the
     * players as the stage says ({@link #wanderTarget}).
     */
    private Decision wander(double dt, boolean idle, Vec3 position, BrainWorld world) {
        if (mode == Mode.WANDER) {
            if (!idle) {
                return Decision.stay("wander");
            }
            startPause();
        } else if (idle) {
            timer -= dt;
        }
        if (idle && timer <= EPS) {
            Vec3 target = wanderTarget(position, world);
            if (target != null) {
                mode = Mode.WANDER;
                return go(target, "wander");
            }
            startPause();
        }
        return Decision.stay("rest");
    }

    /** Gives up a chase: idle, it rests first; moving, it asks for a wander leg now so the chase route is replaced. */
    private Decision calmDown(boolean idle, Vec3 position, BrainWorld world) {
        startPause();
        if (idle) {
            return wander(0, true, position, world);
        }
        Vec3 target = wanderTarget(position, world);
        if (target != null) {
            mode = Mode.WANDER;
            return go(target, "wander");
        }
        return Decision.stay("rest");
    }

    /**
     * A wander leg from {@code position} (SPEC 5.6): {@code minWanderDistance} from the players while DORMANT or
     * AWAKENING, and while ALERT or HUNTING only with {@code wanderKeepAway} (else 0: a random leg).
     */
    private Vec3 wanderTarget(Vec3 position, BrainWorld world) {
        boolean keepAway = stage == Stage.DORMANT || stage == Stage.AWAKENING || params.wanderKeepAway();
        return world.wanderTarget(position, keepAway ? params.minWanderDistance() : 0, random);
    }

    private void startPause() {
        mode = Mode.PAUSE;
        timer = drawPause();
    }

    private void startSearch() {
        mode = Mode.SEARCH;
        timer = 0;
    }

    private double drawPause() {
        return params.wanderPauseSeconds() * (0.5 + random.nextDouble());
    }

    private boolean lostInterest() {
        return secondsSinceHeard >= params.alertLoseInterestSeconds() - EPS;
    }

    private boolean searchOver() {
        return secondsSinceHeard >= params.huntSearchSeconds() - EPS;
    }

    private Decision go(Vec3 target, String reason) {
        goal = target;
        return Decision.go(target, reason);
    }

    private static String seconds(double value) {
        return String.format(Locale.ROOT, "%.1f", Math.max(0, value));
    }
}
