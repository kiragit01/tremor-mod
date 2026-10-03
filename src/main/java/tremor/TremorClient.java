package tremor;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import tremor.client.ClientBlackout;
import tremor.client.ClientTremor;
import tremor.client.dev.AutoTest;
import tremor.client.render.DeformationRenderer;
import tremor.client.sound.RustleSound;

@Mod(value = Tremor.MODID, dist = Dist.CLIENT)
public final class TremorClient {
    public TremorClient(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        modBus.addListener(DeformationRenderer::onModelBakingCompleted);
        modBus.addListener(tremor.client.hollow.SinkOverlay::onRegisterGuiLayers);
        modBus.addListener(tremor.client.hollow.HollowSky::onRegisterDimensionEffects);

        IEventBus game = NeoForge.EVENT_BUS;
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
        game.addListener(EventPriority.LOWEST, true, net.neoforged.neoforge.client.event.ViewportEvent.RenderFog.class,
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
        game.addListener(EventPriority.NORMAL, true, net.neoforged.neoforge.client.event.SelectMusicEvent.class,
                tremor.client.sound.WorldSilence::onSelectMusic);
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
