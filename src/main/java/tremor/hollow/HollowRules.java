package tremor.hollow;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorStandItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.BottleItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.EndCrystalItem;
import net.minecraft.world.item.HangingEntityItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.item.PlaceOnWaterBlockItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingExperienceDropEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockDropsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import tremor.Tremor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What is different inside the hollow (SPEC 9, 12). Everything there is a copy, and a copy is a prop: nothing can be
 * gained from it. Everything the player brings in stays the player's: nothing of it is lost there.
 * <ul>
 *   <li>The copy yields nothing: its broken blocks drop nothing (explosions included), nor do its falling blocks
 *   where they cannot land; tools do not change it (no stripping, no tilling, no roots from rooted dirt), bone meal
 *   does not grow it, buckets and bottles take nothing from it, fishing catches nothing.</li>
 *   <li>Blocks with a block entity, and the blocks of {@link #PROPS}, are props: using them does nothing (no container
 *   or workstation screens, no putting items in or taking them out, no cake; beds and respawn anchors do not
 *   explode). The block entities of the copy never tick ({@link TerrainCopier}), so hoppers, comparators, furnaces,
 *   spawners and sculk do nothing either.</li>
 *   <li>What the player places in the area of the player's event (fluids poured from buckets included) is the
 *   player's ({@link PlayerBlocks}): it works and drops as anywhere else, and what is left of it when the event ends
 *   is given back ({@link HollowManager}). Placing outside that area, into a block of the copy, {@link #UNPLACEABLE}
 *   blocks, and items that become an entity (boats, minecarts, armor stands, item frames, paintings, end crystals,
 *   spawn eggs, mobs in buckets) are refused, so the item stays with the player. Pistons do not move the player's
 *   blocks (the record of them would stay behind).</li>
 *   <li>No mob joins it (no natural spawning, no hatching, no golems, no endermites); a tamed creature of the player
 *   (a parrot leaving the player's shoulder) is put at the place the player comes back to instead. No lightning.</li>
 *   <li>Nothing crosses into or out of it but the moves of {@link HollowManager}: portals, ender pearls, commands and
 *   other mods' teleports are cancelled.</li>
 *   <li>A player who dies inside during an event drops everything, the crafting grid and the carried stack included,
 *   at the place the player comes back to; one who logs out inside keeps the crafting grid and the carried stack.</li>
 * </ul>
 * Event handlers are registered by {@link tremor.Tremor}.
 */
public final class HollowRules {
    /**
     * Blocks without a block entity that are props in the hollow all the same: ones that take items in (composter,
     * flower pots, cauldrons), workstations (crafting table, anvils, stonecutter...), ones that would explode (respawn
     * anchor), or hand out copies (berry bushes, glow berries, pumpkins to carve, cake).
     */
    public static final TagKey<Block> PROPS =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "hollow_props"));
    /**
     * Blocks a player may not place in the hollow: automation that would take from the copy (hoppers, droppers,
     * dispensers, crafters), heads that would build a golem or a wither, and eggs and spawn that would hatch (mobs
     * cannot live there, so what went into them would be lost).
     */
    public static final TagKey<Block> UNPLACEABLE =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "hollow_unplaceable"));
    /** Persistent data of a falling block of the player ({@link #onEntityJoinLevel}). */
    private static final String PLAYERS_FALLING = Tremor.MODID + ":players_falling";

    /** Destructive explosions in the hollow of this tick, their copied blocks to remove ({@link #onExplosion}). */
    private static final List<Blast> BLASTS = new ArrayList<>();
    /** Positions of the hollow to look at again at the end of this tick ({@link #onServerTick}). */
    private static final Map<BlockPos, Recheck> RECHECKS = new LinkedHashMap<>();

    private HollowRules() {
    }

    private record Blast(ServerLevel level, Explosion explosion, List<BlockPos> blocks) {
    }

    /**
     * @param before the state before the placing or the use, null for {@link PlayerBlocks.Change#CHANGED}
     * @param poured the fluid a bucket poured, or null
     */
    private record Recheck(ServerLevel level, PlayerBlocks.Change change, BlockState before, Fluid poured) {
    }

    // ---- using things ----

    /**
     * Both sides, so the client does not show a use the server refuses: the props of the copy are not used (the
     * client cannot tell the player's own blocks, so it holds back and the server uses those), and items that would
     * become an entity, place an {@link #UNPLACEABLE} block, grow the copy (bone meal) or open an end portal in it
     * (eyes of ender) are not used on blocks. Other items work, on props too.
     */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (!HollowDimension.is(level)) {
            return;
        }
        BlockState state = level.getBlockState(event.getPos());
        boolean players = level instanceof ServerLevel server && HollowManager.isPlayerPlaced(server, event.getPos());
        if (!players && (state.hasBlockEntity() || state.is(PROPS))) {
            event.setUseBlock(TriState.FALSE);
        }
        ItemStack stack = event.getItemStack();
        if (becomesEntity(stack) || placesUnplaceable(stack) || !players && stack.getItem() instanceof BoneMealItem
                || stack.is(Items.ENDER_EYE) && state.is(Blocks.END_PORTAL_FRAME)) {
            event.setUseItem(TriState.FALSE);
        }
    }

    /**
     * Items that would become an entity or place an {@link #UNPLACEABLE} block (frogspawn) are refused on both sides.
     * Buckets and bottles take only the player's own fluids; a bucket pours, and a lily pad goes, only into the area
     * of the player's event, and a bucket never into a block of the copy (it would keep the fluid). Only the server
     * knows the player's blocks and the area, so the client shows the use and is given its inventory back if the
     * server refuses (the block it changed is put back by the server's acknowledgement).
     */
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        Level level = event.getLevel();
        if (!HollowDimension.is(level)) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (becomesEntity(stack) || placesUnplaceable(stack)) {
            refuse(event);
        } else if (level instanceof ServerLevel server && event.getEntity() instanceof ServerPlayer player
                && !mayUse(server, player, stack.getItem())) {
            refuse(event);
            player.containerMenu.sendAllDataToRemote();
        }
    }

    /**
     * Tools do not change the copy (stripping, tilling, paths, scraping, and the hanging roots rooted dirt pops when
     * tilled); the player's own blocks change as anywhere. Both sides; the client holds back as for props.
     */
    public static void onToolModification(BlockEvent.BlockToolModificationEvent event) {
        if (event.getLevel() instanceof Level level && HollowDimension.is(level)
                && !(level instanceof ServerLevel server && HollowManager.isPlayerPlaced(server, event.getPos()))) {
            event.setCanceled(true);
        }
    }

    /** Fishing in the copy catches nothing (its water holds no loot). */
    public static void onItemFished(ItemFishedEvent event) {
        if (HollowDimension.is(event.getHookEntity().level())) {
            event.setCanceled(true);
        }
    }

    // ---- the player's blocks ----

    /**
     * A block a player places in the hollow (with a block item, or with anything used on a block that changes blocks:
     * a tool, bone meal, wax, flint and steel) must lie in the area of the player's running event, must not be
     * {@link #UNPLACEABLE}, and must not go into a block of the copy ({@link PlayerBlocks#mayPlace}); otherwise it is
     * refused, and the item stays with the player (the client, which showed the placing, is given its inventory back).
     */
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)
                || !HollowDimension.is(level)) {
            return;
        }
        for (BlockSnapshot snapshot : snapshots(event)) {
            BlockPos pos = snapshot.getPos();
            BlockState before = snapshot.getState();
            BlockState now = level.getBlockState(pos);
            PlayerBlocks.Replaced replaced;
            if (HollowManager.isPlayerPlaced(level, pos)) {
                replaced = PlayerBlocks.Replaced.PLAYERS;
            } else if (before.canBeReplaced() && !before.is(now.getBlock())) {
                replaced = PlayerBlocks.Replaced.NOTHING;
            } else {
                replaced = PlayerBlocks.Replaced.COPY;
            }
            if (!PlayerBlocks.mayPlace(inOwnEvent(level, player, pos), now.is(UNPLACEABLE), replaced)) {
                event.setCanceled(true);
                player.containerMenu.sendAllDataToRemote();
                return;
            }
        }
    }

    /**
     * ...and a block that was placed is the player's from now on. Lowest priority, after everyone else had a say; it
     * is looked at again at the end of the tick all the same, in case a listener after this one put the copy back.
     */
    public static void onBlockPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer && event.getLevel() instanceof ServerLevel level
                && HollowDimension.is(level)) {
            for (BlockSnapshot snapshot : snapshots(event)) {
                mark(level, snapshot.getPos());
                recheck(level, snapshot.getPos(), PlayerBlocks.Change.PLACED, snapshot.getState(), null);
            }
        }
    }

    /**
     * A block of the player changed (broken, burnt, fallen, picked up, grown...): it is looked at again at the end of
     * the tick, once the drops of a break went by (they still find it recorded).
     */
    public static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (event.getLevel() instanceof ServerLevel level && HollowDimension.is(level)
                && HollowManager.isPlayerPlaced(level, event.getPos())) {
            recheck(level, event.getPos(), PlayerBlocks.Change.CHANGED, null, null);
        }
    }

    /**
     * Blocks of the copy broken by players, water, pistons, decay... drop nothing (this also cancels their experience
     * and {@code spawnAfterBreak}: no silverfish); the player's own blocks drop as anywhere.
     */
    public static void onBlockDrops(BlockDropsEvent event) {
        if (HollowDimension.is(event.getLevel()) && !HollowManager.isPlayerPlaced(event.getLevel(), event.getPos())) {
            event.setCanceled(true);
        }
    }

    /**
     * Pistons do not move the player's blocks in the hollow: the record of the player's blocks would stay behind.
     * Pushing them out of the way (breaking them, so they drop) works.
     */
    public static void onPistonMove(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !HollowDimension.is(level)) {
            return;
        }
        // A piston that is not sticky pulls nothing back.
        if (!event.getPistonMoveType().isExtend && !level.getBlockState(event.getPos()).is(Blocks.STICKY_PISTON)) {
            return;
        }
        PistonStructureResolver structure = event.getStructureHelper();
        if (structure == null || !structure.resolve()) {
            return;
        }
        for (BlockPos pos : structure.getToPush()) {
            if (HollowManager.isPlayerPlaced(level, pos)) {
                event.setCanceled(true);
                return;
            }
        }
    }

    /**
     * Explosion drops never pass {@link BlockDropsEvent}. The blocks of the copy a destructive explosion in the hollow
     * would break are taken away from it here, after the vanilla handlers saw them (lowest priority), and removed at
     * the end of the tick without drops: removing them now would change the damage the explosion deals, which is
     * computed next with the blocks still in place. The player's own blocks stay with the explosion and drop.
     */
    public static void onExplosion(ExplosionEvent.Detonate event) {
        Explosion explosion = event.getExplosion();
        Explosion.BlockInteraction interaction = explosion.getBlockInteraction();
        if (event.getLevel() instanceof ServerLevel level && HollowDimension.is(level)
                && (interaction == Explosion.BlockInteraction.DESTROY
                || interaction == Explosion.BlockInteraction.DESTROY_WITH_DECAY)) {
            List<BlockPos> copies = new ArrayList<>();
            for (Iterator<BlockPos> blocks = event.getAffectedBlocks().iterator(); blocks.hasNext(); ) {
                BlockPos pos = blocks.next();
                if (!HollowManager.isPlayerPlaced(level, pos)) {
                    copies.add(pos);
                    blocks.remove();
                }
            }
            BLASTS.add(new Blast(level, explosion, copies));
        }
    }

    /**
     * Removes the copied blocks of this tick's explosions the way an explosion does, minus the drops (TNT still
     * primes); then brings the record of the player's blocks up to date with this tick's changes.
     */
    public static void onServerTick(ServerTickEvent.Post event) {
        for (Blast blast : BLASTS) {
            for (BlockPos pos : blast.blocks()) {
                BlockState state = blast.level().getBlockState(pos);
                if (!state.isAir()) {
                    state.onBlockExploded(blast.level(), pos, blast.explosion());
                }
            }
        }
        BLASTS.clear();
        if (RECHECKS.isEmpty()) {
            return;
        }
        Map<BlockPos, Recheck> due = new LinkedHashMap<>(RECHECKS);
        RECHECKS.clear();
        due.forEach(HollowRules::recheckNow);
    }

    /** Nothing of a stopped server is kept. */
    public static void onServerStopped(ServerStoppedEvent event) {
        BLASTS.clear();
        RECHECKS.clear();
    }

    // ---- what joins and leaves ----

    public static void onPotentialSpawns(LevelEvent.PotentialSpawns event) {
        if (event.getLevel() instanceof Level level && HollowDimension.is(level)) {
            event.setCanceled(true);
        }
    }

    /**
     * What joins the hollow (not what is loaded with its chunks, nor what {@link HollowManager} moves): no lightning
     * (the weather is the overworld's, and a copied biome can rain); a falling block of the copy drops nothing where it
     * cannot land, one of the player's is followed to where it lands ({@link #onEntityLeaveLevel}); no mob (natural
     * spawning is stopped above; this stops hatching, golems, endermites, commands), but a tamed creature of a player
     * is put at the place its owner comes back to ({@link #sendHome}). Spawn eggs and mobs in buckets are refused
     * before they are used ({@link #becomesEntity}).
     */
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !HollowDimension.is(level) || event.loadedFromDisk()
                || HollowManager.isMoving(event.getEntity())) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof LightningBolt) {
            event.setCanceled(true);
        } else if (entity instanceof FallingBlockEntity falling) {
            // Joins as it starts to fall, from the block's own position, still recorded until the end of the tick.
            if (HollowManager.isPlayerPlaced(level, falling.blockPosition())) {
                falling.getPersistentData().putBoolean(PLAYERS_FALLING, true);
            } else {
                falling.dropItem = false;
            }
        } else if (entity instanceof Mob mob) {
            if (mob instanceof OwnableEntity pet && pet.getOwnerUUID() != null) {
                sendHome(mob, pet.getOwnerUUID(), level.getServer());
            }
            event.setCanceled(true);
        }
    }

    /** A falling block of the player that landed is the player's where it landed. */
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof FallingBlockEntity falling && event.getLevel() instanceof ServerLevel level
                && HollowDimension.is(level) && falling.getRemovalReason() == Entity.RemovalReason.DISCARDED
                && falling.getPersistentData().getBoolean(PLAYERS_FALLING)
                && level.getBlockState(falling.blockPosition()).is(falling.getBlockState().getBlock())) {
            mark(level, falling.blockPosition());
        }
    }

    /**
     * Nothing crosses into or out of the hollow but the moves of {@link HollowManager} (SPEC 9: nobody follows the
     * player in; SPEC 12: the real world does not change during the event): portals (a copied one would lead into the
     * real Nether or End and build a portal there), commands, other mods' teleports, ender pearls. {@code /tremor
     * hollow leave} and {@code /tremor restore} get a player out.
     */
    public static void onTravelToDimension(EntityTravelToDimensionEvent event) {
        Entity entity = event.getEntity();
        if (HollowDimension.is(entity.level()) != (event.getDimension() == HollowDimension.KEY)
                && !HollowManager.isMoving(entity)) {
            event.setCanceled(true);
        }
    }

    /**
     * An ender pearl does not pull its owner across the edge of the hollow (one thrown before a move that lands after
     * it), nor hurts the owner or brings an endermite for nothing. Within the hollow it works.
     */
    public static void onEnderPearl(EntityTeleportEvent.EnderPearl event) {
        Level pearl = event.getPearlEntity().level();
        Level owner = event.getPlayer().level();
        if (pearl != owner && (HollowDimension.is(pearl) || HollowDimension.is(owner))) {
            event.setCanceled(true);
        }
    }

    // ---- death and logout ----

    /**
     * The items of a player who dies inside the hollow during an event go to the place the player comes back to, with
     * what was in the crafting grids and on the cursor (vanilla drops those later, at the player's feet, where they
     * would be swept away with the copy, or fall into the void).
     */
    public static void onLivingDrops(LivingDropsEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            HollowManager.DropSite site = HollowManager.dropSite(player);
            if (site != null) {
                for (ItemEntity drop : event.getDrops()) {
                    dropAt(site, drop.getItem().copy());
                }
                for (ItemStack loose : takeLoose(player)) {
                    dropAt(site, loose);
                }
                event.setCanceled(true);
            }
        }
    }

    /** ...and so does the experience. */
    public static void onExperienceDrop(LivingExperienceDropEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            HollowManager.DropSite site = HollowManager.dropSite(player);
            if (site != null) {
                ExperienceOrb.award(site.level(), site.position(), event.getDroppedExperience());
                event.setCanceled(true);
            }
        }
    }

    /**
     * A player who logs out inside the hollow keeps what is in the crafting grids and on the cursor (vanilla drops it
     * at the player's feet, where it would be swept away with the copy): it goes back into the inventory before the
     * player is saved, what does not fit to the place the player comes back to. High priority: before
     * {@link HollowManager#onPlayerLoggedOut} ends the event.
     */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !HollowDimension.is(player.level())
                || !player.isAlive()) {
            return;
        }
        HollowManager.DropSite site = HollowManager.dropSite(player);
        for (ItemStack stack : takeLoose(player)) {
            player.getInventory().add(stack);
            if (!stack.isEmpty()) {
                if (site != null) {
                    dropAt(site, stack);
                } else {
                    player.drop(stack, false);
                }
            }
        }
    }

    // ---- internals ----

    /** Items that become an entity when used: it would be swept away with the copy, with what it holds. */
    private static boolean becomesEntity(ItemStack stack) {
        Item item = stack.getItem();
        return item instanceof BoatItem || item instanceof MinecartItem || item instanceof ArmorStandItem
                || item instanceof HangingEntityItem || item instanceof EndCrystalItem || item instanceof SpawnEggItem
                || item instanceof MobBucketItem;
    }

    private static boolean placesUnplaceable(ItemStack stack) {
        return stack.getItem() instanceof BlockItem item && item.getBlock().defaultBlockState().is(UNPLACEABLE);
    }

    private static void refuse(PlayerInteractEvent.RightClickItem event) {
        event.setCancellationResult(InteractionResult.FAIL);
        event.setCanceled(true);
    }

    /**
     * Whether {@code player} may use {@code item} (not on a block: buckets, bottles and lily pads aim on their own) on
     * what it aims at: an empty bucket or a glass bottle takes only from the player's own blocks (a bottle fills from
     * water without taking it, so from the player's water only); a full bucket pours only into the area of the
     * player's event, and not into a block of the copy that would keep the fluid (a slab it waterlogs); a lily pad or
     * frogspawn goes only into that area. Where a bucket pours or a lily pad goes is followed
     * ({@link PlayerBlocks.Change#USED}): that use fires no placing event. True for every other item.
     */
    private static boolean mayUse(ServerLevel level, ServerPlayer player, Item item) {
        if (item instanceof BucketItem bucket) {
            Fluid content = bucket.content;
            BlockHitResult hit = Item.getPlayerPOVHitResult(level, player,
                    content == Fluids.EMPTY ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE);
            if (hit.getType() != HitResult.Type.BLOCK) {
                return true;
            }
            BlockPos at = hit.getBlockPos();
            if (content == Fluids.EMPTY) {
                return !(level.getBlockState(at).getBlock() instanceof BucketPickup)
                        || HollowManager.isPlayerPlaced(level, at);
            }
            // Where BucketItem.use pours: into the block hit if it takes the fluid, else in front of it.
            BlockPos into = takesFluid(level, player, at, content) ? at : at.relative(hit.getDirection());
            if (!inOwnEvent(level, player, into)
                    || takesFluid(level, player, into, content) && !HollowManager.isPlayerPlaced(level, into)) {
                return false;
            }
            recheck(level, into, PlayerBlocks.Change.USED, level.getBlockState(into), content);
            return true;
        }
        if (item instanceof PlaceOnWaterBlockItem) {
            BlockHitResult hit = Item.getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
            if (hit.getType() != HitResult.Type.BLOCK) {
                return true;
            }
            // Where PlaceOnWaterBlockItem.use places: on top of the water aimed at.
            BlockPos on = hit.getBlockPos().above();
            if (!inOwnEvent(level, player, on)) {
                return false;
            }
            recheck(level, on, PlayerBlocks.Change.USED, level.getBlockState(on), null);
            return true;
        }
        if (item instanceof BottleItem) {
            BlockHitResult hit = Item.getPlayerPOVHitResult(level, player, ClipContext.Fluid.SOURCE_ONLY);
            return hit.getType() != HitResult.Type.BLOCK || !level.getFluidState(hit.getBlockPos()).is(FluidTags.WATER)
                    || HollowManager.isPlayerPlaced(level, hit.getBlockPos());
        }
        return true;
    }

    /** Whether the block at {@code pos} would keep {@code fluid} poured into it (waterlogging), as a bucket asks. */
    private static boolean takesFluid(Level level, Player player, BlockPos pos, Fluid fluid) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof LiquidBlockContainer container
                && container.canPlaceLiquid(player, level, pos, state, fluid);
    }

    /** Whether {@code pos} lies in the area of the running event {@code player} is inside of. */
    private static boolean inOwnEvent(ServerLevel level, ServerPlayer player, BlockPos pos) {
        HollowEvent own = HollowManager.event(player);
        return own != null && own.phase().inHollow() && own == HollowManager.eventAt(level, pos);
    }

    private static List<BlockSnapshot> snapshots(BlockEvent.EntityPlaceEvent event) {
        return event instanceof BlockEvent.EntityMultiPlaceEvent multi ? multi.getReplacedBlockSnapshots()
                : List.of(event.getBlockSnapshot());
    }

    /** Records the block at {@code pos} as the player's while the event there runs (after that it was given back). */
    private static void mark(ServerLevel level, BlockPos pos) {
        HollowEvent event = HollowManager.eventAt(level, pos);
        if (event != null && event.phase().inHollow()) {
            HollowManager.markPlaced(level, pos);
        }
    }

    /** Looks at {@code pos} again at the end of the tick; a placing or a use outweighs a change. */
    private static void recheck(ServerLevel level, BlockPos pos, PlayerBlocks.Change change, BlockState before,
                                Fluid poured) {
        RECHECKS.merge(pos.immutable(), new Recheck(level, change, before, poured),
                (old, now) -> now.change() == PlayerBlocks.Change.CHANGED ? old : now);
    }

    private static void recheckNow(BlockPos pos, Recheck recheck) {
        ServerLevel level = recheck.level();
        BlockState now = level.getBlockState(pos);
        boolean sourceOfPoured = recheck.poured() != null && now.getBlock() instanceof LiquidBlock
                && now.getFluidState().isSource() && now.getFluidState().getType().isSame(recheck.poured());
        switch (PlayerBlocks.afterTick(recheck.change(), HollowManager.isPlayerPlaced(level, pos),
                now == recheck.before(), holdsPlayers(now), sourceOfPoured)) {
            case MARK -> mark(level, pos);
            case UNMARK -> HollowManager.unmarkPlaced(level, pos);
            case NONE -> {
            }
        }
    }

    /**
     * Whether {@code state} can be (what became of) a block of the player: not air, fire, a block on its way from a
     * piston, or fluid flowing in after the block went.
     */
    private static boolean holdsPlayers(BlockState state) {
        return !state.isAir() && !(state.getBlock() instanceof BaseFireBlock) && !state.is(Blocks.MOVING_PISTON)
                && !(state.getBlock() instanceof LiquidBlock && !state.getFluidState().isSource());
    }

    /**
     * Takes out what is in the player's crafting grids and on the cursor: the open menu and the inventory's are closed
     * the way a dead or disconnected player's are, and what they drop is caught instead of spawned (what a living,
     * connected player's menus give back goes into the inventory as usual).
     */
    private static List<ItemStack> takeLoose(ServerPlayer player) {
        List<ItemEntity> caught = new ArrayList<>();
        Collection<ItemEntity> outer = player.captureDrops(caught);
        try {
            if (player.hasContainerOpen()) {
                player.doCloseContainer();
            }
            player.inventoryMenu.removed(player);
        } finally {
            player.captureDrops(outer);
        }
        List<ItemStack> stacks = new ArrayList<>(caught.size());
        for (ItemEntity entity : caught) {
            stacks.add(entity.getItem());
        }
        return stacks;
    }

    private static void dropAt(HollowManager.DropSite site, ItemStack stack) {
        ItemEntity moved = new ItemEntity(site.level(), site.position().x, site.position().y, site.position().z, stack);
        moved.setDefaultPickUpDelay();
        site.level().addFreshEntity(moved);
    }

    /**
     * Recreates, with all its data, a tamed creature that would join the hollow (a parrot leaving its owner's shoulder
     * there) at the place its owner comes back to, or at the world spawn if the owner is in no event: in the hollow
     * it would be swept away with the copy.
     */
    private static void sendHome(Mob mob, UUID owner, MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        HollowManager.DropSite site = player == null ? null : HollowManager.dropSite(player);
        ServerLevel level = site != null ? site.level() : server.overworld();
        Vec3 at = site != null ? site.position() : Vec3.atBottomCenterOf(level.getSharedSpawnPos());
        level.getChunkAt(BlockPos.containing(at));
        CompoundTag tag = new CompoundTag();
        Entity home = mob.save(tag) ? EntityType.loadEntityRecursive(tag, level, copy -> {
            copy.moveTo(at.x, at.y, at.z, copy.getYRot(), copy.getXRot());
            return copy;
        }) : null;
        if (home == null || !level.addFreshEntity(home)) {
            Tremor.LOGGER.warn("Hollow: could not put {} of {} out of the hollow", mob, owner);
        }
    }
}
