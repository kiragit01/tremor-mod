package tremor.debug;

/** What {@code /tremor debug <view> on|off} shows with particles (SPEC 14.1). */
public enum DebugView {
    /** The route ahead: green on the skin, red through rock, a big marker at the target. */
    PATH("path"),
    /** Smoothed surface normals of the nodes around the entity, and the entity's own (time-smoothed) normal. */
    NORMALS("normals"),
    /** Graph nodes around the entity and the midpoints of their edges (dive edges in orange). */
    GRAPH("graph"),
    /**
     * Every vibration the entity evaluates: a particle where it enters the ground (green heard, red not, bigger for
     * louder) and its numbers on the action bar, with the anger it added and how the stage reacts. Drawn as
     * vibrations come in, not periodically.
     */
    HEARING("hearing");

    private final String id;

    DebugView(String id) {
        this.id = id;
    }

    /** Name in the command. */
    public String id() {
        return id;
    }
}
