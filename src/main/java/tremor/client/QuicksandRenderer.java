package tremor.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.RenderTypeHelper;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.model.data.ModelData;
import tremor.block.QuicksandBlockEntity;
import tremor.block.TremorBlocks;

/**
 * Draws a block of an ambush's quicksand ({@code tremor:quicksand}, which has no model of its own) as the block it was
 * ({@link QuicksandBlockEntity#original}): its model tesselated in its place as the chunk would (the light and the
 * ambient occlusion of the level, the tint of the biome, the faces the neighbours hide left out), in the render types
 * of that model, so the ground looks as it did while a player sinks into it. As far as the chunks around are drawn.
 * Render thread only.
 */
public final class QuicksandRenderer implements BlockEntityRenderer<QuicksandBlockEntity> {
    /** Seen from this far (blocks): no hole in the ground at a distance. */
    private static final int VIEW_DISTANCE = 256;

    private final RandomSource random = RandomSource.create();

    public QuicksandRenderer(BlockEntityRendererProvider.Context context) {
    }

    /** Mod bus. */
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(TremorBlocks.QUICKSAND_ENTITY.get(), QuicksandRenderer::new);
    }

    @Override
    public void render(QuicksandBlockEntity entity, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int light, int overlay) {
        BlockState original = entity.original();
        Level level = entity.getLevel();
        if (level == null || original.getRenderShape() != RenderShape.MODEL) {
            return;
        }
        BlockPos pos = entity.getBlockPos();
        BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
        BakedModel model = blocks.getBlockModel(original);
        long seed = original.getSeed(pos);
        ModelData data = model.getModelData(level, pos, original, ModelData.EMPTY);
        random.setSeed(seed);
        for (RenderType type : model.getRenderTypes(original, random, data)) {
            blocks.getModelRenderer().tesselateBlock(level, model, original, pos, pose,
                    buffers.getBuffer(RenderTypeHelper.getEntityRenderType(type, false)), true, random, seed, overlay,
                    data, type);
        }
    }

    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }
}
