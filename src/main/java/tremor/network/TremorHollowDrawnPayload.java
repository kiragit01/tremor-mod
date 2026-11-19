package tremor.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * From the client: it has the hollow drawn around the player (the loading screen of the dimension change is gone, the
 * sections round the player are meshed), sent once per arrival. The level's clock starts only then (a player once came
 * in to a third of the time left), or after {@code HollowManager}'s wait if the word never comes.
 */
public record TremorHollowDrawnPayload() implements TremorPayload {
    public static final TremorHollowDrawnPayload INSTANCE = new TremorHollowDrawnPayload();

    @Override
    public void write(FriendlyByteBuf buf) {
    }

    static TremorHollowDrawnPayload read(FriendlyByteBuf buf) {
        return INSTANCE;
    }
}
