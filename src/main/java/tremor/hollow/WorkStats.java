package tremor.hollow;

/**
 * What one job of the hollow (copying a slot or clearing it) cost (SPEC 16): the server time of each tick that worked
 * on it, and what was written. Shown by {@code /tremor hollow status} and logged when the job is done. Plain Java.
 */
public final class WorkStats {
    private int ticks;
    private long totalNanos;
    private long worstNanos;
    private long blocks;
    private long changed;
    private long lightChecks;
    private int lightTicks;
    private boolean finished;

    /** One tick's work on the job took {@code nanos} of server time. */
    public void addTick(long nanos) {
        ticks++;
        totalNanos += nanos;
        worstNanos = Math.max(worstNanos, nanos);
    }

    /** A piece was written: {@code blocks} visited, {@code changed} of them changed, {@code lightChecks} queued. */
    public void addPiece(long blocks, long changed, long lightChecks) {
        this.blocks += blocks;
        this.changed += changed;
        this.lightChecks += lightChecks;
    }

    /**
     * The light engine finished the job's light checks {@code ticks} ticks after the last piece was written: the job
     * is done.
     */
    public void lightDone(int ticks) {
        lightTicks = ticks;
        finished = true;
    }

    public int ticks() {
        return ticks;
    }

    public double totalMillis() {
        return totalNanos / 1e6;
    }

    public double worstMillis() {
        return worstNanos / 1e6;
    }

    public long blocks() {
        return blocks;
    }

    public long changed() {
        return changed;
    }

    public long lightChecks() {
        return lightChecks;
    }

    public int lightTicks() {
        return lightTicks;
    }

    public boolean finished() {
        return finished;
    }
}
