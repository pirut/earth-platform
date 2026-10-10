package io.earthplatform.minecraft.renderprobe;

import io.earthplatform.minecraft.EarthPlatformMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

@EventBusSubscriber(modid=EarthPlatformMod.MOD_ID,value=Dist.CLIENT)
public final class NativeRenderEvents {
    private NativeRenderEvents(){}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){NativeRenderSession.closeCurrent();}

    @EventBusSubscriber(modid=EarthPlatformMod.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class Resources {
        private Resources(){}
        @SubscribeEvent public static void register(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((barrier,manager,prepareProfiler,applyProfiler,background,game)->{
                NativeRenderSession.requestReloadInvalidation();
                return barrier.wait(null).thenRunAsync(NativeRenderSession::closeCurrent,game);
            });
        }
    }
}
