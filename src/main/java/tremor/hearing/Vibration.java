package tremor.hearing;

import tremor.core.hearing.Hearing;
import tremor.core.math.Vec3;
import tremor.world.LevelVoxelView;

/**
 * A vibration entering the ground (SPEC 7.1, 7.2), as the entity of the level evaluates it.
 *
 * @param event      what made it, for {@code lastHeard} and the debug view: the game event ({@code step}; the path for
 *                   the {@code minecraft} namespace, else the full id), {@code fall} or {@code item_land}
 * @param source     where it enters the ground: the centre of the voxel the source stands on, or of the block of a
 *                   block event
 * @param loudness   base loudness after the per-source rules (sneaking, sprinting, mounts, mobs...)
 * @param footing    insulation under the source: conductivity of that voxel (the rustling factor on leaves), the
 *                   water factor, or 0 in the air
 * @param angerBonus anger added on top when it is heard (explosions: "aggression above the scale")
 * @param note       why loudness or footing differ from the plain values ({@code sneaking}, {@code airborne}...), or null
 * @param rustling   the source rustles in the leaves it stands on (SPEC 7.2 "Шелест"): those leaves do not damp it on
 *                   its way ({@link #foliage})
 */
public record Vibration(String event, Vec3 source, double loudness, double footing, float angerBonus, String note,
                        boolean rustling) {
    /** A vibration of a source that does not rustle. */
    public Vibration(String event, Vec3 source, double loudness, double footing, float angerBonus, String note) {
        this(event, source, loudness, footing, angerBonus, note, false);
    }

    /**
     * The leaves a rustling source rustles in, for its way to the entity ({@link Hearing#perceived(
     * tremor.core.VoxelView, Vec3, Vec3, double, double, Hearing.Foliage, tremor.core.hearing.HearingParams)}): the
     * blocks of {@code #tremor:rustling} in {@code view}; null if the source does not rustle.
     */
    public Hearing.Foliage foliage(LevelVoxelView view) {
        return rustling ? (x, y, z) -> Contact.rustles(view, x, y, z) : null;
    }
}
