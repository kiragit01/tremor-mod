package tremor.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BlackoutFadeTest {
    private static final double EPS = 1e-9;
    private static final long TICK = Math.round(1e9 / BlackoutFade.TICKS_PER_SECOND);

    /** A fade driven frame by frame at a fixed frame rate, from time 0. */
    private static final class Frames {
        final BlackoutFade fade = new BlackoutFade();
        final long frame;
        long now;
        /** Updates in which the failsafe lifted the blackout. */
        int lifted;

        Frames(double fps) {
            frame = Math.round(1e9 / fps);
            fade.update(0, false, false);
        }

        /** Runs frames for {@code ticks} ticks; returns the largest change of opacity from one frame to the next. */
        double run(double ticks, boolean paused, boolean loading) {
            long end = now + Math.round(ticks * TICK);
            double previous = fade.opacity();
            double maxStep = 0;
            while (now < end) {
                now = Math.min(end, now + frame);
                if (fade.update(now, paused, loading)) {
                    lifted++;
                }
                maxStep = Math.max(maxStep, Math.abs(fade.opacity() - previous));
                previous = fade.opacity();
            }
            return maxStep;
        }

        double run(double ticks) {
            return run(ticks, false, false);
        }

        /** A payload arriving now, between frames. */
        void accept(boolean dark, int fadeTicks) {
            fade.update(now, false, false);
            fade.set(dark, fadeTicks);
        }
    }

    @Test
    void startsClear() {
        assertEquals(0, new BlackoutFade().opacity(), EPS);
    }

    @Test
    void fadesToBlackOverTheGivenTicksAndStays() {
        Frames f = new Frames(60);
        f.accept(true, 20);
        f.run(10);
        assertEquals(0.5, f.fade.opacity(), EPS);
        f.run(9.9);
        assertTrue(f.fade.opacity() < 1);
        f.run(0.1);
        assertEquals(1, f.fade.opacity(), EPS);
        f.run(200);
        assertEquals(1, f.fade.opacity(), EPS);
    }

    @Test
    void fadesBackOverTheGivenTicks() {
        Frames f = new Frames(60);
        f.accept(true, 0);
        f.accept(false, 40);
        f.run(20);
        assertEquals(0.5, f.fade.opacity(), EPS);
        f.run(20);
        assertEquals(0, f.fade.opacity(), EPS);
    }

    @Test
    void zeroTicksChangesAtOnce() {
        Frames f = new Frames(60);
        f.accept(true, 0);
        assertEquals(1, f.fade.opacity(), EPS);
        f.accept(false, 0);
        assertEquals(0, f.fade.opacity(), EPS);
    }

    @Test
    void easesInAndOutAndMovesSmoothlyEveryFrame() {
        Frames f = new Frames(60);
        f.accept(true, 20);
        double firstTick = f.run(1);
        double maxStep = f.run(19);
        // Smoothstep: slow at the start, at most 1.5x the linear rate in the middle (1/20 per tick, 3 frames a tick).
        assertEquals(1, f.fade.opacity(), EPS);
        assertTrue(firstTick < 0.01, "start " + firstTick);
        assertTrue(maxStep <= 1.5 / 20 / 3 + EPS, "step " + maxStep);
    }

    @Test
    void sameTimelineAtAnyFrameRate() {
        for (double fps : new double[]{24, 60, 144, 333}) {
            Frames f = new Frames(fps);
            f.accept(true, 30);
            f.run(7.5);
            assertEquals(0.15625, f.fade.opacity(), 1e-6, "fps " + fps); // level 0.25
        }
    }

    @Test
    void reversingMidFadeContinuesFromTheCurrentOpacity() {
        Frames f = new Frames(60);
        f.accept(true, 20);
        f.run(5);
        double before = f.fade.opacity();
        f.accept(false, 10);
        assertEquals(before, f.fade.opacity(), EPS);
        // Level 0.25 back to 0 at 1/10 per tick: 2.5 ticks. Below level 0.25 the opacity changes at most 1.125x as
        // fast as the level, which drops 1/30 a frame.
        double maxStep = f.run(2.4);
        assertTrue(f.fade.opacity() > 0 && f.fade.opacity() < before);
        assertTrue(maxStep <= 1.125 / 30 + EPS, "step " + maxStep);
        f.run(0.1);
        assertEquals(0, f.fade.opacity(), EPS);
    }

    @Test
    void repeatingTheTargetMidFadeKeepsGoingAtTheNewRate() {
        Frames same = new Frames(60);
        Frames plain = new Frames(60);
        same.accept(true, 20);
        plain.accept(true, 20);
        same.run(8);
        plain.run(8);
        same.accept(true, 20);
        same.run(6);
        plain.run(6);
        assertEquals(plain.fade.opacity(), same.fade.opacity(), EPS);

        // A shorter fade covers the rest faster: level 0.4 to 1 at 1/10 per tick takes 6 ticks.
        Frames faster = new Frames(60);
        faster.accept(true, 20);
        faster.run(8);
        faster.accept(true, 10);
        faster.run(5.9);
        assertTrue(faster.fade.opacity() < 1);
        faster.run(0.1);
        assertEquals(1, faster.fade.opacity(), EPS);
    }

    @Test
    void timeBeforeAPayloadBelongsToThePreviousFade() {
        Frames f = new Frames(20);
        f.accept(true, 20);
        f.run(10); // frames on the tick: the last one at exactly 10 ticks, level 0.5
        f.now += TICK / 2; // the payload arrives half a tick after that frame
        f.accept(false, 20);
        f.run(0.5); // next frame
        // Up 0.025 under the old fade, then down 0.025 under the new one: level (and opacity) 0.5 again.
        assertEquals(0.5, f.fade.opacity(), EPS);
    }

    @Test
    void holdsWhilePaused() {
        Frames f = new Frames(60);
        f.accept(true, 20);
        f.run(5);
        double held = f.fade.opacity();
        assertEquals(0, f.run(100, true, false), EPS);
        assertEquals(held, f.fade.opacity(), EPS);
        double step = f.run(1);
        assertTrue(step < 0.03, "no jump on resume: " + step);
        f.run(14);
        assertEquals(1, f.fade.opacity(), EPS);
    }

    @Test
    void levelLoadingFinishesAFadeToBlackAtOnce() {
        Frames f = new Frames(60);
        f.accept(true, 40);
        f.run(3);
        f.run(0.1, false, true);
        assertEquals(1, f.fade.opacity(), EPS);
        f.run(100, false, true);
        assertEquals(1, f.fade.opacity(), EPS);
    }

    @Test
    void levelLoadingHoldsAFadeBack() {
        Frames f = new Frames(60);
        f.accept(true, 0);
        f.run(5, false, true);
        f.fade.update(f.now, false, true);
        f.fade.set(false, 20); // arrives while the new level loads
        f.run(60, false, true);
        assertEquals(1, f.fade.opacity(), EPS);
        f.run(10); // loaded: the fade back starts now
        assertEquals(0.5, f.fade.opacity(), EPS);
        f.run(10);
        assertEquals(0, f.fade.opacity(), EPS);
    }

    @Test
    void clearIsImmediate() {
        Frames f = new Frames(60);
        f.accept(true, 20);
        f.run(30);
        f.fade.clear();
        assertEquals(0, f.fade.opacity(), EPS);
        f.run(30);
        assertEquals(0, f.fade.opacity(), EPS);
    }

    @Test
    void firstUpdateDoesNotAdvance() {
        BlackoutFade fade = new BlackoutFade();
        fade.set(true, 20);
        fade.update(123_456_789_000L, false, false);
        assertEquals(0, fade.opacity(), EPS);
        fade.update(123_456_789_000L + 10 * TICK, false, false);
        assertEquals(0.5, fade.opacity(), EPS);
    }

    @Test
    void failsafeLiftsABlackoutTheServerNeverLifts() {
        Frames f = new Frames(60);
        f.accept(true, 20);
        f.run(BlackoutFade.FAILSAFE_TICKS - 0.1);
        assertEquals(1, f.fade.opacity(), EPS);
        assertEquals(0, f.lifted);
        f.run(0.1);
        assertEquals(1, f.lifted);
        f.run(BlackoutFade.FAILSAFE_FADE_TICKS / 2.0);
        assertEquals(0.5, f.fade.opacity(), EPS);
        f.run(BlackoutFade.FAILSAFE_FADE_TICKS / 2.0);
        assertEquals(0, f.fade.opacity(), EPS);
        f.run(BlackoutFade.FAILSAFE_TICKS * 2);
        assertEquals(1, f.lifted);
    }

    @Test
    void everyPayloadStartsTheFailsafeAgain() {
        Frames f = new Frames(60);
        f.accept(true, 20);
        f.run(BlackoutFade.FAILSAFE_TICKS - 10);
        f.accept(true, 20); // the server is still busy and says so
        f.run(BlackoutFade.FAILSAFE_TICKS - 0.1);
        assertEquals(1, f.fade.opacity(), EPS);
        assertEquals(0, f.lifted);
        f.run(0.1);
        assertEquals(1, f.lifted);

        // A fade back and a new blackout start it again too.
        Frames g = new Frames(60);
        g.accept(true, 0);
        g.run(BlackoutFade.FAILSAFE_TICKS - 10);
        g.accept(false, 0);
        g.accept(true, 0);
        g.run(BlackoutFade.FAILSAFE_TICKS - 0.1);
        assertEquals(1, g.fade.opacity(), EPS);
        assertEquals(0, g.lifted);
    }

    @Test
    void failsafeCountsTheRunningClockOnly() {
        Frames f = new Frames(60);
        f.accept(true, 20);
        f.run(BlackoutFade.FAILSAFE_TICKS - 1);
        f.run(BlackoutFade.FAILSAFE_TICKS, true, false);
        f.run(BlackoutFade.FAILSAFE_TICKS, false, true);
        assertEquals(1, f.fade.opacity(), EPS);
        assertEquals(0, f.lifted);
        f.run(1);
        assertEquals(1, f.lifted);
    }

    @Test
    void failsafeNeverFiresWhileClear() {
        Frames f = new Frames(60);
        f.run(BlackoutFade.FAILSAFE_TICKS * 2);
        f.accept(true, 0);
        f.accept(false, 20);
        f.run(BlackoutFade.FAILSAFE_TICKS * 2);
        f.accept(true, 0);
        f.fade.clear();
        f.run(BlackoutFade.FAILSAFE_TICKS * 2);
        assertEquals(0, f.lifted);
        assertEquals(0, f.fade.opacity(), EPS);
    }

    @Test
    void failsafeFadesBackFromTheMomentItWasDue() {
        // One long frame across the deadline: the part after it already counts for the fade back.
        BlackoutFade fade = new BlackoutFade();
        fade.update(0, false, false);
        fade.set(true, 0);
        assertTrue(fade.update((BlackoutFade.FAILSAFE_TICKS + BlackoutFade.FAILSAFE_FADE_TICKS / 2) * TICK, false,
                false));
        assertEquals(0.5, fade.opacity(), EPS);

        // A fade to black slower than the failsafe turns back from where it got to.
        Frames f = new Frames(60);
        f.accept(true, BlackoutFade.FAILSAFE_TICKS * 2);
        f.run(BlackoutFade.FAILSAFE_TICKS);
        assertEquals(1, f.lifted);
        f.run(BlackoutFade.FAILSAFE_FADE_TICKS / 4.0); // level 0.5 back down at 1/20 per tick: 0.25
        assertEquals(0.15625, f.fade.opacity(), 1e-6);
    }
}
