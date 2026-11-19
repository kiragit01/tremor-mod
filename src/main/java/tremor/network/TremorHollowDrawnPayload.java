package tremor.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import tremor.Tremor;

/**
 * From the client: it has the hollow drawn around the player (the loading screen of the dimension change is gone, the
 * sections round the player are meshed), sent once per arrival. The level's clock starts only then (a player once came
 * in to a third of the time left), or after {@code HollowManager}'s wait if the word never comes.
 */
public record TremorHollowDrawnPayload() implements CustomPacketPayload {
    public static final TremorHollowDrawnPayload INSTANCE = new TremorHollowDrawnPayload();

    public static final Type<TremorHollowDrawnPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "hollow_drawn"));

    public static final StreamCodec<ByteBuf, TremorHollowDrawnPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
