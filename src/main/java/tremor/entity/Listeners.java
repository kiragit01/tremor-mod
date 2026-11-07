package tremor.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import tremor.Tremor;
import tremor.config.TremorConfig;
import tremor.core.behavior.Stage;
import tremor.core.graph.SurfaceGraph;
import tremor.core.hearing.Hearing;
import tremor.core.hearing.HearingParams;
import tremor.core.math.Vec3;
import tremor.core.math.VoxelPos;
import tremor.core.shape.BumpParams;
import tremor.hearing.Vibration;
import tremor.hollow.HollowDimension;
import tremor.network.TremorShapePayload;
import tremor.network.TremorStatePayload;
import tremor.sound.TremorSounds;
import tremor.world.LevelVoxelView;
import tremor.world.TremorTags;

/**
 * The listeners (1.4.0): small still mounds a tremor leaves where it lost the player it hunted (its search ran out
 * with nothing found), like a baby monitor. Each listens around it; hearing a player (or a thing that lands) it sends
 * a signal along the ground to its tremor, a small fast bump the player can see go; once there, the tremor has heard
 * it where the sound was. Against them: wool or a carpet over one deafens it (as the hearing has it), wool or a carpet
 * on the signal's way soaks it up, a gap in the ground loses it, and breaking the block one sits in kills it. Each
 * breathes now and then and puffs a little dust, so a watchful player sees it; it lives a few minutes, and goes with
 * its tremor. Not saved.
 * <p>
 * The clients draw them and their signals as small bumps of their own, under instances of the small creatures' range
 * ({@link TremorStatePayload#crawler}), which the seismograph and the rustle leave out.
 */
public final class Listeners {
    /** Its look: a small low mound, breathing; it stands up when it hears. */
    static final double SIZE = 0.35;
    static final double AMPLITUDE = 0.35;
    static final double PERK_AMPLITUDE = 0.9;
    static final int PERK_TICKS = 20;
    /** The signal: a smaller bump, running along the ground. */
    static final double SIGNAL_SIZE = 0.28;
    static final double SIGNAL_AMPLITUDE = 0.6;
    /** An echo runs back to its tremor this fast (blocks per second). */
    static final double ECHO_BACK_SPEED = 12;
    /** It lies this far at most from where it lost the player (blocks), no two nearer than this. */
    static final double PLACE_RADIUS = 6;
    static final double SPACING = 3;
    static final int PLACE_TRIES = 10;
    /** A floor: a node whose smoothed normal points at least this much up. */
    static final double FLOOR_NORMAL = 0.7;
    /** Breaking a block this near its middle kills it (blocks). */
    static final double KILL_RADIUS = 1.3;
    /** After a signal it is deaf this long (ticks). */
    static final int DEAF_TICKS = 40;
    /** The signal climbs or drops at most this much a step (blocks): more is a gap, it is lost. */
    static final int GROUND_STEP = 3;
    /** It is there within this far of its tremor (blocks, across), or lost after this far or long. */
    static final double ARRIVE = 2.5;
    static final double MAX_TRAVEL = 160;
    static final int MAX_SIGNAL_TICKS = 600;
    /** How loud what it relays reaches its tremor (as a perceived loudness), and the anger it adds on top: asleep it
     * wakes ALERT at least (its own listener calls it). */
    static final double RELAYED = 1.0;
    static final double RELAY_ANGER = 30;
    static final double SUNK = 0.03;
    /**
     * A listener going sinks back into the ground this share of the way each tick (some 1.5 s; a signal twice as
     * fast), never at once (the user: nothing pops out of sight).
     */
    static final double SINK_RATE = 0.07;
    static final double TICK_SECONDS = 0.05;

    private static final Map<ServerLevel, List<Listener>> LISTENERS = new HashMap<>();
    private static final Map<ServerLevel, List<Signal>> SIGNALS = new HashMap<>();

