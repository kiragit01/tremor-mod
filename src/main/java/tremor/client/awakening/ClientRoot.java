package tremor.client.awakening;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import tremor.Tremor;

/**
 * The view of the target of a swallowing while the hill rises (SPEC 9), on its client. The server roots the target
 * ({@code tremor.awakening.Root}): a synced modifier brings its movement speed to 0, and vanilla narrows the field of
 * view with the speed, down to half of it for a speed of 0; the camera would zoom in just as the hill rises around
 * the player. While the player whose view it is carries the root's modifier, its FOV modifier is the one it would have
 * without it ({@link RootFov}); every other effect on the view stays. Main thread only.
 */
public final class ClientRoot {
    /** Id of the root's movement speed modifier ({@code tremor.awakening.Root}); keep the two the same. */
    static final ResourceLocation ROOT_MODIFIER = ResourceLocation.fromNamespaceAndPath(Tremor.MODID,
            "awakening_root");

    private ClientRoot() {
    }

    /** Game bus: takes the root's part out of the field of view of a rooted player. */
    public static void onComputeFovModifier(ComputeFovModifierEvent event) {
        Player player = event.getPlayer();
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null || !speed.hasModifier(ROOT_MODIFIER)) {
            return;
        }
        float fov = event.getFovModifier();
        double unrooted = RootFov.unrooted(fov, speed.getValue(), freeSpeed(speed),
                player.getAbilities().getWalkingSpeed());
        double scale = Minecraft.getInstance().options.fovEffectScale().get();
        event.setNewFovModifier((float) RootFov.corrected(event.getNewFovModifier(), scale, fov, unrooted));
    }

    /** The value the movement speed would have without the root's modifier. */
    private static double freeSpeed(AttributeInstance speed) {
        double add = 0, addMultipliedBase = 0, multipliedTotal = 1;
        for (AttributeModifier modifier : speed.getModifiers()) {
            if (modifier.id().equals(ROOT_MODIFIER)) {
                continue;
            }
            switch (modifier.operation()) {
                case ADD_VALUE -> add += modifier.amount();
                case ADD_MULTIPLIED_BASE -> addMultipliedBase += modifier.amount();
                case ADD_MULTIPLIED_TOTAL -> multipliedTotal *= 1 + modifier.amount();
            }
        }
        return speed.getAttribute().value().sanitizeValue(
                RootFov.attributeValue(speed.getBaseValue(), add, addMultipliedBase, multipliedTotal));
    }
}
