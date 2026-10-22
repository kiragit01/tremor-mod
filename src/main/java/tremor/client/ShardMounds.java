package tremor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import tremor.config.TremorConfig;
import tremor.core.behavior.Stage;
import tremor.core.math.Vec3;
import tremor.core.shape.BumpFrame;
import tremor.core.shape.BumpParams;
import tremor.item.TremorItems;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The little mounds that swallow thrown shards (the user's wish, 1.2.2: the ground takes a thrown shard at once). The
 * server marks a thrown shard that has landed with no gravity and takes it a moment later ({@code
 * tremor.entity.Frenzy}); over each shard so marked the ground rises into a small round hill that engulfs it, and once
 * the shard is gone (or after {@link #HOLD_SECONDS} at most, whatever stays under it) the hill sinks back. The renderer
 * draws them as bumps of their own, with ids far below those of the entities' hills. Render thread only.
 */
public final class ShardMounds {
    /** Height of a mound at its full (blocks): over the shard and its bobbing. */
    private static final double HEIGHT = 1.3;
    /** Its width (the sigma of its round shape, blocks): some 3 blocks round, a little hill of its own. */
    private static final double WIDTH = 1.5;
    /** While it stands it breathes (the user likes the ground alive): by this share of its height, this often (s). */
    private static final double BREATH = 0.1, BREATH_SECONDS = 0.8;
    /** Seconds it takes to rise, and to sink back. */
    private static final double RISE_SECONDS = 0.6, SINK_SECONDS = 1.0;
    /** A mound stands this long at most (seconds), whatever stays under it. */
    private static final double HOLD_SECONDS = 3.0;
    private static final BumpParams SHAPE = new BumpParams(HEIGHT, WIDTH, WIDTH, WIDTH, 0, 1, 0, 0.06);
    private static final Vec3 UP = new Vec3(0, 1, 0), FORWARD = new Vec3(1, 0, 0);

    /** A mound: where it stands, when it began to rise, and when its shard was gone (NaN while it is there). */
    private static final class Mound {
        final Vec3 at;
        final double start;
        double gone = Double.NaN;

        Mound(Vec3 at, double start) {
            this.at = at;
            this.start = start;
        }

        /** When it begins to sink (seconds). */
        double end() {
            return Math.min(Double.isNaN(gone) ? Double.POSITIVE_INFINITY : gone, start + HOLD_SECONDS);
        }
    }

    private static final Map<Integer, Mound> MOUNDS = new HashMap<>();
    private static ClientLevel boundLevel;
    private static long scanned = Long.MIN_VALUE;

    private ShardMounds() {
    }

    /** The mounds standing this frame, as bumps; looks for the marked shards once a tick. */
    public static List<ClientTremor.RenderState> states(ClientLevel level, float partialTick) {
        if (level != boundLevel) {
            MOUNDS.clear();
            boundLevel = level;
            scanned = Long.MIN_VALUE;
        }
        if (level == null) {
            return List.of();
        }
        long tick = level.getGameTime();
        if (tick != scanned) {
            scanned = tick;
            scan(level, tick / 20.0);
        }
        if (MOUNDS.isEmpty()) {
            return List.of();
        }
        double now = (tick + partialTick) / 20.0;
        List<ClientTremor.RenderState> states = new ArrayList<>(MOUNDS.size());
        for (Map.Entry<Integer, Mound> entry : MOUNDS.entrySet()) {
            Mound mound = entry.getValue();
            double height = HEIGHT * smoothstep((now - mound.start) / RISE_SECONDS)
                    * (1 - smoothstep((now - mound.end()) / SINK_SECONDS))
                    * (1 + BREATH * Math.sin(2 * Math.PI * (now - mound.start) / BREATH_SECONDS));
            if (height > 0) {
                states.add(new ClientTremor.RenderState(new BumpFrame(mound.at, UP, FORWARD),
                        SHAPE.withAmplitude(height), HEIGHT, now, Integer.MIN_VALUE + entry.getKey(), Stage.HUNTING,
                        Double.NaN, null));
            }
        }
        return states;
    }

    /** A mound for every marked shard near the camera; one whose shard is gone sinks, and is dropped once down. */
    private static void scan(ClientLevel level, double now) {
        net.minecraft.world.phys.Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        double across = 2 * TremorConfig.CLIENT.deformationDistance();
        Set<Integer> seen = new HashSet<>();
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, AABB.ofSize(camera, across, across, across),
                item -> item.isNoGravity() && item.getItem().is(TremorItems.SHARD.get()))) {
            seen.add(item.getId());
            MOUNDS.computeIfAbsent(item.getId(), id -> new Mound(new Vec3(item.getX(), item.getY(), item.getZ()), now));
        }
        for (Iterator<Map.Entry<Integer, Mound>> it = MOUNDS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Mound> entry = it.next();
            Mound mound = entry.getValue();
            if (!seen.contains(entry.getKey())) {
                if (Double.isNaN(mound.gone)) {
                    mound.gone = now;
                }
                if (now > mound.end() + SINK_SECONDS) {
                    it.remove(); // one whose shard stays (after HOLD_SECONDS) is kept, down, so as not to rise again
                }
            }
        }
    }

    private static double smoothstep(double x) {
        double t = Math.max(0, Math.min(1, x));
        return t * t * (3 - 2 * t);
    }
}
