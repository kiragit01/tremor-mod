package tremor.debug;

import java.util.Locale;
import java.util.Objects;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.common.ForgeConfigSpec;
import tremor.Tremor;
import tremor.config.TremorConfig;
import tremor.core.math.Vec3;
import tremor.core.motion.Crawler;
import tremor.core.shape.BumpParams;
import tremor.entity.TremorEntity;
import tremor.entity.TremorRuntime;

/**
 * The server's part of a report for a bug report, asked for with the debug probe ({@code /tremor probe report}): the
 * mod and the world (the seed, the time, the difficulty, the game rule), where the player stands, every setting of the
 * mod that is not its default, the probe's lines on the nearest tremor ({@link DebugProbe}) and that tremor's exact
 * numbers (its place, the way it faces, its speed and its shape). Text of numbers to read, not to look at (the user:
 * instead of screenshots, text to send for a full analysis); the client adds its own part ({@code ProbeReport}).
 */
public final class DebugReport {
    private DebugReport() {
    }

    public static String text(ServerPlayer player) {
        StringBuilder out = new StringBuilder();
        ServerLevel level = player.serverLevel();
        MinecraftServer server = level.getServer();
        out.append("## server\n");
        out.append(String.format(Locale.ROOT, "mod tremor %s, %s %s, minecraft %s, %s server, %d player(s)%n",
                version(Tremor.MODID), "forge", version("forge"), SharedConstants.getCurrentVersion().getName(),
                server.isDedicatedServer() ? "dedicated" : "integrated", server.getPlayerCount()));
        long day = level.getDayTime();
        out.append(String.format(Locale.ROOT, "seed %d, %s, game time %d, day %d at %d, difficulty %s, doTremor %b%n",
                level.getSeed(), level.dimension().location(), level.getGameTime(), day / 24000, day % 24000,
                level.getDifficulty().getKey(), level.getGameRules().getBoolean(
                        tremor.TremorSwitch.RULE)));
        out.append(String.format(Locale.ROOT, "player at %.3f %.3f %.3f, yaw %.1f, pitch %.1f, %s, marks %d%n",
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot(),
                player.gameMode.getGameModeForPlayer().getName(), tremor.hollow.Marks.of(player)));
        StringBuilder changed = new StringBuilder();
        changed(changed, TremorConfig.COMMON_SPEC.getValues(), "");
        out.append("settings not at their default: ").append(changed.length() == 0 ? "none" : changed).append('\n');
        out.append("## probe (server)\n");
        for (String line : DebugProbe.lines(player)) {
            out.append(line).append('\n');
        }
        TremorRuntime runtime = DebugProbe.tremor(level, player, player.getX(), player.getY(), player.getZ());
        TremorEntity entity = runtime == null ? null : runtime.entity();
        if (entity != null) {
            Crawler crawler = entity.crawler();
            BumpParams shape = entity.params().bumpParams();
            out.append(String.format(Locale.ROOT, "## tremor #%d (server)%n", entity.instance()));
            out.append("position ").append(vec(crawler.position())).append(", normal ").append(vec(crawler.normal()))
                    .append(", forward ").append(vec(crawler.forward())).append('\n');
            out.append(String.format(Locale.ROOT,
                    "speed %.3f b/s, velocity %s, amplitude now %.3f, braking %b, arrived %b, absorbed %b%n",
                    crawler.speed(), vec(crawler.velocity()), crawler.amplitude(), crawler.braking(),
                    crawler.arrived(), entity.absorbed()));
            out.append(String.format(Locale.ROOT,
                    "shape: amplitude %.3f, sigma front %.3f back %.3f side %.3f, trail lag %.3f sigma %.3f depth %.3f, "
                            + "jitter %.3f%n", shape.amplitude(), shape.sigmaFront(), shape.sigmaBack(),
                    shape.sigmaSide(), shape.trailLag(), shape.trailSigma(), shape.trailDepth(), shape.jitter()));
        }
        return out.toString();
    }

    /** Every value under {@code values} that is not its default, as {@code key=value; }. */
    public static void changed(StringBuilder out, UnmodifiableConfig values, String prefix) {
        for (UnmodifiableConfig.Entry entry : values.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof UnmodifiableConfig inner) {
                changed(out, inner, key);
            } else if (value instanceof ForgeConfigSpec.ConfigValue<?> setting) {
                Object now = setting.get();
                if (!Objects.equals(now, setting.getDefault())) {
                    out.append(key).append('=').append(now).append("; ");
                }
            }
        }
    }

    public static String version(String mod) {
        return ModList.get().getModContainerById(mod).map(c -> c.getModInfo().getVersion().toString()).orElse("?");
    }

    public static String vec(Vec3 v) {
        return String.format(Locale.ROOT, "%.3f %.3f %.3f", v.x(), v.y(), v.z());
    }
}
