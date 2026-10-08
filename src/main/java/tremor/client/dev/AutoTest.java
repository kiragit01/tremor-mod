package tremor.client.dev;

import java.nio.file.Path;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.loading.FMLPaths;
import tremor.Tremor;

/**
 * Dev-only scripted in-game test harness, for a developer who cannot play the game: boots into a fixed-seed world,
 * runs a script of commands, benchmarks and screenshots, writes the results to files and quits.
 * <p>
 * Enabled only by {@code -Dtremor.autotest=<absolute path of the script>} (Gradle run {@code clientAutotest}).
 * Optional properties: {@code tremor.autotest.world} (default {@code tremor-autotest}), {@code tremor.autotest.seed}
 * (default {@code 1337}), {@code tremor.autotest.timeout} in seconds (default {@code 900}),
 * {@code tremor.autotest.fresh} (delete the world first, default {@code false}).
 * <p>
 * Results: {@code <gameDir>/tremor-autotest/<yyyyMMdd-HHmmss>/report.txt}, {@code bench.csv} and
 * {@code screenshots/<name>.png}; {@code <gameDir>/tremor-autotest/latest.txt} holds that directory's absolute path.
 * Every run that gets as far as creating the directory ends {@code report.txt} with a {@code FINISH:} line, a summary
 * and {@code END}, also when the script cannot be read, mod loading fails or the game crashes; a missing
 * {@code latest.txt} means the directory itself could not be created (see the game log).
 * Script format: see {@link Script}.
 */
public final class AutoTest {
    public static final String PROPERTY = "tremor.autotest";
    static final String REPORT_ROOT = "tremor-autotest";
    static final String DEFAULT_WORLD = "tremor-autotest";
    static final long DEFAULT_SEED = 1337;
    static final long DEFAULT_TIMEOUT = 900;

    private static boolean started;
    private static AutoTestRunner runner;

    private AutoTest() {
    }

    /** {@code true} when the game was started with {@code -Dtremor.autotest=<script>}. */
    public static boolean isEnabled() {
        String script = System.getProperty(PROPERTY);
        return script != null && !script.isBlank();
    }

    /**
     * Creates the report directory, reads the script and hooks the harness into the game ({@code modBus} for client
     * setup, the NeoForge bus for everything else). Does nothing when the harness is disabled. When it cannot start
     * (unreadable script, invalid path), the error is recorded in the report and the game is stopped once loading has
     * finished, so an unattended run never idles on the title screen.
     */
    public static synchronized void init(IEventBus modBus) {
        if (!isEnabled() || started) {
            return;
        }
        started = true;
        // The report comes first: everything after this point that fails ends up in it.
        Path root = FMLPaths.GAMEDIR.get().resolve(REPORT_ROOT);
        Report report;
        try {
            report = Report.create(root);
        } catch (Exception e) {
            Tremor.LOGGER.error("[autotest] cannot create the report directory in {}, stopping the game", root, e);
            AutoTestRunner.stopOnceLoaded();
            return;
        }
        Config config;
        AutoTestRunner created;
        try {
            config = Config.fromSystemProperties();
            created = AutoTestRunner.create(config, report);
        } catch (Throwable t) {
            Tremor.LOGGER.error("[autotest] cannot start with -D{}={}, stopping the game; report {}", PROPERTY,
                    System.getProperty(PROPERTY), report.dir(), t);
            AutoTestRunner.failStartup(report, t);
            return;
        }
        created.register(modBus);
        runner = created;
        Tremor.LOGGER.info("[autotest] enabled, script {}, report {}", config.script(), created.reportDir());
    }

    record Config(Path script, String world, long seed, long timeoutSeconds, boolean fresh) {
        static Config fromSystemProperties() {
            Path script = Path.of(System.getProperty(PROPERTY).strip()).toAbsolutePath().normalize();
            String world = System.getProperty(PROPERTY + ".world", DEFAULT_WORLD).strip();
            if (world.isEmpty()) {
                world = DEFAULT_WORLD;
            }
            long seed = longProperty(PROPERTY + ".seed", DEFAULT_SEED);
            long timeout = Math.max(1, longProperty(PROPERTY + ".timeout", DEFAULT_TIMEOUT));
            boolean fresh = Boolean.parseBoolean(System.getProperty(PROPERTY + ".fresh", "false").strip());
            return new Config(script, world, seed, timeout, fresh);
        }

        private static long longProperty(String name, long fallback) {
            String value = System.getProperty(name);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return Long.parseLong(value.strip());
            } catch (NumberFormatException e) {
                Tremor.LOGGER.error("[autotest] ignoring invalid -D{}={}, using {}", name, value, fallback);
                return fallback;
            }
        }
    }
}
