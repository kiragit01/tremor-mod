package tremor.entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import tremor.config.TremorConfig;
import tremor.core.math.Vec3;
import tremor.world.TremorTags;

/**
 * The echo of a ring a frozen tremor sends through the ground (1.4.0, the user's idea, as the comments asked it to
 * sense a player now and then): its front runs out at {@code echo.speed}, out to {@code echo.range}, and whatever
 * stands on the ground where it passes gives an echo back, a rough fix (off by up to {@value #FUZZ} blocks). A player
 * clearly, a mob faintly (false trails). Not one in the air as it passes (one who jumps just then), in water, or on wool
 * or a carpet (they swallow it, as the hearing has it); not one more than {@value #HEIGHT} blocks above or below.
 */
final class Echo {
    /** The fix is off by up to this (blocks, across). */
    static final double FUZZ = 3;
    /** Only what stands within this far above or below the ring (blocks). */
    static final double HEIGHT = 6;
    /** How loud an echo comes back (as a perceived loudness), and the anger it adds. */
    static final double PLAYER = 0.6;
    static final double MOB = 0.3;
    static final double PLAYER_ANGER = 4;

    /** An echo: what gave it, where it seems to be, how loud. */
    record Hit(LivingEntity entity, Player player, Vec3 at, double perceived, double anger) {
    }

    private final Vec3 center;
    private final long start;
    private double swept;
    private final Set<UUID> met = new HashSet<>();

    Echo(Vec3 center, long start) {
        this.center = center;
        this.start = start;
    }

    /** The front this far out at {@code now} (blocks). */
    private double radius(long now) {
        return Math.min(TremorConfig.COMMON.echo.range.get(),
                TremorConfig.COMMON.echo.speed.get() * (now - start) / 20.0);
    }

    boolean over(long now) {
        return radius(now) >= TremorConfig.COMMON.echo.range.get();
    }

    /** The echoes of what the front passed over since the last tick. */
    List<Hit> tick(ServerLevel level, long now) {
        double r = radius(now);
        List<Hit> hits = new ArrayList<>();
        AABB box = new AABB(center.x() - r - 1, center.y() - HEIGHT, center.z() - r - 1, center.x() + r + 1,
                center.y() + HEIGHT, center.z() + r + 1);
        RandomSource random = level.getRandom();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, Echo::answers)) {
            double d = Math.hypot(e.getX() - center.x(), e.getZ() - center.z());
            if (d > r || d <= swept || !met.add(e.getUUID())) {
                continue; // not reached yet, or passed before
            }
            if (!grounded(level, e)) {
                continue; // in the air as it passed (a jump just then), in water, on wool or a carpet
            }
            double a = random.nextDouble() * Math.PI * 2, off = random.nextDouble() * FUZZ;
            Vec3 at = new Vec3(e.getX() + Math.cos(a) * off, e.getY(), e.getZ() + Math.sin(a) * off);
            Player player = e instanceof Player p ? p : null;
            hits.add(new Hit(e, player, at, player != null ? PLAYER : MOB, player != null ? PLAYER_ANGER : 0));
        }
        swept = r;
        return hits;
    }

    /** A player who can be found (not a spectator nor in creative), or a mob. */
    private static boolean answers(LivingEntity e) {
        return e.isAlive() && (e instanceof Player p ? !p.isSpectator() && !p.isCreative() : e instanceof Mob);
    }

    /** On the ground, not in water, nor on wool or a carpet (the carpet's own block, or the wool under the feet). */
    private static boolean grounded(ServerLevel level, LivingEntity e) {
        if (!e.onGround() || e.isInWater()) {
            return false;
        }
        BlockPos feet = BlockPos.containing(e.getX(), e.getY() + 0.01, e.getZ());
        return !level.getBlockState(feet).is(TremorTags.CONDUCTIVITY_INSULATING)
                && !level.getBlockState(feet.below()).is(TremorTags.CONDUCTIVITY_INSULATING);
    }
}
