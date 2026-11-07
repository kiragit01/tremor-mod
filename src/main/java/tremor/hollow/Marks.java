package tremor.hollow;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import tremor.Tremor;
import tremor.config.TremorConfig;

/**
 * The marks (1.4.0, as the comments asked: the creature learns you): every time a tremor takes a player into the
 * hollow they get a mark, and their hollow is harder the more they have: the node farther, the walls closing faster,
 * less time ({@link #nodeFactor}, {@link #closeFactor}, {@link #breathFactor}, each to a limit), and A hushing charm takes one off. Kept with
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
    /** The marks {@code player} has once taken in once more ({@link #add}), not counted yet: the hollow is planned by it. */
    public static int next(Player player) {
        return TremorConfig.COMMON.marks.enabled.get() ? of(player) + 1 : of(player);
    }

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

    /** The marks go with the player over a death (a new player entity). */
    public static void onClone(PlayerEvent.Clone event) {
        set(event.getEntity(), of(event.getOriginal()));
    }
}
