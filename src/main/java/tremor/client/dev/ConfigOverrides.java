package tremor.client.dev;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraftforge.common.ForgeConfigSpec;
import tremor.config.TremorConfig;

/**
 * The values changed by the {@code config} steps of a run (see {@link Script}), looked up by their dotted path in
 * {@link TremorConfig#COMMON_SPEC} or {@link TremorConfig#CLIENT_SPEC} through {@link ForgeConfigSpec#getValues()}, so
 * entries added to the config later need no change here.
 * <p>
 * A change takes the way of an edit in NeoForge's config screen: {@link ForgeConfigSpec.ConfigValue#set} puts the value
 * into the loaded config and into the value's cache, then {@link ForgeConfigSpec#save()} writes the config file and
 * fires {@code ModConfigEvent.Reloading}. The client and the integrated server read the same spec objects (one JVM,
 * and common configs are not synced), so both see the new value on their next {@code get()}. {@code set} leaves the
 * cache of a value marked {@code worldRestart} or {@code gameRestart} alone; that cache is cleared too, so the running
 * game sees such a value at once, unlike after an edit in the config screen.
 * <p>
 * The first original value of every changed path is kept and {@link #restore put back} when the run ends, so that a
 * test run never leaves the config files of the autotest game directory changed. Changes happen on the client
 * thread; the watchdog and the JVM shutdown hook restore through {@link #restoreBounded}.
 */
final class ConfigOverrides {
    /** How long the watchdog or the JVM shutdown hook waits for a restore before giving up on it. */
    private static final long RESTORE_TIMEOUT_MS = 5_000;

    /** Original value per changed path (canonical form), in the order of the first change. Guarded by {@code this}. */
    private final Map<String, Original> originals = new LinkedHashMap<>();
    /** {@code true} while {@link #originals} is not empty; read without the lock. */
    private volatile boolean changed;

    private record Original(Script.ConfigPath path, ForgeConfigSpec spec, ForgeConfigSpec.ConfigValue<Object> value,
                            Object original) {
    }

    /**
     * Client thread: sets the value at {@code path} to {@code raw}, read by the type of the current value (see
     * {@link Script#configValue}), and returns the report line {@code config <path>: <old> -> <new>}.
     *
     * @throws IllegalArgumentException for an unknown path, or a value that cannot be read or that the spec rejects;
     *                                  nothing has changed then
     */
    synchronized String apply(Script.ConfigPath path, String raw) {
        ForgeConfigSpec spec = path.client() ? TremorConfig.CLIENT_SPEC : TremorConfig.COMMON_SPEC;
        ForgeConfigSpec.ConfigValue<Object> value = lookup(spec, path);
        if (!spec.isLoaded()) {
            throw new IllegalArgumentException("the " + file(path) + " config is not loaded");
        }
        Object old = value.get();
        Object parsed = Script.configValue(old, raw);
        ForgeConfigSpec.ValueSpec valueSpec = spec.getSpec().get(value.getPath());
        validate(path, valueSpec, parsed);
        originals.putIfAbsent(path.toString(), new Original(path, spec, value, old));
        changed = true;
        set(value, parsed);
        spec.save();
        return "config " + path + ": " + format(old) + " -> " + format(parsed)
                + (valueSpec.needsWorldRestart() ? " (applied at once although the spec asks for a world restart)" : "");
    }

    /**
     * Puts every changed value back and saves each config file that had a change, with a report line per value.
     * Client thread, or through {@link #restoreBounded}. Failures are reported, never thrown.
     */
    synchronized void restore(Report report) {
        Map<ForgeConfigSpec, String> touched = new LinkedHashMap<>();
        for (Original o : originals.values()) {
            try {
                Object current = o.value().get();
                set(o.value(), o.original());
                touched.put(o.spec(), file(o.path()));
                report.line("config " + o.path() + ": " + format(current) + " -> " + format(o.original())
                        + " (restored)");
            } catch (Throwable t) {
                report.error("cannot restore config " + o.path() + " to " + format(o.original()), t);
            }
        }
        originals.clear();
        changed = false;
        touched.forEach((spec, file) -> {
            try {
                spec.save();
            } catch (Throwable t) {
                report.error("cannot save the restored " + file + " config", t);
            }
        });
    }

    /**
     * {@link #restore} for a thread that must never hang (the watchdog before it halts the JVM, the JVM shutdown
     * hook): a stuck client thread may hold the lock or the config, so the restore runs on a helper thread that is
     * given up on after {@link #RESTORE_TIMEOUT_MS}.
     */
    void restoreBounded(Report report) {
        if (!changed) {
            return;
        }
        Thread worker = new Thread(() -> restore(report), "Tremor autotest config restore");
        worker.setDaemon(true);
        worker.start();
        try {
            worker.join(RESTORE_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            report.error("config values not restored within " + RESTORE_TIMEOUT_MS / 1000 + " s, the config files may "
                    + "keep the values of the config steps");
        }
    }

    /** The value at {@code path} in {@code spec}; an error names the keys of the section that lacks one. */
    @SuppressWarnings("unchecked")
    private static ForgeConfigSpec.ConfigValue<Object> lookup(ForgeConfigSpec spec, Script.ConfigPath path) {
        Object entry = spec.getValues();
        List<String> keys = path.keys();
        for (int i = 0; i < keys.size(); i++) {
            if (!(entry instanceof UnmodifiableConfig section)) {
                throw new IllegalArgumentException("unknown config path '" + path + "': "
                        + String.join(".", keys.subList(0, i)) + " is a value, not a section");
            }
            entry = section.get(List.of(keys.get(i)));
            if (entry == null) {
                throw new IllegalArgumentException("unknown config path '" + path + "': "
                        + (i == 0 ? "the " + file(path) + " config" : "section " + String.join(".", keys.subList(0, i)))
                        + " has no '" + keys.get(i) + "', only " + keysOf(section));
            }
        }
        if (entry instanceof ForgeConfigSpec.ConfigValue<?> value) {
            return (ForgeConfigSpec.ConfigValue<Object>) value;
        }
        throw new IllegalArgumentException("config path '" + path + "' is a section, not a value; it has "
                + keysOf((UnmodifiableConfig) entry));
    }

    /** Throws unless the spec accepts {@code value}; the message names the range or the allowed enum constants. */
    private static void validate(Script.ConfigPath path, ForgeConfigSpec.ValueSpec spec, Object value) {
        if (spec.test(value)) {
            return;
        }
        ForgeConfigSpec.Range<?> range = spec.getRange();
        String reason;
        if (range != null) {
            reason = "out of range " + range;
        } else if (value instanceof Enum<?> constant) {
            reason = "allowed are " + Arrays.stream(constant.getDeclaringClass().getEnumConstants())
                    .filter(spec::test).map(Enum::name).collect(Collectors.joining(", "));
        } else {
            reason = "rejected by the config spec";
        }
        throw new IllegalArgumentException("invalid value " + format(value) + " for " + path + ": " + reason);
    }

    /** Sets the value; its cache is cleared, so that its next {@code get()} reads the new value from the config. */
    private static void set(ForgeConfigSpec.ConfigValue<Object> value, Object newValue) {
        value.set(newValue);
        value.clearCache();
    }

    private static String keysOf(UnmodifiableConfig section) {
        return section.entrySet().stream().map(UnmodifiableConfig.Entry::getKey).collect(Collectors.joining(", "));
    }

    private static String file(Script.ConfigPath path) {
        return path.client() ? "client" : "common";
    }

    private static String format(Object value) {
        return value instanceof Enum<?> constant ? constant.name() : String.valueOf(value);
    }
}
