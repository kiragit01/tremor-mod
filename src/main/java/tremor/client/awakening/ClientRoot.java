package tremor.client.awakening;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ComputeFovModifierEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
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
 *     knock or a slide does not carry the player off; it still sinks or falls;</li>
 *     <li>but not while the client knows nothing yet of the ground under it ({@link #onPlayerTickPost}), rooted or in
 *     the dark of a move into or out of the hollow ({@link tremor.client.ClientBlackout#dark}): coming out of the hollow
 *     over a slow network (the user's friends on a LAN), the player was in the level a tick before its ground, fell
 *     into the stone as it came, and the server, finding the player in it, put the player back and turned its camera
 *     back to where it had looked a moment before.</li>
 * </ul>
 * The view: vanilla narrows the field of view with the movement speed, down to half of it for a speed of 0; the camera
 * would zoom in just as the hill rises around the player. While the player whose view it is carries the root's
 * modifier, its FOV modifier is the one it would have without it ({@link RootFov}); every other effect on the view
 * stays. Main thread only.
 */
public final class ClientRoot {
    /** Id of the root's movement speed modifier ({@code tremor.awakening.Root}); keep the two the same. */
    static final ResourceLocation ROOT_MODIFIER = ResourceLocation.fromNamespaceAndPath(Tremor.MODID,
            "awakening_root");

    /** Where the rooted local player stood before its tick while its ground had not arrived; null else. */
    private static Vec3 heldAt;

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
    public static void onPlayerTickPre(PlayerTickEvent.Pre event) {
        if (!(event.getEntity() instanceof LocalPlayer player) || player.isPassenger()) {
            return;
        }
        heldAt = (rooted(player) || tremor.client.ClientBlackout.dark()) && groundMissing(player) ? player.position()
                : null;
        if (!rooted(player)) {
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

    /**
     * After the player's tick: a player held for its ground ({@link #heldAt}: rooted or in the dark of a move, the
     * ground under it not come yet) is put back where it was before the tick, at rest, instead of falling into what
     * comes.
     */
    public static void onPlayerTickPost(PlayerTickEvent.Post event) {
        if (heldAt != null && event.getEntity() instanceof LocalPlayer player) {
            player.setPos(heldAt);
            player.setDeltaMovement(Vec3.ZERO);
        }
        heldAt = null;
    }

    /**
     * Whether the client knows nothing yet of the block under the player's feet within the world's height: its chunk
     * has not arrived, so it reads as void air.
     */
    private static boolean groundMissing(LocalPlayer player) {
        net.minecraft.core.BlockPos below = net.minecraft.core.BlockPos.containing(player.getX(), player.getY() - 0.5,
                player.getZ());
        return !player.level().isOutsideBuildHeight(below)
                && player.level().getBlockState(below).is(net.minecraft.world.level.block.Blocks.VOID_AIR);
    }

    /** Whether the player carries the root's movement speed modifier. */
    private static boolean rooted(Player player) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        return speed != null && speed.hasModifier(ROOT_MODIFIER);
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
