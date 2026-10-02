package tremor.core.math;

/** Immutable 3D vector of doubles. The core's own type, so that it does not depend on any game math classes. */
public record Vec3(double x, double y, double z) {
    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public static final Vec3 UNIT_X = new Vec3(1, 0, 0);
    public static final Vec3 UNIT_Y = new Vec3(0, 1, 0);
    public static final Vec3 UNIT_Z = new Vec3(0, 0, 1);

    private static final double EPSILON = 1e-9;

    /** Centre of the voxel whose minimum corner is {@code (x, y, z)}. */
    public static Vec3 voxelCenter(int x, int y, int z) {
        return new Vec3(x + 0.5, y + 0.5, z + 0.5);
    }

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 add(double dx, double dy, double dz) {
        return new Vec3(x + dx, y + dy, z + dz);
    }

    public Vec3 sub(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 scale(double s) {
        return new Vec3(x * s, y * s, z * s);
    }

    public Vec3 negate() {
        return new Vec3(-x, -y, -z);
    }

    public double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vec3 cross(Vec3 o) {
        return new Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public double lengthSquared() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    public double distanceSquared(Vec3 o) {
        double dx = x - o.x, dy = y - o.y, dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distance(Vec3 o) {
        return Math.sqrt(distanceSquared(o));
    }

    public boolean isNearZero() {
        return lengthSquared() < EPSILON * EPSILON;
    }

    /** Unit vector in the same direction, or {@link #ZERO} if this vector is (almost) zero. */
    public Vec3 normalize() {
        double len = length();
        return len < EPSILON ? ZERO : new Vec3(x / len, y / len, z / len);
    }

    /** Component of this vector lying in the plane with the given unit normal: {@code v - (v·n)n}. */
    public Vec3 projectOnPlane(Vec3 unitNormal) {
        return sub(unitNormal.scale(dot(unitNormal)));
    }

    public Vec3 lerp(Vec3 o, double t) {
        return new Vec3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t);
    }

    /** Some unit vector perpendicular to this (non-zero) vector; deterministic for a given input. */
    public Vec3 anyPerpendicular() {
        // Cross with the world axis that is least aligned with this vector.
        double ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
        Vec3 axis = ax <= ay && ax <= az ? UNIT_X : (ay <= az ? UNIT_Y : UNIT_Z);
        return cross(axis).normalize();
    }
}
