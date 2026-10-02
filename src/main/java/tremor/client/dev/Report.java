package tremor.client.dev;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import tremor.Tremor;

/**
 * The run's output directory: {@code report.txt} (flushed after every write, so a crash still leaves partial results),
 * {@code bench.csv} and the {@code screenshots/} folder.
 * <p>
 * Thread-safe: screenshot callbacks arrive on the IO pool, and the watchdog and the JVM shutdown hook write while the
 * client thread may be stuck. Writers never wait more than {@link #LOCK_TIMEOUT_MS} for the file; once a wait timed
 * out, later writes give up immediately until the file is free again, so a writer frozen mid-write can drop lines but
 * never hang the JVM's exit.
 */
final class Report {
    static final String CSV_HEADER = "label,ticks,frames,seconds,avg_fps,mean_ms,p50_ms,p95_ms,p99_ms,max_ms,"
            + "renderer_frames,renderer_avg_ms,renderer_avg_copies,renderer_avg_vertices";
    static final String LATEST = "latest.txt";
    private static final long LOCK_TIMEOUT_MS = 2000;

    private final Path dir;
    private final long startNanos = System.nanoTime();
    private final long startMillis = System.currentTimeMillis();
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicInteger errors = new AtomicInteger();
    private volatile LongSupplier tick = () -> 0;
    private volatile boolean wedged;
    private volatile boolean closed;
    /** Guarded by {@link #lock}. */
    private BufferedWriter out;

    private Report(Path dir, BufferedWriter out) {
        this.dir = dir;
        this.out = out;
    }

    /**
     * Creates {@code <root>/<yyyyMMdd-HHmmss>/} and points {@code <root>/latest.txt} at it before opening
     * {@code report.txt} in it. If anything fails, {@code latest.txt} is deleted (best effort) so that it never points
     * at a previous run.
     */
    static Report create(Path root) throws IOException {
        Path latest = root.resolve(LATEST);
        try {
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path dir = root.resolve(stamp);
            for (int i = 2; Files.exists(dir); i++) {
                dir = root.resolve(stamp + "-" + i);
            }
            dir = dir.toAbsolutePath().normalize();
            Files.createDirectories(dir.resolve("screenshots"));
            Files.writeString(latest, dir + System.lineSeparator(), StandardCharsets.UTF_8);
            BufferedWriter out = Files.newBufferedWriter(dir.resolve("report.txt"), StandardCharsets.UTF_8);
            return new Report(dir, out);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(latest);
            } catch (IOException | RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    /** Supplies the current client tick for line prefixes ({@code 0} until set). */
    void tickSource(LongSupplier tick) {
        this.tick = tick;
    }

    Path dir() {
        return dir;
    }

    /** Wall-clock time the report was created, i.e. when the harness started. */
    long startedAtMillis() {
        return startMillis;
    }

    Path screenshotFile(String name) {
        return dir.resolve("screenshots").resolve(name + ".png");
    }

    int errorCount() {
        return errors.get();
    }

    /** {@code true} once {@link #close()} was called; nothing is written after that. */
    boolean isClosed() {
        return closed;
    }

    /** Writes a line without the tick prefix (headers, bench blocks). */
    void raw(String text) {
        if (closed) {
            return;
        }
        if (!lock()) {
            Tremor.LOGGER.error("[autotest] report file busy, dropped line: {}", text);
            return;
        }
        try {
            if (out != null) {
                out.write(text);
                out.newLine();
                out.flush();
            }
        } catch (IOException e) {
            Tremor.LOGGER.error("[autotest] cannot write report", e);
        } finally {
            lock.unlock();
        }
    }

    /** Writes a line prefixed with the client tick and the time since the harness started. */
    void line(String text) {
        raw(prefix() + text);
    }

    void error(String text) {
        errors.incrementAndGet();
        Tremor.LOGGER.error("[autotest] {}", text);
        line("ERROR: " + text);
    }

    void error(String text, Throwable t) {
        errors.incrementAndGet();
        Tremor.LOGGER.error("[autotest] {}", text, t);
        line("ERROR: " + text + ": " + t);
        stackTrace(t);
    }

    /** Writes the stack trace of {@code t} without its first line (the exception itself), indented. */
    void stackTrace(Throwable t) {
        StringWriter trace = new StringWriter();
        t.printStackTrace(new PrintWriter(trace));
        trace.toString().lines().skip(1).forEach(l -> raw("    " + l.strip()));
    }

    /** Appends one row to {@code bench.csv}, writing the header first if the file is new. */
    void csv(String row) {
        if (!lock()) {
            Tremor.LOGGER.error("[autotest] report files busy, dropped bench.csv row: {}", row);
            return;
        }
        Path file = dir.resolve("bench.csv");
        try {
            String content = (Files.exists(file) ? "" : CSV_HEADER + System.lineSeparator()) + row + System.lineSeparator();
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Tremor.LOGGER.error("[autotest] cannot write bench.csv", e);
        } finally {
            lock.unlock();
        }
    }

    void close() {
        closed = true;
        if (!lock()) {
            // Someone is stuck inside a write and holds the file; the OS closes it when the process exits.
            Tremor.LOGGER.error("[autotest] report file busy, cannot close it");
            return;
        }
        try {
            if (out != null) {
                out.close();
            }
        } catch (IOException e) {
            Tremor.LOGGER.error("[autotest] cannot close report", e);
        } finally {
            out = null;
            lock.unlock();
        }
    }

    /** Takes the writer lock, waiting at most {@link #LOCK_TIMEOUT_MS} (not at all after a wait has timed out). */
    private boolean lock() {
        boolean locked;
        try {
            locked = wedged ? lock.tryLock() : lock.tryLock(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = lock.tryLock();
        }
        wedged = !locked;
        return locked;
    }

    private String prefix() {
        double seconds = (System.nanoTime() - startNanos) / 1e9;
        return String.format(Locale.ROOT, "[t=%d +%.1fs] ", tick.getAsLong(), seconds);
    }
}
