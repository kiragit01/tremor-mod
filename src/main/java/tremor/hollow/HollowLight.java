package tremor.hollow;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import tremor.config.TremorConfig;

/**
 * A faint light of their own around a player in the hollow ({@code hollow.playerLight}): enough to make out the
 * walls next to them without a torch, not to see down a tunnel. Vanilla has no light from players, so it is an
 * invisible light block that follows the player's head through the air, a plain light of the world (no client mod
 * needed, shaders show it too). Survival and adventure players only, like the blindness ({@link HollowDigging}).
 */
public final class HollowLight {
    /** Ticks between two moves of a light. */
    private static final int INTERVAL = 2;
    /** Where each player's light is, by player. */
    private static final Map<UUID, BlockPos> LIGHTS = new HashMap<>();

    private HollowLight() {
    }

    /** Game bus: moves the light of a player in the hollow to the air at their head, or puts it out. */
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer server) || player.tickCount % INTERVAL != 0) {
            return;
        }
        int light = TremorConfig.COMMON.hollow.playerLight.get();
        boolean lit = light > 0 && HollowDimension.is(server.level()) && !server.isCreative() && !server.isSpectator()
                && server.isAlive();
        BlockPos old = LIGHTS.get(server.getUUID());
        BlockPos head = lit ? BlockPos.containing(server.getEyePosition()) : null;
        if (head != null && !server.level().getBlockState(head).isAir() && !head.equals(old)) {
            head = null; // in a block (sinking, a door): the light stays where it was, or goes
            lit = old != null;
        }
        if (!lit) {
            putOut(server);
            return;
        }
        ServerLevel level = server.serverLevel();
        BlockState wanted = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, Math.min(15, light));
        if (head == null || head.equals(old) && level.getBlockState(head).equals(wanted)) {
            return;
        }
        level.setBlock(head, wanted, Block.UPDATE_CLIENTS);
        LIGHTS.put(server.getUUID(), head);
        if (old != null && !old.equals(head)) {
            clear(level, old);
        }
    }

    /** Game bus: the light stays behind in the hollow no longer than the player. */
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && event.getFrom() == HollowDimension.KEY) {
            BlockPos old = LIGHTS.remove(player.getUUID());
            ServerLevel hollow = player.server.getLevel(HollowDimension.KEY);
            if (old != null && hollow != null) {
                clear(hollow, old);
            }
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            putOut(player);
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        LIGHTS.clear();
    }

    private static void putOut(ServerPlayer player) {
        BlockPos old = LIGHTS.remove(player.getUUID());
        MinecraftServer server = player.server;
        ServerLevel hollow = server.getLevel(HollowDimension.KEY);
        if (old != null && hollow != null) {
            clear(hollow, old);
        }
    }

    private static void clear(ServerLevel level, BlockPos pos) {
        if (level.isLoaded(pos) && level.getBlockState(pos).is(Blocks.LIGHT)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }
}
