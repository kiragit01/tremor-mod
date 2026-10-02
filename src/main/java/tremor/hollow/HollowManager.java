package tremor.hollow;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BucketPickup;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import tremor.Tremor;
import tremor.config.TremorConfig;
import tremor.network.TremorBlackoutPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;

/**
 * Runs the events of the hollow (SPEC 9, 12; stage 4a): an event moves one player into a copy of the terrain around
 * the player in {@code tremor:hollow} and back, with the screen dark around each move ({@link TremorBlackoutPayload}).
 * The phases ({@link HollowEvent.Phase}):
 * <ol>
 *   <li>COPYING: the screen fades to black; the slot's chunks load (a ticket keeps them loaded until the event is
 *   over), the slot is emptied (normally it is: this only removes what a crash may have left there) and the terrain
 *   is copied into it, with a closing shell around, a piece at a time within {@code hollow.budgetMillis} per tick
 *   ({@link TerrainCopier}); once the light engine is through and the fade is over, the player is moved to the same
 *   place and rotation in the copy;</li>
 *   <li>ENTERING: the screen stays black until the client has the terrain around the player for
 *   {@code hollow.settleTicks} ticks, then fades in;</li>
 *   <li>INSIDE: until {@link #leave} (stage 4c plays the level here);</li>
 *   <li>LEAVING and RETURNING: the same in reverse, back to the origin (or another exit), next to it if somebody built
 *   over it meanwhile ({@link StandSpots});</li>
 *   <li>CLEARING: what is the player's in the slot (the blocks the player placed, items, experience, pets) is given
 *   back at the exit, or where the player was swallowed if the player died; then the slot (the copy's chunk columns
 *   and a margin, full height) is emptied the same way, and the event is gone and the slot free for the next one.</li>
 * </ol>
 * Safety (SPEC 9: after leaving the game or a crash inside the hollow the player is back at the origin on the next
 * login; others cannot follow): a player who dies in the hollow, leaves it by other means or logs out ends the event,
 * and the slot is cleared. The way back is saved with the player while the player is in the hollow, so it is in the
 * same file as the position after any crash. Nobody stays in the hollow without an event there: anybody else in it
 * (logged in inside, pulled back in by an ender pearl, teleported in by a command the rules did not stop) is moved
 * out at once, to that way back, else to the exit of the player's latest event, else to the respawn point. After a
 * restart every event left in the saved data only has its slot cleared, the player's things given back first.
 * <p>
 * Event handlers are registered by {@link tremor.Tremor}. Server thread only.
 */
public final class HollowManager {
    /** Keeps the chunks of a slot loaded from the start of its event until it is cleared. */
    private static final TicketType<ChunkPos> TICKET =
            TicketType.create(Tremor.MODID + "_hollow", Comparator.comparingLong(ChunkPos::toLong));
    /** An event whose slot is not loaded after this many ticks stops before the player is moved. */
    private static final int LOAD_TIMEOUT_TICKS = 200;
    /** After a move the screen fades back in after this many ticks at the latest, even if the client seems slow. */
    private static final int MAX_DARK_TICKS = 200;
    /** The client has the terrain once every chunk this close to the player (Chebyshev, chunks) was sent to it. */
    private static final int READY_RADIUS = 2;
    /** A player who could not be moved out of the hollow (another mod stopped it) is tried again this much later. */
    private static final int RETRY_TICKS = 100;
    /** Key of the way back out ({@link Origin}) in the persistent data of a player inside the hollow. */
    private static final String WAY_BACK = Tremor.MODID + ":hollow_way_back";

    private static State state;
    /** The entity this class is moving at this moment ({@link #isMoving}), or null. */
    private static Entity moving;
    /** Told about every event that ends ({@link #addEndListener}). */
    private static final List<EndListener> END_LISTENERS = new CopyOnWriteArrayList<>();

    private HollowManager() {
    }

    /** Told when the player part of an event ends (SPEC 9: the Awakening that swallowed the player ends with it). */
    @FunctionalInterface
    public interface EndListener {
        /**
         * The event has just entered CLEARING ({@code why} is its {@link HollowEvent#end()}); server thread. Also
         * called for the events a (re)start finds in the saved data, before anything else of the hollow runs.
         */
        void ended(HollowEvent event, HollowEvent.End why);
    }

    /** Adds a listener told about every event that ends (for the mod's setup); one that throws is logged. */
    public static void addEndListener(EndListener listener) {
        END_LISTENERS.add(listener);
    }

    /** Why an event could not start or change ({@link #enter}, {@link #leave}); the message is for the player. */
    public static final class Refusal extends Exception {
        Refusal(String message) {
            super(message);
        }
    }

    /** Where things of a player in the hollow go instead of staying there ({@link #dropSite}, and the clearing). */
    record DropSite(ServerLevel level, Vec3 position) {
    }

    // ---- API ----

