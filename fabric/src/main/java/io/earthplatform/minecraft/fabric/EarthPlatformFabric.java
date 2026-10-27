package io.earthplatform.minecraft.fabric;

import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeConfigRegistry;
import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeModConfigEvents;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.cubic.CubicEvents;
import io.earthplatform.minecraft.cubic.CubicPoiClaims;
import io.earthplatform.minecraft.cubic.CubicSpawns;
import io.earthplatform.minecraft.cubic.compat.SableWind;
import io.earthplatform.minecraft.cubic.earth.ImageryCalibration;
import io.earthplatform.minecraft.cubic.net.CubePayloads;
import io.earthplatform.minecraft.cubic.play.EarthAdmin;
import io.earthplatform.minecraft.cubic.play.EarthCompass;
import io.earthplatform.minecraft.cubic.play.EarthConfig;
import io.earthplatform.minecraft.cubic.play.EarthExplorer;
import io.earthplatform.minecraft.cubic.play.EarthRules;
import io.earthplatform.minecraft.cubic.play.EarthWorlds;
import io.earthplatform.minecraft.cubic.portal.CubicTravel;
import java.util.IdentityHashMap;
import java.util.Map;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacements;
import net.neoforged.fml.config.ModConfig;

/**
 * The Fabric entry point: Fabric API's events (and, where it has none, the mixins in
 * {@code io.earthplatform.minecraft.fabric.mixin}) handed to the same game code NeoForge's events reach.
 */
public final class EarthPlatformFabric implements ModInitializer {
    /** Extra spawn rules, OR'd with vanilla's in {@code SpawnPlacementsMixin}, as NeoForge's Operation.OR does. */
    public static final Map<EntityType<?>, SpawnPlacements.SpawnPredicate<?>> SPAWN_RULES = new IdentityHashMap<>();

    /** Runs /earth after every other mod's commands, as NeoForge's lowest priority does. */
    private static final ResourceLocation LAST = ResourceLocation.fromNamespaceAndPath(EarthPlatformMod.MOD_ID, "last");

    @Override
    public void onInitialize() {
        NeoForgeModConfigEvents.loading(EarthPlatformMod.MOD_ID).register(EarthConfig::apply);
        NeoForgeModConfigEvents.reloading(EarthPlatformMod.MOD_ID).register(EarthConfig::apply);
        NeoForgeConfigRegistry.INSTANCE.register(EarthPlatformMod.MOD_ID, ModConfig.Type.CLIENT, EarthConfig.CLIENT_SPEC);
        NeoForgeConfigRegistry.INSTANCE.register(EarthPlatformMod.MOD_ID, ModConfig.Type.COMMON, EarthConfig.COMMON_SPEC);
        EarthConfig.setup();

        CubePayloads.register(new FabricPayloads());
        EarthWorlds.payloads(new FabricPayloads());

        CubicSpawns.placements(new CubicSpawns.Rules() {
            @Override public <T extends Entity> void or(EntityType<T> type, SpawnPlacements.SpawnPredicate<T> rule) { SPAWN_RULES.put(type, rule); }
        });

        // Levels
        ServerWorldEvents.LOAD.register((server, level) -> CubicEvents.load(level));
        ServerWorldEvents.UNLOAD.register((server, level) -> CubicEvents.unload(level));
        ServerTickEvents.END_WORLD_TICK.register(level -> {
            CubicEvents.tick(level);
            SableWind.tick(level);
        });
        ServerEntityEvents.ENTITY_LOAD.register(CubicPoiClaims::join);

        // Commands
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> {
            CubicEvents.commands(dispatcher);
            ImageryCalibration.commands(dispatcher);
        });
        CommandRegistrationCallback.EVENT.addPhaseOrdering(Event.DEFAULT_PHASE, LAST);
        CommandRegistrationCallback.EVENT.register(LAST, (dispatcher, context, selection) -> EarthWorlds.commands(dispatcher));

        // Server
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            FabricPlatform.server = server;
            EarthWorlds.aboutToStart(server);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            EarthWorlds.started(server);
            EarthAdmin.started(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> EarthAdmin.stopping());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            EarthWorlds.stopped();
            EarthCompass.stopped();
            EarthExplorer.stopped();
            EarthRules.stopped();
            FabricPlatform.server = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            EarthWorlds.tick(server);
            EarthRules.tick(server);
        });

        // Players
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            EarthWorlds.loggedIn(handler.player);
            EarthCompass.loggedIn(handler.player);
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, player, alive) -> {
            EarthWorlds.respawned(player);
            EarthCompass.respawned(player);
        });
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, from, to) -> {
            CubicTravel.changedDimension(player, from.dimension(), to.dimension());
            EarthWorlds.changedDimension(player);
            EarthCompass.changedDimension(player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            EarthWorlds.loggedOut(handler.player);
            EarthCompass.loggedOut(handler.player);
            EarthExplorer.loggedOut(handler.player);
            EarthRules.loggedOut(handler.player);
        });

        EarthPlatformMod.LOGGER.info("Earth Platform loaded");
    }
}
