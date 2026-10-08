package tremor.core.math;

/** {@code Math.clamp} for Java 17, which Minecraft 1.20.1 runs on. Same overloads, so call sites resolve alike. */
public final class Clamp {
    private Clamp() {
    }

    public static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }

    public static float clamp(float value, float min, float max) {
        return Math.min(max, Math.max(min, value));
    }

    public static int clamp(long value, int min, int max) {
        return (int) Math.min(max, Math.max(min, value));
    }

    public static long clamp(long value, long min, long max) {
        return Math.min(max, Math.max(min, value));
    }
}
