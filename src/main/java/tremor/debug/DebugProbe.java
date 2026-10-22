package tremor.debug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import tremor.awakening.AwakeningManager;
import tremor.awakening.Craters;
import tremor.config.TremorConfig;
import tremor.core.behavior.DespawnClock;
import tremor.core.math.Vec3;
import tremor.core.motion.Crawler;
import tremor.entity.TremorEntity;
import tremor.entity.TremorManager;
import tremor.entity.TremorRuntime;
import tremor.hearing.Perception;
import tremor.hearing.Vibration;
import tremor.item.TremorItems;
import tremor.network.TremorDebugPayload;
import tremor.network.TremorNetwork;
import tremor.spawn.NaturalSpawner;

/**
 * The debug probe ({@code tremor:debug_probe}, SPEC 14.1): while an operator holds it (either hand), a few lines at
 * the top left of the screen tell what the nearest tremor is up to ({@code tremor.client.DebugProbeHud}): its
 * stage and anger, where it is and how fast it goes, the depth factors where it is (the deeper, the fiercer), what it
 * does and what it heard last, how it heard the holder's latest sound (loudness, footing, distance, how loud at it,
 * the anger that added), whether it despawns, how many there are, the natural spawn for the holder, and the
 * Awakening. Sent every {@value #INTERVAL} ticks while held (or also without it, or never: {@link Panel}); holding it
 * changes nothing. Operators only (permission level 2), like the {@code /tremor} commands; it is in the operator tab
 * of the creative inventory. The short answers of the commands go above the hotbar rather than into the chat
 * ({@link #quiet(Player)}), so that the clicks of the multitool do not fill it, unless an operator asks for the chat;
 * and the panel shows only once asked for (the user's wish: quiet, and nothing on the screen, at first).
 */
public final class DebugProbe {
    /** Ticks between two updates of the lines. */
    private static final int INTERVAL = 5;
    /** How long the holder's latest sound stays on the probe (ticks). */
    private static final int OWN_SOUND_TICKS = 200;
    /** Longest the behaviour line gets (characters). */
    private static final int BEHAVIOUR_LENGTH = 110;
    /** Where the panel shows for whoever changed it ({@code /tremor probe unpin|pin}); others: {@link Panel#HIDDEN}. */
    private static final Map<UUID, Panel> PANELS = new HashMap<>();
    /** Operators who want the short answers of the commands in the chat ({@code /tremor probe quiet off}). */
    private static final java.util.Set<UUID> LOUD = new java.util.HashSet<>();
    /** The latest sound of each holder the entity evaluated. */
    private static final Map<UUID, Sound> SOUNDS = new HashMap<>();

    private record Sound(long time, Vibration vibration, Perception perception) {
    }

    /** Where the panel shows ({@code /tremor probe unpin|pin|hide}); an operator's choice lasts until they leave. */
    public enum Panel {
        /** While the probe is in hand. */
        HELD,
        /** Also without it in hand. */
        PINNED,
        /** Not at all, the probe in hand or not (at first). */
        HIDDEN
    }

    private DebugProbe() {
    }

    /** Whether {@code player} holds the probe in either hand. */
    public static boolean holding(Player player) {
        return player.getMainHandItem().is(TremorItems.DEBUG_PROBE.get())
                || player.getOffhandItem().is(TremorItems.DEBUG_PROBE.get());
    }

    /** Whether {@code player} may use it: an operator (level 2), as for the commands. */
    private static boolean allowed(ServerPlayer player) {
        return player.hasPermissions(2);
    }

    /** Where the panel of {@code player} shows from now on; if that is nowhere now, it goes at once. */
    public static void panel(ServerPlayer player, Panel panel) {
        if (panel == Panel.HIDDEN) {
            PANELS.remove(player.getUUID());
        } else {
            PANELS.put(player.getUUID(), panel);
        }
        if (!shown(player)) {
            // An empty panel: gone now, not once the last lines get stale.
            TremorNetwork.sendToPlayer(player, new TremorDebugPayload(List.of()));
        }
    }

