package tremor.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a block of quicksand ({@link QuicksandBlock}) was: kept with it and sent to the clients, which draw it in its
 * place; the quicksand puts it back ({@code tremor.awakening.Quicksand}, which also keeps it in the level's saved data).
 */
public final class QuicksandBlockEntity extends BlockEntity {
    private BlockState original = Blocks.AIR.defaultBlockState();

    public QuicksandBlockEntity(BlockPos pos, BlockState state) {
        super(TremorBlocks.QUICKSAND_ENTITY.get(), pos, state);
    }

    /** The block it was; air until the quicksand sets it. */
    public BlockState original() {
        return original;
    }

    /** Sets the block it was, and sends it to the clients near. */
    public void setOriginal(BlockState original) {
        this.original = original;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        original = NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("original"));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("original", NbtUtils.writeBlockState(original));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
