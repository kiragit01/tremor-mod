package tremor.item;

import net.minecraft.core.Direction;
import net.minecraft.core.Position;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
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
    public AbstractArrow createArrow(Level level, ItemStack ammo, LivingEntity shooter, @Nullable ItemStack weapon) {
        return ResonanceArrows.mark(new Arrow(level, shooter, ammo.copyWithCount(1), weapon));
    }

    @Override
    public Projectile asProjectile(Level level, Position pos, ItemStack stack, Direction direction) {
        Arrow arrow = new Arrow(level, pos.x(), pos.y(), pos.z(), stack.copyWithCount(1), null);
        arrow.pickup = AbstractArrow.Pickup.ALLOWED;
        return ResonanceArrows.mark(arrow);
    }
}
