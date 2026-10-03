package tremor.client.awakening;

import net.minecraft.SharedConstants;
import tremor.core.shape.AwakeningField;
import tremor.core.shape.AwakeningParams;
import tremor.core.shape.AwakeningShape;
import tremor.core.shape.Ripple;
import tremor.core.shape.RippleParams;
import tremor.network.TremorAwakeningPayload;
import tremor.network.TremorAwakeningPayload.Phase;

import java.util.ArrayList;
import java.util.List;

/**
 * How the ground of the current Awakening moves this frame (SPEC 9, phase 1 in the real world), from what
 * {@link ClientAwakening} knows, for the deformation renderer:
 * <ul>
 *     <li>BUILDUP: the zone breathes ({@link AwakeningShape#breath}), each breath higher than the last as the build-up
 *     runs ({@link AwakeningShape#breathAmplitude}); every step sends a ring ({@link AwakeningShape#stepRipple});</li>
 *     <li>SWALLOWING: the breathing stays at its full height and the rings go on, and the hill rises at the focus
 *     ({@link AwakeningShape#hillPeak});</li>
 *     <li>HOLLOW: nothing; for everyone left behind the ground is smooth at once, as if nobody had been there;</li>
 *     <li>EMERGING (after a victory in the hollow, SPEC 9 "Победа"): the hill rises at the focus once more, quickly,
 *     with a ring bursting out from under it, and settles slowly over the phase ({@link AwakeningShape#emergeHill}),
 *     shaking dust off its flanks: the player comes out of it;</li>
 *     <li>over in the real world (escape, victory, or cancelled): the ground of the last frame settles over
 *     {@link AwakeningParams#releaseSeconds} ({@link AwakeningShape#release}) as the entity goes deep, instead of
 *     snapping flat.</li>
 * </ul>
 * The breathing keeps its rhythm across the phases: its clock runs from the start of the first phase this client saw
 * of the event (the build-up, unless it came near or joined later). Everything runs on the level's game time, like
 * the server's phases. Render thread only.
 */
public final class AwakeningGround {
    private static final AwakeningParams PARAMS = AwakeningParams.defaults();
    private static final double TICKS_PER_SECOND = SharedConstants.TICKS_PER_SECOND;
    /** Strength of the ring that bursts out from under the emerging hill: a heavy landing's. */
    static final double EMERGE_RING_STRENGTH = 3;

    /** Event whose breathing clock runs, and the game time it started at. */
    private static int clockEvent;
    private static boolean clocked;
    private static double breathStart;
    /** The Awakening as of the last frame it was known, kept to let its ground settle; null if none. */
    private static TremorAwakeningPayload last;
    private static List<ClientAwakening.StepRipple> lastRipples = List.of();
    /** Game time the settling started at; NaN while the Awakening is known. */
    private static double releaseStart = Double.NaN;

    private AwakeningGround() {
    }

    /**
     * The ground of the current Awakening at {@code gameTime} (the level's game time plus the partial tick), or null
     * if it does not move.
     */
    public static AwakeningField frame(double gameTime) {
        TremorAwakeningPayload state = ClientAwakening.state();
        double release = 1;
        List<ClientAwakening.StepRipple> ripples;
        if (state != null) {
            if (!clocked || clockEvent != state.id()) {
                clocked = true;
                clockEvent = state.id();
                breathStart = state.phaseStart();
            }
            if (state.phase() != Phase.BUILDUP && state.phase() != Phase.SWALLOWING
                    && state.phase() != Phase.EMERGING) {
                last = null; // in the hollow: smooth at once, nothing settles
                lastRipples = List.of();
                return null;
            }
            last = state;
            releaseStart = Double.NaN;
            // Steps ring out only in the zone's own phases; the hill a victor comes out of has a ring of its own.
            ripples = state.phase() == Phase.EMERGING ? List.of()
                    : ClientAwakening.ripples((long) Math.floor(gameTime));
            lastRipples = ripples;
        } else {
            if (last == null) {
                return null;
            }
            if (Double.isNaN(releaseStart)) {
                releaseStart = gameTime;
            }
            release = AwakeningShape.release(PARAMS, (gameTime - releaseStart) / TICKS_PER_SECOND);
            if (release <= 0) {
                reset();
                return null;
            }
            state = last;
            ripples = lastRipples;
        }
        return field(state, ripples, gameTime, release);
    }

