package tremor;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.PistonEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import tremor.awakening.AwakeningManager;
import tremor.awakening.EdgeExits;
import tremor.awakening.Outcomes;
import tremor.awakening.Sinkholes;
import tremor.block.TremorBlocks;
import tremor.command.TremorCommands;
import tremor.config.TremorConfig;
import tremor.debug.DebugParticles;
import tremor.entity.TremorManager;
import tremor.hearing.VibrationListener;
import tremor.hollow.HollowManager;
import tremor.hollow.HollowRules;
import tremor.hollow.level.HollowLevels;
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
        TremorBlocks.register(modBus);

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
        // The hollow (SPEC 9, 12): its events, and the rules inside it.
        game.addListener(HollowManager::onServerTick);
        game.addListener(HollowManager::onPlayerLoggedOut);
        game.addListener(HollowManager::onServerStopped);
        // The level inside the hollow (SPEC 9, stage 4c); HollowManager prepares and ticks it.
        game.addListener(HollowLevels::onServerStopped);
        game.addListener(HollowRules::onRightClickBlock);
        game.addListener(HollowRules::onRightClickItem);
        game.addListener(HollowRules::onToolModification);
        game.addListener(HollowRules::onItemFished);
        game.addListener(HollowRules::onBlockPlace);
        // Lowest priority: a placing is the player's once everyone else let it be.
        game.addListener(EventPriority.LOWEST, BlockEvent.EntityPlaceEvent.class, HollowRules::onBlockPlaced);
        // Lowest priority, cancelled ones included: the block changed whatever the listeners did.
        game.addListener(EventPriority.LOWEST, true, BlockEvent.NeighborNotifyEvent.class,
                HollowRules::onNeighborNotify);
        game.addListener(HollowRules::onBlockDrops);
        game.addListener(HollowRules::onPistonMove);
        // Lowest priority: takes the blocks away from the explosion after everyone else saw them.
        game.addListener(EventPriority.LOWEST, ExplosionEvent.Detonate.class, HollowRules::onExplosion);
        game.addListener(HollowRules::onServerTick);
        game.addListener(HollowRules::onServerStopped);
        game.addListener(HollowRules::onPotentialSpawns);
        game.addListener(HollowRules::onEntityJoinLevel);
        game.addListener(HollowRules::onEntityLeaveLevel);
        game.addListener(HollowRules::onTravelToDimension);
        game.addListener(HollowRules::onEnderPearl);
        game.addListener(HollowRules::onLivingDrops);
        game.addListener(HollowRules::onExperienceDrop);
        // High priority: before HollowManager's listener ends the event (the place to drop at is still known).
        game.addListener(EventPriority.HIGH, PlayerEvent.PlayerLoggedOutEvent.class, HollowRules::onPlayerLoggedOut);
        // The Awakening (SPEC 9, stage 4b). Its tick comes after TremorManager's: it sees the stage of this tick.
        game.addListener(AwakeningManager::onLevelTick);
        game.addListener(AwakeningManager::onLevelUnload);
        game.addListener(AwakeningManager::onPlayerLoggedOut);
        game.addListener(AwakeningManager::onPlayerChangedDimension);
        game.addListener(AwakeningManager::onPlayerRespawn);
        game.addListener(AwakeningManager::onPlayerLoggedIn);
        // Lowest priority: a death another listener cancelled is none.
        game.addListener(EventPriority.LOWEST, LivingDeathEvent.class, AwakeningManager::onLivingDeath);
        // Highest priority: the fall of a rooted target does not happen, for anybody else either.
        game.addListener(EventPriority.HIGHEST, LivingFallEvent.class, AwakeningManager::onLivingFall);
        // High priority: the entities go deep before TremorManager drops its runtimes.
        game.addListener(EventPriority.HIGH, ServerStoppingEvent.class, AwakeningManager::onServerStopping);
        // The sinkholes of defeats (SPEC 9 "Поражение"), dug over a few ticks.
        game.addListener(Sinkholes::onServerTick);
        game.addListener(Sinkholes::onServerStopped);
        // Where escapes through the edge come out (SPEC 9 "Побег"), once the real chunks there are loaded.
        game.addListener(EdgeExits::onServerTick);
        game.addListener(EdgeExits::onServerStopped);
        // A player who survived a defeat is weakened once out of the hollow.
        HollowManager.addEndListener(Outcomes::onHollowEnded);
        HollowManager.addEndListener(AwakeningManager::onHollowEnded);
        HollowManager.addEndListener(HollowLevels::onHollowEnded);
    }
}
