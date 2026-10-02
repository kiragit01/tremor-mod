package tremor.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import tremor.Tremor;
import tremor.core.shape.BumpParams;

/**
 * Shape parameters of the entity's bump (SPEC 6.1), sent when they change ({@code /tremor set}), on spawn, and to
 * players entering the dimension. Kept out of {@link TremorStatePayload} to keep that one small.
 * The amplitude in here is the configured height; the state carries the current (animated) one.
 */
public record TremorShapePayload(int instance, BumpParams params) implements CustomPacketPayload {

    public static final Type<TremorShapePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "shape"));

    public static final StreamCodec<FriendlyByteBuf, TremorShapePayload> STREAM_CODEC =
            StreamCodec.ofMember(TremorShapePayload::write, TremorShapePayload::read);

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(instance);
        buf.writeFloat((float) params.amplitude());
        buf.writeFloat((float) params.sigmaFront());
        buf.writeFloat((float) params.sigmaBack());
        buf.writeFloat((float) params.sigmaSide());
        buf.writeFloat((float) params.trailLag());
        buf.writeFloat((float) params.trailSigma());
        buf.writeFloat((float) params.trailDepth());
        buf.writeFloat((float) params.jitter());
    }

    private static TremorShapePayload read(FriendlyByteBuf buf) {
        int instance = buf.readVarInt();
        return new TremorShapePayload(instance, new BumpParams(buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
