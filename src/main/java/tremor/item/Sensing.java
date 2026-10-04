package tremor.item;

import tremor.core.behavior.Stage;

/**
 * The pure rules of the tools that sense the entity (SPEC 15, stage 5): how strong the redstone signal of a
 * {@link GeophoneBlock geophone} is, what its comparator reads, and how much the needle of a seismograph trembles.
 * Plain Java, unit tested.
 */
public final class Sensing {
    private Sensing() {
    }

    /**
     * Redstone power of a geophone with the entity {@code distance} blocks away: 15 when it is right there, falling
     * evenly to 1 at {@code range}, 0 beyond (or with no entity: NaN).
     */
    public static int power(double distance, double range) {
        if (!(distance < range) || !(range > 0)) {
            return 0;
        }
        return Math.max(1, Math.min(15, (int) Math.ceil(15 * (1 - Math.max(0, distance) / range))));
    }

    /** What a comparator reads from a geophone that hears the entity in {@code stage}: 4, 8, 12 or 15; 0 with none. */
    public static int stageSignal(Stage stage) {
        if (stage == null) {
            return 0;
        }
        return switch (stage) {
            case DORMANT -> 4;
            case ALERT -> 8;
            case HUNTING -> 12;
            case AWAKENING -> 15;
        };
    }

    /** How far (a share of a turn) the needle of a seismograph trembles either way with the entity in {@code stage}. */
    public static double tremble(Stage stage) {
        if (stage == null) {
            return 0;
        }
        return switch (stage) {
            case DORMANT -> 0.005;
            case ALERT -> 0.02;
            case HUNTING -> 0.05;
            case AWAKENING -> 0.09;
        };
    }
}
