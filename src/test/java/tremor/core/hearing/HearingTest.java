package tremor.core.hearing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tremor.core.math.Vec3;
import tremor.core.testing.ArrayVoxelGrid;

class HearingTest {
    private static final HearingParams PARAMS = HearingParams.defaults();
    private static final float AIR = 0.05f;
    private static final float STONE = 1.2f;
    private static final float GRAVEL = 0.4f;
    /** Leaves conduct as insulating blocks along the way... */
    private static final float LEAVES = 0.1f;
    /** ...while a source rustling in them has this footing (hearing.rustlingFactor). */
    private static final double RUSTLING = 1.5;

    /** Stone below y=0 (conductivity 1.2), air above (0.05). */
    private static ArrayVoxelGrid stoneGround() {
        ArrayVoxelGrid g = ArrayVoxelGrid.flatFloor(0);
        for (int x = g.minX; x <= g.maxX; x++) {
            for (int y = g.minY; y <= g.maxY; y++) {
                for (int z = g.minZ; z <= g.maxZ; z++) {
                    g.setConductivity(x, y, z, g.isSolid(x, y, z) ? STONE : AIR);
                }
            }
        }
        return g;
    }

    @Test
    void formulaOnUniformGround() {
        ArrayVoxelGrid g = stoneGround();
        Vec3 source = Vec3.voxelCenter(0, -1, 0);
        Vec3 listener = Vec3.voxelCenter(10, -1, 0);
        double expected = 2 * 1.2 / (1 + 10 / (double) STONE);
        assertEquals(expected, Hearing.perceived(g, source, listener, 2, 1.2, PARAMS), 1e-9);
    }

    @Test
    void footingScalesLinearly() {
        ArrayVoxelGrid g = stoneGround();
        Vec3 source = Vec3.voxelCenter(0, -1, 0), listener = Vec3.voxelCenter(12, -1, 3);
        double onStone = Hearing.perceived(g, source, listener, 2, 1.2, PARAMS);
        double onWool = Hearing.perceived(g, source, listener, 2, 0.1, PARAMS);
        assertEquals(onStone / 12, onWool, 1e-12);
    }

    @Test
    void airGapDampsMuchMoreThanRock() {
        ArrayVoxelGrid g = stoneGround();
        Vec3 source = Vec3.voxelCenter(0, -1, 0);
        double throughRock = Hearing.perceived(g, source, Vec3.voxelCenter(16, -1, 0), 2, 1.2, PARAMS);
        // Same distance, but straight through the air above the ground.
        double throughAir = Hearing.perceived(g, Vec3.voxelCenter(0, 3, 0), Vec3.voxelCenter(16, 3, 0), 2, 1.2, PARAMS);
        assertTrue(throughRock > 20 * throughAir, throughRock + " vs " + throughAir);
    }

    @Test
    void gravelDampsMoreThanStone() {
        ArrayVoxelGrid g = stoneGround();
        for (int x = 4; x <= 8; x++) {
            for (int z = -3; z <= 3; z++) {
                g.setConductivity(x, -1, z, GRAVEL);
            }
        }
        Vec3 source = Vec3.voxelCenter(0, -1, 0), listener = Vec3.voxelCenter(12, -1, 0);
        double withGravel = Hearing.perceived(g, source, listener, 2, 1.2, PARAMS);
        double plain = Hearing.perceived(stoneGround(), source, listener, 2, 1.2, PARAMS);
        assertTrue(withGravel < plain);
    }

    @Test
    void walkingOnStoneIsHeardAcrossACaveButSneakingAndWoolAreNot() {
        ArrayVoxelGrid g = stoneGround();
        Vec3 source = Vec3.voxelCenter(0, -1, 0), listener = Vec3.voxelCenter(15, -1, 4);
        assertTrue(Hearing.perceived(g, source, listener, 2, STONE, PARAMS) > PARAMS.threshold());
        assertEquals(0, Hearing.perceived(g, source, listener, 0, STONE, PARAMS));
        assertTrue(Hearing.perceived(g, source, listener, 2, 0.1, PARAMS) < PARAMS.threshold());
    }

    @Test
    void nothingBeyondMaxDistanceOrWithoutStrength() {
        ArrayVoxelGrid g = stoneGround();
        Vec3 source = Vec3.voxelCenter(0, -1, 0);
        assertEquals(0, Hearing.perceived(g, source, source.add(PARAMS.maxDistance() + 1, 0, 0), 100, 1, PARAMS));
        assertEquals(0, Hearing.perceived(g, source, source.add(3, 0, 0), 5, 0, PARAMS));
        assertEquals(0, Hearing.perceived(g, source, source.add(3, 0, 0), -5, 1, PARAMS));
    }

    @Test
    void zeroLengthSegmentReadsTheSourceVoxel() {
        ArrayVoxelGrid g = stoneGround();
        Vec3 p = Vec3.voxelCenter(2, -1, 2);
        assertEquals(1 / 1.2, Hearing.averageResistance(g, p, p, 0.5, 0.01), 1e-6);
        assertEquals(2 * 1.2, Hearing.perceived(g, p, p, 2, 1.2, PARAMS), 1e-12);
    }

