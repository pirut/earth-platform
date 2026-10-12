package io.earthplatform.minecraft.survival.runtime;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.survival.source.SurvivalSceneSourceAssets;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** Explicit default-off ordinary entry command. No fixture profile or old prototype rerouting. */
@EventBusSubscriber(modid="earthplatform")
public final class InstalledMarinSessionCommands {
    private static MinecraftServer sourceServer;
    private static SurvivalSceneSourceAssets sourceAssets;
    private InstalledMarinSessionCommands() {}
    /** Coordinator port for reusing the very same precharged source already prepared for rendering. */
    public static void useSourceAssets(MinecraftServer server, SurvivalSceneSourceAssets exactAssets) {
        if (!server.isSameThread() || exactAssets.source().palette().context().biomes()!=server.registryAccess().registryOrThrow(Registries.BIOME))
            throw new SurvivalRefusal("Installed source setup requires its exact native registry owner");
        if (sourceAssets!=null && (sourceServer!=server || sourceAssets!=exactAssets))
            throw new SurvivalRefusal("Installed source identity cannot be replaced");
        sourceServer=server; sourceAssets=exactAssets;
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        if (!Boolean.getBoolean(InstalledMarinSessionAdmission.FLAG)) return;
        event.getDispatcher().register(Commands.literal("earthsurvival")
                .requires(source->source.getEntity() instanceof ServerPlayer)
                .then(Commands.literal("enter").executes(context->{
                    ServerPlayer player=context.getSource().getPlayerOrException();
                    try {
                        InstalledMarinSessionAdmission.requireLaunch();
                        if (sourceAssets==null) {
                            var palette=EnginePaletteContext.create(player.server.registryAccess().registryOrThrow(Registries.BIOME));
                            useSourceAssets(player.server,SurvivalSceneSourceAssets.load(
                                    player.server.getServerDirectory().resolve("earthplatform-installed-marin-source"),palette));
                        }
                        if (sourceServer!=player.server) throw new SurvivalRefusal("Source owner belongs to another native server");
                        InstalledMarinSessionRuntime.requestEntry(player,sourceAssets); return 1;
                    } catch (java.io.IOException | RuntimeException failure) {
                        context.getSource().sendFailure(Component.literal(failure.getMessage())); return 0;
                    }
                })));
        // Advertises the exact text lane to the real client. The authenticated packet HEAD owns
        // execution; reaching this harmless fallback never invokes return, status or other commands.
        event.getDispatcher().register(Commands.literal("earthsurvival_control")
                .requires(source->source.getEntity() instanceof ServerPlayer)
                .then(Commands.argument("typed",StringArgumentType.greedyString()).executes(context->{
                    context.getSource().sendFailure(Component.literal("Use the current session's Return or Status control.")); return 0;
                })));
    }
}