    private Listeners() {
    }

    private static final class Listener {
        final int instance;
        final TremorRuntime owner;
        final long node;
        final Vec3 at;
        final Vec3 normal;
        final BumpParams shape;
        final long until;
        double amplitude;
        long perkUntil;
        long deafUntil;
        long nextDust;
        boolean muted;
        boolean sinking;
        boolean synced;
        Set<UUID> told = new HashSet<>();

        Listener(int instance, TremorRuntime owner, long node, Vec3 normal, BumpParams shape, long until, long now) {
            this.instance = instance;
            this.owner = owner;
            this.node = node;
            this.at = VoxelPos.center(node);
            this.normal = normal;
            this.shape = shape;
            this.until = until;
            this.nextDust = now + 40;
        }
    }

    private static final class Signal {
        final int instance;
        final TremorRuntime owner;
        final Vec3 source;
        final UUID player;
        final BumpParams shape;
        Vec3 at;
        Vec3 velocity = Vec3.ZERO;
        double traveled;
        int ticks;
        double amplitude;
        boolean dying;
        Set<UUID> told = new HashSet<>();
        /** Whether a listener sent it (it hears again after); else an echo running back ({@link #echoBack}). */
        boolean fromListener = true;
        double perceived = RELAYED;
        double anger = RELAY_ANGER;
        String event = "listener";
        /** Blocks per second; the config's for a listener's. */
        double speed = -1;

        Signal(int instance, TremorRuntime owner, Vec3 at, Vec3 source, UUID player, BumpParams shape) {
            this.instance = instance;
            this.owner = owner;
            this.at = at;
            this.source = source;
            this.player = player;
            this.shape = shape;
        }
    }

    // ---- placing ----

    /**
     * {@code runtime}'s entity lost the one it hunted around {@code where} (its search ran out): it leaves 1 to
     * {@code listeners.count} listeners on the floor around there, the oldest of its own going beyond that many.
     */
    static void lost(TremorRuntime runtime, Vec3 where) {
        TremorConfig.Listeners config = TremorConfig.COMMON.listeners;
        ServerLevel level = runtime.level();
        if (where == null || !config.enabled.get() || config.count.get() <= 0 || HollowDimension.is(level)
                || tremor.TremorSwitch.off(level)) {
            return;
        }
        RandomSource random = level.getRandom();
        int count = 1 + random.nextInt(config.count.get());
        int placed = 0;
        for (int i = 0; i < count; i++) {
            if (place(runtime, where, random, "lost the player") != null) {
                placed++;
            }
        }
        if (placed > 0) {
            List<Listener> all = LISTENERS.get(level);
            long own = all.stream().filter(l -> l.owner == runtime && !l.sinking).count();
            for (Listener l : all) {
                if (own <= config.count.get()) {
                    break;
                }
                if (l.owner == runtime && !l.sinking) {
                    sink(level, l); // the oldest of its own go
                    own--;
                }
            }
        }
    }

    /**
     * {@code /tremor listener spawn}: a listener of the tremor of {@code level} nearest to {@code pos}, on a floor near
     * it; null if there is no tremor or no room.
     */
    public static Integer spawn(ServerLevel level, BlockPos pos, String why) {
        TremorRuntime nearest = null;
        double best = Double.POSITIVE_INFINITY;
        Vec3 p = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        for (TremorRuntime runtime : TremorManager.runtimes(level)) {
            TremorEntity entity = runtime.entity();
            if (entity != null && !entity.absorbed() && entity.crawler().position().distance(p) < best) {
                best = entity.crawler().position().distance(p);
                nearest = runtime;
            }
        }
        return nearest == null ? null : place(nearest, p, level.getRandom(), why);
    }

