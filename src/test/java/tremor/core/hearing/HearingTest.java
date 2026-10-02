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
}
