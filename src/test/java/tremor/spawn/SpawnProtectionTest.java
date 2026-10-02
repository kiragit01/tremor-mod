package tremor.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tremor.core.math.Vec3;

class SpawnProtectionTest {
    @Test
    void protectsAVerticalCylinderAroundEachCentre() {
        SpawnProtection protection = new SpawnProtection(32);
        protection.add(0.5, 0.5);
        protection.add(100.5, -200.5);
        assertEquals(2, protection.size());
        assertFalse(protection.test(new Vec3(0.5, 64, 0.5)));
        assertFalse(protection.test(new Vec3(20, 64, 20)));
        // The height does not matter: a cave far below a base is protected too.
        assertFalse(protection.test(new Vec3(10, -60, 10)));
        assertFalse(protection.test(new Vec3(100, 300, -190)));
        // Exactly at the radius is allowed, just inside is not.
        assertTrue(protection.test(new Vec3(32.5, 64, 0.5)));
        assertFalse(protection.test(new Vec3(32.49, 64, 0.5)));
        assertTrue(protection.test(new Vec3(40, 64, 40)));
        assertTrue(protection.test(new Vec3(100.5, 64, -150)));
    }

    @Test
    void radiusZeroOrNoCentreProtectsNothing() {
        SpawnProtection off = new SpawnProtection(0);
        off.add(0, 0);
        assertTrue(off.test(Vec3.ZERO));
        SpawnProtection empty = new SpawnProtection(32);
        assertTrue(empty.test(Vec3.ZERO));
    }

    @Test
    void manyCentres() {
        SpawnProtection protection = new SpawnProtection(5);
        for (int i = 0; i < 50; i++) {
            protection.add(100 * i, 0);
        }
        assertEquals(50, protection.size());
        for (int i = 0; i < 50; i++) {
            assertFalse(protection.test(new Vec3(100 * i + 4, 0, 0)));
            assertTrue(protection.test(new Vec3(100 * i + 50, 0, 0)));
        }
    }

    @Test
    void radiusIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> new SpawnProtection(-1));
        assertThrows(IllegalArgumentException.class, () -> new SpawnProtection(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new SpawnProtection(Double.POSITIVE_INFINITY));
    }
}
