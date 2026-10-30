package tremor.entity;

import java.util.Locale;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DripstoneThickness;
import net.neoforged.neoforge.network.PacketDistributor;
import tremor.Tremor;
import tremor.awakening.AwakeningManager;
import tremor.awakening.AwakeningRules;
import tremor.config.Difficulty;
import tremor.config.TremorConfig;
import tremor.core.VoxelView;
import tremor.core.behavior.Stage;
import tremor.core.graph.SurfaceGraph;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.motion.Crawler;
import tremor.core.motion.MotionParams;
import tremor.core.path.PathSearch;
import tremor.core.shape.BumpParams;
import tremor.network.TremorShapePayload;
import tremor.network.TremorStatePayload;

/**
 * The answer to a player out of reach on a pillar (the user's design): HUNTING or seeking, the entity heard a player
 * standing on ground too narrow for it ({@code movement.minGroundWidth}) more than {@link AwakeningRules#REACH_HEIGHT}
 * over it, while it is within {@code awakening.reachDistance} of the place. It guards from below: it stands, facing
 * there from the foot of the pillar, and its brain waits, so no other sound draws it off. Under a ceiling ({@code contact.stalactites}) a small
 * bump rolls out of its hill, crawls up the walls to the ceiling right over where the player was heard, shakes there
 * and drops a pointed dripstone, then sinks into the ceiling; the stalactite strikes whoever it falls on (the damage
 * of a falling stalactite, the strike's throw toward the entity below) and breaks without a drop. Again every
 * {@value #COOLDOWN_TICKS} ticks while the player stays up there. The guard ends once the player is down (or gone, or
 * can no longer be taken) or the entity calmed down below HUNTING. Not saved: the small bump is no entity of the
 * level, only drawn by the clients (its own instance); the stalactite already falling falls on.
 */
