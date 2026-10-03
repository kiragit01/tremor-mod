package tremor.core.behavior;

import java.util.random.RandomGenerator;

import tremor.core.math.Vec3;

/** The questions the {@link Brain} asks about the world; answered by the game (graph, players, line of sight). */
public interface BrainWorld {
    /**
     * A wander destination (SPEC 5.6): a reachable surface point 16-32 blocks from {@code from}, preferably one the
     * nearest player can see, else one near that player, at least {@code minDistanceToPlayer} from every player it
     * keeps away from (which ones is the world's call; while the entity seeks in AWAKENING the way there also passes
     * none of them within reach, see {@link KeepAway}); null if none was found.
     */
    Vec3 wanderTarget(Vec3 from, double minDistanceToPlayer, RandomGenerator random);

    /** A reachable surface point within {@code radius} of {@code center} (searching around a sound); null if none. */
    Vec3 searchTarget(Vec3 center, double radius, RandomGenerator random);
}
