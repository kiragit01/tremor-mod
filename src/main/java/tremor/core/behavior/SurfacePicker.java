package tremor.core.behavior;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

import tremor.core.VoxelView;
import tremor.core.graph.SurfaceGraph;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.voxel.LineOfSight;

/**
 * Picks surface points for the behaviour: wander and search targets (SPEC 5.6, 8) and spawn points (SPEC 11), by
 * random sampling over a {@link SurfaceGraph} and line of sight through its {@link SurfaceGraph#view() view}.
 * <p>
 * Candidates are columns: a horizontal point is drawn and the surface node nearest in height to a reference level is
 * taken in its column ({@link #nodeInColumn}), so floors are found on uneven ground. Reachability over the graph is
 * never checked here: the body's A* finds out. The sampling methods draw exactly two values from {@code random} per
 * attempt, so the result is deterministic for a seeded generator and a given world. Their cost is bounded by
 * {@code attempts} column scans and at most {@code attempts} line-of-sight rays; every column scanned memoizes node
 * flags in the graph (see {@link SurfaceGraph#sectionCount()}), and only voxels the view has are read.
 */
public final class SurfacePicker {
    /** Face directions -x, +x, -y, +y, -z, +z, the order {@link #viewPoint} breaks ties in. */
    private static final int[] DX = {-1, 1, 0, 0, 0, 0}, DY = {0, 0, -1, 1, 0, 0}, DZ = {0, 0, 0, 0, -1, 1};
    /**
     * Half the diagonal of a voxel, {@code √3 / 2}: the farthest a point of a voxel is from its centre. A spawn
     * candidate's distance band is widened by it ({@link #spawnPoint}).
     */
    public static final double HALF_VOXEL_DIAGONAL = Math.sqrt(3) / 2;
    /**
     * Steepest mean slope of the player's way ahead, as rise per block of horizontal travel, for a spawn point near
     * it ({@link #spawnPoint}): 1, 45°, a staircase.
     */
    public static final double ROUTE_MAX_GRADE = 1;

    private SurfacePicker() {
    }

    /**
     * The first surface node of the column {@code x, z} in the order {@code y0, y0 + 1, y0 - 1, y0 + 2, y0 - 2, ...}
     * up to {@code y0 ± range}: the one nearest in height to {@code y0} (above on a tie). {@link SurfaceGraph#NO_NODE}
     * if there is none (also for a negative range).
     */
    public static long nodeInColumn(SurfaceGraph graph, int x, int z, int y0, int range) {
        Objects.requireNonNull(graph, "graph");
        for (int k = 0; k <= range; k++) {
            if (graph.isNode(x, y0 + k, z)) {
                return VoxelPos.pack(x, y0 + k, z);
            }
            if (k > 0 && graph.isNode(x, y0 - k, z)) {
                return VoxelPos.pack(x, y0 - k, z);
            }
        }
        return SurfaceGraph.NO_NODE;
    }

    /**
     * The point a viewer must see for the node to count as visible: the centre of the open face neighbour of the
     * node whose direction is most aligned with its smoothed normal ({@link SurfaceGraph#normal}); ties (and a ZERO
     * normal) go to the first in the order -x, +x, -y, +y, -z, +z. On a floor it is the air voxel above the node, on
     * a wall the one in front of it, at a convex edge the side the normal leans to. Unlike {@code centre + k·normal}
     * it is always an open voxel, also where a diagonal normal points at a solid voxel. Null if the voxel has no open
     * face neighbour (it is no surface voxel).
     */
    public static Vec3 viewPoint(SurfaceGraph graph, long node) {
        Objects.requireNonNull(graph, "graph");
        int x = VoxelPos.x(node), y = VoxelPos.y(node), z = VoxelPos.z(node);
        Vec3 normal = graph.normal(x, y, z);
        VoxelView view = graph.view();
        int best = -1;
        double bestDot = Double.NEGATIVE_INFINITY;
        for (int t = 0; t < 6; t++) {
            if (!view.isOpen(x + DX[t], y + DY[t], z + DZ[t])) {
                continue;
            }
            double dot = normal.x() * DX[t] + normal.y() * DY[t] + normal.z() * DZ[t];
            if (dot > bestDot) {
                best = t;
                bestDot = dot;
            }
        }
        return best < 0 ? null : Vec3.voxelCenter(x + DX[best], y + DY[best], z + DZ[best]);
    }

