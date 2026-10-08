package tremor.client.hollow;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RegisterDimensionSpecialEffectsEvent;
import org.joml.Matrix4f;
import tremor.Tremor;

/**
 * What the hollow shows above and around the copy (SPEC 9 phase 2: "Небо не видно"): nothing. The dimension type
 * {@code tremor:hollow} names these effects ({@code "effects": "tremor:hollow"} in
 * {@code data/tremor/dimension_type/hollow.json}): no sky, no sun or moon, no glow of the dusk it is frozen in, no
 * stars, no clouds, no rain or snow; behind the black fog ({@link HollowFog}) there is only black. Clouds are off by
 * their height ({@link Float#NaN}), which Sodium's own cloud renderer honours as well (it replaces vanilla's and skips
 * NeoForge's cloud hook); the sky by its type, which vanilla and Sodium both leave undrawn. The light of the copy is not
 * touched: it keeps the light of the dimension type.
 */
public final class HollowSky extends DimensionSpecialEffects {
    /** The effects' id, as the dimension type names them. */
    public static final ResourceLocation ID = new ResourceLocation(Tremor.MODID, "hollow");

    private HollowSky() {
        super(Float.NaN, true, SkyType.NONE, false, false);
    }

    /** Registers the effects under {@link #ID} (mod bus). */
    public static void onRegisterDimensionEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(ID, new HollowSky());
    }

    /** Black, whatever the biome's fog and the time of day (the fog events make it black in the end anyway). */
    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
        return Vec3.ZERO;
    }

    @Override
    public boolean isFoggyAt(int x, int y) {
        return false;
    }

    /** No glow of the dusk on the horizon. */
    @Override
    public float[] getSunriseColor(float timeOfDay, float partialTicks) {
        return null;
    }

    /** Draws no sky at all. */
    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, PoseStack poseStack, Camera camera,
                             Matrix4f projectionMatrix, boolean isFoggy, Runnable setupFog) {
        return true;
    }

    /** Draws no clouds (their height is NaN as well). */
    @Override
    public boolean renderClouds(ClientLevel level, int ticks, float partialTick, PoseStack poseStack, double camX,
                                double camY, double camZ, Matrix4f projectionMatrix) {
        return true;
    }

    /** No rain or snow falls in the hollow. */
    @Override
    public boolean renderSnowAndRain(ClientLevel level, int ticks, float partialTick, LightTexture lightTexture,
                                     double camX, double camY, double camZ) {
        return true;
    }

    /** No splashes or sounds of rain either. */
    @Override
    public boolean tickRain(ClientLevel level, int ticks, Camera camera) {
        return true;
    }
}
