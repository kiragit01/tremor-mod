package tremor.client.dev;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the player did on the ticks of one {@code hold} step, and the warnings that follow from it. Plain Java (no game
 * classes) so that it can be unit tested: {@link AutoTestRunner} feeds it once per held tick, after the player has
 * moved, and reports {@link #summary()} and {@link #warnings()} when the hold ends.
 * <p>
 * A hold is the harness's way to make footsteps, and vanilla only makes them for a walking player: none while
 * {@code abilities.flying} is set ({@code Player.getMovementEmission}), spectators always fly, and a player off the
 * ground has air under its feet ({@code Entity.move} needs a solid block at {@code getOnPos()}). Every held tick with a
 * player falls into exactly one of: spectator, flying (not spectator), on ground, airborne (walking mode, off the
 * ground).
 */
final class HoldTally {
    /** Keys that move the player; a hold without any of them (only sneak and/or sprint) is not meant to walk. */
    private static final Set<Script.HoldKey> MOVING = EnumSet.of(Script.HoldKey.FORWARD, Script.HoldKey.BACK,
            Script.HoldKey.LEFT, Script.HoldKey.RIGHT, Script.HoldKey.JUMP);

    /** The player's state on one held tick, after it has moved. */
    record TickState(boolean sprinting, boolean sneaking, boolean onGround, boolean flying, boolean spectator) {
    }

    private final Set<Script.HoldKey> keys;
    private int ticks;
    private int noPlayer;
    private int screenOpen;
    private int sprinting;
    private int sneaking;
    private int spectator;
    private int flying;
    private int onGround;
    private int airborne;
    private int sprintStops;

    HoldTally(Set<Script.HoldKey> keys) {
        this.keys = Set.copyOf(keys);
    }

    /** Counts one held tick; {@code player} is {@code null} when there is no player. */
    void sample(TickState player, boolean screen) {
        ticks++;
        screenOpen += screen ? 1 : 0;
        if (player == null) {
            noPlayer++;
            return;
        }
        sprinting += player.sprinting() ? 1 : 0;
        sneaking += player.sneaking() ? 1 : 0;
        if (player.spectator()) {
            spectator++;
        } else if (player.flying()) {
            flying++;
        } else if (player.onGround()) {
            onGround++;
        } else {
            airborne++;
        }
    }

    /** The harness found the player sprinting before a tick of a hold without {@code sprint} and stopped it. */
    void sprintStopped() {
        sprintStops++;
    }

    int ticks() {
        return ticks;
    }

    /** The tick counts, e.g. {@code ticks sprinting 0, sneaking 0, on ground 60, ... of 60}. */
    String summary() {
        return String.format(Locale.ROOT, "ticks sprinting %d, sneaking %d, on ground %d, airborne %d, flying %d, "
                        + "spectator %d, screen open (keys inactive) %d%s of %d", sprinting, sneaking, onGround,
                airborne, flying, spectator, screenOpen, noPlayer > 0 ? ", no player " + noPlayer : "", ticks);
    }

    /**
     * What went against the hold's intent, worst first, without a prefix; empty if nothing did.
     * <ul>
     * <li>spectator ticks, for any hold: spectators never make steps;</li>
     * <li>sprinting (or a stopped sprint) in a hold without {@code sprint};</li>
     * <li>for a hold with a moving key: flying ticks; no tick on the ground at all; more than half of the walking
     * ticks off the ground when the hold does not jump.</li>
     * </ul>
     */
    List<String> warnings() {
        List<String> out = new ArrayList<>();
        if (spectator > 0) {
            out.add(String.format(Locale.ROOT, "the player was a spectator on %d of %d ticks; spectators never make "
                    + "steps, so this hold made none on those ticks (switch to survival or creative first)",
                    spectator, ticks));
        }
        if (!keys.contains(Script.HoldKey.SPRINT) && (sprinting > 0 || sprintStops > 0)) {
            out.add(String.format(Locale.ROOT, "the player sprinted on %d of %d ticks although the hold has no "
                    + "sprint key; the harness found it sprinting and stopped it before %d ticks", sprinting, ticks,
                    sprintStops));
        }
        if (keys.stream().noneMatch(MOVING::contains)) {
            return out;
        }
        if (flying > 0) {
            out.add(String.format(Locale.ROOT, "the player was flying on %d of %d ticks and makes no steps while "
                    + "flying", flying, ticks));
        }
        int walking = onGround + airborne;
        if (walking > 0 && onGround == 0) {
            out.add(String.format(Locale.ROOT, "the player never touched the ground on its %d walking ticks and made "
                    + "no steps", walking));
        } else if (!keys.contains(Script.HoldKey.JUMP) && airborne * 2 > walking) {
            out.add(String.format(Locale.ROOT, "the player was off the ground on %d of its %d walking ticks "
                    + "(falling?) and made few or no steps", airborne, walking));
        }
        return out;
    }
}
