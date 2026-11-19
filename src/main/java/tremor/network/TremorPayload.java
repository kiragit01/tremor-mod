package tremor.network;

import net.minecraft.network.FriendlyByteBuf;

/** A message of {@link TremorNetwork} (all but one go to the client); each also has a static {@code read(FriendlyByteBuf)}. */
public interface TremorPayload {
    void write(FriendlyByteBuf buf);
}
