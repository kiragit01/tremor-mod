package tremor.core.surface;

import java.util.HashMap;
import java.util.Map;

import tremor.core.VoxelView;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;

/**
 * Memoizing front-end to {@link Surface} and {@link SurfaceNormals} for one sampling pass: each voxel's surface flag,
 * raw normal and smoothed normal is computed at most once. Results are identical to the static methods.
 * <p>
 * Not thread-safe. The view is assumed not to change during the pass; call {@link #clear()} if it does.
 * Keys are packed like Minecraft block positions, so x/z must lie in [-2^25, 2^25) and y in [-2048, 2048).
 */
public final class NormalField {
    private final VoxelView view;
    private final Map<Long, Boolean> surface = new HashMap<>();
    private final Map<Long, Vec3> raw = new HashMap<>();
    private final Map<Long, Vec3> smooth = new HashMap<>();
    private final SurfaceNormals.Lookup lookup = new SurfaceNormals.Lookup() {
        @Override
        public boolean isSurface(int x, int y, int z) {
            return NormalField.this.isSurface(x, y, z);
        }

        @Override
        public Vec3 raw(int x, int y, int z) {
            return NormalField.this.raw(x, y, z);
        }
    };

    public NormalField(VoxelView view) {
        this.view = view;
    }

    public VoxelView view() {
        return view;
    }

    /** Cached {@link Surface#isSurface}. */
    public boolean isSurface(int x, int y, int z) {
        long key = pack(x, y, z);
        Boolean cached = surface.get(key);
        if (cached == null) {
            cached = Surface.isSurface(view, x, y, z);
            surface.put(key, cached);
        }
        return cached;
    }

    /** Cached {@link SurfaceNormals#rawNormal}. */
    public Vec3 raw(int x, int y, int z) {
        long key = pack(x, y, z);
        Vec3 cached = raw.get(key);
        if (cached == null) {
            cached = SurfaceNormals.rawNormal(view, x, y, z);
            raw.put(key, cached);
        }
        return cached;
    }

    /** Cached {@link SurfaceNormals#smoothNormal}. */
    public Vec3 smooth(int x, int y, int z) {
        long key = pack(x, y, z);
        Vec3 cached = smooth.get(key);
        if (cached == null) {
            cached = isSurface(x, y, z) ? SurfaceNormals.smooth(lookup, x, y, z) : Vec3.ZERO;
            smooth.put(key, cached);
        }
        return cached;
    }

    /** Forgets everything cached, e.g. after the world changed or before the next pass. */
    public void clear() {
        surface.clear();
        raw.clear();
        smooth.clear();
    }

    /** Packs a voxel position into a long: 26 bits x, 26 bits z, 12 bits y (the Minecraft {@code BlockPos} layout). */
    static long pack(int x, int y, int z) {
        return VoxelPos.pack(x, y, z);
    }
}
