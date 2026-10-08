package tremor.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import tremor.hollow.level.HollowLevels;

/**
 * {@code tremor:heart_node}, the node of the hollow (SPEC 9, "Узел"; "Победа — узел разрушен"). Its texture beats on
 * its own; the beat the player hears and the ripples come from the state of the hollow
 * ({@code TremorHollowStatePayload.beatTicks}). Breaking it is the victory: once the block is gone, the level of the
 * hollow it belongs to is told ({@link HollowLevels#nodeBroken}); a node anywhere else is just a block.
 */
public final class HeartNodeBlock extends Block {
    public HeartNodeBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /** Called by {@code ServerPlayerGameMode.destroyBlock} (creative players included) to remove the block. */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest,
                                       FluidState fluid) {
        boolean removed = super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
        if (removed && level instanceof ServerLevel server && player instanceof ServerPlayer breaker) {
            HollowLevels.nodeBroken(server, pos, breaker);
        }
        return removed;
    }
}
