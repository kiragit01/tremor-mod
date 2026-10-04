package tremor.network;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class TremorNetwork {
    private static final String PROTOCOL = "7";

    private TremorNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL);
        // Handler bodies only run on the client, so client classes are never loaded on a dedicated server.
        registrar.playToClient(TremorStatePayload.TYPE, TremorStatePayload.STREAM_CODEC,
                (payload, context) -> tremor.client.ClientTremor.acceptState(payload));
        registrar.playToClient(TremorShapePayload.TYPE, TremorShapePayload.STREAM_CODEC,
                (payload, context) -> tremor.client.ClientTremor.acceptShape(payload));
        registrar.playToClient(TremorBlackoutPayload.TYPE, TremorBlackoutPayload.STREAM_CODEC,
                (payload, context) -> tremor.client.ClientBlackout.accept(payload));
        registrar.playToClient(TremorAwakeningPayload.TYPE, TremorAwakeningPayload.STREAM_CODEC,
                (payload, context) -> tremor.client.awakening.ClientAwakening.accept(payload));
        registrar.playToClient(TremorStepRipplePayload.TYPE, TremorStepRipplePayload.STREAM_CODEC,
                (payload, context) -> tremor.client.awakening.ClientAwakening.acceptRipple(payload));
        registrar.playToClient(TremorHollowStatePayload.TYPE, TremorHollowStatePayload.STREAM_CODEC,
                (payload, context) -> tremor.client.hollow.ClientHollow.accept(payload));
    }
}
