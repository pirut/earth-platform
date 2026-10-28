package io.earthplatform.minecraft.neoforge;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubicEvents;
import io.earthplatform.minecraft.cubic.CubicPoiClaims;
import io.earthplatform.minecraft.cubic.CubicSpawns;
import io.earthplatform.minecraft.cubic.compat.PortalReturn;
import io.earthplatform.minecraft.cubic.compat.SableWind;
import io.earthplatform.minecraft.cubic.earth.ImageryCalibration;
import io.earthplatform.minecraft.cubic.net.CubePayloads;
import io.earthplatform.minecraft.cubic.play.EarthAdmin;
import io.earthplatform.minecraft.cubic.play.EarthBasics;
import io.earthplatform.minecraft.cubic.play.EarthCompass;
import io.earthplatform.minecraft.cubic.play.EarthConfig;
import io.earthplatform.minecraft.cubic.play.EarthExplorer;
import io.earthplatform.minecraft.cubic.play.EarthRules;
import io.earthplatform.minecraft.cubic.play.EarthWorlds;
import io.earthplatform.minecraft.cubic.portal.CubicTravel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** NeoForge's events, on both sides, handed to the game code. */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID)
public final class NeoForgeEvents {
    private NeoForgeEvents() {}

    /** Setup and registration, on the mod's own event bus. */
    @EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Setup {
        private Setup() {}

        @SubscribeEvent public static void payloads(RegisterPayloadHandlersEvent event) {
            CubePayloads.register(new NeoForgePayloads(event.registrar(CubePayloads.VERSION)));
            EarthWorlds.payloads(new NeoForgePayloads(event.registrar(EarthWorlds.PAYLOADS_VERSION)));
        }

        @SubscribeEvent public static void configLoaded(ModConfigEvent.Loading event) { EarthConfig.apply(event.getConfig()); }
        @SubscribeEvent public static void configReloaded(ModConfigEvent.Reloading event) { EarthConfig.apply(event.getConfig()); }
        @SubscribeEvent public static void setup(FMLCommonSetupEvent event) { EarthConfig.setup(); }

        @SubscribeEvent public static void placements(RegisterSpawnPlacementsEvent event) {
            CubicSpawns.placements(new CubicSpawns.Rules() {
                @Override public <T extends Entity> void or(EntityType<T> type, SpawnPlacements.SpawnPredicate<T> rule) {
                    event.register(type, rule, RegisterSpawnPlacementsEvent.Operation.OR);
                }
            });
        }
    }

    // Levels -------------------------------------------------------------------------------------

    @SubscribeEvent public static void load(LevelEvent.Load event) { CubicEvents.load(event.getLevel()); }
    @SubscribeEvent public static void save(LevelEvent.Save event) { CubicEvents.save(event.getLevel()); }
    @SubscribeEvent public static void unload(LevelEvent.Unload event) { CubicEvents.unload(event.getLevel()); }

    @SubscribeEvent public static void levelTick(LevelTickEvent.Post event) {
        CubicEvents.tick(event.getLevel());
        SableWind.tick(event.getLevel());
    }

    @SubscribeEvent public static void sleptThrough(SleepFinishedTimeEvent event) { EarthBasics.sleptThrough(event.getLevel(), event.getNewTime()); }

    @SubscribeEvent public static void join(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk()) CubicPoiClaims.join(event.getEntity(), event.getLevel());
    }

    @SubscribeEvent public static void travel(EntityTravelToDimensionEvent event) {
        if (PortalReturn.fallFromAether(event.getEntity(), event.getDimension())) event.setCanceled(true);
    }

    // Commands -----------------------------------------------------------------------------------

    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        CubicEvents.commands(event.getDispatcher());
        ImageryCalibration.commands(event.getDispatcher());
    }

    /** /earth last, so it can add to commands other mods made. */
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void earthCommands(RegisterCommandsEvent event) {
        EarthWorlds.commands(event.getDispatcher());
    }

    // Server -------------------------------------------------------------------------------------

    @SubscribeEvent public static void aboutToStart(ServerAboutToStartEvent event) { EarthWorlds.aboutToStart(event.getServer()); }

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        EarthWorlds.started(event.getServer());
        EarthAdmin.started(event.getServer());
    }

    @SubscribeEvent public static void stopping(ServerStoppingEvent event) { EarthAdmin.stopping(); }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        EarthWorlds.stopped();
        EarthCompass.stopped();
        EarthExplorer.stopped();
        EarthRules.stopped();
    }

    @SubscribeEvent public static void serverTick(ServerTickEvent.Post event) {
        EarthWorlds.tick(event.getServer());
        EarthRules.tick(event.getServer());
    }

    // Players ------------------------------------------------------------------------------------

    @SubscribeEvent public static void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        EarthWorlds.loggedIn(event.getEntity());
        EarthCompass.loggedIn(event.getEntity());
    }

    @SubscribeEvent public static void respawned(PlayerEvent.PlayerRespawnEvent event) {
        EarthWorlds.respawned(event.getEntity());
        EarthCompass.respawned(event.getEntity());
    }

    @SubscribeEvent public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) CubicTravel.changedDimension(player, event.getFrom(), event.getTo());
        EarthWorlds.changedDimension(event.getEntity());
        EarthCompass.changedDimension(event.getEntity());
    }

    @SubscribeEvent public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        EarthWorlds.loggedOut(event.getEntity());
        EarthCompass.loggedOut(event.getEntity());
        EarthExplorer.loggedOut(event.getEntity());
        EarthRules.loggedOut(event.getEntity());
    }

    @SubscribeEvent public static void respawnPosition(PlayerRespawnPositionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            event.setDimensionTransition(CubicTravel.home(player, event.getDimensionTransition()));
    }
}
