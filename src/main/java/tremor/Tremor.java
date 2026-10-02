package tremor;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import org.slf4j.Logger;
import tremor.command.TremorCommands;
import tremor.config.TremorConfig;
import tremor.debug.DebugParticles;
import tremor.entity.TremorManager;
import tremor.hearing.VibrationListener;
import tremor.network.TremorNetwork;
import tremor.sound.TremorSounds;
import tremor.spawn.NaturalSpawner;
import tremor.world.LevelVoxelView;

@Mod(Tremor.MODID)
public final class Tremor {
    public static final String MODID = "tremor";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Tremor(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, TremorConfig.COMMON_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, TremorConfig.CLIENT_SPEC);

        modBus.addListener(TremorNetwork::register);
        TremorSounds.register(modBus);

        IEventBus game = NeoForge.EVENT_BUS;
        game.addListener(TremorCommands::register);

        game.addListener(TremorManager::onLevelLoad);
        game.addListener(TremorManager::onLevelUnload);
        game.addListener(TremorManager::onLevelTick);
        // Lowest priority, cancelled ones included: what matters is the final state of the world.
        game.addListener(EventPriority.LOWEST, true, BlockEvent.NeighborNotifyEvent.class,
                TremorManager::onNeighborNotify);
        game.addListener(EventPriority.LOWEST, ExplosionEvent.Detonate.class, TremorManager::onExplosion);
        game.addListener(EventPriority.LOWEST, PistonEvent.Post.class, TremorManager::onPistonMoved);
        game.addListener(TremorManager::onChunkLoad);
        game.addListener(TremorManager::onPlayerLoggedIn);
        game.addListener(TremorManager::onPlayerChangedDimension);
        game.addListener(TremorManager::onPlayerRespawn);
        game.addListener(TremorManager::onServerStopping);
        // Hearing (SPEC 7). Lowest priority: a game event or fall another mod cancels is not heard.
        game.addListener(EventPriority.LOWEST, VanillaGameEvent.class, VibrationListener::onGameEvent);
        game.addListener(EventPriority.LOWEST, LivingFallEvent.class, VibrationListener::onLivingFall);
        game.addListener(LevelVoxelView::onTagsUpdated);
        // Natural spawn (SPEC 11).
        game.addListener(NaturalSpawner::onLevelTick);
        game.addListener(NaturalSpawner::onLevelUnload);
        // After TremorManager's tick listener: draws the state of this tick.
        game.addListener(DebugParticles::onLevelTick);
        game.addListener(DebugParticles::onServerStopping);
    }
}
