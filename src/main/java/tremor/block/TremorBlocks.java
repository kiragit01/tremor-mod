package tremor.block;

import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;
import tremor.Tremor;

/**
 * The mod's blocks, both only ever placed by the level inside the hollow (SPEC 9, phase 2; {@link
 * tremor.hollow.level.HollowLevels}) and without items: nothing drops them and nothing places them.
 */
public final class TremorBlocks {
    /** Immune to explosions, as bedrock. */
    private static final float BLAST_PROOF = 3_600_000;

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Tremor.MODID);

    /**
     * The node (SPEC 9, "Узел"): a pulsing block somewhere in the hollow; a player who breaks it wins. A few seconds by
     * hand, much less with a pickaxe ({@code #minecraft:mineable/pickaxe}); it glows, drops nothing and neither
     * explosions nor pistons move or break it.
     */
    public static final DeferredBlock<HeartNodeBlock> HEART_NODE = BLOCKS.registerBlock("heart_node",
            HeartNodeBlock::new, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .strength(2.5F, BLAST_PROOF)
                    .lightLevel(state -> 11)
                    .emissiveRendering((state, level, pos) -> true)
                    .hasPostProcess((state, level, pos) -> true)
                    .sound(SoundType.SCULK_CATALYST)
                    .pushReaction(PushReaction.BLOCK)
                    .noLootTable()
                    .isValidSpawn((state, level, pos, type) -> false));

    /**
     * The soft ground (SPEC 9, "Затягивание"): what the ground under a player who stands still turns into, sinking
     * level by level ({@link MireBlock#SOFTNESS}); it sets back into the copied block once the player is off it.
     * Unbreakable (the player gets out by moving, or by digging the ground around), drops nothing, never moved.
     */
    public static final DeferredBlock<MireBlock> MIRE = BLOCKS.registerBlock("mire", MireBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.TERRACOTTA_CYAN)
                    .strength(-1.0F, BLAST_PROOF)
                    .sound(SoundType.MUD)
                    .pushReaction(PushReaction.BLOCK)
                    .noLootTable()
                    .isValidSpawn((state, level, pos, type) -> false)
                    .isViewBlocking((state, level, pos) -> true)
                    .isSuffocating((state, level, pos) -> false));

    private TremorBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }
}
