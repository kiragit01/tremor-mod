package tremor.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import tremor.hollow.level.SinkTracker;

/**
 * What a block of quicksand ({@link QuicksandBlock}) was, and how much of it has given way ({@link #softness}, in 32nds
 * of a block: its collision): kept with it and sent to the clients, which draw the block it was in its place and let a
 * player sink as far; the quicksand puts the block back ({@code tremor.awakening.Quicksand}, which also keeps it in the
 * level's saved data).
 */
public final class QuicksandBlockEntity extends BlockEntity {
    private BlockState original = Blocks.AIR.defaultBlockState();
    private int softness;

    public QuicksandBlockEntity(BlockPos pos, BlockState state) {
        super(TremorBlocks.QUICKSAND_ENTITY.get(), pos, state);
    }

    /** The block it was; air until the quicksand sets it. */
    public BlockState original() {
        return original;
    }

    /** How much of it has given way, 0 (holds as a block) .. {@link SinkTracker#MAX_SOFTNESS} (holds nothing). */
    public int softness() {
        return softness;
    }

    /** Sets the block it was and how much of it has given way, and sends both to the clients near. */
    public void set(BlockState original, int softness) {
        int soft = Math.max(0, Math.min(SinkTracker.MAX_SOFTNESS, softness));
        if (original.equals(this.original) && soft == this.softness) {
            return;
        }
        this.original = original;
        this.softness = soft;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        original = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag.getCompound("original"));
        softness = Math.max(0, Math.min(SinkTracker.MAX_SOFTNESS, tag.getInt("softness")));
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.put("original", NbtUtils.writeBlockState(original));
        tag.putInt("softness", softness);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Drawn as a whole block, however far its collision has sunk (Forge's box would follow the collision). */
    @Override
    public AABB getRenderBoundingBox() {
        return new AABB(worldPosition);
    }
}
