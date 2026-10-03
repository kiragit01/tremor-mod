package tremor.hollow.level;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import tremor.Tremor;
import tremor.hearing.Vibration;
import tremor.hollow.HollowDimension;
import tremor.hollow.HollowEvent;
import tremor.hollow.HollowManager;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The levels inside the hollow (SPEC 9, phase 2; stage 4c), one per event ({@link EventLevel}), runtime only. The
 * hooks:
 * <ul>
 *   <li>{@link HollowManager}: {@link #prepare} once the copy and its light are done, until it says ready (the
 *   player is moved in after that); {@link #tick} every tick the player is inside, alive;</li>
 *   <li>{@link HollowManager#addEndListener}: {@link #onHollowEnded} drops the level and tells the client;</li>
 *   <li>{@link tremor.hearing.VibrationListener}: {@link #listens} and {@link #vibration}, the player's noise and the
 *   lures;</li>
 *   <li>{@link tremor.block.HeartNodeBlock}: {@link #nodeBroken};</li>
 *   <li>{@code /tremor hollow status}: {@link #describe}.</li>
 * </ul>
 * A level that fails (an exception) is logged and stands still, and its player is brought back to where the player
 * was swallowed, with no outcome ({@link HollowManager#leave}; an event still copying is called off), rather than left
 * in a copy without a node, a closing or a way out. Server thread only.
 */
public final class HollowLevels {
    /** The vibrations ({@link Vibration#event}) that can be lures (SPEC 7.4): a thrown item, a projectile landing. */
    private static final Set<String> LURES = Set.of("item_land", "projectile_land");
    private static final Map<HollowEvent, EventLevel> LEVELS = new IdentityHashMap<>();
    /** Id of the next level ({@code TremorHollowStatePayload.event}); never reused while the server runs. */
    private static int nextId = 1;

    private HollowLevels() {
    }

    /**
     * Prepares the level of {@code event}, a step per call (the copy and its light are done, the player is not in yet):
     * true once it is ready. If it fails, the event is called off (false; true only if that is refused, and the player
     * is brought back from inside then).
     */
    public static boolean prepare(ServerLevel hollow, HollowEvent event) {
        EventLevel level = LEVELS.computeIfAbsent(event, e -> new EventLevel(hollow, e, nextId++));
        try {
            return level.prepare();
        } catch (RuntimeException e) {
            Tremor.LOGGER.error("Hollow level: preparing {} failed, the event is called off", event, e);
            level.fail(e);
            ServerPlayer player = hollow.getServer().getPlayerList().getPlayer(event.player());
            return player == null || !bringBack(event, player);
        }
    }

    /** One tick of the level of {@code event}, whose player is inside the copy, alive. */
    public static void tick(HollowEvent event, ServerPlayer player) {
        EventLevel level = LEVELS.get(event);
        if (level == null) {
            return;
        }
        if (!level.failed()) {
            try {
                level.tick(player);
                return;
            } catch (RuntimeException e) {
                Tremor.LOGGER.error("Hollow level: {} failed, the player is brought back", event, e);
                level.fail(e);
            }
        }
        bringBack(event, player);
    }

    /**
     * The level of {@code event} failed: its player goes back to where the player was swallowed, with no outcome (an
     * event still copying is called off). False if that was refused.
     */
    private static boolean bringBack(HollowEvent event, ServerPlayer player) {
        try {
            HollowManager.leave(player, null);
            return true;
        } catch (HollowManager.Refusal refusal) {
            Tremor.LOGGER.warn("Hollow level: could not bring {} back: {}", event, refusal.getMessage());
            return false;
        }
    }

    /** The player part of the event is over: the level goes, and the player's client drops its state. */
    public static void onHollowEnded(HollowEvent event, HollowEvent.End why) {
        EventLevel level = LEVELS.remove(event);
        if (level != null) {
            level.ended();
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        LEVELS.clear();
    }

    /** Whether a level in {@code level} takes vibrations: it is the hollow, and some level there plays. */
    public static boolean listens(ServerLevel level) {
        if (LEVELS.isEmpty() || !HollowDimension.is(level)) {
            return false;
        }
        for (EventLevel running : LEVELS.values()) {
            if (running.running()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A vibration in the hollow, as the hearing evaluated it: from a player (steps, landings, blocks broken and
     * placed...), it is noise of that player's level, as loud as it got into the ground (loudness times footing); an
     * item or a projectile landing ({@link #LURES}) may be a lure of the level it landed in. Anything else (an
     * explosion, a falling block) is neither.
     *
     * @param player the player behind it, or null
     */
    public static void vibration(ServerLevel level, Vibration vibration, Player player) {
        if (!listens(level)) {
            return;
        }
        if (player instanceof ServerPlayer source) {
            EventLevel own = LEVELS.get(HollowManager.event(source));
            if (own != null) {
                own.noise(vibration.loudness() * vibration.footing());
            }
        } else if (player == null && LURES.contains(vibration.event())) {
            tremor.core.math.Vec3 at = vibration.source();
            EventLevel there = LEVELS.get(HollowManager.eventAt(level, BlockPos.containing(at.x(), at.y(), at.z())));
            if (there != null) {
                there.lure(at);
            }
        }
    }

    /** {@code player} broke a {@code tremor:heart_node} at {@code pos} of {@code level}. */
    public static void nodeBroken(ServerLevel level, BlockPos pos, ServerPlayer player) {
        if (!HollowDimension.is(level)) {
            return;
        }
        for (EventLevel owner : List.copyOf(LEVELS.values())) {
            if (owner.nodeBroken(player, pos)) {
                return;
            }
        }
    }

    /** The node of the level of {@code event} ({@code /tremor hollow tonode}), or null if there is none. */
    public static BlockPos node(HollowEvent event) {
        EventLevel level = LEVELS.get(event);
        return level == null ? null : level.node();
    }

    /** The level of {@code event} for {@code /tremor hollow status}, or null if it has none. */
    public static String describe(HollowEvent event) {
        EventLevel level = LEVELS.get(event);
        return level == null ? null : level.describe();
    }
}
