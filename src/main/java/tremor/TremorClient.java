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

        // Dev-only scripted run (gradlew runClientAutotest); inert unless -Dtremor.autotest is set.
        if (AutoTest.isEnabled()) {
            AutoTest.init(modBus);
        }
    }
}
