package tremor.client.sound;

/**
 * Loudness and timing of what a player hears inside the hollow (SPEC 9 phase 2), as plain functions;
 * {@link HollowSounds} and {@link WorldSilence} play it. No game classes.
 * <ul>
 *     <li>The node's heartbeat comes from the node (SPEC 9: "его ищут по сердцебиению (объёмный звук, громче
 *     ближе)"): it is found by ear in the dark, so it is heard from anywhere in the copy, never quieter than a floor
 *     ({@link #FAR_SHARE}), and grows plainly louder the nearer the player comes ({@link #beatFalloff}) and the further
 *     the hollow has closed ({@code closeness}, 0..1); its pace is the server's.</li>
 *     <li>As the ring of a beat runs under the player, the ground gives a faint, deeper thump ({@link #thumpVolume}).
 *     </li>
 *     <li>The pull of the soft ground ({@code sink}, 0 free .. 1 fully pulled in) muffles everything else
 *     ({@link #muffle}) and squelches under the player, more often and louder the deeper it has the player.</li>
 * </ul>
 */
final class HollowTone {
    /** Share of the configured heartbeat volume while the hollow has not started to close; all of it once closed. */
    static final double QUIETEST_BEAT = 0.6;
    /** Pitch added to the heartbeat once the hollow has closed, linearly from 0. */
    static final double BEAT_PITCH_RISE = 0.1;
    /** Within this distance of the node (blocks) the heartbeat is at its loudest... */
    static final double NEAR_NODE = 4;
    /**
     * ...beyond, its loudness falls as {@code (NEAR_NODE / distance)^FALLOFF_POWER}: by half (6 dB) every time the
     * distance grows fourfold, so that each step closer is heard, near and far alike...
     */
    static final double FALLOFF_POWER = 0.5;
    /**
     * ...but not below this share of it (reached some 44 blocks out): the heartbeat is heard all over the hollow, from
     * the far side of the copy too.
     */
    static final double FAR_SHARE = 0.3;
    /** Share of the configured heartbeat volume the thump of a ring at its full height passing under the player has. */
    static final double THUMP_SHARE = 0.3;
    /** Share of their loudness the other sounds lose when the player is fully pulled in. */
    static final double MUFFLE_DEPTH = 0.75;
    /** Sink below which the ground does not squelch. */
    static final double PULL_SILENT = 0.01;
    /** Ticks from one squelch of the pulling ground to the next as the pull starts... */
    static final int SLOWEST_PULL_TICKS = 28;
    /** ...and when the player is fully pulled in. */
    static final int FASTEST_PULL_TICKS = 12;
    /** Share of the configured pull volume as the pull starts; all of it when the player is fully pulled in. */
    static final double QUIETEST_PULL = 0.4;
    /** Pitch taken off the squelch when the player is fully pulled in: it deepens as the player sinks. */
    static final double PULL_PITCH_DROP = 0.15;

    private HollowTone() {
    }

    /**
     * Volume of a heartbeat of the node {@code distance} blocks away: {@code base}, times a share rising linearly from
     * {@link #QUIETEST_BEAT} with the closeness, times {@link #beatFalloff}, times {@code muffle}. At least
     * {@code base·QUIETEST_BEAT·FAR_SHARE·muffle} however far off: about a fifth of the configured volume.
     */
    static double beatVolume(double base, double closeness, double distance, double muffle) {
        return base * lerp(QUIETEST_BEAT, 1, share(closeness)) * beatFalloff(distance) * share(muffle);
    }

    /**
     * Share of its loudness the heartbeat keeps {@code distance} blocks from the node: 1 within {@link #NEAR_NODE},
     * then {@code (NEAR_NODE / distance)^FALLOFF_POWER} (about 0.63 at 10 blocks, 0.45 at 20, 0.34 at 35), and never
     * less than {@link #FAR_SHARE}. Unlike the engine's attenuation, which dies out at a fixed distance, it never falls
     * silent within the hollow, and it tells near from far all the way in. 1 for a NaN distance.
     */
    static double beatFalloff(double distance) {
        if (!(distance > NEAR_NODE)) {
            return 1;
        }
        return Math.max(FAR_SHARE, Math.pow(NEAR_NODE / distance, FALLOFF_POWER));
    }

    /**
     * Volume of the thump of a ring passing under the player: {@code base} (the heartbeat's) times
     * {@link #THUMP_SHARE}, times the ring's {@code strength} (its height now as a share of a fresh ring's at the start
     * of the closing, clamped to 0..1), times {@code muffle}: faint, below the heartbeat near the node.
     */
    static double thumpVolume(double base, double strength, double muffle) {
        return base * THUMP_SHARE * share(strength) * share(muffle);
    }

    /** Pitch of a heartbeat: 1, plus up to {@link #BEAT_PITCH_RISE} as the hollow closes. */
    static double beatPitch(double closeness) {
        return 1 + BEAT_PITCH_RISE * share(closeness);
    }

    /**
     * Gain of every other sound while the ground pulls the player in: {@code 1 - MUFFLE_DEPTH·sink}, down to a quarter
     * when fully pulled in, as if heard through the earth.
     */
    static double muffle(double sink) {
        return 1 - MUFFLE_DEPTH * share(sink);
    }

    /** Whether the ground squelches at this sink: above {@link #PULL_SILENT}. */
    static boolean pulling(double sink) {
        return sink > PULL_SILENT;
    }

    /**
     * Ticks to the next squelch: from {@link #SLOWEST_PULL_TICKS} linearly to {@link #FASTEST_PULL_TICKS} with the
     * sink.
     */
    static int pullTicks(double sink) {
        return (int) Math.round(lerp(SLOWEST_PULL_TICKS, FASTEST_PULL_TICKS, share(sink)));
    }

    /** Volume of a squelch: {@code base} times a share rising linearly from {@link #QUIETEST_PULL} with the sink. */
    static double pullVolume(double base, double sink) {
        return base * lerp(QUIETEST_PULL, 1, share(sink));
    }

    /** Pitch of a squelch: 1, less up to {@link #PULL_PITCH_DROP} with the sink. */
    static double pullPitch(double sink) {
        return 1 - PULL_PITCH_DROP * share(sink);
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    /** {@code x} clamped to {@code [0, 1]}; NaN counts as 0. */
    private static double share(double x) {
        return x > 0 ? Math.min(x, 1) : 0;
    }
}
