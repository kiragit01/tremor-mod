package tremor.client.sound;

/**
 * Timing and loudness of the build-up of an Awakening as the player in its zone hears it (SPEC 9 phase 1: "Тишина:
 * внутри зоны у игрока приглушаются все звуки мира (мобы, погода, музыка), остаётся низкий гул и сердцебиение"), as
 * plain functions; {@link WorldSilence}, {@link AwakeningSounds} and {@link HumSound} play it. No game classes.
 * <p>
 * Everything follows one tension, 0..1 ({@link #tension}): the heartbeat quickens and grows louder, the hum swells and
 * rises a little in pitch.
 */
final class AwakeningTone {
    static final double TICKS_PER_SECOND = 20;
    /** Seconds the world takes to fall silent around a player in the zone, and to come back once the silence ends. */
    static final double SILENCE_FADE_SECONDS = 3.0;
    /**
     * Quietest gain the silence passes on its way to a floor of 0 (-60 dB): it fades evenly in decibels, so a floor of
     * 0 is taken only at the very end, from a gain nobody hears.
     */
    static final double QUIETEST = 0.001;
    /** Seconds from one heartbeat to the next at the start of the build-up (about 55 a minute). */
    static final double SLOWEST_BEAT_SECONDS = 1.1;
    /** Seconds from one heartbeat to the next at the end of the build-up and while swallowing (about 133 a minute). */
    static final double FASTEST_BEAT_SECONDS = 0.45;
    /** Share of the configured heartbeat volume at tension 0; it rises linearly to all of it at tension 1. */
    static final double QUIETEST_BEAT = 0.4;
    /** Pitch added to the heartbeat at tension 1, linearly from 0. */
    static final double BEAT_PITCH_RISE = 0.1;
    /** Share of the configured hum volume at tension 0; it rises linearly to all of it at tension 1. */
    static final double QUIETEST_HUM = 0.35;
    /** Pitch added to the hum at tension 1, linearly from 0. */
    static final double HUM_PITCH_RISE = 0.2;
    /** Time constant (seconds) with which the hum swells toward a louder target: it comes up over a few seconds. */
    static final double HUM_ATTACK_SECONDS = 1.0;
    /** Time constant (seconds) with which the hum dies toward a quieter one: gone about a second after the event. */
    static final double HUM_RELEASE_SECONDS = 0.25;

    private AwakeningTone() {
    }

    /**
     * Tension of the build-up: its progress (0 at the start, 1 when the zone closes), then 1 while the hill swallows
     * the player.
     *
     * @param progress fraction of the build-up that has passed
     */
    static double tension(boolean swallowing, double progress) {
        return swallowing ? 1 : share(progress);
    }

    /**
     * Ticks from one heartbeat to the next: the rate (beats per second) rises linearly with the tension from
     * {@code 1 / SLOWEST_BEAT_SECONDS} to {@code 1 / FASTEST_BEAT_SECONDS}, like a pulse that steadily quickens. Whole
     * ticks, so the beats keep an even rhythm and the rate changes in small steps.
     */
    static int beatTicks(double tension) {
        double t = share(tension);
        double rate = (1 - t) / SLOWEST_BEAT_SECONDS + t / FASTEST_BEAT_SECONDS;
        return Math.max(1, (int) Math.round(TICKS_PER_SECOND / rate));
    }

    /** Volume of a heartbeat: {@code base} times a share rising linearly from {@link #QUIETEST_BEAT} with tension. */
    static double beatVolume(double base, double tension) {
        return base * lerp(QUIETEST_BEAT, 1, share(tension));
    }

    /** Pitch of a heartbeat: 1, plus up to {@link #BEAT_PITCH_RISE} with tension. */
    static double beatPitch(double tension) {
        return 1 + BEAT_PITCH_RISE * share(tension);
    }

    /** Target volume of the hum: {@code base} times a share rising linearly from {@link #QUIETEST_HUM} with tension. */
    static double humVolume(double base, double tension) {
        return base * lerp(QUIETEST_HUM, 1, share(tension));
    }

    /** Pitch of the hum: 1, plus up to {@link #HUM_PITCH_RISE} with tension. */
    static double humPitch(double tension) {
        return 1 + HUM_PITCH_RISE * share(tension);
    }

    /**
     * Played volume (or pitch) of the hum after {@code dtSeconds}, eased exponentially toward {@code target}: with
     * {@link #HUM_ATTACK_SECONDS} up, {@link #HUM_RELEASE_SECONDS} down.
     */
    static double humApproach(double current, double target, double dtSeconds) {
        double tau = target > current ? HUM_ATTACK_SECONDS : HUM_RELEASE_SECONDS;
        return target + (current - target) * Math.exp(-dtSeconds / tau);
    }

    /**
     * Depth of the silence after {@code dtSeconds}: it moves linearly toward 1 while the player is {@code silenced} and
     * back toward 0 otherwise, the whole way in {@link #SILENCE_FADE_SECONDS}; clamped to {@code [0, 1]}.
     */
    static double silenceDepth(double depth, boolean silenced, double dtSeconds) {
        double step = dtSeconds / SILENCE_FADE_SECONDS;
        return share(depth + (silenced ? step : -step));
    }

    /**
     * Gain of the sounds of the world at a depth of the silence: 1 at depth 0, {@code floor} at depth 1, in between
     * {@code floor^s} with {@code s} the smoothstep of the depth: even in decibels, so it sounds as if it fell
     * steadily, and gentle at both ends. A floor of 0 is approached through {@link #QUIETEST}; a floor of 1 or more is
     * no silence at all.
     */
    static double silenceGain(double depth, double floor) {
        if (!(floor < 1)) {
            return 1;
        }
        double d = share(depth);
        if (d >= 1) {
            return Math.max(0, floor);
        }
        double s = d * d * (3 - 2 * d);
        return Math.exp(Math.log(Math.max(floor, QUIETEST)) * s);
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }

    /** {@code x} clamped to {@code [0, 1]}; NaN counts as 0. */
    private static double share(double x) {
        return x > 0 ? Math.min(x, 1) : 0;
    }
}
