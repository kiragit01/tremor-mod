package tremor.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import tremor.config.TremorConfig;
import tremor.core.math.Vec3;
import tremor.entity.TremorEntity;
import tremor.entity.TremorManager;
import tremor.entity.TremorRuntime;

/**
 * The geophone (SPEC 15, stage 5: the alarm for a base): a block that listens to the ground for the entity, not for
 * players. With the entity within {@code items.geophoneRange} blocks it gives a redstone signal, the stronger the
 * nearer ({@link Sensing#power}), and a comparator reads the entity's stage ({@link Sensing#stageSignal}); while it
 * hears something its veins and lens glow and pulse (model {@code geophone_on}, light level 3) and it sheds dust. It checks every {@value #INTERVAL} ticks (a scheduled tick, kept with the chunk).
 */
public class GeophoneBlock extends Block {
    public static final IntegerProperty POWER = BlockStateProperties.POWER;
    /** Ticks between two checks. */
    static final int INTERVAL = 10;

    public GeophoneBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(POWER, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(POWER);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moved) {
        if (!level.isClientSide && !old.is(this)) {
            level.scheduleTick(pos, this, INTERVAL);
        }
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        TremorEntity entity = heard(level, pos);
        int power = entity == null ? 0 : Sensing.power(distance(entity, pos), range());
        if (power != state.getValue(POWER)) {
            level.setBlock(pos, state.setValue(POWER, power), Block.UPDATE_ALL);
        }
        level.updateNeighbourForOutputSignal(pos, this);
        level.scheduleTick(pos, this, INTERVAL);
    }

    @Override
    public boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    public int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(POWER);
    }

    @Override
    public boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        if (state.getValue(POWER) == 0 || !(level instanceof ServerLevel server)) {
            return 0;
        }
        TremorEntity entity = heard(server, pos);
        return entity == null ? 0 : Sensing.stageSignal(entity.stage());
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        int power = state.getValue(POWER);
        if (power > 0 && random.nextInt(18 - power) < 2) {
            level.addParticle(new BlockParticleOption(ParticleTypes.FALLING_DUST, state), pos.getX() + random.nextDouble(),
                    pos.getY() + 1.02, pos.getZ() + random.nextDouble(), 0, 0, 0);
        }
    }

    /** The entity of the level, if it is within range of {@code pos}; null otherwise. */
    private static TremorEntity heard(ServerLevel level, BlockPos pos) {
        TremorRuntime runtime = TremorManager.runtime(level);
        TremorEntity entity = runtime == null ? null : runtime.entity();
        return entity != null && distance(entity, pos) < range() ? entity : null;
    }

    private static double distance(TremorEntity entity, BlockPos pos) {
        Vec3 at = entity.crawler().position();
        return at.distance(new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
    }

    private static double range() {
        return TremorConfig.COMMON.geophoneRange.get();
    }
}
