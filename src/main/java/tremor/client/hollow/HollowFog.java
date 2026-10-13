package tremor.client.hollow;

import com.mojang.blaze3d.shaders.FogShape;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.material.FogType;
import net.minecraftforge.client.event.ViewportEvent;
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
 * <p>
 * Near the end of the escape tunnel the light of the way out shows through it ({@link TremorHollowStatePayload#exit}):
 * the fog draws back to {@value #EXIT_FAR} blocks, starts further out and turns into a pale haze, more so the nearer
 * the light, so the player sees light ahead down the tunnel and runs into it (the screen then turns white,
 * {@code tremor.client.ClientBlackout}).
 */
public final class HollowFog {
    /** Where the fog ends at the light of the way out (blocks)... */
    private static final double EXIT_FAR = 16;
    /** ...and where it starts, as a share of that: the tunnel close by stays clear, the haze is ahead. */
    private static final double EXIT_NEAR_SHARE = 0.4;
    /** The colour of the haze at the light of the way out: daylight, a little warm. */
    private static final float EXIT_RED = 0.88f;
    private static final float EXIT_GREEN = 0.87f;
    private static final float EXIT_BLUE = 0.82f;
    /** How fast the fog follows the light (the state comes a few times a second): time constant, seconds. */
    private static final double EXIT_EASE_SECONDS = 0.3;
    /** The light of the way out as the fog shows it now, 0..1, eased. */
    private static double exit;
    private static long exitNanos;

    private HollowFog() {
    }

    /** The light of the way out as the fog shows it this frame: the state's, followed smoothly. */
    private static double exit() {
        TremorHollowStatePayload state = ClientHollow.state();
        double target = state == null ? 0 : state.exit();
        long now = System.nanoTime();
        double seconds = exitNanos == 0 ? 1 : Math.min(1, (now - exitNanos) / 1e9);
        exitNanos = now;
        exit += (target - exit) * (1 - Math.exp(-seconds / EXIT_EASE_SECONDS));
        return exit;
    }

    /** Sets the fog of the hollow (or keeps a thicker one) for the terrain and the sky. */
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        Minecraft mc = Minecraft.getInstance();
        if (!inFog(mc)) {
            return;
        }
        double light = exit();
        float far = (float) Math.min(far() + (EXIT_FAR - far()) * light, event.getFarPlaneDistance());
        double near = FogCurve.near(far, event.getNearPlaneDistance());
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance((float) (near + (far * EXIT_NEAR_SHARE - near) * light));
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
        float haze = (float) Math.pow(exit(), 1.5);
        event.setRed(EXIT_RED * haze);
        event.setGreen(EXIT_GREEN * haze);
        event.setBlue(EXIT_BLUE * haze);
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
