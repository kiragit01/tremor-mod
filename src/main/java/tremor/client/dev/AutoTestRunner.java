package tremor.client.dev;

import com.mojang.blaze3d.platform.GlUtil;
import com.mojang.blaze3d.platform.Window;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import net.minecraft.SharedConstants;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.client.NarratorStatus;
import net.minecraft.client.Options;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.DatapackLoadFailureScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.GenericDirtMessageScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.ModLoadingWarning;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.common.ForgeI18n;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ScreenshotEvent;
import net.minecraftforge.client.gui.LoadingErrorScreen;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.versions.forge.ForgeVersion;
import tremor.Tremor;
import tremor.client.render.RenderStats;

/**
 * The harness state machine: title screen, open or create the world, wait for terrain, run the script one step per
 * client tick, then save, leave the world and stop the client.
 * <p>
 * Every game interaction happens on the client thread. Anything that can open screens or block (loading a world,
 * pressing confirmation buttons, leaving the world) is queued with {@link Minecraft#tell} instead of running inside a
 * tick handler, the same way vanilla button presses are processed. Handlers never throw: failures go to the report.
 * <p>
 * Game rules of a newly created world ({@code doDaylightCycle}, {@code doWeatherCycle}, {@code doMobSpawning} off)
 * are set through {@link LevelSettings}; an existing world keeps whatever rules it has.
 * <p>
 * Movement keys of a {@code hold} step are pressed again before every client tick and released when it ends, on
 * {@code release}, when a step fails and when the run finishes. A hold without {@code sprint} does not sprint:
 * vanilla's double tap of forward is disarmed before every tick, and a sprint found anyway is stopped and reported.
 * When a hold ends, the report gets what the player did on its ticks and a warning for anything that kept it from
 * walking as asked (spectator, flying, off the ground, an unasked sprint), see {@link HoldTally}.
 * <p>
 * Config values changed by {@code config} steps are put back wherever the run ends (finish, the game shutting down,
 * the watchdog halting the JVM, the JVM shutdown hook), see {@link ConfigOverrides}. A {@code waitfor} step is
 * matched against each chat line in {@link #onChat} as it is written to the report.
 * <p>
 * Two more threads can end the run: the watchdog (timeouts, and a fatal mod loading error, after which NeoForge's game
 * bus never starts and none of the event handlers runs) and a JVM shutdown hook (a crash exits the JVM without
 * {@link Minecraft#stop()}). Moving to {@link State#FINISHED} is therefore atomic, see {@link #claimFinish()}.
 * <p>
 * Exit codes when the watchdog has to halt the JVM: {@value #EXIT_TIMEOUT} global timeout,
 * {@value #EXIT_STUCK_AFTER_FINISH} no exit after finishing, {@value #EXIT_MOD_LOADING_FAILED} no exit after a fatal
 * mod loading error, {@value #EXIT_STARTUP_FAILED} no exit after the harness failed to start.
 */
final class AutoTestRunner {
    private enum State { BOOT, LOADING, SETTLING, RUNNING, FINISHED }

    static final int EXIT_TIMEOUT = 3;
    static final int EXIT_STUCK_AFTER_FINISH = 4;
    static final int EXIT_MOD_LOADING_FAILED = 5;
    static final int EXIT_STARTUP_FAILED = 6;

    /** Initial terrain wait before the script starts; after it, {@link #SETTLE_EXTRA_TICKS} more ticks. */
    private static final int SETTLE_TIMEOUT_TICKS = 1200;
    private static final int SETTLE_EXTRA_TICKS = 40;
    /** Terrain counts as ready when chunk and section counts have not changed for this long with an idle mesher. */
    private static final int TERRAIN_STABLE_TICKS = 20;
    private static final int SCREENSHOT_TIMEOUT_TICKS = 100;
    /** A bench that sees no frames (window minimised) is cut off this many ticks after its tick budget. */
    private static final int BENCH_STALL_TICKS = 200;
    /** A screen still open this long after the player has joined is closed. */
    private static final int STUCK_SCREEN_TICKS = 900;
    private static final int MAX_AUTO_CONFIRMS = 8;
    private static final long WATCHDOG_GRACE_MS = 60_000;
    private static final long SHUTDOWN_GRACE_MS = 120_000;
    /** After a fatal mod loading error the stop is only queued on the client task queue; halt if it did not work. */
    private static final long MOD_LOADING_STOP_GRACE_MS = 30_000;
    /** After a failed start: halt if the game is still running this long after the harness was initialised. */
    private static final long STARTUP_FAILURE_HALT_MS = 300_000;
    /** Consecutive watchdog polls (one per second) that must see the fatal mod loading error state. */
    private static final int LOADING_ERROR_POLLS = 2;
    private static final String SCREENSHOT_FAILURE_KEY = "screenshot.failure";
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int RENDER_DISTANCE = 12;
    /** Mojang name of {@code LocalPlayer}'s protected double-tap sprint window, see {@link #suppressDoubleTapSprint}. */
    private static final String SPRINT_TRIGGER_FIELD = "sprintTriggerTime";
    /** Button labels that mean "go ahead" on screens that can interrupt world loading, in order of preference. */
    private static final List<String> CONFIRM_KEYS = List.of(
            "selectWorld.backupJoinSkipButton", "gui.yes", "gui.proceed", "gui.continue");

    private final AutoTest.Config config;
    private final Script script;
    private final Report report;
    private final long deadlineNanos;
    private final Minecraft mc = Minecraft.getInstance();
    private final ConfigOverrides configOverrides = new ConfigOverrides();

    private final Object stateLock = new Object();
    private volatile State state = State.BOOT;
    private volatile long tick;
    private volatile long finishRequestedAtMillis;
    private volatile long stopGraceMs = SHUTDOWN_GRACE_MS;
    private volatile int stopExitCode = EXIT_STUCK_AFTER_FINISH;
    private volatile Thread clientThread;
    private boolean clientPrepared;

    private boolean launchRan;
    private volatile boolean launchFailed;
    private Screen lastConfirmed;
    private Screen lastErrorScreen;
    private int confirms;
    private int inWorldScreenTicks;
    /** A respawn was requested and the death screen closed; reset once the player is alive again. */
    private boolean respawnRequested;

    private int settleTicks;
    private boolean settled;
    private int settleExtra;
    private int stableTicks;
    private int lastChunks = -1;
    private int lastSections = -1;

    private int stepIndex;
    private volatile int executed;
    private Script.Step current;
    private int stepTicks;
    private int waitLeft;
    private volatile FrameBench bench;
    private volatile int benchCount;
    private String pendingShot;
    private int shotSerial;
    private volatile int shotSaved;
    /** The running hold step, client thread only. */
    private Hold hold;
    /** The running waitfor step, client thread only. */
    private WaitFor waitFor;
    /** {@code LocalPlayer.sprintTriggerTime}, or {@code null} if it cannot be reached; client thread only. */
    private Field sprintTriggerTime;
    private boolean sprintTriggerResolved;