    /** Makes the answers to {@code player} quiet or not (null: the other way round); returns which. */
    public static boolean quiet(ServerPlayer player, Boolean on) {
        boolean quiet = on != null ? on : LOUD.contains(player.getUUID());
        if (quiet) {
            LOUD.remove(player.getUUID());
        } else {
            LOUD.add(player.getUUID());
        }
        return quiet;
    }

    /** Whether the short answers of the commands go above the hotbar of {@code player}, not into the chat. */
    public static boolean quiet(Player player) {
        return !LOUD.contains(player.getUUID());
    }

    /** Whether {@code player} sees the panel: pinned, or holding the probe unless it is hidden. */
    private static boolean shown(Player player) {
        Panel panel = PANELS.getOrDefault(player.getUUID(), Panel.HIDDEN);
        return panel == Panel.PINNED || panel == Panel.HELD && holding(player);
    }

    /** The entity evaluated a sound of {@code player} (null: of nobody's); kept while the player sees the panel. */
    public static void heard(Player player, Vibration vibration, Perception perception) {
        if (player instanceof ServerPlayer server && shown(server)) {
            SOUNDS.put(server.getUUID(), new Sound(server.level().getGameTime(), vibration, perception));
        }
    }

    /** Game bus: the lines to a holder, every {@value #INTERVAL} ticks. */
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)
                || player.tickCount % INTERVAL != 0) {
            return;
        }
        if (!shown(player)) {
            SOUNDS.remove(player.getUUID());
        } else if (allowed(player)) {
            TremorNetwork.sendToPlayer(player, new TremorDebugPayload(lines(player)));
        }
    }

    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SOUNDS.remove(event.getEntity().getUUID());
        // The client forgets them too on leaving (tremor.client.ProbeTool#forget).
        PANELS.remove(event.getEntity().getUUID());
        LOUD.remove(event.getEntity().getUUID());
    }

    public static void onServerStopped(ServerStoppedEvent event) {
        SOUNDS.clear();
        PANELS.clear();
        LOUD.clear();
    }

    /** What the probe shows {@code player}. */
    static List<String> lines(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        List<String> lines = new ArrayList<>();
        TremorRuntime runtime = TremorManager.nearest(level, player.getX(), player.getY(), player.getZ());
        TremorEntity entity = runtime == null ? null : runtime.entity();
        if (entity == null) {
            lines.add("No tremor in " + level.dimension().location());
        } else {
            entity(lines, player, runtime, entity, now);
        }
        int count = TremorManager.runtimes(level).size();
        if (count > 1) {
            lines.add(String.format(Locale.ROOT, "%d tremors in the dimension (this is the nearest)", count));
        }
        lines.add(NaturalSpawner.describe(player));
        Sound sound = SOUNDS.get(player.getUUID());
        if (sound != null && now - sound.time() <= OWN_SOUND_TICKS) {
            own(lines, sound, now);
        }
        String awakening = AwakeningManager.describe(level, player.getX(), player.getZ());
        String craters = Craters.describe(level);
        for (String text : new String[]{awakening, craters}) {
            if (text != null) {
                lines.addAll(List.of(text.split("\n")));
            }
        }
        return lines;
    }

    private static void entity(List<String> lines, ServerPlayer player, TremorRuntime runtime, TremorEntity entity,
                               long now) {
        TremorConfig.Common config = TremorConfig.COMMON;
        ServerLevel level = player.serverLevel();
        Crawler crawler = entity.crawler();
        Vec3 at = crawler.position();
        lines.add(String.format(Locale.ROOT, "Tremor #%d: %s, anger %.1f of %.0f%s%s%s%s%s", entity.instance(),
                entity.stage().name().toLowerCase(Locale.ROOT), entity.anger(), TremorEntity.MAX_ANGER,
                entity.leaving() ? ", leaving" : "", runtime.listening() ? ", listening" : "",
                entity.frenzy() ? ", frenzy" : "",
                entity.aiEnabled() ? "" : ", ai off", runtime.isPaused() ? ", paused (not loaded)" : ""));
        lines.add(String.format(Locale.ROOT, "%.1f blocks from you, at %d %d %d%s; speed %.2f of %.2f b/s",
                Math.sqrt(player.distanceToSqr(at.x(), at.y(), at.z())), floor(at.x()), floor(at.y()),
                floor(at.z()), crawler.diving() ? ", diving" : "", crawler.speed(), runtime.cruiseSpeed()));
        if (level.dimension() == Level.NETHER) {
            lines.add("Depth: no part in the Nether (its own factors)");
        } else {
            double share = tremor.core.behavior.Depth.share(at.y(), config.depthShallowY.get(),
                    config.depthDeepY.get());
            lines.add(String.format(Locale.ROOT, "Depth at y %.0f: %.0f%% (hearing ×%.2f, anger ×%.2f, speed ×%.2f)",
                    at.y(), share * 100, config.depth(level, at.y(), config.depthHearingFactor),
                    config.depth(level, at.y(), config.depthAngerFactor),
                    config.depth(level, at.y(), config.depthSpeedFactor)));
        }
        String route = tremor.entity.Routes.describe(runtime);
        if (route != null) {
            lines.add(route);
        } else if (runtime.angerHeld()) {
            lines.add("Look held (/tremor hold)");
        }
        String behaviour = runtime.behavior();
        lines.add("Does: " + (behaviour.length() > BEHAVIOUR_LENGTH
                ? behaviour.substring(0, BEHAVIOUR_LENGTH - 3) + "..." : behaviour));
        lines.add("Ambush: " + runtime.ambush());
        BlockPos target = entity.target();
        lines.add("Target: " + (target == null ? "none" : target.toShortString() + " ("
                + entity.targetKind().name().toLowerCase(Locale.ROOT) + ")"));
        TremorEntity.Heard heard = entity.lastHeard();
        lines.add(heard == null ? "Heard: nothing yet" : String.format(Locale.ROOT,
                "Heard: %s at %d %d %d, %.3f loud, %.1f s ago", heard.event(), floor(heard.position().x()),
                floor(heard.position().y()), floor(heard.position().z()), heard.perceived(),
                (now - heard.gameTime()) / 20.0));
        if (entity.natural()) {
            DespawnClock clock = entity.despawnClock();
            lines.add(String.format(Locale.ROOT, "Natural%s: no player near %.0f of %.0f s, quiet %.0f of %.0f s",
                    entity.leaving() ? ", leaving" : "", clock.farSeconds(), config.despawnFarSeconds.get(),
                    clock.quietSeconds(), config.despawnQuietSeconds.get()));
        } else {
            lines.add("Spawned by a command: does not despawn by itself");
        }
    }

    /** How the entity heard the holder's latest sound. */
    private static void own(List<String> lines, Sound sound, long now) {
        Vibration vibration = sound.vibration();
        Perception perception = sound.perception();
        lines.add(String.format(Locale.ROOT, "Your %s %.1f s ago: loudness %.1f%s, footing %.2f, %.1f blocks",
                vibration.event(), (now - sound.time()) / 20.0, vibration.loudness(),
                vibration.note() == null ? "" : " (" + vibration.note() + ")", vibration.footing(),
                perception.distance()));
        double threshold = TremorConfig.COMMON.hearingParams().threshold();
        lines.add(perception.heard()
                ? String.format(Locale.ROOT, "  heard %.3f loud (threshold %.3f): anger %+.2f, %s",
                perception.perceived(), threshold, perception.angerAdded(), perception.reaction())
                : String.format(Locale.ROOT, "  %.3f loud: not heard (threshold %.3f)", perception.perceived(),
                threshold));
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
