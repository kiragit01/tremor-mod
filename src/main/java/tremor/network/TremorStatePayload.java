package tremor.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import tremor.Tremor;
import tremor.core.behavior.Stage;
import tremor.core.math.Vec3;

/**
 * Compact state of the entity in the receiver's dimension (SPEC 6.3), sent several times a second to nearby players;
 * the client interpolates between these snapshots. {@code present = false} means there is no entity (then only
 * {@code instance} and {@code gameTime} are meaningful). About 80 bytes on the wire.
 *
 * @param instance  changes whenever the entity is (re)spawned, so the client can drop stale snapshots
 * @param gameTime  level game time the snapshot was taken at
 * @param forward   unit direction of travel (kept while standing)
 * @param phase     animation clock in seconds
 * @param stage     aggression stage (SPEC 8)
 * @param rippleAge game ticks since the latest ground ripple started (SPEC 8, ALERT), at the time of the snapshot;
 *                  {@link #NO_RIPPLE} if there is none (or it is older than that). Sent as an unsigned short.
 */
public record TremorStatePayload(int instance, boolean present, long gameTime, Vec3 position, Vec3 normal,
                                 Vec3 forward, Vec3 velocity, float amplitude, float phase, Stage stage,
                                 int rippleAge)
        implements TremorPayload {

    /** {@link #rippleAge} when there is no ripple; also the largest age that is sent. */
    public static final int NO_RIPPLE = 0xFFFF;

    public static TremorStatePayload absent(int instance, long gameTime) {
        return new TremorStatePayload(instance, false, gameTime, Vec3.ZERO, Vec3.UNIT_Y, Vec3.UNIT_X, Vec3.ZERO, 0, 0,
                Stage.DORMANT, NO_RIPPLE);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(instance);
        buf.writeBoolean(present);
        buf.writeVarLong(gameTime);
        if (!present) {
            return;
        }
        buf.writeDouble(position.x());
        buf.writeDouble(position.y());
        buf.writeDouble(position.z());
        writeVec(buf, normal);
        writeVec(buf, forward);
        writeVec(buf, velocity);
        buf.writeFloat(amplitude);
        buf.writeFloat(phase);
        buf.writeEnum(stage);
        buf.writeShort(Math.max(0, Math.min(NO_RIPPLE, rippleAge)));
    }

    static TremorStatePayload read(FriendlyByteBuf buf) {
        int instance = buf.readVarInt();
        boolean present = buf.readBoolean();
        long gameTime = buf.readVarLong();
        if (!present) {
            return absent(instance, gameTime);
        }
        Vec3 position = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        Vec3 normal = readVec(buf);
        Vec3 forward = readVec(buf);
        Vec3 velocity = readVec(buf);
        float amplitude = buf.readFloat();
        float phase = buf.readFloat();
        Stage stage = buf.readEnum(Stage.class);
        int rippleAge = buf.readUnsignedShort();
        return new TremorStatePayload(instance, true, gameTime, position, normal, forward, velocity, amplitude, phase,
                stage, rippleAge);
    }

    static void writeVec(FriendlyByteBuf buf, Vec3 v) {
        buf.writeFloat((float) v.x());
        buf.writeFloat((float) v.y());
        buf.writeFloat((float) v.z());
    }

    static Vec3 readVec(FriendlyByteBuf buf) {
        return new Vec3(buf.readFloat(), buf.readFloat(), buf.readFloat());
    }
}
