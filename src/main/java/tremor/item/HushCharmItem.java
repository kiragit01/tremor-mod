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
 * The hushing charm (1.4.0): used, it takes {@value #MARKS} of the player's marks off ({@link Marks}) and is spent; with
 * no mark, it says so and is kept. Made with an echo shard of an ancient city (the user: one shard of the tremor, as a
 * victory gives, made the mark it brought cost nothing; the ancient cities are dear enough, the craft is no dearer) and
 * two shards of the tremor.
 */
public final class HushCharmItem extends Item {
    /** Marks one charm takes off. */
    static final int MARKS = 2;

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
        int left = Math.max(0, marks - MARKS);
        Marks.set(server, left);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE,
                SoundSource.PLAYERS, 1.2f, 0.7f);
        server.displayClientMessage(Component.translatable("tremor.marks.removed", left), true);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResultHolder.consume(stack);
    }
}
