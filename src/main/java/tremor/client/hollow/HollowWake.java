package tremor.client.hollow;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import tremor.config.TremorConfig;
import tremor.core.math.Vec3;
import tremor.core.shape.HollowParams;
import tremor.core.shape.HollowShape;
import tremor.core.shape.Ripple;
import tremor.core.shape.RippleParams;
import tremor.network.TremorHollowStatePayload;

/**
 * The wake of the node's rings around the local player in the dark of the hollow (SPEC 9 phase 2: "по волне видно,
 * откуда она пришла"): the ground the rings raise ({@link HollowGround}) is all the player sees of them within the
 * black fog, so they also stir the surfaces they cross, once per client tick, along the part of their leading crest
 * within sight ({@link WakeCurve}):
 * <ul>
 *     <li>little clouds of dust rise off the floor right where the crest runs over it around the player (the circle in
 *     which it cuts the floor at the player's feet, {@link WakeCurve#floorRadius}) and drift on the way the ring runs,
 *     so the wave is seen as a line of dust coming from one side, passing and going on;</li>
 *     <li>where the crest crosses a ceiling within sight, dust trickles down from it, and where it runs through a wall,
 *     a cloud comes out of the wall;</li>
 *     <li>as the crest runs under the player, a puff of dust at the player's feet, and a faint thump that
 *     {@code tremor.client.sound.HollowSounds} plays ({@link #passes}).</li>
 * </ul>
 * The clouds are vanilla's poof (1.20.1 has no dust plume), not chips of the block:
 * the renderer already kicks those up along the rings, and in the black fog they are as dark as the ground they come
 * off, while a pale cloud stands out against it a few blocks away. Where there is no light to see even a pale cloud
 * by (a copied cave without torches), some of the clouds come with a dim glint drawn at full brightness
 * ({@link #glint}), so the crest still shows there, as a sparse line of them. The dust and the glints follow the
 * height of the ring (lower far from the node, higher as the hollow closes), the {@code rippleDust} client config and
 * the particle setting (half at Decreased, none at Minimal); the thump does not.
 * Runs on the level's game time with the node's pulse ({@link HollowPulse}, which ticks first); nothing while the
 * game is paused, the player is dead or the node is gone. Main thread only.
 */
public final class HollowWake {
    private static final HollowParams PARAMS = HollowParams.defaults();
    private static final double TICK_SECONDS = 1.0 / SharedConstants.TICKS_PER_SECOND;
    /** Dust starts this far out of the open face, so that its box does not begin inside the block. */
    private static final double CLEARANCE = 0.1;
    /**
     * The floor under a point of the crest is looked for from this many blocks above the player's feet (a step up)...
     */
    private static final int FLOOR_RISE = 1;
    /** ...down to this many below them (a ledge down). */
    private static final int FLOOR_DROP = 3;
    /**
     * A point of the crest within the ground with open space at most this many blocks above it lies in the floor
     * (the floor's dust is the floor circle's); deeper, it lies in a wall.
     */
    private static final int FLOOR_DEPTH = 2;
    /** From a point of the crest in the open, the ceiling is looked for this many blocks up. */
    private static final int CEILING_HEIGHT = 3;
    /**
     * Speed (blocks per tick) a cloud of dust is handed on along the way the ring runs, or out of a wall: it drifts a
     * block or two after the crest. It rises by itself (the plume adds its own lift, half a block or so).
     */
    private static final double DRIFT = 0.1;
    /** The puff at the feet is spread this far around the player. */
    private static final double PUFF_SPREAD = 0.35;
    /**
     * Light (0..15: the brighter of the block light and the sky light, as the sky of the hollow is darkened) at which
     * the dust is too dark to be seen in the black fog: at a spot this dark or darker a cloud of dust along a crest may
     * come with a glint ({@link #glint}).
     */
    private static final int DARK_LIGHT = 3;
    /** Share of the clouds of dust in the dark that come with a glint: few, so the crest shows as a sparse line. */
    private static final double GLINT_SHARE = 0.3;
    /** The glint is drawn at this share of its colour (it is a sculk charge's pop): dim, though in no light at all. */
    private static final float GLINT_TINT = 0.5f;

    private static ClientLevel owner;
    /** Game time of the beat whose ring last ran under the player. */
    private static long passedBeat = Long.MIN_VALUE;
    /** Rings that ran under the player so far, counted on: tells the sounds that one did. */
    private static int passes;
    /** Strength of the ring that last ran under the player ({@link #strength}). */
    private static double passStrength;

    private HollowWake() {
    }

