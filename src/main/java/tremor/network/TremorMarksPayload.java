package tremor.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import tremor.Tremor;

/**
 * A hushing charm was used: the player's marks before and after, for the counter rolled down over the hotbar
 * ({@code tremor.client.MarksCounter}); the same two when there was no mark to take.
 */
public record TremorMarksPayload(int before, int after) implements CustomPacketPayload {

    public static final Type<TremorMarksPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "marks"));

    public static final StreamCodec<FriendlyByteBuf, TremorMarksPayload> STREAM_CODEC =
            StreamCodec.ofMember(TremorMarksPayload::write, TremorMarksPayload::read);

    public TremorMarksPayload {
        before = Math.max(0, before);
        after = Math.max(0, after);
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(before);
        buf.writeVarInt(after);
    }

    private static TremorMarksPayload read(FriendlyByteBuf buf) {
        return new TremorMarksPayload(buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
