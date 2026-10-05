package tremor.awakening;

import net.minecraft.core.BlockPos;
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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import tremor.Tremor;
import tremor.item.TremorItems;
import tremor.config.TremorConfig;
import tremor.core.math.Vec3;
import tremor.hollow.HollowDimension;
import tremor.hollow.HollowEvent;
import tremor.hollow.HollowManager;
import tremor.hollow.HollowOutcome;
import tremor.hollow.Origin;
import tremor.network.TremorBlackoutPayload;
import tremor.sound.TremorSounds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

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
 *   the Awakening goes EMERGING there ({@link Awakening#won}): the hill rises as high as the one that swallowed the
 *   player, the player is put into it while the screen is still dark and held there, the screen comes back on the
 *   view the player had as it went dark, and the hill settles and lets the player out; then the Awakening ends.</li>
 *   <li>{@link #edgeEscape}: the screen goes black, and the player fades out to a safe standing spot near the matching
 *   place of the real world with a way to the swallow point there, outside the footprint of the crater
 *   ({@link EdgeExits}, once the real chunks are loaded), alive and with everything; once the player is on the way
 *   out there, the crater opens at the swallow point all the same ({@link Craters}, without any items; none if
 *   {@code awakening.craterRadius} is 0): the node saves the base, the edge only the life. Without such a spot the
 *   player comes out at the swallow point (next to it if it is taken), and no crater opens under the player. The
 *   Awakening ends when the event does.</li>
 *   <li>{@link #defeat}: the screen goes black at once (the player is in the ground). A crater opens in the real world
 *   at the swallow point ({@link Craters}; none if {@code awakening.craterRadius} is 0); whatever the player leaves
 *   after a death goes to its bottom ({@link HollowManager#setDeathDrops}): the items into caches of rubble scattered
 *   over it, some of them buried ({@link CraterCaches}), the experience onto it. Items left while it is still being
 *   dug (a player who burns or withers to death in the meantime) wait for it, and go into its caches once it is dug;
 *   if it is called off, has no bottom, or the server stops first, they lie at the swallow point (or on the bottom) as
 *   items that never despawn. Once it is dug, if {@code awakening.lethal}, the ground kills the player (the damage type
 *   {@code tremor:swallowed}: "поглотила земля"; armour, enchantments, effects and absorption do not stop it, nor is
 *   the armour worn down by it; a totem of undying still saves the player, and creative mode); {@code keepInventory}
 *   and the curse of vanishing work as in vanilla. A player it does not kill (the soft variant, a totem, creative
 *   mode) fades out of the hollow onto the rubble of the bottom, keeping everything (no caches), and once there
 *   ({@link #onHollowEnded}) is weakened: 1 health, blind, dizzy, weak and slow for {@value #WEAKENED_TICKS} ticks,
 *   and hungry ({@value #WEAKENED_FOOD} food of 20, no saturation), so the health does not come back until the player
 *   has eaten well (vanilla regenerates nothing below 18 food, but on peaceful; the regeneration of a totem still
 *   heals), nor can the player sprint before eating something. Either way the Awakening ends then. A player who logs
 *   out while the crater is dug (or on the way out) keeps everything and is not weakened (the logout ends the event,
 *   and the Awakening as a defeat); the crater is dug all the same, and the player comes back at the swallow point on
 *   the next login, let down onto whatever is under it then (the bottom of the crater, or as far as it got;
 *   {@link HollowManager}). A defeat called off before it took the player (its event ended by {@code /tremor restore},
 *   the player brought out by {@code /tremor awaken stop} or {@code /tremor hollow leave}) takes nobody: no crater is
 *   dug (or none further), the player is neither killed nor weakened, and comes out at the swallow point the same way,
 *   let down onto what is under it.</li>
 * </ul>
 * After a victory no crater opens.
 * While a decided event is still inside (the fade, the digging, the search for the exit), the level reads
 * {@link HollowEvent#outcome} and stops.
 */
public final class Outcomes {
    /** The damage type of the ground's pull ({@code data/tremor/damage_type/swallowed.json}). */
    private static final ResourceKey<DamageType> SWALLOWED = ResourceKey.create(Registries.DAMAGE_TYPE,
            ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "swallowed"));
    /** How long a player who survives a defeat stays weakened (ticks). */
    static final int WEAKENED_TICKS = 600;
    /** Amplifier of the weakness and the slowness then (level II). */
    private static final int WEAKENED_AMPLIFIER = 1;
    /** The food a player who survives a defeat is left with (of 20): too little to sprint (over 6) or heal (18). */
    static final int WEAKENED_FOOD = 6;
    /** Events whose defeat sent the player out onto the bottom alive (weakened once there). */
    private static final Set<HollowEvent> TAKEN_OUT = Collections.newSetFromMap(new WeakHashMap<>());
    /**
     * The items the player of a defeat left after a death while its crater was still being dug, waiting for it
     * ({@link #lose}); gone once they are in its caches or lie somewhere.
     */
    private static final Map<HollowEvent, List<ItemStack>> WAITING = new WeakHashMap<>();

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
     * place of the real world, or the nearest safe one.
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
        reward(player, real, event.origin().position());
        Tremor.LOGGER.info("Outcome: {} destroyed the node, out at {}", name(player), text(event.origin().position()));
        return event;
    }

    /**
     * The node's reward (SPEC 15, stage 5): {@code items.shardsMin}..{@code shardsMax} shards of the entity, into the
     * victor's inventory (it goes out of the hollow with the player), or dropped at the swallow point if it is full.
     */
    private static void reward(ServerPlayer player, ServerLevel real, net.minecraft.world.phys.Vec3 at) {
        if (tremor.hollow.Nightmare.is(player.getUUID())) {
            tremor.hollow.Nightmare.clear(player.getUUID());
            return; // a nightmare gives nothing: getting out alive is all
        }
        int min = TremorConfig.COMMON.shardsMin.get();
        int count = min + player.getRandom().nextInt(Math.max(0, TremorConfig.COMMON.shardsMax.get() - min) + 1);
        if (count <= 0) {
            return;
        }
        ItemStack shards = new ItemStack(TremorItems.SHARD.get(), count);
        if (!player.getInventory().add(shards) && !shards.isEmpty() && real != null) {
            ItemEntity dropped = new ItemEntity(real, at.x, at.y + 0.5, at.z, shards);
            dropped.setUnlimitedLifetime();
            real.addFreshEntity(dropped);
        }
    }

    /**
     * {@link #edgeEscape}; returns the place reached, mapped into the real world. The exit is a safe spot near it
     * outside the crater's footprint, or the swallow point ({@link EdgeExits}); the player is sent out once it is
     * found, if still inside then, and the crater opens.
     */
    static net.minecraft.world.phys.Vec3 escape(ServerPlayer player, Vec3 hollowPos) throws HollowManager.Refusal {
        HollowEvent event = HollowManager.decide(player, HollowOutcome.EDGE_ESCAPE);
        net.minecraft.world.phys.Vec3 reached = event.toReal(new net.minecraft.world.phys.Vec3(hollowPos.x(),
                hollowPos.y(), hollowPos.z()));
        Tremor.LOGGER.info("Outcome: {} got to the edge, at {} of the real world", name(player), text(reached));
        ServerLevel real = player.server.getLevel(event.origin().dimension());
        if (real == null) {
            HollowManager.leave(player, null);
            return reached;
        }
        PacketDistributor.sendToPlayer(player, new TremorBlackoutPayload(true,
                TremorConfig.COMMON.hollow.fadeTicks.get()));
        MinecraftServer server = player.server;
        UUID id = player.getUUID();
        float yRot = player.getYRot();
        float xRot = player.getXRot();
        EdgeExits.find(real, event, reached, Craters.footprint(TremorConfig.COMMON.awakening.craterRadius.get()),
                exit -> getOut(server, id, event, exit == null ? null
                        : new Origin(event.origin().dimension(), exit, yRot, xRot)));
        return reached;
    }

    /**
     * {@link #defeat}; returns the event. While the crater is dug, what the player leaves after a death waits for it
     * ({@link #WAITING}).
     */
    static HollowEvent lose(ServerPlayer player) throws HollowManager.Refusal {
        HollowEvent event = HollowManager.decide(player, HollowOutcome.DEFEAT);
        PacketDistributor.sendToPlayer(player, new TremorBlackoutPayload(true,
                TremorConfig.COMMON.hollow.fadeTicks.get()));
        MinecraftServer server = player.server;
        UUID id = player.getUUID();
        ServerLevel real = server.getLevel(event.origin().dimension());
        Tremor.LOGGER.info("Outcome: the ground pulled {} in", name(player));
        if (real == null || TremorConfig.COMMON.awakening.craterRadius.get() == 0) {
            takeIn(server, id, event, null);
        } else {
            List<ItemStack> waiting = new ArrayList<>();
            WAITING.put(event, waiting);
            HollowManager.setDeathDrops(event, event.origin(), stacks -> {
                for (ItemStack stack : stacks) {
                    waiting.add(stack.copy());
                }
                return true;
            });
            Craters.dig(real, event.origin().blockPos(), "defeat of " + event.playerName(), () -> {
                if (stillWanted(event)) {
                    return true;
                }
                // Called off: nothing waits for it any more.
                HollowManager.setDeathDrops(event, event.origin(), null);
                lay(server, event, event.origin());
                return false;
            }, crater -> takeIn(server, id, event, crater));
        }
        return event;
    }

    /**
     * The player part of an event of the hollow is over (registered with {@link HollowManager#addEndListener}): a
     * player who survived a defeat and has just come out on the bottom of the crater the normal way is weakened now,
     * in the real world (in the hollow, on the way out, 1 health would not last against what is still going on there).
     * A player brought out otherwise (a command called the defeat off) is not.
     */
    public static void onHollowEnded(HollowEvent event, HollowEvent.End why) {
        boolean takenOut = TAKEN_OUT.remove(event);
        if (event.outcome() != HollowOutcome.DEFEAT || why != HollowEvent.End.LEFT || !takenOut) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(event.player());
        if (player != null && player.isAlive()) {
            weaken(player);
        }
    }

    /**
     * The exit of an escape through the edge is found ({@code exit}: null for the swallow point): the player goes out
     * there, if still inside the copy, alive, with the same event; and, unless the exit is the swallow point, the
     * crater opens there (the exit lies outside its footprint). A player gone meanwhile (a logout, a death, a command)
     * comes back at the swallow point later, so no crater opens then.
     */
    private static void getOut(MinecraftServer server, UUID id, HollowEvent event, Origin exit) {
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player == null || HollowManager.event(player) != event || !inside(event) || !player.isAlive()) {
            // Logged out, died, restored or brought out otherwise meanwhile: that ended (or ends) it.
            Tremor.LOGGER.info("Outcome: {} is gone before getting out through the edge", event.playerName());
            return;
        }
        try {
            HollowManager.leave(player, exit);
        } catch (HollowManager.Refusal refusal) {
            Tremor.LOGGER.warn("Outcome: could not get {} out through the edge: {}", name(player),
                    refusal.getMessage());
            return;
        }
        Tremor.LOGGER.info("Outcome: {} got out through the edge, out at {}", name(player), exit == null
                ? "the swallow point " + text(event.origin().position()) : text(exit.position()));
        ServerLevel real = server.getLevel(event.origin().dimension());
        if (exit == null || real == null || TremorConfig.COMMON.awakening.craterRadius.get() == 0) {
            if (exit == null && TremorConfig.COMMON.awakening.craterRadius.get() > 0) {
                Tremor.LOGGER.info("Outcome: no crater after the escape of {}: out at the swallow point",
                        name(player));
            }
            return;
        }
        Craters.dig(real, event.origin().blockPos(), "edge escape of " + event.playerName(), () -> true,
                crater -> {
                });
    }

    /**
     * Whether the crater of a defeat is still wanted: the player is still in the copy (the event entering or
     * inside), or left the event in a way that does not call the defeat off (a logout, a death, a server stop). Not once
     * the player is on the way out or out by a command ({@code /tremor awaken stop}, {@code /tremor hollow leave},
     * {@code /tremor restore}), nor after an error.
     */
    private static boolean stillWanted(HollowEvent event) {
        if (inside(event)) {
            return true;
        }
        HollowEvent.End end = event.end();
        return event.phase() == HollowEvent.Phase.CLEARING && (end == HollowEvent.End.LOGGED_OUT
                || end == HollowEvent.End.DIED || end == HollowEvent.End.SERVER_STOPPED);
    }

    /**
     * The crater of a defeat is dug ({@code crater}: null without one): whatever the player leaves after a death goes
     * to its bottom from now on (the items into its caches, if it has a bottom), and so do the items that waited for
     * it (where its caches have no place for them, they lie on the bottom, or at the swallow point without one); and
     * the defeat takes the player if still in the copy (the event entering or inside, not on its way out): dead, or on
     * the way out onto the rubble of the bottom (weakened once there, {@link #onHollowEnded}). Then the Awakening ends.
     */
    private static void takeIn(MinecraftServer server, UUID id, HollowEvent event, Craters.Crater crater) {
        net.minecraft.world.phys.Vec3 bottom = crater == null ? null : crater.bottom();
        Origin site = bottom == null ? event.origin() : new Origin(event.origin().dimension(), bottom,
                event.origin().yRot(), event.origin().xRot());
        HollowManager.setDeathDrops(event, site, bottom == null ? null : stacks -> CraterCaches.bury(crater, stacks));
        List<ItemStack> waiting = WAITING.get(event);
        if (waiting != null && !waiting.isEmpty() && bottom != null && CraterCaches.bury(crater, waiting)) {
            WAITING.remove(event);
        }
        lay(server, event, site);
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (player == null || HollowManager.event(player) != event || !inside(event)
                || !HollowDimension.is(player.level())) {
            // Logged out, brought out by a command or got out otherwise meanwhile: that ended (or ends) the event,
            // and with it the Awakening.
            Tremor.LOGGER.info("Outcome: {} is gone before the defeat took them", event.playerName());
            return;
        }
        boolean nightmare = tremor.hollow.Nightmare.is(player.getUUID());
        if (nightmare) {
            // A nightmare takes everything: nothing is left on the bottom of the crater.
            tremor.hollow.Nightmare.clear(player.getUUID());
            player.getInventory().clearContent();
            player.setExperienceLevels(0);
            player.setExperiencePoints(0);
        }
        if (player.isAlive() && (nightmare || TremorConfig.COMMON.awakening.lethal.get())) {
            player.hurt(new DamageSource(server.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE)
                    .getHolderOrThrow(SWALLOWED)), AwakeningRules.pullDamage(player.getHealth(),
                    player.getAbsorptionAmount()));
        }
        if (player.isDeadOrDying()) {
            // The items went into the caches on the bottom (CraterCaches), the experience onto it (HollowRules); the
            // event ends when the player respawns.
            AwakeningManager.defeated(event, name(player) + " was swallowed by the ground");
            return;
        }
        try {
            HollowManager.leave(player, new Origin(site.dimension(), site.position(), player.getYRot(),
                    player.getXRot()));
            TAKEN_OUT.add(event);
        } catch (HollowManager.Refusal refusal) {
            Tremor.LOGGER.warn("Outcome: could not get {} out to the crater: {}", name(player),
                    refusal.getMessage());
        }
        AwakeningManager.defeated(event, name(player) + " survived, out to the bottom of the crater");
    }

    /** Whether the event's player is (still) in the copy, not on the way out: entering or inside. */
    private static boolean inside(HollowEvent event) {
        return event.phase() == HollowEvent.Phase.ENTERING || event.phase() == HollowEvent.Phase.INSIDE;
    }

    /**
     * The server stops while craters of defeats are dug (they stay as far as they got): the items waiting for them lie
     * at their swallow points, while the levels are still there to keep them.
     */
    public static void onServerStopping(ServerStoppingEvent event) {
        for (HollowEvent waiting : List.copyOf(WAITING.keySet())) {
            lay(event.getServer(), waiting, waiting.origin());
        }
        WAITING.clear();
    }

    /**
     * Lays the items that waited for the crater of the defeat of {@code event}, if any are left, at {@code at}: as items
     * that never despawn (the player may be far away, or offline), its chunk loaded so they are kept.
     */
    private static void lay(MinecraftServer server, HollowEvent event, Origin at) {
        List<ItemStack> waiting = WAITING.remove(event);
        ServerLevel level = server.getLevel(at.dimension());
        if (waiting == null || waiting.isEmpty() || level == null) {
            return;
        }
        net.minecraft.world.phys.Vec3 pos = at.position();
        level.getChunkAt(BlockPos.containing(pos));
        for (ItemStack stack : waiting) {
            ItemEntity item = new ItemEntity(level, pos.x, pos.y, pos.z, stack, 0, 0, 0);
            item.setUnlimitedLifetime();
            level.addFreshEntity(item);
        }
        Tremor.LOGGER.info("Outcome: {} stacks of {} that waited for the crater lie at {}", waiting.size(),
                event.playerName(), text(pos));
    }

    /**
     * The soft side of a defeat: 1 health, blind, dizzy, weak and slow for a while, and hungry, so that the health
     * does not come back by itself before the player eats.
     */
    private static void weaken(ServerPlayer player) {
        player.setHealth(1);
        player.getFoodData().setFoodLevel(Math.min(player.getFoodData().getFoodLevel(), WEAKENED_FOOD));
        player.getFoodData().setSaturation(0);
        player.getFoodData().setExhaustion(0);
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
