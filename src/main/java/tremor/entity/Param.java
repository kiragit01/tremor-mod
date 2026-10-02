package tremor.entity;

import net.neoforged.neoforge.common.ModConfigSpec;
import tremor.config.TremorConfig;

import java.util.Arrays;
import java.util.List;

/**
 * A per-entity tunable of {@code /tremor set} (SPEC 14.1). The default and the allowed range of each one are those of
 * the COMMON config entry with the same name ({@link TremorConfig.Common}).
 */
public enum Param {
    AMPLITUDE("amplitude", Kind.SHAPE),
    SIGMA_FRONT("sigmaFront", Kind.SHAPE),
    SIGMA_BACK("sigmaBack", Kind.SHAPE),
    SIGMA_SIDE("sigmaSide", Kind.SHAPE),
    TRAIL_LAG("trailLag", Kind.SHAPE),
    TRAIL_SIGMA("trailSigma", Kind.SHAPE),
    TRAIL_DEPTH("trailDepth", Kind.SHAPE),
    JITTER("jitter", Kind.SHAPE),
    SPEED("speed", Kind.MOTION),
    ACCELERATION("acceleration", Kind.MOTION),
    NORMAL_SMOOTHING("normalSmoothing", Kind.MOTION),
    AMPLITUDE_SMOOTHING("amplitudeSmoothing", Kind.MOTION),
    MAX_DIVE_DEPTH("maxDiveDepth", Kind.GRAPH),
    DIVE_COST("diveCost", Kind.SEARCH);

    /** What a change of the parameter affects. */
    public enum Kind {
        /** The bump shape: clients get a new shape packet. */
        SHAPE,
        /** How the crawler moves; read every tick. */
        MOTION,
        /** The surface graph (dive edges): it is rebuilt and the route replanned. */
        GRAPH,
        /** Path costs: the route is replanned. */
        SEARCH
    }

    private static final List<String> IDS = Arrays.stream(values()).map(Param::id).toList();

    private final String id;
    private final Kind kind;

    Param(String id, Kind kind) {
        this.id = id;
        this.kind = kind;
    }

    /** Name in commands, NBT and the config. */
    public String id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    /** Integer parameters accept only whole values. */
    public boolean isInteger() {
        return this == MAX_DIVE_DEPTH;
    }

    /** The configured value, used when the entity has no override. */
    public double configValue() {
        return config().get().doubleValue();
    }

    public double min() {
        return bound(false);
    }

    public double max() {
        return bound(true);
    }

    /** The parameter with this id (case-insensitive), or null. */
    public static Param byId(String id) {
        for (Param param : values()) {
            if (param.id.equalsIgnoreCase(id)) {
                return param;
            }
        }
        return null;
    }

    public static List<String> ids() {
        return IDS;
    }

    private double bound(boolean upper) {
        ModConfigSpec.ValueSpec spec = config().getSpec();
        if (isInteger()) {
            ModConfigSpec.Range<Integer> range = spec.<Integer>getRange();
            return upper ? range.getMax() : range.getMin();
        }
        ModConfigSpec.Range<Double> range = spec.<Double>getRange();
        return upper ? range.getMax() : range.getMin();
    }

    private ModConfigSpec.ConfigValue<? extends Number> config() {
        TremorConfig.Common c = TremorConfig.COMMON;
        return switch (this) {
            case AMPLITUDE -> c.amplitude;
            case SIGMA_FRONT -> c.sigmaFront;
            case SIGMA_BACK -> c.sigmaBack;
            case SIGMA_SIDE -> c.sigmaSide;
            case TRAIL_LAG -> c.trailLag;
            case TRAIL_SIGMA -> c.trailSigma;
            case TRAIL_DEPTH -> c.trailDepth;
            case JITTER -> c.jitter;
            case SPEED -> c.speed;
            case ACCELERATION -> c.acceleration;
            case NORMAL_SMOOTHING -> c.normalSmoothing;
            case AMPLITUDE_SMOOTHING -> c.amplitudeSmoothing;
            case MAX_DIVE_DEPTH -> c.maxDiveDepth;
            case DIVE_COST -> c.diveCost;
        };
    }
}