    /**
     * Starts an event for {@code player} at the player's position (SPEC 9, the swallowing): the screen fades to black,
     * the terrain around is copied into a free slot, and the player is moved into the copy.
     *
     * @throws Refusal if the hollow is missing, the player is in it or in an event already, too many events run, the
     *                 player is outside the hollow's build height, or no slot is free
     */
    public static HollowEvent enter(ServerPlayer player) throws Refusal {
        State s = require(player.server);
        if (HollowDimension.is(player.level())) {
            throw new Refusal("Already in the hollow");
        }
        if (s.data.active(player.getUUID()) != null) {
            throw new Refusal(player.getGameProfile().getName() + " is in a hollow event already");
        }
        TremorConfig.Hollow config = TremorConfig.COMMON.hollow;
        if (s.data.activeCount() >= config.maxEvents.get()) {
            throw new Refusal("Already " + s.data.activeCount() + " hollow events (hollow.maxEvents)");
        }
        Origin origin = Origin.of(player);
        BlockPos at = origin.blockPos();
        ServerLevel level = player.serverLevel();
        HollowBox box = HollowBox.around(at.getX(), at.getY(), at.getZ(), config.radius.get(), config.below.get(),
                config.above.get(), Math.max(level.getMinBuildHeight(), s.hollow.getMinBuildHeight()),
                Math.min(level.getMaxBuildHeight(), s.hollow.getMaxBuildHeight()));
        if (box == null) {
            throw new Refusal("y " + at.getY() + " is outside the build height of the hollow");
        }
        // The slots lie around the middle of the world border, so that some fit inside it wherever it is.
        int middleX = SlotLayout.middleChunk(s.hollow.getWorldBorder().getCenterX());
        int middleZ = SlotLayout.middleChunk(s.hollow.getWorldBorder().getCenterZ());
        IntUnaryOperator dx = index -> (middleX + SlotLayout.chunkX(index) - (at.getX() >> 4)) << 4;
        IntUnaryOperator dz = index -> (middleZ + SlotLayout.chunkZ(index) - (at.getZ() >> 4)) << 4;
        int slot = SlotLayout.lowestFree(index -> s.taken(box, dx.applyAsInt(index), dz.applyAsInt(index)),
                index -> s.fits(box, dx.applyAsInt(index), dz.applyAsInt(index)));
        if (slot < 0) {
            throw new Refusal("No free slot in the hollow (inside the world border)");
        }
        HollowEvent event = new HollowEvent(player.getUUID(), player.getGameProfile().getName(), origin, slot, box,
                dx.applyAsInt(slot), dz.applyAsInt(slot), level.getGameTime(), HollowEvent.Phase.COPYING);
        event.phaseStartTick = s.server.getTickCount();
        s.data.add(event);
        // Not in the hollow, so a way back still stored with the player is stale.
        forgetWayBack(player);
        s.holdTicket(event);
        blackout(player, true, config.fadeTicks.get());
        Tremor.LOGGER.info("Hollow: {} starts at {} in {}, box {}", event, String.format(Locale.ROOT, "%.1f %.1f %.1f",
                origin.position().x, origin.position().y, origin.position().z), origin.dimension().location(), box);
        return event;
    }

    /**
     * Moves {@code player} out of the hollow: the screen fades to black, the player is moved to {@code exit} (null: the
     * origin) and the slot is cleared. An event that is still copying just stops.
     *
     * @throws Refusal if the player is in no event, or already on the way out
     */
    public static void leave(ServerPlayer player, Origin exit) throws Refusal {
        State s = require(player.server);
        HollowEvent event = s.data.active(player.getUUID());
        if (event == null) {
            throw new Refusal(player.getGameProfile().getName() + " is in no hollow event");
        }
        long now = s.server.getTickCount();
        switch (event.phase()) {
            case COPYING -> {
                blackout(player, false, 0);
                s.end(event, HollowEvent.End.CANCELLED, now);
            }
            case ENTERING, INSIDE -> {
                event.setExit(exit);
                event.setPhase(HollowEvent.Phase.LEAVING, now);
                s.data.setDirty();
                if (HollowDimension.is(player.level())) {
                    storeWayBack(player, event.exit());
                }
                blackout(player, true, TremorConfig.COMMON.hollow.fadeTicks.get());
            }
            default -> throw new Refusal(player.getGameProfile().getName() + " is on the way out already");
        }
    }

    /**
     * Ends every event at once (SPEC 14.1, {@code /tremor restore}): players inside are moved back to their exits
     * without a fade (offline ones on their next login), anybody else in the hollow on the next tick, and all slots
     * are cleared. Returns the number of events ended.
     */
    public static int restore(MinecraftServer server) {
        State s = state(server);
        if (s == null) {
            return 0;
        }
        long now = server.getTickCount();
        int ended = 0;
        for (HollowEvent event : List.copyOf(s.data.events())) {
            if (event.phase() != HollowEvent.Phase.CLEARING) {
                s.abort(event, HollowEvent.End.RESTORED, now);
                ended++;
            }
        }
        return ended;
    }

    /** The player's event that is not yet clearing its slot, or null. */
    public static HollowEvent event(ServerPlayer player) {
        State s = state(player.server);
        return s == null ? null : s.data.active(player.getUUID());
    }

    /** All events, those clearing their slot included; empty if the hollow is missing. */
    public static List<HollowEvent> events(MinecraftServer server) {
        State s = state(server);
        return s == null ? List.of() : s.data.events();
    }

    /** Whether the dimension {@code tremor:hollow} exists (its datapack files loaded). */
    public static boolean available(MinecraftServer server) {
        return state(server) != null;
    }

