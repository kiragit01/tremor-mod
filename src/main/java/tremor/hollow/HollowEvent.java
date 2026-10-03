package tremor.hollow;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * One event of the hollow (SPEC 9): a player moved into a copy of the terrain around the place the player was
 * swallowed, in a slot of {@code tremor:hollow} of its own, and back. The copy maps 1:1 onto the real place, shifted
 * by a whole number of chunks horizontally ({@link #offsetX()}, {@link #offsetZ()}; y is the same), so a position in
 * the copy and the real one differ only by that offset.
 * <p>
 * The identity, the origin, the slot, the box, the phase, the exit, why it ended, its outcome, where the things of the
 * player go after a death and the blocks the player placed are saved ({@link HollowSavedData}); the progress of the
 * running phase is not: an event loaded after a restart only has its slot cleared, the player's blocks given back
 * first ({@link HollowManager}).
 */
public final class HollowEvent {

    public enum Phase {
        /** The screen fades to black while the slot loads and the terrain is copied into it. */
        COPYING,
        /** Moved into the copy; the screen stays black until the client has the terrain, then fades in. */
        ENTERING,
        /** In the copy (stage 4c plays the level here). */
        INSIDE,
        /** The screen fades to black before the move back. */
        LEAVING,
        /** Moved back; the screen stays black until the client has the terrain, then fades in. */
        RETURNING,
        /** The player is gone; the slot is emptied, then the event ends and the slot is free. */
        CLEARING;

        /** The player is (or should be) in the hollow in this phase. */
        public boolean inHollow() {
            return this == ENTERING || this == INSIDE || this == LEAVING;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Why the player part of an event ended (shown while its slot is cleared). */
    public enum End {
        /** Left the normal way ({@link HollowManager#leave}). */
        LEFT,
        /** Stopped before the player was moved: the player died, logged out or changed dimension while it was dark. */
        CANCELLED,
        /** Logged out inside the hollow; moved back on the next login. */
        LOGGED_OUT,
        /** Died inside the hollow. */
        DIED,
        /** Got out by other means: a portal, a teleport command. */
        ESCAPED,
        /** Ended by {@code /tremor restore}. */
        RESTORED,
        /** The server stopped (or crashed) during the event. */
        SERVER_STOPPED,
        /** Something went wrong (see the log). */
        ERROR;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Chunks around the copy on every side where the player may still build ({@link #area}). */
    static final int MARGIN_CHUNKS = 1;
    /**
     * Chunks around the copy on every side that are emptied after the event ({@link #slotArea}): one more than the
     * area, for what flowed, fell, grew or was pushed out of it.
     */
    static final int SLOT_MARGIN_CHUNKS = MARGIN_CHUNKS + 1;

    private final UUID player;
    private final String playerName;
    private final Origin origin;
    private final int slot;
    private final HollowBox box;
    private final int offsetX;
    private final int offsetZ;
    private final long createdGameTime;
    private Phase phase;
    private End end;
    private Origin exit;
    /** How the level ended for the player, once decided ({@link HollowManager#decide}); null before. */
    private HollowOutcome outcome;
    /** Where the things of the player go if the player dies in the event ({@link #deathDrops}); null: the origin. */
    private Origin deathDrops;
    /**
     * The blocks the player placed in the slot (SPEC 12: the player's own, not copies), by position in the hollow
     * ({@link BlockPos#asLong}), with the block placed there.
     */
    private final Long2ObjectOpenHashMap<Block> placed = new Long2ObjectOpenHashMap<>();

    // Progress of the running phase; not saved.
    long phaseStartTick;
    boolean ticketHeld;
    boolean slotLoaded;
    PieceCursor cursor;
    /** COPYING: the cursor still empties the slot (whatever an earlier event may have left), the copy comes next. */
    boolean emptying;
    final List<CompletableFuture<?>> light = new ArrayList<>();
    long workDoneTick = -1;
    int readyTicks;
    boolean died;
    WorkStats copyStats;
    WorkStats clearStats;

    HollowEvent(UUID player, String playerName, Origin origin, int slot, HollowBox box, int offsetX, int offsetZ,
                long createdGameTime, Phase phase) {
        if ((offsetX & 15) != 0 || (offsetZ & 15) != 0) {
            throw new IllegalArgumentException("The offset must be whole chunks: " + offsetX + " " + offsetZ);
        }
        this.player = player;
        this.playerName = playerName;
        this.origin = origin;
        this.slot = slot;
        this.box = box;
        this.offsetX = offsetX;
        this.offsetZ = offsetZ;
        this.createdGameTime = createdGameTime;
        this.phase = phase;
    }

    public UUID player() {
        return player;
    }

    /** The player's name when the event started (for messages while the player is offline). */
    public String playerName() {
        return playerName;
    }

    /** Where the player was swallowed. */
    public Origin origin() {
        return origin;
    }

    /** Where the player comes back out: the origin unless {@link HollowManager#leave} said otherwise. */
    public Origin exit() {
        return exit != null ? exit : origin;
    }

    void setExit(Origin exit) {
        this.exit = exit;
    }

    /** How the level inside the hollow ended for the player (SPEC 9 "Исходы"), once decided; null before. */
    public HollowOutcome outcome() {
        return outcome;
    }

    void setOutcome(HollowOutcome outcome) {
        this.outcome = outcome;
    }

    /**
     * Where the things of the player go if the player dies in the event: the bottom of the crater after a defeat
     * ({@link HollowManager#setDeathDrops}; the items into its caches), else the place the player was swallowed
     * (SPEC 9: the things lie there).
     */
    public Origin deathDrops() {
        return deathDrops != null ? deathDrops : origin;
    }

    void setDeathDrops(Origin deathDrops) {
        this.deathDrops = deathDrops;
    }

    /**
     * Where the player's things left in the slot go when the event is over: to the exit, back with the player, or
     * where the things of a player who died go ({@link #deathDrops}) if the player died.
     */
    Origin dropOrigin() {
        return end == End.DIED ? deathDrops() : exit();
    }

    /** Index of the slot ({@link SlotLayout}), on the layout around the world border's middle at the start. */
    public int slot() {
        return slot;
    }

    /** The copied box, in the coordinates of the origin's level. */
    public HollowBox box() {
        return box;
    }

    /** The copy of {@link #box()} in the hollow. */
    public HollowBox hollowBox() {
        return box.offset(offsetX, 0, offsetZ);
    }

    /** What is written into the slot: the copy of the box and a shell one block thick around it ({@link TerrainCopier}). */
    HollowBox copyRegion(int minBuildY, int maxBuildY) {
        return hollowBox().grow(1, minBuildY, maxBuildY);
    }

    /**
     * Where the player may build: the copy's chunk columns and {@link #MARGIN_CHUNKS} more, over the hollow's whole
     * height.
     */
    HollowBox area(int minBuildY, int maxBuildY) {
        return hollowBox().chunkColumns(MARGIN_CHUNKS, minBuildY, maxBuildY);
    }

    /**
     * The slot: the copy's chunk columns and {@link #SLOT_MARGIN_CHUNKS} more, over the hollow's whole height. It is
     * kept loaded during the event, emptied before the copy (whatever an earlier event may have left) and after the
     * event, and lies inside the world border.
     */
    HollowBox slotArea(int minBuildY, int maxBuildY) {
        return slotArea(hollowBox(), minBuildY, maxBuildY);
    }

    static HollowBox slotArea(HollowBox hollowBox, int minBuildY, int maxBuildY) {
        return hollowBox.chunkColumns(SLOT_MARGIN_CHUNKS, minBuildY, maxBuildY);
    }

    /** x from a position of the real place to its copy. */
    public int offsetX() {
        return offsetX;
    }

    /** z from a position of the real place to its copy. */
    public int offsetZ() {
        return offsetZ;
    }

    /** Chunk x of the middle of the slot: the chunk the origin is copied to. */
    public int slotChunkX() {
        return (origin.blockPos().getX() + offsetX) >> 4;
    }

    /** Chunk z of the middle of the slot. */
    public int slotChunkZ() {
        return (origin.blockPos().getZ() + offsetZ) >> 4;
    }

    public Vec3 toHollow(Vec3 real) {
        return real.add(offsetX, 0, offsetZ);
    }

    public Vec3 toReal(Vec3 hollow) {
        return hollow.subtract(offsetX, 0, offsetZ);
    }

    public BlockPos toHollow(BlockPos real) {
        return real.offset(offsetX, 0, offsetZ);
    }

    public BlockPos toReal(BlockPos hollow) {
        return hollow.offset(-offsetX, 0, -offsetZ);
    }

    public long createdGameTime() {
        return createdGameTime;
    }

    public Phase phase() {
        return phase;
    }

    void setPhase(Phase phase, long tick) {
        this.phase = phase;
        phaseStartTick = tick;
        readyTicks = 0;
    }

    /** The blocks the player placed in the slot, by position in the hollow ({@link BlockPos#asLong}). */
    Long2ObjectMap<Block> placed() {
        return Long2ObjectMaps.unmodifiable(placed);
    }

    /** Records that the player placed {@code block} at {@code pos} (of the hollow); true if that is news. */
    boolean markPlaced(BlockPos pos, Block block) {
        return placed.put(pos.asLong(), block) != block;
    }

    /** True if {@code pos} (of the hollow) held a block of the player. */
    boolean unmarkPlaced(BlockPos pos) {
        return placed.remove(pos.asLong()) != null;
    }

    /** Forgets all the player's blocks (they were given back). */
    void clearPlaced() {
        placed.clear();
    }

    /** Why the player part ended, once the event is {@link Phase#CLEARING}; null before. */
    public End end() {
        return end;
    }

    void setEnd(End end) {
        this.end = end;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("player", player);
        tag.putString("playerName", playerName);
        tag.put("origin", origin.save());
        if (exit != null) {
            tag.put("exit", exit.save());
        }
        tag.putInt("slot", slot);
        tag.putIntArray("box", new int[]{box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()});
        tag.putInt("offsetX", offsetX);
        tag.putInt("offsetZ", offsetZ);
        tag.putLong("created", createdGameTime);
        tag.putString("phase", phase.name());
        if (end != null) {
            tag.putString("end", end.name());
        }
        if (outcome != null) {
            tag.putString("outcome", outcome.name());
        }
        if (deathDrops != null) {
            tag.put("deathDrops", deathDrops.save());
        }
        if (!placed.isEmpty()) {
            long[] positions = new long[placed.size()];
            ListTag blocks = new ListTag();
            int i = 0;
            for (Long2ObjectMap.Entry<Block> entry : placed.long2ObjectEntrySet()) {
                positions[i++] = entry.getLongKey();
                blocks.add(StringTag.valueOf(BuiltInRegistries.BLOCK.getKey(entry.getValue()).toString()));
            }
            tag.putLongArray("placed", positions);
            tag.put("placedBlocks", blocks);
        }
        return tag;
    }

    /** @throws RuntimeException if the tag is not a saved event */
    static HollowEvent load(CompoundTag tag) {
        int[] b = tag.getIntArray("box");
        if (b.length != 6 || !tag.hasUUID("player")) {
            throw new IllegalArgumentException("Not a hollow event: " + tag);
        }
        HollowEvent event = new HollowEvent(tag.getUUID("player"), tag.getString("playerName"),
                Origin.load(tag.getCompound("origin")), tag.getInt("slot"),
                new HollowBox(b[0], b[1], b[2], b[3], b[4], b[5]), tag.getInt("offsetX"), tag.getInt("offsetZ"),
                tag.getLong("created"), Phase.valueOf(tag.getString("phase")));
        if (tag.contains("exit", Tag.TAG_COMPOUND)) {
            event.exit = Origin.load(tag.getCompound("exit"));
        }
        for (End end : End.values()) {
            if (end.name().equals(tag.getString("end"))) {
                event.end = end;
            }
        }
        for (HollowOutcome outcome : HollowOutcome.values()) {
            if (outcome.name().equals(tag.getString("outcome"))) {
                event.outcome = outcome;
            }
        }
        if (tag.contains("deathDrops", Tag.TAG_COMPOUND)) {
            event.deathDrops = Origin.load(tag.getCompound("deathDrops"));
        }
        long[] positions = tag.getLongArray("placed");
        ListTag blocks = tag.getList("placedBlocks", Tag.TAG_STRING);
        for (int i = 0; i < positions.length && i < blocks.size(); i++) {
            // A block unknown now (its mod removed) reads as air and is left out.
            ResourceLocation id = ResourceLocation.tryParse(blocks.getString(i));
            Block block = id == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(id);
            if (!block.defaultBlockState().isAir()) {
                event.placed.put(positions[i], block);
            }
        }
        return event;
    }

    @Override
    public String toString() {
        return "hollow event of " + playerName + " (" + player + "), slot " + slot + ", " + phase.id();
    }
}
