package tremor.item;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import tremor.Tremor;

/**
 * The things made from the entity (SPEC 15, stage 5): the shard a destroyed node gives (one by default,
 * {@code tremor.awakening.Outcomes}), the seismograph (its needle points at the entity, client side
 * {@code tremor.client.SeismographNeedle}) and the geophone ({@link GeophoneBlock}); the "muffled steps" enchantment
 * is data ({@code data/tremor/enchantment/muffled_steps.json}), its book a recipe. Registered by {@link Tremor}.
 */
public final class TremorItems {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Tremor.MODID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Tremor.MODID);

    public static final DeferredItem<Item> SHARD = ITEMS.registerSimpleItem("tremor_shard",
            new Item.Properties().rarity(Rarity.UNCOMMON));
    public static final DeferredItem<Item> SEISMOGRAPH = ITEMS.registerSimpleItem("seismograph",
            new Item.Properties().stacksTo(1));
    public static final DeferredBlock<GeophoneBlock> GEOPHONE = BLOCKS.registerBlock("geophone", GeophoneBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.DEEPSLATE)
                    .strength(3.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> state.getValue(GeophoneBlock.POWER) > 0 ? 3 : 0)
                    .sound(SoundType.DEEPSLATE));
    public static final DeferredItem<BlockItem> GEOPHONE_ITEM = ITEMS.registerSimpleBlockItem(GEOPHONE);

    private TremorItems() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(TremorItems::onCreativeTabs);
    }

    private static void onCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(SHARD);
        } else if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(SEISMOGRAPH);
        } else if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(GEOPHONE_ITEM);
        }
    }
}
