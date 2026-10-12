package io.earthplatform.minecraft.lod;

import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.coregate.RollingGateClient;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Explicit root-run diagnostic only; never automatically allocates additional GPU owners. */
@EventBusSubscriber(modid=EarthPlatformMod.MOD_ID,value=Dist.CLIENT)
public final class DhLongDistanceProbeClient {
    public static final String ENABLED="earthplatform.experimental.longDistanceDepthProbe";
    private static boolean pending;
    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event){
        if(!Boolean.getBoolean(ENABLED))return;
        event.getDispatcher().register(Commands.literal("earth_lod_depth_probe").executes(c->{if(RollingGateClient.hasRetainedSession()){c.getSource().sendFailure(Component.literal("Leave the retained gameplay session before running the isolated depth probe."));return 0;}pending=true;c.getSource().sendSuccess(()->Component.literal("Queued isolated actual DH/native depth probe."),false);return 1;}));
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Post event){
        if(!pending)return;pending=false;
        try{if(RollingGateClient.hasRetainedSession())throw new IllegalStateException("A gameplay session was retained after probe queuing");String message=DhLongDistanceGpuProbe.run();EarthPlatformMod.LOGGER.info("LONG_DEPTH_PROBE_PASS {}",message);if(Minecraft.getInstance().player!=null)Minecraft.getInstance().gui.getChat().addMessage(Component.literal(message));}
        catch(Exception|Error failure){EarthPlatformMod.LOGGER.error("LONG_DEPTH_PROBE_FAIL",failure);if(Minecraft.getInstance().player!=null)Minecraft.getInstance().gui.getChat().addMessage(Component.literal("Long-distance depth probe failed; inspect report/client log."));}
    }
}
