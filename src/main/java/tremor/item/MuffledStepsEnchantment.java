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
        super(Rarity.VERY_RARE, EnchantmentCategory.ARMOR_FEET, new EquipmentSlot[] {EquipmentSlot.FEET});
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
