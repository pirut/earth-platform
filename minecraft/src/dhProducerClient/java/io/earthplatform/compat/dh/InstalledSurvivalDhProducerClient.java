package io.earthplatform.compat.dh;

import com.google.gson.GsonBuilder;
import io.earthplatform.minecraft.EarthPlatformMod;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.server.IntegratedServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Default-off, one-shot installed diagnostic. No gameplay action, real GL or scene draw authority. */
@EventBusSubscriber(modid=EarthPlatformMod.MOD_ID, value=Dist.CLIENT)
public final class InstalledSurvivalDhProducerClient {
    public static final String ENABLED="earthplatform.experimental.survivalDhProducerClient";
    private static final Object LOCK=new Object();
    private static final IntegratedProducerRequestGate<IntegratedServer,InstalledIrisRuntime> GATE=new IntegratedProducerRequestGate<>();
    private static boolean reportAttempted;
    // These identities are captured/read exclusively by client-owner callbacks, never by the server.
    private static ClientLevel clientLevel;
    private static ClientPacketListener clientConnection;
    private InstalledSurvivalDhProducerClient() {}

    @SubscribeEvent public static void clientTick(ClientTickEvent.Post ignored) {
        if (!Boolean.getBoolean(ENABLED)) return;
        var game=Minecraft.getInstance();
        if(!game.isSameThread()) throw new IllegalStateException("Producer request requires client owner");
        synchronized(LOCK) {
            if(GATE.state()==IntegratedProducerRequestGate.State.PASS || GATE.state()==IntegratedProducerRequestGate.State.FAIL) return;
            if(GATE.state()==IntegratedProducerRequestGate.State.NEW) {
                var server=game.getSingleplayerServer();
                if(server==null || game.level==null || game.getConnection()==null || game.player==null) return;
                if(server.getClass()!=IntegratedServer.class)throw new IllegalStateException("Exact native IntegratedServer required");
                clientLevel=game.level; clientConnection=game.getConnection();
                final InstalledIrisRuntime installed;
                try { installed=InstalledIrisRuntime.resolveInstalled(); }
                catch(Throwable failure) { failure.printStackTrace();GATE.fail();finish(false,failure);return; }
                var request=GATE.publish(server,installed,System.nanoTime());
                System.out.println("EARTH_DH_PRODUCER_CLIENT_REQUEST generation="+request.generation());
            } else if(game.level!=clientLevel || game.getConnection()!=clientConnection
                    || game.getSingleplayerServer()!=GATE.request().server()) {
                revoke("Client level, connection or integrated server replaced");
            } else if(System.nanoTime()-GATE.request().startedNanos()>TimeUnit.SECONDS.toNanos(120)) {
                revoke("One-shot producer checked wall deadline exceeded");
            }
        }
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut ignored) {
        if(!Boolean.getBoolean(ENABLED)) return;
        if(!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Producer revocation requires client owner");
        synchronized(LOCK) { if(GATE.state()==IntegratedProducerRequestGate.State.PENDING || GATE.state()==IntegratedProducerRequestGate.State.RUNNING) revoke("Client logout retired producer request"); }
    }
    private static void revoke(String reason) {
        if(GATE.revoke()) finish(false,new IllegalStateException(reason));
    }
    private static void requireCurrent(IntegratedProducerRequestGate.Request<IntegratedServer,InstalledIrisRuntime> exact) {
        synchronized(LOCK) { GATE.requireCurrent(exact,exact.witness()); }
    }
    @SubscribeEvent public static void serverTick(ServerTickEvent.Post event) {
        if(!Boolean.getBoolean(ENABLED)) return;
        final IntegratedProducerRequestGate.Request<IntegratedServer,InstalledIrisRuntime> exact;
        synchronized(LOCK) {
            if(GATE.state()!=IntegratedProducerRequestGate.State.PENDING || event.getServer()!=GATE.request().server()) return;
            if(!event.getServer().isSameThread()) throw new IllegalStateException("Producer requires exact integrated server owner");
            exact=GATE.consume((IntegratedServer)event.getServer());
            if(exact==null)throw new IllegalStateException("One-shot producer consumption changed");
        }
        try {
            requireCurrent(exact);
            var root=FMLPaths.GAMEDIR.get().toRealPath();
            Path work=Files.createDirectory(root.resolve("dh-producer-client-work"));
            InstalledSurvivalDhProducerClientBody.run(work,exact.witness(),()->requireCurrent(exact));
            synchronized(LOCK) { GATE.complete(exact,()->finish(true,null)); }
        } catch(Throwable failure) {
            failure.printStackTrace();
            synchronized(LOCK) { GATE.fail(); if(!reportAttempted) finish(false,failure); }
        }
    }
    /** Called under LOCK. Success is committed only while the original one-shot request is current. */
    private static void finish(boolean success, Throwable failure) {
        if(reportAttempted)throw new IllegalStateException("Terminal report is already attempted");
        reportAttempted=true;
        String status=success?"PASS":"FAIL";
        var request=GATE.request();
        var report=new LinkedHashMap<String,Object>();
        report.put("status",status); report.put("utc",Instant.now().toString());
        report.put("generation",request==null?0:request.generation());
        report.put("elapsedNanos",request==null?0:System.nanoTime()-GATE.request().startedNanos());
        report.put("runtime","ACTUAL_INTEGRATED_CLIENT_SERVER"); report.put("gl","MODELED_ONLY");
        var globalCpu=io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.global().evidence();
        report.put("requiredGlobalSourceAssetsReservationBytes",64L<<20);
        report.put("actualGlobalSourceAssetsPoolBytes",globalCpu.pools().get(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.SOURCE_ASSETS));
        report.put("actualGlobalCpuBytes",globalCpu.bytes());
        report.put("peakGlobalCpuBytes",globalCpu.peakBytes());
        report.put("fixtureOperationCpuEnvelopeBytes",128L<<20);
        report.put("selectedSourceDistance",24); report.put("liveSceneDrawClaim",false);
        report.put("configGenerationRequirement","ACTUAL_TRANSFORMED_AS_GRASS_OFF");
        report.put("requiredWorkspaceBytes",InstalledDhTerrainRuntime.SURVIVAL_WORKSPACE_BYTES);
        report.put("requiredWorkspaceMetadataBytes",InstalledDhTerrainRuntime.SURVIVAL_WORKSPACE_METADATA_BYTES);
        report.put("qualification","Real source and official helper with production ownership; modeled GPU operations, no whole-scene draw or gameplay claim");
        if(failure!=null) {
            String description=failure.getClass().getName()+": "+String.valueOf(failure.getMessage());
            report.put("failure",description.substring(0,Math.min(4096,description.length())));
        }
        try {
            String name=System.getProperty(ENABLED+".report","");
            if(!name.matches("[A-Za-z0-9._-]{1,80}\\.json")) throw new IllegalStateException("Explicit unique report basename required");
            var dir=FMLPaths.GAMEDIR.get().toRealPath().resolve("dh-producer-reports");
            if(!Files.exists(dir,LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(dir);
            if(!Files.isDirectory(dir,LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Report directory is not an owned ordinary directory");
            String json=new GsonBuilder().setPrettyPrinting().create().toJson(report)+"\n";
            Files.writeString(dir.resolve(name),json,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
            System.out.println("EARTH_DH_PRODUCER_CLIENT_TERMINAL "+status+" report="+name);
        } catch(Throwable reportingFailure) {
            GATE.fail();
            reportingFailure.printStackTrace();
            System.err.println("EARTH_DH_PRODUCER_CLIENT_REPORT_FAILURE");
            throw new IllegalStateException("Terminal report write failed",reportingFailure);
        }
    }
}
