package io.earthplatform.minecraft.scene;

import io.earthplatform.minecraft.EarthPlatformMod;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;

@EventBusSubscriber(modid=EarthPlatformMod.MOD_ID,value=Dist.CLIENT)
public final class SceneClientEvents {
    private static volatile SceneSession current;
    private static net.minecraft.client.multiplayer.ClientPacketListener lastConnection;
    private static long lastEpoch;
    private static boolean cleanupFaultReported;
    private static String stopped="Use /earth_scene resume to initialize this visual carrier";
    private SceneClientEvents(){}
    public static void start(SceneStartPayload payload) {
        var game=Minecraft.getInstance();
        if(!Boolean.getBoolean(SceneCommands.OPT_IN)||!game.isSameThread()||game.getSingleplayerServer()==null)throw new IllegalStateException("Explicit integrated scene context required");
        if(lastConnection!=game.getConnection()){lastConnection=game.getConnection();lastEpoch=0;}
        if(payload.epoch()<=lastEpoch)throw new IllegalStateException("Stale visual scene request");lastEpoch=payload.epoch();
        if(!closeCurrent("View restarted"))return;
        try{current=new SceneSession(payload);current.initialize();}
        catch(RuntimeException failure){EarthPlatformMod.LOGGER.error("Sparse terrain view could not start",failure);closeCurrent("Scene unavailable: "+failure.getMessage());game.gui.getChat().addMessage(Component.literal(stopped));}
    }
    private static boolean closeCurrent(String reason){
        var session=current;stopped=reason;if(session==null)return true;
        try{session.close();current=null;cleanupFaultReported=false;return true;}
        catch(RuntimeException failure){
            // Retain the single failed session and retry on subsequent client ticks before any reopen.
            stopped="Cleanup pending; scene remains fenced";
            if(!cleanupFaultReported){EarthPlatformMod.LOGGER.error("Sparse view retained failed cleanup owners for retry",failure);cleanupFaultReported=true;}
            return false;
        }
    }
    private static void invalidate(){var session=current;if(session!=null)session.requestInvalidation();}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){closeCurrent("Disconnected; session resources closed");lastConnection=null;lastEpoch=0;}
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var session=current;if(session!=null&&(!session.stillValid()||session.invalidated()))closeCurrent("Scene stopped; /earth_scene resume rebuilds at your camera");
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL)return;
        var session=current;if(session==null)return;
        try{session.render(event);}
        catch(RuntimeException failure){EarthPlatformMod.LOGGER.error("Sparse view failed closed",failure);closeCurrent("Scene stopped: "+failure.getMessage());}
    }
    @SubscribeEvent public static void hud(RenderGuiEvent.Post event) {
        if(!Boolean.getBoolean(SceneCommands.OPT_IN))return;
        var game=Minecraft.getInstance();if(game.level==null||game.player==null||game.options.hideGui||!game.level.dimension().equals(SceneCommands.CARRIER))return;
        var session=current;var gui=event.getGuiGraphics();
        List<String> lines=session==null?List.of("EARTH VISUAL-ONLY CARRIER | no cubic collision/edit/save",stopped):List.of(
                "EARTH TERRAIN VIEW | native Y "+String.format(java.util.Locale.ROOT,"%.1f",session.camera().y)+" | visual-only carrier",
                "Copernicus DSM + WorldCover | 48m near / 512m far | fixed light",
                "Near owners "+session.resident()+"/256 | ready meshes "+session.nearCount()+"/"+session.targetCount()+" | "+session.status(),
                "Spectator: no cube collision/edit/save | /earth_scene leave");
        int boxWidth=Math.min(gui.guiWidth()-12,lines.stream().mapToInt(game.font::width).max().orElse(0)+10),height=lines.size()*(game.font.lineHeight+2)+8;
        int x=6,y=game.getDebugOverlay().showDebugScreen()?gui.guiHeight()-height-6:6;
        gui.fill(x,y,x+boxWidth,y+height,0xDD101820);gui.enableScissor(x,y,x+boxWidth,y+height);
        for(int i=0;i<lines.size();i++)gui.drawString(game.font,lines.get(i),x+5,y+4+i*(game.font.lineHeight+2),i==2?0xF2D178:0xE5F0F6);
        gui.disableScissor();gui.flush();if(session!=null)session.afterHud();
    }
    @EventBusSubscriber(modid=EarthPlatformMod.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class Resources {
        @SubscribeEvent public static void register(RegisterClientReloadListenersEvent event) {
            event.registerReloadListener((barrier,manager,prepare,apply,background,game)->{
                invalidate();return barrier.wait(null).thenRunAsync(()->closeCurrent("Resource reload closed the scene; /earth_scene resume"),game);
            });
        }
    }
}
