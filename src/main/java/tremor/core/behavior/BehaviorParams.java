package tremor.core.behavior;

/**
 * Aggression scale and stage behaviour (SPEC 8). Times in seconds, distances in blocks, loudness in "perceived"
 * units (see {@link tremor.core.hearing.Hearing}).
 *
 * @param alertAt                  anger from which the entity is ALERT (SPEC: 25)
 * @param huntAt                   anger from which it is HUNTING (SPEC: 60)
 * @param awakenAt                 anger that triggers the AWAKENING (SPEC: 100); also the top of the scale
 * @param hysteresis               a stage is left downwards only once anger is this far below its threshold
 * @param decayPerSecond           anger lost per second
 * @param quietAfterSeconds        after this long without a heard sound...
 * @param quietDecayFactor         ...anger decays this many times faster (SPEC: "faster if it has heard nothing
 *                                 for a long time")
 * @param dormantReactLoudness     a DORMANT entity only goes after sounds at least this loud ("almost ignores
 *                                 sounds, only strong ones"); quieter heard sounds still add anger
 * @param alertFreezeSeconds       an ALERT entity freezes and turns toward each sound for this long
 * @param alertLoseInterestSeconds an ALERT entity that hears nothing this long stops creeping and wanders again
 * @param huntSearchRadius         a HUNTING entity that finds nobody at the sound searches this far around it...
 * @param huntSearchSeconds        ...for this long, waiting for a new sound
 * @param wanderPauseSeconds       mean pause between two legs of wandering
 * @param minWanderDistance        DORMANT wander targets keep at least this far from the players (SPEC 5.6), and so
 *                                 do those of AWAKENING from the players who can be taken (a quiet player is not
 *                                 found by chance, SPEC 8; which players is the {@link BrainWorld}'s answer); ALERT and
 *                                 HUNTING ones only with {@code wanderKeepAway}. In every stage a point the nearest
 *                                 player sees is preferred only from at least this far
 *                                 ({@link BrainWorld#wanderTarget}). Going for a sound and searching around it keep
 *                                 away from nobody
 * @param seekSearchRadius         an entity seeking in AWAKENING searches only this far around the last sound
 *                                 (instead of {@code huntSearchRadius}), so that a player who gets quietly a little
 *                                 farther than this plus its reach ({@code awakening.reachDistance}) from the noise is
 *                                 out of reach of the search (a search point is the centre of a voxel, up to half a
 *                                 voxel diagonal past this; on open ground: a route around rock may bend farther out)
 * @param wanderKeepAway           ALERT and HUNTING wander targets keep {@code minWanderDistance} from the players
 *                                 too; without it (SPEC 5.6, the default) they are drawn at random and may end near a
 *                                 player, as a warden roams
 */
public record BehaviorParams(double alertAt, double huntAt, double awakenAt, double hysteresis, double decayPerSecond,
                             double quietAfterSeconds, double quietDecayFactor, double dormantReactLoudness,
                             double alertFreezeSeconds, double alertLoseInterestSeconds, double huntSearchRadius,
                             double huntSearchSeconds, double wanderPauseSeconds, double minWanderDistance,
                             double seekSearchRadius, boolean wanderKeepAway) {
    public BehaviorParams {
        if (!(0 < alertAt && alertAt < huntAt && huntAt < awakenAt) || !(hysteresis >= 0)
                || !(hysteresis < alertAt) || !(decayPerSecond >= 0) || !(quietAfterSeconds >= 0)
                || !(quietDecayFactor >= 1) || !(dormantReactLoudness >= 0) || !(alertFreezeSeconds >= 0)
                || !(alertLoseInterestSeconds >= 0) || !(huntSearchRadius >= 0) || !(huntSearchSeconds >= 0)
                || !(wanderPauseSeconds >= 0) || !(minWanderDistance >= 0) || !(seekSearchRadius >= 0)) {
            throw new IllegalArgumentException(toString());
        }
    }

    public static BehaviorParams defaults() {
        return new BehaviorParams(25, 60, 100, 3, 0.5, 20, 3, 0.35, 2.5, 12, 8, 20, 4, 24, 4, false);
    }
}
