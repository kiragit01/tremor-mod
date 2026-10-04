package tremor.core.graph;

import java.util.Arrays;
import java.util.Objects;

import tremor.core.VoxelView;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.surface.Surface;
import tremor.core.surface.SurfaceNormals;

/**
 * The surface graph of SPEC 5.2 / 5.4, built lazily from a {@link VoxelView} and memoized per voxel.
 * <p>
 * <b>Nodes</b> are surface voxels ({@link tremor.core.surface.Surface#isSurface}: solid, known, with an open face).
 * <p>
 * <b>Skin edges</b> join a node {@code a} to a node {@code b = a + d} in its 26-neighbourhood ({@code d} has
 * {@code m} non-zero components) when the move stays on the skin and never crosses open space:
 * <ul>
 *   <li><i>skin continuity</i> (all {@code m}): some open face-neighbour of {@code a} and some open face-neighbour of
 *   {@code b} are the same voxel or within Chebyshev distance 1 of each other. This keeps the two faces of a
 *   2-thick wall apart (that crossing is a dive) while joining floor to wall in a corner and over an edge.</li>
 *   <li>{@code m = 1}: continuity is enough.</li>
 *   <li>{@code m = 2}: additionally at least one of the two voxels at the corners of the move
 *   ({@code a + d_i e_i}, {@code a + d_j e_j}) is solid, so the move never passes between two open voxels.</li>
 *   <li>{@code m = 3}: allowed only as a shortcut of an existing two-step skin path: some face-mate
 *   {@code f = a + d_i e_i} of {@code a}, or {@code f = b - d_i e_i} of {@code b}, is a node with skin edges
 *   {@code a-f} and {@code f-b} (trying both ends keeps the rule symmetric).</li>
 * </ul>
 * Length = {@code |d|}.
 * <p>
 * <b>Dive edges</b> go straight through rock along one of the 6 axes: from node {@code a} along {@code dir} to node
 * {@code b = a + k·dir}, {@code 1 <= k <= maxDiveDepth}, when {@code a - dir} is open (the dive starts into the rock
 * under the skin), {@code b + dir} is open (it surfaces on the far side), every voxel strictly between is solid and
 * known, and there is no skin edge {@code a-b}. Length = {@code k}. Through open space: never.
 * <p>
 * <b>Leap edges</b> (not memoized; off until {@link #setMaxLeap}) go straight through open space along one of the 6
 * axes: from node {@code a} along {@code dir} to node {@code b = a + k·dir}, {@code 2 <= k <= maxLeap + 1}, when every
 * voxel strictly between is open and known (a gap of {@code k - 1} blocks: the bump flings itself across, e.g. from
 * the ground up to the underside of a block over it). Reported as dive edges (the bump is hidden on them).
 * Length = {@code k}.
 * <p>
 * All edges are symmetric. Edges are reported skin edges first, in the (y, z, x) neighbour order of
 * {@link SurfaceNormals}, then dive edges in the direction order -x, +x, -y, +y, -z, +z.
 * <p>
 * Results (node flag, edges, smoothed normal) are memoized per voxel; {@link #invalidate} and {@link #invalidateBox}
 * drop everything that can depend on changed voxels. The owner must report every voxel whose answer from the view
 * changed, including those a cache under the graph took from the world without being told (a section read again):
 * a memo left stale on one side of an edge makes the edge one-way. Wrap the world in a
 * {@link tremor.core.voxel.VoxelCache} for speed (the owner invalidates that cache too; the graph only reads its
 * view). Not thread-safe.
 */
public final class SurfaceGraph implements Graph {
    /** Largest supported {@code maxDiveDepth} (dive lengths are memoized in 5 bits). */
    public static final int MAX_DIVE_DEPTH = 31;

    // Per-voxel memo, one long: bits 0..25 skin edges to the 26 neighbours (neighbour order), bits 26..55 the dive
    // length along each of the 6 directions (5 bits each, 0 = none), and flags. 0 = nothing known yet.
    private static final long SKIN_MASK = (1L << 26) - 1;
    private static final int DIVE_SHIFT = 26;
    private static final int DIVE_BITS = 5;
    private static final long DIVE_FIELD = (1L << DIVE_BITS) - 1;
    private static final long NODE_KNOWN = 1L << 60;
    private static final long NODE = 1L << 61;
    private static final long EDGES_KNOWN = 1L << 62;

