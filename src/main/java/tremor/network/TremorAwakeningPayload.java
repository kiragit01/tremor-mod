package tremor.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import tremor.Tremor;
import tremor.core.math.Vec3;

import java.util.UUID;

/**
 * State of an Awakening (SPEC 9) for the players near it: the zone, the phase and its timing, and whom it is about.
 * Sent when the event starts, on every phase change, and to players entering its dimension; {@code phase == ENDED}
 * tells the clients to drop it.
 *
 * @param id          event id (a new one for every Awakening)
 * @param center      centre of the zone (the target's position when the event started)
 * @param radius      zone radius in blocks
 * @param phase       current phase
 * @param phaseStart  level game time the phase started at (SETTLING: the game time the hill starts to settle, which
 *                    may lie a little ahead: it stands until then)
 * @param phaseTicks  planned length of the phase in ticks (0 if open-ended; EMERGING: the length of the rise, after
 *                    which the hill stands until SETTLING)
 * @param target      the player the event is about (who is swallowed)
 * @param focus       where the swallow hill rises (the target's position when SWALLOWING started); the centre otherwise
 */
public record TremorAwakeningPayload(int id, Vec3 center, float radius, Phase phase, long phaseStart, int phaseTicks,
                                     UUID target, Vec3 focus) implements CustomPacketPayload {

    /** Phases of an Awakening as the clients see them. */
    public enum Phase {
        /** SPEC 9 phase 1 in the real world: silence, breathing, ripples from steps; the target can still escape. */
        BUILDUP,
        /** The hill rises under the target and swallows them (the screen goes dark at the end). */
        SWALLOWING,
        /** The target is in the hollow; for everyone else the ground is smooth as if nothing happened. */
        HOLLOW,
        /**
         * After a victory (SPEC 9): at the focus (the swallow point) a hill rises over {@code phaseTicks}, as high as
         * at the end of the swallowing, and stands; the player is put into it while it stands. Lasts until SETTLING.
         */
        EMERGING,
        /**
         * The hill of EMERGING stands until {@code phaseStart} (the player's screen comes back meanwhile), then settles
         * over {@code phaseTicks} and lets the player out.
         */
        SETTLING,
        /** Over (escape, victory, defeat or cancelled): drop it. */
        ENDED
    }

    public static final Type<TremorAwakeningPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "awakening"));

    public static final StreamCodec<FriendlyByteBuf, TremorAwakeningPayload> STREAM_CODEC =
            StreamCodec.ofMember(TremorAwakeningPayload::write, TremorAwakeningPayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(id);
        buf.writeDouble(center.x());
        buf.writeDouble(center.y());
        buf.writeDouble(center.z());
        buf.writeFloat(radius);
        buf.writeEnum(phase);
        buf.writeVarLong(phaseStart);
        buf.writeVarInt(phaseTicks);
        buf.writeUUID(target);
        buf.writeDouble(focus.x());
        buf.writeDouble(focus.y());
        buf.writeDouble(focus.z());
    }

    private static TremorAwakeningPayload read(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        Vec3 center = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        float radius = buf.readFloat();
        Phase phase = buf.readEnum(Phase.class);
        long phaseStart = buf.readVarLong();
        int phaseTicks = buf.readVarInt();
        UUID target = buf.readUUID();
        Vec3 focus = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        return new TremorAwakeningPayload(id, center, radius, phase, phaseStart, phaseTicks, target, focus);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