    /** A listener of {@code runtime} on a floor near {@code where}; its instance, or null if there is no room. */
    static Integer place(TremorRuntime runtime, Vec3 where, RandomSource random, String why) {
        ServerLevel level = runtime.level();
        SurfaceGraph graph = runtime.graph();
        for (int t = 0; t < PLACE_TRIES; t++) {
            double a = random.nextDouble() * Math.PI * 2, d = t == 0 ? 0 : random.nextDouble() * PLACE_RADIUS;
            Vec3 p = new Vec3(where.x() + Math.cos(a) * d, where.y(), where.z() + Math.sin(a) * d);
            long node = graph.nearestNode(p, 3);
            if (node == SurfaceGraph.NO_NODE) {
                continue;
            }
            Vec3 normal = graph.normal(VoxelPos.x(node), VoxelPos.y(node), VoxelPos.z(node));
            Vec3 at = VoxelPos.center(node);
            if (normal.y() < FLOOR_NORMAL || near(level, at, SPACING)) {
                continue;
            }
            int instance = MinorIds.next();
            BumpParams d0 = BumpParams.defaults().scaled(SIZE * (0.85 + random.nextDouble() * 0.3));
            BumpParams shape = new BumpParams(AMPLITUDE, d0.sigmaFront(), d0.sigmaBack(), d0.sigmaSide(),
                    d0.trailLag(), d0.trailSigma(), 0, 0.3 + random.nextDouble() * 0.2);
            long now = level.getGameTime();
            Listener l = new Listener(instance, runtime, node, normal, shape,
                    now + TremorConfig.COMMON.listeners.lifetimeSeconds.get() * 20L, now);
            LISTENERS.computeIfAbsent(level, k -> new ArrayList<>()).add(l);
            Tremor.LOGGER.info("Tremor #{} in {} leaves a listener #{} at {} ({})", runtime.entity() == null ? 0
                            : runtime.entity().instance(), level.dimension().location(), instance,
                    VoxelPos.toString(node), why);
            return instance;
        }
        return null;
    }

