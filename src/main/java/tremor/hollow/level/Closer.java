package tremor.hollow.level;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import tremor.hollow.HollowManager;

import java.util.ArrayDeque;

/**
 * The closing of the hollow made real (SPEC 9, "Смыкание": "края изнанки постепенно закрываются — стены
 * вспучиваются, проходы сужаются... Рядом с игроком клетки не засыпаются"): every column of the copy whose turn has
 * come ({@link ClosingOrder}) is filled from the bottom up, each open cell with a copy of a solid neighbour, the one
 * towards the edge first ({@link Materials#material}), so the walls seem to grow inwards. Never filled: the way to the
 * node and the node ({@link EventLevel#keepsOpen}), the player's own blocks, and the cells near the player
 * ({@value #NEAR} blocks aside, from {@value #NEAR} below the feet to {@value #NEAR} above the head); a column with
 * such a cell, or with a cell that had nothing to grow from yet, is gone over again {@value #RETRY_TICKS} ticks later.
 * Every {@value #RESCAN_TICKS} ticks the closed columns around the player are gone over again too, so what the player
 * digs into the closed part fills up behind the player. The work is amortized: a number of cells per tick, and the
 * time left of the tick's budget.
 */
final class Closer {
    /** How close to the player nothing is filled (blocks). */
    static final double NEAR = 3;
    private static final int RESCAN_TICKS = 20;
    private static final int RESCAN_RADIUS = 8;
    private static final int RETRY_TICKS = 30;
    /** Cells looked at between two looks at the clock. */
    private static final int CLOCK_EVERY = 16;

    private final ClosingOrder order;
    private final int minY;
    private final int maxY;
    private final double centreX;
    private final double centreZ;
    /** Columns to go over, the one in progress first. */
    private final ArrayDeque<Column> queue = new ArrayDeque<>();
    /** Columns to go over again later, in the order they are due. */
    private final ArrayDeque<Column> waiting = new ArrayDeque<>();
    /** Columns in {@link #queue} or {@link #waiting} ({@link ChunkPos#asLong} of x, z). */
    private final LongOpenHashSet listed = new LongOpenHashSet();
    /** Columns of the order queued so far. */
    private int started;
    private long filled;

    private static final class Column {
        final int x;
        final int z;
        int y;
        /** A cell was left open for now: go over the column again later. */
        boolean again;
        long dueTick;

        Column(int x, int z, int y) {
            this.x = x;
            this.z = z;
            this.y = y;
        }
    }

    Closer(ClosingOrder order, int minY, int maxY, double centreX, double centreZ) {
        this.order = order;
        this.minY = minY;
        this.maxY = maxY;
        this.centreX = centreX;
        this.centreZ = centreZ;
    }

    /**
     * One tick at the closing radius {@code radius} with the player at {@code player} ({@code height} tall): fills up
     * to {@code maxFills} cells until {@code deadline} ({@link System#nanoTime}).
     */
    void tick(ServerLevel level, EventLevel owner, double radius, Vec3 player, double height, long tick,
              long deadline, int maxFills) {
        int due = order.advance(radius);
        for (; started < due; started++) {
            list(order.x(started), order.z(started));
        }
        while (!waiting.isEmpty() && waiting.peek().dueTick <= tick) {
            queue.add(waiting.poll());
        }
        if (tick % RESCAN_TICKS == 0) {
            int px = (int) Math.floor(player.x);
            int pz = (int) Math.floor(player.z);
            for (int z = pz - RESCAN_RADIUS; z <= pz + RESCAN_RADIUS; z++) {
                for (int x = px - RESCAN_RADIUS; x <= px + RESCAN_RADIUS; x++) {
                    if (order.closed(x, z, radius)) {
                        list(x, z);
                    }
                }
            }
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int fills = 0;
        int looked = 0;
        while (!queue.isEmpty()) {
            Column column = queue.peek();
            Direction outward = outward(column.x, column.z);
            for (; column.y <= maxY; column.y++) {
                if (fills >= maxFills || ++looked % CLOCK_EVERY == 0 && System.nanoTime() > deadline) {
                    return;
                }
                pos.set(column.x, column.y, column.z);
                BlockState state = level.getBlockState(pos);
                if (!Materials.open(state) || owner.keepsOpen(pos.asLong())
                        || HollowManager.isPlayerPlaced(level, pos)) {
                    continue;
                }
                if (near(player, height, column.x, column.y, column.z)) {
                    column.again = true;
                    continue;
                }
                BlockState fill = Materials.material(level, pos, outward);
                if (fill == null) {
                    column.again = true;
                    continue;
                }
                level.setBlock(pos, fill, Materials.FLAGS);
                fills++;
                filled++;
            }
            queue.poll();
            if (column.again) {
                column.again = false;
                column.y = minY;
                column.dueTick = tick + RETRY_TICKS;
                waiting.add(column);
            } else {
                listed.remove(ChunkPos.asLong(column.x, column.z));
            }
        }
    }

    /** Blocks filled so far. */
    long filled() {
        return filled;
    }

    /** Columns waiting to be gone over (now or later). */
    int pending() {
        return listed.size();
    }

    private void list(int x, int z) {
        if (listed.add(ChunkPos.asLong(x, z))) {
            queue.add(new Column(x, z, minY));
        }
    }

    /** Whether the cell is too close to the player to be filled. */
    private static boolean near(Vec3 player, double height, int x, int y, int z) {
        double dx = x + 0.5 - player.x;
        double dz = z + 0.5 - player.z;
        return dx * dx + dz * dz <= (NEAR + 0.5) * (NEAR + 0.5) && y + 1 > player.y - NEAR
                && y < player.y + height + NEAR;
    }

    /** The side of the column that faces the edge: the wall the cells grow from. */
    private Direction outward(int x, int z) {
        double dx = x + 0.5 - centreX;
        double dz = z + 0.5 - centreZ;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
