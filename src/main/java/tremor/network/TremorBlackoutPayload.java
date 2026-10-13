package tremor.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import tremor.Tremor;

/**
 * Screen blackout around a move into or out of the hollow (SPEC 9: the player is swallowed, the screen goes dark and
 * the player is moved; the dimension change and the copying of the terrain happen while it is dark).
 *
 * @param dark       true: fade to black and stay black (also over the vanilla loading screen of a dimension change);
 *                   false: fade back in
 * @param fadeTicks  length of the fade in client ticks (0 = at once)
 * @param white      the light of the way out over the edge of the hollow (SPEC 9 "Побег"): the screen fades to white
 *                   instead of black (with a rush of air), and back from white; only a fade out sets the colour, the
 *                   fade back keeps it
 */
public record TremorBlackoutPayload(boolean dark, int fadeTicks, boolean white) implements CustomPacketPayload {

    public static final Type<TremorBlackoutPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "blackout"));

    public static final StreamCodec<FriendlyByteBuf, TremorBlackoutPayload> STREAM_CODEC =
            StreamCodec.ofMember(TremorBlackoutPayload::write, TremorBlackoutPayload::read);

    public TremorBlackoutPayload {
        fadeTicks = Math.max(0, fadeTicks);
    }

    /** A black one. */
    public TremorBlackoutPayload(boolean dark, int fadeTicks) {
        this(dark, fadeTicks, false);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(dark);
        buf.writeVarInt(fadeTicks);
        buf.writeBoolean(white);
    }

    private static TremorBlackoutPayload read(FriendlyByteBuf buf) {
        return new TremorBlackoutPayload(buf.readBoolean(), buf.readVarInt(), buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
