package tremor.core.deform;

import java.util.ArrayList;
import java.util.List;

import tremor.core.VoxelView;
import tremor.core.math.Vec3;
import tremor.core.shape.BumpFrame;
import tremor.core.shape.BumpParams;
import tremor.core.surface.NormalField;

/** Finds the surface voxels a bump deforms. */
public final class SurfaceCollector {
    /**
     * Selection limits around the bump centre.
     *
     * @param radius       maximum distance of a voxel centre from the bump centre
     * @param normalBand   maximum distance of a voxel centre from the bump's tangent plane
     * @param minNormalDot minimum dot product of the voxel's smoothed normal with the bump normal
     */
    public record Options(double radius, double normalBand, double minNormalDot) {
        /** @throws IllegalArgumentException if {@code radius} is not finite and {@code >= 0}, or a value is NaN */
        public Options {
            if (!(radius >= 0) || !Double.isFinite(radius)) {
                throw new IllegalArgumentException("radius must be finite and >= 0: " + radius);
            }
            if (!(normalBand >= 0)) {
                throw new IllegalArgumentException("normalBand must be >= 0: " + normalBand);
            }
            if (Double.isNaN(minNormalDot)) {
                throw new IllegalArgumentException("minNormalDot must not be NaN");
            }
        }

        /** {@code radius = params.influenceRadius()}, {@code normalBand = 2.5}, {@code minNormalDot = -0.2}. */
        public static Options forParams(BumpParams params) {
            return new Options(params.influenceRadius(), 2.5, -0.2);
        }
    }

    private SurfaceCollector() {
    }

    /**
     * All surface voxels whose centre {@code p} satisfies {@code |p - c| <= radius}, {@code |(p - c)·n| <= normalBand}
     * and {@code smoothNormal(p)·n >= minNormalDot} ({@code c}, {@code n} from the frame). The band and normal filters
     * keep e.g. a cave ceiling right above a floor bump from bulging too. Voxels whose smoothed normal cancels to zero
     * are skipped, so every returned normal is a unit vector.
     *
     * @return a new mutable list, sorted by y, then z, then x
     */
    public static List<SurfacePoint> collect(VoxelView view, BumpFrame frame, Options options) {
        Vec3 c = frame.center(), n = frame.normal();
        double cx = c.x(), cy = c.y(), cz = c.z();
        double nx = n.x(), ny = n.y(), nz = n.z();
        double r = options.radius(), r2 = r * r;
        double band = options.normalBand(), minDot = options.minNormalDot();

        // Voxel x is inside the box if its centre x + 0.5 lies in [cx - r, cx + r].
        int minX = (int) Math.ceil(cx - r - 0.5), maxX = (int) Math.floor(cx + r - 0.5);
        int minY = (int) Math.ceil(cy - r - 0.5), maxY = (int) Math.floor(cy + r - 0.5);
        int minZ = (int) Math.ceil(cz - r - 0.5), maxZ = (int) Math.floor(cz + r - 0.5);

        NormalField field = new NormalField(view);
        List<SurfacePoint> points = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            double dy = y + 0.5 - cy;
            for (int z = minZ; z <= maxZ; z++) {
                double dz = z + 0.5 - cz;
                for (int x = minX; x <= maxX; x++) {
                    double dx = x + 0.5 - cx;
                    if (dx * dx + dy * dy + dz * dz > r2) {
                        continue;
                    }
                    if (Math.abs(dx * nx + dy * ny + dz * nz) > band) {
                        continue;
                    }
                    if (!field.isSurface(x, y, z)) {
                        continue;
                    }
                    Vec3 normal = field.smooth(x, y, z);
                    if (normal.isNearZero() || normal.dot(n) < minDot) {
                        continue;
                    }
                    points.add(new SurfacePoint(x, y, z, normal));
                }
            }
        }
        return points;
    }
}
