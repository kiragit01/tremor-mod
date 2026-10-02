package tremor.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import tremor.Tremor;
import tremor.core.math.Vec3;

/**
 * State of the hollow (SPEC 9, phase 2) for the player inside it: the copy, how far it has closed, the node and the
 * heartbeat, and how deep the ground has pulled the player in. Sent to that player a few times a second while they
 * are inside, and with {@code active = false} when they leave.
 *
 * @param event       id of the hollow event (changes for every swallow)
 * @param active      false: the player is no longer in this hollow (drop the state)
 * @param center      centre of the copy in the hollow's coordinates (where the player was swallowed)
 * @param boxRadius   horizontal radius of the copy: its edge, which is the way out (SPEC 9 "Побег")
 * @param closeRadius horizontal radius the copy has closed down to so far (SPEC 9 "Смыкание"), {@code <= boxRadius}
 * @param node        position of the node ({@code tremor:heart_node}), or null while there is none
 * @param beatTicks   game ticks between two beats of the node (its pulse quickens as the hollow closes)
 * @param sink        how deep the soft ground has pulled the player in, 0 (free) .. 1 (fully pulled in)
 * @param gameTime    level game time of this state
 */
public record TremorHollowStatePayload(int event, boolean active, Vec3 center, float boxRadius, float closeRadius,
                                       BlockPos node, int beatTicks, float sink, long gameTime)
        implements CustomPacketPayload {

    public static final Type<TremorHollowStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "hollow_state"));

    public static final StreamCodec<FriendlyByteBuf, TremorHollowStatePayload> STREAM_CODEC =
            StreamCodec.ofMember(TremorHollowStatePayload::write, TremorHollowStatePayload::read);

    /** The player has left the hollow of {@code event}. */
    public static TremorHollowStatePayload inactive(int event, long gameTime) {
        return new TremorHollowStatePayload(event, false, Vec3.ZERO, 0, 0, null, 0, 0, gameTime);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(event);
        buf.writeBoolean(active);
        buf.writeVarLong(gameTime);
        if (!active) {
            return;
        }
        buf.writeDouble(center.x());
        buf.writeDouble(center.y());
        buf.writeDouble(center.z());
        buf.writeFloat(boxRadius);
        buf.writeFloat(closeRadius);
        buf.writeBoolean(node != null);
        if (node != null) {
            buf.writeBlockPos(node);
        }
        buf.writeVarInt(beatTicks);
        buf.writeFloat(sink);
    }

    private static TremorHollowStatePayload read(FriendlyByteBuf buf) {
        int event = buf.readVarInt();
        boolean active = buf.readBoolean();
        long gameTime = buf.readVarLong();
        if (!active) {
            return inactive(event, gameTime);
        }
        Vec3 center = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        float boxRadius = buf.readFloat();
        float closeRadius = buf.readFloat();
        BlockPos node = buf.readBoolean() ? buf.readBlockPos() : null;
        int beatTicks = buf.readVarInt();
        float sink = buf.readFloat();
        return new TremorHollowStatePayload(event, true, center, boxRadius, closeRadius, node, beatTicks, sink,
                gameTime);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
