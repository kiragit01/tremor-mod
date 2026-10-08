package tremor.item;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.registries.RegistryObject;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.DeferredRegister;
import tremor.Tremor;

/**
 * The things made from the entity (SPEC 15, stage 5): the shard a destroyed node gives (one by default,
 * {@code tremor.awakening.Outcomes}), the seismograph (its needle points at the entity, client side
 * {@code tremor.client.SeismographNeedle}) and the geophone ({@link GeophoneBlock}); the "muffled steps" enchantment
 * ({@link MuffledStepsEnchantment}) comes only from its book's recipe. Registered by {@link Tremor}.
 */
public final class TremorItems {
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Tremor.MODID);
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Tremor.MODID);

    public static final RegistryObject<Item> SHARD = ITEMS.register("tremor_shard",
            () -> new Item(new Item.Properties().rarity(Rarity.UNCOMMON)));
    public static final RegistryObject<Item> SEISMOGRAPH = ITEMS.register("seismograph",
            () -> new Item(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<GeophoneBlock> GEOPHONE = BLOCKS.register("geophone",
            () -> new GeophoneBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.DEEPSLATE)
                    .strength(3.0F, 6.0F)
                    .requiresCorrectToolForDrops()
                    .lightLevel(state -> state.getValue(GeophoneBlock.POWER) > 0 ? 3 : 0)
                    .sound(SoundType.DEEPSLATE)));
    private static final DeferredRegister<Enchantment> ENCHANTMENTS =
            DeferredRegister.create(ForgeRegistries.ENCHANTMENTS, Tremor.MODID);
    public static final RegistryObject<Enchantment> MUFFLED_STEPS =
            ENCHANTMENTS.register("muffled_steps", MuffledStepsEnchantment::new);
    public static final RegistryObject<BlockItem> GEOPHONE_ITEM = ITEMS.register("geophone",
            () -> new BlockItem(GEOPHONE.get(), new Item.Properties()));

    private TremorItems() {
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        ENCHANTMENTS.register(modBus);
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
