package tremor.client.hollow;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraftforge.event.TickEvent;
import tremor.network.TremorHollowStatePayload;

/**
 * How deep the soft ground of the hollow (or the quicksand of an ambush, {@link #quicksand}) has the local player
 * (SPEC 9 phase 2: "Затягивание"), as the effects show it:
 * the {@link TremorHollowStatePayload#sink} the server sends a few times a second, eased once per client tick
 * ({@link SinkCurve#approach}) and interpolated between ticks, so that the darkness ({@link SinkOverlay}) and the
 * muffling and the pull's sound ({@code tremor.client.sound.HollowSounds}) neither step nor jump. It eases back to 0
 * when the player is out of the hollow or dead, and is 0 at once in a new client level. Main thread only.
 */
public final class HollowSink {
    private static final double TICK_SECONDS = 1.0 / 20;

    private static ClientLevel owner;
    private static double previous;
    private static double current;

    private HollowSink() {
    }

    /** Moves the shown sink toward the server's (toward 0 out of the hollow), unless the game is paused. */
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != owner) {
            owner = mc.level;
            previous = 0;
            current = 0;
        }
        if (mc.isPaused()) {
            return;
        }
        LocalPlayer player = mc.player;
        TremorHollowStatePayload state = mc.level == null || player == null || player.isDeadOrDying() ? null
                : ClientHollow.state();
        previous = current;
        double sink = state != null ? state.sink() : mc.level == null || player == null || player.isDeadOrDying() ? 0
                : quicksand(player);
        current = SinkCurve.approach(current, sink, TICK_SECONDS);
    }

    /**
     * How deep the quicksand of an ambush has the local player ({@code tremor.awakening.Quicksand}): while the ambush
     * they are the target of pulls them in, from the ground's top where they stepped in (its focus) down to the feet,
     * against the eye height of a standing player; 0 otherwise.
     */
    private static double quicksand(LocalPlayer player) {
        tremor.network.TremorAwakeningPayload ambush = tremor.client.awakening.ClientAwakening.state();
        if (ambush == null || !ambush.ambush() || ambush.phase() != tremor.network.TremorAwakeningPayload.Phase.SWALLOWING
                || !player.getUUID().equals(ambush.target())) {
            return 0;
        }
        return tremor.hollow.level.SinkTracker.sink(ambush.focus().y(), player.getY(),
                player.getEyeHeight(net.minecraft.world.entity.Pose.STANDING));
    }

    /** The shown sink as of the last client tick, 0..1. */
    public static double now() {
        return current;
    }

    /** The shown sink between the last two client ticks, 0..1. */
    public static double at(float partialTick) {
        return Mth.lerp(partialTick, previous, current);
    }
}
