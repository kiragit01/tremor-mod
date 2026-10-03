package tremor.awakening;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

/**
 * How the things of a player the ground killed are hidden in the crater (SPEC 9 "Поражение": scattered over its
 * bottom, partly buried under the rubble): plain Java, unit-tested directly; {@code CraterCaches} places the caches.
 * <p>
 * The stacks go into {@link #count} caches, one stack per {@value #STACKS_PER_CACHE} or so, at least
 * {@value #MIN_CACHES} and at most {@value #MAX_CACHES} (never more than there are stacks, nor than spots to put them
 * on). The caches lie as far apart as the spots allow (the first spot picked at random, then each time the one
 * farthest from those picked), about a third of them on top of the rubble where they can be seen (at least one), the
 * others buried under one or two more blocks of it ({@value #MAX_BURIED} at most). The stacks are shuffled and dealt
 * out in turn, so every cache gets about as many and none gets only the armour. The same seed gives the same layout.
 */
public final class CacheLayout {
    public static final int MIN_CACHES = 3;
    public static final int MAX_CACHES = 8;
    /** About this many stacks go into one cache. */
    public static final int STACKS_PER_CACHE = 5;
    /** A buried cache lies under at most this many blocks of rubble. */
    public static final int MAX_BURIED = 2;

    private CacheLayout() {
    }

    /** A column of the bottom a cache may be put on. */
    public record Spot(int x, int z) {
    }

    /**
     * One cache: the index of its {@link Spot}, the blocks of rubble over it (0: it lies on top) and the indices of the
     * stacks in it.
     */
    public record Cache(int spot, int buried, List<Integer> stacks) {
    }

    /** How many caches {@code stacks} stacks go into when there are {@code spots} spots for them. */
    public static int count(int stacks, int spots) {
        if (stacks <= 0 || spots <= 0) {
            return 0;
        }
        int wanted = Math.max(MIN_CACHES, Math.min(MAX_CACHES, (stacks + STACKS_PER_CACHE - 1) / STACKS_PER_CACHE));
        return Math.min(wanted, Math.min(stacks, spots));
    }

    /** The caches for {@code stacks} stacks on {@code spots}; empty if there are no stacks or no spots. */
    public static List<Cache> plan(int stacks, List<Spot> spots, long seed) {
        int count = count(stacks, spots.size());
        if (count == 0) {
            return List.of();
        }
        SplittableRandom random = new SplittableRandom(seed);
        List<Integer> picked = spread(spots, count, random);
        shuffle(picked, random);
        // Dealt out in turn from a shuffled pile.
        List<Integer> pile = new ArrayList<>(stacks);
        for (int i = 0; i < stacks; i++) {
            pile.add(i);
        }
        shuffle(pile, random);
        List<List<Integer>> dealt = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            dealt.add(new ArrayList<>());
        }
        for (int i = 0; i < pile.size(); i++) {
            dealt.get(i % count).add(pile.get(i));
        }
        int exposed = (count + 2) / 3;
        List<Cache> caches = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int buried = i < exposed ? 0 : 1 + random.nextInt(MAX_BURIED);
            caches.add(new Cache(picked.get(i), buried, List.copyOf(dealt.get(i))));
        }
        return caches;
    }

    /** {@code count} spots as far apart as they go: one at random, then each time the farthest from those picked. */
    private static List<Integer> spread(List<Spot> spots, int count, SplittableRandom random) {
        List<Integer> picked = new ArrayList<>(count);
        long[] nearest = new long[spots.size()];
        Arrays.fill(nearest, Long.MAX_VALUE);
        int next = random.nextInt(spots.size());
        while (true) {
            picked.add(next);
            if (picked.size() == count) {
                return picked;
            }
            Spot at = spots.get(next);
            int farthest = -1;
            for (int i = 0; i < spots.size(); i++) {
                Spot spot = spots.get(i);
                long dx = spot.x() - at.x();
                long dz = spot.z() - at.z();
                nearest[i] = Math.min(nearest[i], dx * dx + dz * dz);
                if (!picked.contains(i) && (farthest < 0 || nearest[i] > nearest[farthest])) {
                    farthest = i;
                }
            }
            next = farthest;
        }
    }

    private static <T> void shuffle(List<T> list, SplittableRandom random) {
        for (int i = list.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            T swap = list.get(i);
            list.set(i, list.get(j));
            list.set(j, swap);
        }
    }
}
