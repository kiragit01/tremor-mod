package tremor.item;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * A resonance arrow in flight on 1.20.1, where a plain arrow always gives back a plain arrow: picked up again (one that
 * glanced off and lies there) it is a resonance arrow, as on 1.21, whose arrows keep the stack they came from.
 * shortcut: a world saved with one lying about loads it as a plain arrow (its tag still makes it ring) and it is picked
 * up as a plain one; good enough unless players report it.
 */
public final class ResonanceArrowEntity extends Arrow {
    public ResonanceArrowEntity(Level level, LivingEntity shooter) {
        super(level, shooter);
    }

    public ResonanceArrowEntity(Level level, double x, double y, double z) {
        super(level, x, y, z);
    }

    @Override
    protected ItemStack getPickupItem() {
        return new ItemStack(TremorItems.RESONANCE_ARROW.get());
    }
}