    /** Stirs the surfaces along the crests within sight, and counts a ring running under the player. */
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != owner) {
            owner = level;
            passedBeat = Long.MIN_VALUE;
        }
        LocalPlayer player = mc.player;
        if (level == null || player == null || player.isDeadOrDying() || mc.isPaused()) {
            return;
        }
        TremorHollowStatePayload state = ClientHollow.state();
        if (state == null || state.node() == null) {
            return;
        }
        BlockPos at = state.node();
        Vec3 node = Vec3.voxelCenter(at.getX(), at.getY(), at.getZ());
        Vec3 chest = new Vec3(player.getX(), player.getY() + player.getBbHeight() / 2, player.getZ());
        double distance = chest.distance(node);
        if (distance < 1e-6) {
            return;
        }
        Vec3 way = chest.sub(node).scale(1 / distance);
        Vec3 across = way.anyPerpendicular();
        Vec3 up = way.cross(across);
        // The floor around the player: its height and where the player stands on it as seen from the node.
        double feet = player.getY(), aboveNode = feet - node.y();
        double toPlayerX = player.getX() - node.x(), toPlayerZ = player.getZ() - node.z();
        double toPlayer = Math.sqrt(toPlayerX * toPlayerX + toPlayerZ * toPlayerZ);
        double facing = Math.atan2(toPlayerZ, toPlayerX);
        RippleParams ring = HollowShape.nodeRing(PARAMS, ClientHollow.closeness(state));
        double sight = HollowFog.far();
        double share = dustShare(mc);
        RandomSource random = level.random;
        long now = level.getGameTime();
        for (long beat : HollowPulse.beats()) {
            double age = (now - beat) * TICK_SECONDS;
            if (!Ripple.active(ring, age)) {
                continue;
            }
            double strength = strength(ring, age);
            if (beat > passedBeat && WakeCurve.passes(ring, age - TICK_SECONDS, age, distance)) {
                passedBeat = beat;
                passes++;
                passStrength = strength;
                puff(level, player, way, WakeCurve.puffCount(strength, share, random.nextDouble()));
            }
            double crest = WakeCurve.crest(ring, age);
            // Off the floor, along the arc of the crest's circle at the player's feet that is within sight.
            double radius = WakeCurve.floorRadius(crest, aboveNode);
            double half = WakeCurve.arcHalfAngle(radius, toPlayer, sight);
            int floor = WakeCurve.dustCount(WakeCurve.FLOOR_DUST_PER_TICK, strength, half * radius, sight, share,
                    random.nextDouble());
            for (int i = 0; i < floor; i++) {
                double a = facing + (random.nextDouble() * 2 - 1) * half;
                double cos = Math.cos(a), sin = Math.sin(a);
                floorDust(level, node.x() + radius * cos, feet, node.z() + radius * sin, cos, sin);
            }
            // Down from the ceilings and out of the walls, over the cap of the crest's sphere within sight.
            double cap = WakeCurve.capRadius(sight, distance, crest);
            int surface = WakeCurve.dustCount(WakeCurve.SURFACE_DUST_PER_TICK, strength, cap, sight, share,
                    random.nextDouble());
            for (int i = 0; i < surface; i++) {
                double r = cap * Math.sqrt(random.nextDouble()), phi = random.nextDouble() * 2 * Math.PI;
                Vec3 dir = way.scale(crest).add(across.scale(r * Math.cos(phi))).add(up.scale(r * Math.sin(phi)))
                        .normalize();
                stir(level, node.add(dir.scale(crest)), chest);
            }
        }
    }

    /** Rings that ran under the local player so far: changes with every one. */
    public static int passes() {
        return passes;
    }

    /** Strength of the ring that last ran under the local player, 0..1 ({@link #strength}). */
    public static double passStrength() {
        return passStrength;
    }

    /**
     * How strong a ring is {@code ageSeconds} after its beat, 0..1: its envelope then ({@code 1 - age/duration}) times
     * its amplitude as a share of a fresh ring's when the hollow starts to close, so a ring from far off is a little
     * weaker and the rings of a closing hollow stronger.
     */
    private static double strength(RippleParams ring, double ageSeconds) {
        double s = (1 - ageSeconds / ring.duration()) * ring.amplitude() / PARAMS.ring().amplitude();
        return Mth.clamp(s, 0, 1);
    }

    /**
     * A cloud of dust off the floor at {@code x, z}, near the height of the player's feet {@code feet}: on the highest
     * sturdy top face with open space above it from {@link #FLOOR_RISE} blocks above the feet down to
     * {@link #FLOOR_DROP} below them, drifting on along {@code (dirX, dirZ)}, the way the ring runs. Nothing over a
     * drop or within a wall.
     */
    private static void floorDust(ClientLevel level, double x, double feet, double z, double dirX, double dirZ) {
        int base = Mth.floor(feet);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(Mth.floor(x), base + FLOOR_RISE + 1,
                Mth.floor(z));
        boolean openAbove = open(level, pos);
        for (int y = base + FLOOR_RISE; y >= base - FLOOR_DROP; y--) {
            pos.setY(y);
            boolean openHere = open(level, pos);
            if (openAbove && !openHere) {
                if (level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP)) {
                    level.addParticle(ParticleTypes.POOF, x, y + 1 + CLEARANCE, z, dirX * DRIFT, 0,
                            dirZ * DRIFT);
                    glint(level, x, y + 1 + CLEARANCE, z, dirX * DRIFT, 0, dirZ * DRIFT);
                }
                return;
            }
            openAbove = openHere;
        }
    }

    /**
     * Dust where the crest crosses a ceiling or a wall at {@code p}: down from the ceiling above a point in the open,
     * out of the wall on the side of the player for a point deep in the ground. Nothing for a point in the open with
     * no ceiling close above, nor for one in the floor (the floor's dust is {@link #floorDust}'s).
     */
    private static void stir(ClientLevel level, Vec3 p, Vec3 chest) {
        int x = Mth.floor(p.x()), y = Mth.floor(p.y()), z = Mth.floor(p.z());
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
        if (open(level, pos)) {
            for (int k = 1; k <= CEILING_HEIGHT; k++) {
                pos.setY(y + k);
                BlockState ceiling = level.getBlockState(pos);
                if (ceiling.isFaceSturdy(level, pos, Direction.DOWN)) {
                    level.addParticle(new BlockParticleOption(ParticleTypes.FALLING_DUST, ceiling), p.x(),
                            y + k - CLEARANCE, p.z(), 0, 0, 0);
                    glint(level, p.x(), y + k - CLEARANCE, p.z(), 0, 0, 0);
                    return;
                }
                if (!open(level, pos)) {
                    return;
                }
            }
            return;
        }
        for (int k = 1; k <= FLOOR_DEPTH; k++) {
            pos.setY(y + k);
            if (open(level, pos)) {
                return;
            }
        }
        // In a wall: out of it one block towards the player, if that is open.
        Vec3 out = chest.sub(p).normalize();
        Vec3 q = p.add(out);
        pos.set(Mth.floor(q.x()), Mth.floor(q.y()), Mth.floor(q.z()));
        if (open(level, pos)) {
            level.addParticle(ParticleTypes.POOF, q.x(), q.y(), q.z(), out.x() * DRIFT, out.y() * DRIFT,
                    out.z() * DRIFT);
            glint(level, q.x(), q.y(), q.z(), out.x() * DRIFT, out.y() * DRIFT, out.z() * DRIFT);
        }
    }

    /**
     * Now and then ({@link #GLINT_SHARE}), a glint with a cloud of dust at {@code x, y, z} that is too dark to be seen
     * ({@link #DARK_LIGHT}), moving on as the cloud does: vanilla's pop of a sculk charge, which is drawn at full
     * brightness whatever the light (so it shows where nothing else does), dimmed ({@link #GLINT_TINT}), there for a
     * few ticks. The fog still darkens it with the distance, as everything. In a copy without light the crest so
     * shows as a sparse line of faint glints running over the floor, the walls and the ceilings.
     */
    private static void glint(ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
        if (level.random.nextDouble() >= GLINT_SHARE
                || level.getMaxLocalRawBrightness(BlockPos.containing(x, y, z)) > DARK_LIGHT) {
            return;
        }
        Particle particle = Minecraft.getInstance().particleEngine.createParticle(ParticleTypes.SCULK_CHARGE_POP, x,
                y, z, vx, vy, vz);
        if (particle != null) {
            particle.setColor(GLINT_TINT, GLINT_TINT, GLINT_TINT);
        }
    }

    /** A puff of dust off the ground under the player's feet, drifting on the way the ring runs. */
    private static void puff(ClientLevel level, LocalPlayer player, Vec3 way, int count) {
        if (count <= 0) {
            return;
        }
        BlockPos below = player.getOnPos();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return;
        }
        RandomSource random = level.random;
        double along = Math.sqrt(way.x() * way.x() + way.z() * way.z());
        double dirX = along > 1e-6 ? way.x() / along : 0, dirZ = along > 1e-6 ? way.z() / along : 0;
        for (int i = 0; i < count; i++) {
            double x = player.getX() + (random.nextDouble() * 2 - 1) * PUFF_SPREAD;
            double z = player.getZ() + (random.nextDouble() * 2 - 1) * PUFF_SPREAD;
            level.addParticle(ParticleTypes.POOF, x, below.getY() + 1 + CLEARANCE, z, dirX * DRIFT, 0,
                    dirZ * DRIFT);
        }
    }

    /** Whether dust can fly at the position: nothing there to collide with (air, plants, torches...). */
    private static boolean open(ClientLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /**
     * Share of the dust the client config and the particle setting allow: none with {@code rippleDust} off; as with
     * the renderer's ripple dust, half at Decreased, none at Minimal.
     */
    private static double dustShare(Minecraft mc) {
        if (!TremorConfig.CLIENT.drawDust()) {
            return 0;
        }
        ParticleStatus setting = mc.options.particles().get();
        return setting == ParticleStatus.ALL ? 1 : (setting == ParticleStatus.DECREASED ? 0.5 : 0);
    }
}
