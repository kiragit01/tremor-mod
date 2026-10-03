package tremor.awakening;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import tremor.Tremor;
import tremor.config.TremorConfig;
import tremor.core.math.Vec3;
import tremor.hollow.HollowDimension;
import tremor.hollow.HollowEvent;
import tremor.hollow.HollowManager;
import tremor.hollow.HollowOutcome;
import tremor.hollow.Origin;
import tremor.network.TremorBlackoutPayload;
import tremor.sound.TremorSounds;

import java.util.Locale;
import java.util.UUID;

/**
 * How an Awakening ends for a player in the hollow (SPEC 9 "Исходы"). Called by the level inside the hollow when the
 * player wins, gets out through the edge or is pulled in; each call decides the outcome of the player's event in the
 * hollow ({@link HollowManager#decide}: once, while the player is alive in the copy; a call after that does nothing,
 * nor does one for a player who is not there, which is logged) and carries it out. The player's Awakening, if the
 * event is one's, ends with that outcome; an event without one ({@code /tremor hollow enter}) has the same outcomes,
 * only no Awakening to end. Server thread only.
 * <ul>
 *   <li>{@link #victory}: the player fades out of the hollow to the swallow point (next to it if it is taken,
 *   {@link HollowManager#leave}); the ground lets go (the RELEASE sound, in the hollow and at the swallow point) and
 *   the Awakening goes EMERGING there: the hill rises, the player comes out of it, it settles, and the Awakening ends
 *   ({@link Awakening#emerge}).</li>
 *   <li>{@link #edgeEscape}: the player fades out to the matching place of the real world (next to it if it is taken);
 *   the Awakening ends when the event does.</li>
 *   <li>{@link #defeat}: the screen goes black at once (the player is in the ground). A sinkhole opens in the real
 *   world at the swallow point ({@link Sinkholes}; none if {@code awakening.sinkholeRadius} is 0); once it is there,
 *   whatever the player leaves after a death goes to its bottom ({@link HollowManager#setDeathDrops}). Then, if
 *   {@code awakening.lethal}, the ground kills the player (the damage type {@code tremor:tremor}: "поглотила земля"),
 *   and the death drops (items, experience) land on the bottom. A player it does not kill (the soft variant, a
 *   totem of undying, creative mode) fades out of the hollow onto the bottom, keeping everything, and once there
 *   ({@link #onHollowEnded}) has 1 health and is blind, dizzy, weak and slow for {@value #WEAKENED_TICKS} ticks.
 *   Either way the Awakening ends then. A player who logs out while the sinkhole is dug (or on the way out) keeps
 *   everything and is not weakened (the logout ends the event, and the Awakening as a defeat); the sinkhole is dug
 *   all the same.</li>
 * </ul>
 * While a decided event is still inside (the fade, the digging), the level reads {@link HollowEvent#outcome} and stops.
 */
