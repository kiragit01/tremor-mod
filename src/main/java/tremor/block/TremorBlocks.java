package tremor.block;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.RegistryObject;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.DeferredRegister;
import tremor.Tremor;

/**
 * The mod's blocks: the node and the soft ground, only ever placed by the level inside the hollow (SPEC 9, phase 2;
 * {@link tremor.hollow.level.HollowLevels}) and without items, so nothing drops them and nothing places them; and the
 * caches of rubble on the bottom of a crater ({@link #RUBBLE_CACHE}), with an item for commands.
 */
public final class TremorBlocks {
    /** Immune to explosions, as bedrock. */
    private static final float BLAST_PROOF = 3_600_000;
    /** The node's light level: the block next to it is barely lit, the tunnel around it dark. */
    public static final int NODE_LIGHT = 4;

    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Tremor.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Tremor.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Tremor.MODID);

    /**
     * The node (SPEC 9, "Узел"): a pulsing block somewhere in the hollow; a player who breaks it wins. A few seconds by
     * hand, much less with a pickaxe ({@code #minecraft:mineable/pickaxe}); drops nothing and neither explosions nor
     * pistons move or break it. Up close it looks lit (emissive), but its light ({@value #NODE_LIGHT}) dies within a
     * few blocks: it is found by its beat and its rings, not by a glow down the tunnels ("Сквозь туман он не
     * светится").
     */
    public static final RegistryObject<HeartNodeBlock> HEART_NODE = BLOCKS.register("heart_node",
            () -> new HeartNodeBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_RED)
                    .strength(2.5F, BLAST_PROOF)
                    .lightLevel(state -> TremorBlocks.NODE_LIGHT)
                    .emissiveRendering((state, level, pos) -> true)
                    .hasPostProcess((state, level, pos) -> true)
                    .sound(SoundType.SCULK_CATALYST)
                    .pushReaction(PushReaction.BLOCK)
                    .noLootTable()
                    .isValidSpawn((state, level, pos, type) -> false)));

    /**
     * The soft ground (SPEC 9, "Затягивание"): what the ground under a player who stands still turns into, sinking
     * level by level ({@link MireBlock#SOFTNESS}); it sets back into the copied block once the player is off it.
     * Unbreakable (the player gets out by moving, or by digging the ground around), drops nothing, never moved.
     */
    public static final RegistryObject<MireBlock> MIRE = BLOCKS.register("mire",
            () -> new MireBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.TERRACOTTA_CYAN)
                    .strength(-1.0F, BLAST_PROOF)
                    .sound(SoundType.MUD)
                    .pushReaction(PushReaction.BLOCK)
                    .noLootTable()
                    .isValidSpawn((state, level, pos, type) -> false)
                    .isViewBlocking((state, level, pos) -> true)
                    .isSuffocating((state, level, pos) -> false)));

    /**
     * A cache of rubble (SPEC 9, "Поражение": the things of a player the ground killed, scattered over the bottom of
     * the crater and partly buried; {@link tremor.awakening.CraterCaches}): looks like suspicious gravel (with a faint
     * glint now and then when it lies open), as hard as gravel ({@code #minecraft:mineable/shovel}) and drops a gravel
     * ({@code loot_table/blocks/rubble_cache.json}); whatever breaks it spills what it holds, a command that replaces it
     * empties it first ({@link RubbleCacheBlock}). It does not fall, pistons do not move it.
     */
    public static final RegistryObject<RubbleCacheBlock> RUBBLE_CACHE = BLOCKS.register("rubble_cache",
            () -> new RubbleCacheBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(0.6F)
                    .sound(SoundType.SUSPICIOUS_GRAVEL)
                    .pushReaction(PushReaction.BLOCK)));

    /** What a {@link #RUBBLE_CACHE} holds. */
    public static final RegistryObject<BlockEntityType<RubbleCacheBlockEntity>>
            RUBBLE_CACHE_ENTITY = BLOCK_ENTITIES.register("rubble_cache",
            () -> BlockEntityType.Builder.of(RubbleCacheBlockEntity::new, RUBBLE_CACHE.get()).build(null));

    /** The item of a {@link #RUBBLE_CACHE} (for {@code /give} and {@code /setblock}; placed, it holds nothing). */
    public static final RegistryObject<BlockItem> RUBBLE_CACHE_ITEM = ITEMS.register("rubble_cache",
            () -> new BlockItem(RUBBLE_CACHE.get(), new Item.Properties()));

    private TremorBlocks() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        ITEMS.register(modBus);
    }
}
