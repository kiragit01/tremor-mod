package tremor.core.testing;

import tremor.core.math.Clamp;
import java.util.Arrays;

import tremor.core.VoxelView;

/**
 * In-memory {@link VoxelView} over the inclusive box {@code [minX..maxX] × [minY..maxY] × [minZ..maxZ]}.
 * <p>
 * Everything starts open, with conductivity {@code 1} and unprotected. Outside the box the grid answers according
 * to its {@link Outside} mode; {@link Outside#EXTEND} repeats the nearest edge voxel, which makes the scene factories
 * effectively infinite and free of edge artefacts.
 */
public final class ArrayVoxelGrid implements VoxelView {
    /** What the grid reports for voxels outside its box. */
    public enum Outside {
        SOLID, OPEN, EXTEND
    }

    public final int minX, minY, minZ, maxX, maxY, maxZ;
    private final int sizeX, sizeY, sizeZ;
    private final Outside outside;
    private final boolean[] solid;
    private final float[] conductivity;
    private final boolean[] protectedVoxels;

    public ArrayVoxelGrid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Outside outside) {
        if (maxX < minX || maxY < minY || maxZ < minZ) {
            throw new IllegalArgumentException("empty box");
        }
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.sizeX = maxX - minX + 1;
        this.sizeY = maxY - minY + 1;
        this.sizeZ = maxZ - minZ + 1;
        this.outside = outside;
        int volume = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
        this.solid = new boolean[volume];
        this.conductivity = new float[volume];
        Arrays.fill(conductivity, 1f);
        this.protectedVoxels = new boolean[volume];
    }

    /** Grid whose out-of-bounds voxels are all solid ({@code true}) or all open ({@code false}). */
    public ArrayVoxelGrid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean outsideSolid) {
        this(minX, minY, minZ, maxX, maxY, maxZ, outsideSolid ? Outside.SOLID : Outside.OPEN);
    }

    // ---- scene factories -------------------------------------------------------------------------------------------

    /** Horizontal extent used by the scene factories; with {@link Outside#EXTEND} the scenes continue beyond it. */
    public static final int SCENE_HALF_WIDTH = 20;
    /** Vertical extent used by the scene factories. */
    public static final int SCENE_HALF_HEIGHT = 16;

    private static ArrayVoxelGrid sceneBox() {
        return new ArrayVoxelGrid(-SCENE_HALF_WIDTH, -SCENE_HALF_HEIGHT, -SCENE_HALF_WIDTH,
                SCENE_HALF_WIDTH, SCENE_HALF_HEIGHT, SCENE_HALF_WIDTH, Outside.EXTEND);
    }

    /** Infinite flat floor: solid for {@code y <= topY}. Its surface layer is {@code y = topY}, normals up. */
    public static ArrayVoxelGrid flatFloor(int topY) {
        ArrayVoxelGrid g = sceneBox();
        g.fill(g.minX, g.minY, g.minZ, g.maxX, topY, g.maxZ, true);
        return g;
    }

    /**
     * Cave: floor solid for {@code y <= floorTopY}, ceiling solid for {@code y >= ceilingBottomY}, open in between.
     * Floor surface at {@code y = floorTopY} (normals up), ceiling surface at {@code y = ceilingBottomY} (normals down).
     */
    public static ArrayVoxelGrid cave(int floorTopY, int ceilingBottomY) {
        ArrayVoxelGrid g = sceneBox();
        g.fill(g.minX, g.minY, g.minZ, g.maxX, floorTopY, g.maxZ, true);
        g.fill(g.minX, ceilingBottomY, g.minZ, g.maxX, g.maxY, g.maxZ, true);
        return g;
    }

    /** Infinite vertical wall facing +x: solid for {@code x <= faceX}. Its surface layer is {@code x = faceX}. */
    public static ArrayVoxelGrid wallFacingPlusX(int faceX) {
        ArrayVoxelGrid g = sceneBox();
        g.fill(g.minX, g.minY, g.minZ, faceX, g.maxY, g.maxZ, true);
        return g;
    }

    /**
     * Floor/wall corner: solid for {@code y <= floorTopY} or {@code x <= wallFaceX}. The concave edge runs along z;
     * the voxels next to it are {@code (wallFaceX + 1, floorTopY, z)} (floor side) and
     * {@code (wallFaceX, floorTopY + 1, z)} (wall side).
     */
    public static ArrayVoxelGrid floorWallCorner(int floorTopY, int wallFaceX) {
        ArrayVoxelGrid g = flatFloor(floorTopY);
        g.fill(g.minX, g.minY, g.minZ, wallFaceX, g.maxY, g.maxZ, true);
        return g;
    }

    /**
     * Closed room: open box {@code [x0..x1] × [y0..y1] × [z0..z1]} carved out of solid rock. Floor at {@code y0 - 1},
     * ceiling at {@code y1 + 1}, walls at {@code x0 - 1}, {@code x1 + 1}, {@code z0 - 1}, {@code z1 + 1}.
     */
    public static ArrayVoxelGrid room(int x0, int y0, int z0, int x1, int y1, int z1) {
        ArrayVoxelGrid g = sceneBox();
        g.fill(g.minX, g.minY, g.minZ, g.maxX, g.maxY, g.maxZ, true);
        g.fill(x0, y0, z0, x1, y1, z1, false);
        return g;
    }

    /**
     * Step down toward +x: solid for {@code y <= upperTopY} when {@code x <= edgeX}, and for {@code y <= upperTopY - 1}
     * when {@code x > edgeX}. The convex edge voxels are {@code (edgeX, upperTopY, z)}.
     */
    public static ArrayVoxelGrid step(int upperTopY, int edgeX) {
        ArrayVoxelGrid g = flatFloor(upperTopY - 1);
        g.fill(g.minX, upperTopY, g.minZ, edgeX, upperTopY, g.maxZ, true);
        return g;
    }

    /**
     * Two caves separated by a vertical wall {@code thickness} blocks thick: floor solid for {@code y <= floorTopY},
     * ceiling solid for {@code y >= ceilingBottomY}, and the wall occupies {@code x0 <= x < x0 + thickness}.
     */
    public static ArrayVoxelGrid thinWallBetweenCaves(int floorTopY, int ceilingBottomY, int x0, int thickness) {
        ArrayVoxelGrid g = cave(floorTopY, ceilingBottomY);
        g.fill(x0, floorTopY + 1, g.minZ, x0 + thickness - 1, ceilingBottomY - 1, g.maxZ, true);
        return g;
    }

    // ---- editing ---------------------------------------------------------------------------------------------------

    /** Sets every voxel of the inclusive box (clipped to the grid) to solid or open. */
    public ArrayVoxelGrid fill(int x0, int y0, int z0, int x1, int y1, int z1, boolean value) {
        int ax = Math.max(Math.min(x0, x1), minX), bx = Math.min(Math.max(x0, x1), maxX);
        int ay = Math.max(Math.min(y0, y1), minY), by = Math.min(Math.max(y0, y1), maxY);
        int az = Math.max(Math.min(z0, z1), minZ), bz = Math.min(Math.max(z0, z1), maxZ);
        for (int y = ay; y <= by; y++) {
            for (int z = az; z <= bz; z++) {
                for (int x = ax; x <= bx; x++) {
                    solid[index(x, y, z)] = value;
                }
            }
        }
        return this;
    }

    public ArrayVoxelGrid set(int x, int y, int z, boolean value) {
        solid[checkedIndex(x, y, z)] = value;
        return this;
    }

    public ArrayVoxelGrid setConductivity(int x, int y, int z, float value) {
        conductivity[checkedIndex(x, y, z)] = value;
        return this;
    }

    public ArrayVoxelGrid setProtected(int x, int y, int z, boolean value) {
        protectedVoxels[checkedIndex(x, y, z)] = value;
        return this;
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    // ---- VoxelView -------------------------------------------------------------------------------------------------

    @Override
    public boolean isSolid(int x, int y, int z) {
        if (contains(x, y, z)) {
            return solid[index(x, y, z)];
        }
        return switch (outside) {
            case SOLID -> true;
            case OPEN -> false;
            case EXTEND -> solid[clampedIndex(x, y, z)];
        };
    }

    @Override
    public float conductivity(int x, int y, int z) {
        if (contains(x, y, z)) {
            return conductivity[index(x, y, z)];
        }
        return outside == Outside.EXTEND ? conductivity[clampedIndex(x, y, z)] : 1f;
    }

    @Override
    public boolean isProtected(int x, int y, int z) {
        if (contains(x, y, z)) {
            return protectedVoxels[index(x, y, z)];
        }
        return outside == Outside.EXTEND && protectedVoxels[clampedIndex(x, y, z)];
    }

    private int index(int x, int y, int z) {
        return ((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX);
    }

    private int checkedIndex(int x, int y, int z) {
        if (!contains(x, y, z)) {
            throw new IndexOutOfBoundsException("(" + x + ", " + y + ", " + z + ") outside the grid");
        }
        return index(x, y, z);
    }

    private int clampedIndex(int x, int y, int z) {
        return index(Clamp.clamp(x, minX, maxX), Clamp.clamp(y, minY, maxY), Clamp.clamp(z, minZ, maxZ));
    }
}
