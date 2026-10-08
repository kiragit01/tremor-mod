package tremor.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Clearable;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * What a {@link RubbleCacheBlock} holds: any number of stacks, saved with the chunk, never ticked and not a container
 * (no hopper takes them out, no screen shows them). The stacks spill when the block goes ({@link #spill}); a command
 * that replaces the block ({@code /setblock}, {@code /fill}, {@code /clone ... move}) empties it first, as it does a
 * chest ({@link Clearable}), so a cache it moves is not doubled. A copy of the block in the hollow gets a new, empty one
 * ({@code TerrainCopier}), so it holds nothing.
 */
public final class RubbleCacheBlockEntity extends BlockEntity implements Clearable {
    private static final String ITEMS = "Items";

    private final List<ItemStack> stacks = new ArrayList<>();

    public RubbleCacheBlockEntity(BlockPos pos, BlockState state) {
        super(TremorBlocks.RUBBLE_CACHE_ENTITY.get(), pos, state);
    }

    /** Puts copies of the stacks (the empty ones left out) into the cache. */
    public void add(Collection<ItemStack> added) {
        for (ItemStack stack : added) {
            if (!stack.isEmpty()) {
                stacks.add(stack.copy());
            }
        }
        setChanged();
    }

    /** What the cache holds. */
    public List<ItemStack> stacks() {
        return Collections.unmodifiableList(stacks);
    }

    /** Throws what the cache holds out at {@code pos}, as a container that is broken does, and empties it. */
    void spill(Level level, BlockPos pos) {
        for (ItemStack stack : stacks) {
            Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
        }
        stacks.clear();
        setChanged();
    }

    /** Empties the cache without spilling (a command is about to replace the block). */
    @Override
    public void clearContent() {
        stacks.clear();
        setChanged();
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        stacks.clear();
        ListTag items = tag.getList(ITEMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = ItemStack.of(items.getCompound(i));
            if (!stack.isEmpty()) {
                stacks.add(stack);
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        ListTag items = new ListTag();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                items.add(stack.save(new CompoundTag()));
            }
        }
        tag.put(ITEMS, items);
    }
}
