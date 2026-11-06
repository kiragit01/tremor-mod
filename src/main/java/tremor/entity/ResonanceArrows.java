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
import tremor.hearing.VibrationListener;

/**
 * The resonance arrows in flight and where they strike (1.4.0, the user's idea; the item is
 * {@link tremor.item.ResonanceArrowItem}). Flying, one rings: small rings of light around it. Where it strikes it bursts
 * into a pulse, and is gone: every listener's signal within {@value #SIGNAL_RADIUS} blocks is soaked up (the user: a
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
        }
    }

    /** Rings around every resonance arrow in flight. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
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
        ring(level, at, Vec3.UNIT_Y, 1.0, 12);
        ring(level, at, Vec3.UNIT_Y, 2.4, 22);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 2.0f, 0.6f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.PLAYERS, 1.0f, 0.8f);
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

    public static void onServerStopped(ServerStoppedEvent event) {
        FLYING.clear();
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