public final class Outcomes {
    /** The damage type of the ground ({@code data/tremor/damage_type/tremor.json}). */
    private static final ResourceKey<DamageType> GROUND = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "tremor"));
    /** How long a player who survives a defeat stays weakened (ticks). */
    static final int WEAKENED_TICKS = 600;
    /** Amplifier of the weakness and the slowness then (level II). */
    private static final int WEAKENED_AMPLIFIER = 1;

    private Outcomes() {
    }

    /** Victory: the player destroyed the node (SPEC 9 "Победа"). */
    public static void victory(ServerPlayer player) {
        if (decided(player)) {
            return;
        }
        try {
            win(player);
        } catch (HollowManager.Refusal refusal) {
            refused(player, HollowOutcome.VICTORY, refusal);
        }
    }

    /**
     * Escape through the edge of the hollow before it closed (SPEC 9 "Побег"): the player comes out at the matching
     * place of the real world.
     *
     * @param hollowPos where the player reached the edge, in the hollow's coordinates
     */
    public static void edgeEscape(ServerPlayer player, Vec3 hollowPos) {
        if (decided(player)) {
            return;
        }
        try {
            escape(player, hollowPos);
        } catch (HollowManager.Refusal refusal) {
            refused(player, HollowOutcome.EDGE_ESCAPE, refusal);
        }
    }

    /** Defeat: the soft ground pulled the player in completely (SPEC 9 "Поражение"). */
    public static void defeat(ServerPlayer player) {
        if (decided(player)) {
            return;
        }
        try {
            lose(player);
        } catch (HollowManager.Refusal refusal) {
            refused(player, HollowOutcome.DEFEAT, refusal);
        }
    }

    // ---- the outcomes, refused with the reason (for /tremor hollow outcome) ----

    /** {@link #victory}; returns the event. */
    static HollowEvent win(ServerPlayer player) throws HollowManager.Refusal {
        HollowEvent event = HollowManager.decide(player, HollowOutcome.VICTORY);
        HollowManager.leave(player, null);
        float volume = (float) TremorConfig.COMMON.transitionVolume.getAsDouble();
        player.serverLevel().playSound(null, player.getX(), player.getY(), player.getZ(), TremorSounds.RELEASE,
                SoundSource.HOSTILE, volume, 1);
        ServerLevel real = player.server.getLevel(event.origin().dimension());
        if (real != null) {
            net.minecraft.world.phys.Vec3 at = event.origin().position();
            real.playSound(null, at.x, at.y, at.z, TremorSounds.RELEASE, SoundSource.HOSTILE, volume, 1);
        }
        AwakeningManager.victory(event);
        Tremor.LOGGER.info("Outcome: {} destroyed the node, out at {}", name(player), text(event.origin().position()));
        return event;
    }

    /** {@link #edgeEscape}; returns where the player comes out. */
    static Origin escape(ServerPlayer player, Vec3 hollowPos) throws HollowManager.Refusal {
        HollowEvent event = HollowManager.decide(player, HollowOutcome.EDGE_ESCAPE);
        Origin exit = new Origin(event.origin().dimension(), event.toReal(new net.minecraft.world.phys.Vec3(
                hollowPos.x(), hollowPos.y(), hollowPos.z())), player.getYRot(), player.getXRot());
        HollowManager.leave(player, exit);
        Tremor.LOGGER.info("Outcome: {} got out through the edge, out at {}", name(player), text(exit.position()));
        return exit;
    }

    /** {@link #defeat}; returns the event. */
    static HollowEvent lose(ServerPlayer player) throws HollowManager.Refusal {
        HollowEvent event = HollowManager.decide(player, HollowOutcome.DEFEAT);
        PacketDistributor.sendToPlayer(player, new TremorBlackoutPayload(true,
                TremorConfig.COMMON.hollow.fadeTicks.get()));
        MinecraftServer server = player.server;
        UUID id = player.getUUID();
        ServerLevel real = server.getLevel(event.origin().dimension());
        Tremor.LOGGER.info("Outcome: the ground pulled {} in", name(player));
        if (real == null || TremorConfig.COMMON.awakening.sinkholeRadius.get() == 0) {
            takeIn(server, id, event, null);
        } else {
            Sinkholes.dig(real, event.origin().blockPos(), bottom -> takeIn(server, id, event, bottom));
        }
        return event;
    }

    /**
     * The player part of an event of the hollow is over (registered with {@link HollowManager#addEndListener}): a
     * player who survived a defeat and has just come out on the bottom of the sinkhole the normal way is weakened now,
     * in the real world (in the hollow, on the way out, 1 health would not last against what is still going on there).
     */
    public static void onHollowEnded(HollowEvent event, HollowEvent.End why) {
        if (event.outcome() != HollowOutcome.DEFEAT || why != HollowEvent.End.LEFT) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(event.player());
        if (player != null && player.isAlive()) {
            weaken(player);
        }
    }

    /**
     * The sinkhole of a defeat is there ({@code bottom}: its bottom, null without one): whatever the player leaves
     * after a death goes there from now on, and the defeat takes the player if still in the copy: dead, or on the way
     * out to the bottom (weakened once there, {@link #onHollowEnded}). Then the Awakening ends.
     */
    private static void takeIn(MinecraftServer server, UUID id, HollowEvent event,
                               net.minecraft.world.phys.Vec3 bottom) {
        Origin site = bottom == null ? event.origin() : new Origin(event.origin().dimension(), bottom,
                event.origin().yRot(), event.origin().xRot());
        HollowManager.setDeathDrops(event, site);
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player == null || HollowManager.event(player) != event || !HollowDimension.is(player.level())) {
            // Logged out (or got out otherwise) meanwhile: that ended the event, and with it the Awakening.
            Tremor.LOGGER.info("Outcome: {} is gone before the defeat took them", event.playerName());
            return;
        }
        if (player.isAlive() && TremorConfig.COMMON.awakening.lethal.get()) {
            player.hurt(new DamageSource(server.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(GROUND)), Float.MAX_VALUE);
        }
        if (player.isDeadOrDying()) {
            // The death drops went to the bottom (HollowRules); the event ends when the player respawns.
            AwakeningManager.defeated(event, name(player) + " was swallowed by the ground");
            return;
        }
        try {
            HollowManager.leave(player, new Origin(site.dimension(), site.position(), player.getYRot(),
                    player.getXRot()));
        } catch (HollowManager.Refusal refusal) {
            Tremor.LOGGER.warn("Outcome: could not get {} out to the sinkhole: {}", name(player),
                    refusal.getMessage());
        }
        AwakeningManager.defeated(event, name(player) + " survived, out to the bottom of the sinkhole");
    }

    /** The soft side of a defeat: 1 health, blind, dizzy, weak and slow for a while. */
    private static void weaken(ServerPlayer player) {
        player.setHealth(1);
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, WEAKENED_TICKS, 0));
        player.addEffect(new MobEffectInstance(MobEffects.CONFUSION, WEAKENED_TICKS, 0));
        player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, WEAKENED_TICKS, WEAKENED_AMPLIFIER));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, WEAKENED_TICKS, WEAKENED_AMPLIFIER));
    }

    /** Whether the outcome of the player's event is decided (the level may ask again while it is carried out). */
    private static boolean decided(ServerPlayer player) {
        HollowEvent event = HollowManager.event(player);
        return event != null && event.outcome() != null;
    }

    private static void refused(ServerPlayer player, HollowOutcome outcome, HollowManager.Refusal refusal) {
        Tremor.LOGGER.warn("Outcome {} for {} refused: {}", outcome.id(), name(player), refusal.getMessage());
    }

    private static String name(ServerPlayer player) {
        return player.getGameProfile().getName();
    }

    private static String text(net.minecraft.world.phys.Vec3 v) {
        return String.format(Locale.ROOT, "%.1f %.1f %.1f", v.x, v.y, v.z);
    }
}
