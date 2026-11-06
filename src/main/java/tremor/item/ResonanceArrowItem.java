package tremor.item;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import tremor.entity.ResonanceArrows;

/**
 * The resonance arrow (1.4.0, the user's idea): an arrow that rings as it flies and bursts into a pulse where it
 * strikes, dissolving the listeners and the stalactite crawlers near and soaking up the listeners' signals; a miss
 * rings through the ground and may bring the tremor ({@link ResonanceArrows}). A plain arrow marked as one.
 */
public final class ResonanceArrowItem extends ArrowItem {
    public ResonanceArrowItem(Properties properties) {
        super(properties);
    }

    @Override
    public AbstractArrow createArrow(Level level, ItemStack ammo, LivingEntity shooter) {
        return ResonanceArrows.mark(new Arrow(level, shooter));
    }
}
