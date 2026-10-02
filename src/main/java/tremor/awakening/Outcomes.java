package tremor.awakening;

import net.minecraft.server.level.ServerPlayer;
import tremor.core.math.Vec3;

/**
 * How an Awakening ends for a player in the hollow (SPEC 9 "Исходы"). Called by the level inside the hollow when the
 * player wins, gets out through the edge or is pulled in; each call ends the player's hollow event and their
 * Awakening with that outcome. Server thread only.
 */
public final class Outcomes {
    private Outcomes() {
    }

    /** Victory: the player destroyed the node (SPEC 9 "Победа"). */
    public static void victory(ServerPlayer player) {
        // Implemented in stage 4c.
    }

    /**
     * Escape through the edge of the hollow before it closed (SPEC 9 "Побег"): the player comes out at the matching
     * place of the real world.
     *
     * @param hollowPos where the player reached the edge, in the hollow's coordinates
     */
    public static void edgeEscape(ServerPlayer player, Vec3 hollowPos) {
        // Implemented in stage 4c.
    }

    /** Defeat: the soft ground pulled the player in completely (SPEC 9 "Поражение"). */
    public static void defeat(ServerPlayer player) {
        // Implemented in stage 4c.
    }
}