    /**
     * The event (in any phase, clearing included) whose area ({@link HollowEvent#area}: the copy's chunk columns and a
     * margin, full height; where the player may build) contains {@code pos} of {@code level}; null if there is none or
     * {@code level} is not the hollow. The slots never overlap, so there is at most one.
     */
    public static HollowEvent eventAt(ServerLevel level, BlockPos pos) {
        State s = HollowDimension.is(level) ? state(level.getServer()) : null;
        return s == null ? null : s.eventAt(pos);
    }

    /**
     * Whether the block at {@code pos} of the hollow was placed there by the player of the event (SPEC 12: the
     * player's own, not a copy): it drops as usual when broken, and is given back when the event ends.
     */
    public static boolean isPlayerPlaced(ServerLevel level, BlockPos pos) {
        HollowEvent event = eventAt(level, pos);
        return event != null && event.placed().containsKey(pos.asLong());
    }

    /**
     * Records that the block now at {@code pos} of the hollow was placed by a player (call it once the block is in
     * place); a no-op for air and outside the area of an event. Saved with the event.
     */
    public static void markPlaced(ServerLevel level, BlockPos pos) {
        HollowEvent event = eventAt(level, pos);
        BlockState placed = level.getBlockState(pos);
        if (event != null && !placed.isAir() && event.markPlaced(pos, placed.getBlock())) {
            state.data.setDirty();
        }
    }

    /** Forgets the player's block at {@code pos} of the hollow (it was broken or taken); a no-op if there is none. */
    public static void unmarkPlaced(ServerLevel level, BlockPos pos) {
        HollowEvent event = eventAt(level, pos);
        if (event != null && event.unmarkPlaced(pos)) {
            state.data.setDirty();
        }
    }

    /**
     * Whether {@code entity} is being moved by this class right now (a player into the hollow or out of it, a pet or
     * experience to a drop site): the rules that keep everything else from entering the hollow let it through.
     */
    public static boolean isMoving(Entity entity) {
        return entity == moving;
    }

    /** Whether {@code player} has an event in a phase in which the player is (or should be) in the hollow. */
    public static boolean insideEvent(ServerPlayer player) {
        HollowEvent event = event(player);
        return event != null && event.phase().inHollow();
    }

    /**
     * Where things of {@code player}, who is in the hollow, go instead of staying there (death drops, what is left in
     * the crafting grid at a logout, a pet): where the player comes back out, or, for a player who died in an event,
     * the place the player was swallowed (SPEC 9: the things lie there); its chunk is loaded so nothing put there is
     * lost. Null if the player is not in the hollow or nothing is known.
     */
    static DropSite dropSite(ServerPlayer player) {
        State s = HollowDimension.is(player.level()) ? state(player.server) : null;
        if (s == null) {
            return null;
        }
        HollowEvent event = s.data.active(player.getUUID());
        Origin to = event == null || !event.phase().inHollow() ? s.wayOut(player)
                : player.isAlive() ? event.exit() : event.origin();
        return to == null ? null : s.site(to);
    }

    // ---- events ----

    public static void onServerTick(ServerTickEvent.Post event) {
        State s = state(event.getServer());
        if (s != null) {
            s.tick();
        }
    }

