package tremor.item;

import java.util.function.Consumer;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The debug probe ({@code tremor:debug_probe}), an operator's multitool: held (or pinned), it shows what the nearest
 * tremor is up to ({@code tremor.debug.DebugProbe}); a right click acts where the player looks, by the mode chosen
 * (send the tremor there, add a route point or a listening point), and a right click while sneaking opens its menu.
 * The client does both ({@code tremor.client.ProbeTool}) by running the operator's {@code /tremor} commands, so the
 * server checks the permission as for any command.
 */
public final class DebugProbeItem extends Item {
    /** What a right click does on the client ({@code true}: while sneaking); null where there is no client. */
    public static Consumer<Boolean> onUse;

    public DebugProbeItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide() && onUse != null) {
            onUse.accept(player.isShiftKeyDown());
        }
        return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }
}
