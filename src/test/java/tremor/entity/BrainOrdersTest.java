package tremor.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;
import tremor.core.behavior.BehaviorParams;
import tremor.core.behavior.Brain;
import tremor.core.behavior.BrainWorld;
import tremor.core.behavior.Decision;
import tremor.core.behavior.Stage;
import tremor.core.math.Vec3;

class BrainOrdersTest {
    private static final Vec3 A = new Vec3(10.5, 64.5, 0.5);
    private static final Vec3 B = new Vec3(14.5, 64.5, 3.5);
    private static final Vec3 C = new Vec3(-6.5, 63.5, 8.5);

    @Test
    void wanderAndSearchLegsAreOrderedAtOnce() {
        BrainOrders orders = new BrainOrders();
        for (String reason : List.of("wander", "search")) {
            BrainOrders.Order order = orders.next(Decision.go(A, reason), 0.4, false, p -> false);
            assertEquals(BrainOrders.Type.GO, order.type());
            assertEquals(A, order.point());
            assertFalse(order.sound());
            // Only a wander leg's route must keep away from the players (a search leg searches where it heard).
            assertEquals(reason.equals("wander"), order.wander(), reason);
            assertEquals(0, order.perceived());
            assertFalse(orders.waiting());
        }
    }

    @Test
    void soundGoesCarryTheLoudnessAndWaitForTheCooldown() {
        for (String reason : List.of("hunt", "creep", "investigate")) {
            BrainOrders orders = new BrainOrders();
            List<Double> asked = new ArrayList<>();
            BrainOrders.Order order = orders.next(Decision.go(A, reason), 0.4, false, p -> {
                asked.add(p);
                return true;
            });
            assertEquals(BrainOrders.Type.GO, order.type());
            assertTrue(order.sound());
            assertEquals(0.4, order.perceived());
            assertEquals(List.of(0.4), asked);

            assertSame(BrainOrders.Order.NONE, orders.next(Decision.go(B, reason), 0.3, false, p -> false));
            assertTrue(orders.waiting(), reason);
            // The brain says STAY while it waits ("keep following that route"): the GO is ordered once allowed.
            assertSame(BrainOrders.Order.NONE, orders.next(Decision.stay(reason), 0.3, false, p -> false));
            order = orders.next(Decision.stay(reason), 0.3, false, p -> true);
            assertEquals(new BrainOrders.Order(BrainOrders.Type.GO, B, true, false, 0.3), order);
            assertFalse(orders.waiting());
            assertSame(BrainOrders.Order.NONE, orders.next(Decision.stay(reason), 0.3, false, p -> true));
        }
    }

    @Test
    void theLatestDecisionReplacesOrCancelsAWaitingGo() {
        BrainOrders orders = new BrainOrders();
        orders.next(Decision.go(A, "hunt"), 0.4, false, p -> false);
        orders.next(Decision.go(B, "hunt"), 0.5, false, p -> false);
        assertEquals(new BrainOrders.Order(BrainOrders.Type.GO, B, true, false, 0.5),
                orders.next(Decision.stay("hunt"), 0.5, false, p -> true));

        orders.next(Decision.go(A, "hunt"), 0.4, false, p -> false);
        assertEquals(new BrainOrders.Order(BrainOrders.Type.GO, C, false, true, 0),
                orders.next(Decision.go(C, "wander"), 0.4, false, p -> false));
        assertFalse(orders.waiting());

        orders.next(Decision.go(A, "creep"), 0.4, false, p -> false);
        assertEquals(BrainOrders.Type.FREEZE, orders.next(Decision.freeze(B, "freeze"), 0.4, false, p -> true).type());
        assertFalse(orders.waiting());
    }

