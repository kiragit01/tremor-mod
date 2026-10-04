package tremor.hollow;

import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import tremor.block.TremorBlocks;
import tremor.config.TremorConfig;

/**
 * The ground of the hollow resists the pickaxe (SPEC 9: the hollow is the entity's body, not a place to dig out of):
 * in the hollow dimension a survival or adventure player breaks every block {@code hollow.level.digFactor} times as
 * fast as elsewhere, the node excepted. No effect is put on the player: the blocks are only slow to give. Runs on both
 * sides (the client predicts the breaking from the same speed). Registered by {@link tremor.Tremor}.
 */
public final class HollowDigging {
    private HollowDigging() {
    }

    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        Player player = event.getEntity();
        if (player.isCreative() || player.isSpectator() || !HollowDimension.is(player.level())
                || event.getState().is(TremorBlocks.HEART_NODE.get())) {
            return;
        }
        event.setNewSpeed(event.getNewSpeed() * TremorConfig.COMMON.hollow.level.digFactor.get().floatValue());
    }
}
