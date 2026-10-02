package tremor.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import tremor.Tremor;
import tremor.core.motion.MotionParams;
import tremor.core.shape.BumpParams;

import java.util.EnumMap;
import java.util.Locale;

/**
 * The entity's values of the {@link Param}s: overrides set by {@code /tremor set}, the config for everything else (so
 * config changes still reach parameters that were never overridden). Saved with the entity.
 */
public final class EntityParams {
    private final EnumMap<Param, Double> overrides = new EnumMap<>(Param.class);

    public double get(Param param) {
        Double value = overrides.get(param);
        return value != null ? value : param.configValue();
    }

    public boolean isOverridden(Param param) {
        return overrides.containsKey(param);
    }

    /**
     * Overrides a parameter.
     *
     * @throws IllegalArgumentException (with a message for players) if the value is outside the config range, or not
     *                                  whole for an integer parameter
     */
    public void set(Param param, double value) {
        double min = param.min(), max = param.max();
        if (!(value >= min && value <= max)) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, "%s must be between %s and %s",
                    param.id(), format(min), format(max)));
        }
        if (param.isInteger() && value != Math.rint(value)) {
            throw new IllegalArgumentException(param.id() + " must be a whole number");
        }
        overrides.put(param, value);
    }

    /** Back to the config values. */
    public void reset() {
        overrides.clear();
    }

    /** Shape of the bump (SPEC 6.1); the amplitude is the configured height, not the current animated one. */
    public BumpParams bumpParams() {
        return new BumpParams(get(Param.AMPLITUDE), get(Param.SIGMA_FRONT), get(Param.SIGMA_BACK),
                get(Param.SIGMA_SIDE), get(Param.TRAIL_LAG), get(Param.TRAIL_SIGMA), get(Param.TRAIL_DEPTH),
                get(Param.JITTER));
    }

    public MotionParams motionParams() {
        return new MotionParams(get(Param.SPEED), get(Param.ACCELERATION), get(Param.NORMAL_SMOOTHING),
                get(Param.AMPLITUDE), get(Param.AMPLITUDE_SMOOTHING));
    }

    public int maxDiveDepth() {
        return (int) Math.round(get(Param.MAX_DIVE_DEPTH));
    }

    public double diveCost() {
        return get(Param.DIVE_COST);
    }

    /** The overrides only. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        overrides.forEach((param, value) -> tag.putDouble(param.id(), value));
        return tag;
    }

    /** Overrides that no longer fit the config ranges are dropped. */
    public static EntityParams load(CompoundTag tag) {
        EntityParams params = new EntityParams();
        for (Param param : Param.values()) {
            if (tag.contains(param.id(), Tag.TAG_ANY_NUMERIC)) {
                try {
                    params.set(param, tag.getDouble(param.id()));
                } catch (IllegalArgumentException e) {
                    Tremor.LOGGER.warn("Ignoring saved tremor parameter: {}", e.getMessage());
                }
            }
        }
        return params;
    }

    /** Shortest exact rendering of a value: whole numbers without a fraction. */
    static String format(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15
                ? Long.toString((long) value) : String.valueOf(value);
    }
}
