package tremor.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import tremor.Tremor;
import tremor.core.math.Vec3;

/**
 * A ring wave on the ground from a step inside an Awakening zone (SPEC 9: "каждый шаг игрока порождает кольцевую
 * волну, расходящуюся по земле"), sent to the players near it.
 *
 * @param event    id of the Awakening ({@link TremorAwakeningPayload#id})
 * @param position where the vibration entered the ground
 * @param gameTime level game time of the step
 * @param strength relative strength, 1 for a walking step (louder steps make stronger rings)
 */
public record TremorStepRipplePayload(int event, Vec3 position, long gameTime, float strength)
        implements TremorPayload {

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(event);
        buf.writeDouble(position.x());
        buf.writeDouble(position.y());
        buf.writeDouble(position.z());
        buf.writeVarLong(gameTime);
        buf.writeFloat(strength);
    }

    static TremorStepRipplePayload read(FriendlyByteBuf buf) {
        return new TremorStepRipplePayload(buf.readVarInt(),
                new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble()), buf.readVarLong(), buf.readFloat());
    }
}
