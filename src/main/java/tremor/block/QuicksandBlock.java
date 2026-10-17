package tremor.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import tremor.hollow.level.SinkTracker;

/**
 * {@code tremor:quicksand}, the ground of an ambush's quicksand in the real world ({@code tremor.awakening.Quicksand}):
 * it gives way and holds as the soft ground of the hollow ({@link MireBlock}: in 32nds, the softness kept by its block
 * entity rather than its state, so that the chunk is not rebuilt as it gives way; a creature in
 * it held as in a cobweb, only less), but it looks like the block it was, which it remembers
 * ({@link QuicksandBlockEntity}): it has no model of its own, the client draws that block in its place
 * ({@code tremor.client.QuicksandRenderer}), and steps on it sound and dust as on that block. A player sinks into the
 * ground as it looks.
 * <p>
 * The ground of the quicksand is the entity's while it pulls somebody in, the only time there is any: nobody breaks it
 * ({@link #getDestroyProgress}; a player in creative mode neither, {@link #onDestroyedByPlayer}). Digging into where an
 * entity lies in wait, before it springs, enrages it instead ({@code tremor.entity.TremorMind#groundBroken}).
 * Explosions and pistons leave it alone, it does not suffocate, it drops nothing.
 */
public final class QuicksandBlock extends Block implements EntityBlock {
    public static final MapCodec<QuicksandBlock> CODEC = simpleCodec(QuicksandBlock::new);
    /** The motion multiplier inside, as the hollow's soft ground's. */
    private static final Vec3 HOLD = new Vec3(0.4, 1.5, 0.4);
    private static final VoxelShape[] SHAPES = new VoxelShape[SinkTracker.MAX_SOFTNESS + 1];

    static {
        for (int softness = 0; softness < SinkTracker.MAX_SOFTNESS; softness++) {
            SHAPES[softness] = Block.box(0, 0, 0, 16, 16 - 16.0 * softness / SinkTracker.MAX_SOFTNESS, 16);
        }
        SHAPES[SinkTracker.MAX_SOFTNESS] = Shapes.empty();
    }

    public QuicksandBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /** The block the quicksand at {@code pos} was, as it looks; air if unknown (not sent yet). */
    public static BlockState original(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof QuicksandBlockEntity entity ? entity.original()
                : Blocks.AIR.defaultBlockState();
    }

    @Override
    protected MapCodec<QuicksandBlock> codec() {
        return CODEC;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new QuicksandBlockEntity(pos, state);
    }

    /** No model of its own: the client draws the block it was ({@code tremor.client.QuicksandRenderer}). */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        return level.getBlockEntity(pos) instanceof QuicksandBlockEntity entity ? SHAPES[entity.softness()]
                : Shapes.block();
    }

    /** Things placed on it hold as on the block it was. */
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

    /** Holds a creature whose feet are in it, as the soft ground of the hollow. */
    @Override
    protected void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (entity.getY() < pos.getY() + 1 && entity.getY() >= pos.getY()) {
            entity.makeStuckInBlock(state, HOLD);
        }
    }

    /** The entity's ground: nobody breaks it. */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        return 0;
    }

    /** Not even in creative mode: it stays (the client gets it back). */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, boolean willHarvest,
                                       FluidState fluid) {
        return false;
    }

    @Override
    public SoundType getSoundType(BlockState state, LevelReader level, BlockPos pos, Entity entity) {
        BlockState original = original(level, pos);
        return original.isAir() ? super.getSoundType(state, level, pos, entity)
                : original.getSoundType(level, pos, entity);
    }

    /** The dust of a sprint over it is the block's it was. */
    @Override
    public boolean addRunningEffects(BlockState state, Level level, BlockPos pos, Entity entity) {
        BlockState original = original(level, pos);
        if (!original.isAir()) {
            Vec3 motion = entity.getDeltaMovement();
            level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, original),
                    entity.getX() + (level.getRandom().nextDouble() - 0.5) * entity.getBbWidth(), entity.getY() + 0.1,
                    entity.getZ() + (level.getRandom().nextDouble() - 0.5) * entity.getBbWidth(), motion.x * -4.0,
                    1.5, motion.z * -4.0);
        }
        return true;
    }

    /** So is the dust of a landing on it. */
    @Override
    public boolean addLandingEffects(BlockState state, ServerLevel level, BlockPos pos, BlockState entityState,
                                     LivingEntity entity, int particles) {
        BlockState original = original(level, pos);
        if (!original.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, original), entity.getX(), entity.getY(),
                    entity.getZ(), particles, 0, 0, 0, 0.15);
        }
        return true;
    }
}
