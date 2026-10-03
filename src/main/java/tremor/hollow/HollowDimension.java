package tremor.hollow;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import tremor.Tremor;

/**
 * The dimension {@code tremor:hollow} (SPEC 9), defined by the datapack files {@code data/tremor/dimension/hollow.json}
 * (an empty void: flat with no layers, the biome {@code the_void}, no features or structures) and
 * {@code data/tremor/dimension_type/hollow.json} (the overworld's build height, a time frozen at dusk with a little
 * ambient light, beds and respawn anchors not working, no raids, and the client effects {@code tremor:hollow}: no sky,
 * clouds or rain, {@code tremor.client.hollow.HollowSky}).
 */
public final class HollowDimension {
    public static final ResourceKey<Level> KEY =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(Tremor.MODID, "hollow"));

    private HollowDimension() {
    }

    /** Whether {@code level} is the hollow (on either side). */
    public static boolean is(Level level) {
        return level.dimension() == KEY;
    }

    /** The hollow, or null if the dimension is missing (its datapack files did not load). */
    public static ServerLevel level(MinecraftServer server) {
        return server.getLevel(KEY);
    }
}
