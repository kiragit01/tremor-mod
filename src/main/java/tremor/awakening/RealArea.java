package tremor.awakening;

import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * A square of chunks of a real level that something of an outcome works on ({@link Sinkholes}, {@link EdgeExits}):
 * held loaded by a region ticket from the first {@link #ready} until {@link #release}, and loaded once every chunk is
 * a full chunk with its entities. Server thread only.
 */
final class RealArea {
    private final ServerLevel level;
    private final TicketType<Integer> type;
    /** The ticket's value: one ticket per area and type. */
    private final int id;
    private final int minChunkX, maxChunkX, minChunkZ, maxChunkZ;
    private final ChunkPos centre;
    private final int distance;
    private boolean held;

    /** The chunks the blocks {@code minX..maxX, minZ..maxZ} lie in, held by a ticket of {@code type} with {@code id}. */
    RealArea(ServerLevel level, TicketType<Integer> type, int id, int minX, int minZ, int maxX, int maxZ) {
        this.level = level;
        this.type = type;
        this.id = id;
        minChunkX = minX >> 4;
        maxChunkX = maxX >> 4;
        minChunkZ = minZ >> 4;
        maxChunkZ = maxZ >> 4;
        centre = new ChunkPos((minChunkX + maxChunkX) >> 1, (minChunkZ + maxChunkZ) >> 1);
        distance = Math.max(Math.max(centre.x - minChunkX, maxChunkX - centre.x),
                Math.max(centre.z - minChunkZ, maxChunkZ - centre.z));
    }

    ServerLevel level() {
        return level;
    }

    /** Holds the ticket (from the first call on) and says whether every chunk is loaded, entities included. */
    boolean ready() {
        if (!held) {
            level.getChunkSource().addRegionTicket(type, centre, distance, id);
            held = true;
        }
        return loaded();
    }

    /** Whether every chunk is loaded now, entities included (without asking for it). */
    boolean loaded() {
        for (int z = minChunkZ; z <= maxChunkZ; z++) {
            for (int x = minChunkX; x <= maxChunkX; x++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null || !chunk.getFullStatus().isOrAfter(FullChunkStatus.FULL)
                        || !level.areEntitiesLoaded(ChunkPos.asLong(x, z))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Lets the chunks go again (a no-op if they were not held). */
    void release() {
        if (held) {
            level.getChunkSource().removeRegionTicket(type, centre, distance, id);
            held = false;
        }
    }
}
