package tremor.client.hollow;

import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.ViewportEvent;
import tremor.config.TremorConfig;
import tremor.hollow.HollowDimension;
import tremor.network.TremorHollowStatePayload;

/**
 * The black fog of the hollow (SPEC 9 phase 2: "в изнанке чёрный туман — видно ~5 блоков ... Небо не видно"): while
 * the local player is in the dimension {@code tremor:hollow} (from the dark move in on, so that the fog is there when
 * the screen clears, until the move out), everything further than {@link FogCurve#far} is black, the terrain, the
 * entities and the moving ground alike, and the sky and the clouds are not drawn at all ({@link HollowSky}).
 * Spectators see through it.
 * <p>
 * Both through NeoForge's fog events, at the lowest priority, after other mods: {@link #onRenderFog} sets where the fog
 * starts and ends for the terrain and the sky (a spherical fog: a ceiling or a pit fogs as a wall does), keeping a
 * thicker fog that is already there (lava, powder snow, blindness), though not the water's start behind the eye,
 * which would make it thicker than either ({@link FogCurve#near(double, double)}: under water it is as in the air);
 * {@link #onComputeFogColor} makes it black, after vanilla has brightened it for night vision, so night vision does
 * not lift it (it still lightens what is within the fog, as it lightens a dark cave). NeoForge writes both into the
 * shader fog of {@code RenderSystem} (fog start, end and shape once the fog event is cancelled; the colour through
 * {@code FogRenderer.levelFogColor}), which every vanilla shader reads, and so does Sodium's terrain shader
 * ({@code ChunkShaderFogComponent} reads {@code RenderSystem.getShaderFogStart/End/Shape/Color} every frame); Sodium's
 * fog occlusion then skips the terrain beyond the fog, which is black anyway. Lava and powder snow keep their own
 * colour. Render thread only.
 */
public final class HollowFog {
    private HollowFog() {
    }

    /** Sets the fog of the hollow (or keeps a thicker one) for the terrain and the sky. */
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        Minecraft mc = Minecraft.getInstance();
        if (!inFog(mc)) {
            return;
        }
        float far = (float) Math.min(far(), event.getFarPlaneDistance());
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance((float) FogCurve.near(far, event.getNearPlaneDistance()));
        event.setFogShape(FogShape.SPHERE);
        event.setCanceled(true);
    }

    /** Makes the fog (and the background where the sky would be) black, except in lava and powder snow. */
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        Minecraft mc = Minecraft.getInstance();
        if (!inFog(mc)) {
            return;
        }
        FogType fluid = event.getCamera().getFluidInCamera();
        if (fluid == FogType.LAVA || fluid == FogType.POWDER_SNOW) {
            return;
        }
        event.setRed(0);
        event.setGreen(0);
        event.setBlue(0);
    }

    /** Whether the local player is in the hollow's fog: in its dimension, and not a spectator. */
    static boolean inFog(Minecraft mc) {
        LocalPlayer player = mc.player;
        return mc.level != null && player != null && mc.level.dimension() == HollowDimension.KEY
                && !player.isSpectator();
    }

    /**
     * Where the fog ends now: the configured distance, closing in a little as the hollow the client knows of closes
     * (as it is before it closes while the client does not know of it yet, moving in).
     */
    static double far() {
        TremorHollowStatePayload state = ClientHollow.state();
        double closeness = state == null ? 0 : ClientHollow.closeness(state);
        return FogCurve.far(TremorConfig.CLIENT.hollowFogDistance.get(), closeness);
    }

    /** How far around the player the moving ground of the hollow is drawn for the configured fog. */
    static double reach() {
        return FogCurve.reach(TremorConfig.CLIENT.hollowFogDistance.get());
    }
}
