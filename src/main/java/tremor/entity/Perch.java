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
import tremor.core.surface.Surface;
import tremor.network.TremorNetwork;
import tremor.network.TremorShapePayload;
import tremor.network.TremorStatePayload;

/**
 * The answer to a player out of reach up high (the user's design): HUNTING or seeking, the entity heard a player more
 * than {@link AwakeningRules#REACH_HEIGHT} over it, while it is within {@code awakening.reachDistance} of the place,
 * standing on ground too narrow for it ({@code movement.minGroundWidth}: a pillar, a bridge) or that no way leads to
 * from where it is (a platform on a pillar: {@link #noPath}). It guards from below: it stands under the player facing
 * there, and its brain waits, so no other sound draws it off; heard higher up, or aside out of reach still (a bridge
 * out of the pillar), or felt so every {@value #FEEL_TICKS} ticks (too high up to be heard), the player is followed.
 * Under a ceiling ({@code contact.stalactites}) a small bump rolls out of its hill, crawls up the walls to the ceiling
 * right over where the player was heard, shakes there and drops a pointed dripstone, then sinks into the ceiling; the
 * stalactite strikes whoever it falls on (the damage of a falling stalactite, the strike's throw toward the entity
 * below) and breaks without a drop. Again every {@value #COOLDOWN_TICKS} ticks while the player stays up there. After
 * {@value #MAX_DROPS} that missed, or once it has guarded {@value #BUILD_AFTER_TICKS} ticks with no ceiling to drop
 * from ({@code contact.buildUp}), it builds the pillar up out of the ground around, a rough heap ({@link PerchBuild},
 * once per guard, following the player up as they build higher, and on their heels along a bridge aside, widening it).
 * It builds under the ground (the user: blocks put under its hill looked unclear): its bump goes down
 * {@value #SINK_TICKS} ticks before the first block, stays down while there is anything to build, and once the core is
 * up and nothing is left it comes up beside the player ({@link #emerge}) and goes after them every
 * {@value #CLIMB_ORDER_TICKS} ticks, striking them as it touches them; more to build (the player higher, or aside), it
 * goes under again. The guard over, it is up at once where it is. Its anger holds while it guards (no player waits it
 * out up there), for {@value #MAX_GUARD_TICKS} ticks at most. While a stalactite falls the ground at the foot is an
 * ambush's quicksand for a moment ({@link TremorMind#setTrap}): the throw aims the player into its middle
 * ({@link Strike#aimed}); it ends {@value #LAND_TICKS} ticks after the strike, {@value #MISS_TICKS} after a miss, or at
 * once when the player comes down elsewhere. The guard ends once the player is down (no more than
 * {@link AwakeningRules#REACH_HEIGHT} over the ground under where they were heard), gone, can no longer be taken, or
 * got {@value #AWAY_DISTANCE} blocks away, or the entity calmed down below HUNTING. Not saved: the small bump is no
 * entity of the level, only drawn by the clients (its own instance); the stalactite already falling falls on.
 */
