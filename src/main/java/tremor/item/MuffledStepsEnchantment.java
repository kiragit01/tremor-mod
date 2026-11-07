package tremor.item;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentCategory;

/**
 * "Muffled Steps": the wearer's footsteps are heard {@code items.muffledStepsFactor} as loud
 * ({@link tremor.hearing.VibrationListener}). One level, boots only. Only from its book's recipe: never from the
 * enchanting table, trades or loot.
 */
public final class MuffledStepsEnchantment extends Enchantment {
    MuffledStepsEnchantment() {
        // RARE: the book costs 2 levels on an anvil, as the 1.21 data's anvil cost of 4 (never on the table anyway).
        super(Rarity.RARE, EnchantmentCategory.ARMOR_FEET, new EquipmentSlot[] {EquipmentSlot.FEET});
    }

    @Override
    public int getMinCost(int level) {
        return 25;
    }

    @Override
    public int getMaxCost(int level) {
        return 50;
    }

    @Override
    public boolean isTreasureOnly() {
        return true;
    }

    @Override
    public boolean isTradeable() {
        return false;
    }

    @Override
    public boolean isDiscoverable() {
        return false;
    }
}
