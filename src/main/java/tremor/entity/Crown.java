package tremor.entity;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * A tree crown gives way under a player (the user: up on leaves the tremor could not get them, and a swallowing there
 * went wrong): the leaves under them, a column {@value #REACH} across, from their feet down for as long as there are
 * leaves in it, at most {@value #MAX_DEPTH} layers, crumble as leaves that decay do, dropping what decaying leaves drop.
 * Server thread only.
 */
public final class Crown {
    /** The column of leaves that gives way is this many blocks either side of the player's (3x3). */
    static final int REACH = 1;
    static final int MAX_DEPTH = 12;

    private Crown() {
    }

    /** Whether {@code player} stands on leaves. */
    public static boolean under(Player player) {
        return player.level().getBlockState(player.getOnPos()).is(BlockTags.LEAVES);
    }

    /** The leaves that give way under {@code player}, the highest first. */
    public static List<BlockPos> below(Player player) {
        Level level = player.level();
        BlockPos feet = player.getOnPos();
        List<BlockPos> found = new ArrayList<>();
        for (int dy = 0; dy < MAX_DEPTH; dy++) {
            boolean any = false;
            for (int dz = -REACH; dz <= REACH; dz++) {
                for (int dx = -REACH; dx <= REACH; dx++) {
                    BlockPos p = feet.offset(dx, -dy, dz);
                    if (level.getBlockState(p).is(BlockTags.LEAVES)) {
                        found.add(p);
                        any = true;
                    }
                }
            }
            if (!any) {
                break;
            }
        }
        return found;
    }

    /** {@code p} crumbles if it is still leaves: broken with its particles and sound, dropping as decay does. */
    public static void crumble(ServerLevel level, BlockPos p) {
        if (level.getBlockState(p).is(BlockTags.LEAVES)) {
            level.destroyBlock(p, true);
        }
    }
}