    /**
     * Ends the player's event. A player inside the hollow is saved there by the logout that follows, with the way back
     * stored when the player was moved in, so the next login moves the player out even after a crash. Whatever of the
     * player's stays in the hollow is given back with the rest of the slot.
     */
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        State s = state(player.server);
        if (s == null) {
            return;
        }
        s.retries.remove(player.getUUID());
        HollowEvent hollowEvent = s.data.active(player.getUUID());
        if (hollowEvent != null) {
            s.logout(hollowEvent, s.server.getTickCount());
        }
    }

    /**
     * The saved data was written with the levels; only the runtime state goes. Not on stopping: the players are
     * logged out after that, and their events end then.
     */
    public static void onServerStopped(ServerStoppedEvent event) {
        state = null;
        moving = null;
    }

    // ---- internals ----

    private static State state(MinecraftServer server) {
        if (state == null || state.server != server) {
            state = new State(server);
        }
        return state.hollow != null ? state : null;
    }

    private static State require(MinecraftServer server) throws Refusal {
        State s = state(server);
        if (s == null) {
            throw new Refusal("The dimension tremor:hollow is missing (its datapack files did not load)");
        }
        return s;
    }

    /** Runs {@code teleport}, a move of {@code entity} by this class ({@link #isMoving}). */
    private static void move(Entity entity, Runnable teleport) {
        Entity outer = moving;
        moving = entity;
        try {
            teleport.run();
        } finally {
            moving = outer;
        }
    }

    /** The way back out of the hollow stored with the player, or null. */
    private static Origin wayBack(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(WAY_BACK, Tag.TAG_COMPOUND)) {
            return null;
        }
        try {
            return Origin.load(data.getCompound(WAY_BACK));
        } catch (RuntimeException e) {
            Tremor.LOGGER.warn("Hollow: unreadable way back of {}", player.getGameProfile().getName(), e);
            return null;
        }
    }

    /**
     * Stores the way back out with a player in the hollow: it is saved in the player's file with the player's position
     * (vanilla persistent data, not kept by a respawn), so after a crash both are from the same moment.
     */
    private static void storeWayBack(ServerPlayer player, Origin to) {
        player.getPersistentData().put(WAY_BACK, to.save());
    }

    private static void forgetWayBack(ServerPlayer player) {
        player.getPersistentData().remove(WAY_BACK);
    }

    /**
     * Where the player is put at {@code pos} of {@code level}: there if the player fits (crawling at least) with no
     * lava or fire, else at the first such place close by ({@link StandSpots}), else there all the same.
     */
    private static Vec3 standSpot(ServerLevel level, ServerPlayer player, Vec3 pos) {
        EntityDimensions size = player.getDimensions(Pose.SWIMMING);
        for (StandSpots.Offset offset : StandSpots.offsets()) {
            Vec3 at = pos.add(offset.dx(), offset.dy(), offset.dz());
            level.getChunkAt(BlockPos.containing(at));
            AABB box = size.makeBoundingBox(at);
            if (level.noCollision(player, box) && level.getBlockStatesIfLoaded(box).noneMatch(HollowManager::burns)) {
                return at;
            }
        }
        return pos;
    }

    private static boolean burns(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA) || state.is(BlockTags.FIRE);
    }

    /** Adds a copy of {@code stack} to {@code parcel}, topping up the stacks of the same item there first. */
    private static void pack(List<ItemStack> parcel, ItemStack stack) {
        ItemStack rest = stack.copy();
        for (ItemStack held : parcel) {
            if (rest.isEmpty()) {
                return;
            }
            if (ItemStack.isSameItemSameComponents(held, rest) && held.getCount() < held.getMaxStackSize()) {
                int moved = Math.min(rest.getCount(), held.getMaxStackSize() - held.getCount());
                held.grow(moved);
                rest.shrink(moved);
            }
        }
        if (!rest.isEmpty()) {
            parcel.add(rest);
        }
    }

    private static AABB aabb(HollowBox box) {
        return new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
    }

    private static void blackout(ServerPlayer player, boolean dark, int fadeTicks) {
        PacketDistributor.sendToPlayer(player, new TremorBlackoutPayload(dark, fadeTicks));
    }

    private static int fadeTicks() {
        return TremorConfig.COMMON.hollow.fadeTicks.get();
    }

    /** The client has the terrain around the player: every chunk near the player is loaded and was sent. */
    private static boolean clientReady(ServerPlayer player) {
        ServerChunkCache chunks = player.serverLevel().getChunkSource();
        ChunkPos at = player.chunkPosition();
        for (int z = at.z - READY_RADIUS; z <= at.z + READY_RADIUS; z++) {
            for (int x = at.x - READY_RADIUS; x <= at.x + READY_RADIUS; x++) {
                if (chunks.getChunkNow(x, z) == null || player.connection.chunkSender.isPending(ChunkPos.asLong(x, z))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The hollow of one running server: the saved events and what is going on with them now. */
    private static final class State {
        final MinecraftServer server;
        /** Null: the dimension is missing, nothing works. */
        final ServerLevel hollow;
        final HollowSavedData data;
        final TickBudget budget = new TickBudget(System::nanoTime);
        /** Players in the hollow who could not be moved out, and the tick to try again. */
        final Map<UUID, Long> retries = new HashMap<>();

        State(MinecraftServer server) {
            this.server = server;
            hollow = HollowDimension.level(server);
            if (hollow == null) {
                Tremor.LOGGER.error("The dimension tremor:hollow is missing (data/tremor/dimension/hollow.json did not "
                        + "load): the Awakening cannot take place");
                data = null;
                return;
            }
            data = HollowSavedData.get(server);
            resume();
        }

        /**
         * After a (re)start: nothing of a running event survives but its slot, which is cleared (the player's things
         * given back first). Its player, if inside, is moved out on login by the way back saved with the player.
         */
        private void resume() {
            long now = server.getTickCount();
            for (HollowEvent event : List.copyOf(data.events())) {
                if (event.phase() != HollowEvent.Phase.CLEARING) {
                    end(event, HollowEvent.End.SERVER_STOPPED, now);
                } else if (event.end() == null) {
                    event.setEnd(HollowEvent.End.SERVER_STOPPED);
                }
                holdTicket(event);
            }
        }

        void tick() {
            long now = server.getTickCount();
            sweep(now);
            budget.start((long) (TremorConfig.COMMON.hollow.budgetMillis.get() * 1e6));
            for (HollowEvent event : List.copyOf(data.events())) {
                try {
                    step(event, now);
                } catch (RuntimeException e) {
                    Tremor.LOGGER.error("Hollow: {} failed", event, e);
                    if (event.phase() == HollowEvent.Phase.CLEARING) {
                        // Give up on the slot rather than fail every tick; the next event in it empties it first.
                        releaseTicket(event);
                        data.remove(event);
                    } else {
                        abort(event, HollowEvent.End.ERROR, now);
                    }
                }
            }
        }

        private void step(HollowEvent event, long now) {
            switch (event.phase()) {
                case COPYING -> copying(event, now);
                case ENTERING -> arriving(event, now, true);
                case INSIDE -> inside(event, now);
                case LEAVING -> leaving(event, now);
                case RETURNING -> arriving(event, now, false);
                case CLEARING -> clearing(event, now);
            }
        }

        /**
         * Moves out of the hollow every living player in it without an event there (SPEC 9: nobody else enters it, and
         * nobody stays after the event): one who logged in inside after a logout, a crash or a restart, one whose event
         * {@code /tremor restore} ended or whose move out failed, one pulled back in by an ender pearl or teleported in
         * some other way. A dead player respawns outside anyway.
         */
        private void sweep(long now) {
            if (hollow.players().isEmpty()) {
                return;
            }
            for (ServerPlayer player : List.copyOf(hollow.players())) {
                UUID id = player.getUUID();
                HollowEvent event = data.active(id);
                if (!player.isAlive() || event != null && event.phase().inHollow()
                        || retries.getOrDefault(id, Long.MIN_VALUE) > now) {
                    continue;
                }
                Origin to = wayOut(player);
                if (to != null && moveOut(player, to) || respawn(player)) {
                    retries.remove(id);
                    Tremor.LOGGER.info("Hollow: moved {} (in the hollow without an event) out to {} {}",
                            player.getGameProfile().getName(), player.level().dimension().location(),
                            player.blockPosition().toShortString());
                } else {
                    retries.put(id, now + RETRY_TICKS);
                    Tremor.LOGGER.warn("Hollow: could not move {} out of the hollow (another mod stopped it)",
                            player.getGameProfile().getName());
                }
            }
        }

        /** Where a player in the hollow is moved out to: the way back stored, else the exit of the latest event. */
        Origin wayOut(ServerPlayer player) {
            Origin to = wayBack(player);
            HollowEvent latest = to == null ? data.latest(player.getUUID()) : null;
            return latest != null ? latest.exit() : to;
        }

        private void copying(HollowEvent event, long now) {
            ServerPlayer player = player(event);
            if (player == null || !player.isAlive() || player.level().dimension() != event.origin().dimension()) {
                // Logged out (handled on logout), died or changed dimension while it was getting dark.
                if (player != null) {
                    blackout(player, false, 0);
                }
                end(event, HollowEvent.End.CANCELLED, now);
                return;
            }
            if (!slotReady(event)) {
                if (now - event.phaseStartTick > LOAD_TIMEOUT_TICKS) {
                    Tremor.LOGGER.warn("Hollow: the slot of {} did not load in {} ticks", event, LOAD_TIMEOUT_TICKS);
                    blackout(player, false, 0);
                    end(event, HollowEvent.End.ERROR, now);
                }
                return;
            }
            ServerLevel origin = player.serverLevel();
            if (event.cursor == null) {
                // Empty the slot first. It is, unless the server died before an earlier event in it was saved: then
                // its copy is still there, but the event is not.
                HollowBox slot = slotArea(event);
                TerrainCopier.forgetTicks(hollow, slot);
                TerrainCopier.removeEntities(hollow, slot);
                event.cursor = new PieceCursor(slot);
                event.emptying = true;
                event.copyStats = new WorkStats();
            }
            if (event.emptying) {
                if (event.cursor.hasNext()) {
                    run(event, event.copyStats, piece -> TerrainCopier.clear(hollow, piece, event.copyStats));
                    return;
                }
                event.emptying = false;
                // The shell stays inside the build height of both levels, as the box does.
                event.cursor = new PieceCursor(event.copyRegion(
                        Math.max(origin.getMinBuildHeight(), hollow.getMinBuildHeight()),
                        Math.min(origin.getMaxBuildHeight(), hollow.getMaxBuildHeight())));
            }
            HollowBox box = event.hollowBox();
            if (!work(event, event.copyStats, now, piece -> TerrainCopier.copy(origin, hollow, piece, box,
                    event.offsetX(), event.offsetZ(), event.copyStats))) {
                return;
            }
            if (now - event.phaseStartTick < fadeTicks()) {
                return;
            }
            Vec3 to = event.toHollow(event.origin().position());
            move(player, () -> player.teleportTo(hollow, to.x, to.y, to.z, Set.of(), event.origin().yRot(),
                    event.origin().xRot()));
            if (player.level() != hollow) {
                Tremor.LOGGER.warn("Hollow: could not move {} into the hollow (another mod stopped it)", event);
                blackout(player, false, 0);
                end(event, HollowEvent.End.ERROR, now);
                return;
            }
            player.resetFallDistance();
            storeWayBack(player, event.exit());
            event.setPhase(HollowEvent.Phase.ENTERING, now);
            data.setDirty();
        }

        /** ENTERING ({@code entering}) or RETURNING: fade in once the client has had the terrain for a while. */
        private void arriving(HollowEvent event, long now, boolean entering) {
            ServerPlayer player = player(event);
            if (player == null) {
                // Logging out ends events on its own; this only catches a player gone without a logout event.
                logout(event, now);
                return;
            }
            if (entering && !stillInside(event, player, now)) {
                return;
            }
            if (!entering && player.level().dimension() != event.exit().dimension()
                    && !HollowDimension.is(player.level())) {
                // Went on (through a portal at the exit) before the screen came back. One pulled back into the
                // hollow is moved out again by the sweep, and the screen comes back as usual.
                blackout(player, false, 0);
                end(event, HollowEvent.End.LEFT, now);
                return;
            }
            if (!player.isAlive()) {
                return;
            }
            event.readyTicks = clientReady(player) ? event.readyTicks + 1 : 0;
            if (event.readyTicks > TremorConfig.COMMON.hollow.settleTicks.get()
                    || now - event.phaseStartTick >= MAX_DARK_TICKS) {
                blackout(player, false, fadeTicks());
                if (entering) {
                    event.setPhase(HollowEvent.Phase.INSIDE, now);
                    data.setDirty();
                } else {
                    end(event, HollowEvent.End.LEFT, now);
                }
            }
        }

        private void inside(HollowEvent event, long now) {
            ServerPlayer player = player(event);
            if (player == null) {
                logout(event, now);
            } else {
                stillInside(event, player, now);
            }
        }

        private void leaving(HollowEvent event, long now) {
            ServerPlayer player = player(event);
            if (player == null) {
                logout(event, now);
                return;
            }
            if (!stillInside(event, player, now) || !player.isAlive() || now - event.phaseStartTick < fadeTicks()) {
                return;
            }
            if (moveOut(player, event.exit())) {
                event.setPhase(HollowEvent.Phase.RETURNING, now);
                data.setDirty();
            } else if (now - event.phaseStartTick >= MAX_DARK_TICKS) {
                // Moving back keeps failing (another mod stops it): give the screen back. The copy stays under the
                // player (see clearing) while the sweep tries again.
                Tremor.LOGGER.warn("Hollow: could not move {} out of the hollow", event);
                blackout(player, false, 0);
                end(event, HollowEvent.End.ERROR, now);
            }
        }

        private void clearing(HollowEvent event, long now) {
            HollowBox slot = slotArea(event);
            for (ServerPlayer player : hollow.players()) {
                if (slot.contains(player.getBlockX(), player.getBlockY(), player.getBlockZ())) {
                    // Never pull the ground from under a player: the event's own (dead, or a move out failed), or
                    // anybody else (the sweep moves them out).
                    return;
                }
            }
            if (!slotReady(event)) {
                return;
            }
            if (event.cursor == null) {
                collect(event, slot);
                TerrainCopier.forgetTicks(hollow, slot);
                event.cursor = new PieceCursor(slot);
                event.clearStats = new WorkStats();
            }
            if (!work(event, event.clearStats, now, piece -> TerrainCopier.clear(hollow, piece, event.clearStats))) {
                return;
            }
            // Whatever turned up while the slot was cleared (normally nothing) goes the same way.
            collect(event, slot);
            releaseTicket(event);
            data.remove(event);
        }

        /**
         * Gives the player back what is the player's in the slot (SPEC 12: copies yield nothing, but nothing of the
         * player's is lost), at the drop site ({@link HollowEvent#dropOrigin}): the blocks the player placed that are
         * still there ({@link #takePlaced}), and every item, arrow that can be picked up and content of a container
         * entity (copies never drop anything, so all of them are the player's), merged into full stacks that do not
         * despawn (the player may be offline, or dead and far away); experience orbs and owned creatures are moved
         * there as they are. Every other entity is removed.
         */
        private void collect(HollowEvent event, HollowBox slot) {
            List<ItemStack> parcel = new ArrayList<>();
            List<Entity> moved = new ArrayList<>();
            takePlaced(event, parcel);
            for (Entity entity : hollow.getEntities((Entity) null, aabb(slot), entity -> !(entity instanceof Player))) {
                if (entity instanceof ItemEntity item) {
                    pack(parcel, item.getItem());
                } else if (entity instanceof AbstractArrow arrow) {
                    if (arrow.pickup == AbstractArrow.Pickup.ALLOWED) {
                        pack(parcel, arrow.getPickupItemStackOrigin());
                    }
                } else if (entity instanceof ExperienceOrb
                        || entity instanceof OwnableEntity owned && owned.getOwnerUUID() != null) {
                    moved.add(entity);
                    continue;
                } else if (entity instanceof Container container) {
                    for (int i = 0; i < container.getContainerSize(); i++) {
                        pack(parcel, container.removeItemNoUpdate(i));
                    }
                }
                entity.discard();
            }
            if (parcel.isEmpty() && moved.isEmpty()) {
                return;
            }
            DropSite site = site(event.dropOrigin());
            for (Entity entity : moved) {
                send(entity, site);
            }
            Vec3 at = site.position();
            for (ItemStack stack : parcel) {
                ItemEntity item = new ItemEntity(site.level(), at.x, at.y, at.z, stack, 0, 0, 0);
                item.setUnlimitedLifetime();
                site.level().addFreshEntity(item);
            }
            Tremor.LOGGER.info("Hollow: {} gave back {} stacks and {} entities at {} {}", event, parcel.size(),
                    moved.size(), site.level().dimension().location(), BlockPos.containing(at).toShortString());
        }

        /**
         * Takes the blocks the player placed that are still in the slot out of it: each is mined as if with shears
         * and silk touch, so that it comes back as itself (a door or a bed once, a slab or candles as many as there
         * are, a shulker box with what it holds), and what other blocks hold spills into the slot (the sweep after
         * gives it back); a fluid comes back in its bucket. A block that turned into another one meanwhile (dirt
         * grown over, powder set) comes back as the block placed; one gone (broken, burnt, washed away) does not.
         */
        private void takePlaced(HollowEvent event, List<ItemStack> parcel) {
            if (event.placed().isEmpty()) {
                return;
            }
            ItemStack tool = new ItemStack(Items.SHEARS);
            tool.enchant(hollow.registryAccess().registryOrThrow(Registries.ENCHANTMENT)
                    .getHolderOrThrow(Enchantments.SILK_TOUCH), 1);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            Long2ObjectMap<Block> blocks = event.placed();
            for (long at : blocks.keySet().toLongArray()) {
                pos.set(at);
                Block placed = blocks.get(at);
                BlockState state = hollow.getBlockState(pos);
                if (!state.is(placed)) {
                    if (!state.canBeReplaced()) {
                        pack(parcel, new ItemStack(placed));
                    }
                } else if (placed instanceof LiquidBlock || placed instanceof PowderSnowBlock) {
                    pack(parcel, ((BucketPickup) placed).pickupBlock(null, hollow, pos, state));
                } else {
                    for (ItemStack drop : Block.getDrops(state, hollow, pos, hollow.getBlockEntity(pos), null, tool)) {
                        pack(parcel, drop);
                    }
                    // Without updates: nothing around reacts; only the block's own onRemove runs (a chest spills).
                    hollow.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
            event.clearPlaced();
            data.setDirty();
        }

        /** Moves an entity of the player's (an experience orb, a pet) to the drop site as it is. */
        private void send(Entity entity, DropSite site) {
            move(entity, () -> entity.changeDimension(new DimensionTransition(site.level(), site.position(), Vec3.ZERO,
                    entity.getYRot(), entity.getXRot(), DimensionTransition.DO_NOTHING)));
            if (!entity.isRemoved()) {
                Tremor.LOGGER.warn("Hollow: could not move {} out of the hollow (another mod stopped it)", entity);
            }
        }

        /** The drop site at {@code at}, its chunk loaded so nothing put there is lost; the world spawn if the dimension is gone. */
        DropSite site(Origin at) {
            ServerLevel level = server.getLevel(at.dimension());
            Vec3 pos = at.position();
            if (level == null) {
                level = server.overworld();
                pos = Vec3.atBottomCenterOf(level.getSharedSpawnPos());
            }
            level.getChunkAt(BlockPos.containing(pos));
            return new DropSite(level, pos);
        }

        /**
         * Runs the pieces of the event's job (its cursor) within the budget of this tick, but at least one, and queues
         * a wait for the light engine after each chunk column.
         */
        private void run(HollowEvent event, WorkStats stats, Consumer<PieceCursor.Piece> job) {
            long start = budget.now();
            event.cursor.run(budget, piece -> {
                job.accept(piece);
                if (piece.lastInColumn()) {
                    event.light.add(TerrainCopier.lightDone(hollow, piece.chunkX(), piece.chunkZ()));
                }
            });
            stats.addTick(budget.now() - start);
        }

        /**
         * Runs the event's job ({@link #run}); true once all is written and the light engine is through it. Then the
         * chunks go again to whoever watches them, and the cost is logged.
         */
        private boolean work(HollowEvent event, WorkStats stats, long now, Consumer<PieceCursor.Piece> job) {
            PieceCursor cursor = event.cursor;
            if (cursor.hasNext()) {
                run(event, stats, job);
                if (!cursor.hasNext()) {
                    event.workDoneTick = now;
                }
                return false;
            }
            if (event.light.isEmpty()) {
                return true;
            }
            for (CompletableFuture<?> future : event.light) {
                if (!future.isDone()) {
                    return false;
                }
            }
            event.light.clear();
            stats.lightDone((int) (now - event.workDoneTick));
            HollowBox box = cursor.box();
            for (int z = box.minChunkZ(); z <= box.maxChunkZ(); z++) {
                for (int x = box.minChunkX(); x <= box.maxChunkX(); x++) {
                    TerrainCopier.resend(hollow, x, z);
                }
            }
            Tremor.LOGGER.info("Hollow: {} {} {} blocks ({} changed, {} light checks) in {} ticks, {} ms of server "
                            + "time, worst tick {} ms; light done {} ticks later", event,
                    event.phase() == HollowEvent.Phase.CLEARING ? "cleared" : "copied", stats.blocks(),
                    stats.changed(), stats.lightChecks(), stats.ticks(),
                    String.format(Locale.ROOT, "%.1f", stats.totalMillis()),
                    String.format(Locale.ROOT, "%.2f", stats.worstMillis()), stats.lightTicks());
            return true;
        }

        /**
         * True if the player of an event that should be inside the hollow still is. A dead player waits there for the
         * respawn. Otherwise the player got out another way (the respawn after dying, a way the rules did not stop)
         * and the event ends.
         */
        private boolean stillInside(HollowEvent event, ServerPlayer player, long now) {
            if (HollowDimension.is(player.level())) {
                if (!player.isAlive()) {
                    event.died = true;
                }
                return true;
            }
            forgetWayBack(player);
            blackout(player, false, 0);
            end(event, event.died ? HollowEvent.End.DIED : HollowEvent.End.ESCAPED, now);
            return false;
        }

        /** The player of a running event logged out (or vanished): the event ends; the way back is with the player. */
        void logout(HollowEvent event, long now) {
            switch (event.phase()) {
                case COPYING -> end(event, HollowEvent.End.CANCELLED, now);
                case RETURNING -> end(event, HollowEvent.End.LEFT, now);
                default -> end(event, HollowEvent.End.LOGGED_OUT, now);
            }
        }

        /**
         * Ends a running event at once: a player inside is moved straight out (later if offline, or by the sweep if it
         * fails), the screen cleared.
         */
        void abort(HollowEvent event, HollowEvent.End why, long now) {
            ServerPlayer player = player(event);
            if (player != null) {
                if (event.phase().inHollow() && HollowDimension.is(player.level()) && player.isAlive()
                        && !moveOut(player, event.exit())) {
                    Tremor.LOGGER.warn("Hollow: could not move {} out of the hollow", event);
                }
                blackout(player, false, 0);
            }
            end(event, why, now);
        }

        /** The player part of the event is over: its slot is cleared from now on. */
        void end(HollowEvent event, HollowEvent.End why, long now) {
            event.setEnd(why);
            event.setPhase(HollowEvent.Phase.CLEARING, now);
            event.cursor = null;
            event.emptying = false;
            event.light.clear();
            data.setDirty();
            Tremor.LOGGER.info("Hollow: {} ended ({}), clearing the slot", event, why.id());
            for (EndListener listener : END_LISTENERS) {
                try {
                    listener.ended(event, why);
                } catch (RuntimeException e) {
                    Tremor.LOGGER.error("Hollow: a listener failed on the end of {}", event, e);
                }
            }
        }

        /**
         * Moves the player to {@code to}, or next to it if it is blocked ({@link #standSpot}); false if the player did
         * not get there (its dimension is gone, or another mod stopped the move). The player's ender pearls still
         * flying in the hollow are removed first: one landing later would pull the player back in.
         */
        private boolean moveOut(ServerPlayer player, Origin to) {
            ServerLevel level = server.getLevel(to.dimension());
            if (level == null) {
                return false;
            }
            if (player.level() == hollow) {
                for (ThrownEnderpearl pearl : hollow.getEntities(EntityType.ENDER_PEARL,
                        pearl -> pearl.getOwner() == player)) {
                    pearl.discard();
                }
            }
            Vec3 pos = standSpot(level, player, to.position());
            move(player, () -> player.teleportTo(level, pos.x, pos.y, pos.z, Set.of(), to.yRot(), to.xRot()));
            if (player.level() != level) {
                return false;
            }
            player.resetFallDistance();
            forgetWayBack(player);
            return true;
        }

        /**
         * Moves the player to the respawn point (bed, anchor without using a charge) or the world spawn; false if
         * another mod stopped it.
         */
        private boolean respawn(ServerPlayer player) {
            DimensionTransition to = player.findRespawnPositionAndUseSpawnBlock(true, DimensionTransition.DO_NOTHING);
            if (HollowDimension.is(to.newLevel())) {
                to = new DimensionTransition(server.overworld(), player, DimensionTransition.DO_NOTHING);
            }
            DimensionTransition transition = to;
            move(player, () -> player.changeDimension(transition));
            if (HollowDimension.is(player.level())) {
                return false;
            }
            player.resetFallDistance();
            forgetWayBack(player);
            return true;
        }

        /** Whether the slot of an event is loaded, entities included (asking for it to be if needed). */
        private boolean slotReady(HollowEvent event) {
            holdTicket(event);
            if (!event.slotLoaded) {
                event.slotLoaded = TerrainCopier.loaded(hollow, slotArea(event));
            }
            return event.slotLoaded;
        }

        void holdTicket(HollowEvent event) {
            if (!event.ticketHeld) {
                ChunkPos center = new ChunkPos(event.slotChunkX(), event.slotChunkZ());
                hollow.getChunkSource().addRegionTicket(TICKET, center, ticketDistance(event), center);
                event.ticketHeld = true;
            }
        }

        void releaseTicket(HollowEvent event) {
            if (event.ticketHeld) {
                ChunkPos center = new ChunkPos(event.slotChunkX(), event.slotChunkZ());
                hollow.getChunkSource().removeRegionTicket(TICKET, center, ticketDistance(event), center);
                event.ticketHeld = false;
            }
        }

        private int ticketDistance(HollowEvent event) {
            return slotArea(event).chunkDistance(event.slotChunkX(), event.slotChunkZ());
        }

        private HollowBox slotArea(HollowEvent event) {
            return event.slotArea(hollow.getMinBuildHeight(), hollow.getMaxBuildHeight());
        }

        /** Whether the slot of the copy of {@code box} shifted by {@code dx, dz} lies inside the world border. */
        boolean fits(HollowBox box, int dx, int dz) {
            return hollow.getWorldBorder().isWithinBounds(aabb(HollowEvent.slotArea(box.offset(dx, 0, dz),
                    hollow.getMinBuildHeight(), hollow.getMaxBuildHeight())));
        }

        /**
         * Whether the slot of the copy of {@code box} shifted by {@code dx, dz} overlaps that of an event (one laid out
         * around another middle, if the world border moved since).
         */
        boolean taken(HollowBox box, int dx, int dz) {
            HollowBox slot = HollowEvent.slotArea(box.offset(dx, 0, dz), hollow.getMinBuildHeight(),
                    hollow.getMaxBuildHeight());
            for (HollowEvent event : data.events()) {
                if (slotArea(event).intersects(slot)) {
                    return true;
                }
            }
            return false;
        }

        /** The event whose area contains {@code pos} of the hollow, or null. */
        HollowEvent eventAt(BlockPos pos) {
            for (HollowEvent event : data.events()) {
                if (event.area(hollow.getMinBuildHeight(), hollow.getMaxBuildHeight())
                        .contains(pos.getX(), pos.getY(), pos.getZ())) {
                    return event;
                }
            }
            return null;
        }

        private ServerPlayer player(HollowEvent event) {
            return server.getPlayerList().getPlayer(event.player());
        }
    }
}