    private AutoTestRunner(AutoTest.Config config, Script script, Report report) {
        this.config = config;
        this.script = script;
        this.report = report;
        this.deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.timeoutSeconds());
        report.tickSource(() -> tick);
    }

    /** Reads the script and writes the report header; throws if the script cannot be read. */
    static AutoTestRunner create(AutoTest.Config config, Report report) throws IOException {
        AutoTestRunner runner = new AutoTestRunner(config, Script.load(config.script()), report);
        runner.writeHeader();
        return runner;
    }

    Path reportDir() {
        return report.dir();
    }

    void register(IEventBus modBus) {
        // Watchdog and shutdown hook first: if anything below fails, mod loading fails and the watchdog reports it.
        startWatchdog();
        Runtime.getRuntime().addShutdownHook(new Thread(this::onJvmShutdown, "Tremor autotest shutdown hook"));
        IEventBus bus = MinecraftForge.EVENT_BUS;
        modBus.addListener(EventPriority.NORMAL, false, FMLClientSetupEvent.class, e -> e.enqueueWork(this::prepareClient));
        bus.addListener(EventPriority.NORMAL, false, TickEvent.ClientTickEvent.class, this::onClientTickPre);
        bus.addListener(EventPriority.NORMAL, false, TickEvent.ClientTickEvent.class, this::onClientTick);
        bus.addListener(EventPriority.NORMAL, false, TickEvent.RenderTickEvent.class, this::onFrameStart);
        bus.addListener(EventPriority.NORMAL, false, TickEvent.RenderTickEvent.class, this::onFrameEnd);
        bus.addListener(EventPriority.NORMAL, false, ScreenEvent.Opening.class, this::onScreenOpening);
        bus.addListener(EventPriority.LOWEST, true, ClientChatReceivedEvent.class, this::onChat);
        bus.addListener(EventPriority.NORMAL, false, GameShuttingDownEvent.class, this::onGameShuttingDown);
    }

    // ---- startup failure ---------------------------------------------------------------------------------------

    /**
     * The harness cannot start (unreadable script, invalid path): records the error, completes the report with
     * {@code FINISH}, the summary and {@code END}, and stops the game once loading has finished.
     */
    static void failStartup(Report report, Throwable error) {
        report.raw("Tremor autotest report");
        report.raw("started   " + LocalDateTime.now().format(TIMESTAMP));
        report.raw("script    " + System.getProperty(AutoTest.PROPERTY));
        report.raw("report    " + report.dir());
        report.error("cannot start the harness", error);
        writeSummaryBlock(report, "aborted: startup failed", State.BOOT, 0, 0, 0);
        report.line("stopping the client once loading has finished");
        report.raw("END");
        report.close();
        stopOnceLoaded();
    }

    /**
     * Stops the game as soon as loading has finished (a screen is up and the loading overlay is gone), for a run the
     * harness could not start. A fatal mod loading error keeps NeoForge's game bus from starting, so the tick handler
     * never runs then; a backstop thread queues the stop on the client task queue instead, and halts the JVM with
     * {@value #EXIT_STARTUP_FAILED} if the game is still alive {@link #STARTUP_FAILURE_HALT_MS} after this call.
     */
    static void stopOnceLoaded() {
        Minecraft mc = Minecraft.getInstance();
        AtomicBoolean ticked = new AtomicBoolean();
        AtomicBoolean stopping = new AtomicBoolean();
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, TickEvent.ClientTickEvent.class, e -> {
            ticked.set(true);
            if (mc.getOverlay() == null && mc.screen != null && stopping.compareAndSet(false, true)) {
                Tremor.LOGGER.error("[autotest] the harness could not start, stopping the game");
                mc.stop();
            }
        });
        Thread backstop = new Thread(() -> {
            long deadline = System.currentTimeMillis() + STARTUP_FAILURE_HALT_MS;
            int loadingErrorPolls = 0;
            try {
                while (System.currentTimeMillis() < deadline) {
                    TimeUnit.SECONDS.sleep(1);
                    loadingErrorPolls = !ticked.get() && fatalLoadingError(mc) ? loadingErrorPolls + 1 : 0;
                    if (loadingErrorPolls >= LOADING_ERROR_POLLS && stopping.compareAndSet(false, true)) {
                        Tremor.LOGGER.error("[autotest] mod loading failed and the harness could not start, stopping the game");
                        mc.execute(mc::stop);
                    }
                }
            } catch (InterruptedException e) {
                return;
            }
            Tremor.LOGGER.error("[autotest] the game is still running {} s after the harness failed to start, halting the JVM",
                    STARTUP_FAILURE_HALT_MS / 1000);
            Runtime.getRuntime().halt(EXIT_STARTUP_FAILED);
        }, "Tremor autotest startup-failure watchdog");
        backstop.setDaemon(true);
        backstop.start();
    }

    // ---- setup -------------------------------------------------------------------------------------------------

    private void writeHeader() {
        report.raw("Tremor autotest report");
        report.raw("started   " + LocalDateTime.now().format(TIMESTAMP));
        report.raw("script    " + script.path() + " (" + script.steps().size() + " steps, "
                + script.errorCount() + " invalid)");
        report.raw("world     '" + config.world() + "', seed " + config.seed() + ", fresh " + config.fresh());
        report.raw("timeout   " + config.timeoutSeconds() + " s");
        report.raw("report    " + report.dir());
        report.raw("shots     " + report.dir().resolve("screenshots"));
        for (Script.Step step : script.steps()) {
            if (step.kind() == Script.Kind.INVALID) {
                report.raw("invalid   line " + step.line() + ": " + step.text() + "  [" + step.source() + "]");
            }
        }
    }

    /** Client thread, once: unattended options and the environment block. */
    private void prepareClient() {
        if (clientPrepared) {
            return;
        }
        clientPrepared = true;
        clientThread = Thread.currentThread();
        try {
            applyOptions();
        } catch (Throwable t) {
            report.error("cannot apply options", t);
        }
        try {
            writeEnvironment();
        } catch (Throwable t) {
            report.error("cannot collect environment info", t);
        }
    }

    private void applyOptions() {
        Options options = mc.options;
        options.pauseOnLostFocus = false;
        options.onboardAccessibility = false;
        options.joinedFirstServer = true;
        options.tutorialStep = TutorialSteps.NONE;
        options.enableVsync().set(false);
        options.framerateLimit().set(Options.UNLIMITED_FRAMERATE_CUTOFF);
        options.renderDistance().set(RENDER_DISTANCE);
        options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
        options.narrator().set(NarratorStatus.OFF);
        // hold presses keys with KeyMapping.setDown, which flips a key in toggle mode instead of pressing it.
        options.toggleCrouch().set(false);
        options.toggleSprint().set(false);
        options.autoJump().set(false);
        options.save();
    }

    private void writeEnvironment() {
        Window window = mc.getWindow();
        Options options = mc.options;
        Runtime runtime = Runtime.getRuntime();
        report.raw("environment");
        report.raw("  minecraft   " + SharedConstants.getCurrentVersion().getName() + ", forge "
                + ForgeVersion.getVersion() + ", " + ModList.get().size() + " mods loaded");
        report.raw("  java        " + System.getProperty("java.version") + " (" + System.getProperty("java.vm.name")
                + ", " + System.getProperty("java.vendor") + ")");
        report.raw("  os          " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " "
                + System.getProperty("os.arch"));
        report.raw("  cpu         " + GlUtil.getCpuInfo() + ", " + runtime.availableProcessors() + " threads");
        report.raw("  heap        max " + runtime.maxMemory() / (1024 * 1024) + " MB");
        report.raw("  gl          " + GlUtil.getRenderer() + " | " + GlUtil.getVendor() + " | " + GlUtil.getOpenGLVersion());
        report.raw("  window      " + window.getWidth() + "x" + window.getHeight() + (window.isFullscreen() ? " fullscreen" : ""));
        report.raw("  options     render distance " + options.renderDistance().get() + ", simulation distance "
                + options.simulationDistance().get() + ", graphics " + options.graphicsMode().get()
                + ", framerate limit " + options.framerateLimit().get() + " (260 = unlimited), vsync "
                + options.enableVsync().get() + ", pauseOnLostFocus " + options.pauseOnLostFocus);
        report.raw("");
    }

    // ---- events ------------------------------------------------------------------------------------------------

    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        tick++;
        if (state == State.FINISHED) {
            return;
        }
        if (respawnRequested && mc.player != null && mc.player.isAlive()) {
            respawnRequested = false;
        }
        try {
            if (!clientPrepared) {
                prepareClient();
            }
            if (System.nanoTime() > deadlineNanos) {
                report.error("global timeout of " + config.timeoutSeconds() + " s reached in state " + state
                        + (mc.screen != null ? ", screen " + describe(mc.screen) : ""));
                finish("timeout");
                return;
            }
            respawnIfDead();
            switch (state) {
                case BOOT -> tickBoot();
                case LOADING -> tickLoading();
                case SETTLING -> tickSettling();
                case RUNNING -> tickRunning();
                default -> {
                }
            }
        } catch (Throwable t) {
            report.error("unexpected exception in state " + state, t);
            finish("aborted: exception");
        }
    }

    /**
     * A dead player (killed during the run, or saved dead by an earlier run) would leave the death screen open and stop
     * the script: respawn at once and report it as an error. The death screen enables its buttons after a delay, the
     * respawn request itself has none ({@link net.minecraft.client.player.LocalPlayer#respawn}).
     */
    private void respawnIfDead() {
        if (mc.player == null || !(mc.screen instanceof DeathScreen) || respawnRequested) {
            return;
        }
        respawnRequested = true;
        report.error("player died (" + describe(mc.screen) + ") at " + position(mc.player) + ", respawning");
        mc.player.respawn();
        mc.setScreen(null);
    }

    /**
     * Presses the held keys again before the player reads its input this tick: opening a screen
     * ({@code KeyMapping.releaseAll}), grabbing the mouse in a focused window ({@code KeyMapping.setAll} polls the
     * physical keys) and real key events overwrite them. In a hold without {@code sprint} it also keeps vanilla's
     * double-tap of forward from sprinting (see {@link #suppressDoubleTapSprint}) and, as a backstop, stops any sprint
     * it finds. Releases the keys if another thread has ended the run.
     */
    private void onClientTickPre(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        Hold h = hold;
        if (h == null) {
            return;
        }
        try {
            if (state != State.RUNNING) {
                endHold("run finished");
                return;
            }
            pressKeys(h.keys);
            LocalPlayer player = mc.player;
            if (player != null && h.keys.contains(Script.HoldKey.ATTACK)) {
                mine(player);
            }
            if (player != null && h.keys.contains(Script.HoldKey.DROP) && !h.dropped) {
                // Held keys make no clicks: drop one of the held item once, as one press of the drop key does.
                h.dropped = true;
                if (player.drop(false)) {
                    player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                }
            }
            if (player != null && !h.keys.contains(Script.HoldKey.SPRINT)) {
                suppressDoubleTapSprint(player);
                if (player.isSprinting()) {
                    player.setSprinting(false);
                    h.tally.sprintStopped();
                }
            }
        } catch (Throwable t) {
            report.error("hold " + h.label + ": cannot press the keys", t);
            endHold("failed");
        }
    }

    private void onFrameStart(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        FrameBench b = bench;
        if (b != null && state == State.RUNNING) {
            try {
                b.onFrameStart(System.nanoTime());
            } catch (Throwable t) {
                report.error("bench frame hook failed", t);
                b.forceFinish();
            }
        }
    }

    private void onFrameEnd(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (pendingShot == null) {
            return;
        }
        String name = pendingShot;
        int serial = shotSerial;
        pendingShot = null;
        Path file = report.screenshotFile(name);
        try {
            // A file left by an earlier step of the same name would make a failed save look like a success.
            Files.deleteIfExists(file);
            Thread grabThread = Thread.currentThread();
            // The main render target holds the complete frame (world and GUI) until it is blitted to the screen.
            // Screenshot.grab writes <dir>/screenshots/<name> on the IO pool and calls back from there, with
            // "screenshot.failure" if writing failed. If a ScreenshotEvent listener cancels the save, it calls back
            // right away on this thread with the cancel reason instead.
            Screenshot.grab(report.dir().toFile(), name + ".png", mc.getMainRenderTarget(), message -> {
                try {
                    onScreenshotResult(name, file, message, Thread.currentThread() == grabThread);
                } catch (Throwable t) {
                    // Never let this escape: Screenshot would call back a second time with "screenshot.failure".
                    report.error("screenshot '" + name + "': cannot check the result", t);
                } finally {
                    shotSaved = serial;
                }
            });
        } catch (Throwable t) {
            report.error("screenshot '" + name + "' failed", t);
            shotSaved = serial;
        }
    }

    /** Any thread: a screenshot callback; failures count as report errors. */
    private void onScreenshotResult(String name, Path file, Component message, boolean cancelled) {
        String text = message.getString();
        if (cancelled || message == ScreenshotEvent.DEFAULT_CANCEL_REASON) {
            report.error("screenshot '" + name + "' cancelled by a ScreenshotEvent listener: " + text);
        } else if (message.getContents() instanceof TranslatableContents tc && tc.getKey().equals(SCREENSHOT_FAILURE_KEY)) {
            report.error("screenshot '" + name + "' could not be saved: " + text);
        } else if (!Files.isRegularFile(file)) {
            report.error("screenshot '" + name + "' reported '" + text + "' but " + file + " does not exist");
        } else {
            report.line("screenshot: " + text);
        }
    }

    private void onScreenOpening(ScreenEvent.Opening event) {
        try {
            if (state != State.FINISHED && event.getNewScreen() instanceof AccessibilityOnboardingScreen) {
                mc.options.onboardAccessibility = false;
                mc.options.save();
                event.setNewScreen(new TitleScreen(true));
                report.line("replaced the accessibility onboarding screen with the title screen");
            }
        } catch (Throwable t) {
            report.error("screen hook failed", t);
        }
    }

    /** Writes every chat line to the report and hands it to a running {@code waitfor} step. */
    private void onChat(ClientChatReceivedEvent event) {
        try {
            String kind = event instanceof ClientChatReceivedEvent.System system
                    ? (system.isOverlay() ? "OVERLAY" : "SYSTEM") : "CHAT";
            String text = event.getMessage().getString().replace("\r", "").replace("\n", "\n    ");
            String line = kind + (event.isCanceled() ? " (canceled)" : "") + ": " + text;
            report.line(line);
            WaitFor w = waitFor;
            if (w != null && w.match == null && w.pattern.matcher(line).find()) {
                w.match = line;
            }
        } catch (Throwable t) {
            report.error("chat hook failed", t);
        }
    }

    /** The game is stopping without the harness having asked for it (window closed, crash handling). */
    private void onGameShuttingDown(GameShuttingDownEvent event) {
        State from = claimFinish();
        if (from == null) {
            return;
        }
        configOverrides.restore(report);
        try {
            writeSummary("aborted: game shut down externally", from, true);
        } catch (Throwable t) {
            report.error("cannot write summary", t);
        }
        report.raw("END");
        report.close();
    }

    // ---- states ------------------------------------------------------------------------------------------------

    private void tickBoot() {
        Screen screen = mc.screen;
        if (screen instanceof LoadingErrorScreen && screen != lastErrorScreen) {
            lastErrorScreen = screen;
            String label = ForgeI18n.parseMessage("fml.button.continue.launch");
            Button proceed = findButton(screen, b -> b.getMessage().getString().equals(label));
            if (proceed == null) {
                report.error("mod loading failed: " + describe(screen));
                writeLoadingIssues();
                finish("aborted: mod loading failed");
            } else {
                report.line("WARNING: mod loading warnings shown, continuing");
                writeLoadingIssues();
                pressLater(proceed);
            }
            return;
        }
        if (screen instanceof TitleScreen && mc.getOverlay() == null && advance(State.BOOT, State.LOADING)) {
            report.line("title screen reached");
            mc.tell(this::launchWorld);
        }
    }

    /**
     * Queued task: open the autotest world, creating it first if needed; or, with the system property
     * {@code tremor.autotest.server} ({@code host:port}), join that server instead (the multiplayer test,
     * {@code tools/mp-test.sh}).
     */
    private void launchWorld() {
        launchRan = true;
        String server = System.getProperty("tremor.autotest.server", "").strip();
        if (!server.isEmpty()) {
            report.line("connecting to server " + server);
            ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(server),
                    new ServerData("tremor autotest", server, false), false);
            return;
        }
        try {
            String name = config.world();
            LevelStorageSource source = mc.getLevelSource();
            boolean exists = false;
            if (source.levelExists(name)) {
                try (LevelStorageSource.LevelStorageAccess access = source.createAccess(name)) {
                    if (config.fresh() || !Files.exists(access.getLevelPath(LevelResource.LEVEL_DATA_FILE))) {
                        report.line("deleting world folder " + source.getBaseDir().resolve(name).toAbsolutePath()
                                + (config.fresh() ? " (fresh)" : " (no level data)"));
                        access.deleteLevel();
                    } else {
                        exists = true;
                    }
                }
            }
            if (exists) {
                report.line("opening world " + source.getBaseDir().resolve(name).toAbsolutePath());
                mc.createWorldOpenFlows().loadLevel(new TitleScreen(), name);
            } else {
                report.line("creating world " + source.getBaseDir().resolve(name).toAbsolutePath() + ": creative, peaceful, "
                        + "commands on, normal preset, seed " + config.seed() + ", no structures, "
                        + "doDaylightCycle/doWeatherCycle/doMobSpawning false");
                mc.createWorldOpenFlows().createFreshLevel(name, levelSettings(name),
                        new WorldOptions(config.seed(), false, false), WorldPresets::createNormalWorldDimensions);
            }
        } catch (Throwable t) {
            launchFailed = true;
            report.error("cannot open world '" + config.world() + "'", t);
        }
    }

    private static LevelSettings levelSettings(String name) {
        GameRules rules = new GameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
        return new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules,
                WorldDataConfiguration.DEFAULT);
    }

    private void tickLoading() {
        if (!launchRan) {
            return;
        }
        if (mc.level != null && mc.player != null) {
            if (mc.screen == null) {
                if (advance(State.LOADING, State.SETTLING)) {
                    report.line("joined the world (" + worldInfo() + ") at " + position(mc.player) + ", waiting for terrain");
                }
            } else if (++inWorldScreenTicks == STUCK_SCREEN_TICKS) {
                report.error("screen " + describe(mc.screen) + " still open after joining, closing it");
                mc.setScreen(null);
            }
            return;
        }
        Screen screen = mc.screen;
        if (launchFailed || screen instanceof TitleScreen || screen instanceof DisconnectedScreen
                || screen instanceof AlertScreen || screen instanceof DatapackLoadFailureScreen) {
            report.error("world loading failed" + (screen != null ? ", screen " + describe(screen) : ""));
            finish("aborted: world loading failed");
            return;
        }
        if ((screen instanceof ConfirmScreen || screen instanceof BackupConfirmScreen) && screen != lastConfirmed) {
            lastConfirmed = screen;
            Button button = null;
            for (String key : CONFIRM_KEYS) {
                button = findButton(screen, b -> b.getMessage().getContents() instanceof TranslatableContents tc
                        && tc.getKey().equals(key));
                if (button != null) {
                    break;
                }
            }
            if (button == null || ++confirms > MAX_AUTO_CONFIRMS) {
                report.error("cannot get past " + describe(screen));
                finish("aborted: world loading blocked");
                return;
            }
            report.line("auto-confirming " + describe(screen) + " with '" + button.getMessage().getString() + "'");
            pressLater(button);
        }
    }

    private void tickSettling() {
        if (lostWorld()) {
            return;
        }
        releaseMouse();
        if (!settled) {
            settleTicks++;
            if (terrainReady()) {
                settled = true;
                report.line("terrain ready after " + settleTicks + " ticks (" + terrainStats() + ")");
            } else if (settleTicks >= SETTLE_TIMEOUT_TICKS) {
                settled = true;
                report.line("WARNING: terrain not ready after " + settleTicks + " ticks (" + terrainStats()
                        + "), starting anyway");
            }
            return;
        }
        if (++settleExtra >= SETTLE_EXTRA_TICKS && advance(State.SETTLING, State.RUNNING)) {
            report.line("running script: " + script.steps().size() + " steps");
        }
    }

    private void tickRunning() {
        if (lostWorld()) {
            return;
        }
        releaseMouse();
        if (current != null) {
            stepTicks++;
            boolean done;
            try {
                done = continueStep(current);
            } catch (Throwable t) {
                report.error("step at line " + current.line() + " failed", t);
                abortStep();
                done = true;
            }
            if (!done) {
                return;
            }
            current = null;
            if (state == State.FINISHED) {
                return;
            }
        }
        if (stepIndex >= script.steps().size()) {
            finish("completed (end of script)");
            return;
        }
        Script.Step step = script.steps().get(stepIndex++);
        executed++;
        report.line("STEP " + stepIndex + "/" + script.steps().size() + " (line " + step.line() + "): " + step.source());
        stepTicks = 0;
        try {
            if (!startStep(step)) {
                current = step;
            }
        } catch (Throwable t) {
            report.error("step at line " + step.line() + " failed", t);
            abortStep();
        }
    }

    // ---- steps -------------------------------------------------------------------------------------------------

    /** Runs or starts a step; returns {@code true} if it is already complete. */
    private boolean startStep(Script.Step step) {
        LocalPlayer player = mc.player;
        switch (step.kind()) {
            case WAIT -> {
                waitLeft = step.number();
                return waitLeft == 0;
            }
            case WAITCHUNKS -> {
                resetTerrainWatch();
                return false;
            }
            case CMD -> player.connection.sendCommand(step.text());
            case HUD -> mc.options.hideGui = step.number() == 0;
            case VIEW -> mc.options.setCameraType(net.minecraft.client.CameraType.values()[(int) step.number()]);
            case LOOK -> look(player, step.yaw(), step.pitch());
            case LOOKAT -> {
                String[] xyz = step.text().split(" ");
                double dx = Double.parseDouble(xyz[0]) - player.getX();
                double dy = Double.parseDouble(xyz[1]) - player.getEyeY();
                double dz = Double.parseDouble(xyz[2]) - player.getZ();
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                look(player, yaw, pitch);
            }
            case HOLD -> {
                hold = new Hold(step, player.position());
                pressKeys(step.keys());
                // Whether the player could walk is judged when the hold ends, from what it did on every tick.
                report.line("hold " + step.text() + ": pressed for " + step.number() + " ticks at " + position(player));
                return false;
            }
            case RELEASE -> {
                releaseMovementKeys();
                report.line("release: movement keys released at " + position(player));
            }
            case GRAPHICS -> {
                // Same as the video settings screen: set the option, then rebuild the level renderer's targets.
                mc.options.graphicsMode().set(switch (step.text()) {
                    case "fast" -> GraphicsStatus.FAST;
                    case "fabulous" -> GraphicsStatus.FABULOUS;
                    default -> GraphicsStatus.FANCY;
                });
                mc.levelRenderer.allChanged();
                report.line("graphics: " + mc.options.graphicsMode().get());
            }
            case FPS -> {
                // The option stores steps of 10 and maps anything else (Script only lets multiples of 10 through)
                // down; the window's limit is what the render loop uses, so report that one.
                int requested = step.number();
                mc.options.framerateLimit().set(requested);
                int applied = mc.getWindow().getFramerateLimit();
                if (applied == requested) {
                    report.line("fps limit: " + fpsLabel(applied));
                } else {
                    report.line("WARNING: fps limit: " + fpsLabel(applied) + " applied, " + fpsLabel(requested)
                            + " requested (option value " + fpsLabel(mc.options.framerateLimit().get()) + ")");
                }
            }
            case BENCH -> {
                bench = new FrameBench(step.text(), step.number());
                return false;
            }
            case SCREENSHOT -> {
                report.line("screenshot -> " + report.screenshotFile(step.text()));
                shotSerial++;
                pendingShot = step.text();
                return false;
            }
            case LOG -> report.line("LOG: " + step.text());
            case CONFIG -> {
                try {
                    report.line(configOverrides.apply(Script.ConfigPath.parse(step.text()), step.value()));
                } catch (IllegalArgumentException e) {
                    report.error("script line " + step.line() + ": " + e.getMessage() + " [" + step.source() + "]");
                }
            }
            case WAITFOR -> {
                // Script.parse has compiled the regex once already, so this cannot fail.
                waitFor = new WaitFor(Script.waitforPattern(step.text()));
                return false;
            }
            case QUIT -> finish("completed (quit at line " + step.line() + ")");
            case INVALID -> report.error("script line " + step.line() + ": " + step.text() + " [" + step.source() + "]");
        }
        return true;
    }

    /** Advances a blocking step by one tick; returns {@code true} when it is complete. */
    private boolean continueStep(Script.Step step) {
        switch (step.kind()) {
            case WAIT -> {
                return --waitLeft <= 0;
            }
            case HOLD -> {
                Hold h = hold;
                if (h == null) {
                    // Ended by the pre-tick handler.
                    return true;
                }
                h.sample(mc.player, mc.screen != null);
                if (h.tally.ticks() < h.total) {
                    return false;
                }
                endHold(null);
                return true;
            }
            case WAITCHUNKS -> {
                if (terrainReady()) {
                    report.line("waitchunks: ready after " + stepTicks + " ticks (" + terrainStats() + ")");
                    return true;
                }
                if (stepTicks >= step.number()) {
                    report.line("WARNING: waitchunks timed out after " + stepTicks + " ticks (" + terrainStats() + ")");
                    return true;
                }
                return false;
            }
            case BENCH -> {
                FrameBench b = bench;
                b.onTick();
                if (!b.isDone() && stepTicks > b.ticks + BENCH_STALL_TICKS) {
                    report.error("bench '" + b.label + "': frames stopped arriving, cutting it short");
                    b.forceFinish();
                }
                if (b.isDone()) {
                    bench = null;
                    writeBench(b.result(), true);
                    return true;
                }
                return false;
            }
            case WAITFOR -> {
                WaitFor w = waitFor;
                if (w.match != null) {
                    waitFor = null;
                    report.line("PASS waitfor /" + step.text() + "/ after " + stepTicks + " ticks: " + w.match);
                    return true;
                }
                if (stepTicks >= step.number()) {
                    waitFor = null;
                    report.error("FAIL waitfor /" + step.text() + "/ after " + step.number() + " ticks");
                    return true;
                }
                return false;
            }
            case SCREENSHOT -> {
                if (shotSaved == shotSerial) {
                    return true;
                }
                if (stepTicks >= SCREENSHOT_TIMEOUT_TICKS) {
                    pendingShot = null;
                    report.error("screenshot '" + step.text() + "' not saved within " + SCREENSHOT_TIMEOUT_TICKS + " ticks");
                    return true;
                }
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    private void abortStep() {
        endHold("step failed");
        waitFor = null;
        pendingShot = null;
        FrameBench b = bench;
        bench = null;
        if (b != null) {
            b.forceFinish();
            writeBench(b.result(), true);
        }
    }

    // ---- movement keys -----------------------------------------------------------------------------------------

    private void pressKeys(Set<Script.HoldKey> keys) {
        for (Script.HoldKey key : keys) {
            mapping(key).setDown(true);
        }
    }

    /** Releases every key a hold can press and stops sprinting, which vanilla keeps up while forward stays pressed. */
    private void releaseMovementKeys() {
        for (Script.HoldKey key : Script.HoldKey.values()) {
            mapping(key).setDown(false);
        }
        if (mc.player != null) {
            mc.player.setSprinting(false);
        }
    }

    /**
     * One tick of a held left button on the block under the crosshair, as {@code Minecraft.continueAttack} does: that
     * one only mines while the window holds the mouse, which an unattended test window usually does not.
     */
    private void mine(LocalPlayer player) {
        if (mc.gameMode != null && mc.level != null && mc.screen == null
                && mc.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                && !mc.level.getBlockState(hit.getBlockPos()).isAir()
                && mc.gameMode.continueDestroyBlock(hit.getBlockPos(), hit.getDirection())) {
            mc.particleEngine.crack(hit.getBlockPos(), hit.getDirection());
            player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
    }

    private KeyMapping mapping(Script.HoldKey key) {
        Options options = mc.options;
        return switch (key) {
            case FORWARD -> options.keyUp;
            case BACK -> options.keyDown;
            case LEFT -> options.keyLeft;
            case RIGHT -> options.keyRight;
            case JUMP -> options.keyJump;
            case SNEAK -> options.keyShift;
            case SPRINT -> options.keySprint;
            case ATTACK -> options.keyAttack;
            case DROP -> options.keyDrop;
        };
    }

    /**
     * Ends the running hold, if any: releases the keys, reports where the player got to and what it did, then warns
     * about whatever kept the hold from walking as asked (see {@link HoldTally#warnings()}).
     */
    private void endHold(String earlyReason) {
        Hold h = hold;
        if (h == null) {
            return;
        }
        hold = null;
        try {
            releaseMovementKeys();
            LocalPlayer player = mc.player;
            int ticks = h.tally.ticks();
            report.line("hold " + h.label + ": released after " + (ticks < h.total ? ticks + " of " : "")
                    + h.total + " ticks" + (earlyReason != null ? " (" + earlyReason + ")" : "")
                    + (player != null ? " at " + position(player) + "; " + h.moved(player) : ", no player")
                    + "; " + h.tally.summary());
            for (String warning : h.tally.warnings()) {
                report.line("WARNING: hold " + h.label + ": " + warning);
            }
        } catch (Throwable t) {
            report.error("hold " + h.label + ": cannot release the keys", t);
        }
    }

    /**
     * Vanilla's double-tap of forward ({@code LocalPlayer.aiStep}): a rising edge of forward impulse on the ground
     * opens a 7-tick window in {@code sprintTriggerTime}, and another rising edge inside it starts sprinting. Two holds
     * with forward a few ticks apart are such a double tap. Zeroing the window before the player ticks (this runs in
     * {@link TickEvent.ClientTickEvent} start, before {@code tickEntities}) makes every rising edge only open it again.
     * <p>
     * The field is protected, so it is reached by reflection on its Mojang name (the runtime names in NeoForge 1.21.1),
     * resolved once. If that fails, the runner reports it once and only the backstop in {@link #onClientTickPre}
     * remains, which stops such a sprint a tick late.
     */
    private void suppressDoubleTapSprint(LocalPlayer player) {
        if (!sprintTriggerResolved) {
            sprintTriggerResolved = true;
            try {
                Field field = LocalPlayer.class.getDeclaredField(SPRINT_TRIGGER_FIELD);
                field.setAccessible(true);
                sprintTriggerTime = field;
            } catch (ReflectiveOperationException | RuntimeException e) {
                reportSprintTriggerUnavailable(e);
            }
        }
        Field field = sprintTriggerTime;
        if (field == null) {
            return;
        }
        try {
            field.setInt(player, 0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            sprintTriggerTime = null;
            reportSprintTriggerUnavailable(e);
        }
    }

    private void reportSprintTriggerUnavailable(Throwable e) {
        report.line("WARNING: cannot reset LocalPlayer." + SPRINT_TRIGGER_FIELD + " (" + e + "): a double tap of "
                + "forward can sprint for one tick in a hold without sprint before the harness stops it; such ticks "
                + "show up as hold warnings");
    }

    /** A running {@code hold} step: its keys, where it started and what the player did on the ticks so far. */
    private static final class Hold {
        final String label;
        final Set<Script.HoldKey> keys;
        final int total;
        final Vec3 start;
        final HoldTally tally;
        /** A held drop key has dropped its one item. */
        boolean dropped;

        Hold(Script.Step step, Vec3 start) {
            this.label = step.text();
            this.keys = step.keys();
            this.total = step.number();
            this.start = start;
            this.tally = new HoldTally(keys);
        }

        /** Counts one held tick, after the player has moved. */
        void sample(LocalPlayer player, boolean screen) {
            tally.sample(player == null ? null : new HoldTally.TickState(player.isSprinting(),
                    player.isShiftKeyDown(), player.onGround(), player.getAbilities().flying, player.isSpectator()),
                    screen);
        }

        String moved(LocalPlayer player) {
            Vec3 d = player.position().subtract(start);
            return String.format(Locale.ROOT, "moved %.2f blocks horizontally (dx %.2f, dz %.2f), dy %.2f",
                    Math.sqrt(d.x * d.x + d.z * d.z), d.x, d.z, d.y);
        }
    }

    /** A running {@code waitfor} step: its regex and the first chat line, as reported, that matched it. */
    private static final class WaitFor {
        final Pattern pattern;
        String match;

        WaitFor(Pattern pattern) {
            this.pattern = pattern;
        }
    }

    private static void look(LocalPlayer player, float yaw, float pitch) {
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.yRotO = yaw;
        player.xRotO = pitch;
        player.yHeadRot = yaw;
        player.yHeadRotO = yaw;
        player.yBodyRot = yaw;
        player.yBodyRotO = yaw;
        player.yBob = yaw;
        player.yBobO = yaw;
        player.xBob = pitch;
        player.xBobO = pitch;
    }

    /** @param withContext add the game context line; only on the client thread, other threads must not touch the game */
    private void writeBench(FrameBench.Result r, boolean withContext) {
        benchCount++;
        report.line(String.format(Locale.ROOT, "BENCH %s: %d ticks, %d frames in %.2f s%s", r.label(), r.ticks(),
                r.frames(), r.seconds(), r.incomplete() ? " (INCOMPLETE)" : ""));
        report.raw(String.format(Locale.ROOT, "  avg fps     %.1f", r.avgFps()));
        report.raw(String.format(Locale.ROOT, "  frame ms    mean %.2f | p50 %.2f | p95 %.2f | p99 %.2f | max %.2f",
                r.meanMs(), r.p50Ms(), r.p95Ms(), r.p99Ms(), r.maxMs()));
        report.raw(String.format(Locale.ROOT, "  renderer    frames %d | avg %.3f ms/frame | avg copies %.1f | avg vertices %.1f",
                r.renderer().frames(), r.renderer().avgMillis(), r.renderer().avgCopies(), r.renderer().avgVertices()));
        RenderStats.Motion motion = r.renderer().motion();
        if (motion.frames() > 0) {
            report.raw(String.format(Locale.ROOT,
                    "  motion      frames %d | speed %.2f b/s | cv %.3f | p01 %.2f | p99 %.2f | max %.2f | frame-to-frame"
                            + " change p99 %.3f, max %.3f (x mean)",
                    motion.frames(), motion.meanSpeed(), motion.cv(), motion.p01(), motion.p99(), motion.max(),
                    motion.jumpP99(), motion.jumpMax()));
        }
        if (withContext) {
            Window window = mc.getWindow();
            String context = "  context     window " + window.getWidth() + "x" + window.getHeight()
                    + " | render distance " + mc.options.getEffectiveRenderDistance()
                    + " | hud " + (mc.options.hideGui ? "off" : "on");
            if (mc.level != null && mc.player != null) {
                context += " | " + terrainStats() + " | " + position(mc.player);
            }
            report.raw(context);
        }
        report.csv(r.csvRow());
    }

    // ---- terrain -----------------------------------------------------------------------------------------------

    private void resetTerrainWatch() {
        stableTicks = 0;
        lastChunks = -1;
        lastSections = -1;
    }

    /**
     * Called once per tick: {@code true} once neither the loaded chunk count nor the number of visible meshed sections
     * has changed for {@link #TERRAIN_STABLE_TICKS} ticks and the section mesher's queue is empty right now. Chunks
     * stream in batches every tick until the view distance is filled, so a pause means the server is done sending;
     * the mesher only has to be idle at the end, so occasional re-meshing (fluids, block updates) does not block.
     */
    private boolean terrainReady() {
        int chunks = mc.level.getChunkSource().getLoadedChunksCount();
        int sections = mc.levelRenderer.countRenderedChunks();
        if (chunks == 0 || chunks != lastChunks || sections != lastSections) {
            stableTicks = 0;
        } else {
            stableTicks++;
        }
        lastChunks = chunks;
        lastSections = sections;
        return stableTicks >= TERRAIN_STABLE_TICKS && mc.levelRenderer.hasRenderedAllChunks();
    }

    private String terrainStats() {
        return "chunks " + mc.level.getChunkSource().getLoadedChunksCount()
                + ", meshed visible sections " + mc.levelRenderer.countRenderedChunks()
                + ", mesher " + (mc.levelRenderer.hasRenderedAllChunks() ? "idle" : "busy");
    }

    /** Without a grabbed mouse, someone touching the mouse cannot turn the camera mid-benchmark. */
    private void releaseMouse() {
        if (mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
        }
    }

    private boolean lostWorld() {
        if (mc.level != null && mc.player != null) {
            return false;
        }
        report.error("left the world unexpectedly" + (mc.screen != null ? ", screen " + describe(mc.screen) : ""));
        finish("aborted: left the world");
        return true;
    }

    // ---- shutdown ----------------------------------------------------------------------------------------------

    /**
     * Puts back the config values, writes the summary and queues leaving the world (saved, as "Save and Quit to Title"
     * does) and stopping the game.
     */
    private void finish(String status) {
        State from = claimFinish();
        if (from == null) {
            return;
        }
        endHold("run finished");
        configOverrides.restore(report);
        writeSummary(status, from, true);
        mc.tell(this::shutdown);
    }

    /** Atomically ends the run; returns the state it ended in, or {@code null} if it had already finished. */
    private State claimFinish() {
        synchronized (stateLock) {
            State from = state;
            if (from == State.FINISHED) {
                return null;
            }
            state = State.FINISHED;
            finishRequestedAtMillis = System.currentTimeMillis();
            return from;
        }
    }

    /** Client thread: moves on to {@code to} unless another thread finished the run in the meantime. */
    private boolean advance(State from, State to) {
        synchronized (stateLock) {
            if (state != from) {
                return false;
            }
            state = to;
            return true;
        }
    }

    /** @param onClientThread {@code false} from the watchdog or the shutdown hook, which must not touch the game */
    private void writeSummary(String status, State from, boolean onClientThread) {
        FrameBench b = bench;
        bench = null;
        if (b != null) {
            b.forceFinish();
            writeBench(b.result(), onClientThread);
        }
        pendingShot = null;
        writeSummaryBlock(report, status, from, executed, script.steps().size(), benchCount);
    }

    private static void writeSummaryBlock(Report report, String status, State from, int executed, int steps, int benches) {
        report.line("FINISH: " + status + " (state " + from + ")");
        report.raw("");
        report.raw("summary");
        report.raw("  status      " + status);
        report.raw("  steps       " + executed + " of " + steps + " executed");
        report.raw("  benches     " + benches);
        report.raw("  errors      " + report.errorCount());
        report.raw("");
    }

    private void shutdown() {
        try {
            if (mc.level != null) {
                report.line("saving and leaving the world");
                mc.level.disconnect();
                mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
                report.line("world saved and closed");
            } else if (mc.getSingleplayerServer() != null) {
                mc.clearLevel(new GenericDirtMessageScreen(Component.translatable("menu.savingLevel")));
            }
        } catch (Throwable t) {
            report.error("leaving the world failed", t);
        }
        report.line("stopping the client");
        report.raw("END");
        report.close();
        mc.stop();
    }

    /**
     * Last resort for hangs where client ticks stop (e.g. a stuck integrated server): halts the JVM
     * {@link #WATCHDOG_GRACE_MS} after the global timeout, or {@link #SHUTDOWN_GRACE_MS} after finishing if the
     * process is still alive by then. Also the only part of the harness that can notice a fatal mod loading error,
     * see {@link #failModLoading()}.
     */
    private void startWatchdog() {
        long hardDeadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(config.timeoutSeconds()) + WATCHDOG_GRACE_MS;
        Thread watchdog = new Thread(() -> {
            int loadingErrorPolls = 0;
            try {
                while (true) {
                    TimeUnit.SECONDS.sleep(1);
                    long now = System.currentTimeMillis();
                    long finishedAt = finishRequestedAtMillis;
                    if (finishedAt != 0) {
                        long grace = stopGraceMs;
                        if (now - finishedAt > grace) {
                            report.error("watchdog: the client did not exit within " + grace / 1000
                                    + " s after finishing, halting the JVM");
                            // Halting skips the shutdown hook; finishing has normally put the config values back.
                            configOverrides.restoreBounded(report);
                            report.close();
                            Runtime.getRuntime().halt(stopExitCode);
                        }
                    } else if (state == State.BOOT && tick == 0 && fatalLoadingError(mc)) {
                        // No client tick has ever been seen: the game bus is not running, so nothing else will act.
                        if (++loadingErrorPolls >= LOADING_ERROR_POLLS) {
                            failModLoading();
                        }
                    } else if (now > hardDeadline) {
                        report.error("watchdog: no clean finish within timeout + " + WATCHDOG_GRACE_MS / 1000
                                + " s (state " + state + "), halting the JVM");
                        Thread client = clientThread;
                        if (client != null) {
                            report.raw("client thread stack:");
                            for (StackTraceElement e : client.getStackTrace()) {
                                report.raw("    at " + e);
                            }
                        }
                        // Halting skips the shutdown hook.
                        configOverrides.restoreBounded(report);
                        report.raw("END");
                        report.close();
                        Runtime.getRuntime().halt(EXIT_TIMEOUT);
                    } else {
                        loadingErrorPolls = 0;
                    }
                }
            } catch (InterruptedException ignored) {
            }
        }, "Tremor autotest watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /**
     * {@code true} while the game shows the {@link LoadingErrorScreen} of a failed mod loading. After such an error
     * {@code ClientModLoader.completeModLoading} returns that screen without ever starting NeoForge's game bus, so
     * events posted there (ticks, frames, screens, {@link GameShuttingDownEvent}) never reach any listener.
     */
    private static boolean fatalLoadingError(Minecraft mc) {
        try {
            // The screen first: once it is up, loading has ended and FML's issue list is complete.
            return mc.screen instanceof LoadingErrorScreen && !ModLoader.isLoadingStateValid();
        } catch (Throwable t) {
            // The issue list is filled by loader threads without synchronisation.
            return false;
        }
    }

    /**
     * Watchdog thread: mod loading failed and the game bus never started. Completes the report and queues stopping the
     * game on the client task queue, which is still processed; if that does not work either, the watchdog halts the
     * JVM {@link #MOD_LOADING_STOP_GRACE_MS} later with {@value #EXIT_MOD_LOADING_FAILED}.
     */
    private void failModLoading() {
        State from = claimFinish();
        if (from == null) {
            return;
        }
        stopGraceMs = MOD_LOADING_STOP_GRACE_MS;
        stopExitCode = EXIT_MOD_LOADING_FAILED;
        try {
            Screen screen = mc.screen;
            report.error("mod loading failed" + (screen != null ? ": " + describe(screen) : "")
                    + "; NeoForge's game event bus was not started, the harness cannot run");
            writeLoadingIssues();
            report.line("crash report: " + findCrashReport());
            writeSummary("aborted: mod loading failed", from, false);
        } catch (Throwable t) {
            report.error("cannot report the mod loading failure", t);
        }
        report.line("stopping the client");
        report.raw("END");
        report.close();
        try {
            mc.execute(mc::stop);
        } catch (Throwable t) {
            Tremor.LOGGER.error("[autotest] cannot queue stopping the game", t);
        }
    }

    /**
     * Writes FML's mod loading warnings to the report; the errors the {@link LoadingErrorScreen} lists are in the log
     * (Forge 1.20.1 keeps them to the screen).
     */
    private void writeLoadingIssues() {
        List<ModLoadingWarning> warnings;
        try {
            warnings = ModLoader.get().getWarnings();
        } catch (Throwable t) {
            report.line("cannot read the mod loading warnings: " + t);
            return;
        }
        report.raw("mod loading warnings: " + warnings.size() + " (errors: see the log)");
        for (ModLoadingWarning warning : warnings) {
            report.raw("  " + warning.formatToString());
        }
    }

    /**
     * JVM shutdown hook. A crash ({@code Minecraft.crash}) exits through {@link System#exit} without
     * {@link Minecraft#stop()}, so {@link GameShuttingDownEvent} never fires and nothing else would complete the
     * report. Runs while the client thread may be stuck or blocked inside {@code System.exit}: it only reads game
     * fields, and neither the report nor putting back the config values makes it wait for long.
     */
    private void onJvmShutdown() {
        if (report.isClosed()) {
            return;
        }
        try {
            State from = claimFinish();
            String crashReport = findCrashReport();
            if (from != null) {
                report.error("JVM exiting without a clean stop (game crash?)");
                report.line("crash report: " + crashReport);
                if (from == State.BOOT && fatalLoadingError(mc)) {
                    // Closed on the mod loading error screen before the watchdog noticed it.
                    writeLoadingIssues();
                }
                configOverrides.restoreBounded(report);
                writeSummary("aborted: JVM exiting without a clean stop (game crash?)", from, false);
            } else {
                report.error("JVM exiting before the harness finished stopping the game (game crash?)");
                report.line("crash report: " + crashReport);
                configOverrides.restoreBounded(report);
            }
        } catch (Throwable t) {
            report.error("shutdown hook failed", t);
        }
        report.raw("END");
        report.close();
    }

    /** The newest file in {@code <gameDir>/crash-reports} written since the harness started, else that directory. */
    private String findCrashReport() {
        Path dir = mc.gameDirectory.toPath().resolve("crash-reports").toAbsolutePath().normalize();
        Path newest = null;
        long newestModified = report.startedAtMillis();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                try {
                    long modified = Files.getLastModifiedTime(file).toMillis();
                    if (modified >= newestModified && Files.isRegularFile(file)) {
                        newest = file;
                        newestModified = modified;
                    }
                } catch (IOException | RuntimeException e) {
                    // Vanished or unreadable: skip it.
                }
            }
        } catch (IOException | RuntimeException e) {
            // No such directory (nothing saved) or unreadable: point at the directory.
        }
        return newest != null ? newest.toString() : "none written since the harness started, see " + dir;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /** Presses the button from the task queue, like a real click, outside of any event handler. */
    private void pressLater(Button button) {
        mc.tell(() -> {
            try {
                button.onPress();
            } catch (Throwable t) {
                report.error("pressing '" + button.getMessage().getString() + "' failed", t);
                finish("aborted: exception");
            }
        });
    }

    private static Button findButton(Screen screen, Predicate<Button> filter) {
        for (GuiEventListener child : screen.children()) {
            if (child instanceof Button button && filter.test(button)) {
                return button;
            }
        }
        return null;
    }

    private String worldInfo() {
        String info = mc.gameMode != null ? mc.gameMode.getPlayerMode().getName() : "?";
        if (mc.getSingleplayerServer() != null) {
            info += ", seed " + mc.getSingleplayerServer().getWorldData().worldGenOptions().seed();
        }
        return info;
    }

    private static String fpsLabel(int limit) {
        return limit >= Options.UNLIMITED_FRAMERATE_CUTOFF ? "unlimited" : Integer.toString(limit);
    }

    private static String describe(Screen screen) {
        return screen.getClass().getSimpleName() + " '" + screen.getTitle().getString() + "'";
    }

    private static String position(LocalPlayer player) {
        return String.format(Locale.ROOT, "pos %.1f %.1f %.1f, yaw %.1f, pitch %.1f",
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
    }
}
