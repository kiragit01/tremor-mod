package tremor;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.VanillaGameEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.PistonEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;
import tremor.awakening.AwakeningManager;
import tremor.awakening.CraterCaches;
import tremor.awakening.Craters;
import tremor.awakening.EdgeExits;
import tremor.awakening.Outcomes;
import tremor.block.TremorBlocks;
import tremor.command.TremorCommands;
import tremor.config.TremorConfig;
import tremor.debug.DebugParticles;
import tremor.entity.TremorManager;
import tremor.hearing.VibrationListener;
import tremor.hollow.HollowManager;
import tremor.hollow.HollowRules;
import tremor.hollow.SprintLock;
import tremor.hollow.level.HollowLevels;
import tremor.network.TremorNetwork;
import tremor.sound.TremorSounds;
import tremor.spawn.NaturalSpawner;
import tremor.world.LevelVoxelView;

@Mod(Tremor.MODID)
public final class Tremor {
    public static final String MODID = "tremor";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Tremor() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, TremorConfig.COMMON_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, TremorConfig.CLIENT_SPEC);

        TremorNetwork.register();
        TremorSounds.register(modBus);
        TremorBlocks.register(modBus);
        tremor.item.TremorItems.register(modBus);
        tremor.hollow.HollowDrops.register(modBus);

        IEventBus game = MinecraftForge.EVENT_BUS;
        game.addListener(TremorCommands::register);
        game.addListener(tremor.hollow.HollowDigging::onBreakSpeed);
        game.addListener(tremor.hollow.HollowDigging::onPlace);
        game.addListener(tremor.hollow.HollowDigging::onPlayerTick);
        game.addListener(tremor.hollow.HollowDigging::onChangedDimension);

        game.addListener(TremorManager::onLevelLoad);
        game.addListener(TremorManager::onLevelUnload);
        game.addListener(TremorManager::onLevelTick);
        game.addListener(tremor.entity.Devour::onLevelTick);
        game.addListener(tremor.entity.Frenzy::onItemToss);
        game.addListener(tremor.entity.Frenzy::onLevelTick);
        // Lowest priority, cancelled ones included: what matters is the final state of the world.
        game.addListener(EventPriority.LOWEST, true, BlockEvent.NeighborNotifyEvent.class,
                TremorManager::onNeighborNotify);
        game.addListener(EventPriority.LOWEST, false, ExplosionEvent.Detonate.class, TremorManager::onExplosion);
        game.addListener(EventPriority.LOWEST, false, PistonEvent.Post.class, TremorManager::onPistonMoved);
        game.addListener(TremorManager::onChunkLoad);
        game.addListener(TremorManager::onPlayerLoggedIn);
        game.addListener(TremorManager::onPlayerChangedDimension);
        game.addListener(TremorManager::onPlayerRespawn);
        game.addListener(TremorManager::onServerStopping);
        // Hearing (SPEC 7). Lowest priority: a game event or fall another mod cancels is not heard.
        game.addListener(EventPriority.LOWEST, false, VanillaGameEvent.class, VibrationListener::onGameEvent);
        game.addListener(EventPriority.LOWEST, false, LivingFallEvent.class, VibrationListener::onLivingFall);
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
        game.addListener(EventPriority.LOWEST, false, BlockEvent.EntityPlaceEvent.class, HollowRules::onBlockPlaced);
        // Lowest priority, cancelled ones included: the block changed whatever the listeners did.
        game.addListener(EventPriority.LOWEST, true, BlockEvent.NeighborNotifyEvent.class,
                HollowRules::onNeighborNotify);
        game.addListener(HollowRules::onBlockBreak);
        game.addListener(HollowRules::onPistonMove);
        // Lowest priority: takes the blocks away from the explosion after everyone else saw them.
        game.addListener(EventPriority.LOWEST, false, ExplosionEvent.Detonate.class, HollowRules::onExplosion);
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
        game.addListener(EventPriority.HIGH, false, PlayerEvent.PlayerLoggedOutEvent.class, HollowRules::onPlayerLoggedOut);
        // No running in the hollow, whatever the client sends.
        game.addListener(SprintLock::onPlayerTick);
        // The Awakening (SPEC 9, stage 4b). Its tick comes after TremorManager's: it sees the stage of this tick.
        game.addListener(AwakeningManager::onLevelTick);
        game.addListener(AwakeningManager::onLevelUnload);
        game.addListener(AwakeningManager::onPlayerLoggedOut);
        game.addListener(AwakeningManager::onPlayerChangedDimension);
        game.addListener(AwakeningManager::onPlayerRespawn);
        game.addListener(AwakeningManager::onPlayerLoggedIn);
        // Lowest priority: a death another listener cancelled is none.
        game.addListener(EventPriority.LOWEST, false, LivingDeathEvent.class, AwakeningManager::onLivingDeath);
        // Highest priority: the fall of a rooted target does not happen, for anybody else either.
        game.addListener(EventPriority.HIGHEST, false, LivingFallEvent.class, AwakeningManager::onLivingFall);
        // High priority: the entities go deep before TremorManager drops its runtimes.
        game.addListener(EventPriority.HIGH, false, ServerStoppingEvent.class, AwakeningManager::onServerStopping);
        // The craters of defeats and edge escapes (SPEC 9 "Исходы"), dug over many ticks.
        game.addListener(Craters::onServerTick);
        game.addListener(Craters::onServerStopped);
        // The things that waited for the crater of a defeat lie at its swallow point, while the levels are there.
        game.addListener(Outcomes::onServerStopping);
        // High priority: the things of a player the ground killed go into the crater's caches before HollowRules
        // moves them.
        game.addListener(EventPriority.HIGH, false, LivingDropsEvent.class, CraterCaches::onLivingDrops);
        // Where escapes through the edge come out (SPEC 9 "Побег"), once the real chunks there are loaded.
        game.addListener(EdgeExits::onServerTick);
        game.addListener(EdgeExits::onServerStopped);
        // A player who survived a defeat is weakened once out of the hollow.
        HollowManager.addEndListener(Outcomes::onHollowEnded);
        HollowManager.addEndListener(AwakeningManager::onHollowEnded);
        HollowManager.addEndListener(HollowLevels::onHollowEnded);

        if (FMLEnvironment.dist == Dist.CLIENT) {
            TremorClient.init(modBus);
        }
    }
}
