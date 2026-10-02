package tremor.client.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * The world as seen by a block copy floating out of the ground: empty all around, uniformly lit with the light of the
 * open space next to the original block, but with the real biome tint and directional shading. Feeding this to
 * {@code ModelBlockRenderer} instead of the level gives copies that look like terrain without the black faces
 * that real neighbours (solid ground) would cause.
 */
final class ProbeLevel implements BlockAndTintGetter {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private ClientLevel level;
    private int skyLight;
    private int blockLight;

    void set(ClientLevel level, int skyLight, int blockLight) {
        this.level = level;
        this.skyLight = skyLight;
        this.blockLight = blockLight;
    }

    @Override
    public int getBrightness(LightLayer lightType, BlockPos blockPos) {
        return lightType == LightLayer.SKY ? skyLight : blockLight;
    }

    @Override
    public int getRawBrightness(BlockPos blockPos, int amount) {
        return Math.max(blockLight, skyLight - amount);
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return level.getShade(direction, shade);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return level.getLightEngine();
    }

    @Override
    public int getBlockTint(BlockPos blockPos, ColorResolver colorResolver) {
        return level.getBlockTint(blockPos, colorResolver);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return AIR;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return Fluids.EMPTY.defaultFluidState();
    }

    @Override
    public int getHeight() {
        return level.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return level.getMinBuildHeight();
    }
}
