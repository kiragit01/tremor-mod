package tremor.world;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import tremor.config.TremorConfig;
import tremor.core.VoxelView;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link VoxelView} over a live level (server or client).
 * <p>
 * Never loads or waits for chunks: blocks are read only from chunks that are fully loaded right now
 * ({@code getChunkNow}). Voxels in other chunks and below the world are unknown: solid, but never surface.
 * Voxels above the build limit are open. A view caches the last chunk it touched, so use one per pass and thread.
 * <p>
 * Conductivity (SPEC 7.2) comes from the {@link ConductivityClass} of the block, whose value is in the COMMON config.
 * Unknown voxels conduct like air: a vibration does not cross terrain that is not loaded.
 */
public final class LevelVoxelView implements VoxelView {
    /** A non-full block still counts as ground if its collision box covers the whole footprint and this much volume. */
    private static final double MIN_SKIN_VOLUME = 0.8;
    private static final Map<BlockState, Boolean> NEARLY_FULL = new ConcurrentHashMap<>();
    /** Class of every block state seen so far; depends on tags only, so it is dropped when tags are reloaded. */
    private static final Map<BlockState, ConductivityClass> CONDUCTIVITY = new ConcurrentHashMap<>();

    /**
     * How well a block carries vibrations (SPEC 7.2). A block belongs to the first tagged class
     * ({@code #tremor:conductivity/<class>}) that contains it, in declaration order; an untagged block is
     * {@link #EARTH} if it has a collision shape (leaves excepted: they are {@link #INSULATING} by tag, and stay open
     * if a pack removes them from it), {@link #FLUID} if it holds a fluid, else {@link #AIR}.
     * <p>
     * The order matters where tags overlap: {@link #WOODEN} comes before {@link #STONY}, which takes the whole
     * {@code #minecraft:slabs} and {@code #minecraft:stairs} (they include the wooden ones), so wooden slabs and stairs
     * stay wooden; {@link #INSULATING} comes first so that a pack may tag anything soft over its material.
     */
    public enum ConductivityClass {
        /** Wool, carpets, leaves. */
        INSULATING(TremorTags.CONDUCTIVITY_INSULATING),
        /** Planks, logs, wooden slabs, stairs, fences..., ladders, scaffolding, wooden workstations. */
        WOODEN(TremorTags.CONDUCTIVITY_WOODEN),
        GRAVELLY(TremorTags.CONDUCTIVITY_GRAVELLY),
        /** Sand, soul sand and soil, snow (also powder snow). */
        SANDY(TremorTags.CONDUCTIVITY_SANDY),
        /**
         * Stone, deepslate, ores, bedrock, obsidian; masonry (bricks, prismarine, quartz, purpur, terracotta,
         * concrete) with all non-wooden slabs, stairs and walls; metal and mineral blocks.
         */
        STONY(TremorTags.CONDUCTIVITY_STONY),
        /** Any other solid block: dirt, grass and the baseline (1.0 by default). */
        EARTH(null),
        FLUID(null),
        AIR(null);

        private static final ConductivityClass[] TAGGED = {INSULATING, WOODEN, GRAVELLY, SANDY, STONY};

        private final TagKey<Block> tag;

        ConductivityClass(TagKey<Block> tag) {
            this.tag = tag;
        }

        /** The configured conductivity. */
        public float value() {
            TremorConfig.Common c = TremorConfig.COMMON;
            return (float) (switch (this) {
                case INSULATING -> c.conductivityInsulating;
                case WOODEN -> c.conductivityWooden;
                case GRAVELLY -> c.conductivityGravelly;
                case SANDY -> c.conductivitySandy;
                case STONY -> c.conductivityStony;
                case EARTH -> c.conductivityEarth;
                case FLUID -> c.conductivityFluid;
                case AIR -> c.conductivityAir;
            }).getAsDouble();
        }

        public static ConductivityClass of(BlockState state) {
            ConductivityClass c = CONDUCTIVITY.get(state);
            return c != null ? c : CONDUCTIVITY.computeIfAbsent(state, ConductivityClass::classify);
        }

        /**
         * The first class of {@link #TAGGED} whose tag holds the block; keep {@link #WOODEN} before {@link #STONY}
         * (wooden slabs and stairs are in both). Then the untagged fallbacks, leaves never being {@link #EARTH}.
         */
        private static ConductivityClass classify(BlockState state) {
            for (ConductivityClass c : TAGGED) {
                if (state.is(c.tag)) {
                    return c;
                }
            }
            if (!state.isAir() && !state.is(BlockTags.LEAVES)
                    && !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
                return EARTH;
            }
            return state.getFluidState().isEmpty() ? AIR : FLUID;
        }
    }

    /** Block tags were (re)loaded: classes are worked out again. Registered on the game bus by {@link tremor.Tremor}. */
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        CONDUCTIVITY.clear();
    }

    /** Conductivity of a block state, wherever it is. */
    public static float conductivity(BlockState state) {
        return ConductivityClass.of(state).value();
    }

    private final Level level;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private LevelChunk chunk;
    private long chunkKey = Long.MIN_VALUE;

    public LevelVoxelView(Level level) {
        this.level = level;
    }

    public Level level() {
        return level;
    }

    /** The block at the position if it is in a loaded chunk inside the world, else {@code null}. */
    public BlockState stateAt(int x, int y, int z) {
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
            return null;
        }
        long key = ChunkPos.asLong(x >> 4, z >> 4);
        if (key != chunkKey) {
            chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            chunkKey = key;
        }
        return chunk == null ? null : chunk.getBlockState(cursor.set(x, y, z));
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        if (y >= level.getMaxBuildHeight()) {
            return false;
        }
        BlockState state = stateAt(x, y, z);
        return state == null || isSkin(state, cursor);
    }

    @Override
    public boolean isKnown(int x, int y, int z) {
        return y >= level.getMaxBuildHeight() || stateAt(x, y, z) != null;
    }

    @Override
    public float conductivity(int x, int y, int z) {
        BlockState state = stateAt(x, y, z);
        return state == null ? ConductivityClass.AIR.value() : conductivity(state);
    }

    @Override
    public boolean isProtected(int x, int y, int z) {
        BlockState state = stateAt(x, y, z);
        return state == null || state.hasBlockEntity() || state.is(TremorTags.PROTECTED);
    }

    /**
     * Blocks that make up the crawlable skin of the world: full collidable blocks, opaque full cubes (mud, soul sand)
     * and near-full ground like dirt paths and farmland; never foliage.
     */
    public boolean isSkin(BlockState state, BlockPos pos) {
        if (state.isAir() || state.is(BlockTags.LEAVES)) {
            return false;
        }
        return state.isCollisionShapeFullBlock(level, pos) || state.isSolidRender(level, pos) || nearlyFull(state, pos);
    }

    private boolean nearlyFull(BlockState state, BlockPos pos) {
        if (state.getBlock().hasDynamicShape()) {
            return nearlyFull(state.getCollisionShape(level, pos));
        }
        return NEARLY_FULL.computeIfAbsent(state,
                s -> nearlyFull(s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)));
    }

    private static boolean nearlyFull(VoxelShape shape) {
        if (shape.isEmpty()) {
            return false;
        }
        AABB bounds = shape.bounds();
        if (bounds.minX > 0 || bounds.minZ > 0 || bounds.maxX < 1 || bounds.maxZ < 1 || bounds.minY > 0) {
            return false;
        }
        double volume = 0;
        for (AABB box : shape.toAabbs()) {
            volume += box.getXsize() * box.getYsize() * box.getZsize();
        }
        return volume >= MIN_SKIN_VOLUME;
    }
}
