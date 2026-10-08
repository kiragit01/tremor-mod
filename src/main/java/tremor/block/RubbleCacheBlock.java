package tremor.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code tremor:rubble_cache}: rubble with things in it (SPEC 9 "Поражение": the things of a player the ground killed
 * lie on the bottom of the crater, some of them buried; {@link tremor.awakening.CraterCaches} puts them there). It
 * looks like suspicious gravel, its top a little darker in the middle, as if something had sunk into it, and breaks
 * like gravel (a shovel is quicker) into a gravel. Whatever breaks it (a player, an explosion, {@code /setblock ...
 * destroy}) spills what it holds ({@link RubbleCacheBlockEntity}); a command that replaces it empties it first, as it
 * does a chest. Unlike gravel it does not fall, and pistons do not move it. One lying open (air over it) now and then
 * glints faintly ({@link #animateTick}): a hint for one who searches the bottom closely, not to be seen from afar.
 */
public final class RubbleCacheBlock extends Block implements EntityBlock {
    /**
     * One in this many of the client's random looks at the block shows a glint (vanilla looks at the blocks near the
     * player far more often): about every eight seconds to a player right by it, every half a minute or so ten blocks
     * away, hardly ever from the rim of the crater.
     */
    private static final int GLINT_CHANCE = 24;

    public RubbleCacheBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RubbleCacheBlockEntity(pos, state);
    }

    /** The block is going (broken, blown up, replaced): what it holds spills, on the server. */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide && !state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof RubbleCacheBlockEntity cache) {
            cache.spill(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /** Client: now and then a faint glint on the top of a cache with air over it (a small sparkle, gone at once). */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(GLINT_CHANCE) == 0 && level.getBlockState(pos.above()).isAir()) {
            level.addParticle(ParticleTypes.WAX_OFF, pos.getX() + 0.15 + 0.7 * random.nextDouble(), pos.getY() + 1.02,
                    pos.getZ() + 0.15 + 0.7 * random.nextDouble(), 0, 0, 0);
        }
    }
}
