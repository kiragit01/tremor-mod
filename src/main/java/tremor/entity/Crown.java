package tremor.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;

/**
 * A tree is dragged under the ground with a player on it (the user: up on leaves the tremor could not get them, and a
 * swallowing there went wrong; leaves crumbling away under the feet looked like blocks set to air): the tree under the
 * player, its natural leaves and its logs ({@link #tree}), sinks whole into the ground at {@value #SPEED} blocks a
 * tick, shown by block displays sliding down, nothing dropping; a player up in it floats down with it (slow falling:
 * free to walk, jump or leap off, no fall damage), never held.
 * Leaves a player laid there themselves: only the column under them ({@link #below}). Kept per level in memory;
 * server thread only, ticked by {@link #onLevelTick}, registered by {@code tremor.Tremor}.
 */
public final class Crown {
    /** How fast (blocks a tick) a tree sinks. */
    static final double SPEED = 0.15;
    /** Players up in it fall slowly while it sinks and this many ticks more ({@link #sink}). */
    static final int RIDE_EXTRA_TICKS = 20;
    /** A tree is taken this far (blocks, across) from the player's feet at most... */
    static final int RADIUS = 6;
    /** ...and this many blocks of it at most. */
    static final int MAX_BLOCKS = 300;
    /** The column of leaves under a player not on a tree: this many blocks either side of theirs (3x3)... */
    static final int REACH = 1;
    /** ...this many layers down at most. */
    static final int MAX_DEPTH = 12;

    /** One tree going under: its displays, how deep it goes, the ticks it takes and has taken. */
    private record Sink(List<Display.BlockDisplay> shown, float depth, int ticks, int[] age) {
    }

    private static final Map<ServerLevel, List<Sink>> SINKS = new WeakHashMap<>();

    private Crown() {
    }

    /** Whether {@code player} stands on leaves. */
    public static boolean under(Player player) {
        return player.level().getBlockState(player.getOnPos()).is(BlockTags.LEAVES);
    }

    /**
     * What goes under with {@code player}: the tree they stand on (natural leaves and logs joined to them, also
     * diagonally, within {@value #RADIUS} blocks across, at most {@value #MAX_BLOCKS}); if they stand on leaves no tree
     * grew (laid by a player), the column of them under their feet ({@link #below}).
     */
    public static List<BlockPos> what(Player player) {
        Level level = player.level();
        BlockPos start = player.getOnPos();
        if (!natural(level.getBlockState(start))) {
            return below(player);
        }
        List<BlockPos> found = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>(List.of(start));
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty() && found.size() < MAX_BLOCKS) {
            BlockPos p = queue.poll();
            BlockState state = level.getBlockState(p);
            if (!(natural(state) || state.is(BlockTags.LOGS)) || state.hasBlockEntity()
                    || Math.max(Math.abs(p.getX() - start.getX()), Math.abs(p.getZ() - start.getZ())) > RADIUS) {
                continue;
            }
            found.add(p);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        BlockPos n = p.offset(dx, dy, dz);
                        if (seen.add(n)) {
                            queue.add(n);
                        }
                    }
                }
            }
        }
        return found;
    }

    /** The leaves under {@code player}, a column {@value #REACH} either side of theirs, for as long as there are any. */
    static List<BlockPos> below(Player player) {
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

    /** Leaves that grew on a tree (not laid by a player). */
    private static boolean natural(BlockState state) {
        return state.is(BlockTags.LEAVES)
                && !(state.hasProperty(LeavesBlock.PERSISTENT) && state.getValue(LeavesBlock.PERSISTENT));
    }

    /**
     * {@code blocks} go under: taken out of the world at once (nothing drops; the leaves left around decay as ever),
     * and shown sinking whole into the ground, their full height deep; players up among them ride down.
     */
    public static void sink(ServerLevel level, List<BlockPos> blocks) {
        if (blocks.isEmpty()) {
            return;
        }
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        List<Display.BlockDisplay> shown = new ArrayList<>(blocks.size());
        for (BlockPos p : blocks) {
            BlockState state = level.getBlockState(p);
            shown.add(display(level, state, p));
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }
        for (BlockPos p : blocks) {
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        float depth = maxY - minY + 1;
        BlockPos middle = new BlockPos((minX + maxX) / 2, maxY, (minZ + maxZ) / 2);
        level.playSound(null, middle, SoundEvents.AZALEA_LEAVES_BREAK, SoundSource.BLOCKS, 2f, 0.5f);
        level.playSound(null, middle, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.BLOCKS, 2f, 0.4f);
        int ticks = (int) Math.ceil(depth / SPEED);
        for (ServerPlayer player : level.players()) {
            // Up in it: they float down with it, free to walk, jump or leap off; never held (the user).
            if (player.getX() >= minX - 0.3 && player.getX() <= maxX + 1.3 && player.getZ() >= minZ - 0.3
                    && player.getZ() <= maxZ + 1.3 && player.getY() >= minY && player.getY() <= maxY + 2) {
                player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, ticks + RIDE_EXTRA_TICKS, 0, false,
                        false, false));
            }
        }
        SINKS.computeIfAbsent(level, l -> new ArrayList<>()).add(new Sink(shown, depth, ticks, new int[1]));
    }

    /** Slides the displays of the trees going under and clears what has sunk. */
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) {
            return;
        }
        List<Sink> sinks = SINKS.get(level);
        if (sinks == null || sinks.isEmpty()) {
            return;
        }
        for (Iterator<Sink> it = sinks.iterator(); it.hasNext(); ) {
            Sink s = it.next();
            int age = ++s.age()[0];
            if (age == 1) {
                for (Display.BlockDisplay d : s.shown()) {
                    PerchBuild.slide(d, 0, -s.depth(), 0, s.ticks());
                }
            }
            if (age >= s.ticks() + 2) {
                s.shown().forEach(Display.BlockDisplay::discard);
                it.remove();
            }
        }
    }

    private static Display.BlockDisplay display(ServerLevel level, BlockState state, BlockPos at) {
        Display.BlockDisplay shown = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, level);
        shown.setPos(at.getX(), at.getY(), at.getZ());
        CompoundTag tag = shown.saveWithoutId(new CompoundTag());
        tag.put("block_state", NbtUtils.writeBlockState(state));
        shown.load(tag);
        level.addFreshEntity(shown);
        shown.addTag(Devour.TAG); // after it joined: one saved mid-way does not come back
        return shown;
    }
}
