package tremor.item;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import tremor.hollow.Marks;

/**
 * The hushing charm (1.4.0): used, it takes one of the player's marks off ({@link Marks}) and is spent; with no mark,
 * it says so and is kept. One shard, as a victory gives (the user: three were too dear).
 */
public final class HushCharmItem extends Item {
    public HushCharmItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(player instanceof ServerPlayer server)) {
            return InteractionResultHolder.success(stack);
        }
        int marks = Marks.of(server);
        if (marks <= 0) {
            server.displayClientMessage(Component.translatable("tremor.marks.none"), true);
            return InteractionResultHolder.fail(stack);
        }
        Marks.set(server, marks - 1);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE,
                SoundSource.PLAYERS, 1.2f, 0.7f);
        server.displayClientMessage(Component.translatable("tremor.marks.removed", marks - 1), true);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResultHolder.consume(stack);
    }
}
