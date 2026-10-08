package tremor.network;

import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import tremor.Tremor;

public final class TremorNetwork {
    private static final String PROTOCOL = "7";

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Tremor.MODID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private TremorNetwork() {
    }

    public static void register() {
        int id = 0;
        // Handler bodies only run on the client, so client classes are never loaded on a dedicated server.
        clientbound(id++, TremorStatePayload.class, TremorStatePayload::read,
                payload -> tremor.client.ClientTremor.acceptState(payload));
        clientbound(id++, TremorShapePayload.class, TremorShapePayload::read,
                payload -> tremor.client.ClientTremor.acceptShape(payload));
        clientbound(id++, TremorBlackoutPayload.class, TremorBlackoutPayload::read,
                payload -> tremor.client.ClientBlackout.accept(payload));
        clientbound(id++, TremorAwakeningPayload.class, TremorAwakeningPayload::read,
                payload -> tremor.client.awakening.ClientAwakening.accept(payload));
        clientbound(id++, TremorStepRipplePayload.class, TremorStepRipplePayload::read,
                payload -> tremor.client.awakening.ClientAwakening.acceptRipple(payload));
        clientbound(id, TremorHollowStatePayload.class, TremorHollowStatePayload::read,
                payload -> tremor.client.hollow.ClientHollow.accept(payload));
    }

    public static void sendToPlayer(ServerPlayer player, TremorPayload payload) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    public static void sendToPlayersInDimension(ServerLevel level, TremorPayload payload) {
        CHANNEL.send(PacketDistributor.DIMENSION.with(level::dimension), payload);
    }

    private static <T extends TremorPayload> void clientbound(int id, Class<T> type, Function<FriendlyByteBuf, T> read,
                                                              Consumer<T> handler) {
        CHANNEL.messageBuilder(type, id, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(TremorPayload::write)
                .decoder(read)
                .consumerMainThread((payload, context) -> handler.accept(payload))
                .add();
    }
}
