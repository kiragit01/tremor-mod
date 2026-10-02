package tremor.hollow;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.AABB;
import tremor.Tremor;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Writes the copy of the terrain into a slot of the hollow and clears the slot again (SPEC 9, 12), one
 * {@link PieceCursor.Piece} at a time. Server thread only.
 * <p>
 * The blocks go straight into the chunk sections, as chunk generation fills them, not through {@code Level.setBlock}:
 * that runs every block's {@code onPlace} whatever the flags (sand falls, water and lava flow, powered TNT primes,
 * pistons extend) and starts block entity tickers. Everything else {@code LevelChunk.setBlockState} does for a changed
 * block is done the same way: the heightmaps, the section's emptiness and the sky light sources for the light engine,
 * a light check for every block whose light properties changed, so the light ends up exactly as after a {@code /fill},
 * and the points of interest (lightning rods, lodestones, beds...). No neighbour is notified and no tick is scheduled:
 * the copy stands still until something touches it.
 * The chunks are not sent while they change: the player arrives after the copy (and its light) is done and gets them
 * like any chunks; anyone already watching gets them again ({@link #resend}).
 * <p>
 * Block entities of the copy are props: a new, empty block entity of the right type is put in the chunk, so chests,
 * signs and banners look right, but it is never registered for ticking or as a game event listener (furnaces do not
 * burn, hoppers do not move items, spawners do not spawn, sculk does not listen). Only decorative data is copied
 * ({@link #DECORATIVE}), never what a container holds. Portals become air (SPEC 12: they would lead into the real
 * Nether or End and build portals there).
 * <p>
 * The copy is closed by a shell one block thick ({@link #shell}): caves cut by the edge of the box stay as dark as in
 * the real world. The real world is read only from chunks that are loaded; a part that is not is filled with stone.
 */
final class TerrainCopier {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState UNLOADED = Blocks.STONE.defaultBlockState();
    /** The heightmaps {@code LevelChunk.setBlockState} updates. */
    private static final Heightmap.Types[] HEIGHTMAPS = {Heightmap.Types.MOTION_BLOCKING,
            Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.WORLD_SURFACE};
    /** Block entities whose data only changes how they look: sign text, banner patterns, the owner of a head. */
    private static final Set<BlockEntityType<?>> DECORATIVE = Set.of(BlockEntityType.SIGN,
            BlockEntityType.HANGING_SIGN, BlockEntityType.BANNER, BlockEntityType.SKULL);

    private TerrainCopier() {
    }

    /** Where the states of a piece come from, by position in the level written to. */
    @FunctionalInterface
    private interface Source {
        BlockState at(int x, int y, int z);
    }

    /** Puts the prop block entity of a block that has one. */
    @FunctionalInterface
    private interface Props {
        void place(LevelChunk chunk, BlockPos pos, BlockState state);
    }

    /**
     * Copies {@code piece} of a slot (coordinates of the hollow) from {@code origin}, where it lies {@code dx},
     * {@code dz} blocks back (whole chunks). Inside {@code box} (the copy, coordinates of the hollow) every block is
     * copied; a piece may also hold blocks of the shell around it ({@link #shell}). The first piece of a chunk column
     * also copies the column's biomes, so grass, foliage, water, fog and weather look as in the real place.
     */
    static void copy(ServerLevel origin, ServerLevel hollow, PieceCursor.Piece piece, HollowBox box, int dx, int dz,
                     WorkStats stats) {
        LevelChunk target = loadedChunk(hollow, piece.chunkX(), piece.chunkZ());
        LevelChunk source = origin.getChunkSource().getChunkNow(piece.chunkX() - (dx >> 4), piece.chunkZ() - (dz >> 4));
        if (piece.firstInColumn()) {
            fillBiomes(hollow, target, source == null ? null
                    : (x, y, z, sampler) -> source.getNoiseBiome(x - QuartPos.fromBlock(dx), y, z - QuartPos.fromBlock(dz)));
        }
        if (source == null) {
            write(hollow, target, piece, (x, y, z) -> UNLOADED, null, stats);
            return;
        }
        // The offset is whole chunks: a position has the same place in its section in both levels.
        LevelChunkSection section = source.getSection(source.getSectionIndexFromSectionY(piece.sectionY()));
        BlockPos.MutableBlockPos real = new BlockPos.MutableBlockPos();
        write(hollow, target, piece, (x, y, z) -> {
            BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
            return box.contains(x, y, z) ? copyable(state) : shell(source, state, real.set(x - dx, y, z - dz));
        }, (chunk, pos, state) -> placeProp(hollow, chunk, pos, state, source, pos.offset(-dx, 0, -dz)), stats);
    }

    /** Empties {@code piece} of a slot; the first piece of a chunk column also gives the column the void biome back. */
    static void clear(ServerLevel hollow, PieceCursor.Piece piece, WorkStats stats) {
        LevelChunk target = loadedChunk(hollow, piece.chunkX(), piece.chunkZ());
        if (piece.firstInColumn()) {
            fillBiomes(hollow, target, null);
        }
        if (target.getSection(target.getSectionIndexFromSectionY(piece.sectionY())).hasOnlyAir()) {
            stats.addPiece(piece.volume(), 0, 0);
            return;
        }
        write(hollow, target, piece, (x, y, z) -> AIR, null, stats);
    }

    /**
     * Whether every chunk column of {@code area} is loaded and accessible in {@code level}, its entities included (they
     * are read after the blocks, so a sweep before would miss them).
     */
    static boolean loaded(ServerLevel level, HollowBox area) {
        for (int z = area.minChunkZ(); z <= area.maxChunkZ(); z++) {
            for (int x = area.minChunkX(); x <= area.maxChunkX(); x++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null || !chunk.getFullStatus().isOrAfter(FullChunkStatus.FULL)
                        || !level.areEntitiesLoaded(ChunkPos.asLong(x, z))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Drops the block and fluid ticks scheduled in {@code area} (water touched in the copy, a pressed button...). */
    static void forgetTicks(ServerLevel level, HollowBox area) {
        BoundingBox bounds = new BoundingBox(area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(),
                area.maxZ());
        level.getBlockTicks().clearArea(bounds);
        level.getFluidTicks().clearArea(bounds);
    }

    /** Removes every entity but players from {@code area}: dropped items, arrows, falling blocks, boats. */
    static void removeEntities(ServerLevel level, HollowBox area) {
        level.getEntities((Entity) null, new AABB(area.minX(), area.minY(), area.minZ(), area.maxX() + 1,
                area.maxY() + 1, area.maxZ() + 1), entity -> !(entity instanceof Player)).forEach(Entity::discard);
    }

    /** Completes on the light thread once the light engine went through everything queued for the chunk so far. */
    static CompletableFuture<?> lightDone(ServerLevel level, int chunkX, int chunkZ) {
        return level.getChunkSource().getLightEngine().waitForPendingTasks(chunkX, chunkZ);
    }

    /** Sends the chunk again to the players that already have it, with its blocks, biomes and light as they are now. */
    static void resend(ServerLevel level, int chunkX, int chunkZ) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk != null) {
            for (ServerPlayer player : level.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false)) {
                player.connection.chunkSender.markChunkPendingToSend(chunk);
            }
        }
    }

    /**
     * Writes the states of {@code source} into {@code piece} of {@code chunk}, top down, with what
     * {@code LevelChunk.setBlockState} does for every changed block except {@code onRemove}, {@code onPlace} and the
     * ticker of a new block entity (see the class comment), and the points of interest {@code Level.setBlock} keeps.
     * A replaced block entity is removed without dropping anything; {@code props} (if any) gives a new one to a block
     * that needs it.
     */
    private static void write(ServerLevel level, LevelChunk chunk, PieceCursor.Piece piece, Source source, Props props,
                              WorkStats stats) {
        LevelChunkSection section = chunk.getSection(chunk.getSectionIndexFromSectionY(piece.sectionY()));
        boolean wasEmpty = section.hasOnlyAir();
        LongArrayList checks = new LongArrayList();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        long changed = 0;
        for (int y = piece.maxY(); y >= piece.minY(); y--) {
            for (int z = piece.minZ(); z <= piece.maxZ(); z++) {
                for (int x = piece.minX(); x <= piece.maxX(); x++) {
                    BlockState state = source.at(x, y, z);
                    BlockState old = section.setBlockState(x & 15, y & 15, z & 15, state);
                    if (old == state) {
                        continue;
                    }
                    changed++;
                    pos.set(x, y, z);
                    if (old.hasBlockEntity()) {
                        chunk.removeBlockEntity(pos);
                    }
                    for (Heightmap.Types type : HEIGHTMAPS) {
                        chunk.getOrCreateHeightmapUnprimed(type).update(x & 15, y, z & 15, state);
                    }
                    if (LightEngine.hasDifferentLightProperties(chunk, pos, old, state)) {
                        chunk.getSkyLightSources().update(chunk, x & 15, y, z & 15);
                        checks.add(pos.asLong());
                    }
                    if (props != null && state.hasBlockEntity()) {
                        props.place(chunk, pos.immutable(), state);
                    }
                    if (PoiTypes.hasPoi(old) || PoiTypes.hasPoi(state)) {
                        updatePoi(level.getPoiManager(), pos, PoiTypes.forState(state));
                    }
                }
            }
        }
        if (changed > 0) {
            // In the order setBlockState queues them: a section is non-empty before the checks in it.
            ThreadedLevelLightEngine light = level.getChunkSource().getLightEngine();
            SectionPos sectionPos = SectionPos.of(chunk.getPos(), piece.sectionY());
            boolean nowEmpty = section.hasOnlyAir();
            if (wasEmpty && !nowEmpty) {
                light.updateSectionStatus(sectionPos, false);
            }
            for (int i = 0; i < checks.size(); i++) {
                light.checkBlock(BlockPos.of(checks.getLong(i)));
            }
            if (!wasEmpty && nowEmpty) {
                light.updateSectionStatus(sectionPos, true);
            }
            chunk.setUnsaved(true);
        }
        stats.addPiece(piece.volume(), changed, checks.size());
    }

    /**
     * The point of interest at {@code pos} becomes {@code type} (none if empty), as {@code ServerLevel.onBlockStateChange}
     * does it, but from what is registered there: a block of an earlier copy may not be.
     */
    private static void updatePoi(PoiManager pois, BlockPos pos, Optional<Holder<PoiType>> type) {
        Optional<Holder<PoiType>> registered = pois.getType(pos);
        if (!registered.equals(type)) {
            if (registered.isPresent()) {
                pois.remove(pos);
            }
            type.ifPresent(poi -> pois.add(pos.immutable(), poi));
        }
    }

    /**
     * The state a block of the real world gets in the copy: a moving piston (what moves lives in its block entity) and
     * a portal (nether, end, gateway: it would lead out into the real world) become air.
     */
    private static BlockState copyable(BlockState state) {
        return state.is(Blocks.MOVING_PISTON) || state.is(BlockTags.PORTALS) ? AIR : state;
    }

    /**
     * The state a block of the real world at {@code pos} gets in the shell, the layer just outside the copy. Above the
     * real surface (the highest block that blocks motion or holds a fluid, leaves not counted) it is air, so the
     * copy of the surface ends in the open. Below it the shell closes the copy: a full solid block stays as it is, and
     * anything else (the air of a cave, water, a torch) becomes stone. Without it the light of the empty hollow
     * around the copy would shine into every cave the edge of the box cuts, and water there would run out into the
     * void.
     */
    private static BlockState shell(LevelChunk source, BlockState state, BlockPos pos) {
        if (pos.getY() > source.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ())) {
            return AIR;
        }
        return state.isSolidRender(source, pos) ? state : UNLOADED;
    }

    /**
     * Gives the block at {@code pos} an empty block entity that is never ticked; for {@link #DECORATIVE} ones the
     * data of the original block entity at {@code sourcePos} of {@code source} is copied in.
     */
    private static void placeProp(ServerLevel hollow, LevelChunk chunk, BlockPos pos, BlockState state,
                                  LevelChunk source, BlockPos sourcePos) {
        if (!(state.getBlock() instanceof EntityBlock block)) {
            return;
        }
        BlockEntity prop = block.newBlockEntity(pos, state);
        if (prop == null) {
            return;
        }
        if (DECORATIVE.contains(prop.getType())) {
            BlockEntity original = source.getBlockEntity(sourcePos);
            if (original != null && original.getType() == prop.getType()) {
                HolderLookup.Provider registries = hollow.registryAccess();
                try {
                    prop.loadWithComponents(original.saveWithoutMetadata(registries), registries);
                } catch (RuntimeException e) {
                    Tremor.LOGGER.warn("Hollow: could not copy the look of the block entity at {}", sourcePos, e);
                }
            }
        }
        // Not addAndRegisterBlockEntity: that would give it a ticker and a game event listener.
        chunk.setBlockEntity(prop);
    }

    /** Fills the biomes of the whole chunk column from {@code resolver}, or with the void biome if it is null. */
    private static void fillBiomes(ServerLevel hollow, LevelChunk chunk, BiomeResolver resolver) {
        if (resolver == null) {
            Holder<Biome> none = hollow.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.THE_VOID);
            resolver = (x, y, z, sampler) -> none;
        }
        chunk.fillBiomesFromNoise(resolver, hollow.getChunkSource().randomState().sampler());
        chunk.setUnsaved(true);
    }

    private static LevelChunk loadedChunk(ServerLevel level, int chunkX, int chunkZ) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            throw new IllegalStateException("Chunk " + chunkX + " " + chunkZ + " of the hollow is not loaded");
        }
        return chunk;
    }
}