    @Test
    void aFreezeIsOrderedWhenItStartsAndWhenItsFacingChanges() {
        BrainOrders orders = new BrainOrders();
        assertEquals(new BrainOrders.Order(BrainOrders.Type.FREEZE, A, false, false, 0),
                orders.next(Decision.freeze(A, "freeze"), 0.4, false, p -> true));
        assertSame(BrainOrders.Order.NONE, orders.next(Decision.freeze(A, "freeze"), 0.4, false, p -> true));
        assertEquals(new BrainOrders.Order(BrainOrders.Type.FREEZE, B, false, false, 0),
                orders.next(Decision.freeze(B, "freeze"), 0.4, false, p -> true));
        assertSame(BrainOrders.Order.NONE, orders.next(Decision.freeze(B, "freeze"), 0.4, false, p -> true));
        // Anything else ends the freeze; the next one starts anew, even toward the same point.
        assertSame(BrainOrders.Order.NONE, orders.next(Decision.stay("rest"), 0.4, false, p -> true));
        assertEquals(BrainOrders.Type.FREEZE, orders.next(Decision.freeze(B, "freeze"), 0.4, false, p -> true).type());
        assertEquals(BrainOrders.Type.GO, orders.next(Decision.go(B, "creep"), 0.4, false, p -> true).type());
        assertEquals(BrainOrders.Type.FREEZE, orders.next(Decision.freeze(B, "freeze"), 0.4, false, p -> true).type());
    }

    @Test
    void aManualGotoSuspendsEveryDecision() {
        BrainOrders orders = new BrainOrders();
        orders.next(Decision.go(A, "hunt"), 0.4, false, p -> false);
        orders.next(Decision.freeze(B, "freeze"), 0.4, false, p -> true);
        assertSame(BrainOrders.Order.NONE, orders.next(Decision.go(C, "wander"), 0.4, true, p -> true));
        assertSame(BrainOrders.Order.NONE, orders.next(Decision.freeze(B, "freeze"), 0.4, true, p -> true));
        assertFalse(orders.waiting());
        // Once the goto has ended, the freeze toward the same point is new.
        assertEquals(BrainOrders.Type.FREEZE, orders.next(Decision.freeze(B, "freeze"), 0.4, false, p -> true).type());
    }

    @Test
    void clearForgetsTheWaitingGoAndTheFreeze() {
        BrainOrders orders = new BrainOrders();
        orders.next(Decision.go(A, "hunt"), 0.4, false, p -> false);
        orders.clear();
        assertFalse(orders.waiting());
        assertSame(BrainOrders.Order.NONE, orders.next(Decision.stay("hunt"), 0.4, false, p -> true));
        orders.next(Decision.freeze(B, "freeze"), 0.4, false, p -> true);
        orders.clear();
        assertEquals(BrainOrders.Type.FREEZE, orders.next(Decision.freeze(B, "freeze"), 0.4, false, p -> true).type());
    }

    /**
     * A hunting brain hears a step every 5 ticks while the retarget cooldown is 10 ticks: every step is a GO, the
     * ones within the cooldown wait, and the body always ends up heading for the brain's latest sound.
     */
    @Test
    void withARealBrainTheBodyFollowsTheLatestSoundWithinTheCooldown() {
        int cooldown = 10;
        Brain brain = new Brain(BehaviorParams.defaults(), 7);
        BrainOrders orders = new BrainOrders();
        SoundPursuit pursuit = new SoundPursuit();
        BrainWorld world = new BrainWorld() {
            @Override
            public Vec3 wanderTarget(Vec3 from, double minDistanceToPlayer, RandomGenerator random) {
                return null;
            }

            @Override
            public Vec3 searchTarget(Vec3 center, double radius, RandomGenerator random) {
                return null;
            }
        };
        Vec3 position = new Vec3(0.5, 64.5, 0.5);
        Vec3 bodyTarget = null;
        List<Long> ordered = new ArrayList<>();
        Vec3 lastStep = null;
        for (long tick = 0; tick < 40; tick++) {
            if (tick % 5 == 0) {
                lastStep = new Vec3(10.5 + tick, 64.5, 0.5);
                brain.hear(lastStep, 0.3);
            }
            boolean following = bodyTarget != null;
            boolean idle = bodyTarget == null && !orders.waiting();
            Decision decision = brain.tick(0.05, Stage.HUNTING, position, idle, world);
            long now = tick;
            BrainOrders.Order order = orders.next(decision, brain.lastHeardLoudness(), false,
                    p -> pursuit.mayRetarget(following, now, cooldown, p));
            if (order.type() == BrainOrders.Type.GO) {
                assertTrue(order.sound());
                pursuit.retargeted(now, order.perceived());
                pursuit.targetSet();
                bodyTarget = order.point();
                ordered.add(tick);
            }
            if (tick >= 1) {
                // Never more than one tick behind the cooldown, and never toward an older step.
                assertTrue(orders.waiting() || lastStep.equals(bodyTarget), "tick " + tick);
            }
        }
        assertEquals(List.of(0L, 10L, 20L, 30L), ordered);
    }
}
