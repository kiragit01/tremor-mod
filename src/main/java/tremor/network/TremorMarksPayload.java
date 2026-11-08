package tremor.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * A hushing charm was used: the player's marks before and after, for the counter rolled down under the middle of the
 * screen ({@code tremor.client.MarksCounter}); the same two when there was no mark to take.
 */
public record TremorMarksPayload(int before, int after) implements TremorPayload {

    public TremorMarksPayload {
        before = Math.max(0, before);
        after = Math.max(0, after);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(before);
        buf.writeVarInt(after);
    }

    static TremorMarksPayload read(FriendlyByteBuf buf) {
        return new TremorMarksPayload(buf.readVarInt(), buf.readVarInt());
    }
}
