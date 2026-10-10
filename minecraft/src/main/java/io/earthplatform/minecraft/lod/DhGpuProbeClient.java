package io.earthplatform.minecraft.lod;

import io.earthplatform.minecraft.EarthPlatformMod;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Explicit, client-only entrypoint. A title-screen run needs no world or DH mod installation. */
@EventBusSubscriber(modid = EarthPlatformMod.MOD_ID, value = Dist.CLIENT)
public final class DhGpuProbeClient {
    public static final String ENABLED = "earthplatform.experimental.dhGpuProbe";
    public static final String AUTO_RUN = "earthplatform.experimental.dhGpuProbeAutoRun";
    private static boolean pending;
    private static boolean autoRunAttempted;

    private DhGpuProbeClient() { }

    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) {
        if (!Boolean.getBoolean(ENABLED)) return;
        event.getDispatcher().register(Commands.literal("earth_lod_probe")
                .then(Commands.literal("run").executes(context -> {
                    pending = true;
                    context.getSource().sendSuccess(() -> Component.literal("Queued isolated DH GPU diagnostic on the next rendered frame."), false);
                    return 1;
                })));
    }

    @SubscribeEvent public static void render(RenderFrameEvent.Post event) {
        if (!Boolean.getBoolean(ENABLED)) return;
        if (!autoRunAttempted && Boolean.getBoolean(AUTO_RUN)) {
            autoRunAttempted = true;
            pending = true;
        }
        if (!pending) return;
        pending = false;
        try {
            String result = DhOffscreenGpuProbe.run();
            EarthPlatformMod.LOGGER.info("DH_GPU_PROBE_PASS {}", result);
            if (Minecraft.getInstance().player != null)
                Minecraft.getInstance().gui.getChat().addMessage(Component.literal(result));
        } catch (Exception failure) {
            EarthPlatformMod.LOGGER.error("DH_GPU_PROBE_FAIL Isolated diagnostic did not pass", failure);
            if (Minecraft.getInstance().player != null)
                Minecraft.getInstance().gui.getChat().addMessage(Component.literal("DH GPU diagnostic failed. See the evidence report and client log."));
        }
    }
}
