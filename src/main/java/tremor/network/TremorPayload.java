package tremor.network;

import net.minecraft.network.FriendlyByteBuf;

/** A clientbound message of {@link TremorNetwork}; each one also has a static {@code read(FriendlyByteBuf)}. */
public interface TremorPayload {
    void write(FriendlyByteBuf buf);
}
