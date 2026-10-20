package tremor.debug;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.minecraft.world.level.levelgen.Heightmap;
import tremor.core.noise.PerlinNoise;

import java.util.Locale;
import java.util.Random;

/**
 * A preview of what a lost great frenzy could leave in the world ({@code docs/design/great-frenzy.md}), to look at, not
 * to keep: {@code /tremor scar rift|maw|inverted} around where it is run. A rift: a long jagged chasm almost down to the
 * bedrock, the land on one side of it dropped into a scarp, torn edges. A maw: a funnel with spiral grooves and teeth
 * of dripstone round its rim, a throat down to the deep. Inverted land: the ground of a circle turned upside down, its
 * depths on top. Written block by block (setBlock, no neighbour updates), with the count and the time told.
 * Prototype branch only.
 */
public final class ScarPreview {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final ServerLevel level;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private int changed;
    private int columns;

    private ScarPreview(ServerLevel level) {
        this.level = level;
    }

    public static int run(CommandSourceStack source, String kind) {
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        ScarPreview preview = new ScarPreview(level);
        Random random = new Random(level.getSeed() ^ at.asLong());
        long start = System.nanoTime();
        switch (kind) {
            case "rift" -> preview.rift(at, random);
            case "maw" -> preview.maw(at);
            case "inverted" -> preview.inverted(at);
            default -> {
                source.sendFailure(Component.literal("A scar is a rift, a maw or inverted"));
                return 0;
            }
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        int count = preview.changed, cols = preview.columns;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Scar %s at %s: %d blocks changed over %d columns in %d ms", kind, at.toShortString(), count, cols, ms)),
                false);
        return count;
    }

    // ---- the rift ----

    private void rift(BlockPos at, Random random) {
        double angle = random.nextDouble() * Math.PI;
        double dx = Math.cos(angle), dz = Math.sin(angle);
        double half = 70, scarp = 30;
        int reach = (int) (half + scarp) + 4;
        int deep = level.getMinBuildHeight() + 6;
        for (int x = at.getX() - reach; x <= at.getX() + reach; x++) {
            for (int z = at.getZ() - reach; z <= at.getZ() + reach; z++) {
                double px = x + 0.5 - at.getX(), pz = z + 0.5 - at.getZ();
                double t = px * dx + pz * dz, s = -px * dz + pz * dx;
                if (Math.abs(t) > half) {
                    continue;
                }
                double taper = 1 - (t / half) * (t / half);
                double centre = 5 * PerlinNoise.noise(t * 0.035, 0.5, 0.5) * taper;
                double width = (1.5 + 7.5 * taper) * (0.75 + 0.25 * (PerlinNoise.noise(t * 0.09, 3.3, 1.1) + 1));
                double across = s - centre;
                if (Math.abs(across) > width + scarp) {
                    continue;
                }
                columns++;
                int top = surface(x, z);
                if (Math.abs(across) <= width + 1.6) {
                    // The chasm: jagged walls, nearly to the bedrock in the middle, shallow at the ends.
                    int floor = (int) Math.round(top - (top - deep) * Math.sqrt(taper));
                    for (int y = top + 30; y > floor; y--) {
                        if (Math.abs(across) <= width + 1.6 * PerlinNoise.noise(x * 0.31, y * 0.23, z * 0.31)) {
                            set(x, y, z, AIR);
                        }
                    }
                    if (Math.abs(across) <= width) {
                        double n = PerlinNoise.noise(x * 0.21, floor * 0.21, z * 0.21);
                        set(x, floor, z, n > 0.35 ? Blocks.LAVA.defaultBlockState()
                                : n > -0.1 ? Blocks.GRAVEL.defaultBlockState()
                                : Blocks.COBBLED_DEEPSLATE.defaultBlockState());
                    }
                    continue;
                }
                double edge = Math.abs(across) - width;
                if (across > 0) {
                    // The scarp: the land on this side drops, the most at the rift, none at its far side.
                    int drop = (int) Math.round(10 * taper * Math.max(0, 1 - edge / scarp));
                    if (drop > 0) {
                        shift(x, z, top, -drop);
                        top -= drop;
                    }
                }
                if (edge < 5) {
                    // Torn edges: the ground broken up and heaved.
                    double n = PerlinNoise.noise(x * 0.45, 7.7, z * 0.45);
                    if (n > 0.2) {
                        shift(x, z, top, n > 0.5 ? 2 : 1);
                    } else if (n < -0.25) {
                        set(x, top, z, n < -0.5 ? Blocks.COBBLESTONE.defaultBlockState()
                                : Blocks.COARSE_DIRT.defaultBlockState());
                    }
                }
            }
        }
    }