    private static boolean near(ServerLevel level, Vec3 at, double radius) {
        List<Listener> all = LISTENERS.get(level);
        if (all != null) {
            for (Listener l : all) {
                if (!l.sinking && l.at.distance(at) < radius) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Removes every listener and signal of {@code level}; how many listeners there were. */
    public static int clear(ServerLevel level) {
        List<Listener> all = LISTENERS.remove(level);
        List<Signal> signals = SIGNALS.remove(level);
        long now = level.getGameTime();
        if (signals != null) {
            for (Signal s : signals) {
                PacketDistributor.sendToPlayersInDimension(level, TremorStatePayload.absent(s.instance, now));
            }
        }
        if (all == null) {
            return 0;
        }
        for (Listener l : all) {
            PacketDistributor.sendToPlayersInDimension(level, TremorStatePayload.absent(l.instance, now));
        }
        return all.size();
    }

    // ---- hearing ----

    /** Whether a listener of {@code level} listens. */
    public static boolean listens(ServerLevel level) {
        List<Listener> all = LISTENERS.get(level);
        return all != null && !all.isEmpty();
    }

    /** Whether a source at {@code pos} is within the hearing range of a listener of {@code level}. */
    public static boolean wants(ServerLevel level, net.minecraft.world.phys.Vec3 pos) {
        List<Listener> all = LISTENERS.get(level);
        if (all == null) {
            return false;
        }
        double range = TremorConfig.COMMON.listeners.hearingRange.get();
        for (Listener l : all) {
            if (pos.distanceToSqr(l.at.x(), l.at.y(), l.at.z()) <= range * range) {
                return true;
            }
        }
        return false;
    }

    /**
     * A vibration in {@code level}: a listener that is not deaf, muted nor going, within its hearing range, hears it
     * as a tremor would ({@link Hearing#perceived}), {@code hearingFactor} times better; heard, it sends a signal to its
     * tremor. Mobs it lets be.
     */
    public static void vibration(ServerLevel level, Vibration vibration, ServerPlayer player) {
        List<Listener> all = LISTENERS.get(level);
        if (all == null || all.isEmpty() || "mob".equals(vibration.note())) {
            return;
        }
        TremorConfig.Listeners config = TremorConfig.COMMON.listeners;
        HearingParams params = TremorConfig.COMMON.hearingParams();
        double range = config.hearingRange.get();
        long now = level.getGameTime();
        LevelVoxelView view = null;
        for (Listener l : all) {
            if (l.sinking || l.muted || now < l.deafUntil || vibration.source().distance(l.at) > range) {
                continue;
            }
            if (view == null) {
                view = new LevelVoxelView(level);
            }
            double perceived = Hearing.perceived(view, vibration.source(), l.at, vibration.loudness(),
                    vibration.footing(), vibration.foliage(view), params) * config.hearingFactor.get();
            if (perceived >= params.threshold()) {
                signal(level, l, vibration.source(), player, now);
            }
        }
    }

    private static void signal(ServerLevel level, Listener l, Vec3 source, ServerPlayer player, long now) {
        l.deafUntil = Long.MAX_VALUE; // until its signal is over
        l.perkUntil = now + PERK_TICKS;
        level.playSound(null, l.at.x(), l.at.y(), l.at.z(), TremorSounds.LISTENER_PING.get(), SoundSource.HOSTILE,
                1.0f, 0.95f + level.getRandom().nextFloat() * 0.1f);
        RandomSource random = level.getRandom();
        BumpParams d0 = BumpParams.defaults().scaled(SIGNAL_SIZE);
        BumpParams shape = new BumpParams(SIGNAL_AMPLITUDE, d0.sigmaFront(), d0.sigmaBack(), d0.sigmaSide(),
                d0.trailLag(), d0.trailSigma(), 0.2, 0.25 + random.nextDouble() * 0.1);
        Signal s = new Signal(MinorIds.next(), l.owner, l.at, source, player == null ? null : player.getUUID(),
                shape);
        SIGNALS.computeIfAbsent(level, k -> new ArrayList<>()).add(s);
        Tremor.LOGGER.info("Listener #{} in {} hears {} at {} {} {} and signals its tremor", l.instance,
                level.dimension().location(), player == null ? "something" : player.getGameProfile().getName(),
                String.format(java.util.Locale.ROOT, "%.1f", source.x()),
                String.format(java.util.Locale.ROOT, "%.1f", source.y()),
                String.format(java.util.Locale.ROOT, "%.1f", source.z()));
    }

    /**
     * The echo of a frozen tremor's ring found {@code player} ({@link Echo}): a wave runs back from under them along
     * the ground to {@code owner}, as a listener's signal does and as it can be stopped (a carpet, a gap, a resonance
     * arrow), but faster; once there the tremor hears them at {@code source}.
     */
    static void echoBack(ServerLevel level, TremorRuntime owner, Vec3 from, Vec3 source, ServerPlayer player,
                         double perceived, double anger) {
        BumpParams d0 = BumpParams.defaults().scaled(SIGNAL_SIZE);
        BumpParams shape = new BumpParams(SIGNAL_AMPLITUDE, d0.sigmaFront(), d0.sigmaBack(), d0.sigmaSide(),
                d0.trailLag(), d0.trailSigma(), 0.2, 0.3);
        Signal s = new Signal(MinorIds.next(), owner, from, source, player.getUUID(), shape);
        s.fromListener = false;
        s.perceived = perceived;
        s.anger = anger;
        s.event = "echo";
        s.speed = ECHO_BACK_SPEED;
        SIGNALS.computeIfAbsent(level, k -> new ArrayList<>()).add(s);
    }

    /**
     * A resonance arrow's pulse at {@code at} ({@link ResonanceArrows}): every signal within {@code signalRadius} is
     * soaked up, every listener within {@code creatureRadius} dissolves. Whether it struck any.
     */
    public static boolean resonate(ServerLevel level, Vec3 at, double signalRadius, double creatureRadius) {
        boolean struck = false;
        List<Signal> signals = SIGNALS.get(level);
        if (signals != null) {
            for (Signal s : signals) {
                if (!s.dying && s.at.distance(at) <= signalRadius) {
                    die(level, s, "soaked up by a resonance arrow");
                    struck = true;
                }
            }
        }
        List<Listener> all = LISTENERS.get(level);
        if (all != null) {
            for (Listener l : all) {
                if (!l.sinking && l.at.distance(at) <= creatureRadius) {
                    sink(level, l);
                    level.sendParticles(ParticleTypes.REVERSE_PORTAL, l.at.x(), l.at.y() + 0.6, l.at.z(), 30, 0.4,
                            0.2, 0.4, 0.05);
                    Tremor.LOGGER.info("Listener #{} in {} dissolves in a resonance arrow's pulse", l.instance,
                            level.dimension().location());
                    struck = true;
                }
            }
        }
        return struck;
    }

    // ---- events ----

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        List<Listener> all = LISTENERS.get(level);
        List<Signal> signals = SIGNALS.get(level);
        if ((all == null || all.isEmpty()) && (signals == null || signals.isEmpty())) {
            return;
        }
        if (tremor.TremorSwitch.off(level)) {
            clear(level);
            return;
        }
        long now = level.getGameTime();
        if (signals != null) {
            signals.removeIf(s -> tickSignal(level, s, now));
        }
        if (all != null) {
            all.removeIf(l -> tickListener(level, l, now));
        }
    }

    /** A block broken in a listener kills it. */
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        List<Listener> all = LISTENERS.get(level);
        if (all == null) {
            return;
        }
        BlockPos pos = event.getPos();
        for (Listener l : all) {
            if (!l.sinking && Math.sqrt(pos.distToCenterSqr(l.at.x(), l.at.y(), l.at.z())) <= KILL_RADIUS) {
                // Gone with its block at once, under the block's own burst: a copy of a broken block cannot sink.
                l.sinking = true;
                l.amplitude = 0;
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, event.getState()), l.at.x(),
                        l.at.y() + 0.6, l.at.z(), 30, 0.4, 0.2, 0.4, 0.1);
                level.playSound(null, l.at.x(), l.at.y(), l.at.z(), SoundEvents.ROOTED_DIRT_BREAK,
                        SoundSource.HOSTILE, 1, 0.6f);
                Tremor.LOGGER.info("Listener #{} in {} killed by {}", l.instance, level.dimension().location(),
                        event.getPlayer() == null ? "something" : event.getPlayer().getGameProfile().getName());
            }
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        LISTENERS.clear();
        SIGNALS.clear();
    }

