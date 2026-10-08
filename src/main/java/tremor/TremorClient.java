package tremor;

import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.common.MinecraftForge;
import tremor.client.ClientBlackout;
import tremor.client.ClientTremor;
import tremor.client.dev.AutoTest;
import tremor.client.render.DeformationRenderer;
import tremor.client.sound.RustleSound;

/** The client side of the mod; {@link Tremor} calls {@link #init} on a physical client only. */
public final class TremorClient {
    private TremorClient() {
    }

    static void init(IEventBus modBus) {
        modBus.addListener(DeformationRenderer::onModelBakingCompleted);
        modBus.addListener(tremor.client.SeismographNeedle::onClientSetup);
        modBus.addListener(tremor.client.hollow.SinkOverlay::onRegisterGuiOverlays);
        modBus.addListener(tremor.client.hollow.HollowTimeBar::onRegisterGuiOverlays);
        modBus.addListener(tremor.client.hollow.HollowSky::onRegisterDimensionEffects);

        IEventBus game = MinecraftForge.EVENT_BUS;
        game.addListener(DeformationRenderer::onRenderLevelStage);
        game.addListener(ClientTremor::onLoggingOut);
        game.addListener(DeformationRenderer::onLoggingOut);
        game.addListener(RustleSound::onClientTick);
        game.addListener(RustleSound::onLoggingOut);
        game.addListener(ClientBlackout::onRenderFrame);
        // Lowest priority: the black goes above everything else drawn on the HUD.
        game.addListener(EventPriority.LOWEST, ClientBlackout::onRenderGui);
        game.addListener(EventPriority.LOWEST, true, RenderGuiEvent.Pre.class, ClientBlackout::onRenderGuiCancelled);
        game.addListener(ClientBlackout::onScreenRender);
        game.addListener(ClientBlackout::onRespawn);
        game.addListener(ClientBlackout::onLoggingOut);
        game.addListener(tremor.client.awakening.ClientAwakening::onLoggingOut);
        game.addListener(tremor.client.hollow.ClientHollow::onLoggingOut);
        // Lowest priority: the root's part is taken out of the view after other mods changed it.
        game.addListener(EventPriority.LOWEST, tremor.client.awakening.ClientRoot::onComputeFovModifier);
        // A rooted player's client holds it too: no movement keys, no sprint, no sideways momentum.
        game.addListener(tremor.client.awakening.ClientRoot::onMovementInput);
        game.addListener(tremor.client.awakening.ClientRoot::onPlayerTickPre);
        // The node's pulse and the pull of the ground first: the sounds of the hollow follow them in the same tick.
        game.addListener(tremor.client.hollow.HollowPulse::onClientTick);
        game.addListener(tremor.client.hollow.HollowSink::onClientTick);
        // After the pulse: the wake of its rings counts a ring passing under the player for the sounds that follow.
        game.addListener(tremor.client.hollow.HollowWake::onClientTick);
        // The black fog of the hollow, at the lowest priority (also when cancelled): over what other mods set.
        game.addListener(EventPriority.LOWEST, true, net.minecraftforge.client.event.ViewportEvent.RenderFog.class,
                tremor.client.hollow.HollowFog::onRenderFog);
        game.addListener(EventPriority.LOWEST, tremor.client.hollow.HollowFog::onComputeFogColor);
        // No running in the hollow: the sprint key is let go before the player's tick, a sprint stopped after it
        // (and a sprint's push taken off a jump).
        game.addListener(tremor.client.hollow.HollowStride::onPlayerTickPre);
        game.addListener(tremor.client.hollow.HollowStride::onPlayerTickPost);
        game.addListener(tremor.client.hollow.HollowStride::onJump);
        // The emerging hill's clock first too: its rumble follows it in the same tick.
        game.addListener(tremor.client.awakening.ClientEmerge::onClientTick);
        game.addListener(tremor.client.sound.AwakeningSounds::onClientTick);
        game.addListener(tremor.client.sound.AwakeningSounds::onLoggingOut);
        game.addListener(tremor.client.sound.WorldSilence::onClientTick);
        // Lowest priority: the silence wraps the sound other mods settled on.
        game.addListener(EventPriority.LOWEST, tremor.client.sound.WorldSilence::onPlaySound);
        game.addListener(tremor.client.sound.WorldSilence::onLoggingOut);
        game.addListener(tremor.client.sound.WorldSilence::onLevelUnload);
        // Fired on the sound engine's thread.
        game.addListener(tremor.client.sound.WorldSilence::onSoundStarted);
        game.addListener(tremor.client.sound.WorldSilence::onStreamStarted);

        // Dev-only scripted run (gradlew runClientAutotest); inert unless -Dtremor.autotest is set.
        if (AutoTest.isEnabled()) {
            AutoTest.init(modBus);
        }
    }
}
