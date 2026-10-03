package tremor.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import tremor.hollow.level.SinkTracker;

/**
 * {@code tremor:mire}, the soft ground (SPEC 9, "Затягивание": "он медленно проваливается (замедление + опускание)").
 * It looks like a full block of mud (the model uses vanilla's mud texture), but only the bottom {@code 1 - softness /
 * 8} of it holds: a player on it stands that much lower, sunk into it, and goes lower as the level raises the
 * {@link #SOFTNESS} (both sides see the same state, so the client's own movement agrees with the server). Inside it a
 * creature is held as in a cobweb, only less ({@link #HOLD}, through vanilla's {@code Entity.makeStuckInBlock}: the
 * motion of the next move is scaled and the momentum lost): it walks slowly, and a jump lifts it about 0.6 block, so
 * it gets out of a shallow hole by stepping or jumping, and out of a deeper one only by digging the ground around.
 * It neither suffocates nor drops anything; the level turns it back into the copied block once the player is off it.
 */
public final class MireBlock extends Block {
    public static final MapCodec<MireBlock> CODEC = simpleCodec(MireBlock::new);
    /** How much of the block has given way, in eighths: 0 (holds like the block it was) .. 8 (holds nothing). */
    public static final IntegerProperty SOFTNESS = IntegerProperty.create("softness", 0, SinkTracker.MAX_SOFTNESS);
    /**
     * The motion multiplier inside: powder snow's {@code (0.9, 1.5, 0.9)} slowed down sideways (cobweb: 0.25). Up stays
     * at 1.5, so the one tick a jump lasts in here lifts {@code 0.42 * 1.5} blocks.
     */
    private static final Vec3 HOLD = new Vec3(0.4, 1.5, 0.4);
    private static final VoxelShape[] SHAPES = new VoxelShape[SinkTracker.MAX_SOFTNESS + 1];

    static {
        for (int softness = 0; softness < SinkTracker.MAX_SOFTNESS; softness++) {
            SHAPES[softness] = Block.box(0, 0, 0, 16, 16 - 16.0 * softness / SinkTracker.MAX_SOFTNESS, 16);
        }
        SHAPES[SinkTracker.MAX_SOFTNESS] = Shapes.empty();
    }

    public MireBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(SOFTNESS, 0));
    }

    @Override
    protected MapCodec<MireBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SOFTNESS);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        return SHAPES[state.getValue(SOFTNESS)];
    }

    /** Things placed on it hold as on the block it was (as mud). */
    @Override
    protected VoxelShape getBlockSupportShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.block();
    }

    @Override
    protected VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    /** As mud: the faces around it are shaded as if it were a full solid block. */
    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 0.2F;
    }

    /**
     * Holds a creature whose feet are in it (vanilla calls this for every block the bounding box touches, also the
     * ones beside the block a creature stands on).
     */
    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (entity.getY() < pos.getY() + 1 && entity.getY() >= pos.getY()) {
            entity.makeStuckInBlock(state, HOLD);
        }
    }
}