final class Perch {
    /** A sound of the player older than this (ticks) finds no perch. */
    static final int RECENT_TICKS = 1200;
    /** It gives the guard up after this long (ticks): its anger holds meanwhile, the player could not wait it out. */
    static final int MAX_GUARD_TICKS = 2400;
    /** Following a guarded player aside, it hears them out of reach this far (times {@code reachDistance}). */
    static final double FOLLOW_REACH = 2;
    /** The guarded player this far (blocks, horizontally) from where they were heard last got away. */
    static final double AWAY_DISTANCE = 24;
    /** Up beside the player, it is sent after them every this many ticks. */
    static final int CLIMB_ORDER_TICKS = 10;
    /** It goes under the ground this long (ticks) before the first block of what it builds... */
    static final int SINK_TICKS = 50;
    /** ...and this long when more comes to build while it is up. */
    static final int RESINK_TICKS = 20;
    /**
     * Guarding, it feels where the player stands every this many ticks, through what it is pressed against (a player
     * high up a tall pillar is too far to be heard).
     */
    static final int FEEL_TICKS = 20;
    /** It comes up on a node at most this far from the player's feet. */
    static final int EMERGE_RADIUS = 2;
    /** Whether a way leads to a place is searched again after this long (ticks). */
    static final int PATH_RECHECK_TICKS = 100;
    /** The player is still where they were heard within this (blocks, horizontally; heard farther, a guarded player
     * has moved aside)... */
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
    /** After this many stalactites that missed, it builds the pillar up instead ({@code contact.buildUp}). */
    static final int MAX_DROPS = 3;
    /** Without a ceiling (or stalactites) it builds once it has guarded this long (ticks). */
    static final int BUILD_AFTER_TICKS = 200;
    /** The ground the pillar stands on is looked for at most this far under the player's feet. */
    static final int MAX_PILLAR = 384;
    /** The trap at the foot waits this long after the stalactite falls for the player to come down into it... */
    static final int TRAP_TICKS = 60;
    /** ...and this long after the stalactite strikes the player (the flight down)... */
    static final int LAND_TICKS = 40;
    /** ...but only this long after one that struck nobody. */
    static final int MISS_TICKS = 10;
    /** Steps (blocks) a top is measured in, toward the trap; and the widest top looked at. */
    static final double EDGE_STEP = 0.25;
    static final double MAX_TOP = 4;
    /** Thrown off a top, a player is this much past its edge (half the width, and room) before coming back down. */
    static final double EDGE_CLEAR = 0.7;
    /** The fastest throw toward the trap (blocks per tick, across). */
    static final double MAX_THROW = 1.5;
    /** Damage of the stalactite, as a vanilla one falling from high up (scaled by the difficulty). */
    static final double DAMAGE = 6;
    private static final BlockState STALACTITE = Blocks.POINTED_DRIPSTONE.defaultBlockState()
            .setValue(PointedDripstoneBlock.TIP_DIRECTION, Direction.DOWN)
            .setValue(PointedDripstoneBlock.THICKNESS, DripstoneThickness.TIP);

    private final TremorRuntime runtime;
    private final ServerLevel level;
    private final TremorEntity entity;
    private final TremorMind mind;

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
    private long guardSince;
    private Mini mini;
    private FallingBlockEntity falling;
    private Vec3 fellFrom;
    /** What it builds up to the player, or null; once per guard. */
    private PerchBuild build;
    private boolean built;
    /** The top of the ground under where the player was heard last. */
    private int groundY;
    private long nextClimb;
    /** Its bump is under the ground: it builds ({@link #hidden}); and the game time it builds from. */
    private boolean hidden;
    private long buildFrom;
    private long nextFeel;
    /** Game time the trap at the foot ends at. */
    private long trapUntil;
    /** The last search for a way ({@link #noPath}): to which node, when, and whether none was found. */
    private long pathGoal = SurfaceGraph.NO_NODE;
    private long pathCheckedAt;
    private boolean pathMissing;

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

    Perch(TremorRuntime runtime, TremorEntity entity, TremorMind mind) {
        this.runtime = runtime;
        this.level = runtime.level();
        this.entity = entity;
        this.mind = mind;
    }

    /**
     * A sound of {@code player} the entity heard at {@code at}. The guarded player heard out of reach still, higher up
     * (building the pillar up to get away) or aside (a bridge out of it): the guard follows them, the entity going
     * under them, and so does what it builds.
     */
    void heard(UUID player, Vec3 at, long now) {
        heardPlayer = player;
        heardAt = at;
        heardTime = now;
        if (!player.equals(prey)) {
            return;
        }
        boolean aside = Math.hypot(at.x() - perch.x(), at.z() - perch.z()) > STAY_RADIUS;
        int top = topUnder(at), ground = groundUnder(at);
        if (!aside && at.y() <= perch.y() + 0.5 || top == Integer.MIN_VALUE || ground == Integer.MIN_VALUE
                || !outOfReach(at, ground + 1, perch, TremorConfig.COMMON.awakening.reachDistance.get() * FOLLOW_REACH,
                now)) {
            return;
        }
        perch = at;
        groundY = ground;
        if (build == null) {
            if (aside) {
                runtime.brainGo(entity, new Vec3(at.x(), ground + 1, at.z()), TremorEntity.TargetKind.ROAM, 0, false);
            }
        } else if (!aside) {
            build.raise(top);
        } else if (!build.walkTo(new BlockPos(floor(at.x()), top, floor(at.z())))) {
            build.retarget(floor(at.x()), floor(at.z()), ground, top); // no walkway there: anew under them
            runtime.brainGo(entity, new Vec3(at.x(), ground + 1, at.z()), TremorEntity.TargetKind.ROAM, 0, false);
        }
    }

