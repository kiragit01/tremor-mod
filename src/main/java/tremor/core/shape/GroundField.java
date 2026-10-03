package tremor.core.shape;

import java.util.List;
import java.util.Objects;

import tremor.core.math.Vec3;

/**
 * Ground that moves all over a region rather than in one bump, as one frame of it: the zone of an Awakening in the
 * real world (SPEC 9 phase 1, and the hill a victor comes out of: {@link AwakeningField}) and the copy around the
 * player in the hollow (SPEC 9 phase 2: {@link HollowField}). Besides the displacement along the surface normal
 * anywhere ({@link #at}), it tells the client's renderer what to scan (every surface voxel of a vertical cylinder),
 * what to bake ahead of time, and where the moving ground kicks up dust. Pure math.
 */
public interface GroundField {
    /**
     * A mound of ground rising ({@code speed > 0}) or settling ({@code speed < 0}) this frame, which shakes dust off
     * itself.
     *
     * @param origin where it rises
     * @param radius how far around the origin its flanks move noticeably
     * @param speed  how fast its peak moves, blocks per second
     */
    record Heave(Vec3 origin, double radius, double speed) {
        public Heave {
            Objects.requireNonNull(origin, "origin");
            if (!(radius >= 0) || !Double.isFinite(radius) || !Double.isFinite(speed)) {
                throw new IllegalArgumentException("bad heave: radius " + radius + ", speed " + speed);
            }
        }
    }

    /** Displacement along the surface normal at the point. */
    double at(double x, double y, double z);

    /** Upper bound of {@code |at(p)|} anywhere this frame. */
    double maxHeight();

    /**
     * About the highest the field is going to lift the point while it lasts, for baking ahead the ground that is
     * going to rise: where this stays below {@link BumpShape#RENDER_THRESHOLD}, nothing needs to be baked ahead.
     */
    double ahead(double x, double y, double z);

    /** Centre of the vertical cylinder whose surface voxels the field can move. */
    Vec3 scanCenter();

    /** Horizontal radius of that cylinder. */
    double scanRadius();

    /** Whole blocks the cylinder reaches below and above its centre. */
    int scanHeight();

    /**
     * Whether the cylinder follows something moving (the player in the hollow): when its centre moves, the ground of
     * the old cylinder is still drawn until the new one has been scanned, instead of nothing.
     */
    boolean scanFollows();

    /** The rings running over the ground this frame, for their dust. */
    List<AwakeningField.Ring> rings();

    /**
     * Share of their height the rings show at the point, 0..1: where it is small, the rings are not drawn and kick up
     * no dust.
     */
    double ringShare(double x, double y, double z);

    /** The mounds rising or settling this frame, for their dust. */
    List<Heave> heaves();
}