    /** The 26 neighbour offsets in (y, z, x) order, their lengths and numbers of non-zero components. */
    private static final int[] NX = new int[26], NY = new int[26], NZ = new int[26], NM = new int[26];
    private static final double[] NLENGTH = new double[26];

    /** The 6 axis directions -x, +x, -y, +y, -z, +z; the opposite of {@code t} is {@code t ^ 1}. */
    private static final int[] DX = {-1, 1, 0, 0, 0, 0}, DY = {0, 0, -1, 1, 0, 0}, DZ = {0, 0, 0, 0, -1, 1};
    private static final int[] DIRECTION_NEIGHBOUR = new int[6];

    // Local 5³ box around the voxel being expanded: index (ly * 5 + lz) * 5 + lx, coordinates 0..4, centre at 2.
    private static final int SIDE = 5;
    private static final int CENTER = (2 * SIDE + 2) * SIDE + 2;
    private static final int[] LX = new int[SIDE * SIDE * SIDE], LY = new int[LX.length], LZ = new int[LX.length];
    /** Local index of each of the 26 neighbours of the centre. */
    private static final int[] LOCAL_NEIGHBOUR = new int[26];
    /** Local index delta of each direction. */
    private static final int[] LOCAL_STEP = new int[6];
    /** The 3³ block around the centre: the voxels whose node flags and open faces the skin rules use. */
    private static final int[] INNER;
    /** INNER plus their face neighbours: every voxel whose solidity the skin rules depend on. */
    private static final int[] READ;

    private static final int SECTION_SHIFT = 4;
    private static final int SECTION_VOLUME = 1 << 3 * SECTION_SHIFT;
    private static final int LOCAL_MASK = (1 << SECTION_SHIFT) - 1;
    private static final int INITIAL_CAPACITY = 64;

