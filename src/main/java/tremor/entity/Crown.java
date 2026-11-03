package tremor.entity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Leaves give way under a player up in a tree (the user: up there the tremor could not get them, a swallowing there went
 * wrong, and leaves vanishing a 3x3 at once, or the tree sinking whole, looked wrong): guarded there, the tremor shakes
 * the trunk and the crown falls from its edges in to the trunk ({@link #shake}); reached there, a ragged funnel opens
 * under them within a second ({@link #funnel}). The leaves crumble as decaying leaves do, dropping what they drop.
 * The crown that falls is only of leaves that grew on a tree; the funnel takes leaves a player laid too (the user: a
 * floor and walls of leaves built high up held the tremor off, and a build of leaves is no great loss). Never a block
 * the crater keeps ({@link #kept}); no log is ever taken. Kept per level in memory; server thread only, ticked by
 * {@link #onLevelTick}, registered by {@code tremor.Tremor}.
 */
public final class Crown {
    /**
     * The crown that falls is about this far (blocks) from the player's feet, all round...
     */
    static final int RADIUS = 6;
    /**
     * ...give or take this much in great lobes round it, and {@value #RAGGED} more leaf by leaf (the user: in a dense
     * canopy a neat square was eaten out of it)...
     */
    static final double LOBES = 1.5;
    /** ...and this many leaves of it at most (the canopy of a whole forest), the nearest. */
    static final int MAX_LEAVES = 1500;
    /** The crown falls over this many ticks, from its edges in. */
    static final int SHAKE_TICKS = 80;
    /** A funnel opens over this many ticks... */
    static final int FUNNEL_TICKS = 20;
    /** ...this wide at the top (blocks from the player, give or take {@value #RAGGED})... */
    static final double FUNNEL_RADIUS = 2.5;
    /** ...narrowing by this much a layer down, to this at the narrowest (the user: further down it was a neat 2x2)... */
    static final double FUNNEL_NARROWING = 0.3;
    static final double FUNNEL_MIN_RADIUS = 1.4;
    /** ...as far down as there are leaves under the player, at most this many layers. */
    static final int MAX_DEPTH = 12;
    /** How far (blocks) an edge strays in or out, every layer anew: ragged, uneven. */
    static final double RAGGED = 0.9;

    /** Per level: the leaves due to crumble, with the game time each is due at. */
    private static final Map<ServerLevel, List<Due>> DUE = new WeakHashMap<>();

    private record Due(BlockPos pos, long at) {
    }

    /** A leaf with the key it crumbles in the order of (the lowest first). */
    private record Keyed(BlockPos pos, double key) {
    }

    private Crown() {
    }

    /** Whether {@code player} stands on leaves. */
    public static boolean under(Player player) {
        return player.level().getBlockState(player.getOnPos()).is(BlockTags.LEAVES);
    }

    /**
     * The tremor shakes the trunk of the tree {@code player} is up in: its crown (natural leaves joined to those under
     * them, also diagonally, about {@value #RADIUS} blocks round, the edge lobed and ragged) falls over
     * {@value #SHAKE_TICKS} ticks, from the edges in to its middle, the edge ragged as it goes. On leaves a player
     * laid (a build of leaves): the funnel instead.
     */
    public static void shake(ServerLevel level, Player player) {
        BlockPos start = player.getOnPos();
        if (!natural(level.getBlockState(start))) {
            funnel(level, player);
            return;
        }
        RandomSource random = level.getRandom();
        double phase3 = random.nextDouble() * 2 * Math.PI, phase5 = random.nextDouble() * 2 * Math.PI;
        List<BlockPos> crown = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>(List.of(start));
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty() && crown.size() < MAX_LEAVES) {
            BlockPos p = queue.poll();
            BlockState state = level.getBlockState(p);
            int ox = p.getX() - start.getX(), oz = p.getZ() - start.getZ();
            double angle = Math.atan2(oz, ox);
            double reach = RADIUS + LOBES * (0.6 * Math.sin(3 * angle + phase3) + 0.4 * Math.sin(5 * angle + phase5))
                    + (random.nextDouble() * 2 - 1) * RAGGED;
            if (!natural(state) || kept(level, p, state) || Math.hypot(ox, oz) > reach) {
                continue;
            }
            crown.add(p);
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
        if (crown.isEmpty()) {
            return;
        }
        double mx = 0, mz = 0;
        for (BlockPos p : crown) {
            mx += p.getX() + 0.5;
            mz += p.getZ() + 0.5;
        }
        mx /= crown.size();
        mz /= crown.size();
        List<Keyed> order = new ArrayList<>(crown.size());
        for (BlockPos p : crown) {
            // From the edges in: the farther from the middle, the sooner, give or take (a ragged edge).
            double d = Math.hypot(p.getX() + 0.5 - mx, p.getZ() + 0.5 - mz);
            order.add(new Keyed(p, -d + (random.nextDouble() * 2 - 1) * RAGGED * 2));
        }
        schedule(level, order, SHAKE_TICKS);
        BlockPos trunk = BlockPos.containing(mx, start.getY(), mz);
        level.playSound(null, trunk, SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1.5f, 0.5f);
        level.playSound(null, trunk, SoundEvents.AZALEA_LEAVES_BREAK, SoundSource.BLOCKS, 2f, 0.6f);
    }

    /**
     * A ragged funnel opens under {@code player} within {@value #FUNNEL_TICKS} ticks: leaves about
     * {@value #FUNNEL_RADIUS} blocks from them at the top, narrower each layer down to {@value #FUNNEL_MIN_RADIUS}, the
     * edge straying by up to {@value #RAGGED} every layer anew, crumbling in no neat order, roughly from the top and the
     * middle out; the leaves right under them (all four blocks where they stand on the corner of four) go down to the
     * last, so they fall through. Leaves a player laid too.
     *
     * @return the leaves that go
     */
    public static Set<BlockPos> funnel(ServerLevel level, Player player) {
        double px = player.getX(), pz = player.getZ();
        double half = player.getBbWidth() / 2;
        int top = player.getOnPos().getY(), r = (int) Math.ceil(FUNNEL_RADIUS + RAGGED);
        int bx = (int) Math.floor(px), bz = (int) Math.floor(pz);
        RandomSource random = level.getRandom();
        List<Keyed> order = new ArrayList<>();
        Set<BlockPos> going = new HashSet<>();
        for (int dy = 0; dy < MAX_DEPTH; dy++) {
            boolean any = false;
            for (int x = bx - r; x <= bx + r; x++) {
                for (int z = bz - r; z <= bz + r; z++) {
                    BlockPos p = new BlockPos(x, top - dy, z);
                    BlockState state = level.getBlockState(p);
                    if (!state.is(BlockTags.LEAVES) || kept(level, p, state)) {
                        continue;
                    }
                    // Under the player's feet: the shaft they fall down.
                    boolean underFeet = x + 1 > px - half && x < px + half && z + 1 > pz - half && z < pz + half;
                    double d = Math.hypot(x + 0.5 - px, z + 0.5 - pz);
                    double reach = Math.max(FUNNEL_MIN_RADIUS, FUNNEL_RADIUS - dy * FUNNEL_NARROWING)
                            + (random.nextDouble() * 2 - 1) * RAGGED;
                    if (underFeet || d <= reach) {
                        order.add(new Keyed(p, dy + d * 0.5 + random.nextDouble() * 1.2));
                        going.add(p);
                        any = true;
                    }
                }
            }
            if (!any) {
                break;
            }
        }
        schedule(level, order, FUNNEL_TICKS);
        level.playSound(null, player.getOnPos(), SoundEvents.AZALEA_LEAVES_BREAK, SoundSource.BLOCKS, 2f, 0.5f);
        return going;
    }

    /** The leaves crumble in the order of their keys, spread evenly over the next {@code ticks} ticks. */
    private static void schedule(ServerLevel level, List<Keyed> leaves, int ticks) {
        leaves.sort(Comparator.comparingDouble(Keyed::key));
        long now = level.getGameTime();
        List<Due> due = DUE.computeIfAbsent(level, l -> new ArrayList<>());
        for (int i = 0, n = leaves.size(); i < n; i++) {
            due.add(new Due(leaves.get(i).pos(), now + 1 + (long) i * ticks / n));
        }
    }

    /** Crumbles the leaves due by now, as decaying leaves do (if they are leaves still, and not kept by now). */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        List<Due> due = DUE.get(level);
        if (due == null || due.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        for (Iterator<Due> it = due.iterator(); it.hasNext(); ) {
            Due d = it.next();
            if (d.at() <= now) {
                it.remove();
                BlockState state = level.getBlockState(d.pos());
                if (state.is(BlockTags.LEAVES) && !kept(level, d.pos(), state)) {
                    level.destroyBlock(d.pos(), true);
                }
            }
        }
    }

    /** Leaves that grew on a tree (not laid by a player). */
    private static boolean natural(BlockState state) {
        return state.is(BlockTags.LEAVES)
                && !(state.hasProperty(LeavesBlock.PERSISTENT) && state.getValue(LeavesBlock.PERSISTENT));
    }

    /**
     * Whether {@code p} stays as a crater keeps what it must: a block entity, {@code #tremor:protected}, unbreakable,
     * the spawn protection, outside the world border.
     */
    private static boolean kept(Level level, BlockPos p, BlockState state) {
        if (state.hasBlockEntity() || state.is(tremor.world.TremorTags.PROTECTED) || state.getDestroySpeed(level, p) < 0
                || !level.getWorldBorder().isWithinBounds(p)) {
            return true;
        }
        int radius = level.dimension() == Level.OVERWORLD && level.getServer() != null
                ? Math.max(0, level.getServer().getSpawnProtectionRadius()) : 0;
        BlockPos spawn = level.getSharedSpawnPos();
        return radius > 0 && Math.max(Math.abs(p.getX() - spawn.getX()), Math.abs(p.getZ() - spawn.getZ())) <= radius;
    }
}
