package tremor.client.hollow;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import tremor.network.TremorHollowStatePayload;

/**
 * Client view of the hollow the local player is in (SPEC 9, phase 2): the last {@link TremorHollowStatePayload}.
 * Read by the effects inside the hollow (the node's pulse, the moving walls, the heartbeat, the pull of the ground).
 * Main thread only; dropped on logout, when the client level changes and when the server says the player left.
 */
public final class ClientHollow {
    private static ClientLevel owner;
    private static TremorHollowStatePayload state;

    private ClientHollow() {
    }

    public static void accept(TremorHollowStatePayload payload) {
        adopt(Minecraft.getInstance().level);
        if (!payload.active()) {
            if (state != null && state.event() == payload.event()) {
                state = null;
            }
            return;
        }
        state = payload;
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        owner = null;
        state = null;
    }

    /** The hollow the local player is in, or null. */
    public static TremorHollowStatePayload state() {
        adopt(Minecraft.getInstance().level);
        return state;
    }

    private static void adopt(ClientLevel level) {
        if (level != owner) {
            owner = level;
            state = null;
        }
    }
}
