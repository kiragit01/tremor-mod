package tremor.awakening;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class CacheLayoutTest {
    /** The bottom of a crater: a disc of radius 5. */
    private static List<CacheLayout.Spot> disc() {
        List<CacheLayout.Spot> spots = new ArrayList<>();
        for (int z = -5; z <= 5; z++) {
            for (int x = -5; x <= 5; x++) {
                if (x * x + z * z <= 25) {
                    spots.add(new CacheLayout.Spot(x, z));
                }
            }
        }
        return spots;
    }

    @Test
    void threeToEightCachesByTheNumberOfStacks() {
        assertEquals(0, CacheLayout.count(0, 50));
        assertEquals(0, CacheLayout.count(10, 0));
        assertEquals(1, CacheLayout.count(1, 50));
        assertEquals(2, CacheLayout.count(2, 50));
        assertEquals(3, CacheLayout.count(3, 50));
        assertEquals(3, CacheLayout.count(15, 50));
        assertEquals(4, CacheLayout.count(16, 50));
        assertEquals(8, CacheLayout.count(41, 50), "a full inventory");
        assertEquals(8, CacheLayout.count(500, 50));
        assertEquals(2, CacheLayout.count(41, 2), "only two spots");
    }

    @Test
    void everyStackGoesIntoExactlyOneCache() {
        for (int stacks = 1; stacks <= 60; stacks++) {
            List<CacheLayout.Cache> caches = CacheLayout.plan(stacks, disc(), stacks * 31L);
            assertEquals(CacheLayout.count(stacks, disc().size()), caches.size());
            Set<Integer> seen = new HashSet<>();
            int fewest = Integer.MAX_VALUE;
            int most = 0;
            for (CacheLayout.Cache cache : caches) {
                assertTrue(!cache.stacks().isEmpty(), "an empty cache");
                for (int stack : cache.stacks()) {
                    assertTrue(seen.add(stack), "stack " + stack + " twice");
                }
                fewest = Math.min(fewest, cache.stacks().size());
                most = Math.max(most, cache.stacks().size());
            }
            assertEquals(stacks, seen.size());
            assertTrue(most - fewest <= 1, "dealt unevenly: " + fewest + " to " + most);
        }
    }

    @Test
    void someLieOnTopSomeAreBuried() {
        for (long seed = 0; seed < 50; seed++) {
            List<CacheLayout.Cache> caches = CacheLayout.plan(41, disc(), seed);
            int exposed = 0;
            for (CacheLayout.Cache cache : caches) {
                assertTrue(cache.buried() >= 0 && cache.buried() <= CacheLayout.MAX_BURIED);
                exposed += cache.buried() == 0 ? 1 : 0;
            }
            assertEquals(3, exposed, "a third of eight, rounded up");
        }
        // A single cache can be seen; of two, one is buried.
        assertEquals(0, CacheLayout.plan(1, disc(), 3).get(0).buried());
        List<CacheLayout.Cache> two = CacheLayout.plan(2, disc(), 3);
        assertEquals(1, two.stream().filter(cache -> cache.buried() == 0).count());
    }

    @Test
    void theCachesAreSpreadOverTheBottom() {
        List<CacheLayout.Spot> spots = disc();
        for (long seed = 0; seed < 50; seed++) {
            List<CacheLayout.Cache> caches = CacheLayout.plan(41, spots, seed);
            Set<Integer> used = new HashSet<>();
            for (CacheLayout.Cache cache : caches) {
                assertTrue(used.add(cache.spot()), "two caches on one spot");
            }
            for (int a = 0; a < caches.size(); a++) {
                for (int b = a + 1; b < caches.size(); b++) {
                    CacheLayout.Spot one = spots.get(caches.get(a).spot());
                    CacheLayout.Spot other = spots.get(caches.get(b).spot());
                    int dx = one.x() - other.x(), dz = one.z() - other.z();
                    assertTrue(dx * dx + dz * dz >= 9, "too close: " + one + " " + other);
                }
            }
        }
    }

    @Test
    void theSeedPicksTheLayout() {
        assertEquals(CacheLayout.plan(20, disc(), 5), CacheLayout.plan(20, disc(), 5));
        Set<List<CacheLayout.Cache>> layouts = new HashSet<>();
        for (long seed = 0; seed < 10; seed++) {
            layouts.add(CacheLayout.plan(20, disc(), seed));
        }
        assertTrue(layouts.size() > 1);
        assertEquals(List.of(), CacheLayout.plan(0, disc(), 1));
        assertEquals(List.of(), CacheLayout.plan(5, List.of(), 1));
    }
}
