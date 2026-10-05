package tremor.hollow;

import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import tremor.block.TremorBlocks;
import tremor.config.TremorConfig;

/**
 * The hollow is the entity's body (SPEC 9), and it treats a player as such: nothing can be built in it (a block a
 * survival or adventure player places is refused, so nobody pillars up or walls in; torches, lanterns and candles,
 * {@code #tremor:hollow_placeable}, may be set down), a player in it is blind (the
 * vanilla blindness, ambient, with no icon and no particles, renewed while inside and taken away on the way out; shader
 * packs know it, where they drop the fog of {@code tremor.client.hollow.HollowFog}), and
 * the ground of the hollow resists the pickaxe (SPEC 9: the hollow is the entity's body, not a place to dig out of):
 * in the hollow dimension a survival or adventure player breaks every block {@code hollow.level.digFactor} times as
 * fast as elsewhere, the node excepted. No effect is put on the player: the blocks are only slow to give. Runs on both
 * sides (the client predicts the breaking from the same speed). Registered by {@link tremor.Tremor}.
 */
public final class HollowDigging {
    private HollowDigging() {
    }

    /** Ticks of blindness given at a time; renewed while inside. */
    static final int BLIND_TICKS = 40;

    /** What may still be set down in the hollow: lights that nobody can climb on (torches, lanterns, candles). */
    public static final net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> PLACEABLE =
            net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(tremor.Tremor.MODID,
                            "hollow_placeable"));

    /** A survival or adventure player places no block in the hollow, but a light ({@link #PLACEABLE}). */
    public static void onPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player player && !player.isCreative() && !player.isSpectator()
                && event.getLevel() instanceof net.minecraft.world.level.Level level && HollowDimension.is(level)
                && !event.getPlacedBlock().is(PLACEABLE)) {
            event.setCanceled(true);
        }
    }

    /** Keeps a survival or adventure player in the hollow blind. */
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide || player.isCreative() || player.isSpectator()
                || !HollowDimension.is(player.level()) || player.tickCount % 10 != 0) {
            return;
        }
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.BLINDNESS, BLIND_TICKS, 0, true, false, false));
    }

    /** On the way out of the hollow the blindness it gave goes at once (the way out is to be seen). */
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        Player player = event.getEntity();
        var blind = player.getEffect(net.minecraft.world.effect.MobEffects.BLINDNESS);
        if (event.getFrom() == HollowDimension.KEY && blind != null && blind.getDuration() <= BLIND_TICKS
                && blind.isAmbient()) {
            player.removeEffect(net.minecraft.world.effect.MobEffects.BLINDNESS);
        }
    }

    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        Player player = event.getEntity();
        if (player.isCreative() || player.isSpectator() || !HollowDimension.is(player.level())
                || event.getState().is(TremorBlocks.HEART_NODE.get())) {
            return;
        }
        event.setNewSpeed(event.getNewSpeed() * TremorConfig.COMMON.hollow.level.digFactor.get().floatValue());
    }
}
