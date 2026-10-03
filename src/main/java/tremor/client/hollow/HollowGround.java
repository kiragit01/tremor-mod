package tremor.client.hollow;

import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import tremor.core.math.Vec3;
import tremor.core.shape.AwakeningField;
import tremor.core.shape.HollowField;
import tremor.core.shape.HollowParams;
import tremor.core.shape.HollowShape;
import tremor.core.shape.Ripple;
import tremor.core.shape.RippleParams;
import tremor.network.TremorHollowStatePayload;

import java.util.ArrayList;
import java.util.List;

/**
 * How the ground of the hollow moves around the local player this frame (SPEC 9, phase 2), from what
 * {@link ClientHollow} knows, for the deformation renderer ({@link HollowField}):
 * <ul>
 *     <li>the walls and the floor within {@link HollowParams#breathRadius} of the player heave out of step
 *     ("пространство ходит ходуном"), higher as the hollow closes ({@link HollowShape#breathAmplitude});</li>
 *     <li>on every beat of the node ({@link HollowPulse}) a ring runs out from it over the surfaces, drawn within
 *     {@link HollowParams#ringRadius} of the player, higher as the hollow closes ({@link HollowShape#nodeRing}); the
 *     rings of a node that is gone run out from where it was;</li>
 *     <li>once the client no longer hears of the hollow while still in its level (the player is on the way out), all
 *     of it settles over {@link HollowParams#releaseSeconds} instead of snapping flat.</li>
 * </ul>
 * The region the renderer scans for the ground is centred on the player and follows the player once the player is
 * {@link HollowParams#follow} blocks from its centre. Everything runs on the level's game time. Render thread only.
 */
public final class HollowGround {
    private static final HollowParams PARAMS = HollowParams.defaults();
    private static final double TICKS_PER_SECOND = SharedConstants.TICKS_PER_SECOND;

    /** The hollow as of the last frame it was known, kept to let its ground settle; null if none. */
    private static TremorHollowStatePayload last;
    private static List<Long> lastBeats = List.of();
    /** Centre of the node whose rings run (kept once the node is gone); null if there has been none. */
    private static Vec3 node;
    /** Centre of the region scanned for the ground; null until the first frame of a hollow. */
    private static Vec3 anchor;
    /** Game time the settling started at; NaN while the hollow is known. */
    private static double releaseStart = Double.NaN;

    private HollowGround() {
    }

    /**
     * The ground of the hollow around the local player at {@code gameTime} (the level's game time plus the partial
     * tick), or null if the player is in no hollow (and its ground has settled).
     */
    public static HollowField frame(double gameTime, float partialTick) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            reset();
            return null;
        }
        TremorHollowStatePayload state = ClientHollow.state();
        double release = 1;
        List<Long> beats;
        if (state != null) {
            if (last == null || last.event() != state.event()) {
                node = null;
                anchor = null;
            }
            last = state;
            releaseStart = Double.NaN;
            beats = HollowPulse.beats();
            lastBeats = beats;
            BlockPos at = state.node();
            if (at != null) {
                node = Vec3.voxelCenter(at.getX(), at.getY(), at.getZ());
            }
        } else {
            if (last == null) {
                return null;
            }
            if (Double.isNaN(releaseStart)) {
                releaseStart = gameTime;
            }
            release = HollowShape.release(PARAMS, (gameTime - releaseStart) / TICKS_PER_SECOND);
            if (release <= 0) {
                reset();
                return null;
            }
            state = last;
            beats = lastBeats;
        }
        net.minecraft.world.phys.Vec3 p = player.getPosition(partialTick);
        Vec3 position = new Vec3(p.x, p.y, p.z);
        if (anchor == null || anchor.distanceSquared(position) > PARAMS.follow() * PARAMS.follow()) {
            anchor = position;
        }
        double closeness = ClientHollow.closeness(state);
        List<AwakeningField.Ring> rings = new ArrayList<>(beats.size());
        if (node != null) {
            RippleParams ring = HollowShape.nodeRing(PARAMS, closeness);
            ring = ring.withAmplitude(ring.amplitude() * release);
            for (long beat : beats) {
                double age = (gameTime - beat) / TICKS_PER_SECOND;
                if (Ripple.active(ring, age)) {
                    rings.add(new AwakeningField.Ring(node, age, ring));
                }
            }
        }
        return new HollowField(PARAMS, position, anchor,
                HollowShape.breathAmplitude(PARAMS, closeness) * release, gameTime / TICKS_PER_SECOND, rings);
    }

    /** Forgets everything, e.g. when the level changes. */
    public static void reset() {
        last = null;
        lastBeats = List.of();
        node = null;
        anchor = null;
        releaseStart = Double.NaN;
    }
}
