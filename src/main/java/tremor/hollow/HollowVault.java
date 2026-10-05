package tremor.hollow;

import tremor.hollow.level.LevelNoise;

/**
 * The vault of the hollow (SPEC 9: the hollow is the entity's body, not open sky): written into the copy as it is
 * made ({@link TerrainCopier}), so nobody climbs or pillars out over the top. Over the place of arrival hangs a dome
 * of rock, some {@value #CEILING} blocks up in the middle, coming down to about half that toward the edge, its
 * underside bent by noise; four thick columns of rock, bent and swelling where they meet the floor and the vault,
 * stand at the diagonals about {@value #PILLAR_DISTANCE} of the way out. Everything else of the copy is the copy.
 * Plain Java.
 */
public final class HollowVault {
    /** Height of the vault over the place of arrival in the middle (blocks). */
    static final double CEILING = 11;
    /** Share of {@link #CEILING} the vault comes down by at the edge. */
    private static final double DROP = 0.5;
    /** How far the underside of the vault is bent up and down (blocks). */
    private static final double WOBBLE = 2.5;
    /** Where the columns stand: this share of the radius out from the middle. */
    static final double PILLAR_DISTANCE = 0.6;
    /** Radius of a column at its middle (blocks), and how much it swells toward the floor and the vault. */
    private static final double PILLAR_RADIUS = 2.0;
    private static final double PILLAR_SWELL = 2.5;
    /** Columns start this far under the place of arrival (below, the copy is mostly rock anyway). */
    private static final int PILLAR_DEPTH = 8;

    private final double centreX;
    private final double centreY;
    private final double centreZ;
    private final double radius;
    private final long seed;
    private final double[] pillarX = new double[4];
    private final double[] pillarZ = new double[4];

    /**
     * @param centreX, centreY, centreZ the place of arrival (feet) in the hollow's coordinates
     * @param radius  horizontal radius of the copy
     */
    public HollowVault(double centreX, double centreY, double centreZ, double radius, long seed) {
        this.centreX = centreX;
        this.centreY = centreY;
        this.centreZ = centreZ;
        this.radius = radius;
        this.seed = seed;
        for (int k = 0; k < 4; k++) {
            double angle = Math.PI / 4 + k * Math.PI / 2 + 0.35 * LevelNoise.at(seed, 20 + k, 0.5, 0.5, 0.5);
            double out = radius * (PILLAR_DISTANCE + 0.08 * LevelNoise.at(seed, 30 + k, 0.5, 0.5, 0.5));
            pillarX[k] = centreX + out * Math.cos(angle);
            pillarZ[k] = centreZ + out * Math.sin(angle);
        }
    }

    /** Height of the underside of the vault over the column {@code x, z}. */
    double ceiling(int x, int z) {
        double t = Math.min(1, Math.hypot(x + 0.5 - centreX, z + 0.5 - centreZ) / radius);
        return centreY + CEILING * (1 - DROP * t * t) + WOBBLE * LevelNoise.at(seed, 10, x * 0.11, 0.5, z * 0.11);
    }

    /** Whether the cell is rock of the vault or of a column (whatever the copy had there). */
    public boolean solid(int x, int y, int z) {
        double ceiling = ceiling(x, z);
        if (y + 0.5 >= ceiling) {
            return true;
        }
        if (y < centreY - PILLAR_DEPTH) {
            return false;
        }
        // The columns swell toward the floor (the height of arrival) and toward the vault.
        double toFloor = Math.max(0, 1 - Math.abs(y - centreY + 1) / 4.0);
        double toVault = Math.max(0, 1 - (ceiling - y) / 4.0);
        double swell = PILLAR_SWELL * Math.max(toFloor * toFloor, toVault * toVault);
        for (int k = 0; k < 4; k++) {
            double r = PILLAR_RADIUS + swell + 0.9 * LevelNoise.at(seed, 40 + k, x * 0.3, y * 0.18, z * 0.3);
            double dx = x + 0.5 - pillarX[k];
            double dz = z + 0.5 - pillarZ[k];
            if (dx * dx + dz * dz < r * r) {
                return true;
            }
        }
        return false;
    }
}
