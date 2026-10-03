package tremor.awakening;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import tremor.Tremor;
import tremor.block.RubbleCacheBlockEntity;
import tremor.block.TremorBlocks;
import tremor.hollow.HollowDimension;
import tremor.hollow.HollowEvent;
import tremor.hollow.HollowManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Hides the things of a player the ground killed in the crater of the defeat (SPEC 9 "Поражение": scattered over its
 * bottom, partly buried under the rubble). Item entities would despawn long before the player is back (five minutes),
 * so the things go into caches ({@link TremorBlocks#RUBBLE_CACHE}) laid out by {@link CacheLayout} on the columns of
 * the bottom that have rubble (any column of the bottom if none has): a cache on top of the rubble, or under one or two
 * more blocks of it. Server thread only.
 * <p>
 * The keeper of a defeat ({@link HollowManager#setDeathDrops}) calls {@link #bury} with what the player leaves: the
 * death drops ({@link #onLivingDrops}: what the player carried, the crafting grid and the carried stack included, as
 * the hollow's rules gather it; nothing with {@code keepInventory}, and the curse of vanishing has already taken its
 * share, as in vanilla), and what is left of the player's in the copy when it is cleared. The experience is not kept:
 * its orbs lie on the bottom.
 */
public final class CraterCaches {
    private CraterCaches() {
    }

    /**
     * Puts {@code stacks} into new caches on the bottom of {@code crater}, on columns without one yet; what finds no
     * place there goes into the caches already there. False (nothing taken) if there is no place for any.
     */
    static boolean bury(Craters.Crater crater, List<ItemStack> stacks) {
        List<ItemStack> things = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (!stack.isEmpty()) {
                things.add(stack.copy());
            }
        }
        if (things.isEmpty()) {
            return true;
        }
        List<Craters.Bottom> free = new ArrayList<>();
        for (Craters.Bottom bottom : crater.bottoms) {
            if (bottom.rubble() > 0 && !crater.cached.contains(bottom)) {
                free.add(bottom);
            }
        }
        if (free.isEmpty()) {
            for (Craters.Bottom bottom : crater.bottoms) {
                if (!crater.cached.contains(bottom)) {
                    free.add(bottom);
                }
            }
        }
        List<CacheLayout.Spot> spots = new ArrayList<>(free.size());
        for (Craters.Bottom bottom : free) {
            spots.add(new CacheLayout.Spot(bottom.x(), bottom.z()));
        }
        List<RubbleCacheBlockEntity> placed = new ArrayList<>();
        List<ItemStack> homeless = new ArrayList<>();
        StringBuilder where = new StringBuilder();
        for (CacheLayout.Cache cache : CacheLayout.plan(things.size(), spots, crater.seed + crater.caches.size())) {
            List<ItemStack> held = new ArrayList<>(cache.stacks().size());
            for (int index : cache.stacks()) {
                held.add(things.get(index));
            }
            Craters.Bottom bottom = free.get(cache.spot());
            RubbleCacheBlockEntity entity = place(crater, bottom, cache.buried());
            if (entity == null) {
                homeless.addAll(held);
                continue;
            }
            entity.add(held);
            placed.add(entity);
            crater.cached.add(bottom);
            crater.caches.add(entity.getBlockPos());
            where.append(where.isEmpty() ? "" : ", ").append(entity.getBlockPos().toShortString())
                    .append(cache.buried() == 0 ? " on top" : " under " + cache.buried() + " of rubble");
        }
        if (placed.isEmpty()) {
            // No new cache: the ones already there, if any is left.
            for (BlockPos at : crater.caches) {
                if (crater.level.getBlockEntity(at) instanceof RubbleCacheBlockEntity entity) {
                    placed.add(entity);
                }
            }
            if (placed.isEmpty()) {
                return false;
            }
            homeless = things;
        }
        for (int i = 0; i < homeless.size(); i++) {
            placed.get(i % placed.size()).add(List.of(homeless.get(i)));
        }
        Tremor.LOGGER.info("Crater #{}: {} stacks hidden in {} caches on its bottom ({} in all){}", crater.id,
                things.size(), placed.size(), crater.caches.size(), where.isEmpty() ? "" : ": " + where);
        return true;
    }

    /**
     * The items of a player who dies in the hollow during an event whose things a crater keeps
     * ({@link HollowManager#deathKeeper}) go into its caches, with what was in the crafting grids and on the cursor:
     * the drops are cancelled. If the crater has no place for them, they are left to the hollow's rules (they lie at
     * the drop site). High priority: before {@code HollowRules} moves them.
     */
    public static void onLivingDrops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !HollowDimension.is(player.level())) {
            return;
        }
        HollowEvent hollow = HollowManager.event(player);
        HollowManager.DeathKeeper keeper = hollow == null ? null : HollowManager.deathKeeper(hollow);
        if (keeper == null) {
            return;
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemEntity drop : event.getDrops()) {
            stacks.add(drop.getItem().copy());
        }
        List<ItemStack> loose = takeLoose(player);
        stacks.addAll(loose);
        stacks.removeIf(ItemStack::isEmpty);
        if (stacks.isEmpty()) {
            return;
        }
        if (keeper.keep(stacks)) {
            event.setCanceled(true);
            return;
        }
        // Back to the drops, which the hollow's rules take to the drop site.
        for (ItemStack stack : loose) {
            event.getDrops().add(new ItemEntity(player.level(), player.getX(), player.getY(), player.getZ(), stack));
        }
    }

    /**
     * Puts a cache on {@code bottom}: on top of its rubble ({@code buried} 0), else on its floor under {@code buried}
     * blocks of rubble (more is added if the rubble is thinner). Null if the place is taken by something that is
     * neither air nor rubble (something fell in meanwhile).
     */
    private static RubbleCacheBlockEntity place(Craters.Crater crater, Craters.Bottom bottom, int buried) {
        ServerLevel level = crater.level;
        BlockPos at = new BlockPos(bottom.x(), buried == 0 ? bottom.floor() + Math.max(1, bottom.rubble())
                : bottom.floor() + 1, bottom.z());
        BlockState there = level.getBlockState(at);
        if (!there.isAir() && !Craters.isRubble(there)) {
            return null;
        }
        level.setBlock(at, TremorBlocks.RUBBLE_CACHE.get().defaultBlockState(), Block.UPDATE_CLIENTS);
        for (int over = 1; over <= buried; over++) {
            BlockPos pos = at.above(over);
            BlockState state = level.getBlockState(pos);
            if (Craters.isRubble(state)) {
                continue;
            }
            if (!state.isAir()) {
                break;
            }
            level.setBlock(pos, Craters.rubble(pos.getX(), pos.getY(), pos.getZ(), crater.top.asLong()),
                    Block.UPDATE_CLIENTS);
        }
        return level.getBlockEntity(at) instanceof RubbleCacheBlockEntity entity ? entity : null;
    }

    /**
     * Takes out what is in the player's crafting grids and on the cursor: the open menu and the inventory's are closed
     * the way a dead player's are, and what they drop is caught instead of spawned (as {@code HollowRules} does).
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
}
