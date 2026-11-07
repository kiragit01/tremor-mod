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
import tremor.core.shape.RippleParams;
import tremor.world.TremorTags;

/**
 * The echo of a ring a frozen tremor sends through the ground (1.4.0, the user's idea, as the comments asked it to
 * sense a player now and then): its front runs out at {@code echo.speed}, out to {@code echo.range}, and whatever
 * stands on the ground where it passes gives an echo back, a rough fix (off by up to {@value #FUZZ} blocks). A player
 * clearly, a mob faintly (false trails). Not one in the air as it passes (one who jumps just then), in water, or on wool
 * or a carpet (they swallow it, as the hearing has it); not one more than {@value #HEIGHT} blocks above or below.
 * <p>
 * Each is judged by the crest they see and jump, not by the front a quarter wave ahead of it, and as late as they see
 * it: in the air at any moment from {@value #SEEN_FROM} to {@value #SEEN_TO} s after it passed them here, they jumped
 * it; on the ground all that while, they are found (the user: jumped it, and found a second later all the same).
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
    /**
     * When a player sees the crest pass them (seconds after it passed them on the server): the client draws the tremor
     * some 0.15 to 0.4 s late (its playback delay, more with a ping), and their jump reaches the server later still.
     */
    static final double SEEN_FROM = 0.1;
    static final double SEEN_TO = 0.6;
    /** One who came this far in past the judged edge within a tick is still judged (blocks). */
    private static final double CATCH = 1;

    /** An echo: what gave it, where it seems to be, how loud. */
    record Hit(LivingEntity entity, Player player, Vec3 at, double perceived, double anger) {
    }

    private final Vec3 center;
    private final long start;
    private double swept;
    private final Set<UUID> met = new HashSet<>();
    /** Whether a mob's echo came back from this ring already: one is heard, the rest of a herd is not. */
    private boolean mobHeard;

    Echo(Vec3 center, long start) {
        this.center = center;
        this.start = start;
    }

    /**
     * How far out the crest is seen {@code late} seconds after it passed (blocks): it runs a quarter wave behind the
     * front, as the client draws it ({@link tremor.core.shape.Ripple}).
     */
    private double seen(long now, double late) {
        double crest = RippleParams.echo().wavelength() / 4;
        return Math.min(TremorConfig.COMMON.echo.range.get(),
                TremorConfig.COMMON.echo.speed.get() * ((now - start) / 20.0 - late) - crest);
    }

    /** Seconds since the ring went out, and how far across {@code e} is from where it did (for the log). */
    String where(LivingEntity e, long now) {
        return String.format(java.util.Locale.ROOT, "%.1f blocks off, %.2f s into its ring",
                Math.hypot(e.getX() - center.x(), e.getZ() - center.z()), (now - start) / 20.0);
    }

    boolean over(long now) {
        return seen(now, SEEN_TO) >= TremorConfig.COMMON.echo.range.get();
    }

    /** The echoes of what its crest passed over, as they saw it, since the last tick. */
    List<Hit> tick(ServerLevel level, long now) {
        double from = seen(now, SEEN_FROM), to = seen(now, SEEN_TO);
        List<Hit> hits = new ArrayList<>();
        AABB box = new AABB(center.x() - from - 1, center.y() - HEIGHT, center.z() - from - 1, center.x() + from + 1,
                center.y() + HEIGHT, center.z() + from + 1);
        RandomSource random = level.getRandom();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, Echo::answers)) {
            double d = Math.hypot(e.getX() - center.x(), e.getZ() - center.z());
            if (d > from || d <= swept - CATCH || met.contains(e.getUUID())) {
                continue; // not seen it yet, or it passed before they came
            }
            if (!grounded(level, e)) {
                met.add(e.getUUID()); // in the air as its crest passed (they jumped it), in water, on wool or a carpet
                continue;
            }
            if (d > to) {
                continue; // its crest passes them yet: a jump still saves them
            }
            met.add(e.getUUID());
            double a = random.nextDouble() * Math.PI * 2, off = random.nextDouble() * FUZZ;
            Vec3 at = new Vec3(e.getX() + Math.cos(a) * off, e.getY(), e.getZ() + Math.sin(a) * off);
            Player player = e instanceof Player p ? p : null;
            hits.add(new Hit(e, player, at, player != null ? PLAYER : MOB, player != null ? PLAYER_ANGER : 0));
        }
        swept = Math.max(swept, to);
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

    /** Whether a mob's echo of this ring is still to be heard; true once, for the first mob it meets. */
    boolean takeMob() {
        boolean first = !mobHeard;
        mobHeard = true;
        return first;
    }
}