    // ---- ticking ----

    private static boolean ownerGone(TremorRuntime owner) {
        TremorEntity entity = owner.entity();
        return entity == null || entity.absorbed() || !TremorManager.runtimes(owner.level()).contains(owner);
    }

    /** {@code l} goes: it sinks back into the ground ({@link #SINK_RATE}) with a last puff of dust and an exhale. */
    private static void sink(ServerLevel level, Listener l) {
        if (l.sinking) {
            return;
        }
        l.sinking = true;
        BlockPos under = BlockPos.containing(l.at.x(), l.at.y(), l.at.z());
        level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, level.getBlockState(under)),
                l.at.x(), l.at.y() + 0.7, l.at.z(), 8, 0.35, 0.05, 0.35, 0);
        level.playSound(null, l.at.x(), l.at.y(), l.at.z(), TremorSounds.LISTENER_BREATH.get(), SoundSource.HOSTILE,
                0.3f, 0.6f + level.getRandom().nextFloat() * 0.1f);
    }

    /** One tick of a listener; true once it is gone. */
    private static boolean tickListener(ServerLevel level, Listener l, long now) {
        if (!l.sinking && (now >= l.until || ownerGone(l.owner))) {
            sink(level, l);
        }
        if (now % 10 == 0) {
            BlockPos over = BlockPos.containing(l.at.x(), l.at.y() + 1, l.at.z());
            BlockState cover = level.getBlockState(over);
            l.muted = cover.is(TremorTags.CONDUCTIVITY_INSULATING); // wool or a carpet over it: deaf
        }
        if (!l.sinking && now >= l.nextDust) {
            RandomSource random = level.getRandom();
            l.nextDust = now + 60 + random.nextInt(100);
            BlockPos under = BlockPos.containing(l.at.x(), l.at.y(), l.at.z());
            level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, level.getBlockState(under)),
                    l.at.x(), l.at.y() + 0.7, l.at.z(), 3, 0.3, 0.05, 0.3, 0);
            level.playSound(null, l.at.x(), l.at.y(), l.at.z(), TremorSounds.LISTENER_BREATH.get(),
                    SoundSource.HOSTILE, 0.35f, 0.9f + random.nextFloat() * 0.2f);
        }
        double target = l.sinking ? 0 : now < l.perkUntil ? PERK_AMPLITUDE : AMPLITUDE;
        l.amplitude += (target - l.amplitude) * (l.sinking ? SINK_RATE : 0.25);
        if (l.sinking && l.amplitude < SUNK) {
            PacketDistributor.sendToPlayersInDimension(level, TremorStatePayload.absent(l.instance, now));
            return true;
        }
        if (!l.synced || now % TremorConfig.COMMON.syncInterval.get() == 0) {
            l.synced = true;
            sync(level, l.instance, l.shape, l.at, l.normal, Vec3.UNIT_X, Vec3.ZERO, l.amplitude,
                    now < l.perkUntil || l.sinking ? 0 : 1, l.told, now);
        }
        return false;
    }

    /** One tick of a signal; true once it is over (there, or lost). */
    private static boolean tickSignal(ServerLevel level, Signal s, long now) {
        s.ticks++;
        TremorEntity entity = s.owner.entity();
        if (!s.dying && (ownerGone(s.owner) || s.ticks > MAX_SIGNAL_TICKS || s.traveled > MAX_TRAVEL)) {
            die(level, s, "lost");
        }
        if (!s.dying) {
            Vec3 goal = entity.crawler().position();
            double dx = goal.x() - s.at.x(), dz = goal.z() - s.at.z(), d = Math.hypot(dx, dz);
            if (d <= ARRIVE) {
                s.owner.relay(s.source, s.player == null ? null : level.getServer().getPlayerList()
                        .getPlayer(s.player), s.perceived, s.anger, s.event);
                level.playSound(null, goal.x(), goal.y(), goal.z(), TremorSounds.LISTENER_RELAY.get(),
                        SoundSource.HOSTILE, 1.2f, 0.9f + level.getRandom().nextFloat() * 0.2f);
                Tremor.LOGGER.info("A{} reaches tremor #{} in {}", s.fromListener ? " listener's signal"
                        : "n echo", entity.instance(), level.dimension().location());
                die(level, s, null);
            } else {
                double speed = s.speed > 0 ? s.speed : TremorConfig.COMMON.listeners.signalSpeed.get();
                double step = Math.min(d, speed * TICK_SECONDS);
                double nx = s.at.x() + dx / d * step, nz = s.at.z() + dz / d * step;
                int ground = groundAt(level, nx, s.at.y(), nz);
                if (ground == Integer.MIN_VALUE) {
                    die(level, s, "lost over a gap");
                } else if (level.getBlockState(BlockPos.containing(nx, ground, nz)).is(TremorTags.CONDUCTIVITY_INSULATING)
                        || level.getBlockState(BlockPos.containing(nx, ground + 1, nz))
                        .is(TremorTags.CONDUCTIVITY_INSULATING)) {
                    die(level, s, "soaked up");
                } else {
                    Vec3 next = new Vec3(nx, ground + 0.5, nz);
                    s.velocity = next.sub(s.at).scale(1 / TICK_SECONDS);
                    s.traveled += step;
                    s.at = next;
                }
            }
        }
        double target = s.dying ? 0 : SIGNAL_AMPLITUDE;
        s.amplitude += (target - s.amplitude) * (s.dying ? SINK_RATE * 2 : 0.35);
        if (s.dying && s.amplitude < SUNK) {
            PacketDistributor.sendToPlayersInDimension(level, TremorStatePayload.absent(s.instance, now));
            return true;
        }
        Vec3 forward = s.velocity.lengthSquared() > 1e-6 ? new Vec3(s.velocity.x(), 0, s.velocity.z()).normalize()
                : Vec3.UNIT_X;
        if (s.ticks == 1 || now % TremorConfig.COMMON.syncInterval.get() == 0) {
            sync(level, s.instance, s.shape, s.at, Vec3.UNIT_Y, forward, s.dying ? Vec3.ZERO : s.velocity,
                    s.amplitude, 0, s.told, now);
        }
        return false;
    }

    /** The signal is over: lost or soaked up ({@code why}, dust), or there (null); its listener hears again soon. */
    private static void die(ServerLevel level, Signal s, String why) {
        s.dying = true;
        s.velocity = Vec3.ZERO;
        List<Listener> all = s.fromListener ? LISTENERS.get(level) : null;
        if (all != null) {
            for (Listener l : all) {
                if (l.owner == s.owner && l.deafUntil == Long.MAX_VALUE) {
                    l.deafUntil = level.getGameTime() + DEAF_TICKS;
                }
            }
        }
        if (why != null) {
            BlockPos under = BlockPos.containing(s.at.x(), s.at.y(), s.at.z());
            level.sendParticles(new BlockParticleOption(ParticleTypes.FALLING_DUST, level.getBlockState(under)),
                    s.at.x(), s.at.y() + 0.6, s.at.z(), 8, 0.3, 0.1, 0.3, 0);
            Tremor.LOGGER.info("A listener's signal in {} is {}", level.dimension().location(), why);
        }
    }

    /**
     * The top of the ground in the column at {@code x, z}, within {@value #GROUND_STEP} blocks of {@code y} (a solid
     * block with room over it); MIN_VALUE if none.
     */
    private static int groundAt(ServerLevel level, double x, double y, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), by = (int) Math.floor(y);
        for (int dy = GROUND_STEP; dy >= -GROUND_STEP; dy--) {
            BlockPos p = new BlockPos(bx, by + dy, bz);
            if (!level.isLoaded(p)) {
                return Integer.MIN_VALUE;
            }
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                    && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()) {
                return p.getY();
            }
        }
        return Integer.MIN_VALUE;
    }

    /** A bump's state to the players within the sync range; its shape first to those that do not have it yet. */
    private static void sync(ServerLevel level, int instance, BumpParams shape, Vec3 at, Vec3 normal, Vec3 forward,
                             Vec3 velocity, double amplitude, float lurk, Set<UUID> told, long now) {
        TremorStatePayload state = new TremorStatePayload(instance, true, now, at, normal, forward, velocity,
                (float) amplitude, 0, Stage.HUNTING, TremorStatePayload.NO_RIPPLE, lurk, null);
        double range = TremorConfig.COMMON.syncRange.get();
        Set<UUID> inRange = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(at.x(), at.y(), at.z()) <= range * range) {
                if (!told.contains(player.getUUID())) {
                    PacketDistributor.sendToPlayer(player, new TremorShapePayload(instance, shape));
                }
                PacketDistributor.sendToPlayer(player, state);
                inRange.add(player.getUUID());
            }
        }
        told.retainAll(inRange);
        told.addAll(inRange);
    }
}
