package tremor.hollow.level;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import tremor.hollow.HollowManager;

/**
 * The walls move (SPEC 9: "пространство «ходит ходуном»"; "стены двигаются и путают, но путь не замуровывается
 * насовсем"): now and then a few blocks of wall {@value #NEAR} to {@value #FAR} blocks from the player trade places
 * with the air next to them, so the wall bulges out where the block went and recedes where it was. Only plain blocks
 * of the copy move ({@link Materials#usable}), and only ones nothing leans on (a torch, a plant, a fluid); never near
 * the player, into the way to the node or out of its floor, nor the node itself, nor outside the part of the hollow that
 * is still open.
 */
final class WallShifter {
    /** Nothing moves closer to the player than this (blocks, from the middle of the player's body). */
    static final double NEAR = 5;
    /** ...or farther. */
    static final double FAR = 14;
    /** Places tried per block to move. */
    private static final int TRIES = 12;

    private long shifted;

    /** Moves up to {@code count} wall blocks around {@code player}, inside the closing radius {@code radius}. */
    void shift(ServerLevel level, EventLevel owner, Vec3 player, double radius, int count) {
        RandomSource random = level.getRandom();
        int done = 0;
        for (int attempt = 0; attempt < count * TRIES && done < count; attempt++) {
            double distance = NEAR + random.nextDouble() * (FAR - NEAR);
            double angle = random.nextDouble() * 2 * Math.PI;
            BlockPos air = BlockPos.containing(player.x + distance * Math.cos(angle),
                    player.y + random.nextInt(9) - 4, player.z + distance * Math.sin(angle));
            if (!level.getBlockState(air).isAir() || !owner.mayShift(air, player, radius, NEAR, FAR)) {
                continue;
            }
            int first = random.nextInt(Direction.values().length);
            for (int i = 0; i < Direction.values().length; i++) {
                Direction side = Direction.values()[(first + i) % Direction.values().length];
                BlockPos wall = air.relative(side);
                BlockState state = level.getBlockState(wall);
                if (!Materials.usable(level, wall, state) || !owner.mayShift(wall, player, radius, NEAR, FAR)
                        || owner.keepsOpen(wall.above().asLong()) || HollowManager.isPlayerPlaced(level, wall)
                        || leanedOn(level, wall, air)) {
                    continue;
                }
                level.setBlock(air, state, Materials.FLAGS);
                level.setBlock(wall, Materials.AIR, Materials.FLAGS);
                done++;
                shifted++;
                break;
            }
        }
    }

    /** Blocks moved so far. */
    long shifted() {
        return shifted;
    }

    /** Whether something but air or a full block touches {@code wall} (other than through {@code air}). */
    private static boolean leanedOn(ServerLevel level, BlockPos wall, BlockPos air) {
        for (Direction side : Direction.values()) {
            BlockPos next = wall.relative(side);
            if (next.equals(air)) {
                continue;
            }
            BlockState state = level.getBlockState(next);
            if (!state.isAir() && !state.isCollisionShapeFullBlock(level, next)) {
                return true;
            }
        }
        return false;
    }
}
