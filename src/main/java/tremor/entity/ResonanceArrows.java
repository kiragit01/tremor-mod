package tremor.entity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3f;
import tremor.Tremor;
import tremor.core.math.Vec3;
import tremor.core.shape.BumpParams;
import tremor.effect.TremorEffects;
import tremor.hearing.VibrationListener;
import tremor.sound.TremorSounds;
import tremor.network.TremorShapePayload;
import tremor.network.TremorStatePayload;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The resonance arrows in flight and where they strike (1.4.0, the user's idea; the item is
 * {@link tremor.item.ResonanceArrowItem}). Flying, one rings: small rings of light around it. Where it strikes it bursts
 * into a pulse, a small ring of copies running out over the surface it struck, and is gone: every listener's signal within {@value #SIGNAL_RADIUS} blocks is soaked up (the user: a
 * running signal is too small to hit, the pulse has a reach), every listener and stalactite crawler within
 * {@value #CREATURE_RADIUS} dissolves (bigger and still, a smaller reach). One that struck a block and none of them
 * rings through the ground there, {@value #LOUDNESS} loud: it may bring the tremor (a miss is a risk, or a lure). The
 * tremor itself it does not touch.
 */
public final class ResonanceArrows {
    /** An arrow of this tag is a resonance arrow (the tag is saved with it). */
    public static final String TAG = "tremor_resonance";
    static final double SIGNAL_RADIUS = 4;
    static final double CREATURE_RADIUS = 2.2;
    static final double LOUDNESS = 10;
    private static final DustParticleOptions LIGHT = new DustParticleOptions(new Vector3f(0.72f, 0.45f, 1.0f), 0.9f);

    private static final Map<ServerLevel, Set<AbstractArrow>> FLYING = new HashMap<>();

    private ResonanceArrows() {
    }

    /** Marks {@code arrow} as a resonance arrow; returns it. */
    public static <T extends AbstractArrow> T mark(T arrow) {
        arrow.addTag(TAG);
        return arrow;
    }

    static boolean marked(Entity entity) {
        return entity instanceof AbstractArrow && entity.getTags().contains(TAG);
    }

    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && marked(event.getEntity())) {
            FLYING.computeIfAbsent(level, l -> new HashSet<>()).add((AbstractArrow) event.getEntity());
            Entity arrow = event.getEntity();
            level.playSound(null, arrow.getX(), arrow.getY(), arrow.getZ(), TremorSounds.RESONANCE_FLIGHT.get(),
                    SoundSource.PLAYERS, 0.9f, 0.95f + level.getRandom().nextFloat() * 0.1f);
        }
    }

    /** Rings around every resonance arrow in flight. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        tickPulses(level);
        Set<AbstractArrow> flying = FLYING.get(level);
        if (flying == null || flying.isEmpty()) {
            return;
        }
        flying.removeIf(Entity::isRemoved);
        if (level.getGameTime() % 2 != 0) {
            return;
        }
        for (AbstractArrow arrow : flying) {
            net.minecraft.world.phys.Vec3 v = arrow.getDeltaMovement();
            if (v.lengthSqr() > 1e-4) {
                ring(level, arrow.position(), new Vec3(v.x, v.y, v.z), 0.35, 6);
            }
            if (v.lengthSqr() > 0.04 && level.getGameTime() % 10 == 0) {
                level.playSound(null, arrow.getX(), arrow.getY(), arrow.getZ(), TremorSounds.RESONANCE_FLIGHT.get(),
                        SoundSource.PLAYERS, 0.35f, 0.9f + level.getRandom().nextFloat() * 0.2f); // it rings on as it goes
            }
        }
    }

    /** Where a resonance arrow strikes: the pulse (see the class). */
    public static void onImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        if (!(projectile.level() instanceof ServerLevel level) || !marked(projectile)) {
            return;
        }
        HitResult hit = event.getRayTraceResult();
        net.minecraft.world.phys.Vec3 at = hit.getLocation();
        Vec3 where = new Vec3(at.x, at.y, at.z);
        boolean struck = Listeners.resonate(level, where, SIGNAL_RADIUS, CREATURE_RADIUS)
                | StalactiteCrawlers.resonate(level, where, CREATURE_RADIUS);
        // The pulse runs out over the surface struck as a small ring of copies (the user), not of light.
        if (hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK) {
            net.minecraft.core.BlockPos b = block.getBlockPos();
            net.minecraft.core.Vec3i n = block.getDirection().getNormal();
            pulse(level, new Vec3(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5), new Vec3(n.getX(), n.getY(), n.getZ()));
        } else {
            net.minecraft.core.BlockPos b = net.minecraft.core.BlockPos.containing(at.x, at.y - 0.5, at.z);
            pulse(level, new Vec3(b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5), Vec3.UNIT_Y);
        }
        level.playSound(null, at.x, at.y, at.z, TremorSounds.RESONANCE_HIT.get(), SoundSource.PLAYERS, 1.6f,
                0.95f + level.getRandom().nextFloat() * 0.1f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0f, 0.6f);
        if (hit instanceof net.minecraft.world.phys.EntityHitResult struckEntity
                && struckEntity.getEntity() instanceof net.minecraft.world.entity.LivingEntity living) {
            // A small concussion of sound: the ears ring, every sound goes detuned for a while (the user).
            living.addEffect(new net.minecraft.world.effect.MobEffectInstance(TremorEffects.RESONANCE,
                    TremorEffects.RESONANCE_TICKS));
        }
        if (hit.getType() == HitResult.Type.BLOCK && hit instanceof BlockHitResult block) {
            if (!struck) {
                VibrationListener.resonance(level, block.getBlockPos(), LOUDNESS); // a miss rings through the ground
            }
            projectile.discard(); // it is spent in the pulse
            event.setCanceled(true);
        }
        Tremor.LOGGER.info("A resonance arrow strikes at {} in {}: {}", String.format(java.util.Locale.ROOT,
                "%.1f %.1f %.1f", at.x, at.y, at.z), level.dimension().location(),
                struck ? "it struck the small ones" : hit.getType() == HitResult.Type.BLOCK ? "a miss rings through the ground"
                        : "an entity");
    }

    /** Drawing a bow (or loading a crossbow) with a resonance arrow on it: its head starts to ring. */
    public static void onUseItem(net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent.Start event) {
        if (event.getEntity() instanceof net.minecraft.world.entity.player.Player player
                && player.level() instanceof ServerLevel level
                && event.getItem().getItem() instanceof net.minecraft.world.item.ProjectileWeaponItem
                && player.getProjectile(event.getItem()).is(tremor.item.TremorItems.RESONANCE_ARROW.get())) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(), TremorSounds.RESONANCE_DRAW.get(),
                    SoundSource.PLAYERS, 0.8f, 0.95f + level.getRandom().nextFloat() * 0.1f);
        }
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        FLYING.clear();
        PULSES.clear();
    }

    // ---- the pulse: a small ring of copies over the surface struck ----

    /** A pulse's bump is too low to be drawn: only its ring is (as high as a full ripple). */
    private static final double PULSE_HEIGHT = 0.02;
    /** It lasts this long (ticks): the ring, and a little slack. */
    static final int PULSE_TICKS = 28;
    private static final Map<ServerLevel, java.util.List<Pulse>> PULSES = new HashMap<>();

    private static final class Pulse {
        final int instance;
        final Vec3 center;
        final Vec3 normal;
        final int lasts;
        int ticks;

        Pulse(Vec3 center, Vec3 normal, boolean big) {
            this.instance = big ? MinorIds.next() : MinorIds.nextPulse();
            this.center = center;
            this.normal = normal;
            this.lasts = big ? (int) RippleTimer.RUN_TICKS + 6 : PULSE_TICKS;
        }
    }

    /** A pulse at the voxel {@code center} of the surface struck, its face {@code normal}. */
    static void pulse(ServerLevel level, Vec3 center, Vec3 normal) {
        ring(level, center, normal, false);
    }

    /**
     * A ring at {@code center}: small (a resonance arrow's pulse) or {@code big}, the big ring of a tremor's echo
     * (the hollow's digesting waves, {@code tremor.hollow.Marks}).
     */
    public static void ring(ServerLevel level, Vec3 center, Vec3 normal, boolean big) {
        Pulse p = new Pulse(center, normal, big);
        PULSES.computeIfAbsent(level, l -> new java.util.ArrayList<>()).add(p);
        sendPulse(level, p, true);
    }

    private static void tickPulses(ServerLevel level) {
        java.util.List<Pulse> pulses = PULSES.get(level);
        if (pulses == null || pulses.isEmpty()) {
            return;
        }
        pulses.removeIf(p -> {
            p.ticks++;
            if (p.ticks >= p.lasts) {
                PacketDistributor.sendToPlayersInDimension(level, TremorStatePayload.absent(p.instance, level.getGameTime()));
                return true;
            }
            if (p.ticks % tremor.config.TremorConfig.COMMON.syncInterval.get() == 0) {
                sendPulse(level, p, false);
            }
            return false;
        });
    }

    private static void sendPulse(ServerLevel level, Pulse p, boolean shape) {
        Vec3 forward = p.normal.anyPerpendicular().normalize();
        TremorStatePayload state = new TremorStatePayload(p.instance, true, level.getGameTime(), p.center, p.normal,
                forward, Vec3.ZERO, (float) PULSE_HEIGHT, 0, tremor.core.behavior.Stage.HUNTING, p.ticks, 0, null);
        double range = tremor.config.TremorConfig.COMMON.syncRange.get();
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            if (player.distanceToSqr(p.center.x(), p.center.y(), p.center.z()) <= range * range) {
                if (shape) {
                    PacketDistributor.sendToPlayer(player, new TremorShapePayload(p.instance,
                            BumpParams.defaults().scaled(0.3).withAmplitude(PULSE_HEIGHT)));
                }
                PacketDistributor.sendToPlayer(player, state);
            }
        }
    }

    /** A ring of light of {@code radius} around {@code center}, square to {@code axis}. */
    private static void ring(ServerLevel level, net.minecraft.world.phys.Vec3 center, Vec3 axis, double radius,
                             int points) {
        Vec3 n = axis.normalize();
        Vec3 u = n.anyPerpendicular().normalize();
        Vec3 w = n.cross(u);
        for (int i = 0; i < points; i++) {
            double a = Math.PI * 2 * i / points;
            Vec3 p = u.scale(Math.cos(a) * radius).add(w.scale(Math.sin(a) * radius));
            level.sendParticles(LIGHT, center.x + p.x(), center.y + p.y(), center.z + p.z(), 1, 0, 0, 0, 0);
        }
    }
}
