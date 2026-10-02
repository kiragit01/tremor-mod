package tremor.hollow;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A place in the real world a player is moved to or from: where the player was swallowed (SPEC 9), or where the player
 * comes back out.
 *
 * @param yRot yaw, degrees
 * @param xRot pitch, degrees
 */
public record Origin(ResourceKey<Level> dimension, Vec3 position, float yRot, float xRot) {

    /** Where {@code player} is now, looking the way the player looks. */
    public static Origin of(ServerPlayer player) {
        return new Origin(player.level().dimension(), player.position(), player.getYRot(), player.getXRot());
    }

    public BlockPos blockPos() {
        return BlockPos.containing(position);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", dimension.location().toString());
        tag.putDouble("x", position.x);
        tag.putDouble("y", position.y);
        tag.putDouble("z", position.z);
        tag.putFloat("yRot", yRot);
        tag.putFloat("xRot", xRot);
        return tag;
    }

    /** @throws net.minecraft.ResourceLocationException if the dimension id is malformed */
    static Origin load(CompoundTag tag) {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(tag.getString("dimension")));
        return new Origin(dimension, new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z")),
                tag.getFloat("yRot"), tag.getFloat("xRot"));
    }
}
