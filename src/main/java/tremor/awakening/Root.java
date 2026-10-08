package tremor.awakening;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import tremor.Tremor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Holds the target of a swallowing in place (SPEC 9: "под игроком поднимается холм и затягивает его"): no walking,
 * jumping or flying while the hill rises and the screen goes dark; and the victor of the hollow in the hill they come
 * out of, for as long as it is over their eyes ("пока холм выше глаз — игрок удержан на месте"). A player's client
 * moves the player, so the root works on several layers:
 * <ul>
 *   <li>transient attribute modifiers (never saved with the player) bring the movement speed and the jump strength to
 *   0; both attributes are synced, sent to the player at once when the root starts and ends, so the client itself
 *   stops walking (sprinting included) and jumping. The client also lets go of the movement keys and stops what
 *   momentum the player has ({@code tremor.client.awakening.ClientRoot}); it keeps the field of view of a player with
 *   the modifier as it was (vanilla derives a narrower one from a movement speed of 0).</li>
 *   <li>a player on foot is put back where it stands as the root starts, its momentum gone: the server takes no
 *   movement from the client until the client has had that (vanilla's wait for a teleport to be confirmed), so a
 *   player who was running or jumping when it started does not run on for the moments the client takes to learn of
 *   the root;</li>
 *   <li>flying is switched off and gliding stopped;</li>
 *   <li>a vehicle is left if the player can get off it safely ({@link #mayDrop} at its dismount spot); over a fluid,
 *   lava, fire or the void the player stays on, and the vehicle is held instead (a living one rooted as the player
 *   is);</li>
 *   <li>whatever still moves the player (or the vehicle) away (momentum, knockback, a jump boost, the client's air
 *   control, paddling) is undone: once it strays from the anchor ({@link AwakeningRules#strayed}) it is put back, the
 *   rotation kept.</li>
 * </ul>
 * The player may sink or fall onto a floor: the anchor follows downward. Above a fluid, lava, fire or the void (one
 * stopped in mid-glide, say) it is held at its height instead ({@link AwakeningRules#mayDrop}). A rooted player takes
 * no fall damage ({@link #holds}, {@link AwakeningManager#onLivingFall}), and the fall distance is reset every tick,
 * so none of the fall is left for after the release. Server thread only.
 */
final class Root {
    /** The player is put back once this far (blocks) from the anchor sideways, or this far above (or below) it. */
    static final double TOLERANCE = 0.3;

    /** Id of the modifiers; the client knows a rooted player by it ({@code ClientRoot.ROOT_MODIFIER}: the same). */
    private static final UUID ID = UUID.nameUUIDFromBytes((Tremor.MODID + ":awakening_root")
            .getBytes(StandardCharsets.UTF_8));
    /** Multiplies the total by 1 + (-1) = 0, whatever other modifiers do (sprinting, speed effects). */
    private static final AttributeModifier STILL = new AttributeModifier(ID, "Tremor awakening root", -1,
            AttributeModifier.Operation.MULTIPLY_TOTAL);
    /** Players have no jump strength in 1.20.1: {@code ClientRoot} drops the jump key of a rooted player instead. */
    private static final List<Attribute> ATTRIBUTES = List.of(Attributes.MOVEMENT_SPEED);

    /** Id of the drag of the build-up ({@link #drag}), apart from the root's own modifiers. */
    private static final UUID DRAG_ID = UUID.nameUUIDFromBytes((Tremor.MODID + ":awakening_drag")
            .getBytes(StandardCharsets.UTF_8));

    /** Between {@link #start} and {@link #release}. */
    private boolean rooted;
    /** The player the build-up drags ({@link #drag}), until {@link #release}; null if none. */
    private ServerPlayer dragged;
    /** The rooted player (the object {@link #start} was given), until {@link #release}. */
    private ServerPlayer player;
    /** What is held: the player, or the vehicle the player is kept on; null before the first {@link #hold}. */
    private Entity held;
    /** Where {@link #held} is held. */
    private Vec3 anchor;

    /**
     * Roots the player where it stands (or on its vehicle, if it cannot get off safely): the modifiers are sent to it
     * at once, and a player on foot is put back right there, so that its momentum and the moves its client makes
     * before it learns of the root are gone.
     */
    void start(ServerPlayer player) {
        rooted = true;
        this.player = player;
        still(player, true);
        sync(player);
        hold(player);
        if (held == player) {
            player.connection.teleport(anchor.x, anchor.y, anchor.z, 0, 0, RelativeMovement.ROTATION);
        }
    }

    /**
     * Once per tick while rooted, with the rooted player: gets the player off a vehicle where that is safe, stops
     * flight and puts the player (or the vehicle it is kept on) back if it strayed.
     */
    void hold(ServerPlayer player) {
        if (!rooted || player != this.player) {
            return;
        }
        Level level = player.level();
        Entity vehicle = player.getVehicle();
        if (vehicle != null && mayDrop(level, vehicle.getDismountLocationForPassenger(player))) {
            player.stopRiding();
        }
        Entity what = player.getRootVehicle();
        if (what != held) {
            // Rooted now, or got onto or off a vehicle meanwhile: held where it is now.
            if (held != null && held != player) {
                still(held, false);
            }
            if (what != player) {
                still(what, true);
            }
            held = what;
            anchor = what.position();
        }
        if (what == player) {
            if (player.getAbilities().flying) {
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
            }
            if (player.isFallFlying()) {
                player.stopFallFlying();
            }
        } else {
            what.resetFallDistance();
        }
        player.resetFallDistance();
        Vec3 at = what.position();
        boolean drop = mayDrop(level, new Vec3(anchor.x, at.y, anchor.z));
        if (AwakeningRules.strayed(vec(anchor), vec(at), TOLERANCE, drop)) {
            double y = drop ? Math.min(at.y, anchor.y) : anchor.y;
            if (what == player) {
                // Rotation relative with no change: the player keeps looking around.
                player.connection.teleport(anchor.x, y, anchor.z, 0, 0, RelativeMovement.ROTATION);
            } else {
                what.setDeltaMovement(Vec3.ZERO);
                what.teleportTo(anchor.x, y, anchor.z);
                if (what.getControllingPassenger() == player) {
                    // The client moves a vehicle it steers, and ignores where the server puts it: told directly.
                    player.connection.send(new ClientboundMoveVehiclePacket(what));
                }
            }
        }
        if (drop) {
            anchor = new Vec3(anchor.x, Math.min(anchor.y, what.getY()), anchor.z);
        }
    }

    /**
     * Lets the rooted player (and a vehicle it was kept on) go, the modifiers taken off and sent to it at once; a no-op
     * if not rooted. Also for a player that has died or logged out since (its modifiers go with the object: they are
     * neither saved nor carried over to the respawned player).
     */
    void release() {
        if (dragged != null) {
            modify(dragged, DRAG_ID, null);
            dragged = null;
        }
        if (!rooted) {
            return;
        }
        rooted = false;
        still(player, false);
        sync(player);
        if (held != null && held != player) {
            still(held, false);
        }
        held = null;
        anchor = null;
        player.resetFallDistance();
        player = null;
    }

    boolean rooted() {
        return rooted;
    }

    /** The vehicle the rooted player is kept on, or null. */
    Entity vehicle() {
        return rooted && held != null && !(held instanceof ServerPlayer) ? held : null;
    }

    /** Whether the entity is held now: the rooted player on foot, or the vehicle the rooted player is kept on. */
    boolean holds(Entity entity) {
        return rooted && entity != null && entity == held;
    }

    /** {@link AwakeningRules#mayDrop} for the column at {@code at}; false if its chunk is not loaded. */
    static boolean mayDrop(Level level, Vec3 at) {
        int x = Mth.floor(at.x), z = Mth.floor(at.z);
        if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) {
            return false;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int from = Math.min(Mth.floor(at.y), level.getMaxBuildHeight() - 1);
        return AwakeningRules.mayDrop(y -> cell(level, pos.set(x, y, z)), from, level.getMinBuildHeight());
    }

    private static AwakeningRules.Cell cell(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getFluidState().is(FluidTags.LAVA) || state.is(BlockTags.FIRE)) {
            return AwakeningRules.Cell.UNSAFE;
        }
        if (!state.getCollisionShape(level, pos).isEmpty()) {
            return AwakeningRules.Cell.FLOOR;
        }
        return state.getFluidState().isEmpty() ? AwakeningRules.Cell.OPEN : AwakeningRules.Cell.UNSAFE;
    }

    /**
     * Sends the player its movement speed and jump strength now, rather than with the next round of the entity
     * tracker; not to a player that has logged out.
     */
    private static void sync(ServerPlayer player) {
        if (player.hasDisconnected()) {
            return;
        }
        List<AttributeInstance> instances = new ArrayList<>(ATTRIBUTES.size());
        for (Attribute attribute : ATTRIBUTES) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                instances.add(instance);
            }
        }
        player.connection.send(new ClientboundUpdateAttributesPacket(player.getId(), instances));
    }

    /** Adds or removes the modifiers that bring the movement speed and the jump strength of a living entity to 0. */
    /**
     * The ground of the build-up grabs at the target's feet (SPEC 9 phase 1): its movement speed and jump strength are
     * cut by {@code share} (0..1) of their total, by transient modifiers apart from the root's, until {@link #release}.
     * Called every tick of the build-up with a growing share.
     */
    void drag(ServerPlayer player, double share) {
        if (dragged != null && dragged != player) {
            modify(dragged, DRAG_ID, null);
        }
        dragged = player;
        modify(player, DRAG_ID, new AttributeModifier(DRAG_ID, "Tremor awakening drag",
                -Mth.clamp(share, 0, 1), AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    /** Puts {@code modifier} (with {@code id}) on the movement speed and jump strength of {@code entity}; null removes. */
    private static void modify(Entity entity, UUID id, AttributeModifier modifier) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        for (Attribute attribute : ATTRIBUTES) {
            AttributeInstance instance = living.getAttribute(attribute);
            if (instance != null) {
                instance.removeModifier(id);
                if (modifier != null) {
                    instance.addTransientModifier(modifier);
                }
            }
        }
    }

    private static void still(Entity entity, boolean on) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }
        for (Attribute attribute : ATTRIBUTES) {
            AttributeInstance instance = living.getAttribute(attribute);
            if (instance != null) {
                instance.removeModifier(ID);
                if (on) {
                    instance.addTransientModifier(STILL);
                }
            }
        }
    }

    private static tremor.core.math.Vec3 vec(Vec3 v) {
        return new tremor.core.math.Vec3(v.x, v.y, v.z);
    }
}