    boolean guarding() {
        return prey != null;
    }

    /** Whether its bump is down under the ground while it builds up to the player (the clients see nothing). */
    boolean hidden() {
        return hidden;
    }

    /**
     * Before the brain: starts, keeps or ends the guard ({@code free}: nothing else holds the entity, no goto, a
     * frenzy, ai off). True while it guards: the brain waits.
     */
    boolean guard(long now, Stage stage, boolean free) {
        boolean hunting = stage == Stage.HUNTING || stage == Stage.AWAKENING;
        if (prey != null) {
            String over = !free ? "held by something else" : !hunting ? "calmed down"
                    : now - guardSince > MAX_GUARD_TICKS ? "it gave up" : preyLeft();
            if (over != null) {
                stop(over);
                return false;
            }
            entity.crawler().face(perch.sub(entity.crawler().position()));
            if (now >= nextFeel) {
                nextFeel = now + FEEL_TICKS;
                ServerPlayer player = level.getServer().getPlayerList().getPlayer(prey);
                if (player != null && player.onGround()) {
                    heard(prey, new Vec3(player.getX(), player.getY(), player.getZ()), now); // felt, not heard
                }
            }
            if (build != null) {
                if (hidden && now >= buildFrom && build.idle()) {
                    emerge(now, build.coreFilled());
                } else if (!hidden && !build.idle()) {
                    hidden = true; // more to build (the player went higher or aside): under again until it is done
                    buildFrom = now + RESINK_TICKS;
                } else if (!hidden && now >= nextClimb) {
                    nextClimb = now + CLIMB_ORDER_TICKS;
                    chase();
                }
            }
            if (build == null && mini == null && falling == null && now >= nextDrop) {
                boolean stalactites = TremorConfig.COMMON.contactStalactites.get();
                boolean buildUp = TremorConfig.COMMON.contactBuildUp.get() && !built;
                if (buildUp && (drops >= MAX_DROPS || !stalactites && now - guardSince >= BUILD_AFTER_TICKS)) {
                    startBuild(now);
                } else if (stalactites && send(now)) {
                    nextDrop = now + COOLDOWN_TICKS;
                } else if (buildUp && now - guardSince >= BUILD_AFTER_TICKS) {
                    startBuild(now); // no ceiling over the player, or no way up to it
                } else {
                    nextDrop = now + RETRY_TICKS;
                }
            }
            return true;
        }
        if (!free || !hunting || heardPlayer == null || now - heardTime > RECENT_TICKS
                || !outOfReach(heardAt, entity.crawler().position().y(), entity.crawler().position(),
                TremorConfig.COMMON.awakening.reachDistance.get(), now)) {
            return false;
        }
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(heardPlayer);
        if (player == null || player.level() != level || !AwakeningManager.takeable(player) || !near(player, heardAt)) {
            return false;
        }
        prey = heardPlayer;
        perch = heardAt;
        drops = 0;
        built = false;
        guardSince = now;
        nextDrop = now + SETTLE_TICKS;
        int ground = groundUnder(perch);
        groundY = ground != Integer.MIN_VALUE ? ground : floor(entity.crawler().position().y());
        if (ground != Integer.MIN_VALUE) {
            runtime.brainGo(entity, new Vec3(perch.x(), ground + 1, perch.z()), TremorEntity.TargetKind.ROAM, 0,
                    false); // under the player: the foot of the pillar, the ground under the platform
        }
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
        if (build != null && now >= buildFrom && build.tick(now)) {
            Tremor.LOGGER.info("Tremor #{} in {} stops building ({} blocks)", entity.instance(),
                    level.dimension().location(), build.placed());
            build = null;
        }
    }

