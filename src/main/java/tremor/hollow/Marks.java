package tremor.hollow;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import tremor.Tremor;
import tremor.config.TremorConfig;
import tremor.world.TremorTags;

/**
 * The marks (1.4.0, as the comments asked: the creature learns you): every time a tremor takes a player into the
 * hollow they get a mark, and their hollow is harder the more they have: the node farther, the walls closing faster,
 * less time ({@link #nodeFactor}, {@link #closeFactor}, {@link #breathFactor}, each to a limit), and from the
 * {@value #DIGEST_FROM}rd on the hollow digests: waves run along its floor that hurt whoever stands on the ground as one
 * passes (a jump just then, wool or a carpet under the feet, and it passes). A hushing charm takes one off. Kept with
 * the player (its persistent data, over a death too).
 */
public final class Marks {
    private static final String KEY = "tremor_marks";
    /** The node lies this much farther per mark after the first, at most {@value #NODE_MAX} times as far. */
    static final double NODE_PER = 0.08;
    static final double NODE_MAX = 1.5;
    /** The walls close this much faster per mark after the first, at most {@value #CLOSE_MAX} times as fast. */
    static final double CLOSE_PER = 0.1;
    static final double CLOSE_MAX = 1.8;
    /** This much less time per mark after the first, at least {@value #BREATH_MIN} of it. */
    static final double BREATH_PER = 0.07;
    static final double BREATH_MIN = 0.6;
    /** From this many marks on the hollow digests. */
    public static final int DIGEST_FROM = 3;
    /** A digesting wave hurts this much (scaled by the difficulty). */
    public static final double DIGEST_DAMAGE = 3;
    /** The damage type of a digesting wave: the tremor's own. */
    public static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE,
            new ResourceLocation(Tremor.MODID, "tremor"));

    private Marks() {
    }

    /** How many marks {@code player} has. */
    public static int of(Player player) {
        return player.getPersistentData().getInt(KEY);
    }

    public static void set(Player player, int marks) {
        player.getPersistentData().putInt(KEY, Math.max(0, marks));
    }

    /** A tremor took {@code player} into the hollow: one more mark (none counted with marks off); how many now. */
    public static int add(ServerPlayer player) {
        if (!TremorConfig.COMMON.marks.enabled.get()) {
            return of(player);
        }
        int marks = of(player) + 1;
        set(player, marks);
        Tremor.LOGGER.info("Marks: {} has {} now", player.getGameProfile().getName(), marks);
        return marks;
    }

    private static int extra(int marks) {
        return TremorConfig.COMMON.marks.enabled.get() ? Math.max(0, marks - 1) : 0;
    }

    public static double nodeFactor(int marks) {
        return Math.min(NODE_MAX, 1 + NODE_PER * extra(marks));
    }

    public static double closeFactor(int marks) {
        return Math.min(CLOSE_MAX, 1 + CLOSE_PER * extra(marks));
    }

    public static double breathFactor(int marks) {
        return Math.max(BREATH_MIN, 1 - BREATH_PER * extra(marks));
    }

    public static boolean digests(int marks) {
        return TremorConfig.COMMON.marks.enabled.get() && marks >= DIGEST_FROM;
    }

    /** Whether {@code player} stands on the ground as a wave passes: not in the air, in water, on wool or a carpet. */
    public static boolean grounded(ServerPlayer player) {
        if (!player.onGround() || player.isInWater()) {
            return false;
        }
        BlockPos feet = BlockPos.containing(player.getX(), player.getY() + 0.01, player.getZ());
        return !player.level().getBlockState(feet).is(TremorTags.CONDUCTIVITY_INSULATING)
                && !player.level().getBlockState(feet.below()).is(TremorTags.CONDUCTIVITY_INSULATING);
    }

    /** The marks go with the player over a death (a new player entity). */
    public static void onClone(PlayerEvent.Clone event) {
        set(event.getEntity(), of(event.getOriginal()));
    }
}
