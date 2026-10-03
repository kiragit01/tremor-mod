package tremor.hollow.level;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SpongeBlock;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import tremor.block.TremorBlocks;
import tremor.hollow.HollowRules;

/**
 * What the level of the hollow (SPEC 9, phase 2) does with blocks: which cells are open, which blocks may grow into
 * them, which ground softens. Its changes are written with {@link #FLAGS}: sent to the client, but no neighbour is
 * told (water next to a filled cell does not start flowing, sand does not fall), as the copy itself stands still until
 * something touches it ({@code TerrainCopier}). What the level places is a copy like the rest: it drops nothing.
 */
final class Materials {
    /** Sent to the clients, no neighbour updates or shape updates. */
    static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    static final BlockState AIR = Blocks.AIR.defaultBlockState();
    /** The sides tried after the preferred one: below first (walls grow up from the floor), above last. */
    private static final Direction[] AROUND = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST,
            Direction.EAST, Direction.UP};
    /** The sides tried with a preferred one, by its {@link Direction#ordinal}: it first, then the others of AROUND. */
    private static final Direction[][] FIRST = new Direction[Direction.values().length][];

    static {
        for (Direction first : Direction.values()) {
            Direction[] sides = new Direction[AROUND.length];
            sides[0] = first;
            int n = 1;
            for (Direction side : AROUND) {
                if (side != first) {
                    sides[n++] = side;
                }
            }
            FIRST[first.ordinal()] = sides;
        }
    }

    private Materials() {
    }

    /**
     * Whether the closing fills the cell: air, a fluid, a plant, fire, a snow layer: anything replaceable without a
     * block entity.
     */
    static boolean open(BlockState state) {
        return state.canBeReplaced() && !state.hasBlockEntity();
    }

    /**
     * Whether a block may be copied into another cell: a plain full block (no block entity, no prop, nothing that
     * falls, decays, explodes, soaks up water or gives a signal, nothing unbreakable, none of the mod's own).
     */
    static boolean usable(BlockGetter level, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        return !state.hasBlockEntity() && state.isCollisionShapeFullBlock(level, pos)
                && !(block instanceof FallingBlock) && !(block instanceof LeavesBlock) && !(block instanceof TntBlock)
                && !(block instanceof SpongeBlock) && !state.isSignalSource() && !state.is(HollowRules.PROPS)
                && state.getDestroySpeed(level, pos) >= 0 && !state.is(TremorBlocks.HEART_NODE)
                && !state.is(TremorBlocks.MIRE);
    }

    /**
     * What grows into the open cell at {@code pos}: a copy of a usable neighbour that does not hurt ({@code first}
     * tried first, if not null, then {@link #AROUND}), sand as sandstone; else, next to any solid block, stone
     * (deepslate below y 0); null if every neighbour is open (nothing to grow from). See {@link MaterialChoice}.
     */
    static BlockState material(ServerLevel level, BlockPos pos, Direction first) {
        Direction[] sides = first == null ? AROUND : FIRST[first.ordinal()];
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int chosen = MaterialChoice.choose(sides.length, i -> kind(level, at.setWithOffset(pos, sides[i])));
        if (chosen < 0) {
            return chosen == MaterialChoice.STONE ? stone(pos) : null;
        }
        BlockState state = level.getBlockState(at.setWithOffset(pos, sides[chosen]));
        if (state.is(Blocks.SAND)) {
            return Blocks.SANDSTONE.defaultBlockState();
        }
        return state.is(Blocks.RED_SAND) ? Blocks.RED_SANDSTONE.defaultBlockState() : state;
    }

    /** What the block at {@code pos} is to {@link #material}. */
    private static MaterialChoice.Kind kind(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (open(state)) {
            return MaterialChoice.Kind.OPEN;
        }
        return state.is(Blocks.SAND) || state.is(Blocks.RED_SAND) || usable(level, pos, state) && !hazard(state)
                ? MaterialChoice.Kind.COPY : MaterialChoice.Kind.OTHER;
    }

    /**
     * {@link #material} with the block below tried first, or stone if there is nothing around to grow from (a floor
     * laid over a void): never magma or another block that hurts, so a floor laid where one was is safe ground.
     */
    static BlockState floor(ServerLevel level, BlockPos pos) {
        BlockState state = material(level, pos, Direction.DOWN);
        return state != null ? state : stone(pos);
    }

    /**
     * Whether the ground at {@code pos} can soften into {@code tremor:mire}: any block with a collision (a full block, a
     * slab, a path, soul sand, a fence; unbreakable ones too, they are copies) but one with a block entity (a prop of
     * the copy, which would lose it), the node and the mire itself.
     */
    static boolean softenable(BlockGetter level, BlockPos pos, BlockState state) {
        return !state.hasBlockEntity() && !state.getCollisionShape(level, pos).isEmpty()
                && !state.is(TremorBlocks.HEART_NODE) && !state.is(TremorBlocks.MIRE);
    }

    /**
     * Whether a block hurts or traps whoever is in it or on it: lava, fire, magma, cactus, berry bushes, powder snow,
     * wither roses, dripstone spikes.
     */
    static boolean hazard(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA) || state.is(BlockTags.FIRE) || state.is(BlockTags.CAMPFIRES)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POINTED_DRIPSTONE);
    }

    private static BlockState stone(BlockPos pos) {
        return pos.getY() < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }
}