    /**
     * Whether a viewer at {@code eye} sees the node: {@link LineOfSight#clear} from the eye to its {@link #viewPoint}
     * (false if it has none).
     */
    public static boolean visible(SurfaceGraph graph, Vec3 eye, long node) {
        Objects.requireNonNull(eye, "eye");
        Vec3 point = viewPoint(graph, node);
        return point != null && LineOfSight.clear(graph.view(), eye, point);
    }

    /**
     * A wander destination (SPEC 5.6). Each of up to {@code attempts} candidates: a horizontal direction drawn
     * uniformly, a horizontal distance drawn uniformly from [{@code minRadius}, {@code maxRadius}] from {@code from},
     * and the {@link #nodeInColumn} of that point around {@code floor(from.y)} within {@code verticalRange}; the centre
     * of its voxel is acceptable if it keeps away from the players of {@code keepAway}: it ends far enough from every
     * one ({@link KeepAway#endsClear}), and the straight way to it from {@code from} passes none of them too near
     * ({@link KeepAway#passesClear(Vec3, Vec3)}; the body's route may bend, so it is tested again once planned). With
     * a viewer, the first acceptable candidate it sees wins: {@link #visible} from {@code viewerEye}, and at least
     * {@code seenFrom} from that eye. Otherwise (no viewer, {@code viewerEye} null, or none seen so) the first
     * acceptable candidate wins, whatever the viewer sees of it: a candidate nearer to the eye than {@code seenFrom}
     * is not preferred for being in sight, it can only come first among the others at random. The viewer draws no leg
     * toward itself: with nothing in its sight (a player hiding in a closed hut, say) or nothing but its own
     * surroundings, the leg is as random as one nobody watches, not one that ends as near to that player as
     * {@code keepAway} allows. Whether the target is reachable over the graph is not checked: the body's path search
     * finds out.
     *
     * @param seenFrom a candidate nearer than this to {@code viewerEye} does not count as seen by the viewer (0: every
     *                 visible one does); independent of {@code keepAway}, which rejects candidates outright
     * @return the centre of the chosen node's voxel ({@link VoxelPos#center}), or null if no candidate qualified
     * @throws IllegalArgumentException unless {@code 0 <= minRadius <= maxRadius}
     */
    public static Vec3 wanderTarget(SurfaceGraph graph, Vec3 from, double minRadius, double maxRadius,
                                    int verticalRange, Vec3 viewerEye, double seenFrom, KeepAway keepAway,
                                    RandomGenerator random, int attempts) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(keepAway, "keepAway");
        Objects.requireNonNull(random, "random");
        if (!(0 <= minRadius && minRadius <= maxRadius) || Double.isInfinite(maxRadius)) {
            throw new IllegalArgumentException("radii " + minRadius + ", " + maxRadius);
        }
        int y0 = (int) Math.floor(from.y());
        Vec3 fallback = null;
        for (int i = 0; i < attempts; i++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double distance = minRadius + random.nextDouble() * (maxRadius - minRadius);
            long node = nodeInColumn(graph, (int) Math.floor(from.x() + Math.cos(angle) * distance),
                    (int) Math.floor(from.z() + Math.sin(angle) * distance), y0, verticalRange);
            if (node == SurfaceGraph.NO_NODE) {
                continue;
            }
            Vec3 center = VoxelPos.center(node);
            if (!keepAway.endsClear(center) || !keepAway.passesClear(from, center)) {
                continue;
            }
            if (viewerEye == null || center.distance(viewerEye) >= seenFrom && visible(graph, viewerEye, node)) {
                return center;
            }
            if (fallback == null) {
                fallback = center;
            }
        }
        return fallback;
    }

    /**
     * A search point around {@code center} (SPEC 8 HUNTING: "рыщет вокруг"). Each of up to {@code attempts}
     * candidates: a point drawn uniformly by area from the horizontal disk of {@code radius} around {@code center},
     * and the {@link #nodeInColumn} of its column around {@code floor(center.y)} within {@code ceil(radius / 2) + 2}.
     * The first candidate with a node wins. Reachability is not checked (see {@link #wanderTarget}).
     *
     * @return the centre of the node's voxel, or null if no candidate had one
     * @throws IllegalArgumentException unless {@code radius} is finite and {@code >= 0}
     */
    public static Vec3 searchTarget(SurfaceGraph graph, Vec3 center, double radius, RandomGenerator random,
                                    int attempts) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(random, "random");
        if (!(radius >= 0) || Double.isInfinite(radius)) {
            throw new IllegalArgumentException("radius " + radius);
        }
        int y0 = (int) Math.floor(center.y());
        int range = (int) Math.ceil(radius / 2) + 2;
        for (int i = 0; i < attempts; i++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double distance = radius * Math.sqrt(random.nextDouble());
            long node = nodeInColumn(graph, (int) Math.floor(center.x() + Math.cos(angle) * distance),
                    (int) Math.floor(center.z() + Math.sin(angle) * distance), y0, range);
            if (node != SurfaceGraph.NO_NODE) {
                return VoxelPos.center(node);
            }
        }
        return null;
    }

    /**
     * A chosen spawn point.
     *
     * @param node      the surface node
     * @param position  the centre of its voxel
     * @param visible   whether the player's eye sees it ({@link #visible})
     * @param nearRoute whether it lies near the player's route ahead (see {@link #spawnPoint})
     */
    public record SpawnPick(long node, Vec3 position, boolean visible, boolean nearRoute) {
    }

    /**
     * A spawn point (SPEC 11): a surface node {@code minDistance}..{@code maxDistance} from the player, which the
     * player sees or will pass near.
     * <p>
     * {@code moveDirection} is the player's motion; only its horizontal part counts, and null or an (almost) zero
     * one means standing. Each of up to {@code attempts} candidates is a horizontal point: while moving, every other
     * attempt (the first, third, ...) is drawn from the route strip ahead (a distance uniform in
     * [{@code minDistance}, {@code maxDistance}] along the motion, a lateral offset uniform within
     * {@code ±routeHalfWidth}), the others uniformly by area from the annulus [{@code minDistance},
     * {@code maxDistance}] around {@code feet}. The candidate is the {@link #nodeInColumn} of that point around
     * {@code floor(feet.y)} within {@code verticalRange}; the centre of its voxel must be within
     * [{@code minDistance}, {@code maxDistance}] of {@code feet} in 3D, give or take {@link #HALF_VOXEL_DIAGONAL}
     * (the rounding of a point to its voxel's centre, so that {@code minDistance == maxDistance} still finds nodes),
     * and {@code allowed} (spawn protection; null accepts everything) must accept it. It is accepted if it is
     * {@link #visible} from {@code eye}, or {@link #nearRoute near the route}: moving, ahead by a horizontal distance
     * {@code t > 0} along the motion, at most {@code routeHalfWidth} to the side of it (horizontally), and with the
     * top of its voxel (where one stands on a floor node) within {@code routeHalfWidth + t·}{@link #ROUTE_MAX_GRADE}
     * of the feet in height: the way may climb or fall, but a cave floor far above or below it is no part of it.
     * Reachability is not checked (see {@link #wanderTarget}).
     *
     * @return the first accepted candidate, or null
     * @throws IllegalArgumentException unless {@code 0 <= minDistance <= maxDistance} (finite) and
     *                                  {@code routeHalfWidth >= 0}
     */
    public static SpawnPick spawnPoint(SurfaceGraph graph, Vec3 feet, Vec3 eye, Vec3 moveDirection, double minDistance,
                                       double maxDistance, double routeHalfWidth, int verticalRange,
                                       Predicate<Vec3> allowed, RandomGenerator random, int attempts) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(feet, "feet");
        Objects.requireNonNull(eye, "eye");
        Objects.requireNonNull(random, "random");
        if (!(0 <= minDistance && minDistance <= maxDistance) || Double.isInfinite(maxDistance)
                || !(routeHalfWidth >= 0)) {
            throw new IllegalArgumentException("distances " + minDistance + ", " + maxDistance + ", route half width "
                    + routeHalfWidth);
        }
        Vec3 ahead = moveDirection == null ? Vec3.ZERO : new Vec3(moveDirection.x(), 0, moveDirection.z()).normalize();
        boolean moving = !ahead.isNearZero();
        int y0 = (int) Math.floor(feet.y());
        double min2 = minDistance * minDistance, max2 = maxDistance * maxDistance;
        for (int i = 0; i < attempts; i++) {
            double u = random.nextDouble(), v = random.nextDouble();
            double px, pz;
            if (moving && i % 2 == 0) {
                double along = minDistance + u * (maxDistance - minDistance);
                double lateral = (2 * v - 1) * routeHalfWidth;
                // The side direction is ahead turned by 90° about the vertical.
                px = feet.x() + ahead.x() * along - ahead.z() * lateral;
                pz = feet.z() + ahead.z() * along + ahead.x() * lateral;
            } else {
                double r = Math.sqrt(min2 + u * (max2 - min2));
                double angle = v * 2 * Math.PI;
                px = feet.x() + Math.cos(angle) * r;
                pz = feet.z() + Math.sin(angle) * r;
            }
            long node = nodeInColumn(graph, (int) Math.floor(px), (int) Math.floor(pz), y0, verticalRange);
            if (node == SurfaceGraph.NO_NODE) {
                continue;
            }
            Vec3 position = VoxelPos.center(node);
            double distance = position.distance(feet);
            if (distance < minDistance - HALF_VOXEL_DIAGONAL || distance > maxDistance + HALF_VOXEL_DIAGONAL
                    || allowed != null && !allowed.test(position)) {
                continue;
            }
            boolean nearRoute = moving && nearRoute(feet, ahead, position, routeHalfWidth);
            boolean visible = visible(graph, eye, node);
            if (visible || nearRoute) {
                return new SpawnPick(node, position, visible, nearRoute);
            }
        }
        return null;
    }

    /**
     * The "near the route" test of {@link #spawnPoint}: whether the node whose voxel centre is {@code p} lies near the
     * way of a player at {@code feet} going along {@code ahead}, a horizontal unit vector (its y is ignored). With
     * {@code t} the horizontal distance of {@code p} ahead along it: {@code t > 0}, at most {@code halfWidth} to the
     * side horizontally, and the top of the voxel ({@code p.y + 0.5}) within
     * {@code halfWidth + t·}{@link #ROUTE_MAX_GRADE} of {@code feet.y}.
     */
    public static boolean nearRoute(Vec3 feet, Vec3 ahead, Vec3 p, double halfWidth) {
        double dx = p.x() - feet.x(), dz = p.z() - feet.z();
        double t = dx * ahead.x() + dz * ahead.z();
        double side = Math.abs(dz * ahead.x() - dx * ahead.z());
        double rise = p.y() + 0.5 - feet.y();
        return t > 0 && side <= halfWidth && Math.abs(rise) <= halfWidth + t * ROUTE_MAX_GRADE;
    }
}
