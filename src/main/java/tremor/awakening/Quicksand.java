package tremor.awakening;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import tremor.Tremor;
import tremor.block.MireBlock;
import tremor.block.TremorBlocks;
import tremor.config.TremorConfig;
import tremor.core.math.Vec3;
import tremor.entity.Devour;
import tremor.entity.TremorSavedData;
import tremor.hollow.level.SinkTracker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The quicksand of an ambush ({@link tremor.core.behavior.Ambush}), in the real world, out of real blocks for a moment
 * (the user's idea): around each player an entity lying in wait has caught, the ground turns into {@code tremor:mire}
 * (mud that gives way in eighths and holds whoever is in it, {@link MireBlock}) and gives way deeper and deeper: a pit
 * where the entity lay in wait, as wide as stepping in springs it and a little more, deepest in the middle
 * ({@link #depth}, {@link #deepest}). The player sinks, slowly, as into quicksand, and every creature in the mud is taken
 * under ({@link Devour}). For the first {@code ambush.escapeSeconds} the player may still get out: caught at the edge,
 * one who backs out at once reaches the firm ground (in mud a player walks about 0.8 of a block a second, sprints and
 * jumps a little more than one, {@link #out}), one who walks on gets stuck; then the ground holds the player, the mud
 * spreads ({@link #radius}) and the columns under them give way as deep as the middle, until the eyes are under the mud
 * ({@link #sink}) and the hollow takes the player ({@link Awakening}).
 * <p>
 * Only ground that can come back as it was turns into mud ({@link #top}): two blocks of open air over the column,
 * then three blocks that are solid, without a block entity, no fluid, not unbreakable, and something firm under them
 * (so nobody falls through into a cave); an entity lies in wait only where most of the ground around can
 * ({@link #fits}). Every changed block remembers what it was and is put back once nobody is pulled in any more
 * ({@link #settle}): at once all around, the columns of a player swallowed but not yet moved into the hollow once they
 * are, and the ones a creature is in once it is out (or 30 seconds later, lifting it out). The changed blocks are kept
 * in the level's saved data as well, so that a crash leaves no mud behind ({@link #tick}: put back after a load).
 * Several players caught by one ambush share one quicksand. Server thread only.
 */
public final class Quicksand {
    /** Layers of the ground that turn into mud: the top block and the two under it. */
    static final int LAYERS = 3;
    /** How deep the middle of a funnel gives way at once (blocks)... */
    static final double FIRST_DEPTH = 0.4;
    /** ...and at the end of the pull, slowly at first and faster towards the end: the eyes are under at about 3/4. */
    static final double LAST_DEPTH = 2.2;
    /** All of the mud gives way at least this much (blocks): it holds whoever is in it, up to its edge. */
    static final double EDGE_DEPTH = 0.15;
    /** Deepest a column ever gives way: three layers, the last eighth of the third one holding. */
    static final double MAX_DEPTH = LAYERS - 1.0 / SinkTracker.MAX_SOFTNESS;
    /** The mud reaches this much (blocks) beyond where stepping in springs the ambush, while the player may get out... */
    static final double MARGIN = 0.5;
    /** ...and then spreads this much more by the end of the pull, once the player is held. */
    static final double SPREAD = 1.5;
    /** A player has got out once on firm ground at least this far (blocks) from where they stepped in. */
    static final double MOVED = 0.4;
    /** Of the columns within the radius around where it lies in wait, at least this share must be able to turn. */
    static final double MIN_FIT = 0.6;
    /** A column whose top block is lower than this (of a block) is no ground here (a slab, a path will do). */
    static final double MIN_TOP = 0.9;
    /** A released quicksand puts back the columns a creature stays in at the latest this long after (ticks). */
    static final int SETTLE_LIMIT_TICKS = 600;
    /** {@link #top} of a column that cannot turn into mud. */
    static final int NONE = Integer.MIN_VALUE;
    /** Sent to the clients, no neighbour updates or shape updates (as the hollow's mire). */
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final double EPSILON = 1e-3;

    /** Levels whose leftovers have been put back since the server started ({@link #tick}). */
    private static final Set<ResourceKey<Level>> CHECKED = new HashSet<>();
    /** Every quicksand with blocks still changed. */
    private static final List<Quicksand> ALL = new ArrayList<>();

    private enum Hold {
        /** Being pulled in. */
        PULLING,
        /** Under the mud, the screen dark, not moved into the hollow yet: the columns under the player stay. */
        SWALLOWED
    }

    /** A changed block: what it was, and how soft it is now. */
    private static final class Cell {
        final BlockState original;
        /** The top of the ground of its column before (the block above the top block's bottom). */
        final int surface;
        int softness;

        Cell(BlockState original, int surface) {
            this.original = original;
            this.surface = surface;
        }
    }

    private final ServerLevel level;
    /** The middle of the pit: where the entity lay in wait (the top of its skin). */
    private final Vec3 center;
    /** Radius of the mud as the pull starts. */
    private final double startRadius;
    private final Long2ObjectOpenHashMap<Cell> cells = new Long2ObjectOpenHashMap<>();
    /** The top block of each column looked at, or {@link #NONE}. */
    private final Long2IntOpenHashMap tops = new Long2IntOpenHashMap();
    /** The Awakenings whose targets it pulls or holds, and how. */
    private final Map<Awakening, Hold> victims = new IdentityHashMap<>();
    /** Game time all its victims were gone at, or -1 while it has some. */
    private long releasedAt = -1;

    /** The quicksand of an ambush lying in wait at {@code center}, sprung within {@code radius} of it. */
    Quicksand(ServerLevel level, Vec3 center, double radius) {
        this.level = level;
        this.center = center;
        this.startRadius = radius + MARGIN;
    }

    // ---- where ----

    /**
     * Whether quicksand may form around {@code center} (the top of the skin of a floor), {@code radius} wide: the
     * column at the centre can turn into mud ({@link #top}), and at least {@link #MIN_FIT} of the columns within the
     * radius can.
     */
    public static boolean fits(ServerLevel level, Vec3 center, double radius) {
        int nearY = Mth.floor(center.y() - EPSILON);
        int cx = Mth.floor(center.x());
        int cz = Mth.floor(center.z());
        if (top(level, cx, cz, nearY) == NONE) {
            return false;
        }
        int r = (int) Math.ceil(radius);
        int all = 0;
        int fit = 0;
        for (int z = cz - r; z <= cz + r; z++) {
            for (int x = cx - r; x <= cx + r; x++) {
                if (Math.hypot(x + 0.5 - center.x(), z + 0.5 - center.z()) <= radius) {
                    all++;
                    if (top(level, x, z, nearY) != NONE) {
                        fit++;
                    }
                }
            }
        }
        return fit >= MIN_FIT * all;
    }

    /**
     * The top block of the ground of column {@code x, z} near {@code nearY} (the first solid block from one above it
     * to two below), if the column can turn into mud: two blocks of open air over it, it and the two under it can
     * change ({@link #softenable}; the top one at least {@link #MIN_TOP} high, none higher than a block), and something
     * solid under them; else {@link #NONE}.
     */
    static int top(ServerLevel level, int x, int z, int nearY) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = nearY + 1; y >= nearY - 2; y--) {
            if (height(level, pos.set(x, y, z)) <= 0) {
                continue;
            }
            if (height(level, pos.set(x, y + 1, z)) > 0 || height(level, pos.set(x, y + 2, z)) > 0) {
                return NONE;
            }
            for (int layer = 0; layer < LAYERS; layer++) {
                pos.set(x, y - layer, z);
                double height = height(level, pos);
                if (!softenable(level, pos, level.getBlockState(pos)) || height > 1 || layer == 0 && height < MIN_TOP) {
                    return NONE;
                }
            }
            return height(level, pos.set(x, y - LAYERS, z)) > 0 ? y : NONE;
        }
        return NONE;
    }

    /** Whether a block may turn into mud and back: solid, no block entity, no fluid, breakable, none of ours. */
    static boolean softenable(ServerLevel level, BlockPos pos, BlockState state) {
        return !state.hasBlockEntity() && state.getFluidState().isEmpty() && state.getDestroySpeed(level, pos) >= 0
                && !state.getCollisionShape(level, pos).isEmpty() && !state.is(TremorBlocks.MIRE.get())
                && !state.is(TremorBlocks.HEART_NODE.get());
    }

    /** How high the collision of the block at {@code pos} reaches (blocks; 0 if it has none). */
    private static double height(ServerLevel level, BlockPos pos) {
        VoxelShape shape = level.getBlockState(pos).getCollisionShape(level, pos);
        return shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
    }

    // ---- the pull ----

    /**
     * Radius of the mud {@code progress} (0..1) into the pull, {@code start} at first: as wide until {@code escape} (the
     * share of the pull the player may still get out in), then it spreads, out to {@link #SPREAD} more by the end.
     */
    static double radius(double start, double progress, double escape) {
        double after = progress <= escape || escape >= 1 ? 0 : (progress - escape) / (1 - escape);
        return start + SPREAD * Math.min(1, after);
    }

    /** Depth of the middle of the pit {@code progress} (0..1) into the pull: at once a little, the most at the end. */
    static double deepest(double progress) {
        double p = Math.max(0, Math.min(1, progress));
        return FIRST_DEPTH + (LAST_DEPTH - FIRST_DEPTH) * Math.pow(p, 1.6);
    }

    /**
     * Depth of the pit {@code distance} blocks from its middle, {@code radius} wide, {@code deepest} in the middle:
     * {@link #EDGE_DEPTH} more everywhere inside, so that all of the mud holds; none beyond the radius.
     */
    static double depth(double distance, double radius, double deepest) {
        double s = distance / radius;
        return s >= 1 ? 0 : Math.min(MAX_DEPTH, EDGE_DEPTH + deepest * (1 - s * s));
    }

    /**
     * One tick of the pull on the target {@code player} of {@code victim}, {@code progress} (0..1) of the way (the
     * player may get out until {@code escape} of it): the pit gives way ({@link #depth}), and, the player held
     * ({@code held}), the columns under them as deep as its middle. A creature in the mud is taken.
     */
    void pull(Awakening victim, ServerPlayer player, double progress, double escape, boolean held) {
        victims.putIfAbsent(victim, Hold.PULLING);
        releasedAt = -1;
        double radius = radius(startRadius, progress, escape);
        double deepest = deepest(progress);
        int nearY = Mth.floor(center.y() - EPSILON);
        AABB body = player.getBoundingBox();
        int r = (int) Math.ceil(radius) + 1;
        int cx = Mth.floor(center.x());
        int cz = Mth.floor(center.z());
        for (int z = cz - r; z <= cz + r; z++) {
            for (int x = cx - r; x <= cx + r; x++) {
                double depth = depth(Math.hypot(x + 0.5 - center.x(), z + 0.5 - center.z()), radius, deepest);
                if (held && body.minX < x + 1 && body.maxX > x && body.minZ < z + 1 && body.maxZ > z) {
                    depth = Math.min(MAX_DEPTH, EDGE_DEPTH + deepest);
                }
                if (depth > 0) {
                    soften(x, z, nearY, depth);
                }
            }
        }
        devour(radius);
    }

    /** How deep the player is pulled in: 0 with the feet at the ground's top where they stepped in, 1 eyes under. */
    static double sink(ServerPlayer player, Vec3 focus) {
        return SinkTracker.sink(focus.y(), player.getY(), player.getEyeHeight(Pose.STANDING));
    }

    /**
     * Whether the target got out: standing on firm ground (not mud) as high as where they stepped in ({@code focus}),
     * at least {@link #MOVED} away from there.
     */
    boolean out(ServerPlayer player, Vec3 focus) {
        if (!player.onGround() || player.getY() < focus.y() - 0.5) {
            return false;
        }
        BlockPos under = BlockPos.containing(player.getX(), player.getY() - 0.2, player.getZ());
        return !level.getBlockState(under).is(TremorBlocks.MIRE.get())
                && Math.hypot(player.getX() - focus.x(), player.getZ() - focus.z()) >= MOVED;
    }

    /** Whether a target of it is still being pulled in: the ones under the mud wait for it ({@link Awakening}). */
    boolean pulling() {
        return victims.containsValue(Hold.PULLING);
    }

    /** The target of {@code victim} is under the mud: the rest of the ground may come back, theirs not yet. */
    void swallowed(Awakening victim) {
        victims.put(victim, Hold.SWALLOWED);
        settle(false);
    }

    /**
     * {@code victim} is done with it (moved into the hollow, got out, or its Awakening ended). A player still in the
     * mud (an Awakening called off) is lifted out onto the ground it stood on.
     */
    void release(Awakening victim, ServerPlayer player, Vec3 focus) {
        if (victims.remove(victim) == null) {
            return;
        }
        if (player != null && player.level() == level && player.getY() < focus.y() - 0.5
                && player.getY() > focus.y() - LAYERS - 2
                && Math.hypot(player.getX() - center.x(), player.getZ() - center.z()) < startRadius + SPREAD + 1) {
            player.teleportTo(player.getX(), focus.y(), player.getZ());
        }
        if (victims.isEmpty()) {
            releasedAt = level.getGameTime();
        }
        settle(false);
    }

    /**
     * Puts back what nobody needs: nothing while a target is pulled in; else every changed block but those of the
     * columns under a target swallowed and not moved into the hollow yet, and (unless {@code force}) those a creature is
     * in or on, which wait for it to leave. Forced, a creature in the way is lifted out onto the ground first.
     */
    private void settle(boolean force) {
        if (victims.containsValue(Hold.PULLING)) {
            return;
        }
        List<AABB> kept = new ArrayList<>();
        for (Awakening victim : victims.keySet()) {
            ServerPlayer player = victim.player();
            if (player != null && player.level() == level) {
                kept.add(player.getBoundingBox());
            }
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int restored = 0;
        for (ObjectIterator<Long2ObjectMap.Entry<Cell>> it = cells.long2ObjectEntrySet().fastIterator(); it.hasNext(); ) {
            Long2ObjectMap.Entry<Cell> entry = it.next();
            Cell cell = entry.getValue();
            pos.set(entry.getLongKey());
            AABB column = new AABB(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, cell.surface + 2,
                    pos.getZ() + 1);
            if (kept.stream().anyMatch(column::intersects)) {
                continue;
            }
            List<LivingEntity> in = level.getEntitiesOfClass(LivingEntity.class, column);
            if (!in.isEmpty()) {
                if (!force) {
                    continue;
                }
                for (LivingEntity entity : in) {
                    entity.teleportTo(entity.getX(), cell.surface, entity.getZ());
                }
            }
            if (level.getBlockState(pos).is(TremorBlocks.MIRE.get())) {
                level.setBlock(pos, cell.original, FLAGS);
                if (pos.getY() == cell.surface - 1) {
                    level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, cell.original),
                            pos.getX() + 0.5, cell.surface + 0.1, pos.getZ() + 0.5, 6, 0.3, 0.05, 0.3, 0);
                }
            }
            TremorSavedData.get(level).quicksandRestored(entry.getLongKey());
            it.remove();
            restored++;
        }
        if (restored > 0) {
            Tremor.LOGGER.info("Quicksand in {}: {} blocks back as they were, {} still mud", level.dimension().location(),
                    restored, cells.size());
        }
        if (cells.isEmpty()) {
            ALL.remove(this);
        }
    }

    /**
     * Softens column {@code x, z} (its top block near {@code nearY}) to {@code depth}: the top block, then the ones
     * under it ({@link SinkTracker#softness}). A block changed by something else meanwhile is not ours any more.
     */
    private void soften(int x, int z, int nearY, double depth) {
        long column = BlockPos.asLong(x, 0, z);
        int top = tops.containsKey(column) ? tops.get(column) : NONE;
        if (!tops.containsKey(column)) {
            top = top(level, x, z, nearY);
            tops.put(column, top);
        }
        if (top == NONE) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int layer = 0; layer < LAYERS; layer++) {
            int softness = SinkTracker.softness(depth, layer);
            if (softness <= 0) {
                break;
            }
            pos.set(x, top - layer, z);
            long key = pos.asLong();
            Cell cell = cells.get(key);
            BlockState now = level.getBlockState(pos);
            if (cell == null) {
                if (!softenable(level, pos, now)) {
                    break;
                }
                cell = new Cell(now, top + 1);
                cells.put(key, cell);
                TremorSavedData.get(level).quicksandChanged(key, now);
                if (!ALL.contains(this)) {
                    ALL.add(this);
                }
            } else if (!now.is(TremorBlocks.MIRE.get())) {
                cells.remove(key);
                TremorSavedData.get(level).quicksandRestored(key);
                break;
            }
            if (softness > cell.softness) {
                cell.softness = softness;
                level.setBlock(pos, TremorBlocks.MIRE.get().defaultBlockState().setValue(MireBlock.SOFTNESS, softness),
                        FLAGS);
            }
        }
    }

    /** A creature in the mud of the pit goes under, as one the bump touches. */
    private void devour(double radius) {
        if (!TremorConfig.COMMON.devourMobs.get()) {
            return;
        }
        AABB box = new AABB(center.x() - radius, center.y() - LAYERS - 1, center.z() - radius, center.x() + radius,
                center.y() + 1, center.z() + radius);
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, m -> Devour.takeable(level, m))) {
            BlockPos under = BlockPos.containing(mob.getX(), mob.getY() - 0.2, mob.getZ());
            if (level.getBlockState(under).is(TremorBlocks.MIRE.get())
                    || level.getBlockState(under.above()).is(TremorBlocks.MIRE.get())) {
                Devour.take(level, mob);
            }
        }
    }

    // ---- the level's ----

    /**
     * Once per level tick ({@link AwakeningManager#onLevelTick}): after a load, puts back what a quicksand left as mud
     * (the server stopped without its end: a crash); the quicksands whose targets are gone put back the columns a
     * creature was in once it is out, or {@value #SETTLE_LIMIT_TICKS} ticks after, lifting it out.
     */
    static void tick(ServerLevel level) {
        if (CHECKED.add(level.dimension())) {
            leftovers(level);
        }
        long now = level.getGameTime();
        for (Quicksand quicksand : List.copyOf(ALL)) {
            if (quicksand.level == level && quicksand.releasedAt >= 0) {
                quicksand.settle(now - quicksand.releasedAt >= SETTLE_LIMIT_TICKS);
            }
        }
    }

    /** The server stops: everything goes back now, whoever is in it, before the level is saved. */
    static void stopAll() {
        for (Quicksand quicksand : List.copyOf(ALL)) {
            quicksand.victims.clear();
            quicksand.settle(true);
        }
        ALL.clear();
        CHECKED.clear();
    }

    /** What the level's saved data says is mud from a quicksand that never ended goes back to what it was. */
    private static void leftovers(ServerLevel level) {
        TremorSavedData data = TremorSavedData.get(level);
        Map<Long, BlockState> left = data.quicksandLeft();
        if (left.isEmpty()) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int restored = 0;
        for (Iterator<Map.Entry<Long, BlockState>> it = left.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, BlockState> entry = it.next();
            pos.set(entry.getKey());
            if (level.getBlockState(pos).is(TremorBlocks.MIRE.get())) {
                level.setBlock(pos, entry.getValue(), FLAGS);
                restored++;
            }
            data.quicksandRestored(entry.getKey());
        }
        Tremor.LOGGER.info("Quicksand in {}: {} blocks left as mud (the server stopped during an ambush) put back",
                level.dimension().location(), restored);
    }
}
