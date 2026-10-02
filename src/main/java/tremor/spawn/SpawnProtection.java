package tremor.spawn;

import java.util.Arrays;
import java.util.function.Predicate;

import tremor.core.math.Vec3;

/**
 * Spawn protection (SPEC 11: "Не спавнится в зоне вокруг точки спавна мира/кровати (опционально)"), kept free of the
 * game so it is unit-tested directly: no natural spawn point closer than {@code radius} blocks, measured
 * horizontally, to a protected centre. The zones are vertical cylinders, so caves under a base are protected as well.
 * A radius of 0 protects nothing. As a predicate it accepts the allowed points (part of the {@code allowed} argument
 * of {@link tremor.core.behavior.SurfacePicker#spawnPoint}).
 */
final class SpawnProtection implements Predicate<Vec3> {
    private final double radius;
    /** Centres as x, z pairs. */
    private double[] centres = new double[8];
    private int count;

    /** @throws IllegalArgumentException unless {@code radius} is finite and {@code >= 0} */
    SpawnProtection(double radius) {
        if (!(radius >= 0) || Double.isInfinite(radius)) {
            throw new IllegalArgumentException("radius " + radius);
        }
        this.radius = radius;
    }

    double radius() {
        return radius;
    }

    /** Protects the zone around the horizontal position {@code (x, z)}. */
    void add(double x, double z) {
        if (2 * count == centres.length) {
            centres = Arrays.copyOf(centres, 2 * centres.length);
        }
        centres[2 * count] = x;
        centres[2 * count + 1] = z;
        count++;
    }

    /** Number of protected centres added. */
    int size() {
        return count;
    }

    /** Whether a spawn at {@code p} is allowed: its horizontal distance to every centre is at least the radius. */
    @Override
    public boolean test(Vec3 p) {
        double r2 = radius * radius;
        for (int i = 0; i < count; i++) {
            double dx = p.x() - centres[2 * i], dz = p.z() - centres[2 * i + 1];
            if (dx * dx + dz * dz < r2) {
                return false;
            }
        }
        return true;
    }
}
