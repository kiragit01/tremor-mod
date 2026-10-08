package tremor.hollow;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;

/**
 * The item an arrow (or a trident) on the ground gives back. {@code AbstractArrow.getPickupItem()} is protected in
 * 1.20.1; it is found by its signature (the only abstract method without parameters that returns an item stack), so
 * the name does not matter in the development or the release environment.
 */
final class ArrowItems {
    private static final Method PICKUP = find();

    private ArrowItems() {
    }

    static ItemStack of(AbstractArrow arrow) {
        try {
            return (ItemStack) PICKUP.invoke(arrow);
        } catch (ReflectiveOperationException e) {
            return ItemStack.EMPTY;
        }
    }

    private static Method find() {
        for (Method method : AbstractArrow.class.getDeclaredMethods()) {
            if (method.getParameterCount() == 0 && method.getReturnType() == ItemStack.class
                    && Modifier.isAbstract(method.getModifiers())) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException("AbstractArrow has no pickup item method");
    }
}