    /**
     * Comes up out of the ground: {@code built} up to the player, beside them, on the block under their feet where
     * they were heard last if it can stand there, else on the nearest it can within {@value #EMERGE_RADIUS} blocks;
     * otherwise (no ground left to take, nowhere to stand) where it is.
     */
    private void emerge(long now, boolean built) {
        SurfaceGraph graph = runtime.graph();
        int x = floor(perch.x()), top = topUnder(perch), z = floor(perch.z());
        long node = !built ? SurfaceGraph.NO_NODE : top != Integer.MIN_VALUE && graph.isNode(x, top, z)
                ? VoxelPos.pack(x, top, z) : graph.nearestNode(perch, EMERGE_RADIUS);
        hidden = false;
        if (node == SurfaceGraph.NO_NODE) {
            return;
        }
        runtime.relocate(entity, node);
        nextClimb = now + CLIMB_ORDER_TICKS;
        Vec3 at = VoxelPos.center(node);
        level.playSound(null, at.x(), at.y(), at.z(), SoundEvents.ROOTED_DIRT_BREAK, SoundSource.HOSTILE, 1.5f, 0.5f);
        Tremor.LOGGER.info("Tremor #{} in {} comes up beside the player at {}", entity.instance(),
                level.dimension().location(), VoxelPos.toString(node));
    }

    /** Up beside the player: after them, to where they were heard last if it can stand there, from where it strikes. */
    private void chase() {
        int x = floor(perch.x()), top = topUnder(perch), z = floor(perch.z());
        if (top != Integer.MIN_VALUE && runtime.graph().isNode(x, top, z)) {
            runtime.brainGo(entity, Vec3.voxelCenter(x, top, z), TremorEntity.TargetKind.ROAM, 0, false);
        }
    }

    /** The entity is gone: the small bump with it (the clients are told). */
    void discard() {
        prey = null;
        if (build != null) {
            build.discard();
            build = null;
        }
        if (mini != null) {
            TremorNetwork.sendToPlayersInDimension(level, TremorStatePayload.absent(mini.instance,
                    level.getGameTime()));
            mini = null;
        }
    }

    // ---- the guard ----

    /**
     * Whether a sound at {@code at} comes from out of reach up high: more than {@link AwakeningRules#REACH_HEIGHT}
     * over {@code fromY} (the entity, or the ground under the sound) and within {@code reach} of {@code around}
     * horizontally, standing on a surface voxel (the first solid voxel at most 2 under the sound) that is too narrow
     * for a node, or a node no way leads to from where the entity is ({@link #noPath}).
     */
    private boolean outOfReach(Vec3 at, double fromY, Vec3 around, double reach, long now) {
        if (at.y() - fromY <= AwakeningRules.REACH_HEIGHT || Math.hypot(at.x() - around.x(), at.z() - around.z())
                > reach) {
            return false;
        }
        SurfaceGraph graph = runtime.graph();
        int x = floor(at.x()), top = topUnder(at), z = floor(at.z());
        if (top == Integer.MIN_VALUE || !Surface.isSurface(pillarView(), x, top, z)) {
            return false;
        }
        return !graph.isNode(x, top, z) || noPath(VoxelPos.pack(x, top, z), now);
    }

    /**
     * Whether no way leads from where the entity is to {@code goal}: a search as for its route (with the leaps of its
     * stage), not found within {@code movement.pathMaxNodes}. One for the same goal every {@value #PATH_RECHECK_TICKS}
     * ticks.
     */
    private boolean noPath(long goal, long now) {
        if (goal == pathGoal && now - pathCheckedAt < PATH_RECHECK_TICKS) {
            return pathMissing;
        }
        long start = runtime.startNode(entity.crawler());
        if (start == SurfaceGraph.NO_NODE) {
            return false;
        }
        SurfaceGraph graph = runtime.graph();
        int leap = graph.maxLeap();
        graph.setMaxLeap(TremorConfig.COMMON.leap(entity.stage(), entity.frenzy()));
        PathSearch.Status status = new PathSearch(graph, start, goal, TremorConfig.COMMON.pathMaxNodes.get(),
                entity.params().diveCost()).run();
        graph.setMaxLeap(leap);
        pathGoal = goal;
        pathCheckedAt = now;
        pathMissing = status != PathSearch.Status.FOUND;
        return pathMissing;
    }

