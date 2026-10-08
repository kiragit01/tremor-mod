package tremor.hollow;

import java.util.function.Supplier;

import com.google.common.base.Suppliers;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.loot.IGlobalLootModifier;
import net.minecraftforge.common.loot.LootModifier;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import tremor.Tremor;

/**
 * Blocks of the copy in the hollow drop nothing, however they break (players, water, pistons, explosions, decay); the
 * player's own blocks drop as anywhere. A global loot modifier ({@code data/forge/loot_modifiers}): Forge 1.20.1 has
 * no event for the drops of every block break. The experience of ore is taken in {@link HollowRules#onBlockBreak}.
 */
public final class HollowDrops extends LootModifier {
    public static final Supplier<Codec<HollowDrops>> CODEC = Suppliers.memoize(
            () -> RecordCodecBuilder.create(instance -> codecStart(instance).apply(instance, HollowDrops::new)));

    private static final DeferredRegister<Codec<? extends IGlobalLootModifier>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, Tremor.MODID);

    static {
        SERIALIZERS.register("hollow_drops", CODEC);
    }

    private HollowDrops(LootItemCondition[] conditions) {
        super(conditions);
    }

    public static void register(IEventBus modBus) {
        SERIALIZERS.register(modBus);
    }

    @Override
    protected ObjectArrayList<ItemStack> doApply(ObjectArrayList<ItemStack> loot, LootContext context) {
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        ServerLevel level = context.getLevel();
        if (context.hasParam(LootContextParams.BLOCK_STATE) && origin != null && HollowDimension.is(level)
                && !HollowManager.isPlayerPlaced(level, BlockPos.containing(origin))) {
            loot.clear();
        }
        return loot;
    }

    @Override
    public Codec<? extends IGlobalLootModifier> codec() {
        return CODEC.get();
    }
}
