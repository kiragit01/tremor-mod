package tremor;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import tremor.awakening.AwakeningManager;
import tremor.entity.TremorManager;

/**
 * The mod as a whole on or off, the game rule {@code doTremor} (on by default; a server owner or a modpack turns it
 * off, a quest or a datapack back on: the user, after a modpack maker asked, as In Control sees only real mobs and the
 * tremor is none). Off, nothing of it runs: every tremor is gone, a running Awakening is called off (whoever was being
 * swallowed, or is in the hollow, is brought back out), none comes by itself nor by a command nor a thrown shard, and
 * no vibration is listened to. What is under way and has to end (a crater being dug, a hollow emptied) ends.
 */
public final class TremorSwitch {
    public static final GameRules.Key<GameRules.BooleanValue> RULE = GameRules.register("doTremor",
            GameRules.Category.MOBS, GameRules.BooleanValue.create(true));
    /** Every this many ticks the server looks whether it is switched off. */
    private static final int CHECK_TICKS = 20;
    private static boolean wasOff;

    private TremorSwitch() {
    }

    /** Loads the class: the game rule is registered before any world is. */
    static void init() {
    }

    /** Whether the mod is switched off for {@code level}'s server. */
    public static boolean off(Level level) {
        return !level.getGameRules().getBoolean(RULE);
    }

    public static boolean off(MinecraftServer server) {
        return !server.getGameRules().getBoolean(RULE);
    }

    /** Sets the game rule ({@code /tremor system on|off}). */
    public static void set(MinecraftServer server, boolean on) {
        server.getGameRules().getRule(RULE).set(on, server);
    }

    /**
     * Switched off: whatever is left goes (every tremor, every Awakening called off), also of a world loaded while
     * off; logged as it is switched off and on.
     */
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        MinecraftServer server = event.getServer();
        if (event.phase != TickEvent.Phase.END || server.getTickCount() % CHECK_TICKS != 0) {
            return;
        }
        boolean off = off(server);
        if (off) {
            int tremors = 0;
            for (ServerLevel level : server.getAllLevels()) {
                tremors += TremorManager.despawnAll(level);
                AwakeningManager.cancelAll(level, "the mod was switched off");
            }
            if (!wasOff || tremors > 0) {
                Tremor.LOGGER.info("Tremor is switched off (game rule doTremor false): {} removed", tremors);
            }
        } else if (wasOff) {
            Tremor.LOGGER.info("Tremor is switched on (game rule doTremor true)");
        }
        wasOff = off;
    }
}