    /** Forgets everything, e.g. when the level changes. */
    public static void reset() {
        clocked = false;
        last = null;
        lastRipples = List.of();
        releaseStart = Double.NaN;
    }

    private static AwakeningField field(TremorAwakeningPayload state, List<ClientAwakening.StepRipple> ripples,
                                        double gameTime, double release) {
        if (state.phase() == Phase.EMERGING) {
            return emerging(state, gameTime, release);
        }
        boolean swallowing = state.phase() == Phase.SWALLOWING;
        double progress = phaseProgress(state, gameTime);
        double amplitude = swallowing ? PARAMS.breathEnd() : AwakeningShape.breathAmplitude(PARAMS, progress);
        double breath = AwakeningShape.breath(amplitude, PARAMS.breathPeriod(),
                (gameTime - breathStart) / TICKS_PER_SECOND);
        double hill = swallowing ? AwakeningShape.hillPeak(PARAMS, progress) : 0;
        List<AwakeningField.Ring> rings = new ArrayList<>(ripples.size());
        for (ClientAwakening.StepRipple ripple : ripples) {
            RippleParams params = AwakeningShape.stepRipple(PARAMS, ripple.strength());
            double age = (gameTime - ripple.gameTime()) / TICKS_PER_SECOND;
            if (Ripple.active(params, age) && params.amplitude() > 0) {
                rings.add(new AwakeningField.Ring(ripple.position(), age,
                        params.withAmplitude(params.amplitude() * release)));
            }
        }
        return new AwakeningField(PARAMS, state.center(), state.radius(), breath * release,
                PARAMS.breathEnd() * release, state.focus(), hill * release, rings);
    }

    /**
     * The hill a victor comes out of, {@link AwakeningShape#emergeHill} of the phase's progress at the focus, with a
     * ring of strength {@link #EMERGE_RING_STRENGTH} bursting out from under it as it starts to rise. Only the ground
     * around the focus moves, so only that is scanned: a zone of radius 0 there, which the ring's reach widens
     * ({@link AwakeningField#reach}). No breathing. Flat for an open-ended phase.
     */
    private static AwakeningField emerging(TremorAwakeningPayload state, double gameTime, double release) {
        double progress = phaseProgress(state, gameTime);
        double seconds = state.phaseTicks() / TICKS_PER_SECOND;
        double hill = AwakeningShape.emergeHill(PARAMS, progress) * release;
        double speed = seconds > 0 ? AwakeningShape.emergeHillRate(PARAMS, progress) / seconds * release : 0;
        RippleParams burst = AwakeningShape.stepRipple(PARAMS, EMERGE_RING_STRENGTH);
        double age = (gameTime - state.phaseStart()) / TICKS_PER_SECOND;
        List<AwakeningField.Ring> rings = Ripple.active(burst, age)
                ? List.of(new AwakeningField.Ring(state.focus(), age, burst.withAmplitude(burst.amplitude() * release)))
                : List.of();
        return new AwakeningField(PARAMS, state.focus(), 0, 0, 0, state.focus(), hill, speed, rings);
    }

    /**
     * Share of the phase that has passed at {@code gameTime}, 0..1 (0 for an open-ended phase); the same as
     * {@link ClientAwakening#progress}, but also for the settling state, which {@link ClientAwakening} has dropped.
     */
    private static double phaseProgress(TremorAwakeningPayload state, double gameTime) {
        if (state.phaseTicks() <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(1, (gameTime - state.phaseStart()) / state.phaseTicks()));
    }
}
