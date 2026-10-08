package tremor.client.awakening;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ComputeFovModifierEvent;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.event.TickEvent;
import tremor.Tremor;

/**
 * A rooted player (SPEC 9: the target of a swallowing while the hill rises, and the victor while the hill they come
 * out of is over their eyes) on its own client. The server roots the player ({@code tremor.awakening.Root}): a synced
 * modifier brings its movement speed and jump strength to 0, and whatever still moves it away is put back. The client
 * holds it as well, from the moment the modifier arrives, so that it does not run on and get put back:
 * <ul>
 *     <li>the movement keys count for nothing ({@link #onMovementInput}: no walking, strafing or jumping, so no sprint
 *     either, and no steering in the air, which vanilla gives a player whatever its movement speed);</li>
 *     <li>before the player's tick a sprint stops and the sideways momentum is gone ({@link #onPlayerTickPre}), so a
 *     knock or a slide does not carry the player off; it still sinks or falls.</li>
 * </ul>
 * The view: vanilla narrows the field of view with the movement speed, down to half of it for a speed of 0; the camera
 * would zoom in just as the hill rises around the player. While the player whose view it is carries the root's
 * modifier, its FOV modifier is the one it would have without it ({@link RootFov}); every other effect on the view
 * stays. Main thread only.
 */
public final class ClientRoot {
    /** Id of the root's movement speed modifier ({@code tremor.awakening.Root}); keep the two the same. */
    static final UUID ROOT_MODIFIER = UUID.nameUUIDFromBytes((Tremor.MODID + ":awakening_root")
            .getBytes(StandardCharsets.UTF_8));

    private ClientRoot() {
    }

    /** Game bus: takes the root's part out of the field of view of a rooted player. */
    public static void onComputeFovModifier(ComputeFovModifierEvent event) {
        Player player = event.getPlayer();
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null || speed.getModifier(ROOT_MODIFIER) == null) {
            return;
        }
        float fov = event.getFovModifier();
        double unrooted = RootFov.unrooted(fov, speed.getValue(), freeSpeed(speed),
                player.getAbilities().getWalkingSpeed());
        double scale = Minecraft.getInstance().options.fovEffectScale().get();
        event.setNewFovModifier((float) RootFov.corrected(event.getNewFovModifier(), scale, fov, unrooted));
    }

    /**
     * Game bus: the movement keys of a rooted local player count for nothing (sneaking stays: it moves nobody). Also
     * steers no vehicle the player is kept on.
     */
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (!(event.getEntity() instanceof LocalPlayer player) || !rooted(player)) {
            return;
        }
        Input input = event.getInput();
        input.forwardImpulse = 0;
        input.leftImpulse = 0;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.jumping = false;
    }

    /**
     * Game bus, before the local player's tick: a rooted player on foot stops sprinting and loses its sideways
     * momentum (its fall, or its sinking, goes on).
     */
    public static void onPlayerTickPre(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }
        if (!(event.player instanceof LocalPlayer player) || !rooted(player) || player.isPassenger()) {
            return;
        }
        if (player.isSprinting()) {
            player.setSprinting(false);
        }
        Vec3 motion = player.getDeltaMovement();
        if (motion.x != 0 || motion.z != 0) {
            player.setDeltaMovement(0, motion.y, 0);
        }
    }

    /** Whether the player carries the root's movement speed modifier. */
    private static boolean rooted(Player player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        return speed != null && speed.getModifier(ROOT_MODIFIER) != null;
    }

    /** The value the movement speed would have without the root's modifier. */
    private static double freeSpeed(AttributeInstance speed) {
        double add = 0, addMultipliedBase = 0, multipliedTotal = 1;
        for (AttributeModifier modifier : speed.getModifiers()) {
            if (modifier.getId().equals(ROOT_MODIFIER)) {
                continue;
            }
            switch (modifier.getOperation()) {
                case ADDITION -> add += modifier.getAmount();
                case MULTIPLY_BASE -> addMultipliedBase += modifier.getAmount();
                case MULTIPLY_TOTAL -> multipliedTotal *= 1 + modifier.getAmount();
            }
        }
        return speed.getAttribute().sanitizeValue(
                RootFov.attributeValue(speed.getBaseValue(), add, addMultipliedBase, multipliedTotal));
    }
}
