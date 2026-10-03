package tremor.core.shape;

import tremor.core.noise.PerlinNoise;

/**
 * The formulas of the ground of the hollow around the player (SPEC 9 phase 2), each a displacement along the surface
 * normal or a share of one, summed by {@link HollowField}:
 * <ul>
 *     <li><b>heaving</b> ("пространство ходит ходуном"): every patch of the walls and the floor rises and falls,
 *     {@code A·(0.5 - 0.5·cos(2πt/T + φ(p)))}, out of step with its neighbours ({@link #heave}); {@code A} grows as
 *     the hollow closes ({@link #breathAmplitude});</li>
 *     <li><b>the node's rings</b> ("по волне видно, откуда она пришла"): a {@link Ripple} from the node on every
 *     beat, higher as the hollow closes ({@link #nodeRing}), long-lived enough to reach the player from across the
 *     copy still plain to see ({@link #ringStrength}), its crest higher where it passes the player
 *     ({@link #crestShare});</li>
 * </ul>
 * both only around the player ({@link #near}): the fog of the hollow hides the rest. Pure math, thread-safe.
 */
public final class HollowShape {
    /**
     * How far out of step patches of ground heave: the phase of a patch is {@code PHASE_SPREAD·π·(1 + n)} for the
     * noise {@code n} there, which spans about half a turn over the noise's usual range: at any moment some patches
     * are up while others are down, and neighbouring blocks stay nearly in step.
     */
    public static final double PHASE_SPREAD = 1.0;

    private HollowShape() {
    }

    /**
     * How far the hollow has closed, 0..1: the share of the way from {@code startRadius} (where the closing starts,
     * the edge) down to {@code minRadius} (where it stops) that the closing radius {@code closeRadius} has come,
     * {@code (startRadius - closeRadius) / (startRadius - minRadius)} clamped: 0 before it has started to close, 1 once
     * it has closed as far as it goes, like the server's schedule ({@code ClosingSchedule.closed}), which quickens the
     * node's pulse by the same share. A minimum at or beyond the start leaves nothing to close: 1 (the schedule then
     * beats at its fastest from the start). 0 for a NaN.
     */
    public static double closeness(double startRadius, double minRadius, double closeRadius) {
        if (Double.isNaN(startRadius) || Double.isNaN(minRadius) || Double.isNaN(closeRadius)) {
            return 0;
        }
        double span = startRadius - Math.min(minRadius, startRadius);
        return span > 0 ? share((startRadius - closeRadius) / span) : 1;
    }

    /**
     * Height of a heave at {@code closeness} (0..1, clamped; NaN counts as 0): from {@link HollowParams#breathStart}
     * linearly to {@link HollowParams#breathEnd}.
     */
    public static double breathAmplitude(HollowParams p, double closeness) {
        return lerp(p.breathStart(), p.breathEnd(), share(closeness));
    }

    /**
     * The ring of a beat at {@code closeness} (0..1, clamped; NaN counts as 0): {@link HollowParams#ring} with its
     * amplitude growing linearly to {@link HollowParams#ringEnd}.
     */
    public static RippleParams nodeRing(HollowParams p, double closeness) {
        return p.ring().withAmplitude(lerp(p.ring().amplitude(), p.ringEnd(), share(closeness)));
    }

    /**
     * Share of its starting height a ring keeps when its leading crest (a quarter wavelength behind the front)
     * reaches {@code distance} blocks from its origin: the ring's envelope {@code 1 - age/duration} at that moment,
     * {@code 1 - (distance + wavelength/4) / (speed·duration)}, clamped to 0..1 (0 for a NaN). It falls only slowly
     * with the distance: a ring of the node reaches the player across the copy still plain to see.
     */
    public static double ringStrength(RippleParams ring, double distance) {
        double age = (Math.max(distance, 0) + ring.wavelength() / 4) / ring.speed();
        return share(1 - age / ring.duration());
    }

    /**
     * Share of {@link HollowParams#crestBoost} a ring gains {@code distance} blocks from the player:
     * {@code smoothstep((crestRadius - d) / crestRadius)}, all of it at the player, half halfway out, none from
     * {@link HollowParams#crestRadius} on. The crest swells as it runs under and past the player and sinks back
     * behind, so the wave reads where the player stands, on the floor, the walls and the ceiling alike.
     */
    public static double crestShare(HollowParams p, double distance) {
        return AwakeningShape.smoothstep((p.crestRadius() - distance) / p.crestRadius());
    }

    /**
     * Whether the train of a ring {@code ageSeconds} old (from its tail {@code front - waves·wavelength} out to its
     * front {@code speed·age}) can lie within {@code reach} of a player {@code distance} blocks from its origin, that
     * is, whether it can raise anything drawn around the player now: a running ring whose train overlaps
     * {@code [distance - reach, distance + reach]}. Rings that have not come near yet or have passed are left out of
     * a frame.
     */
    public static boolean ringNear(RippleParams ring, double ageSeconds, double distance, double reach) {
        if (!Ripple.active(ring, ageSeconds)) {
            return false;
        }
        double front = ring.speed() * ageSeconds;
        double tail = front - ring.waves() * ring.wavelength();
        return front > distance - reach && tail < distance + reach;
    }

    /**
     * Share of the heave a point has {@code timeSeconds} into the hollow, 0..1:
     * {@code 0.5 - 0.5·cos(2π·t/period + φ)} with {@code φ = PHASE_SPREAD·π·(1 + noise(p/swellScale))}, so that
     * patches about {@link HollowParams#swellScale} across rise and fall out of step with each other, some up while
     * others are down: the ground walks instead of lifting as one plate. Fixed in place; 0 for a NaN time.
     */
    public static double heave(HollowParams p, double timeSeconds, double x, double y, double z) {
        if (Double.isNaN(timeSeconds)) {
            return 0;
        }
        double s = p.swellScale();
        double phase = PHASE_SPREAD * Math.PI * (1 + PerlinNoise.noise(x / s, y / s, z / s));
        return 0.5 - 0.5 * Math.cos(2 * Math.PI * timeSeconds / p.breathPeriod() + phase);
    }

    /**
     * Share of an effect drawn around the player at {@code distance} from them: {@code smoothstep((radius - d)/fade)},
     * 1 up to {@code radius - fade}, 0 from {@code radius} on.
     */
    public static double near(double radius, double fade, double distance) {
        return AwakeningShape.smoothstep((radius - distance) / fade);
    }

    /**
     * Share of its motion the ground keeps {@code secondsSinceEnd} after the player left the hollow (or the client
     * stopped hearing of it): {@code smoothstep(1 - t/releaseSeconds)}, 1 before.
     */
    public static double release(HollowParams p, double secondsSinceEnd) {
        if (!(secondsSinceEnd > 0)) {
            return 1;
        }
        return AwakeningShape.smoothstep(1 - secondsSinceEnd / p.releaseSeconds());
    }

    private static double share(double x) {
        return x > 0 ? Math.min(x, 1) : 0;
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }
}
