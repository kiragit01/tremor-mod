package tremor.world;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import tremor.Tremor;

public final class TremorTags {
    private TremorTags() {
    }

    /** Blocks the mod must never modify (SPEC 12). Blocks with block entities are always protected as well. */
    public static final TagKey<Block> PROTECTED = block("protected");

    /** Vibration conductivity classes (SPEC 7.2), see {@link LevelVoxelView.ConductivityClass}. */
    public static final TagKey<Block> CONDUCTIVITY_INSULATING = block("conductivity/insulating");
    public static final TagKey<Block> CONDUCTIVITY_WOODEN = block("conductivity/wooden");
    public static final TagKey<Block> CONDUCTIVITY_GRAVELLY = block("conductivity/gravelly");
    public static final TagKey<Block> CONDUCTIVITY_SANDY = block("conductivity/sandy");
    public static final TagKey<Block> CONDUCTIVITY_STONY = block("conductivity/stony");

    private static TagKey<Block> block(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(Tremor.MODID, path));
    }
}
