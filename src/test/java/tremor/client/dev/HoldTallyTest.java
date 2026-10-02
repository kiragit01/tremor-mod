package tremor.client.dev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tremor.client.dev.HoldTally.TickState;
import tremor.client.dev.Script.HoldKey;

class HoldTallyTest {
    private static final TickState WALKING = new TickState(false, false, true, false, false);
    private static final TickState SPRINTING = new TickState(true, false, true, false, false);
    private static final TickState AIRBORNE = new TickState(false, false, false, false, false);
    private static final TickState FLYING = new TickState(false, false, false, true, false);
    /** Vanilla spectators always have {@code abilities.flying} set. */
    private static final TickState SPECTATOR = new TickState(false, false, false, true, true);

    private static HoldTally tally(Set<HoldKey> keys, TickState state, int ticks) {
        HoldTally tally = new HoldTally(keys);
        for (int i = 0; i < ticks; i++) {
            tally.sample(state, false);
        }
        return tally;
    }

    private static void assertSingleWarning(HoldTally tally, String fragment) {
        List<String> warnings = tally.warnings();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains(fragment), warnings.get(0));
    }

    @Test
    void walkingOnTheGroundIsQuiet() {
        HoldTally tally = tally(Set.of(HoldKey.FORWARD), WALKING, 60);
        assertEquals(60, tally.ticks());
        assertEquals(List.of(), tally.warnings());
        assertEquals("ticks sprinting 0, sneaking 0, on ground 60, airborne 0, flying 0, spectator 0, "
                + "screen open (keys inactive) 0 of 60", tally.summary());
    }

    @Test
    void spectatorAlwaysWarnsAndCountsSeparatelyFromFlying() {
        HoldTally tally = tally(Set.of(HoldKey.FORWARD), SPECTATOR, 20);
        assertSingleWarning(tally, "spectator on 20 of 20 ticks");
        assertTrue(tally.summary().contains("flying 0, spectator 20"), tally.summary());
        // Also for a hold that does not move: spectators never make steps.
        assertSingleWarning(tally(Set.of(HoldKey.SNEAK), SPECTATOR, 5), "spectator on 5 of 5 ticks");
    }

    @Test
    void flyingWarnsForMovingHoldsOnly() {
        assertSingleWarning(tally(Set.of(HoldKey.FORWARD), FLYING, 30), "flying on 30 of 30 ticks");
        assertEquals(List.of(), tally(Set.of(HoldKey.SNEAK), FLYING, 30).warnings());
    }

    @Test
    void flyingFoundLateInTheHoldStillWarns() {
        // Checked at the end from what happened, not once at the start: on the ground first, then flying.
        HoldTally tally = tally(Set.of(HoldKey.FORWARD), WALKING, 50);
        for (int i = 0; i < 10; i++) {
            tally.sample(FLYING, false);
        }
        assertSingleWarning(tally, "flying on 10 of 60 ticks");
    }

    @Test
    void neverOnTheGroundWarnsEvenWithJump() {
        assertSingleWarning(tally(Set.of(HoldKey.FORWARD), AIRBORNE, 40), "never touched the ground on its 40");
        assertSingleWarning(tally(Set.of(HoldKey.FORWARD, HoldKey.JUMP), AIRBORNE, 40), "never touched the ground");
    }

    @Test
    void mostlyAirborneWarnsUnlessTheHoldJumps() {
        HoldTally falling = tally(Set.of(HoldKey.FORWARD), WALKING, 10);
        for (int i = 0; i < 11; i++) {
            falling.sample(AIRBORNE, false);
        }
        assertSingleWarning(falling, "off the ground on 11 of its 21 walking ticks");

        HoldTally jumping = tally(Set.of(HoldKey.FORWARD, HoldKey.JUMP), WALKING, 10);
        for (int i = 0; i < 30; i++) {
            jumping.sample(AIRBORNE, false);
        }
        assertEquals(List.of(), jumping.warnings());

        // A short drop (half or less of the walking ticks) is normal.
        HoldTally step = tally(Set.of(HoldKey.FORWARD), WALKING, 10);
        for (int i = 0; i < 10; i++) {
            step.sample(AIRBORNE, false);
        }
        assertEquals(List.of(), step.warnings());
    }

    @Test
    void unaskedSprintWarns() {
        HoldTally tally = tally(Set.of(HoldKey.FORWARD), WALKING, 19);
        tally.sample(SPRINTING, false);
        tally.sprintStopped();
        assertSingleWarning(tally, "sprinted on 1 of 20 ticks although the hold has no sprint key; "
                + "the harness found it sprinting and stopped it before 1 ticks");

        HoldTally stoppedOnly = tally(Set.of(HoldKey.FORWARD), WALKING, 5);
        stoppedOnly.sprintStopped();
        assertSingleWarning(stoppedOnly, "sprinted on 0 of 5 ticks");

        assertEquals(List.of(), tally(Set.of(HoldKey.FORWARD, HoldKey.SPRINT), SPRINTING, 20).warnings());
    }

    @Test
    void screenAndMissingPlayerAreCounted() {
        HoldTally tally = new HoldTally(Set.of(HoldKey.FORWARD));
        tally.sample(WALKING, true);
        tally.sample(null, false);
        assertEquals(2, tally.ticks());
        assertEquals("ticks sprinting 0, sneaking 0, on ground 1, airborne 0, flying 0, spectator 0, "
                + "screen open (keys inactive) 1, no player 1 of 2", tally.summary());
        assertEquals(List.of(), tally.warnings());
    }
}
