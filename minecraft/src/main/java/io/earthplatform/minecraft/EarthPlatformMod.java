package io.earthplatform.minecraft;

import com.mojang.serialization.MapCodec;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import io.earthplatform.geography.GeoPoint;
import io.earthplatform.minecraft.storage.WorldFormatGuard;
import io.earthplatform.minecraft.storage.SectionYCodec;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

/** Actual NeoForge entry point. The first engine adapter is intentionally bounded. */
@Mod(EarthPlatformMod.MOD_ID)
public final class EarthPlatformMod {
    public static final String MOD_ID = "earthplatform";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final DeferredRegister<MapCodec<? extends ChunkGenerator>> GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, MOD_ID);

    static { GENERATORS.register("calibration_window", () -> EarthWindowGenerator.CODEC); }

    public EarthPlatformMod(IEventBus modBus) {
        GENERATORS.register(modBus);
        modBus.addListener(EarthPlatformMod::registerPayloads);
        modBus.addListener(io.earthplatform.minecraft.fixture.NativeProbeEvents::registerPayloads);
        NeoForge.EVENT_BUS.addListener(EarthPlatformMod::registerCommands);
        NeoForge.EVENT_BUS.addListener(EarthPlatformMod::playerChangedDimension);
        NeoForge.EVENT_BUS.addListener(EarthPlatformMod::playerLoggedIn);
        NeoForge.EVENT_BUS.addListener(EarthPlatformMod::serverPreflight);
        LOGGER.info("Earth Platform loaded: bounded vertical-window engine adapter; true cubic engine integration remains in development");
    }

    private static void serverPreflight(ServerAboutToStartEvent event) {
        WorldFormatGuard.preflight(event.getServer().getWorldPath(LevelResource.ROOT), Boolean.getBoolean(SectionYCodec.OPT_IN));
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("2").playToClient(WindowStatePayload.TYPE, WindowStatePayload.CODEC,
                (payload, context) -> WindowStatePayload.latest = payload);
    }
    private static void playerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendWindowState(player);
    }
    private static void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) sendWindowState(player);
    }
    private static void sendWindowState(ServerPlayer player) {
        if (player.serverLevel().getChunkSource().getGenerator() instanceof EarthWindowGenerator gen) {
            PacketDistributor.sendToPlayer(player, new WindowStatePayload(player.serverLevel().dimension().location(), gen.originY(), gen.surfaceY(),
                    gen.realTerrain(), gen.terrainDataset(), gen.horizontalProjection()));
        }
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        var root = Commands.literal("earth").requires(s -> s.hasPermission(2));
        root.then(Commands.literal("status").executes(context -> {
            var source = context.getSource();
            var generator = source.getLevel().getChunkSource().getGenerator();
            String text = generator instanceof EarthWindowGenerator earth
                    ? "Earth Platform window: Earth Y = local Y + " + earth.originY()
                      + ". Canonical Y=" + (earth.originY() + source.getPosition().y)
                      + ". Real Minecraft blocks; " + (earth.realTerrain()
                        ? earth.terrain().source().manifest().provenance().id() + "; projection=" + earth.horizontalProjection()
                            + "; source+projection+material identity=" + earth.terrain().runtimeFingerprint()
                        : "synthetic terrain") + "; 256-block window; NOT seamless cubic integration."
                    : "Earth Platform loaded. Use /earth visit coast, /earth visit everest or /earth visit trench. These are synthetic calibration windows.";
            source.sendSuccess(() -> Component.literal(text), false);
            return 1;
        }));
        var visit = Commands.literal("visit");
        for (String name : new String[]{"coast", "everest", "trench", "real_coast", "real_everest", "real_trench", "copernicus_ridge"}) {
            visit.then(Commands.literal(name).executes(context -> {
                ServerPlayer player = context.getSource().getPlayerOrException();
                ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(MOD_ID, name));
                ServerLevel target = player.server.getLevel(key);
                if (target == null) { context.getSource().sendFailure(Component.literal("Calibration dimension unavailable; create a new world with the mod installed.")); return 0; }
                var gen = (EarthWindowGenerator) target.getChunkSource().getGenerator();
                double x = 0.5, z = 0.5;
                if (name.equals("copernicus_ridge")) {
                    x = 9_674_928.5; z = -3_112_479.5;
                } else if (gen.realTerrain()) {
                    GeoPoint landmark = switch (name) {
                        case "real_everest" -> new GeoPoint(27.9881, 86.925);
                        case "real_trench" -> new GeoPoint(11.3733, 142.5917);
                        default -> new GeoPoint(37.7749, -122.4194);
                    };
                    var projected = gen.terrain().projection().toProjected(landmark);
                    x = Math.floor(projected.x()) + 0.5; z = Math.floor(projected.z()) + 0.5;
                }
                long ground = gen.earthSurfaceY((long) Math.floor(x), (long) Math.floor(z));
                double localY = ground - gen.originY() + 2.0;
                if (localY < 1 || localY >= gen.getGenDepth()) {
                    context.getSource().sendFailure(Component.literal("Sampled terrain does not fit this bounded vertical window; refusing an unsafe teleport."));
                    return 0;
                }
                if (!PreviewTeleport.teleport(player, target, x, localY, z)) {
                    context.getSource().sendFailure(Component.literal("Preview travel was cancelled; no destination was entered."));
                    return 0;
                }
                context.getSource().sendSuccess(() -> Component.literal((gen.realTerrain() ? ProofTerrain.shortLabel(gen.terrainDataset()) + " " : "Synthetic ") + name
                        + ": canonical ground Y=" + ground + "m. 256m window; heuristic materials. /earth status shows source/projection identity."), false);
                return 1;
            }));
        }
        root.then(visit);
        event.getDispatcher().register(root);
    }
}