    static {
        int n = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    NX[n] = dx;
                    NY[n] = dy;
                    NZ[n] = dz;
                    NM[n] = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    NLENGTH[n] = Math.sqrt(NM[n]);
                    LOCAL_NEIGHBOUR[n] = CENTER + localStep(dx, dy, dz);
                    for (int t = 0; t < 6; t++) {
                        if (DX[t] == dx && DY[t] == dy && DZ[t] == dz) {
                            DIRECTION_NEIGHBOUR[t] = n;
                        }
                    }
                    n++;
                }
            }
        }
        for (int t = 0; t < 6; t++) {
            LOCAL_STEP[t] = localStep(DX[t], DY[t], DZ[t]);
        }
        int inner = 0, read = 0;
        int[] innerIndices = new int[27], readIndices = new int[LX.length];
        for (int i = 0; i < LX.length; i++) {
            LX[i] = i % SIDE;
            LZ[i] = i / SIDE % SIDE;
            LY[i] = i / (SIDE * SIDE);
            int far = (Math.abs(LX[i] - 2) == 2 ? 1 : 0) + (Math.abs(LY[i] - 2) == 2 ? 1 : 0)
                    + (Math.abs(LZ[i] - 2) == 2 ? 1 : 0);
            if (far == 0) {
                innerIndices[inner++] = i;
            }
            if (far <= 1) {
                readIndices[read++] = i;
            }
        }
        INNER = innerIndices;
        READ = Arrays.copyOf(readIndices, read);
    }

    private static int localStep(int dx, int dy, int dz) {
        return (dy * SIDE + dz) * SIDE + dx;
    }

    private final VoxelView view;
    private final int maxDiveDepth;
    /** Longest gap a leap edge crosses (blocks); 0: no leaps. */
    private int maxLeap;

    /** Memo sections, open addressing with linear probing; capacity is a power of two. */
    private Memo[] table = new Memo[INITIAL_CAPACITY];
    private int size;
    private Memo last;

    // Scratch for computing the edges of one voxel, indexed like the local box.
    private final boolean[] solid = new boolean[LX.length];
    private final boolean[] node = new boolean[LX.length];
    private final int[] openFaces = new int[LX.length];

    /** @throws IllegalArgumentException unless {@code 0 <= maxDiveDepth <= MAX_DIVE_DEPTH} */
    public SurfaceGraph(VoxelView view, int maxDiveDepth) {
        if (maxDiveDepth < 0 || maxDiveDepth > MAX_DIVE_DEPTH) {
            throw new IllegalArgumentException("maxDiveDepth " + maxDiveDepth + " outside [0, " + MAX_DIVE_DEPTH + "]");
        }
        this.view = Objects.requireNonNull(view, "view");
        this.maxDiveDepth = maxDiveDepth;
    }

    public VoxelView view() {
        return view;
    }

    /**
     * Sets the longest gap of open space a leap edge crosses (blocks, 0 for none; the owner sets it by the entity's
     * stage before each path search). Leap edges are not memoized, so nothing needs invalidating.
     */
    public void setMaxLeap(int maxLeap) {
        this.maxLeap = Math.max(0, maxLeap);
    }

    public int maxLeap() {
        return maxLeap;
    }

    public int maxDiveDepth() {
        return maxDiveDepth;
    }

    public boolean isNode(int x, int y, int z) {
        Memo s = memo(x, y, z);
        int i = index(x, y, z);
        long m = s.data[i];
        if ((m & NODE_KNOWN) == 0) {
            m = withNodeFlag(m, x, y, z);
            s.data[i] = m;
        }
        return (m & NODE) != 0;
    }

    @Override
    public boolean isNode(long node) {
        return isNode(VoxelPos.x(node), VoxelPos.y(node), VoxelPos.z(node));
    }

    @Override
    public void forEachEdge(long node, EdgeConsumer consumer) {
        int x = VoxelPos.x(node), y = VoxelPos.y(node), z = VoxelPos.z(node);
        Memo s = memo(x, y, z);
        int i = index(x, y, z);
        long m = s.data[i];
        if ((m & EDGES_KNOWN) == 0) {
            m = withEdges(m, x, y, z);
            s.data[i] = m;
        }
        // Decoded from the local copy: the consumer may query the graph again.
        for (long skin = m & SKIN_MASK; skin != 0; skin &= skin - 1) {
            int n = Long.numberOfTrailingZeros(skin);
            consumer.accept(VoxelPos.pack(x + NX[n], y + NY[n], z + NZ[n]), NLENGTH[n], false);
        }
        if ((m >>> DIVE_SHIFT & (1L << 6 * DIVE_BITS) - 1) != 0) {
            for (int t = 0; t < 6; t++) {
                int k = (int) (m >>> DIVE_SHIFT + DIVE_BITS * t & DIVE_FIELD);
                if (k != 0) {
                    consumer.accept(VoxelPos.pack(x + k * DX[t], y + k * DY[t], z + k * DZ[t]), k, true);
                }
            }
        }
        if (maxLeap > 0) {
            for (int t = 0; t < 6; t++) {
                int k = leapLength(x, y, z, t);
                if (k != 0) {
                    consumer.accept(VoxelPos.pack(x + k * DX[t], y + k * DY[t], z + k * DZ[t]), k, true);
                }
            }
        }
    }

    /** Length of the leap edge from {@code x, y, z} along direction {@code t}, or 0 if there is none. */
    private int leapLength(int x, int y, int z, int t) {
        for (int k = 1; k <= maxLeap + 1; k++) {
            int bx = x + k * DX[t], by = y + k * DY[t], bz = z + k * DZ[t];
            if (!view.isKnown(bx, by, bz)) {
                return 0;
            }
            if (view.isSolid(bx, by, bz)) {
                return k >= 2 && isNode(bx, by, bz) ? k : 0;
            }
        }
        return 0;
    }

    /** Smoothed surface normal of a node (SPEC 6.2, same result as {@code SurfaceNormals.smoothNormal}); ZERO otherwise. */
    public Vec3 normal(int x, int y, int z) {
        Memo s = memo(x, y, z);
        int i = index(x, y, z);
        Vec3[] normals = s.normals;
        if (normals == null) {
            normals = s.normals = new Vec3[SECTION_VOLUME];
        }
        Vec3 n = normals[i];
        if (n == null) {
            n = isNode(x, y, z) ? SurfaceNormals.smoothNormal(view, x, y, z) : Vec3.ZERO;
            normals[i] = n;
        }
        return n;
    }

    /**
     * The node whose centre is nearest to the point among the voxels within {@code radius} (Chebyshev) of the voxel
     * containing it, ties broken deterministically (lowest y, then z, then x); {@link #NO_NODE} if there is none.
     */
    public long nearestNode(Vec3 point, int radius) {
        int cx = (int) Math.floor(point.x()), cy = (int) Math.floor(point.y()), cz = (int) Math.floor(point.z());
        long best = NO_NODE;
        double bestD2 = Double.POSITIVE_INFINITY;
        int bx = 0, by = 0, bz = 0;
        // Shells of growing Chebyshev distance s: every centre in shell s is at least s - 0.5 away from the point.
        for (int s = 0; s <= radius; s++) {
            for (int dy = -s; dy <= s; dy++) {
                for (int dz = -s; dz <= s; dz++) {
                    int step = s == 0 || Math.abs(dy) == s || Math.abs(dz) == s ? 1 : 2 * s;
                    for (int dx = -s; dx <= s; dx += step) {
                        int x = cx + dx, y = cy + dy, z = cz + dz;
                        if (!isNode(x, y, z)) {
                            continue;
                        }
                        double ex = x + 0.5 - point.x(), ey = y + 0.5 - point.y(), ez = z + 0.5 - point.z();
                        double d2 = ex * ex + ey * ey + ez * ez;
                        if (d2 < bestD2 || d2 == bestD2 && (y != by ? y < by : z != bz ? z < bz : x < bx)) {
                            best = VoxelPos.pack(x, y, z);
                            bestD2 = d2;
                            bx = x;
                            by = y;
                            bz = z;
                        }
                    }
                }
            }
            double next = s + 0.5;
            if (best != NO_NODE && bestD2 < next * next - 1e-9) {
                break;
            }
        }
        return best;
    }

    /** Returned by {@link #nearestNode} when there is no node in range. Not a valid packed position in practice. */
    public static final long NO_NODE = Long.MIN_VALUE;

    /**
     * Drops memoized data that may depend on the voxel (a box of {@code maxDiveDepth + 2} around it). A
     * {@link tremor.core.voxel.VoxelCache} under the graph must be invalidated separately.
     */
    public void invalidate(int x, int y, int z) {
        invalidateBox(x, y, z, x, y, z);
    }

    /**
     * Drops memoized data that may depend on any voxel of the box (inclusive bounds, any order): the box grown by
     * {@code maxDiveDepth + 2}. For a whole region that may have changed at once, e.g. a cache section read again.
     * A {@link tremor.core.voxel.VoxelCache} under the graph must be invalidated separately.
     */
    public void invalidateBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        int r = maxDiveDepth + 2;
        int ax = saturate((long) Math.min(x0, x1) - r), bx = saturate((long) Math.max(x0, x1) + r);
        int ay = saturate((long) Math.min(y0, y1) - r), by = saturate((long) Math.max(y0, y1) + r);
        int az = saturate((long) Math.min(z0, z1) - r), bz = saturate((long) Math.max(z0, z1) + r);
        int sx0 = ax >> SECTION_SHIFT, sx1 = bx >> SECTION_SHIFT;
        int sy0 = ay >> SECTION_SHIFT, sy1 = by >> SECTION_SHIFT;
        int sz0 = az >> SECTION_SHIFT, sz1 = bz >> SECTION_SHIFT;
        double boxSections = ((double) sx1 - sx0 + 1) * ((double) sy1 - sy0 + 1) * ((double) sz1 - sz0 + 1);
        if (boxSections > size) {
            // Fewer memo sections than the box spans: visit those instead of the box.
            for (Memo s : table) {
                if (s != null && s.sx >= sx0 && s.sx <= sx1 && s.sy >= sy0 && s.sy <= sy1 && s.sz >= sz0
                        && s.sz <= sz1) {
                    clear(s, ax, ay, az, bx, by, bz);
                }
            }
            return;
        }
        for (int sy = sy0; sy <= sy1; sy++) {
            for (int sz = sz0; sz <= sz1; sz++) {
                for (int sx = sx0; sx <= sx1; sx++) {
                    Memo s = find(sx, sy, sz);
                    if (s != null) {
                        clear(s, ax, ay, az, bx, by, bz);
                    }
                }
            }
        }
    }

    public void clear() {
        table = new Memo[INITIAL_CAPACITY];
        size = 0;
        last = null;
    }

    /** Number of 16³ sections holding memoized data (about 32-48 KiB each); {@link #clear} to bound memory. */
    public int sectionCount() {
        return size;
    }

    // ---- edge rules ------------------------------------------------------------------------------------------------

    private long withNodeFlag(long m, int x, int y, int z) {
        return m | NODE_KNOWN | (Surface.isSurface(view, x, y, z) ? NODE : 0);
    }

    private long withEdges(long m, int x, int y, int z) {
        if ((m & NODE_KNOWN) == 0) {
            m = withNodeFlag(m, x, y, z);
        }
        if ((m & NODE) == 0) {
            return m | EDGES_KNOWN;
        }
        loadLocal(x, y, z);
        long edges = 0;
        for (int n = 0; n < 26; n++) {
            int b = LOCAL_NEIGHBOUR[n];
            if (node[b] && (NM[n] < 3 ? skinStep(CENTER, b) : shortcut(n))) {
                edges |= 1L << n;
            }
        }
        int open = openFaces[CENTER];
        for (int t = 0; t < 6; t++) {
            if ((open >> (t ^ 1) & 1) != 0) {
                edges |= (long) diveLength(x, y, z, t, edges) << DIVE_SHIFT + DIVE_BITS * t;
            }
        }
        return m | EDGES_KNOWN | edges;
    }

    /** Reads the solidity the skin rules need around the voxel and derives open faces and node flags of its 3³. */
    private void loadLocal(int x, int y, int z) {
        int bx = x - 2, by = y - 2, bz = z - 2;
        for (int i : READ) {
            solid[i] = view.isSolid(bx + LX[i], by + LY[i], bz + LZ[i]);
        }
        for (int i : INNER) {
            int faces = 0;
            for (int t = 0; t < 6; t++) {
                if (!solid[i + LOCAL_STEP[t]]) {
                    faces |= 1 << t;
                }
            }
            openFaces[i] = faces;
            node[i] = solid[i] && faces != 0 && view.isKnown(bx + LX[i], by + LY[i], bz + LZ[i]);
        }
    }

    /** Skin rule for a face or edge move between two nodes of the local 3³ ({@code m} = 1 or 2). */
    private boolean skinStep(int p, int q) {
        int dx = LX[q] - LX[p], dy = LY[q] - LY[p], dz = LZ[q] - LZ[p];
        if (dx != 0 && dy != 0) {
            if (!solid[p + dx] && !solid[p + dy * SIDE * SIDE]) {
                return false;
            }
        } else if (dx != 0 && dz != 0) {
            if (!solid[p + dx] && !solid[p + dz * SIDE]) {
                return false;
            }
        } else if (dy != 0 && dz != 0) {
            if (!solid[p + dy * SIDE * SIDE] && !solid[p + dz * SIDE]) {
                return false;
            }
        }
        return continuous(p, q);
    }

    /** Skin continuity: an open face-neighbour of {@code p} is within Chebyshev distance 1 of one of {@code q}. */
    private boolean continuous(int p, int q) {
        int fp = openFaces[p], fq = openFaces[q];
        for (int i = 0; i < 6; i++) {
            if ((fp >> i & 1) == 0) {
                continue;
            }
            int ux = LX[p] + DX[i], uy = LY[p] + DY[i], uz = LZ[p] + DZ[i];
            for (int j = 0; j < 6; j++) {
                if ((fq >> j & 1) != 0 && Math.abs(LX[q] + DX[j] - ux) <= 1 && Math.abs(LY[q] + DY[j] - uy) <= 1
                        && Math.abs(LZ[q] + DZ[j] - uz) <= 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The m = 3 rule for neighbour {@code n}: a two-step skin path through a face-mate of either end. */
    private boolean shortcut(int n) {
        int b = LOCAL_NEIGHBOUR[n];
        int ex = NX[n], ey = NY[n] * SIDE * SIDE, ez = NZ[n] * SIDE;
        return via(CENTER + ex, b) || via(CENTER + ey, b) || via(CENTER + ez, b)
                || via(b - ex, b) || via(b - ey, b) || via(b - ez, b);
    }

    private boolean via(int c, int b) {
        return node[c] && skinStep(CENTER, c) && skinStep(c, b);
    }

    /** Length of the dive from the node along direction {@code t} (whose back is open), or 0. */
    private int diveLength(int x, int y, int z, int t, long skin) {
        int ex = DX[t], ey = DY[t], ez = DZ[t];
        for (int k = 1; k <= maxDiveDepth; k++) {
            int bx = x + k * ex, by = y + k * ey, bz = z + k * ez;
            if (!view.isSolid(bx, by, bz)) {
                return 0;
            }
            boolean known = view.isKnown(bx, by, bz);
            if (!view.isSolid(bx + ex, by + ey, bz + ez)) {
                // b surfaces on the far side: a node if known; a face-mate joined by skin needs no dive.
                return known && (k > 1 || (skin >> DIRECTION_NEIGHBOUR[t] & 1) == 0) ? k : 0;
            }
            if (!known) {
                return 0;
            }
        }
        return 0;
    }

    // ---- memo sections ---------------------------------------------------------------------------------------------

    private static int saturate(long v) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
    }

    /** Section-local coordinate of a box bound clipped to section {@code s}. */
    private static int clip(int bound, int s, boolean upper) {
        int first = s << SECTION_SHIFT;
        return (upper ? Math.min(bound, first | LOCAL_MASK) : Math.max(bound, first)) & LOCAL_MASK;
    }

    /** Forgets the part of the world box {@code [x0..x1] × [y0..y1] × [z0..z1]} that lies in memo section {@code s}. */
    private static void clear(Memo s, int x0, int y0, int z0, int x1, int y1, int z1) {
        s.clear(clip(x0, s.sx, false), clip(x1, s.sx, true), clip(y0, s.sy, false), clip(y1, s.sy, true),
                clip(z0, s.sz, false), clip(z1, s.sz, true));
    }

    private static int index(int x, int y, int z) {
        return (y & LOCAL_MASK) << 2 * SECTION_SHIFT | (z & LOCAL_MASK) << SECTION_SHIFT | x & LOCAL_MASK;
    }

    private static int hash(int sx, int sy, int sz) {
        int h = sx * 0x9E3779B1 ^ sy * 0x85EBCA77 ^ sz * 0xC2B2AE3D;
        return h ^ h >>> 15;
    }

    private Memo memo(int x, int y, int z) {
        int sx = x >> SECTION_SHIFT, sy = y >> SECTION_SHIFT, sz = z >> SECTION_SHIFT;
        Memo s = last;
        if (s != null && s.sx == sx && s.sy == sy && s.sz == sz) {
            return s;
        }
        s = find(sx, sy, sz);
        if (s == null) {
            s = new Memo(sx, sy, sz, hash(sx, sy, sz));
            insert(s);
        }
        last = s;
        return s;
    }

    private Memo find(int sx, int sy, int sz) {
        int mask = table.length - 1;
        for (int i = hash(sx, sy, sz) & mask; ; i = i + 1 & mask) {
            Memo s = table[i];
            if (s == null || s.sx == sx && s.sy == sy && s.sz == sz) {
                return s;
            }
        }
    }

    private void insert(Memo s) {
        if ((size + 1) * 2 > table.length) {
            Memo[] old = table;
            table = new Memo[old.length * 2];
            for (Memo o : old) {
                if (o != null) {
                    place(o);
                }
            }
        }
        place(s);
        size++;
    }

    private void place(Memo s) {
        int mask = table.length - 1;
        int i = s.hash & mask;
        while (table[i] != null) {
            i = i + 1 & mask;
        }
        table[i] = s;
    }

    /** Memoized data of one 16³ section, index {@code y << 8 | z << 4 | x} in section-local coordinates. */
    private static final class Memo {
        final int sx, sy, sz, hash;
        final long[] data = new long[SECTION_VOLUME];
        /** Allocated on the first normal query; null entries are not computed yet. */
        Vec3[] normals;

        Memo(int sx, int sy, int sz, int hash) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.hash = hash;
        }

        /** Forgets the local box {@code [x0..x1] × [y0..y1] × [z0..z1]}. */
        void clear(int x0, int x1, int y0, int y1, int z0, int z1) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    int row = y << 2 * SECTION_SHIFT | z << SECTION_SHIFT;
                    Arrays.fill(data, row | x0, (row | x1) + 1, 0L);
                    if (normals != null) {
                        Arrays.fill(normals, row | x0, (row | x1) + 1, null);
                    }
                }
            }
        }
    }
}