final class Perch {
    /** A sound of the player older than this (ticks) finds no perch. */
    static final int RECENT_TICKS = 600;
    /** The player is still up there while within this (blocks, horizontally) of where it was heard... */
    static final double STAY_RADIUS = 2;
    /** ...and at most this far below it. */
    static final double STAY_DROP = 2.5;
    /** A ceiling is looked for up to this far over the feet (blocks); at least 2 open blocks over the head. */
    static final int MAX_CEILING = 32;
    /** The small bump's lengths, times the entity's. */
    static final double MINI_SIZE = 0.55;
    /** Height of the small bump (blocks). */
    static final double MINI_AMPLITUDE = 1.3;
    /** Speed of the small bump, times the entity's. */
    static final double MINI_SPEED = 1.4;
    /** Path cost of rock for the small bump, times the entity's dive cost: it climbs where it is seen. */
    static final double MINI_DIVE_COST = 4;
    /** Ticks the small bump shakes on the ceiling before the stalactite falls. */
    static final int SHAKE_TICKS = 20;
    /** The small bump gives up the climb after this long (ticks). */
    static final int CLIMB_TICKS = 400;
    /** Ticks from the start of the guard to the first small bump: it comes to the foot of the pillar. */
    static final int SETTLE_TICKS = 30;
    /** Ticks between two drops while the player stays up there (from the end of the last one). */
    static final int COOLDOWN_TICKS = 160;
    /** No ceiling, or no way up to it: looked for again this many ticks later. */
    static final int RETRY_TICKS = 100;
    /** A falling stalactite strikes a player whose feet are this near its axis (blocks, horizontally). */
    static final double HIT_RADIUS = 0.8;
    /** Damage of the stalactite, as a vanilla one falling from high up (scaled by the difficulty). */
    static final double DAMAGE = 6;
    private static final BlockState STALACTITE = Blocks.POINTED_DRIPSTONE.defaultBlockState()
            .setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.DOWN)
            .setValue(PointedDripstoneBlock.THICKNESS, DripstoneThickness.TIP);

    private final TremorRuntime runtime;
    private final ServerLevel level;
    private final TremorEntity entity;

    /** The player heard last, where and when. */
    private UUID heardPlayer;
    private Vec3 heardAt;
    private long heardTime;
    /** The player guarded, and where it was heard up there; null if none. */
    private UUID prey;
    private Vec3 perch;
    private long nextDrop;
    /** Stalactites that fell while the player stayed up there (the guard of the same perch). */
    private int drops;
    private Mini mini;
    private FallingBlockEntity falling;
    private Vec3 fellFrom;

    /** The small bump: a crawler of its own on the entity's graph, drawn by the clients as a bump of its own. */
    private static final class Mini {
        final int instance;
        final Crawler crawler;
        final BlockPos dropAt;
        int ticks;
        boolean shaking;
        boolean sinking;

        Mini(int instance, Crawler crawler, BlockPos dropAt) {
            this.instance = instance;
            this.crawler = crawler;
            this.dropAt = dropAt;
        }
    }

    Perch(TremorRuntime runtime, TremorEntity entity) {
        this.runtime = runtime;
        this.level = runtime.level();
        this.entity = entity;
    }

    /** A sound of {@code player} the entity heard at {@code at}. */
    void heard(UUID player, Vec3 at, long now) {
        heardPlayer = player;
        heardAt = at;
        heardTime = now;
    }

    boolean guarding() {
        return prey != null;
    }

    /**
     * Before the brain: starts, keeps or ends the guard ({@code free}: nothing else holds the entity, no goto, a
     * frenzy, ai off). True while it guards: the brain waits.
     */
    boolean guard(long now, Stage stage, boolean free) {
        boolean hunting = stage == Stage.HUNTING || stage == Stage.AWAKENING;
        if (prey != null) {
            String over = !free ? "held by something else" : !hunting ? "calmed down" : preyLeft();
            if (over != null) {
                stop(over);
                return false;
            }
            entity.crawler().face(perch.sub(entity.crawler().position()));
            if (TremorConfig.COMMON.contactStalactites.get() && mini == null && falling == null && now >= nextDrop) {
                nextDrop = now + (send(now) ? COOLDOWN_TICKS : RETRY_TICKS);
            }
            return true;
        }
        if (!free || !hunting || heardPlayer == null || now - heardTime > RECENT_TICKS || !perched(heardAt)) {
            return false;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(heardPlayer);
        if (player == null || player.level() != level || !AwakeningManager.takeable(player) || !near(player, heardAt)) {
            return false;
        }
        prey = heardPlayer;
        perch = heardAt;
        drops = 0;
        nextDrop = now + SETTLE_TICKS;
        runtime.brainGo(entity, perch, TremorEntity.TargetKind.ROAM, 0, false); // to the foot of the pillar
        Tremor.LOGGER.info(String.format(Locale.ROOT, "Tremor #%d in %s guards %s out of reach at %.1f %.1f %.1f",
                entity.instance(), level.dimension().location(), player.getGameProfile().getName(), perch.x(),
                perch.y(), perch.z()));
        return true;
    }

    /**
     * After the move, every tick whatever the entity does: the small bump crawls, shakes, drops and sinks; the
     * falling stalactite strikes. {@code motion}: the entity's motion this tick.
     */
    void tick(long now, MotionParams motion) {
        if (prey != null && (entity.leaving() || entity.absorbed())) {
            stop(entity.leaving() ? "leaving" : "taken by an Awakening");
        }
        if (mini != null) {
            tickMini(now, motion);
        }
        if (falling != null) {
            tickFalling();
        }
    }

    /** The entity is gone: the small bump with it (the clients are told). */
    void discard() {
        prey = null;
        if (mini != null) {
            PacketDistributor.sendToPlayersInDimension(level, TremorStatePayload.absent(mini.instance,
                    level.getGameTime()));
            mini = null;
        }
    }

    // ---- the guard ----

    /**
     * Whether a sound at {@code at} comes from out of reach on a pillar: more than {@link AwakeningRules#REACH_HEIGHT}
     * over the entity and within {@code awakening.reachDistance} of it horizontally, on ground too narrow for it (the
     * first solid voxel at most 2 under the sound is no node).
     */
    private boolean perched(Vec3 at) {
        Vec3 bump = entity.crawler().position();
        if (at.y() - bump.y() <= AwakeningRules.REACH_HEIGHT || Math.hypot(at.x() - bump.x(), at.z() - bump.z())
                > TremorConfig.COMMON.awakening.reachDistance.get()) {
            return false;
        }
        SurfaceGraph graph = runtime.graph();
        int x = floor(at.x()), y0 = floor(at.y()), z = floor(at.z());
        for (int y = y0; y >= y0 - 2; y--) {
            if (graph.view().isSolid(x, y, z)) {
                return !graph.isNode(x, y, z);
            }
        }
        return false;
    }

    private static boolean near(ServerPlayer player, Vec3 at) {
        return Math.hypot(player.getX() - at.x(), player.getZ() - at.z()) <= STAY_RADIUS
                && player.getY() >= at.y() - STAY_DROP;
    }

    /** Why the guarded player no longer is out of reach up there, or null. */
    private String preyLeft() {
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(prey);
        if (player == null || player.level() != level) {
            return "the player is gone";
        }
        if (!AwakeningManager.takeable(player)) {
            return "the player can no longer be taken";
        }
        return near(player, perch) ? null : "the player is down";
    }

    private void stop(String why) {
        Tremor.LOGGER.info("Tremor #{} in {} stops guarding ({}, {} stalactites)", entity.instance(),
                level.dimension().location(), why, drops);
        prey = null;
        perch = null;
        if (mini != null) {
            mini.sinking = true;
        }
    }

    // ---- the small bump ----

    /**
     * Sends a small bump up to the ceiling over the perch; false if there is no ceiling within {@value #MAX_CEILING}
     * blocks (a node with open air under it all the way down, a free block to drop from) or no way up to it.
     */
    private boolean send(long now) {
        SurfaceGraph graph = runtime.graph();
        VoxelView view = graph.view();
        int x = floor(perch.x()), z = floor(perch.z()), feet = floor(perch.y());
        long ceiling = SurfaceGraph.NO_NODE;
        for (int y = feet + 1; y <= feet + MAX_CEILING; y++) {
            if (!view.isKnown(x, y, z)) {
                break;
            }
            if (view.isSolid(x, y, z)) {
                if (y >= feet + 4 && graph.isNode(x, y, z)) {
                    ceiling = VoxelPos.pack(x, y, z);
                }
                break;
            }
        }
        BlockPos dropAt = ceiling == SurfaceGraph.NO_NODE ? null : new BlockPos(x, VoxelPos.y(ceiling) - 1, z);
        long start = runtime.startNode(entity.crawler());
        if (dropAt == null || !level.getBlockState(dropAt).isAir() || start == SurfaceGraph.NO_NODE) {
            return false;
        }
        int leap = graph.maxLeap();
        graph.setMaxLeap(0); // no flying across: it climbs
        PathSearch search = new PathSearch(graph, start, ceiling, TremorConfig.COMMON.pathMaxNodes.get(),
                entity.params().diveCost() * MINI_DIVE_COST);
        PathSearch.Status status = search.run();
        graph.setMaxLeap(leap);
        if (status != PathSearch.Status.FOUND) {
            return false;
        }
        Crawler big = entity.crawler();
        Crawler crawler = new Crawler(big.position(), big.normal(), big.forward(), MINI_AMPLITUDE, big.phase());
        crawler.follow(search.path());
        mini = new Mini(runtime.newInstance(), crawler, dropAt);
        PacketDistributor.sendToPlayersInDimension(level, new TremorShapePayload(mini.instance,
                entity.params().bumpParams().scaled(MINI_SIZE).withAmplitude(MINI_AMPLITUDE)));
        sync(mini);
        Tremor.LOGGER.info("Tremor #{} in {} sends a small bump #{} up to the ceiling at {} {} {}", entity.instance(),
                level.dimension().location(), mini.instance, x, VoxelPos.y(ceiling), z);
        return true;
    }

    private void tickMini(long now, MotionParams motion) {
        Mini m = mini;
        m.ticks++;
        if (!m.sinking && !m.shaking && (m.crawler.arrived() || m.ticks > CLIMB_TICKS)) {
            m.shaking = m.crawler.arrived();
            m.sinking = !m.shaking;
            m.ticks = 0;
            if (m.shaking) {
                play(SoundEvents.POINTED_DRIPSTONE_FALL, m.dropAt, 1.5f, 0.6f);
            }
        }
        if (m.shaking && !m.sinking) {
            if (m.ticks % 4 == 0) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST,
                                level.getBlockState(m.dropAt.above())), m.dropAt.getX() + 0.5, m.dropAt.getY() + 0.9,
                        m.dropAt.getZ() + 0.5, 6, 0.4, 0.05, 0.4, 0);
            }
            if (m.ticks >= SHAKE_TICKS) {
                m.sinking = true;
                if (prey != null && level.getBlockState(m.dropAt).isAir()) {
                    drop(m.dropAt);
                }
            }
        }
        double amplitude = m.sinking ? 0 : MINI_AMPLITUDE;
        m.crawler.tick(TremorRuntime.TICK_SECONDS, new MotionParams(motion.maxSpeed() * MINI_SPEED,
                motion.acceleration() * MINI_SPEED, motion.normalSmoothingSeconds(), amplitude,
                motion.amplitudeSmoothingSeconds()), runtime.normals());
        if (m.sinking && Math.abs(m.crawler.amplitude()) < TremorMind.SUNK) {
            PacketDistributor.sendToPlayersInDimension(level, TremorStatePayload.absent(m.instance, now));
            mini = null;
        } else if (m.ticks % TremorConfig.COMMON.syncInterval.get() == 0) {
            sync(m);
        }
    }

    private void sync(Mini m) {
        Crawler c = m.crawler;
        TremorStatePayload state = new TremorStatePayload(m.instance, true, level.getGameTime(), c.position(),
                c.normal(), c.forward(), c.velocity(), (float) c.amplitude(), (float) TremorEntity.wrapPhase(c.phase()),
                Stage.HUNTING, TremorStatePayload.NO_RIPPLE, 0, null);
        Vec3 p = c.position();
        double range = TremorConfig.COMMON.syncRange.get();
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(p.x(), p.y(), p.z()) <= range * range) {
                PacketDistributor.sendToPlayer(player, state);
            }
        }
    }

    // ---- the stalactite ----

    private void drop(BlockPos at) {
        falling = FallingBlockEntity.fall(level, at, STALACTITE);
        falling.dropItem = false;
        fellFrom = new Vec3(falling.getX(), falling.getY(), falling.getZ());
        drops++;
        Tremor.LOGGER.info("Tremor #{} in {} drops a stalactite from {} {} {}", entity.instance(),
                level.dimension().location(), at.getX(), at.getY(), at.getZ());
    }

    /** Strikes a player it falls on (once, and it breaks on them); breaks without a drop where it lands. */
    private void tickFalling() {
        FallingBlockEntity f = falling;
        if (f.isRemoved()) {
            shatter(f);
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && !player.isCreative()
                    && Math.hypot(player.getX() - f.getX(), player.getZ() - f.getZ()) <= HIT_RADIUS
                    && f.getY() <= player.getEyeY() + 0.3 && f.getY() >= player.getY() - 0.5) {
                hit(player, f);
                f.discard();
                shatter(f);
                return;
            }
        }
    }

    private void shatter(FallingBlockEntity f) {
        play(SoundEvents.POINTED_DRIPSTONE_LAND, f.blockPosition(), 1, 1);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, STALACTITE), f.getX(), f.getY() + 0.5,
                f.getZ(), 20, 0.25, 0.25, 0.25, 0.1);
        falling = null;
    }

    /** The damage of a falling stalactite and the strike's throw, toward the entity below (off the pillar). */
    private void hit(ServerPlayer player, FallingBlockEntity f) {
        TremorConfig.Common config = TremorConfig.COMMON;
        float damage = (float) Difficulty.current().damage(DAMAGE);
        if (damage > 0) {
            player.hurt(level.damageSources().fallingStalactite(f), damage);
        }
        Vec3 below = entity.crawler().position();
        double dx = below.x() - player.getX(), dz = below.z() - player.getZ(), d = Math.hypot(dx, dz);
        if (d < 0.3) {
            net.minecraft.world.phys.Vec3 look = player.getLookAngle();
            dx = -look.x;
            dz = -look.z;
            d = Math.max(1e-6, Math.hypot(dx, dz));
        }
        double scale = 1 - Math.max(0, Math.min(1, player.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE)));
        double speed = config.contactKnockback.getAsDouble() * scale;
        player.setDeltaMovement(dx / d * speed, config.contactLift.getAsDouble() * scale, dz / d * speed);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        player.hurtMarked = false;
        Tremor.LOGGER.info("Tremor #{}: the stalactite strikes {} ({} damage, fell {} blocks)", entity.instance(),
                player.getGameProfile().getName(), damage, String.format(Locale.ROOT, "%.1f", fellFrom.y() - f.getY()));
    }

    private void play(SoundEvent sound, BlockPos at, float volume, float pitch) {
        level.playSound(null, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, sound, SoundSource.HOSTILE, volume,
                pitch);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