    // ---- the maw ----

    private void maw(BlockPos at) {
        double rim = 22, depth = 26;
        int throat = level.getMinBuildHeight() + 10;
        int reach = (int) rim + 3;
        BlockState skin = Blocks.MUD.defaultBlockState(), deepSkin = Blocks.DEEPSLATE.defaultBlockState();
        for (int x = at.getX() - reach; x <= at.getX() + reach; x++) {
            for (int z = at.getZ() - reach; z <= at.getZ() + reach; z++) {
                double px = x + 0.5 - at.getX(), pz = z + 0.5 - at.getZ();
                double r = Math.sqrt(px * px + pz * pz), phi = Math.atan2(pz, px);
                if (r > rim + 1) {
                    continue;
                }
                columns++;
                int top = surface(x, z);
                double u = Math.min(1, r / rim);
                if (r <= rim) {
                    double sink = depth * Math.pow(1 - u, 1.4);
                    double groove = 9 * Math.sin(3 * phi + r * 0.5) * u * (1 - u);
                    int floor = (int) Math.round(top - sink + groove);
                    boolean inThroat = r < 3 + 0.8 * PerlinNoise.noise(x * 0.4, 1.5, z * 0.4);
                    if (inThroat) {
                        floor = throat;
                    }
                    for (int y = top + 30; y > floor; y--) {
                        set(x, y, z, AIR);
                    }
                    if (!inThroat) {
                        set(x, floor, z, u < 0.45 ? deepSkin : skin);
                    }
                }
                // Teeth of dripstone round the rim, leaning on nothing: the maw shows them.
                double bite = Math.sin(13 * phi + 0.4);
                if (r > rim - 1.6 && r <= rim + 0.4 && bite > 0.7) {
                    int height = 3 + (int) Math.round((bite - 0.7) / 0.3 * 4);
                    int base = surface(x, z);
                    set(x, base, z, Blocks.DRIPSTONE_BLOCK.defaultBlockState());
                    for (int i = 1; i <= height; i++) {
                        DripstoneThickness thickness = i == height ? DripstoneThickness.TIP
                                : i == height - 1 ? DripstoneThickness.FRUSTUM
                                : i == 1 ? DripstoneThickness.BASE : DripstoneThickness.MIDDLE;
                        set(x, base + i, z, Blocks.POINTED_DRIPSTONE.defaultBlockState()
                                .setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.UP)
                                .setValue(PointedDripstoneBlock.THICKNESS, thickness));
                    }
                }
            }
        }
    }

    // ---- inverted land ----

    private void inverted(BlockPos at) {
        double radius = 16;
        int reach = (int) radius + 1;
        for (int x = at.getX() - reach; x <= at.getX() + reach; x++) {
            for (int z = at.getZ() - reach; z <= at.getZ() + reach; z++) {
                double px = x + 0.5 - at.getX(), pz = z + 0.5 - at.getZ();
                double u = Math.sqrt(px * px + pz * pz) / radius;
                int k = (int) Math.round(12 * (1 - u * u) * (0.85 + 0.15 * PerlinNoise.noise(x * 0.2, 4.4, z * 0.2)));
                if (u > 1 || k <= 0) {
                    continue;
                }
                columns++;
                int top = surface(x, z);
                BlockState[] below = new BlockState[k], above = new BlockState[k];
                for (int i = 0; i < k; i++) {
                    below[i] = get(x, top - i, z);
                    above[i] = get(x, top + 1 + i, z);
                }
                // What was under the ground goes up, its deepest on top; what stood on it goes down, upside down.
                for (int i = 0; i < k; i++) {
                    set(x, top + 1 + i, z, below[i]);
                    set(x, top - i, z, above[i]);
                }
            }
        }
    }

    // ---- blocks ----

    private int surface(int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
    }

    private BlockState get(int x, int y, int z) {
        return level.getBlockState(pos.set(x, y, z));
    }

    private void set(int x, int y, int z, BlockState state) {
        pos.set(x, y, z);
        if (level.getBlockState(pos) != state && level.setBlock(pos, state, FLAGS)) {
            changed++;
        }
    }

    /** Moves the column at {@code x, z} from 40 under its top {@code top} up to 30 over it by {@code delta} blocks. */
    private void shift(int x, int z, int top, int delta) {
        int low = Math.max(level.getMinBuildHeight() + 1, top - 40), high = top + 30;
        if (delta < 0) {
            for (int y = low; y <= high; y++) {
                set(x, y, z, get(x, y - delta, z));
            }
        } else {
            for (int y = high; y >= low + delta; y--) {
                set(x, y, z, get(x, y - delta, z));
            }
        }
    }
}