    @Test
    void conductivityIsClampedBeforeTheResistance() {
        ArrayVoxelGrid g = stoneGround();
        g.setConductivity(0, -1, 0, 0f);
        Vec3 p = Vec3.voxelCenter(0, -1, 0);
        assertEquals(100, Hearing.averageResistance(g, p, p, 0.5, 0.01), 1e-9);
    }

    // ---- rustling (SPEC 7.2 "Шелест") ----

    /** Leaves in the ground row z = 0, y = -1 for x in [fromX, toX] of a stone ground, and a foliage over them. */
    private static ArrayVoxelGrid withLeaves(ArrayVoxelGrid g, int fromX, int toX) {
        for (int x = fromX; x <= toX; x++) {
            g.setConductivity(x, -1, 0, LEAVES);
        }
        return g;
    }

    /** The leaves of a grid: its voxels of exactly the leaves' conductivity. */
    private static Hearing.Foliage foliage(ArrayVoxelGrid g) {
        return (x, y, z) -> g.conductivity(x, y, z) == LEAVES;
    }

    @Test
    void theLeavesARustlingSourceStandsInDoNotDampItsRustle() {
        // A step on one block of leaves set into stone, 15 blocks from the listener.
        ArrayVoxelGrid g = withLeaves(stoneGround(), 0, 0);
        Vec3 source = Vec3.voxelCenter(0, -1, 0), listener = Vec3.voxelCenter(15, -1, 0);
        double onStone = Hearing.perceived(stoneGround(), source, listener, 2, STONE, PARAMS);
        double onLeaves = Hearing.perceived(g, source, listener, 2, RUSTLING, foliage(g), PARAMS);
        assertTrue(onLeaves > 1.2 * onStone, onLeaves + " vs " + onStone); // about 1.5 / 1.2 times
        // Were its own leaves an insulator on its way, the rustle would be quieter than a step on stone.
        assertTrue(Hearing.perceived(g, source, listener, 2, RUSTLING, PARAMS) < onStone);
        assertEquals(Hearing.perceived(g, source, listener, 2, RUSTLING, PARAMS),
                Hearing.perceived(g, source, listener, 2, RUSTLING, null, PARAMS), 1e-12);
    }

    @Test
    void aFloorOfLeavesUnderARustlingSourceCarriesItButABandOfLeavesFartherOnDamps() {
        Vec3 source = Vec3.voxelCenter(0, -1, 0), listener = Vec3.voxelCenter(15, -1, 0);
        double onStone = Hearing.perceived(stoneGround(), source, listener, 2, STONE, PARAMS);
        // The source walks on a floor of leaves reaching 5 blocks toward the listener.
        ArrayVoxelGrid floor = withLeaves(stoneGround(), -3, 5);
        double onFloor = Hearing.perceived(floor, source, listener, 2, RUSTLING, foliage(floor), PARAMS);
        assertTrue(onFloor > onStone, onFloor + " vs " + onStone);
        // A band of leaves 6 to 8 blocks away, with stone between it and the leaf the source stands on.
        ArrayVoxelGrid one = withLeaves(stoneGround(), 0, 0);
        ArrayVoxelGrid band = withLeaves(withLeaves(stoneGround(), 0, 0), 6, 8);
        double clear = Hearing.perceived(one, source, listener, 2, RUSTLING, foliage(one), PARAMS);
        double throughBand = Hearing.perceived(band, source, listener, 2, RUSTLING, foliage(band), PARAMS);
        assertTrue(throughBand < clear / 2, throughBand + " vs " + clear);
    }

    @Test
    void onlyTheLeadingSamplesInTheFoliageConductAtTheFooting() {
        // 8 samples from x = 0 to 4 read the voxels 0, 1, 1, 2, 2, 3, 3, 4; leaves at 0 and 2.
        ArrayVoxelGrid g = withLeaves(withLeaves(stoneGround(), 0, 0), 2, 2);
        Vec3 from = Vec3.voxelCenter(0, -1, 0), to = Vec3.voxelCenter(4, -1, 0);
        double expected = (1 / RUSTLING + 2 / LEAVES + 5 / STONE) / 8;
        assertEquals(expected, Hearing.averageResistance(g, from, to, 0.5, 0.01, foliage(g), RUSTLING), 1e-6);
        // No foliage: the plain average; a way starting outside the leaves: nothing taken at the footing.
        assertEquals(Hearing.averageResistance(g, from, to, 0.5, 0.01),
                Hearing.averageResistance(g, from, to, 0.5, 0.01, null, RUSTLING), 1e-12);
        Vec3 beside = Vec3.voxelCenter(1, -1, 0);
        assertEquals(Hearing.averageResistance(g, beside, to, 0.5, 0.01),
                Hearing.averageResistance(g, beside, to, 0.5, 0.01, foliage(g), RUSTLING), 1e-12);
        // All of the way in the leaves: the footing's resistance.
        assertEquals(1 / RUSTLING, Hearing.averageResistance(g, from, from, 0.5, 0.01, foliage(g), RUSTLING), 1e-12);
    }
}
