package tremor.client.hollow;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import tremor.config.TremorConfig;
import tremor.core.shape.HollowShape;
import tremor.hollow.HollowDimension;
import tremor.network.TremorHollowDrawnPayload;
import tremor.network.TremorHollowStatePayload;

/**
 * Client view of the hollow the local player is in (SPEC 9, phase 2): the last {@link TremorHollowStatePayload}.
 * Read by the effects inside the hollow (the node's pulse, the moving walls, the heartbeat, the pull of the ground).
 * Main thread only; dropped on logout, when the client level changes and when the server says the player left.
 */
public final class ClientHollow {
    private static ClientLevel owner;
    private static TremorHollowStatePayload state;
    /** Whether a hollow has been heard of in this level: the server sends the first state once its level plays. */
    private static boolean heardOf;
    /** Event of {@link #widest}, kept after the state is dropped (its ground settles on). */
    private static int widestEvent;
    /** Widest closing radius sent for {@link #widestEvent}: where its closing started; NaN if none. */
    private static double widest = Double.NaN;
    /** Whether the server was told that this level of the hollow is drawn ({@link #onClientTick}). */
    private static boolean toldDrawn;

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
        if (Double.isNaN(widest) || widestEvent != payload.event()) {
            widestEvent = payload.event();
            widest = payload.closeRadius();
        } else {
            widest = Math.max(widest, payload.closeRadius());
        }
        heardOf = true;
        state = payload;
    }

    /**
     * Tells the server, once per arrival in the hollow, that the hollow is drawn around the player: the loading screen
     * of the dimension change is gone and the sections round the player are meshed. The level's clock starts then.
     */
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        adopt(mc.level);
        if (toldDrawn || mc.level == null || mc.player == null || !HollowDimension.is(mc.level)
                || mc.screen instanceof ReceivingLevelScreen) {
            return;
        }
        BlockPos feet = mc.player.blockPosition();
        for (int dy = -16; dy <= 16; dy += 16) {
            for (int dz = -16; dz <= 16; dz += 16) {
                for (int dx = -16; dx <= 16; dx += 16) {
                    BlockPos at = feet.offset(dx, dy, dz);
                    if (!mc.level.isOutsideBuildHeight(at) && !mc.levelRenderer.isSectionCompiled(at)) {
                        return;
                    }
                }
            }
        }
        toldDrawn = true;
        PacketDistributor.sendToServer(TremorHollowDrawnPayload.INSTANCE);
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        owner = null;
        forget();
    }

    /** The hollow the local player is in, or null. */
    public static TremorHollowStatePayload state() {
        adopt(Minecraft.getInstance().level);
        return state;
    }

    /**
     * Whether the client has had a state of a hollow since it came into the current level. False while the player is
     * being moved in: the server keeps the screen dark until the client has the terrain, and sends the first state
     * only once the level of the hollow plays.
     */
    public static boolean heardOf() {
        adopt(Minecraft.getInstance().level);
        return heardOf;
    }

    /**
     * How far {@code state}'s hollow has closed, 0..1 ({@link HollowShape#closeness}): from where its closing started
     * down to where it stops. The closing starts at the edge, a block inside the copy's radius (as the server starts
     * it), or at the widest closing radius the client was sent for the hollow if that is wider; it stops at
     * {@code hollow.level.minRadius} of the COMMON config. That is the server's: the client reads its own copy, the same
     * in single player and wherever the config is shared; a server set to another minimum makes the effects reach their
     * closed values a little early or not quite.
     */
    public static double closeness(TremorHollowStatePayload state) {
        double start = state.boxRadius() - 1;
        if (state.event() == widestEvent && widest > start) {
            start = widest;
        }
        return HollowShape.closeness(start, TremorConfig.COMMON.hollow.level.minRadius.get(), state.closeRadius());
    }

    private static void adopt(ClientLevel level) {
        if (level != owner) {
            owner = level;
            forget();
        }
    }

    private static void forget() {
        state = null;
        heardOf = false;
        toldDrawn = false;
        widest = Double.NaN;
    }
}
