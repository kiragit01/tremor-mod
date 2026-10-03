package tremor.hollow;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * No running in the hollow (SPEC 9 phase 2: "в изнанке чёрный туман — видно ~5 блоков, бег невозможен"), as under
 * blindness: a player there can neither start nor keep sprinting, by key, double tap or toggle. Creative and spectator
 * players are not held to it ({@link #locks}).
 * <p>
 * The client stops its own player ({@code tremor.client.hollow.HollowStride}). The server stops every player in the
 * hollow at the end of each of their ticks as well ({@link #onPlayerTick}), so that what the server decides on the
 * sprint flag (the sprint's exhaustion and knockback, how other clients see the player) follows the lock even for a
 * client that sends a sprint anyway; the flag goes back to that client with the player's entity data, which stops a
 * client that has no lock of its own. Movement itself is the client's, as everywhere in vanilla: the server does not
 * slow a client down that ignores the flag.
 */
public final class SprintLock {
    private SprintLock() {
    }

    /** Stops a sprinting player in the hollow, after the player's tick. */
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.isSprinting() && locks(player)) {
            player.setSprinting(false);
        }
    }

    /** Whether {@code player} may not sprint: in the hollow, and neither in creative nor a spectator (either side). */
    public static boolean locks(Player player) {
        return HollowDimension.is(player.level()) && !player.isCreative() && !player.isSpectator();
    }
}