    /**
     * The top of the ground under the column of {@code at}: down what the player stands on (a pillar, as far as its
     * voxels are surface ones: under it the ground is buried) and the open air under that (a platform, a bridge);
     * MIN_VALUE if none within {@value #MAX_PILLAR} blocks. Doors and the like are of the pillar ({@link #pillarView}).
     */
    private int groundUnder(Vec3 at) {
        VoxelView view = pillarView();
        int x = floor(at.x()), z = floor(at.z()), y = topUnder(at);
        if (y == Integer.MIN_VALUE) {
            return y;
        }
        int bottom = y - MAX_PILLAR;
        while (y > bottom && view.isSolid(x, y, z) && Surface.isSurface(view, x, y, z)) {
            y--;
        }
        while (y > bottom && !view.isSolid(x, y, z)) {
            y--;
        }
        return y > bottom ? y : Integer.MIN_VALUE;
    }

    /** Whether {@code player} is down: no more than {@link AwakeningRules#REACH_HEIGHT} over the ground under them. */
    private boolean down(ServerPlayer player) {
        return player.getY() - (groundY + 1) <= AwakeningRules.REACH_HEIGHT;
    }

    /**
     * The block the feet stand on at {@code at}: the first solid one ({@link #pillarView}) at most 2 under it;
     * MIN_VALUE if none.
     */
    private int topUnder(Vec3 at) {
        VoxelView view = pillarView();
        int x = floor(at.x()), y0 = floor(at.y()), z = floor(at.z());
        for (int y = y0; y >= y0 - 2; y--) {
            if (view.isSolid(x, y, z)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * The graph's view in which anything with a collision is solid too, a door, a trapdoor, a fence: a player stands on
     * it and a pillar can be built of it, though the entity does not crawl on it (the user built one of doors and
     * blocks; the ground was found on a block in its middle).
     */
    private VoxelView pillarView() {
        VoxelView view = runtime.graph().view();
        return new VoxelView() {
            @Override
            public boolean isSolid(int x, int y, int z) {
                if (view.isSolid(x, y, z)) {
                    return true; // unloaded voxels too: the state below is read only where it is loaded
                }
                BlockPos p = new BlockPos(x, y, z);
                return !level.getBlockState(p).getCollisionShape(level, p).isEmpty();
            }

            @Override
            public float conductivity(int x, int y, int z) {
                return view.conductivity(x, y, z);
            }

            @Override
            public boolean isProtected(int x, int y, int z) {
                return view.isProtected(x, y, z);
            }

            @Override
            public boolean isKnown(int x, int y, int z) {
                return view.isKnown(x, y, z);
            }
        };
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
        if (player.onGround() && down(player)) {
            return "the player is down";
        }
        return Math.hypot(player.getX() - perch.x(), player.getZ() - perch.z()) > AWAY_DISTANCE
                ? "the player got away" : null;
    }

    private void stop(String why) {
        Tremor.LOGGER.info("Tremor #{} in {} stops guarding ({}, {} stalactites)", entity.instance(),
                level.dimension().location(), why, drops);
        prey = null;
        perch = null;
        hidden = false; // up where it is
        if (mini != null) {
            mini.sinking = true;
        }
        if (build != null) {
            build.finish();
        }
    }

    /**
     * Starts building up to the player: from the ground under them ({@link #groundUnder}) to the block under their
     * feet.
     */
    private void startBuild(long now) {
        built = true;
        int x = floor(perch.x()), z = floor(perch.z()), top = topUnder(perch), foot = groundUnder(perch);
        if (top == Integer.MIN_VALUE || foot == Integer.MIN_VALUE || foot >= top) {
            return;
        }
        build = new PerchBuild(level, x, z, foot, top);
        hidden = true; // under the ground first, then the heap rises
        buildFrom = now + SINK_TICKS;
        Tremor.LOGGER.info("Tremor #{} in {} builds the pillar up at {} {} from {} to {} ({} stalactites missed)",
                entity.instance(), level.dimension().location(), x, z, foot + 1, top, drops);
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
        TremorNetwork.sendToPlayersInDimension(level, new TremorShapePayload(mini.instance,
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
                if (prey != null && mind.setTrap(now) != null) {
                    trapUntil = now + SHAKE_TICKS + TRAP_TICKS; // the ground at the foot turns to quicksand
                }
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
            TremorNetwork.sendToPlayersInDimension(level, TremorStatePayload.absent(m.instance, now));
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
                TremorNetwork.sendToPlayer(player, state);
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
            trapUntil = Math.min(trapUntil, level.getGameTime() + MISS_TICKS);
            shatter(f);
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && !player.isCreative()
                    && Math.hypot(player.getX() - f.getX(), player.getZ() - f.getZ()) <= HIT_RADIUS
                    && f.getY() <= player.getEyeY() + 0.3 && f.getY() >= player.getY() - 0.5) {
                hit(player, f);
                trapUntil = Math.max(trapUntil, level.getGameTime() + LAND_TICKS);
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
        Vec3 target = mind.trap();
        if (target == null) {
            Crawler c = entity.crawler();
            target = c.position().add(c.normal().scale(0.5)); // beside it, on its skin
        }
        // Knockback resistance shortens the throw, as it does any knockback.
        double scale = 1 - Math.max(0, Math.min(1, player.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE)));
        double lift = config.contactLift.get() * scale;
        Vec3 feet = new Vec3(player.getX(), player.getY(), player.getZ());
        Vec3 v = Strike.aimed(feet, target, lift, MAX_THROW, offTheTop(feet, target));
        player.setDeltaMovement(v.x() * scale, lift, v.z() * scale);
        player.connection.send(new ClientboundSetEntityMotionPacket(player));
        player.hurtMarked = false;
        Tremor.LOGGER.info("Tremor #{}: the stalactite strikes {} ({} damage, fell {} blocks)", entity.instance(),
                player.getGameProfile().getName(), damage, String.format(Locale.ROOT, "%.1f", fellFrom.y() - f.getY()));
    }

    /**
     * Why the trap at the foot ends now ({@link TremorMind#setTrap}), or null: its time is up, the player is gone, or
     * came down elsewhere (one who comes down into it is caught before this is asked).
     */
    String trapOver(long now) {
        if (now >= trapUntil) {
            return "nobody came down into the trap";
        }
        ServerPlayer player = prey == null ? null : level.getServer().getPlayerList().getPlayer(prey);
        if (player == null || player.level() != level || !AwakeningManager.takeable(player)) {
            return "the player is gone";
        }
        return player.onGround() && down(player) ? "the player came down elsewhere" : null;
    }

    /**
     * How far across (blocks) a player at {@code feet} goes toward {@code target} to be off the top they stand on, by
     * their width and a little more; 0 if they stand on nothing.
     */
    private double offTheTop(Vec3 feet, Vec3 target) {
        double dx = target.x() - feet.x(), dz = target.z() - feet.z(), d = Math.hypot(dx, dz);
        VoxelView view = runtime.graph().view();
        int y = floor(feet.y() - 0.5);
        if (d < 1e-6 || !view.isSolid(floor(feet.x()), y, floor(feet.z()))) {
            return 0;
        }
        for (double s = EDGE_STEP; s < MAX_TOP; s += EDGE_STEP) {
            if (!view.isSolid(floor(feet.x() + dx / d * s), y, floor(feet.z() + dz / d * s))) {
                return s + EDGE_CLEAR;
            }
        }
        return MAX_TOP;
    }

    private void play(SoundEvent sound, BlockPos at, float volume, float pitch) {
        level.playSound(null, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, sound, SoundSource.HOSTILE, volume,
                pitch);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
