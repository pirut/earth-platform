package io.earthplatform.minecraft.coregate;

import io.earthplatform.minecraft.visual.*;
import io.earthplatform.minecraft.scene.NativeVisualCapsuleCapture;
import io.earthplatform.minecraft.scene.NativeVisualStream;
import io.earthplatform.minecraft.scene.NativeVisualGpuBatch;
import io.earthplatform.minecraft.scene.NativeVisualPageOracle;
import io.earthplatform.minecraft.scene.NativeVisualOracleFixtures;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.compat.dh.*;
import io.earthplatform.compat.dh.shader.*;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.enginebridge.RollingCubeActor;
import io.earthplatform.minecraft.enginebridge.BoundedProviderPreparation;
import io.earthplatform.minecraft.enginebridge.RollingCubePlan;
import io.earthplatform.minecraft.lod.DhPreparedLodView;
import io.earthplatform.minecraft.lod.RenderSubmissionProbe;
import io.earthplatform.minecraft.lod.DhReplacementInput;
import io.earthplatform.minecraft.lod.DhReplacementSetInput;
import io.earthplatform.minecraft.lod.DhArenaInput;
import io.earthplatform.minecraft.enginebridge.ArenaCertificate;
import io.earthplatform.minecraft.enginebridge.ReplacementSetCertificate;
import io.earthplatform.minecraft.enginebridge.ReplacementCertificate;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.minecraft.scene.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

/** Bounded rolling replica: only an acknowledged exact near/far pair may advance collision. */
@EventBusSubscriber(modid=EarthPlatformMod.MOD_ID,value=Dist.CLIENT)
public final class RollingGateClient {
    private static volatile Session current;
    private static ClientPacketListener lastConnection;
    private static long lastEpoch;
    private static long actualRenderFrame;
    private static InstalledDhResolver installedResolver;
    private static InstalledIrisRuntime installedIris;
    // Tiny completed policy receipts only; no capsule bytes, resource lease or renderer survives through these.
    private static NativeVisualCapsuleCapture.Qualification nativeQualification;
    private static String completedNativeOracleIdentity;
    static String nativeOracleIdentity(io.earthplatform.minecraft.scene.NativeVisualCapsule.Identity id){return id.resourceDigest()+":"+id.modelDigest()+":"+id.appearanceDigest()+":"+id.bytesDigest();}
    static String nativeDistanceRefusal(int selected,Throwable failure){
        String reason=String.valueOf(failure.getMessage()).replace('\n',' ').replace('\r',' ');
        if(reason.equals("null")||reason.isBlank())reason=failure.getClass().getSimpleName();
        return "Native terrain at "+selected+" chunks stopped: "+reason.substring(0,Math.min(384,reason.length()))+". Coverage was not reduced; leave and select an admitted setting before re-entering.";
    }
    private static final String SHADER_OPT_IN="earthplatform.experimental.installedShaderBackend";
    private static boolean shaderMode(){return Boolean.getBoolean(SHADER_OPT_IN)||io.earthplatform.compat.dh.EarlyPlayableAdmission.enabled();}
    private static NeoForgeDhCatalogue installedCatalogue(){
        if(!shaderMode())return new NeoForgeDhCatalogue();
        if(installedIris==null)installedIris=InstalledIrisRuntime.resolveInstalled();
        return new NeoForgeDhCatalogue(installedIris);
    }
    private static InstalledDhResolver.Runtime requireInstalledDh(){
        if(installedResolver==null)installedResolver=new InstalledDhResolver(installedCatalogue());
        var result=installedResolver.resolve();
        if(result.status()!=InstalledDhResolver.Status.READY)throw new IllegalStateException(result.explanation());
        return result.runtime();
    }
    private static NativeDistanceClientTransition distanceTransition;
    // Keep the original wire epoch/serial and one bounded episode through uncommitted ABA rebuilds.
    private static NativeDistanceClientTransition distanceProgressTransition;
    private static NativeDistanceProgressState distanceProgress;
    private static ClientPacketListener distanceProgressConnection;
    private static Object distanceProgressServer;
    private static NativeDistanceProgressWatchdog.Checkpoint lastDistanceProgress;

    private static ClientPacketListener distanceTransitionConnection;
    private static long distanceRestartApprovedEpoch;
    private static boolean distanceTransitionCancelled;
    private static int visualRecoveryCount;
    private static String visualRecoveryReason="";
    private static NativeDistanceClientTransition.Selection actualDistanceSelection(){
        var options=Minecraft.getInstance().options;
        return new NativeDistanceClientTransition.Selection(options.renderDistance().get(),options.getEffectiveRenderDistance(),
                ((NativeDistanceOptionsAccess)(Object)options).serverRenderDistance());
    }
    /** Called before the exact Options mutation. It fences even an unobserved A→B→A change. */
    public static void beforeNativeDistanceMutation(){
        InstalledIrisRuntime.invalidateLifecycle("Minecraft native render-distance mutation");
        restartNativeDistance("Selected native distance rebuilding");
    }
    /** Owner-tick entry for both option changes and one exceptional same-selection camera replan. */
    private static void restartNativeDistance(String reason){
        var game=Minecraft.getInstance();
        if(game==null||!game.isSameThread())throw new IllegalStateException("Render-distance mutation requires the client owner");
        var s=current;if(distanceTransition==null&&(s==null||!s.distanceMode()))return;
        if(game.getConnection()==null||distanceTransition!=null&&distanceTransition.failed())return;
        if(distanceTransition==null){distanceTransition=distanceProgressTransition==null?new NativeDistanceClientTransition(s.epoch):distanceProgressTransition;distanceTransitionConnection=game.getConnection();}
        var request=distanceTransition.mutation(actualDistanceSelection());
        if(distanceProgress==null){distanceProgressServer=game.getSingleplayerServer();boolean clockBound=NativeDistanceProgressState.bindPauseClock(distanceProgressServer,game.getConnection(),request.epoch(),game.isPaused());distanceProgress=new NativeDistanceProgressState(request,false,System.nanoTime(),game.isPaused(),0);if(!clockBound)distanceProgress.watchdog.fail("pause-clock-owner-conflict");distanceProgressConnection=game.getConnection();distanceProgressTransition=distanceTransition;}
        else{distanceProgress.observe(System.nanoTime(),game.isPaused());distanceProgress.request(request);}
        if(distanceProgress.watchdog.failed()){failDistanceProgress();return;}
        PacketDistributor.sendToServer(request);
        if(s!=null){s.shaderNativeLifecycle.invalidate();s.invalidateOwned();if(s.shaderCarrier!=null)s.shaderCarrier.suspend(reason);}
        stopped="Preparing the selected native render distance; position held safely";
    }
    private static boolean pumpVisualRecovery(){
        var s=current;if(s==null||!s.distanceMode()||distanceTransition!=null)return false;
        if(s.visualFrontier.expired(System.nanoTime()))s.queueVisualRecovery("Native camera handover exceeded its bounded deadline");
        if(!s.visualRecoveryQueued)return false;
        if(visualRecoveryCount!=0){closeCurrent("Native camera recovery failed; leave and re-enter after retained cleanup finishes");return true;}
        visualRecoveryCount++;visualRecoveryReason=s.visualRecoveryDetail;
        EarthPlatformMod.LOGGER.warn("NATIVE_CAMERA_RECOVERY epoch={} count={} reason={}",s.epoch,visualRecoveryCount,visualRecoveryReason);
        s.report("CAMERA_RECOVERY_REQUESTED");
        InstalledIrisRuntime.invalidateLifecycle("Native camera same-selection recovery");
        restartNativeDistance("Native camera handover rebuilding at the same held position");return true;
    }
    public static void nativeDistanceChange(NativeDistanceChangePayload message){
        var game=Minecraft.getInstance();if(!game.isSameThread()){game.execute(()->nativeDistanceChange(message));return;}
        if(distanceProgress!=null){distanceProgress.observe(System.nanoTime(),game.isPaused());if(distanceProgress.watchdog.failed()){failDistanceProgress();return;}}
        if(message.kind()==NativeDistanceChangePayload.FAILED&&distanceProgress!=null&&game.getConnection()==distanceProgressConnection&&distanceProgress.request.sameSelection(message)){distanceProgress.watchdog.fail("server-continuation-refused");failDistanceProgress();return;}
        if(distanceTransition==null||game.getConnection()!=distanceTransitionConnection||message.epoch()!=distanceTransition.epoch())return;
        if(message.kind()==NativeDistanceChangePayload.FAILED){if(distanceTransition.latest()==null||!distanceTransition.latest().sameSelection(message))return;distanceTransition.fail();stopped="Native distance rebuild failed; retained cleanup must finish before leaving";closeCurrent(stopped);return;}
        if(distanceTransition.drain(message))pumpDistanceTransition();
    }
    private static boolean pumpDistanceTransition(){
        if(distanceTransition==null)return false;
        var game=Minecraft.getInstance();
        if(game.getConnection()!=distanceTransitionConnection){closeCurrent("Native distance transition disconnected");if(current==null){distanceTransition=null;distanceTransitionConnection=null;}return true;}
        if(distanceTransitionCancelled){
            boolean drained=closeCurrent("Native distance continuation cancelled");
            if(drained&&(game.level==null||!game.level.dimension().equals(SceneCommands.CARRIER))){distanceTransition=null;distanceTransitionConnection=null;distanceRestartApprovedEpoch=0;distanceTransitionCancelled=false;}
            return true;
        }
        if(distanceTransition.failed()){closeCurrent("Native distance transition failed; cleanup retained");return true;}
        var changed=distanceTransition.observe(actualDistanceSelection());if(changed!=null){if(distanceProgress!=null)distanceProgress.request(changed);PacketDistributor.sendToServer(changed);}
        if(distanceTransition.waitingForDrain()&&closeCurrent("Draining old native distance resources")){
            if(NativeIrisShaderStages.restorationPending())return true;
            var receipt=distanceTransition.physicallyDrained();if(receipt!=null){if(distanceProgress!=null)distanceProgress.credit(NativeDistanceProgressWatchdog.Lane.CLIENT_DRAINED,1);PacketDistributor.sendToServer(receipt);EarthPlatformMod.LOGGER.info("NATIVE_DISTANCE_CLIENT_DRAINED {}",receipt);}
        }
        return true;
    }
    /** Exact pause edge, emitted immediately after Minecraft assigns its actual pause state. */
    @SubscribeEvent public static void distancePause(ClientPauseChangeEvent.Post event){
        if(distanceProgress!=null){
            var progress=distanceProgress;var game=Minecraft.getInstance();
            NativeDistanceProgressState.observeActualPause(game.getSingleplayerServer(),game.getConnection(),progress.watchdog.episode(),event.isPaused());
            boolean edge=progress.checkpoint().paused()!=event.isPaused();long now=System.nanoTime();
            progress.observe(now,event.isPaused());
            if(edge&&progress.checkpointDue(now,true))EarthPlatformMod.LOGGER.info("NATIVE_DISTANCE_PROGRESS_PAUSE {}",new com.google.gson.Gson().toJson(progress.checkpoint()));
        }
    }
    public static void nativeDistanceProgress(NativeDistanceProgressPayload payload){
        var game=Minecraft.getInstance();if(!game.isSameThread()){game.execute(()->nativeDistanceProgress(payload));return;}
        var progress=distanceProgress;if(progress==null||!payload.server()||game.getConnection()!=distanceProgressConnection)return;
        progress.observe(System.nanoTime(),game.isPaused());
        if(progress.watchdog.failed()){failDistanceProgress();return;}
        progress.accept(payload); // Exact current epoch/serial/resource/publication/job only; stale ACKs do nothing.
        if(progress.watchdog.failed())failDistanceProgress();
    }
    private static boolean pumpDistanceProgress(){
        var progress=distanceProgress;if(progress==null)return false;
        var game=Minecraft.getInstance();long now=System.nanoTime();
        if(game.level!=null&&!game.level.dimension().equals(SceneCommands.CARRIER)&&current==null){
            progress.watchdog.fail("left-carrier");lastDistanceProgress=progress.checkpoint();
            NativeDistanceProgressState.revokePauseClock(distanceProgressServer,distanceProgressConnection,progress.watchdog.episode());
            distanceProgress=null;distanceProgressTransition=null;distanceProgressConnection=null;distanceProgressServer=null;distanceTransition=null;distanceTransitionConnection=null;distanceRestartApprovedEpoch=0;distanceTransitionCancelled=false;return false;
        }
        progress.observe(now,game.isPaused()); // Expire before sampling or accepting any late completion.
        if(game.getConnection()==null||game.getConnection()!=distanceProgressConnection)progress.watchdog.fail("disconnected");
        if(progress.watchdog.failed()){failDistanceProgress();return true;}
        var s=current;
        if(s!=null&&!s.closed&&!s.disposed&&s.distanceMode()&&s.epoch==progress.actorEpoch){
            String job=progress.plan;
            var work=s.visualStream==null?null:s.visualStream.progress();
            if(work!=null){
                var stamp=work.stamp();
                if(stamp.sessionEpoch()!=s.epoch||stamp.resourceGeneration()!=s.guard.resourceGeneration()
                        ||stamp.selectedDistanceChunks()!=progress.request.selected()||stamp.effectiveDistanceChunks()!=progress.request.effective()
                        ||stamp.serverDistanceLimit()!=progress.request.serverLimit()||!stamp.world().equals(s.world)
                        ||stamp.visualGeneration()!=s.visualRequest||s.proposedReplacement==null||s.proposedCertificate==null
                        ||!work.center().equals(s.replica.stagedMetadata()==null?s.replica.currentCenter():s.replica.stagedMetadata().candidateCenter())
                        ||!stamp.captureIdentity().equals(s.proposedReplacement.data().capture().earthCapture().contentIdentity())
                        ||!work.captureIdentity().equals(stamp.captureIdentity())||stamp.durableRevision()!=s.proposedCertificate.originalRevision()
                        ||!stamp.storeIdentity().toString().equals(s.proposedCertificate.storeIdentity())){
                    progress.watchdog.fail("native-work-identity-changed");failDistanceProgress();return true;
                }
                job=stamp.identity();
            }
            if(!progress.header(s.epoch,s.guard.resourceGeneration(),s.publication,s.digest,job)){progress.watchdog.fail("native-progress-header-changed");failDistanceProgress();return true;}
            if(s.visualCaptureOwner!=null)progress.credit(NativeDistanceProgressWatchdog.Lane.CAPSULE_CALLBACKS,s.visualCaptureOwner.completedCallbacks());
            progress.credit(NativeDistanceProgressWatchdog.Lane.NATIVE_MESH_CALLBACKS,s.meshWork.metrics().reportedCallbacks());
            progress.credit(NativeDistanceProgressWatchdog.Lane.BODY_EQUALITY_COMPARISONS,s.bodyEqualityCompletedComparisons);
            progress.credit(NativeDistanceProgressWatchdog.Lane.REPLACEMENT_READS,s.replacementCaptureReads);
            if(work!=null){
                progress.credit(NativeDistanceProgressWatchdog.Lane.SOURCE_COLUMNS,work.sampledColumnsDone());
                progress.credit(NativeDistanceProgressWatchdog.Lane.PLAN_TILES,work.planTilesDone());
                progress.credit(NativeDistanceProgressWatchdog.Lane.HALO_ROWS,work.haloRowsDone());
                progress.credit(NativeDistanceProgressWatchdog.Lane.FACE_SCANS,work.faceScansDone());
                progress.credit(NativeDistanceProgressWatchdog.Lane.PRODUCED_PIECES,work.producedPieces());
                progress.credit(NativeDistanceProgressWatchdog.Lane.UPLOADED_PIECES,work.uploadedPieces());
            }
            if(s.far!=null){
                var diagnostics=s.far.diagnostics();
                // A fresh continuation View has exactly one initial ticket before this episode ends.
                // Reject any retry/generation replacement: lifetime upload bytes then equal this job's bytes.
                if(s.preparedCoverageGeneration>1||diagnostics.has("highestRequestedCoverageGeneration")&&diagnostics.get("highestRequestedCoverageGeneration").getAsLong()>1
                        ||diagnostics.has("commits")&&diagnostics.get("commits").getAsLong()>1){progress.watchdog.fail("dh-progress-job-replaced");failDistanceProgress();return true;}
                if(diagnostics.has("uploadedBufferBytes"))progress.credit(NativeDistanceProgressWatchdog.Lane.DH_UPLOADED_BYTES,diagnostics.get("uploadedBufferBytes").getAsLong());
            }
            s.status="Rebuilding native distance "+progress.request.selected()+" | active "+(progress.watchdog.activeNanos()/1_000_000_000L)+"s, useful idle "+(progress.watchdog.usefulIdleNanos()/1_000_000_000L)+"s; /earth leave remains available";
        }
        if(progress.watchdog.failed()){failDistanceProgress();return true;}
        if(progress.checkpointDue(now,false))EarthPlatformMod.LOGGER.info("NATIVE_DISTANCE_PROGRESS_CHECKPOINT {}",new com.google.gson.Gson().toJson(progress.checkpoint()));
        if(progress.sendDue(now)){var packet=progress.packet(0);if(packet==null){failDistanceProgress();return true;}PacketDistributor.sendToServer(packet);}
        return false;
    }
    private static void failDistanceProgress(){
        var progress=distanceProgress;if(progress==null)return;
        if(!progress.failureReported){
            progress.failureReported=true;lastDistanceProgress=progress.checkpoint();
            EarthPlatformMod.LOGGER.error("NATIVE_DISTANCE_PROGRESS_FAILED {}",new com.google.gson.Gson().toJson(lastDistanceProgress));
            if(Minecraft.getInstance().getConnection()==distanceProgressConnection)PacketDistributor.sendToServer(progress.request.failed());
            NativeDistanceProgressState.revokePauseClock(distanceProgressServer,distanceProgressConnection,progress.watchdog.episode());
            if(distanceProgressTransition!=null)distanceProgressTransition.fail();
            if(current!=null)current.report("DISTANCE_PROGRESS_FAILED");
        }
        closeCurrent("Native distance rebuild blocked: "+progress.watchdog.failure()+"; cleanup retained, /earth leave returns safely");
    }
    private static void finishDistanceProgress(long actorEpoch){
        if(distanceProgress==null||distanceProgress.actorEpoch!=actorEpoch)return;
        lastDistanceProgress=distanceProgress.checkpoint();
        EarthPlatformMod.LOGGER.info("NATIVE_DISTANCE_PROGRESS_COMPLETED {}",new com.google.gson.Gson().toJson(lastDistanceProgress));
        NativeDistanceProgressState.revokePauseClock(distanceProgressServer,distanceProgressConnection,distanceProgress.watchdog.episode());
        distanceProgress=null;distanceProgressTransition=null;distanceProgressConnection=null;distanceProgressServer=null;
    }
    private static long playableApprovedChallenge, playableApprovedReload;
    private static ClientPacketListener playableApprovedConnection;
    public static void playableEntry(PlayableEntryPayload request) {
        var game=Minecraft.getInstance();if(!game.isSameThread()){game.execute(()->playableEntry(request));return;}
        if(request.kind()!=PlayableEntryPayload.REQUEST)return;
        if(distanceProgress!=null){distanceProgress.observe(System.nanoTime(),game.isPaused());if(distanceProgress.watchdog.failed()){failDistanceProgress();return;}}
        playableApprovedChallenge=0;playableApprovedConnection=null;
        try{
            if(!io.earthplatform.compat.dh.EarlyPlayableAdmission.enabled())throw new IllegalStateException(io.earthplatform.compat.dh.EarlyPlayableAdmission.explanation());
            if(game.level==null||game.player==null||game.getConnection()==null||game.getSingleplayerServer()==null||current!=null||NativeIrisShaderStages.restorationPending())throw new IllegalStateException("Return to an ordinary single-player world and finish cleanup first");
            var connection=game.getConnection();var level=game.level;long reload=SCENARIO_RELOAD_SEQUENCE.get();
            requireInstalledDh();var lease=io.earthplatform.compat.PlayableProfile.selection().earth()?Objects.requireNonNull(installedIris).leaseDistance():Objects.requireNonNull(installedIris).lease();if(io.earthplatform.compat.PlayableProfile.selection().earth())PlayableModelPreflight.requireEarth(game);else PlayableModelPreflight.require(game);
            if(!lease.current()||connection!=game.getConnection()||level!=game.level||reload!=SCENARIO_RELOAD_SEQUENCE.get())throw new IllegalStateException("Resources changed during playable preflight");
            if(distanceTransition!=null&&!distanceTransition.permitsPreflight())throw new IllegalStateException("Prior native distance has not physically drained");
            if(distanceProgress!=null){distanceProgress.observe(System.nanoTime(),game.isPaused());if(distanceProgress.watchdog.failed()){failDistanceProgress();return;}}
            playableApprovedChallenge=request.challenge();playableApprovedReload=reload;playableApprovedConnection=connection;
            if(distanceTransition!=null){distanceRestartApprovedEpoch=request.challenge();if(distanceProgress!=null)distanceProgress.header(request.challenge(),0,0,"","");}
            PacketDistributor.sendToServer(new PlayableEntryPayload(request.challenge(),PlayableEntryPayload.READY,reload,""));
        }catch(RuntimeException|LinkageError failure){String reason=failure.getMessage();if(reason==null)reason=failure.getClass().getSimpleName();PacketDistributor.sendToServer(new PlayableEntryPayload(request.challenge(),PlayableEntryPayload.REFUSED,SCENARIO_RELOAD_SEQUENCE.get(),reason.substring(0,Math.min(256,reason.length()))));}
    }
    private static JsonObject shaderTextureAliasEvidence() {
        var evidence = io.earthplatform.minecraft.scene.NativeShaderTextureAliases.evidence();
        JsonObject json = new com.google.gson.Gson().toJsonTree(evidence).getAsJsonObject();
        json.addProperty("logicalSlotCount", io.earthplatform.minecraft.scene.NativeShaderTextureAliases.SLOT_COUNT);
        json.addProperty("lastClearedSlotMaskLoHex", String.format(java.util.Locale.ROOT, "%016x", evidence.lastClearedSlotMaskLo()));
        json.addProperty("lastClearedSlotMaskHiHex", String.format(java.util.Locale.ROOT, "%016x", evidence.lastClearedSlotMaskHi()));
        return json;
    }

    private static JsonObject tempFileShimEvidence() {
        var evidence=io.earthplatform.compat.neoforge.NamesOnlyTempFiles.evidence();
        JsonObject json=new com.google.gson.Gson().toJsonTree(evidence).getAsJsonObject();
        json.addProperty("requested",Boolean.getBoolean(io.earthplatform.compat.neoforge.TempFileShimActivation.PROPERTY)||io.earthplatform.compat.dh.EarlyPlayableAdmission.enabled());
        json.addProperty("neoForgeVersion",io.earthplatform.compat.neoforge.TempFileShimActivation.NEOFORGE_VERSION);
        json.addProperty("dhVersion",io.earthplatform.compat.neoforge.TempFileShimActivation.DH_VERSION);
        json.addProperty("scope","pinned shared server temp-file enumeration; call counts are not save success");
        return json;
    }

    public static JsonObject installedDhStatus(){
        if(installedResolver==null)installedResolver=new InstalledDhResolver(installedCatalogue());
        var result=installedResolver.resolve();var j=new JsonObject();var game=Minecraft.getInstance();
        j.addProperty("status",result.status().name());j.addProperty("explanation",result.explanation());
        j.addProperty("fromNeoForgeModList",true);j.addProperty("actualFrame",actualRenderFrame);
        j.addProperty("level",game.level==null?"none":game.level.dimension().location().toString());
        j.addProperty("installedBackendOptIn",Boolean.getBoolean("earthplatform.experimental.installedDhBackend")||io.earthplatform.compat.dh.EarlyPlayableAdmission.enabled());
        j.addProperty("playableProfile",io.earthplatform.compat.PlayableProfile.selection().state().name());j.addProperty("playableProfileId",io.earthplatform.compat.PlayableProfile.selection().profile());j.addProperty("playableProfileExplanation",io.earthplatform.compat.dh.EarlyPlayableAdmission.explanation());
        j.addProperty("isolatedTestOptIn",Boolean.getBoolean("earthplatform.test.isolatedDhRuntime"));
        j.add("tempFileShim",tempFileShimEvidence());
        j.addProperty("installedShaderOptIn",shaderMode());if(shaderMode())j.add("shaderTextureAliases",shaderTextureAliasEvidence());j.addProperty("installedIrisArtifactVerified",installedIris!=null&&installedIris.installationVerified());
        j.addProperty("shaderRequiredIrisVersion",InstalledIrisRuntime.IRIS_VERSION);j.addProperty("shaderRequiredSodiumVersion",InstalledIrisRuntime.SODIUM_VERSION);j.addProperty("shaderRequiredPack",InstalledIrisRuntime.PHOTON_NAME);j.addProperty("effectiveNativeRenderDistance",game.options.getEffectiveRenderDistance());
        if(result.runtime()!=null){var runtime=result.runtime();
            j.addProperty("version",runtime.installation().version());j.addProperty("apiVersion",InstalledDhResolver.API);
            j.addProperty("artifactSha256",InstalledDhResolver.SHA256);j.addProperty("module",runtime.apiClass().getModule().getName());
            j.addProperty("classLoader",runtime.apiClass().getClassLoader().getClass().getName());
        }
        var s=current;j.addProperty("retainedSession",s!=null);j.addProperty("afterLevelSubmissions",0);
        if(s!=null){j.addProperty("epoch",s.epoch);j.addProperty("resourceGeneration",s.guard.resourceGeneration());
            j.addProperty("installedCallbackFrames",s.installedCallbackFrames);j.addProperty("installedLastFrame",s.lastInstalledFrame);
            j.addProperty("eventBindings",s.installedEvents==null?0:s.installedEvents.retainedBindings());
            if(s.installedGate!=null)j.add("carrier",new com.google.gson.Gson().toJsonTree(s.installedGate.counters()));
            if(s.shaderCarrier!=null){j.add("shaderCarrier",new com.google.gson.Gson().toJsonTree(s.shaderCarrier.counters()));j.addProperty("shaderPipelineCurrent",s.shaderCarrier.pipelineCurrent());j.addProperty("shaderDepthValidatedPasses",s.shaderCarrier.depthValidatedPasses());j.add("shaderDepthReceipt",new com.google.gson.Gson().toJsonTree(s.shaderCarrier.depthReceipt()));j.addProperty("shaderRequiredProjectionUniform","dhProjection");j.addProperty("shaderRequiredModelViewUniform","iris_ModelViewMatrix");}
        }else{j.addProperty("eventBindings",0);j.addProperty("ownedCarrier",false);}
        return j;
    }
    /** This identity comes from NeoForge's once-per-client-frame event, never a DH callback count. */
    @SubscribeEvent public static void frame(RenderFrameEvent.Pre event){
        actualRenderFrame=Math.incrementExact(actualRenderFrame);
        if(pumpDistanceProgress())return;
        if(pumpDistanceTransition())return;
        var s=current;if(s==null)return;
        try{if(!s.installedCurrent()||(!s.shaderMode&&!InstalledDhEvents.supportedMode())){
            s.invalidate();closeCurrent("Installed DH ownership or supported renderer mode changed; session fenced");
        }}catch(RuntimeException|Error failure){EarthPlatformMod.LOGGER.error("INSTALLED_DH_FRAME_FENCE_FAILED",failure);s.invalidate();closeCurrent("Installed DH frame validation failed");}
    }
    private static String stopped="/earth_roam enter starts the rolling cubic engine gate";
    /** Probe admission stays closed until every retained gameplay cleanup owner is released. */
    public static boolean hasRetainedSession(){return current!=null;}
    public static void handle(RollingGatePayload payload){
        var game=Minecraft.getInstance();
        if(!game.isSameThread()||!RollingGateFixture.enabled()||game.getSingleplayerServer()==null)throw new IllegalStateException("Integrated rolling gate required");
        if(distanceProgress!=null){distanceProgress.observe(System.nanoTime(),game.isPaused());if(distanceProgress.watchdog.failed()){failDistanceProgress();return;}}
        var b=payload.open();try{
            int kind=b.readUnsignedByte();long epoch=b.readLong();
            if(distanceTransition!=null&&kind==RollingGatePayload.METADATA&&(epoch!=distanceRestartApprovedEpoch||!distanceTransition.permitsPreflight()))return;
            if(distanceTransition!=null&&epoch==distanceTransition.epoch()){if(kind==RollingGatePayload.STOP){end(b);distanceTransitionCancelled=true;distanceTransition.fail();pumpDistanceTransition();}return;}
            if(lastConnection!=game.getConnection()){lastConnection=game.getConnection();lastEpoch=0;}
            // Obsolete envelopes cannot decode/admit data or tear down a newer live session.
            if(epoch<lastEpoch)return;
            if(kind==RollingGatePayload.METADATA){
                long publication=b.readLong();var world=CoreGatePayload.world(b);
                var palette=EnginePaletteContext.create(Objects.requireNonNull(game.level).registryAccess().registryOrThrow(Registries.BIOME));
                var profile=RollingSessionDescriptor.read(b,world,palette);var metadata=RollingGateWire.readMetadata(b,world,epoch);end(b);
                if(metadata.initial()){
                    if(epoch<=lastEpoch)return;
                    if(!closeCurrent("Replacing rolling view"))return;
                    lastEpoch=epoch;
                    if(distanceTransition==null){visualRecoveryCount=0;visualRecoveryReason="";}
                    if(distanceTransition!=null){if(epoch!=distanceRestartApprovedEpoch||!distanceTransition.permitsPreflight())throw new IllegalStateException("Unapproved native distance continuation");distanceTransition=null;distanceTransitionConnection=null;distanceRestartApprovedEpoch=0;distanceTransitionCancelled=false;}
                    if(io.earthplatform.minecraft.lod.DhLongDistanceGpuProbe.hasRetainedResources()){
                        stopped="Depth probe cleanup is pending; retry the probe cleanup before entering the core";
                        PacketDistributor.sendToServer(new RollingGateControlPayload(epoch,publication,1,RollingGateControlPayload.FENCED,"",false));
                        game.gui.getChat().addMessage(Component.literal(stopped));return;
                    }
                    try{current=new Session(world,epoch,palette,profile,requireInstalledDh());}
                    catch(RuntimeException|Error failure){
                        try{PacketDistributor.sendToServer(new RollingGateControlPayload(epoch,publication,1,RollingGateControlPayload.FENCED,"",false));}
                        catch(RuntimeException|Error notification){if(notification!=failure)failure.addSuppressed(notification);}throw failure;
                    }
                    current.initialize();
                }
                var s=current;if(s==null||s.epoch!=epoch)return;
                if(!s.world.equals(world)||!s.profile.equals(profile))throw new IllegalArgumentException("Changed rolling identity");
                synchronized(s.fence){if(!s.valid()||s.guard.invalidationRequested()){closeCurrent("Ignoring metadata for a fenced rolling session");return;}s.metadata(publication,metadata);}return;
            }
            var s=current;if(s==null||s.epoch!=epoch)return;
            synchronized(s.fence){
                // A retained cleanup owner is not an active protocol recipient. STOP may retry cleanup.
                if(s.closed||s.disposed){if(kind==RollingGatePayload.STOP)closeCurrent("Retrying rolling cleanup");return;}
                if(!s.valid()||s.guard.invalidationRequested()){closeCurrent("Ignoring frame for a fenced rolling session");return;}
                switch(kind){
                case RollingGatePayload.EARTH_BOOTSTRAP_SNAPSHOT -> {long generation=b.readLong();var snap=EngineCubeSnapshot.readFrame(b,s.world,epoch,s.palette);end(b);s.earthBootstrap(generation,snap);}
                case RollingGatePayload.SNAPSHOT -> {long generation=b.readLong();var snap=EngineCubeSnapshot.readFrame(b,s.world,epoch,s.palette);end(b);s.snapshot(generation,snap);}
                case RollingGatePayload.READY -> {long publication=b.readLong();var ready=RollingGateWire.readReady(b,s.world,epoch);end(b);s.ready(publication,ready);}
                case RollingGatePayload.REPLACEMENT_FALLBACK -> {long oldPublication=b.readLong(),nextPublication=b.readLong();var ready=RollingGateWire.readReady(b,s.world,epoch);end(b);s.replacementFallback(oldPublication,nextPublication,ready);}
                case RollingGatePayload.ALLOW -> {long publication=b.readLong(),resource=b.readLong();String digest=b.readUtf(64);end(b);s.allow(publication,resource,digest);}
                case RollingGatePayload.COMMITTED -> {long publication=b.readLong(),resource=b.readLong();String digest=b.readUtf(64);end(b);s.committed(publication,resource,digest);}
                case RollingGatePayload.EDIT_BEGIN -> {long publication=b.readLong(),x=b.readLong(),y=b.readLong(),z=b.readLong();end(b);s.beginEdit(publication,x,y,z);}
                case RollingGatePayload.EDIT_ACK -> {long publication=b.readLong();String status=b.readUtf(64);long revision=b.readLong();String detail=b.readUtf(512);end(b);s.ack(publication,status,revision,detail);}
                case RollingGatePayload.STOP -> {end(b);closeCurrent("Rolling server draining");}
                case RollingGatePayload.SCENARIO_CHECKPOINT -> {long publication=b.readLong(),resources=b.readLong(),coverage=b.readLong();String pair=b.readUtf(64),label=b.readUtf(64);end(b);
                    if(!(s.profile.arenaMode()&&io.earthplatform.compat.PlayableDiagnostics.enabled()||s.profile.indexedWorkspaces()&&Boolean.getBoolean(RollingGateFixture.INDEXED_SCENARIO_OPT_IN))||s.pending||!s.visible||publication!=s.publication||resources!=s.guard.resourceGeneration()||coverage!=s.coverageGeneration||!pair.equals(s.digest)||!label.matches("[a-z0-9_-]{1,64}")||!s.scenarioCapture.isEmpty()||s.scenarioScreenshots>=(s.profile.arenaMode()?RollingArenaScenario.MAX_TRANSCRIPT:18))throw new IllegalArgumentException("Stale or excessive scenario checkpoint");
                    s.scenarioCapture=label;s.scenarioCheckpoint=new RollingScenarioCheckpoint(epoch,publication,resources,coverage,pair,label);}
                case RollingGatePayload.REJECT -> {String detail=b.readUtf(512);end(b);s.intentPending=false;s.status="Server rejected: "+detail;}
                default -> throw new IllegalArgumentException("Unknown rolling frame");
            }}
        }catch(RuntimeException|Error failure){EarthPlatformMod.LOGGER.error("ROLLING_CLIENT_FAILED",failure);closeCurrent("Rolling stream failed: "+failure.getMessage());game.gui.getChat().addMessage(Component.literal(stopped));}finally{b.release();}
    }
    private static final java.util.concurrent.atomic.AtomicLong SCENARIO_RELOAD_SEQUENCE=new java.util.concurrent.atomic.AtomicLong();
    private static void closeReloadOwner(Session expected){nativeQualification=null;completedNativeOracleIdentity=null;if(expected!=null&&current==expected)closeCurrent("Resource reload closed rolling view; leave then enter");}
    private static void end(FriendlyByteBuf b){if(b.isReadable())throw new IllegalArgumentException("Trailing rolling envelope bytes");}
    private static boolean closeCurrent(String reason){stopped=reason;var s=current;if(s==null)return true;try{s.closeInstalledBridge();current=null;return true;}catch(RuntimeException|Error failure){stopped="Rolling cleanup pending; owners remain fenced";EarthPlatformMod.LOGGER.error(stopped,failure);return false;}}
    @SubscribeEvent public static void tick(ClientTickEvent.Post e){if(pumpDistanceProgress())return;if(pumpVisualRecovery())return;if(pumpDistanceTransition())return;var s=current;if(s==null)return;var g=Minecraft.getInstance();s.input.observe(g.options.keyAttack.isDown(),g.options.keyUse.isDown());if(!s.valid()||s.guard.invalidationRequested())closeCurrent("Rolling view closed; /earth_roam leave then enter");}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e){if(distanceProgress!=null){NativeDistanceProgressState.revokePauseClock(distanceProgressServer,distanceProgressConnection,distanceProgress.watchdog.episode());distanceProgress.watchdog.fail("disconnected");lastDistanceProgress=distanceProgress.checkpoint();distanceProgress=null;distanceProgressTransition=null;distanceProgressConnection=null;distanceProgressServer=null;}nativeQualification=null;completedNativeOracleIdentity=null;closeCurrent("Rolling disconnected");lastConnection=null;lastEpoch=0;if(current==null){distanceTransition=null;distanceTransitionConnection=null;distanceRestartApprovedEpoch=0;distanceTransitionCancelled=false;}}
    /** Installed DH is the sole engine submission callback. AFTER_LEVEL deliberately does not draw. */
    @SubscribeEvent public static void render(RenderLevelStageEvent e){if(e.getStage()!=RenderLevelStageEvent.Stage.AFTER_LEVEL)return;}
    @SubscribeEvent public static void input(InputEvent.InteractionKeyMappingTriggered e){
        var s=current;var g=Minecraft.getInstance();if(s==null||!s.valid()||g.screen!=null||(!e.isAttack()&&!e.isUseItem()))return;
        e.setCanceled(true);e.setSwingHand(false);if(!s.input.claim(e.isUseItem()))return;
        if(!g.options.getCameraType().isFirstPerson()){s.status="Mouse editing requires first-person view";return;}
        if(!s.visible||s.pending||s.intentPending||!s.collision.ready())return;
        long now=System.nanoTime();if(now-s.lastIntent<250_000_000L)return;
        s.lastIntent=now;s.intentPending=true;s.status="Validating authoritative rolling ray";
        s.send(new RollingGateControlPayload(s.epoch,s.publication,s.guard.resourceGeneration(),RollingGateControlPayload.MOUSE,"",e.isUseItem()));
    }
    @SubscribeEvent public static void hud(RenderGuiEvent.Post e){
        if(!RollingGateFixture.enabled())return;var g=Minecraft.getInstance();if(g.level==null||g.player==null||g.options.hideGui||!g.level.dimension().equals(SceneCommands.CARRIER))return;
        var s=current;var gg=e.getGuiGraphics();
        List<String> lines=s==null?List.of(stopped):List.of((s.profile.naturalRelief()?"EARTH CUBIC CORE | Copernicus + alpine detail":"ROLLING CUBIC CORE | "+s.profile.id())+" | ground Y "+s.profile.anchorY(),
            "5×5×5 collision | resident "+s.replica.cubes().size()+"/213 | meshes "+s.published.size()+"+"+s.candidate.size()+" | DH "+s.profile.lodLayout().radiusBlocks()+"m /"+s.profile.lodLayout().snapBlocks()+"m snap",
            "Center "+s.replica.currentCenter()+" | publication "+s.publication+" | focus "+s.replica.activeFocus()+" revision "+s.replica.revision()+" | pin "+(s.replica.currentPinned()?"native":"3D far"),
            "Far center "+s.publishedFarCenter+(s.preparedFarCenter==null?"":" → "+s.preparedFarCenter),
            s.presentationLabel(),s.status,(s.profile.naturalRelief()?"Earth body limit ±128m; ":"")+(s.profile.twoWorkspaces()?"Mouse: active focus cube ":"Mouse: original cube ")+(s.profile.editPolicy()==io.earthplatform.minecraft.enginebridge.RollingEditPolicy.INTERIOR_V1?"interior only":"including boundaries")+(s.profile.twoWorkspaces()?" | focus original/second":"")+" | /earth_roam walk, fly, status, leave");
        if(s!=null&&s.profile.alpineMode()){
            var pin=s.profile.pinCenter();var bounds=s.profile.bodyEnvelope();
            String build="Persistent build/dig: X"+(pin.minBlockX()+1)+".."+(pin.minBlockX()+14)+", Y"+(pin.minBlockY()+1)+".."+(pin.minBlockY()+14)+", Z"+(pin.minBlockZ()+1)+".."+(pin.minBlockZ()+14);
            double margin=Math.min(Math.min(g.player.getX()-bounds.minX(),bounds.maxX()-g.player.getX()),Math.min(g.player.getZ()-bounds.minZ(),bounds.maxZ()-g.player.getZ()));
            var earthLines=new ArrayList<String>();
            earthLines.add("EVEREST | real Copernicus elevation + WorldCover | 512m exploration / 2km view");
            earthLines.add(s.pending?"Preparing the next safe window...":s.visible?"Ready | ground "+s.profile.anchorY()+"m | edit revision "+s.replica.revision():s.status);
            earthLines.add("Native textured distance: "+g.options.renderDistance().get()+" chunks selected / "+g.options.getEffectiveRenderDistance()+" effective");earthLines.add(build);earthLines.add((margin<24?"Exploration boundary nearby. ":"")+"Outside the small build area is read-only. /earth walk, fly, leave");
            if(io.earthplatform.compat.PlayableDiagnostics.enabled()){earthLines.add("Center "+s.replica.currentCenter()+" | pair "+s.publication+" | "+s.presentationLabel());earthLines.add(s.status);}
            lines=List.copyOf(earthLines);
        }
        if(s!=null&&s.profile.arenaMode()&&!io.earthplatform.compat.PlayableDiagnostics.enabled()){
            boolean inside=s.profile.permitsEdit((long)Math.floor(g.player.getX()),(long)Math.floor(g.player.getY()),(long)Math.floor(g.player.getZ()));
            double edge=Math.max(Math.abs(g.player.getX()),Math.abs(g.player.getZ()));
            lines=List.of("CUBIC LANDSCAPE | 512m exploration | 4km horizon",s.pending?"Loading the next safe window...":s.visible?"Ready | "+(inside?"Build arena: left dig, right diorite":"Read-only landscape; white-marked arena at X/Z0..32, Y128..160"):s.status,
                (edge>240?"Boundary nearby: ":"")+"X/Z limits ±256m | /cubic leave returns");
        }
        if(distanceProgress!=null){
            var progress=distanceProgress;var raw=progress.watchdog.rawVector();var visibleProgress=new ArrayList<String>();
            visibleProgress.add((progress.watchdog.failed()?"Rebuild blocked: "+progress.watchdog.failure():"Rebuilding "+progress.request.selected()+" native chunks")
                    +" | active "+progress.watchdog.activeNanos()/1_000_000_000L+"s | useful idle "+progress.watchdog.usefulIdleNanos()/1_000_000_000L+"s | /earth leave");
            visibleProgress.add("Completed source columns "+raw[NativeDistanceProgressWatchdog.Lane.SOURCE_COLUMNS.ordinal()]+", tiles "+raw[NativeDistanceProgressWatchdog.Lane.PLAN_TILES.ordinal()]
                    +", halo rows "+raw[NativeDistanceProgressWatchdog.Lane.HALO_ROWS.ordinal()]+", face scans "+raw[NativeDistanceProgressWatchdog.Lane.FACE_SCANS.ordinal()]+", uploaded pieces "+raw[NativeDistanceProgressWatchdog.Lane.UPLOADED_PIECES.ordinal()]);
            visibleProgress.addAll(lines);lines=visibleProgress;
        }
        int w=Math.max(30,gg.guiWidth()-12);List<net.minecraft.util.FormattedCharSequence> wrapped=new ArrayList<>();for(var line:lines)wrapped.addAll(g.font.split(Component.literal(line),w-10));
        int h=wrapped.size()*(g.font.lineHeight+2)+8,y=g.getDebugOverlay().showDebugScreen()?Math.max(6,gg.guiHeight()-h-6):6;
        gg.fill(6,y,6+w,y+h,0xDD101820);gg.enableScissor(6,y,6+w,Math.min(gg.guiHeight(),y+h));
        for(int i=0;i<wrapped.size();i++)gg.drawString(g.font,wrapped.get(i),11,y+4+i*(g.font.lineHeight+2),0xE5F0F6);gg.disableScissor();
        if(g.options.getCameraType().isFirstPerson()){int x=gg.guiWidth()/2,z=gg.guiHeight()/2;gg.fill(x-4,z,x+5,z+1,-1);gg.fill(x,z-4,x+1,z+5,-1);}gg.flush();if(s!=null)try{s.afterHud();}catch(RuntimeException|Error failure){EarthPlatformMod.LOGGER.error("ROLLING_SCENARIO_SCREENSHOT_FAILED",failure);closeCurrent("Scenario screenshot failed; session fenced");}
    }
    @EventBusSubscriber(modid=EarthPlatformMod.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class Resources {@SubscribeEvent public static void register(RegisterClientReloadListenersEvent e){e.registerReloadListener((barrier,manager,prepare,apply,background,game)->{SCENARIO_RELOAD_SEQUENCE.incrementAndGet();var s=current;if(s!=null)s.invalidate();return barrier.wait(null).thenRunAsync(()->closeReloadOwner(s),game);});}}

    /** Static construction prevents a late far worker from retaining a client Session/Level. */
    private static SceneSurfaceSampler countedSampler(SceneSurfaceSampler earth,String identity,long analyticHeight,
            java.util.concurrent.atomic.AtomicLong heights,java.util.concurrent.atomic.AtomicLong colors){
        return new SceneSurfaceSampler(){
            public long surfaceY(long x,long z){heights.incrementAndGet();return earth==null?analyticHeight:earth.surfaceY(x,z);}
            public int argb(long x,long z,long y){colors.incrementAndGet();return earth==null?0xFF808080:earth.argb(x,z,y);}
            public String identity(){return identity;}
            public boolean supportsConcurrentReads(){return earth==null||earth.supportsConcurrentReads();}
            public boolean supportsFootprintCoverage(){return earth==null||earth.supportsFootprintCoverage();}
            public boolean supportsStratifiedSides(){return earth!=null&&earth.supportsStratifiedSides();}
            public SceneSurfaceSampler.ColumnMaterials columnMaterials(long x,long z,long y){return earth==null?SceneSurfaceSampler.super.columnMaterials(x,z,y):earth.columnMaterials(x,z,y);}
            public boolean knownFootprint(long minX,long minZ,long maxXExclusive,long maxZExclusive){
                if(minX>=maxXExclusive||minZ>=maxZExclusive)throw new IllegalArgumentException("Nonempty far footprint required");
                return earth==null||earth.knownFootprint(minX,minZ,maxXExclusive,maxZExclusive);
            }
        };
    }

    private static final class Session implements AutoCloseable {
        final RollingReplacementAuthority replacementAuthority;
        final RollingReplacementSetAuthority setAuthority;
        final RollingArenaAuthority arenaAuthority;
        ArenaCertificate proposedArena,publishedArena;
        DhArenaInput proposedArenaInput,arenaInput;
        String publishedArenaIdentity;
        ReplacementSetCertificate proposedSet,publishedSet;
        DhReplacementSetInput proposedSetInput,setInput;
        String publishedSetIdentity;
        ReplacementCertificate proposedCertificate,publishedCertificate;
        DhReplacementInput proposedReplacement,replacementInput;
        RollingReplacementCapture replacementCapture;
        RollingEarthCaptureValidation earthCaptureValidation;
        RollingEarthResumeBootstrap earthResumeBootstrap;
        io.earthplatform.minecraft.enginebridge.EarthReplacementCapture proposedEarthCapture;
        boolean replacementDeclineSent;long replacementCaptures,replacementCaptureReads,replacementDeclines;
        final WorkTiming replacementCaptureTiming=new WorkTiming();
        final Object fence=new Object();final RollingDepthComposition depthComposition=new RollingDepthComposition();
        final RenderSubmissionProbe submissionProbe=new RenderSubmissionProbe(Boolean.getBoolean("earthplatform.experimental.renderSubmissionProbe"));
        final boolean nativePhaseAttribution=Boolean.getBoolean("earthplatform.experimental.nativeSubmissionPhases")&&Boolean.getBoolean("earthplatform.experimental.renderSubmissionProbe");
        long submissionFrame,submittedNativeVertices,submittedFarVertices,publishedPairPublication=-1;int submittedNativeOwners,submittedFarOwners;
        final RollingPublishedPairIdentity publishedPairIdentity=new RollingPublishedPairIdentity();
        final InstalledDhResolver.Runtime installedRuntime;
        final boolean shaderMode=RollingGateClient.shaderMode();
        final NativeVisualGpuBatch visualBatch=new NativeVisualGpuBatch();
        final List<NativeDistanceCpuBudget.Reservation> visualCpuReservations=new ArrayList<>();
        NativeVisualCapsuleCapture visualCaptureOwner;
        NativeCapsuleWorkSlice visualCaptureWork;
        NativeCapsuleWorkSlice.Stats lastCapsuleOwnerSlice;
        NativeVisualCapsuleCapture.Sealed visualCapsule;
        NativeVisualStream visualStream;
        NativeVisualPublication proposedVisual,publishedVisual;
        Matrix4f observedNativeProjection;
        long visualRequest,visualPumpedFrame=-1,visualCommittedPublication,visualFrontierSequence;
        final RollingVisualFrontier visualFrontier;
        boolean visualRecoveryQueued;String visualRecoveryDetail="";
        String visualRequestKey="";
        final WorkTiming visualPublicationTiming=new WorkTiming();
        final WorkTiming capsuleCaptureAndSealTiming=new WorkTiming(),capsuleSealTiming=new WorkTiming();
        long maxCapsuleOwnerOvershootNanos;
        NativeVisualPageOracle visualOracle;
        int visualOracleIndex;
        boolean visualOracleComplete,visualOracleReused;
        RollingDistanceBodyBinding.Owner bodyOwner;
        net.minecraft.world.level.block.state.BlockState[] bodyPaletteStates;
        int[] bodyPaletteIds;int bodyPaletteRegistrySize;boolean bodyPaletteRevoked;
        RollingDistanceBodyBinding.Binding candidateBody,publishedBody;
        RollingDistanceBodyBinding.NativeJoined candidateBodyJoined,publishedBodyJoined;
        RollingDistanceBodyBinding.History inspectedBodyHistory;
        RollingDistanceBodyBinding.Slice lastBodyEqualitySlice;
        RollingDistanceBodyBinding.ResourceGuardMetrics lastBodyResourceGuardMetrics;
        long bodyEqualityCompletedComparisons,bodyEqualityComparedCells,bodyEqualitySlices,maxBodyEqualityOvershootNanos;
        final WorkTiming bodyEqualityTiming=new WorkTiming();
        final RollingEditPresentation<RollingDistanceBodyBinding.History,DhPreparedLodView.PresentationTicket> distancePresentation;
        final Map<SceneMesh,NativeVisualBudget.Reservation> visualBodyReservations=new IdentityHashMap<>();
        NativeVisualBudget.Reservation buildingBodyReservation;
        boolean distanceMode(){return shaderMode&&profile.alpineMode()&&profile.playerFacing();}
        final SceneNativeShaderContract.Lifecycle shaderNativeLifecycle=new SceneNativeShaderContract.Lifecycle();
        SceneNativeShaderContract.Capture shaderNativeContract;
        IrisShaderFarBackend shaderFarBackend;InstalledShaderCarrier shaderCarrier;
        long shaderPreparedFrame=-1,shaderDrawFrame=-1,shaderNativeSubmissions,shaderFarSubmissions;
        int shaderMainDrawMask;String shaderFrameDigest;boolean shaderFrameHistorical;
        final long[] shaderPassSubmissions=new long[DhShaderPassGate.Pass.values().length];
        final long[] visualDrawCalls=new long[DhShaderPassGate.Pass.values().length],visualFullDrawCalls=new long[DhShaderPassGate.Pass.values().length],visualRangeDrawCalls=new long[DhShaderPassGate.Pass.values().length];
        DhCarrierRenderGate installedGate;InstalledDhEvents installedEvents;DhCarrierRenderGate.Lease installedLease;
        long installedCallbackFrames,lastInstalledFrame=-1;String installedCallbackFailure="";boolean installedClosureReported,nativeRefusalReported;
        final WorldIdentity world;final long epoch;final long entryResourceSequence;final EnginePaletteContext palette;final RollingSessionDescriptor profile;
        final ClientLevel carrier;final ClientPacketListener connection;final CoreInputLatch input;final RollingGateReplica replica;final RenderPublicationGuard guard;
        final Map<CubePos,SceneMesh> published=new LinkedHashMap<>(),candidate=new LinkedHashMap<>();
        final Set<SceneMesh> owned=Collections.newSetFromMap(new IdentityHashMap<>());
        // Private authority bindings share existing physical VBOs; old frozen sources remain unchanged.
        final Map<SceneMesh,SceneMesh.Reuse> nativeReuses=new IdentityHashMap<>();
        final Deque<CubePos> nativeReuseQueue=new ArrayDeque<>();
        final List<CubePos> nativeRebuildTargets=new ArrayList<>();
        boolean nativeClassificationComplete;
        int peakNativeReuseQueue,closingNativeReuseQueue;
        final RollingMeshWorkQueue<SceneMesh.Build,SceneMesh> meshWork;
        // Inspection aliases only; the queue/presentation helper remain the sole cleanup owners.
        SceneMesh.Build inspectedNativeBuild;
        RenderPublicationGuard.PresentationLease inspectedNativePresentation;
        long peakObservedRetainedSnapshotIdentities,peakObservedRetainedSnapshotBytes;
        final RollingEditPresentation<RenderPublicationGuard.PresentationLease,DhPreparedLodView.PresentationTicket> presentation;
        final WorkTiming nativeReusePreparation=new WorkTiming(),nativeReuseCommit=new WorkTiming();
        long nativeStrictReused,nativeFootprintPrepared,nativeFootprintRejected,nativeFootprintCommitted,nativeReuseComparedValues;
        long peakNativeFootprintAccountedBytes;
        int lastNativeStrictReused,lastNativeFootprintPrepared,lastNativeRebuildTargets;
        final WorkTiming farInitialization=new WorkTiming(),farPreparation=new WorkTiming(),farPump=new WorkTiming(),farCommit=new WorkTiming(),pairInstallation=new WorkTiming(),updateWork=new WorkTiming();
        BoundedProviderPreparation.Ticket<RollingEarthPreparation.Prepared> sourcePreparation;
        RollingEarthPreparation.Prepared preparedSource;
        long nextSourceAttemptNanos,sourcePrepareNanos;String sourceWorker="",sourceUrl="analytic fixture",sourceDatum="native fixture";
        boolean sourceVerified,closingSourcePreparationPending;
        CoreCollisionBinding collision;DynamicTexture light;DhPreparedLodView far;DhPreparedLodView.CoverageTicket preparedFar;long preparedCoverageGeneration;
        boolean closed,disposed,visible,pending,edit,preparedSent,allowed,pairSent,intentPending,drawn;
        boolean closingPublicationPending,closingPreparedSent;int closingQueuedTargets=-1,closingActiveBuilds=-1,closingCompletedSlots=-1;
        RollingMeshWorkQueue.Phase lastWorkPhase=RollingMeshWorkQueue.Phase.IDLE;
        long nativeWorkPumpedFrame=-1;
        RollingMeshWorkQueue.SliceStats lastNativeOwnerSlice;
        final java.util.concurrent.atomic.AtomicLong sourceHeightCallbacks=new java.util.concurrent.atomic.AtomicLong(),sourceColorCallbacks=new java.util.concurrent.atomic.AtomicLong();
        RollingFarPublication.Center desiredFarCenter,preparedFarCenter,publishedFarCenter;
        String nativeManifestDigest="";
        long publication,coverageGeneration,acceptedSnapshots,peakResident,peakMeshes,peakMeshReservations,maxUpdateNanos,lastIntent,lastEditX=4,lastEditY,lastEditZ=4;int reports,screenshots;
        String scenarioCapture="";RollingScenarioCheckpoint scenarioCheckpoint;int scenarioScreenshots;
        String digest="",status="Opening rolling replica; collision and interactions fenced",capture="",frozenCapture="";
        boolean frozenDrawReported,frozenStaleReported;long frozenDrawFrames,frozenStaleRevisionFrames;Vec3 camera=Vec3.ZERO;
        Session(WorldIdentity world,long epoch,EnginePaletteContext palette,RollingSessionDescriptor profile,InstalledDhResolver.Runtime installedRuntime){
            this.installedRuntime=Objects.requireNonNull(installedRuntime);
            entryResourceSequence=profile.playerFacing()?playableApprovedReload:SCENARIO_RELOAD_SEQUENCE.get();
            if(profile.playerFacing()){
                if(profile.alpineMode()!=io.earthplatform.compat.PlayableProfile.selection().earth())throw new IllegalArgumentException("Server profile differs from selected client Earth/arena profile");
                if(epoch!=playableApprovedChallenge||Minecraft.getInstance().getConnection()!=playableApprovedConnection||SCENARIO_RELOAD_SEQUENCE.get()!=entryResourceSequence)throw new IllegalArgumentException("Playable entry lacks its exact current client preflight");
                playableApprovedChallenge=0;playableApprovedConnection=null;
            }
            if(shaderMode&&(profile.naturalRelief()&&!profile.alpineMode()||installedIris==null))throw new IllegalArgumentException("Shader gate requires an attested opaque source/profile");
            RenderSystem.assertOnRenderThread();var g=Minecraft.getInstance();this.world=world;this.epoch=epoch;this.visualFrontier=new RollingVisualFrontier(epoch);this.palette=palette;this.profile=profile;lastEditX=profile.pinCenter().minBlockX()+4;lastEditY=profile.anchorY()+1;lastEditZ=profile.pinCenter().minBlockZ()+4;
            depthComposition.setSubmissionProbe(submissionProbe);
            carrier=Objects.requireNonNull(g.level);connection=Objects.requireNonNull(g.getConnection());input=new CoreInputLatch(g.options.keyAttack.isDown(),g.options.keyUse.isDown());
            if(!carrier.dimension().equals(SceneCommands.CARRIER)||!world.equals(profile.world()))throw new IllegalArgumentException("Rolling carrier/provider identity mismatch");
            if(profile.naturalRelief()&&!profile.alpineMode()&&!Boolean.getBoolean(RollingEarthPreparation.OPT_IN))throw new IllegalArgumentException("Earth player gate requires explicit client opt-in");
            replica=new RollingGateReplica(world,epoch,palette,profile);guard=new RenderPublicationGuard(replica.admission(),RollingCubePlan.MAX_STAGED_OWNERS);
            replacementAuthority=new RollingReplacementAuthority(world,epoch,guard.resourceGeneration(),profile.target(),profile.alpineMode()?world.datasetVersion():profile.sourceFingerprint(),palette.fingerprint(),profile.alpineMode()?io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission.MATERIAL_POLICY:DhReplacementInput.ANALYTIC_MATERIAL_POLICY,profile.alpineMode()?io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission.DETAIL_POLICY:RollingGateFixture.REPLACEMENT_DETAIL_POLICY);
            arenaAuthority=profile.arenaMode()?new RollingArenaAuthority(profile,epoch,guard.resourceGeneration()):null;
            setAuthority=profile.twoWorkspaces()?new RollingReplacementSetAuthority(world,epoch,guard.resourceGeneration(),profile.workspaces(),profile.sourceFingerprint(),palette.fingerprint(),DhReplacementInput.ANALYTIC_MATERIAL_POLICY,RollingGateFixture.REPLACEMENT_DETAIL_POLICY,profile.indexedWorkspaces()?RollingIndexedQueryGuard.policy(profile):null,profile.indexedWorkspaces()?profile.lodLayout().identity():null):null;
            meshWork=new RollingMeshWorkQueue<>(new RollingMeshWorkQueue.Driver<SceneMesh.Build,SceneMesh>(){
                public SceneMesh.Build begin(CubePos pos){
                    if(distanceMode())throw new IllegalStateException("Distance body authority must never allocate a SceneMesh");
                    // Reserve before the candidate can acquire CPU buffers or any GPU generation.
                    if(owned.size()>=RollingCubePlan.MAX_STAGED_MESH_GENERATIONS)throw new IllegalStateException("Native mesh generation reservation exhausted");
                    peakMeshReservations=Math.max(peakMeshReservations,owned.size()+1L);
                    try{var build=SceneMesh.begin(guard,Objects.requireNonNull(replica.cubes().get(pos)),nativeNeighborhood(),shaderMode?Objects.requireNonNull(shaderNativeContract):SceneNativeShaderContract.defaultPath());inspectedNativeBuild=build;return build;}
                    catch(RuntimeException|Error failure){if(buildingBodyReservation!=null){buildingBodyReservation.releaseAfterPhysicalClose();buildingBodyReservation=null;}throw failure;}
                }
                public RollingMeshWorkQueue.StepStats step(SceneMesh.Build build,int maxCallbacks,long softNanos){
                    if(distanceMode())throw new IllegalStateException("Distance body cannot step a SceneMesh");
                    var v=build.step(maxCallbacks,softNanos);
                    return new RollingMeshWorkQueue.StepStats(v.blocks(),v.completedBlocks(),v.totalBlocks(),v.elapsedNanos(),v.budgetNanos(),v.overshootNanos(),v.maxBlockNanos(),v.complete());
                }
                public SceneMesh finish(SceneMesh.Build build){if(distanceMode())throw new IllegalStateException("Distance body cannot finish a SceneMesh");return build.finish();}
                public void closeBuild(SceneMesh.Build build){build.close();if(buildingBodyReservation!=null){buildingBodyReservation.releaseAfterPhysicalClose();buildingBodyReservation=null;}if(inspectedNativeBuild==build)inspectedNativeBuild=null;}
                public void closeMesh(SceneMesh mesh){mesh.close();releaseBodyReservation(mesh);}
            });
            presentation=new RollingEditPresentation<>(new RollingEditPresentation.Driver<RenderPublicationGuard.PresentationLease,DhPreparedLodView.PresentationTicket>(){
                public RenderPublicationGuard.PresentationLease captureNative(){var ticket=guard.retainPresentation(published.values().stream().map(SceneMesh::presentationSource).toList());inspectedNativePresentation=ticket;return ticket;}
                public DhPreparedLodView.PresentationTicket captureFar(){return Objects.requireNonNull(far).retainPresentation();}
                public boolean nativeCurrent(RenderPublicationGuard.PresentationLease t){return t.isCurrent();}
                public boolean farCurrent(DhPreparedLodView.PresentationTicket t){return far!=null&&far.isPresentationCurrent(t);}
                public void closeNative(RenderPublicationGuard.PresentationLease t){t.close();if(inspectedNativePresentation==t)inspectedNativePresentation=null;}
                public void closeFar(DhPreparedLodView.PresentationTicket t){t.close();}
            });
            distancePresentation=new RollingEditPresentation<>(new RollingEditPresentation.Driver<RollingDistanceBodyBinding.History,DhPreparedLodView.PresentationTicket>(){
                public RollingDistanceBodyBinding.History captureNative(){var ticket=Objects.requireNonNull(publishedBodyJoined).retainPresentation();inspectedBodyHistory=ticket;return ticket;}
                public DhPreparedLodView.PresentationTicket captureFar(){return Objects.requireNonNull(far).retainPresentationDistance(Objects.requireNonNull(inspectedBodyHistory));}
                public boolean nativeCurrent(RollingDistanceBodyBinding.History t){return t.isPresentable();}
                public boolean farCurrent(DhPreparedLodView.PresentationTicket t){return far!=null&&far.isPresentationCurrent(t);}
                public void closeNative(RollingDistanceBodyBinding.History t){t.close();if(inspectedBodyHistory==t)inspectedBodyHistory=null;}
                public void closeFar(DhPreparedLodView.PresentationTicket t){t.close();}
            });
        }
        RollingEditPresentation<?,?> activePresentation(){return distanceMode()?distancePresentation:presentation;}
        void captureBodyPalette(){
            if(bodyPaletteStates!=null)throw new IllegalStateException("Body palette lease already captured");
            bodyPaletteStates=new net.minecraft.world.level.block.state.BlockState[]{net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),net.minecraft.world.level.block.Blocks.DIORITE.defaultBlockState(),net.minecraft.world.level.block.Blocks.ANDESITE.defaultBlockState(),net.minecraft.world.level.block.Blocks.TUFF.defaultBlockState(),net.minecraft.world.level.block.Blocks.SNOW_BLOCK.defaultBlockState()};
            bodyPaletteIds=new int[bodyPaletteStates.length];bodyPaletteRegistrySize=net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size();
            for(int i=0;i<bodyPaletteIds.length;i++)bodyPaletteIds[i]=net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(bodyPaletteStates[i]);
            if(!bodyPaletteCurrent())throw new IllegalStateException("Frozen exact six-state registry lease required");
        }
        boolean bodyPaletteCurrent(){
            if(bodyPaletteRevoked||bodyPaletteStates==null||bodyPaletteIds==null)return false;
            boolean same=bodyPaletteRegistrySize==net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size();
            for(int i=0;same&&i<bodyPaletteIds.length;i++)same=bodyPaletteIds[i]>=0&&net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(bodyPaletteIds[i])==bodyPaletteStates[i]&&net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.getId(bodyPaletteStates[i])==bodyPaletteIds[i];
            if(!same)bodyPaletteRevoked=true;return same;
        }
        boolean bodyResourcesCurrent(){
            if(!bodyPaletteCurrent()||!valid()||guard.invalidationRequested()||visualCapsule==null||shaderNativeContract==null||!shaderNativeContract.isCurrent()||shaderCarrier==null||!shaderCarrier.pipelineCurrent())return false;
            try{visualCapsule.lease().requireCurrent();return valid()&&!guard.invalidationRequested()&&bodyPaletteCurrent();}catch(RuntimeException stale){return false;}
        }
        boolean publishedNativeCurrent(){
            if(!distanceMode())return !published.isEmpty()&&published.values().stream().allMatch(SceneMesh::isCurrent);
            if(publishedBody==null||publishedBodyJoined==null||publishedBody.joined()!=publishedBodyJoined||publishedBody.publication()!=publishedPairPublication||!publishedBodyJoined.isPublishedCurrent())return false;
            try{publishedBodyJoined.requirePrepared(publishedBodyJoined.exactReady(),publishedVisual,replacementInput);return true;}catch(RuntimeException stale){return false;}
        }
        int publishedNativeTargets(){return distanceMode()?publishedBodyJoined==null?0:27:published.size();}
        boolean installedCurrent(){return shaderMode?shaderCarrier!=null&&shaderCarrier.ownsCarrier()&&shaderCarrier.failure().isEmpty()&&(!shaderCarrier.pipelineAcquired()||shaderCarrier.pipelineCurrent()):installedGate!=null&&installedGate.isCurrent(installedLease);}
        SceneNeighborhood nativeNeighborhood(){
            if(!shaderMode)return new SceneNeighborhood(replica.cubes(),SceneRenderEnvelope.PLAYER_DIAGNOSTIC_PADDING);
            if(shaderNativeContract==null||!shaderNativeContract.isCurrent())throw new IllegalStateException("Current attested native shader capture required");
            return new SceneNeighborhood(replica.cubes(),SceneRenderEnvelope.PLAYER_DIAGNOSTIC_PADDING,
                SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE,shaderNativeContract.values().disableDirectionalShading());
        }
        void initialize(){
            if(!valid())throw new IllegalStateException("Resources changed before rolling session initialization");
            if(shaderMode){initializeShader();return;}
            installedGate=new DhCarrierRenderGate();
            installedEvents=new InstalledDhEvents(installedRuntime,installedGate,
                ()->new InstalledDhEvents.Frame(actualRenderFrame,guard.resourceGeneration(),InstalledDhEvents.supportedMode()),
                failure->{installedCallbackFailure=failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage());
                    EarthPlatformMod.LOGGER.error("INSTALLED_DH_CARRIER_CALLBACK_FAILED",failure);invalidate();});
            installedLease=installedGate.acquire(installedRuntime,carrier,profile.fingerprint(),epoch,guard.resourceGeneration(),new DhCarrierRenderGate.Resources(){
                public void draw(Object context){
                    if(current!=Session.this||!valid()||guard.invalidationRequested())throw new IllegalStateException("Stale installed carrier owner");
                    var matrices=InstalledDhFrame.capture(context);lastInstalledFrame=actualRenderFrame;installedCallbackFrames++;
                    render(matrices.projection(),matrices.modelView(),Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
                }
                public void close(){Session.this.close();}
            });
            installedEvents.start();
            EarthPlatformMod.LOGGER.info("INSTALLED_DH_CARRIER_ACQUIRED mod={} version={} api={} artifact={} epoch={} resources={}",InstalledDhResolver.MOD_ID,InstalledDhResolver.VERSION,InstalledDhResolver.API,InstalledDhResolver.SHA256,epoch,guard.resourceGeneration());
            collision=new CoreCollisionBinding(carrier,Objects.requireNonNull(Minecraft.getInstance().player).getUUID(),epoch,profile);light=new DynamicTexture(16,16,false);var pixels=Objects.requireNonNull(light.getPixels());for(int y=0;y<16;y++)for(int x=0;x<16;x++)pixels.setPixelRGBA(x,y,-1);light.upload();pumpSourcePreparation();report("STARTED");}
        void initializeShader(){
            if(distanceMode()){
                visualCpuReservations.add(NativeDistanceCpuBudget.global().reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,16L<<20));
                captureBodyPalette();
            }
            if(NativeIrisShaderStages.restorationPending())throw new IllegalStateException("Prior native shader restoration remains owned");
            shaderFarBackend=new IrisShaderFarBackend(installedIris);
            shaderCarrier=new InstalledShaderCarrier(carrier,profile.fingerprint(),epoch,guard.resourceGeneration(),installedRuntime,installedIris,shaderFarBackend,new InstalledShaderCarrier.Host(){
                public InstalledShaderCarrier.Frame frame(){return shaderFrame();}
                public boolean presentationCurrent(String pair,boolean historical){synchronized(fence){return shaderPresentationCurrent(pair,historical);}}
                public void renderFar(com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam context,double x,double y,double z){shaderFar(context,x,y,z);}
                public void renderNative(DhShaderPassGate.Pass pass,Object context){shaderNative(pass,context);}
                public void observeNative(DhShaderPassGate.Pass pass,Object context){observeVisualNative(pass,context);}
                public void closeOwned(){Session.this.close();}
                public void invalidate(String reason){shaderNativeLifecycle.invalidate();invalidateOwned();NativeIrisShaderStages.restorePendingState();}
                public void failed(Throwable failure){installedCallbackFailure=failure.getClass().getSimpleName()+": "+String.valueOf(failure.getMessage());EarthPlatformMod.LOGGER.error("INSTALLED_SHADER_CARRIER_CALLBACK_FAILED",failure);if(distanceMode()&&!nativeRefusalReported){nativeRefusalReported=true;var game=Minecraft.getInstance();String refusal=nativeDistanceRefusal(game.options.renderDistance().get(),failure);game.execute(()->{if(game.getConnection()==connection)game.gui.getChat().addMessage(Component.literal(refusal));});}invalidateOwned();}
            },distanceMode());
            if(distanceMode())shaderFarBackend.bindNativeDistance(()->Objects.requireNonNull(publishedVisual,"Native distance publication is not ready"));
            shaderCarrier.start();
            collision=new CoreCollisionBinding(carrier,Objects.requireNonNull(Minecraft.getInstance().player).getUUID(),epoch,profile);
            if(distanceMode())collision.clientMotion(Minecraft.getInstance().player,this::visualMotion);
            pumpSourcePreparation();report("STARTED");
        }
        void observeVisualNative(DhShaderPassGate.Pass pass,Object transientContext){synchronized(fence){
            if(!distanceMode()||closed||visualRecoveryQueued||guard.invalidationRequested()||pass!=DhShaderPassGate.Pass.MAIN_NATIVE_SOLID)return;
            var context=NativeIrisShaderStages.context(transientContext);observedNativeProjection=context.projection();
            if(shaderNativeContract==null||!sourceVerified||preparedSource==null)return;
            if(visualCaptureOwner==null){
                visualCpuReservations.add(NativeDistanceCpuBudget.global().reserve(NativeDistanceCpuBudget.Pool.CAPSULE,8L<<20));
                visualCaptureOwner=nativeQualification==null?new NativeVisualCapsuleCapture(shaderNativeContract):new NativeVisualCapsuleCapture(shaderNativeContract,NativeVisualCapsuleCapture.Mode.CAPTURE_WITH_PRIOR_QUALIFICATION,nativeQualification);
                visualCaptureWork=new NativeCapsuleWorkSlice(NativeVisualCapsuleCapture.CAPTURE_CASES+(visualCaptureOwner.mode()==NativeVisualCapsuleCapture.Mode.EXHAUSTIVE_QUALIFICATION?NativeVisualCapsuleCapture.VERIFICATION_CASES:0));
            }
            if(visualCapsule==null){
                long capsuleOwnerStarted=System.nanoTime();
                try{
                    lastCapsuleOwnerSlice=visualCaptureWork.pump(actualRenderFrame,()->valid()&&!closed&&!visualRecoveryQueued&&!guard.invalidationRequested(),(limit,remaining)->{
                        var step=visualCaptureOwner.step(limit,remaining);
                        return new NativeCapsuleWorkSlice.Progress(step.callbacks(),step.completed(),step.total(),step.complete());
                    });
                    status="Qualifying native textured rendering: "+lastCapsuleOwnerSlice.completed()+"/"+lastCapsuleOwnerSlice.total();
                    // Completed comparisons stay private if no budget remains for indivisible seal.
                    if(lastCapsuleOwnerSlice.complete()&&lastCapsuleOwnerSlice.stopReason()!=NativeCapsuleWorkSlice.StopReason.DUPLICATE_FRAME
                            &&valid()&&!closed&&!visualRecoveryQueued&&!guard.invalidationRequested()
                            &&System.nanoTime()-capsuleOwnerStarted<NativeCapsuleWorkSlice.SOFT_SLICE_NANOS){
                        long sealStarted=System.nanoTime();
                        try{visualCapsule=visualCaptureOwner.seal();nativeQualification=visualCapsule.qualification();}
                        finally{capsuleSealTiming.record(sealStarted);}
                        EarthPlatformMod.LOGGER.info("NATIVE_DISTANCE_CAPSULE_QUALIFIED {}",visualCapsule.evidence());
                    }
                }finally{
                    long elapsed=capsuleCaptureAndSealTiming.record(capsuleOwnerStarted);
                    maxCapsuleOwnerOvershootNanos=Math.max(maxCapsuleOwnerOvershootNanos,Math.max(0,elapsed-NativeCapsuleWorkSlice.SOFT_SLICE_NANOS));
                }
                return;
            }
            if(!visualOracleComplete&&Boolean.getBoolean("earthplatform.test.nativeDistanceOracle")&&nativeOracleIdentity(visualCapsule.capsule().identity()).equals(completedNativeOracleIdentity)){
                visualOracleComplete=true;visualOracleReused=true;EarthPlatformMod.LOGGER.info("NATIVE_DISTANCE_PAGE_ORACLE_REUSED identity={}",completedNativeOracleIdentity);
            }
            if(!visualOracleComplete&&Boolean.getBoolean("earthplatform.test.nativeDistanceOracle")){
                if(visualOracle==null)visualOracle=new NativeVisualPageOracle(visualCapsule,NativeVisualOracleFixtures.input(visualOracleIndex));
                if(visualOracleIndex==12){visualOracle.requireUnknownHaloRefusal(4096,3_000_000L);visualOracle.close();EarthPlatformMod.LOGGER.info("NATIVE_DISTANCE_PAGE_ORACLE {} expectedUnknownRefused=true cleanupComplete=true",NativeVisualOracleFixtures.NAMES.get(visualOracleIndex));visualOracle=null;visualOracleIndex++;}
                else {var result=visualOracle.step(4096,3_000_000L);status="Checking native page parity: "+NativeVisualOracleFixtures.NAMES.get(visualOracleIndex)+" "+result.visitedBlocks()+"/"+result.totalBlocks();if(result.complete()){EarthPlatformMod.LOGGER.info("NATIVE_DISTANCE_PAGE_ORACLE {} {}",NativeVisualOracleFixtures.NAMES.get(visualOracleIndex),result);visualOracle.close();visualOracle=null;visualOracleIndex++;}}
                visualOracleComplete=visualOracleIndex==NativeVisualOracleFixtures.NAMES.size();if(visualOracleComplete)completedNativeOracleIdentity=nativeOracleIdentity(visualCapsule.capsule().identity());return;
            }
            visualOracleComplete=true;
            if(visualStream==null)visualStream=new NativeVisualStream(preparedSource.alpine().definition(),visualCapsule,NativeVisualBudget.global());
        }}
        boolean requestVisual(){
            if(!distanceMode())return true;
            if(visualStream==null||observedNativeProjection==null){status=preparationStatus("Waiting for actual native model qualification and main projection");return false;}
            var target=replica.stagedMetadata()==null?replica.currentCenter():replica.stagedMetadata().candidateCenter();
            var earthCapture=Objects.requireNonNull(proposedReplacement).data().capture().earthCapture();
            var pipeline=shaderCarrier.admittedPipeline();int selected=pipeline.selectedRenderDistance(),effective=pipeline.effectiveRenderDistance();
            String key=publication+":"+target+":"+proposedCertificate.identity()+":"+selected+":"+effective;
            if(!key.equals(visualRequestKey)){
                if(far!=null&&far.physicalSummaryWorkPending()){status=preparationStatus("Draining the prior far-source reader before the next native window");return false;}
                if(!visualStream.finishRequest()){status=preparationStatus("Finishing bounded native cache row before next window");return false;}
                var evidence=preparedSource.alpine();
                var stamp=new NativeVisualStamp(world,java.util.UUID.fromString(proposedCertificate.storeIdentity()),epoch,guard.resourceGeneration(),++visualRequest,proposedCertificate.originalRevision(),selected,effective,pipeline.serverRenderDistanceLimit(),evidence.sourceIdentity(),evidence.detailIdentity(),evidence.materialIdentity(),visualCapsule.capsule().identity().bytesDigest(),earthCapture.contentIdentity());
                visualStream.request(stamp,target,earthCapture);visualRequestKey=key;
            }
            return true;
        }
        boolean prepareVisual(){
            if(!distanceMode())return true;
            if(!requestVisual())return false;
            var target=replica.candidateReady().candidateCenter();
            if(!visualStream.drawReady()){status=preparationStatus("Preparing full native distance: "+visualStream.evidence().cachedPages()+" pages");return false;}
            if(proposedVisual==null){
                var domain=NativeVisualPublication.cameraPermit(target);
                long publicationStarted=System.nanoTime();try{proposedVisual=visualStream.publication(observedNativeProjection,domain,()->!closed&&!guard.invalidationRequested()&&shaderCarrier!=null&&shaderCarrier.pipelineCurrent());}finally{visualPublicationTiming.record(publicationStarted);}
            }
            proposedVisual.requireCurrent();return true;
        }
        boolean visualTargetAllowed(CubePos target){try{profile.requireAllowedWindow(target);return true;}catch(IllegalArgumentException refused){return false;}}
        boolean visualMotion(net.minecraft.world.phys.AABB body,Vec3 movement){
            if(!distanceMode())return true;if(closed||visualRecoveryQueued||publishedVisual==null||!publishedVisual.isCurrent())return false;
            if(movement.lengthSqr()==0)return true;
            var d=publishedVisual.cameraDomain();double x=(body.minX+body.maxX)*.5,z=(body.minZ+body.maxZ)*.5,y=body.minY+RollingVisualFrontier.EYE_HEIGHT;
            double nx=x+movement.x,ny=y+movement.y,nz=z+movement.z;
            boolean publishedAllows=RollingVisualFrontier.motionInside(replica.currentCenter(),body,movement);
            // A rejection by the overlap alone must never manufacture an opposite request.
            if(!publishedAllows&&visualCommittedPublication>0&&!visualFrontier.awaiting()){
                int dx=nx-5<d.minX()?-1:nx+5>d.maxX()?1:0,dy=ny-5<d.minY()?-1:ny+5>d.maxY()?1:0,dz=nz-5<d.minZ()?-1:nz+5>d.maxZ()?1:0;
                var target=replica.currentCenter().offset(dx,dy,dz);long sequence=visualFrontierSequence+1;
                if(visualTargetAllowed(target)&&visualFrontier.request(epoch,visualCommittedPublication,guard.resourceGeneration(),sequence,target,System.nanoTime())){
                    visualFrontierSequence=sequence;
                    PacketDistributor.sendToServer(new RollingVisualFrontierIntentPayload(epoch,visualCommittedPublication,guard.resourceGeneration(),sequence,target));
                }
            }
            boolean allowed=publishedAllows&&visualFrontier.admits(body,movement);
            if(!allowed)status="Preparing the next full native-distance window; movement held at the verified frontier";
            return allowed;
        }
        /** Detect before PREPARED and again before the first publication mutation; strict draw guards remain unchanged. */
        boolean candidateCameraReady(CubePos target){
            if(!distanceMode())return true;if(visualRecoveryQueued)return false;
            long generation=replica.stagedMetadata()==null?replica.candidateReady().generation():replica.stagedMetadata().generation();
            if(!visualFrontier.candidateMatches(publication,guard.resourceGeneration(),generation,target))throw new IllegalStateException("Native candidate lost exact camera handover identity");
            var actual=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            if(!RollingVisualFrontier.cameraInside(target,actual)){queueVisualRecovery("Actual camera is outside the exact candidate permit before publication");return false;}
            if(proposedVisual!=null){proposedVisual.requireCurrent();if(!proposedVisual.cameraDomain().contains(actual.x,actual.y,actual.z)){queueVisualRecovery("Actual camera is outside the prepared native permit before publication");return false;}proposedVisual.requireCamera(actual.x,actual.y,actual.z);}
            return true;
        }
        void queueVisualRecovery(String reason){
            if(visualRecoveryQueued)return;visualRecoveryQueued=true;visualRecoveryDetail=reason;
            if(collision!=null)collision.fence();status="Holding position for a bounded native camera handover rebuild";
        }
        InstalledShaderCarrier.Frame shaderFrame(){synchronized(fence){
            if(!valid()||guard.invalidationRequested())throw new IllegalStateException("Shader host frame is fenced");
            if(shaderPreparedFrame!=actualRenderFrame){
                StrictGlErrors.check("shader.prepare.entry");
                if(shaderNativeContract==null)shaderNativeContract=SceneNativeShaderContract.captureIris(shaderNativeLifecycle);
                else if(!shaderNativeContract.isCurrent())throw new IllegalStateException("Native shader provenance changed");
                shaderPreparedFrame=actualRenderFrame;lastInstalledFrame=actualRenderFrame;installedCallbackFrames++;
                camera=Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
                updatePreparedPair();
                StrictGlErrors.check("shader.prepare.exit");
                shaderFrameHistorical=!visualRecoveryQueued&&activePresentation().frozen();
                shaderFrameDigest=visualRecoveryQueued?null:shaderFrameHistorical?activePresentation().stamp().manifestDigest():(visible?publishedPairIdentity.digest():null);
                shaderDrawFrame=actualRenderFrame;shaderMainDrawMask=0;drawn=false;
            }
            return new InstalledShaderCarrier.Frame(actualRenderFrame,guard.resourceGeneration(),shaderFrameDigest,shaderFrameHistorical);
        }}
        boolean shaderPresentationCurrent(String pair,boolean historical){
            if(!valid()||guard.invalidationRequested()||shaderNativeContract==null||!shaderNativeContract.isCurrent()
                    ||!Objects.equals(pair,shaderFrameDigest)||historical!=shaderFrameHistorical)return false;
            if(distanceMode()&&(publishedVisual==null||!publishedVisual.isCurrent()))return false;
            if(historical)return activePresentation().frozen()&&activePresentation().stamp()!=null&&pair.equals(activePresentation().stamp().manifestDigest())&&activePresentation().current();
            return visible&&!activePresentation().frozen()&&pair.equals(publishedPairIdentity.digest())&&publishedFarCoverageReady()&&publishedNativeCurrent();
        }
        void shaderNative(DhShaderPassGate.Pass pass,Object transientContext){synchronized(fence){
            if(visualRecoveryQueued)return; // A previously pinned frame keeps its identity, but submits no more geometry.
            if(!shaderPresentationCurrent(shaderFrameDigest,shaderFrameHistorical))throw new IllegalStateException("Native shader stage lost its pinned pair");
            var context=(NativeIrisShaderStages.Context)transientContext;
            if(context.pass()!=pass)throw new IllegalArgumentException("Native shader context pass mismatch");
            if(pass==DhShaderPassGate.Pass.MAIN_NATIVE_TRANSLUCENT||pass==DhShaderPassGate.Pass.SHADOW_NATIVE_TRANSLUCENT){shaderPassSubmissions[pass.ordinal()]++;return;}
            var host=NativeIrisShaderStages.shaderPass(context,shaderNativeContract);
            boolean shown;
            if(distanceMode()){
                var visual=shaderFrameHistorical?Objects.requireNonNull(inspectedBodyHistory).visual():Objects.requireNonNull(publishedVisual);visual.requireCamera(context.cameraX(),context.cameraY(),context.cameraZ());if(!context.shadow())visual.requireNativeProjection(context.projection());
                var before=visualBatch.evidence();
                try{visualBatch.draw(host,visual.drawnPieces(),visual.drawnMemberMasks(),context.modelView(),context.projection(),context.cameraX(),context.cameraY(),context.cameraZ(),()->{visual.requireCurrent();if(!shaderPresentationCurrent(shaderFrameDigest,shaderFrameHistorical))throw new IllegalStateException("Native body authority changed during submission");});}
                finally{var after=visualBatch.evidence();visualDrawCalls[pass.ordinal()]+=after.drawCalls()-before.drawCalls();visualFullDrawCalls[pass.ordinal()]+=after.fullDrawCalls()-before.fullDrawCalls();visualRangeDrawCalls[pass.ordinal()]+=after.rangeDrawCalls()-before.rangeDrawCalls();}shown=true;
            }else if(shaderFrameHistorical)shown=presentation.draw((nativeTicket,farTicket)->{
                for(var mesh:published.values())if(!mesh.drawPresentedLayer(nativeTicket.tokenFor(mesh.presentationSource()),host,context.modelView(),context.projection(),context.cameraX(),context.cameraY(),context.cameraZ(),null))return false;return true;
            });else{shown=true;for(var mesh:published.values())if(!mesh.drawLayer(host,context.modelView(),context.projection(),context.cameraX(),context.cameraY(),context.cameraZ(),null)){shown=false;break;}}
            if(!shown)throw new IllegalStateException("Native shader layer rejected");
            shaderNativeSubmissions++;shaderPassSubmissions[pass.ordinal()]++;
            if(pass==DhShaderPassGate.Pass.MAIN_NATIVE_SOLID)shaderMainDrawMask|=1;
            shaderDrawComplete();
        }}
        void shaderFar(com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam context,double x,double y,double z){synchronized(fence){
            if(visualRecoveryQueued)return;
            if(!shaderPresentationCurrent(shaderFrameDigest,shaderFrameHistorical))throw new IllegalStateException("Far shader stage lost its pinned pair");
            if(distanceMode())Objects.requireNonNull(publishedVisual).requireCamera(x,y,z);
            var matrices=InstalledDhFrame.capture(context);var combined=new Matrix4f(matrices.projection()).mul(matrices.modelView());
            boolean shown=shaderFrameHistorical?(distanceMode()?distancePresentation.draw((nativeTicket,farTicket)->far.drawPresented(farTicket,combined,x,y,z,guard.resourceGeneration())):presentation.draw((nativeTicket,farTicket)->far.drawPresented(farTicket,combined,x,y,z,guard.resourceGeneration()))):shaderDrawCurrentFar(combined,x,y,z);
            if(!shown)throw new IllegalStateException("Far shader pass rejected");
            shaderFarSubmissions++;shaderPassSubmissions[DhShaderPassGate.Pass.MAIN_DH_OPAQUE.ordinal()]++;
            shaderMainDrawMask|=2;shaderDrawComplete();
        }}
        boolean shaderDrawCurrentFar(Matrix4f combined,double x,double y,double z){far.draw(combined,x,y,z,guard.resourceGeneration());return !guard.invalidationRequested();}
        void shaderDrawComplete(){
            if(drawn||shaderDrawFrame!=actualRenderFrame||(shaderMainDrawMask&3)!=3)return;
            drawn=true;
            if(shaderFrameHistorical){frozenDrawFrames++;
                if(!frozenDrawReported){frozenDrawReported=true;frozenCapture="FROZEN_EDIT_"+publication;report(frozenCapture);}
                if(!publishedNativeCurrent()){frozenStaleRevisionFrames++;if(!frozenStaleReported){frozenStaleReported=true;frozenCapture="FROZEN_PRIOR_REVISION_"+publication;report(frozenCapture);}}
            }
        }
        void pumpSourcePreparation(){
            if(closed||guard.invalidationRequested()||sourceVerified)return;
            if(profile.arenaMode()){profile.requirePreparedWorld(new io.earthplatform.minecraft.enginebridge.NativeArenaCubeProvider(profile.arenaSource(),palette).identity());sourceVerified=true;return;}
            if(!profile.naturalRelief()){profile.requirePreparedWorld(RollingGateFixture.provider(palette,CoreGateProfile.byId(profile.id())).identity());sourceVerified=true;return;}
            if(sourcePreparation==null){
                long now=System.nanoTime();if(now<nextSourceAttemptNanos)return;
                nextSourceAttemptNanos=now+250_000_000L;
                // The worker captures only the immutable palette. No Session/Level/player callbacks.
                final EnginePaletteContext immutablePalette=palette;final boolean profileAlpine=profile.alpineMode();
                try{sourcePreparation=BoundedProviderPreparation.submit(new BoundedProviderPreparation.Stamp(epoch,2),
                    cancellation->profileAlpine?RollingAlpinePreparation.prepare(immutablePalette,cancellation::throwIfCancelled):RollingEarthPreparation.prepare(immutablePalette,cancellation::throwIfCancelled));}
                catch(java.util.concurrent.RejectedExecutionException busy){status=preparationStatus("Earth provider capacity busy; bounded retry pending");return;}
            }
            if(sourcePreparation.state()==BoundedProviderPreparation.State.FAILED){
                var failure=sourcePreparation.failure().orElseThrow();sourcePreparation.close();
                throw new IllegalStateException("Earth client provider preparation failed",failure);
            }
            if(sourcePreparation.state()==BoundedProviderPreparation.State.CANCELLED)throw new IllegalStateException("Earth client source request cancelled");
            var result=sourcePreparation.takeReady(new BoundedProviderPreparation.Stamp(epoch,2));
            if(result.isEmpty())return;
            var prepared=result.orElseThrow();
            profile.requirePreparedWorld(prepared.provider().identity());
            if(!profile.samePreparedSource(prepared.descriptor()))throw new IllegalStateException("Server/client Earth source or bounded session differs");
            preparedSource=prepared;sourcePrepareNanos=prepared.elapsedNanos();sourceWorker=prepared.workerThread();
            sourceUrl=prepared.sourceUrl();sourceDatum=prepared.verticalDatum();sourceVerified=true;
            report("SOURCE_VERIFIED");
        }
        void cancelSourcePreparation(){if(sourcePreparation!=null)sourcePreparation.close();}
        boolean valid(){var g=Minecraft.getInstance();return !closed&&entryResourceSequence==SCENARIO_RELOAD_SEQUENCE.get()&&g.level==carrier&&g.getConnection()==connection&&g.player!=null&&(g.player.isSpectator()||(g.gameMode!=null&&g.gameMode.getPlayerMode()==net.minecraft.world.level.GameType.ADVENTURE));}
        /** CPU fencing is safe from reload preparation threads. GL restoration is owner-only. */
        void invalidate(){
            if(installedGate!=null)installedGate.invalidate("Rolling source/resource invalidated");
            invalidateOwned();
            if(shaderCarrier!=null&&Minecraft.getInstance().isSameThread())shaderCarrier.invalidate("Rolling source/resource invalidated");
        }
        /** Bridge callbacks never recursively enter their own lifecycle/cleanup barrier. */
        void invalidateOwned(){synchronized(fence){visible=false;publishedPairIdentity.revoke();activePresentation().invalidate();if(bodyOwner!=null)bodyOwner.revoke();guard.requestInvalidation();fenceCollision();replacementAuthority.close();if(setAuthority!=null)setAuthority.close();if(arenaAuthority!=null)arenaAuthority.close();closeReplacementCapture();cancelSourcePreparation();if(far!=null)far.requestInvalidation();}}
        void metadata(long id,RollingCubeActor.StageMetadata m){
            if(closed||guard.invalidationRequested())return;if(pending||preparedFar!=null||id<=publication)throw new IllegalArgumentException("Overlapping/replayed rolling publication");
            if(distanceMode()&&!visualFrontier.metadata(epoch,id,guard.resourceGeneration(),m.currentCenter(),m.candidateCenter(),m.generation(),System.nanoTime()))queueVisualRecovery("Metadata differs from the pinned camera handover");
            capture="";drawn=false;replica.acceptMetadata(m);desiredFarCenter=RollingFarPublication.center(profile,m.candidateCenter());nativeManifestDigest="";publication=id;pending=true;edit=false;replacementDeclineSent=false;preparedSent=allowed=pairSent=false;digest="";status=preparationStatus("Staging adjacent native window");report("STAGING_"+id);
        }
        void earthBootstrap(long generation,EngineCubeSnapshot snapshot){if(closed||guard.invalidationRequested())return;if(!profile.earthResumeBootstrap()||proposedCertificate!=null||replacementInput!=null)throw new IllegalArgumentException("Unexpected Earth continuation bootstrap");if(earthResumeBootstrap==null)earthResumeBootstrap=new RollingEarthResumeBootstrap(profile,epoch,palette);earthResumeBootstrap.accept(generation,snapshot);}
        void snapshot(long generation,EngineCubeSnapshot snapshot){if(closed||guard.invalidationRequested())return;replica.acceptSnapshot(generation,snapshot);acceptedSnapshots++;peakResident=Math.max(peakResident,replica.cubes().size());}
        void ready(long id,RollingCubeActor.StageReady ready){
            if(!pending||id!=publication||!digest.isEmpty())throw new IllegalArgumentException("Unexpected rolling readiness");if(profile.arenaMode())ready=RollingArenaCaptureOwner.canonicalize(ready,arenaInput==null?null:arenaInput.data().capture());if(profile.alpineMode())ready=RollingEarthCaptureOwner.canonicalize(ready,replacementInput==null?null:replacementInput.data().capture().earthCapture());replica.acceptReady(ready);if(profile.arenaMode())acceptArena(ready);else if(profile.twoWorkspaces())acceptReplacementSet(ready);else if(profile.alpineMode())acceptEarthReplacement(ready);else acceptReplacementCertificate(ready.replacementCertificate());nativeManifestDigest=replica.candidateManifestDigest();desiredFarCenter=RollingFarPublication.center(profile,ready.candidateCenter());digest=RollingFarPublication.digest(profile,nativeManifestDigest,desiredFarCenter);
            if(distanceMode()){
                if(candidateBody!=null||candidateBodyJoined!=null||!candidate.isEmpty()||!nativeReuses.isEmpty()||!meshWork.isDrained()||!nativeReuseQueue.isEmpty()||!nativeRebuildTargets.isEmpty())throw new IllegalStateException("Previous distance candidate owns resources");
                bodyEqualityCompletedComparisons=0;lastBodyEqualitySlice=null;
                status=preparationStatus("Preparing exact native distance body125 data authority");return;
            }
            if(!candidate.isEmpty()||!nativeReuses.isEmpty()||!meshWork.isDrained())throw new IllegalStateException("Previous candidate owns resources");
            if(!nativeReuseQueue.isEmpty()||!nativeRebuildTargets.isEmpty())throw new IllegalStateException("Previous native classification remains owned");
            nativeClassificationComplete=false;
            lastNativeStrictReused=lastNativeFootprintPrepared=lastNativeRebuildTargets=0;
            for(var pos:replica.candidateTargets()){
                var old=published.get(pos);
                if(old!=null&&old.isCurrent()){candidate.put(pos,old);nativeStrictReused++;lastNativeStrictReused++;}
                else if(old!=null)nativeReuseQueue.addLast(pos);
                else nativeRebuildTargets.add(pos);
            }
            if(nativeReuseQueue.size()>28||nativeRebuildTargets.size()>28)throw new IllegalStateException("Native classification exceeds target cap");
            peakNativeReuseQueue=Math.max(peakNativeReuseQueue,nativeReuseQueue.size());
            if(nativeReuseQueue.isEmpty())startNativeBuilds();
            status=preparationStatus("Validating bounded native read footprints");
        }
        void acceptArena(RollingCubeActor.StageReady ready){
            if(arenaAuthority==null||ready.arenaCertificate()==null)throw new IllegalArgumentException("Complete arena authority required");
            proposedArena=ready.arenaCertificate();RollingArenaQueryGuard.require(profile,proposedArena);proposedArena.verifyCapture(ready.arenaCapture());arenaAuthority.propose(proposedArena);verifyArenaNativeRevisions(proposedArena);
            final RollingArenaAuthority authority=arenaAuthority;final ArenaCertificate certificate=proposedArena;final String identity=certificate.identity();
            if(!edit&&identity.equals(publishedArenaIdentity)){proposedArenaInput=Objects.requireNonNull(arenaInput);return;}
            proposedArenaInput=new DhArenaInput(profile.arenaSource(),certificate,ready.arenaCapture(),new DhArenaInput.AuthorityGuard(){
                public boolean isCurrent(ArenaCertificate value){return value==certificate&&authority.isCurrent(identity);}
                public boolean permitsRetainedPresentation(ArenaCertificate value){return value==certificate&&authority.canPresent(identity);}
            });replacementCaptures++;
        }
        void verifyArenaNativeRevisions(ArenaCertificate value){
            if(value==null||arenaAuthority==null||!arenaAuthority.isCurrent(value.identity()))throw new IllegalStateException("Current complete arena authority required");
            for(var dependency:value.dependencies()){var cube=replica.cubes().get(dependency.position());if(cube!=null&&cube.snapshot().revision()!=dependency.revision())throw new IllegalStateException("Native dependency differs from complete arena");}
        }
        void acceptReplacementSet(RollingCubeActor.StageReady ready){
            if(setAuthority==null||ready.replacementSet()==null)throw new IllegalArgumentException("Complete two-workspace authority required");
            closeReplacementCapture();proposedCertificate=null;proposedReplacement=null;replacementDeclineSent=false;
            proposedSet=ready.replacementSet();RollingIndexedQueryGuard.require(profile,proposedSet,ready.candidateCenter());proposedSet.verifyCaptures(ready.replacementCaptures());setAuthority.propose(proposedSet);verifySetNativeRevisions(proposedSet);
            if(!edit&&publishedSet!=null&&publishedSet.identity().equals(proposedSet.identity())){proposedSetInput=Objects.requireNonNull(setInput);return;}
            final RollingReplacementSetAuthority authority=setAuthority;
            final ReplacementSetCertificate certificate=proposedSet;
            final String certificateIdentity=certificate.identity();
            proposedSetInput=new DhReplacementSetInput(profile.flatPolicy(),proposedSet,ready.replacementCaptures(),new DhReplacementSetInput.AuthorityGuard(){
                public boolean isCurrent(ReplacementSetCertificate value){return value==certificate&&authority.isCurrent(certificateIdentity);}
                public boolean permitsRetainedPresentation(ReplacementSetCertificate value){return value==certificate&&authority.canPresent(certificateIdentity);}
            });replacementCaptures+=2;
        }
        void verifySetNativeRevisions(ReplacementSetCertificate value){
            if(value==null||setAuthority==null||!setAuthority.isCurrent(value.identity()))throw new IllegalStateException("Current complete set authority required");
            for(var region:value.regions())for(var dependency:region.dependencies()){
                var cube=replica.cubes().get(dependency.position());
                if(cube!=null&&cube.snapshot().revision()!=dependency.revision())throw new IllegalStateException("Native dependency differs from durable replacement set");
            }
        }
        void acceptReplacementCertificate(ReplacementCertificate certificate){
            closeReplacementCapture();proposedCertificate=certificate;proposedReplacement=null;replacementDeclineSent=false;
            if(certificate==null){replacementAuthority.cancelProposal();return;}
            if(profile.naturalRelief()||!Boolean.getBoolean(RollingGateFixture.REPLACEMENT_OPT_IN))throw new IllegalArgumentException("Replacement capability was not admitted");
            replacementAuthority.propose(certificate);
            if(publishedCertificate!=null&&publishedCertificate.identity().equals(certificate.identity())){
                proposedReplacement=Objects.requireNonNull(replacementInput);return;
            }
            RollingReplacementCapture.verifyBindings(certificate,replica.cubes(),true);
            replacementCapture=new RollingReplacementCapture(certificate);replacementCaptures++;
        }
        void acceptEarthReplacement(RollingCubeActor.StageReady ready){
            closeReplacementCapture();proposedCertificate=ready.replacementCertificate();proposedReplacement=null;replacementDeclineSent=false;
            proposedEarthCapture=ready.earthReplacementCapture();RollingEarthReplacementWire.require(proposedCertificate,proposedEarthCapture);
            boolean retainedRefusal=edit&&publishedCertificate!=null&&publishedCertificate.identity().equals(proposedCertificate.identity());
            if(retainedRefusal){
                if(replacementInput==null||replacementInput.data().capture().earthCapture()!=proposedEarthCapture)throw new IllegalStateException("Refused Earth edit changed its retained content owner");
                replacementAuthority.reproposeRefusedEarth(proposedCertificate);
            }
            else replacementAuthority.propose(proposedCertificate);
            if(!edit&&publishedCertificate!=null&&publishedCertificate.identity().equals(proposedCertificate.identity())){
                if(replacementInput==null||replacementInput.data().capture().earthCapture()!=proposedEarthCapture)
                    throw new IllegalStateException("Retained Earth certificate lost its exact compact content owner");
                proposedReplacement=replacementInput;return;
            }
            RollingEarthCaptureValidation.verifyBindings(proposedCertificate,earthResumeBootstrap==null?replica.cubes():earthResumeBootstrap.cubes(),!retainedRefusal);
            earthCaptureValidation=new RollingEarthCaptureValidation(proposedCertificate,proposedEarthCapture,!retainedRefusal);replacementCaptures++;
        }
        boolean prepareEarthReplacementInput(){
            if(proposedCertificate==null||proposedEarthCapture==null)throw new IllegalStateException("Earth replacement authority is mandatory");
            if(proposedReplacement!=null)return true;
            if(earthCaptureValidation==null)throw new IllegalStateException("Earth material verification missing");
            long start=System.nanoTime();int before=earthCaptureValidation.completedReads();
            try{
                var validationCubes=earthResumeBootstrap==null?replica.cubes():earthResumeBootstrap.cubes();
                if(earthResumeBootstrap!=null)earthResumeBootstrap.require(proposedCertificate);
                if(!earthCaptureValidation.step(validationCubes)){status=preparationStatus("Validating exact Earth materials "+earthCaptureValidation.completedReads()+"/8000");return false;}
                var capture=io.earthplatform.minecraft.lod.DhVoxelReplacementGeometry.captureEarth(earthCaptureValidation.finish(validationCubes));var c=proposedCertificate;
                var certificate=new DhReplacementInput.Certificate(world,epoch,guard.resourceGeneration(),c.generation(),c.originalCube(),c.originalRevision(),c.identity(),c.dependencyIdentity(),c.materialPolicy());
                final RollingReplacementAuthority authority=replacementAuthority;
                proposedReplacement=new DhReplacementInput(certificate,capture,new DhReplacementInput.AuthorityGuard(){
                    public boolean isCurrent(DhReplacementInput.Certificate value){return authority.isCurrent(value.identity());}
                    public boolean permitsRetainedPresentation(DhReplacementInput.Certificate value){return authority.canPresent(value.identity());}
                });return true;
            }finally{
                if(earthCaptureValidation!=null){replacementCaptureReads+=earthCaptureValidation.completedReads()-before;if(proposedReplacement!=null){earthCaptureValidation.close();earthCaptureValidation=null;}}
                replacementCaptureTiming.record(start);
            }
        }
        boolean prepareReplacementInput(){
            if(profile.alpineMode())return prepareEarthReplacementInput();
            if(profile.arenaMode())return proposedArena!=null&&proposedArenaInput!=null;
            if(profile.twoWorkspaces())return proposedSet!=null&&proposedSetInput!=null;
            if(proposedCertificate==null||proposedReplacement!=null)return true;
            if(replacementCapture==null)throw new IllegalStateException("Certified replacement capture missing");
            long start=System.nanoTime();int before=replacementCapture.completedReads();
            try{
                if(!replacementCapture.step(replica.cubes())){status=preparationStatus("Capturing exact 3D edit halo "+replacementCapture.completedReads()+"/8000");return false;}
                var capture=replacementCapture.finish(replica.cubes());var c=proposedCertificate;
                var certificate=new DhReplacementInput.Certificate(world,epoch,guard.resourceGeneration(),c.generation(),c.originalCube(),c.originalRevision(),c.identity(),c.dependencyIdentity(),c.materialPolicy());
                final RollingReplacementAuthority authority=replacementAuthority;
                proposedReplacement=new DhReplacementInput(certificate,capture,new DhReplacementInput.AuthorityGuard(){
                    public boolean isCurrent(DhReplacementInput.Certificate value){return authority.isCurrent(value.identity());}
                    public boolean permitsRetainedPresentation(DhReplacementInput.Certificate value){return authority.canPresent(value.identity());}
                });return true;
            }catch(UnsupportedOperationException unsupported){requestReplacementFallback(unsupported.getMessage());return false;}
            finally{if(replacementCapture!=null){replacementCaptureReads+=replacementCapture.completedReads()-before;if(proposedReplacement!=null||replacementDeclineSent)closeReplacementCapture();}replacementCaptureTiming.record(start);}
        }
        void closeReplacementCapture(){if(replacementCapture!=null){replacementCapture.close();replacementCapture=null;}if(earthCaptureValidation!=null){earthCaptureValidation.close();earthCaptureValidation=null;}proposedEarthCapture=null;}
        void requestReplacementFallback(String reason){
            if(profile.alpineMode()||profile.arenaMode()||profile.twoWorkspaces())throw new IllegalStateException("An admitted durable replacement cannot be discarded: "+reason);
            if(replacementDeclineSent)return;
            if(proposedCertificate==null||!replica.currentPinned()||!replica.candidatePinned()||preparedSent||allowed||pairSent)
                throw new IllegalStateException("Unsafe replacement decline: "+reason);
            if(preparedFar!=null)far.cancelPrepared(preparedFar);
            replacementDeclineSent=true;replacementDeclines++;status=preparationStatus("Replacement declined; retaining native pin: "+reason);
            send(new RollingGateControlPayload(epoch,publication,guard.resourceGeneration(),RollingGateControlPayload.REPLACEMENT_DECLINED,digest,false));
        }
        void replacementFallback(long oldPublication,long nextPublication,RollingCubeActor.StageReady ready){
            if(profile.alpineMode()||!pending||!replacementDeclineSent||oldPublication!=publication||nextPublication!=publication+1||preparedSent||allowed||pairSent
                    ||!replica.currentPinned()||!replica.candidatePinned()||!ready.pinned()||ready.replacementCertificate()!=null)
                throw new IllegalArgumentException("Stale or unsafe replacement fallback");
            replica.replaceReadyCertificate(ready);replacementAuthority.cancelProposal();closeReplacementCapture();proposedCertificate=null;proposedReplacement=null;
            preparedFar=null;preparedFarCenter=null;publication=nextPublication;replacementDeclineSent=false;
            nativeManifestDigest=replica.candidateManifestDigest();digest=RollingFarPublication.digest(profile,nativeManifestDigest,desiredFarCenter);
            status=preparationStatus("Preparing fallback with the native pin retained");report("REPLACEMENT_FALLBACK_"+publication);
        }
        void startNativeBuilds(){
            if(nativeClassificationComplete||!nativeReuseQueue.isEmpty())throw new IllegalStateException("Native classification is not complete");
            long expectedPublication=publication,expectedResource=guard.resourceGeneration();String expectedDigest=digest;
            lastNativeRebuildTargets=nativeRebuildTargets.size();
            meshWork.start(List.copyOf(nativeRebuildTargets),expectedPublication,expectedResource,()->valid()&&pending&&!guard.invalidationRequested()&&publication==expectedPublication&&guard.resourceGeneration()==expectedResource&&digest.equals(expectedDigest));
            nativeRebuildTargets.clear();nativeClassificationComplete=true;
        }
        void prepareOneNativeReuse(){
            if(nativeClassificationComplete||nativeReuseQueue.isEmpty())throw new IllegalStateException("No private native revalidation work");
            // One bounded footprint per installed opaque callback; never combined with native model callbacks.
            var pos=nativeReuseQueue.getFirst();var old=Objects.requireNonNull(published.get(pos));
            long start=System.nanoTime();
            try{
                var neighborhood=nativeNeighborhood();
                var reuse=old.prepareReuse(neighborhood);
                nativeReuseComparedValues=Math.addExact(nativeReuseComparedValues,old.lastReuseComparedValues());
                if(reuse!=null){
                    nativeReuses.put(old,reuse);candidate.put(pos,old);nativeFootprintPrepared++;lastNativeFootprintPrepared++;
                }else{nativeFootprintRejected++;nativeRebuildTargets.add(pos);}
                nativeReuseQueue.removeFirst();
                if(nativeReuseQueue.isEmpty())startNativeBuilds();
            }finally{nativeReusePreparation.record(start);}
            status=preparationStatus("Validating native reads: queued "+nativeReuseQueue.size()+", reused "+lastNativeFootprintPrepared);
        }
        void beginEdit(long id,long x,long y,long z){
            if(pending||preparedFar!=null||id<=publication||!profile.permitsEdit(profile.arenaMode()?CubePos.fromBlock(x,y,z):replica.activeFocus(),x,y,z))throw new IllegalArgumentException("Unexpected rolling edit barrier");
            if(!visible||!publishedNativeCurrent()||far==null||!publishedFarCoverageReady()||!collision.ready())throw new IllegalStateException("Edit requires a fully committed presentable pair");
            if(distanceMode()&&!visualFrontier.edit(epoch,id,guard.resourceGeneration(),replica.currentActorGeneration(),replica.currentCenter()))queueVisualRecovery("Edit differs from the exact current camera authority");
            // Capture the immutable previous revision before authoritative replacement admission.
            // This is presentation ownership only; it never supplies readiness or collision.
            var retainedStamp=new RollingEditPresentation.Stamp(epoch,publication,guard.resourceGeneration(),coverageGeneration,replica.revision(profile.arenaMode()?CubePos.fromBlock(x,y,z):replica.activeFocus()),publishedNativeTargets(),publishedPairIdentity.digest());
            if(distanceMode())distancePresentation.capture(retainedStamp);else presentation.capture(retainedStamp);
            capture="";frozenCapture="";frozenDrawReported=false;frozenStaleReported=false;drawn=false;publication=id;pending=true;edit=true;preparedSent=allowed=pairSent=false;digest="";intentPending=false;lastEditX=x;lastEditY=y;lastEditZ=z;
            if(arenaAuthority!=null){arenaAuthority.beginEdit(CubePos.fromBlock(x,y,z));proposedArena=null;proposedArenaInput=null;}else if(setAuthority!=null){setAuthority.beginEdit(replica.activeFocus());proposedSet=null;proposedSetInput=null;}else replacementAuthority.beginEdit();proposedCertificate=null;proposedReplacement=null;replacementDeclineSent=false;replica.beginEdit(profile.arenaMode()?CubePos.fromBlock(x,y,z):replica.activeFocus());desiredFarCenter=RollingFarPublication.center(profile,replica.currentCenter());nativeManifestDigest="";visible=false;fenceCollision();status=preparationStatus("Durable active-workspace edit pending");report("EDIT_PENDING_"+id);
        }
        void ack(long id,String result,long revision,String detail){if(!pending||!edit||id!=publication)throw new IllegalArgumentException("Stale rolling edit acknowledgement");replica.acknowledge(result,revision);status="Durable edit "+result+" revision "+revision+"; exact manifest pending";report("EDIT_ACK_"+publication);}
        void allow(long id,long resource,String exact){checkPair(id,resource,exact);if(!preparedSent||allowed||pairSent||preparedFar==null||!far.isPrepared(preparedFar))throw new IllegalStateException("Unexpected pair allowance");allowed=true;fenceCollision();status="Server fenced; installing exact near/far pair";}
        void checkPair(long id,long resource,String exact){if(closed||disposed||!valid()||!pending||id!=publication||resource!=guard.resourceGeneration()||!digest.equals(exact)||digest.isEmpty()||guard.invalidationRequested())throw new IllegalArgumentException("Stale rolling pair acknowledgement");}
        void committed(long id,long resource,String exact){
            checkPair(id,resource,exact);if(!pairSent||activePresentation().retainsOwners())throw new IllegalStateException("Commit precedes paired rendering or frozen-presentation retirement");
            if(!visible||!publishedNativeCurrent()||!publishedFarCoverageReady()||publishedPairPublication!=id||!exact.equals(publishedPairIdentity.digest()))throw new IllegalStateException("Commit lost the exact installed native/far pair");
            if(distanceMode()){
                Objects.requireNonNull(publishedBodyJoined).requireCurrent();
                visualFrontier.committed(id,resource,replica.candidateReady().generation(),replica.candidateReady().candidateCenter());
            }
            if(!edit)replica.commitStage(replica.stagedMetadata().generation());
            Map<CubePos,EngineCubeSnapshot> currentSnapshots=new LinkedHashMap<>();for(var p:replica.currentPositions125())currentSnapshots.put(p,Objects.requireNonNull(replica.cubes().get(p)).snapshot());
            var exactCollisionSnapshots=Map.copyOf(currentSnapshots);
            if(distanceMode())Objects.requireNonNull(publishedBody).requireCommittedSnapshots(exactCollisionSnapshots);
            collision.publish(world,epoch,palette,exactCollisionSnapshots);if(distanceMode()){visualCommittedPublication=id;finishDistanceProgress(epoch);}drawn=false;pending=false;edit=false;allowed=false;intentPending=false;status="READY: rolling collision125 + exact near/DH | revision "+replica.revision();capture="READY_"+publication;report(capture);if(profile.arenaMode()&&io.earthplatform.compat.PlayableDiagnostics.enabled()||profile.indexedWorkspaces()&&Boolean.getBoolean(RollingGateFixture.INDEXED_SCENARIO_OPT_IN))PacketDistributor.sendToServer(new RollingScenarioReceiptPayload(epoch,publication,guard.resourceGeneration(),coverageGeneration,SCENARIO_RELOAD_SEQUENCE.get(),digest,""));
        }
        void send(RollingGateControlPayload p){var g=Minecraft.getInstance();g.execute(()->{if(g.getConnection()==connection)PacketDistributor.sendToServer(p);});}
        void fenceCollision(){var binding=collision;if(binding==null){if(closed||guard.invalidationRequested())send(new RollingGateControlPayload(epoch,publication,guard.resourceGeneration(),RollingGateControlPayload.FENCED,"",false));return;}var g=Minecraft.getInstance();g.execute(()->{binding.fence();if(current==this&&g.level==carrier&&g.player!=null){g.player.setOnGround(false);g.player.resetFallDistance();g.player.setDeltaMovement(Vec3.ZERO);}if(closed||guard.invalidationRequested())send(new RollingGateControlPayload(epoch,publication,guard.resourceGeneration(),RollingGateControlPayload.FENCED,"",false));reportFence(binding);});}
        void reportFence(CoreCollisionBinding binding){var j=new JsonObject();j.addProperty("stage","COLLISION_FENCED");j.addProperty("epoch",epoch);j.addProperty("publication",publication);j.addProperty("closed",closed);j.addProperty("disposed",disposed);j.addProperty("collisionOwners",binding.residentOwners());j.addProperty("collisionReady",binding.ready());write("collision-fenced-"+publication+"-"+(closed?"closed":"active"),j);EarthPlatformMod.LOGGER.info("ROLLING_CLIENT_COLLISION_FENCED epoch={} publication={} closed={} owners={}",epoch,publication,closed,binding.residentOwners());}
        void updatePreparedPair(){
            if(visualRecoveryQueued)return;
            long start=System.nanoTime();
            try{
                pumpSourcePreparation();
                if(distanceMode()&&visualStream!=null&&visualPumpedFrame!=actualRenderFrame){visualPumpedFrame=actualRenderFrame;visualStream.pump(4,6_000_000L);}
                if(pending&&!digest.isEmpty()&&!preparedSent){
                    prepareCandidateWork();
                    if(preparedFar!=null&&!replacementDeclineSent){
                        long pumpStart=System.nanoTime();try{far.pump();}finally{farPump.record(pumpStart);}
                        if(far.isReplacementDeclined(preparedFar)){requestReplacementFallback(far.replacementDeclineReason(preparedFar));}
                        else {
                        if(!far.isCurrent(preparedFar))throw new IllegalStateException("Private DH ticket expired before preparation acknowledgement");
                        if(far.isPrepared(preparedFar)){
                            if(!validateCandidate())return;
                            preparedSent=true;send(new RollingGateControlPayload(epoch,publication,guard.resourceGeneration(),RollingGateControlPayload.PREPARED,digest,false));status=preparationStatus("Native and DH privately prepared; awaiting server allowance");
                        }else status=preparationStatus("Preparing private DH tiles");
                        }
                    }
                }
                if(allowed&&!pairSent){long pairStart=System.nanoTime();try{installPair();}finally{pairInstallation.record(pairStart);}}
            }finally{long elapsed=updateWork.record(start);maxUpdateNanos=Math.max(maxUpdateNanos,elapsed);}
        }
        void render(Matrix4f frameProjection,Matrix4f frameModelView,Vec3 frameCamera){synchronized(fence){
            if(!valid()||guard.invalidationRequested()||!installedCurrent())return;camera=frameCamera;updatePreparedPair();
            if(guard.invalidationRequested())return;
            var displayedStamp=activePresentation().stamp();
            long displayedPublication=activePresentation().frozen()?displayedStamp.publication():(visible?publishedPairPublication:-1);
            long displayedCoverage=activePresentation().frozen()?displayedStamp.coverage():(visible?coverageGeneration:-1);
            long displayedRevision=activePresentation().frozen()?displayedStamp.revision():(visible?replica.revision():-1);
            submissionProbe.setContext(++submissionFrame,epoch,publication,guard.resourceGeneration(),activePresentation().frozen(),displayedPublication,displayedCoverage,displayedRevision);
            if(activePresentation().frozen()){
                if(!edit||!pending||visible)throw new IllegalStateException("Frozen presentation cannot masquerade as a current pair");
                var view=new Matrix4f(frameModelView);
                boolean shown=presentation.draw((nativeTicket,farTicket)->depthComposition.draw(frameProjection,profile.lodLayout(),pass->{
                    if(guard.invalidationRequested())return false;
                    var projection=pass.projection();
                    var dhStage=submissionStage(pass.name(),false);long dhSpan=submissionProbe.start(dhStage,submittedFarOwners,submittedFarVertices);
                    Throwable dhFailure=null;
                    try{if(!far.drawPresented(farTicket,new Matrix4f(projection).mul(view),camera.x,camera.y,camera.z,guard.resourceGeneration()))return false;}
                    catch(RuntimeException|Error failure){dhFailure=failure;throw failure;}
                    finally{RollingSubmissionSpan.finish(dhFailure,()->submissionProbe.finish(dhStage,dhSpan));}
                    if(guard.invalidationRequested())return false;
                    var nativeStage=submissionStage(pass.name(),true);long nativeSpan=submissionProbe.start(nativeStage,submittedNativeOwners,submittedNativeVertices);
                    Throwable nativeFailure=null;try{for(var mesh:published.values()){
                        if(guard.invalidationRequested())return false;
                        if(!mesh.drawPresented(nativeTicket.tokenFor(mesh.presentationSource()),view,projection,camera.x,camera.y,camera.z,light.getId(),nativePhaseAttribution?submissionProbe:null))return false;
                    }}catch(RuntimeException|Error failure){nativeFailure=failure;throw failure;}finally{RollingSubmissionSpan.finish(nativeFailure,()->submissionProbe.finish(nativeStage,nativeSpan));}
                    return true;
                }));
                if(!shown){if(guard.invalidationRequested())return;throw new IllegalStateException("Retained edit presentation lost its exact ownership");}
                drawn=true;frozenDrawFrames++;
                if(!frozenDrawReported){frozenDrawReported=true;frozenCapture="FROZEN_EDIT_"+publication;report(frozenCapture);}
                if(!publishedNativeCurrent()){
                    frozenStaleRevisionFrames++;
                    if(!frozenStaleReported){frozenStaleReported=true;frozenCapture="FROZEN_PRIOR_REVISION_"+publication;report(frozenCapture);}
                }
                return;
            }
            if(!visible)return;
            for(var m:published.values())if(!m.isCurrent())throw new IllegalStateException("Published native dependency stale");
            if(!publishedFarCoverageReady())throw new IllegalStateException("Far coverage or center does not match visible native batch");
            var view=new Matrix4f(frameModelView);
            boolean shown=depthComposition.draw(frameProjection,profile.lodLayout(),pass->{
                if(guard.invalidationRequested())return false;
                var projection=pass.projection();
                var dhStage=submissionStage(pass.name(),false);long dhSpan=submissionProbe.start(dhStage,submittedFarOwners,submittedFarVertices);
                Throwable dhFailure=null;
                try{far.draw(new Matrix4f(projection).mul(view),camera.x,camera.y,camera.z,guard.resourceGeneration());}
                catch(RuntimeException|Error failure){dhFailure=failure;throw failure;}
                finally{RollingSubmissionSpan.finish(dhFailure,()->submissionProbe.finish(dhStage,dhSpan));}
                if(guard.invalidationRequested())return false;
                var nativeStage=submissionStage(pass.name(),true);long nativeSpan=submissionProbe.start(nativeStage,submittedNativeOwners,submittedNativeVertices);
                Throwable nativeFailure=null;try{for(var mesh:published.values()){
                    if(guard.invalidationRequested())return false;
                    if(!mesh.draw(view,projection,camera.x,camera.y,camera.z,light.getId(),nativePhaseAttribution?submissionProbe:null))return false;
                }}catch(RuntimeException|Error failure){nativeFailure=failure;throw failure;}finally{RollingSubmissionSpan.finish(nativeFailure,()->submissionProbe.finish(nativeStage,nativeSpan));}
                return true;
            });
            if(!shown){if(guard.invalidationRequested())return;throw new IllegalStateException("Native paired depth draw rejected");}drawn=true;
        }}
        private static RenderSubmissionProbe.Stage submissionStage(String pass,boolean nativeRenderer){
            return switch(pass){
                case "far" -> nativeRenderer?RenderSubmissionProbe.Stage.FAR_NATIVE:RenderSubmissionProbe.Stage.FAR_DH;
                case "near" -> nativeRenderer?RenderSubmissionProbe.Stage.NEAR_NATIVE:RenderSubmissionProbe.Stage.NEAR_DH;
                case "legacy" -> nativeRenderer?RenderSubmissionProbe.Stage.LEGACY_NATIVE:RenderSubmissionProbe.Stage.LEGACY_DH;
                default -> throw new IllegalArgumentException("Unknown depth pass for attribution");
            };
        }
        void prepareCandidateWork(){
            if(distanceMode()){
                // Main/shadow callbacks share one owner frame. There is no SceneMesh work here.
                if(nativeWorkPumpedFrame==actualRenderFrame)return;
                nativeWorkPumpedFrame=actualRenderFrame;prepareDistanceBody();return;
            }
            if(!nativeClassificationComplete){prepareOneNativeReuse();return;}
            // Every actually drawn legacy profile keeps its original model path and pacing.
            lastWorkPhase=meshWork.pump();meshWork.publishCompleted(this::acceptCandidateMesh);
            if(meshWork.isDrained()){
                if(!validateCandidate())return;
                if(preparedFar==null&&sourceVerified&&!replacementDeclineSent)prepareFarCoverage();
                else if(!sourceVerified)status=preparationStatus("Waiting for verified Earth provider preparation");
            }else status=preparationStatus("Slicing native candidate: queued "+meshWork.queuedTargets()+", active "+meshWork.activeBuilds());
        }
        void prepareDistanceBody(){
            if(!sourceVerified){status=preparationStatus("Waiting for verified Earth provider preparation");return;}
            if(!prepareReplacementInput()||!requestVisual())return;
            var plan=visualStream.drawPlan();
            if(plan==null){status=preparationStatus("Waiting for the exact private source plan");return;}
            if(bodyOwner==null)bodyOwner=new RollingDistanceBodyBinding.Owner(replica,guard,this::bodyResourcesCurrent);
            if(candidateBody==null){
                bodyOwner.accounting(earthResumeBootstrap==null?List.of():earthResumeBootstrap.cubes().values().stream().map(SceneCube::snapshot).toList());
                candidateBody=bodyOwner.begin(publication,visualStream.progress().stamp(),plan,proposedReplacement);
                bodyOwner.accounting(earthResumeBootstrap==null?List.of():earthResumeBootstrap.cubes().values().stream().map(SceneCube::snapshot).toList());
            }
            if(candidateBodyJoined==null){
                if(lastBodyEqualitySlice==null||!lastBodyEqualitySlice.complete()){
                long start=System.nanoTime();
                try{lastBodyEqualitySlice=candidateBody.step();}
                finally{bodyEqualityTiming.record(start);}
                bodyEqualitySlices++;bodyEqualityComparedCells=Math.addExact(bodyEqualityComparedCells,lastBodyEqualitySlice.comparisonsThisSlice());
                bodyEqualityCompletedComparisons=lastBodyEqualitySlice.completedComparisons();
                maxBodyEqualityOvershootNanos=Math.max(maxBodyEqualityOvershootNanos,lastBodyEqualitySlice.overshootNanos());
                if(!lastBodyEqualitySlice.complete()){status=preparationStatus(lastBodyEqualitySlice.entryGuardExhausted()?"Checking exact native resource ownership; body proof awaits the next bounded slice":"Proving exact native body125: "+bodyEqualityCompletedComparisons+" completed value comparisons");return;}
                }
                if(!prepareVisual())return;
                candidateBodyJoined=candidateBody.join(proposedVisual);
            }
            if(!validateCandidate())return;
            if(preparedFar==null&&!replacementDeclineSent)prepareFarCoverage();
        }
        void acceptCandidateMesh(CubePos pos,SceneMesh mesh){
            if(distanceMode())throw new IllegalStateException("Distance body cannot adopt a SceneMesh");
            // Register ownership before either map insertion or a later fence may fail.
            owned.add(mesh);candidate.put(pos,mesh);peakMeshes=Math.max(peakMeshes,owned.size());
            if(owned.size()>RollingCubePlan.MAX_STAGED_MESH_GENERATIONS)throw new IllegalStateException("Native mesh generation cap exceeded");
        }
        boolean publishedFarCoverageReady(){
            return far!=null&&publishedFarCenter!=null&&far.coverageReady(coverageGeneration,publishedFarCenter.x(),publishedFarCenter.z(),profile.lodLayout())&&(profile.arenaMode()?publishedArena!=null&&far.arenaReady(publishedArenaIdentity):profile.twoWorkspaces()?publishedSet!=null&&far.replacementSetReady(publishedSetIdentity):far.replacementReady(publishedCertificate==null?null:publishedCertificate.identity()));
        }
        boolean validateCandidate(){
            var nativeCenter=replica.stagedMetadata()==null?replica.currentCenter():replica.stagedMetadata().candidateCenter();
            if(!candidateCameraReady(nativeCenter))return false;
            if(desiredFarCenter==null||!desiredFarCenter.equals(RollingFarPublication.center(profile,nativeCenter)))throw new IllegalStateException("Private far center differs from the authoritative native candidate");
            if(distanceMode()){
                if(candidateBody==null||candidateBodyJoined==null||candidateBody.publication()!=publication||candidateBody.joined()!=candidateBodyJoined||!candidateBody.dependenciesCurrent())throw new IllegalStateException("Exact distance body125 proof is not current");
                candidateBodyJoined.requirePrepared(candidateBodyJoined.exactReady(),Objects.requireNonNull(proposedVisual),Objects.requireNonNull(proposedReplacement));
                if(!candidate.isEmpty()||!owned.isEmpty()||!nativeReuses.isEmpty()||!meshWork.isDrained())throw new IllegalStateException("Distance body acquired forbidden SceneMesh owners");
            }else{
            if(!nativeClassificationComplete||!nativeReuseQueue.isEmpty()||!meshWork.isDrained()||candidate.size()!=replica.candidateTargets().size())throw new IllegalStateException("Candidate queue drained before all targets were published");
            for(var m:candidate.values()){
                var reuse=nativeReuses.get(m);
                if(reuse!=null?!reuse.isCurrent():!m.isCurrent())throw new IllegalStateException("Private native candidate stale");
            }
            if(replica.candidatePinned()&&!candidate.containsKey(replica.candidateFocus()))throw new IllegalStateException("Edited central volume lost pinned native coverage");
            }
            if(!replica.candidatePinned()&&proposedCertificate==null&&proposedSet==null&&proposedArena==null)throw new IllegalStateException("Unpinned candidate lacks authoritative 3D replacement");
            if(profile.arenaMode()){RollingArenaQueryGuard.require(profile,proposedArena);verifyArenaNativeRevisions(proposedArena);}
            if(profile.twoWorkspaces()){RollingIndexedQueryGuard.require(profile,proposedSet,replica.candidateReady().candidateCenter());verifySetNativeRevisions(proposedSet);}
            if(proposedCertificate!=null&&proposedReplacement!=null&&(publishedCertificate==null||!publishedCertificate.identity().equals(proposedCertificate.identity()))){
                if(profile.alpineMode()){if(earthResumeBootstrap!=null)earthResumeBootstrap.require(proposedCertificate);RollingEarthCaptureValidation.verifyBindings(proposedCertificate,earthResumeBootstrap==null?replica.cubes():earthResumeBootstrap.cubes());}
                else RollingReplacementCapture.verifyBindings(proposedCertificate,replica.cubes(),true);
            }
            return true;
        }
        void prepareFarCoverage(){
            if(!validateCandidate())return;
            if(!sourceVerified)throw new IllegalStateException("Prepared provider identity is required");
            if(!prepareReplacementInput())return;
            if(!prepareVisual())return;
            if(far==null){long start=System.nanoTime();try{
                if(!sourceVerified)throw new IllegalStateException("Unverified provider cannot publish near/far coverage");
                final SceneSurfaceSampler earth=profile.arenaMode()?new io.earthplatform.minecraft.lod.DhArenaSurfaceSampler(profile.arenaSource()):preparedSource==null?null:preparedSource.sampler();
                if(earth!=null&&!earth.supportsFootprintCoverage())throw new IllegalStateException("Earth far geometry requires verified full-footprint source coverage");
                var sampler=countedSampler(earth,world.datasetVersion(),profile.anchorY(),sourceHeightCallbacks,sourceColorCallbacks);
                far=distanceMode()?new DhPreparedLodView(sampler,world,epoch,guard.resourceGeneration(),profile.fixedFarCenterX(),profile.fixedFarCenterZ(),profile.lodLayout(),installedRuntime,shaderFarBackend,io.earthplatform.minecraft.lod.DhNativeDistanceBudget.configuration()):shaderMode?new DhPreparedLodView(sampler,world,epoch,guard.resourceGeneration(),profile.fixedFarCenterX(),profile.fixedFarCenterZ(),profile.lodLayout(),installedRuntime,shaderFarBackend):new DhPreparedLodView(sampler,world,epoch,guard.resourceGeneration(),profile.fixedFarCenterX(),profile.fixedFarCenterZ(),profile.lodLayout(),installedRuntime);far.initialize();
            }finally{farInitialization.record(start);}}
            var ready=distanceMode()?Objects.requireNonNull(candidateBodyJoined).exactReady():candidate.values().stream().filter(m->profile.distantMaskEligible(m.snapshot().position())).map(m->{var snap=m.snapshot();return new SceneReadyCube(snap.position(),world,epoch,snap.leaseGeneration(),snap.revision(),guard.resourceGeneration());}).toList();
            preparedCoverageGeneration=Math.incrementExact(Math.max(coverageGeneration,preparedCoverageGeneration));long start=System.nanoTime();
            try{preparedFarCenter=desiredFarCenter;preparedFar=distanceMode()?far.prepareCoverageDistance(preparedCoverageGeneration,ready,preparedFarCenter.x(),preparedFarCenter.z(),proposedReplacement,proposedVisual,candidateBodyJoined):profile.arenaMode()?far.prepareCoverageArena(preparedCoverageGeneration,ready,preparedFarCenter.x(),preparedFarCenter.z(),proposedArenaInput):profile.twoWorkspaces()?far.prepareCoverageSet(preparedCoverageGeneration,ready,preparedFarCenter.x(),preparedFarCenter.z(),proposedSetInput):far.prepareCoverage(preparedCoverageGeneration,ready,preparedFarCenter.x(),preparedFarCenter.z(),proposedReplacement);}finally{farPreparation.record(start);}
            if(preparedFar==null)throw new IllegalStateException("DH preparation must return a retained exact ticket");
        }
        void installPair(){
            if(!allowed||!preparedSent||pairSent)throw new IllegalStateException("Server ALLOW required before prepared pair commit");
            if(!validateCandidate())return;
            if(preparedFar==null||preparedFarCenter==null||!preparedFarCenter.equals(desiredFarCenter)||!far.isPrepared(preparedFar))throw new IllegalStateException("Far prepared coverage or center lost before server allowance");
            try{
            visible=false;activePresentation().invalidate();if(distanceMode())activePresentation().retire();frozenCapture="";long start=System.nanoTime();try{if(!far.commitPrepared(preparedFar))throw new IllegalStateException("Far refused exact allowed prepared coverage");}finally{farCommit.record(start);}
            if(!far.coverageReady(preparedCoverageGeneration,preparedFarCenter.x(),preparedFarCenter.z(),profile.lodLayout())||!(profile.arenaMode()?proposedArena!=null&&far.arenaReady(proposedArenaInput.certificateIdentity()):profile.twoWorkspaces()?proposedSet!=null&&far.replacementSetReady(proposedSetInput.certificateIdentity()):far.replacementReady(proposedCertificate==null?null:proposedCertificate.identity())))throw new IllegalStateException("Far committed a different coverage generation or center");
            if(distanceMode()){var previousVisual=publishedVisual;publishedVisual=Objects.requireNonNull(proposedVisual);proposedVisual=null;visualStream.retainPresentation(publishedVisual);if(previousVisual!=null)previousVisual.close();}
            coverageGeneration=preparedCoverageGeneration;publishedFarCenter=preparedFarCenter;preparedFar=null;preparedFarCenter=null;
            activePresentation().retire();
            if(arenaAuthority!=null){arenaAuthority.commit(proposedArenaInput.certificateIdentity());publishedArena=proposedArena;publishedArenaIdentity=proposedArenaInput.certificateIdentity();arenaInput=proposedArenaInput;}
            else if(setAuthority!=null){setAuthority.commit(proposedSet.identity());publishedSet=proposedSet;publishedSetIdentity=proposedSet.identity();setInput=proposedSetInput;}else replacementAuthority.commit(proposedCertificate==null?null:proposedCertificate.identity());publishedCertificate=proposedCertificate;replacementInput=proposedReplacement;
            // The authenticated original27 remains charged through every pre-commit revalidation.
            if(earthResumeBootstrap!=null){earthResumeBootstrap.close();earthResumeBootstrap=null;}
            // ALLOW and exact far commit preceded this; historical tickets are now gone.
            // Commit fresh native bindings before exposing any current native coverage.
            long reuseStart=System.nanoTime();try{
                for(var reuse:nativeReuses.values()){reuse.commit();nativeFootprintCommitted++;}
                closeNativeReuses();
            }finally{nativeReuseCommit.record(reuseStart);}
            visible=false;
            if(distanceMode()){
                bodyOwner.install(Objects.requireNonNull(candidateBody));publishedBody=candidateBody;publishedBodyJoined=candidateBodyJoined;candidateBody=null;candidateBodyJoined=null;
                if(!publishedBodyJoined.isPublishedCurrent())throw new IllegalStateException("Installed distance body lost currentness");
                submittedNativeOwners=publishedVisual.drawnPieces().size();submittedNativeVertices=0;
                for(var piece:publishedVisual.drawnPieces()){var receipt=piece.completion();long mask=publishedVisual.drawnMemberMasks().getOrDefault(receipt.key(),0L);for(var range:receipt.memberRanges())if((mask&(1L<<range.member()))!=0)submittedNativeVertices=Math.addExact(submittedNativeVertices,4L*range.quads());}
            }else{
                Set<SceneMesh> keep=Collections.newSetFromMap(new IdentityHashMap<>());keep.addAll(candidate.values());closeExcept(keep);
                published.clear();published.putAll(candidate);candidate.clear();
                submittedNativeOwners=published.size();submittedNativeVertices=published.values().stream().mapToLong(SceneMesh::vertices).sum();
            }
            var drawContext=far.diagnostics();submittedFarOwners=drawContext.get("pageTileOwners").getAsInt();submittedFarVertices=drawContext.get("preparedVertexBytes").getAsLong()/16;
            publishedPairPublication=publication;publishedPairIdentity.publish(digest);visible=true;pairSent=true;
            send(new RollingGateControlPayload(epoch,publication,guard.resourceGeneration(),RollingGateControlPayload.PAIR_READY,digest,false));status="Exact pair installed; waiting for authoritative collision commit";report("PAIR_READY_"+publication);
            }catch(RuntimeException|Error failure){
                try{invalidateOwned();}catch(RuntimeException|Error fencing){if(fencing!=failure)failure.addSuppressed(fencing);}throw failure;
            }
        }
        void closeNativeReuses(){
            Throwable failure=null;
            for(var it=nativeReuses.values().iterator();it.hasNext();){
                try{it.next().close();it.remove();}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            }
            if(failure instanceof RuntimeException e)throw e;if(failure instanceof Error e)throw e;
        }
        void releaseBodyReservation(SceneMesh mesh){var r=visualBodyReservations.remove(mesh);if(r!=null)r.releaseAfterPhysicalClose();}
        void closeExcept(Set<SceneMesh> keep){Throwable failure=null;for(var it=owned.iterator();it.hasNext();){var m=it.next();if(keep.contains(m))continue;try{m.close();releaseBodyReservation(m);it.remove();}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}}if(failure instanceof RuntimeException e)throw e;if(failure instanceof Error e)throw e;}
        String preparationStatus(String phase){
            if(edit){var stamp=activePresentation().stamp();return phase+" | "+(activePresentation().frozen()&&stamp!=null?"frozen revision "+stamp.revision():"presentation fenced")+"; motion and interactions fenced";}
            if(!visible)return phase+" | bootstrap: no published pair; collision and interactions fenced";
            return phase+" | previous pair retained; collision "+(collision!=null&&collision.ready()?"active":"fenced");
        }
        String presentationLabel(){
            var stamp=activePresentation().stamp();
            if(activePresentation().frozen()&&stamp!=null)return "FROZEN VIEW: revision "+stamp.revision()+" | saving/rebuilding; motion and interactions fenced";
            if(!visible)return "VIEW WAITING: no active pair | collision and interactions fenced";
            return "CURRENT VIEW | collision "+(collision!=null&&collision.ready()?"active (125 known cubes)":"fenced; awaiting authoritative commit");
        }
        Path directory(){return Path.of("earthplatform-evidence","rolling-core-gate");}
        void write(String suffix,JsonObject j){if(profile.playerFacing()&&!io.earthplatform.compat.PlayableDiagnostics.enabled())return;try{Files.createDirectories(directory());Files.writeString(directory().resolve("epoch-"+epoch+"-"+suffix.toLowerCase(Locale.ROOT)+".json"),j+"\n");}catch(IOException failure){EarthPlatformMod.LOGGER.warn("Rolling evidence write failed",failure);}}
        void reportBodyAuthority(JsonObject j){
            if(bodyOwner!=null)lastBodyResourceGuardMetrics=bodyOwner.resourceGuardMetrics();
            if(lastBodyResourceGuardMetrics!=null)j.add("bodyResourceGuardTiming",new com.google.gson.Gson().toJsonTree(lastBodyResourceGuardMetrics));
            j.addProperty("bodyEqualityEntryGuardExhausted",lastBodyEqualitySlice!=null&&lastBodyEqualitySlice.entryGuardExhausted());
            j.addProperty("bodyPaletteRegistryCurrent",bodyPaletteCurrent());j.addProperty("bodyPaletteRegistrySize",bodyPaletteRegistrySize);
            j.addProperty("body125Required",125);j.addProperty("bodyTargetsRequired",27);
            j.addProperty("bodyCandidatePublication",candidateBody==null?-1:candidateBody.publication());
            j.addProperty("bodyPublishedPublication",publishedBody==null?-1:publishedBody.publication());
            j.addProperty("bodyPublishedCurrent",publishedNativeCurrent());
            j.addProperty("bodyCandidateDependenciesCurrent",candidateBody!=null&&candidateBody.dependenciesCurrent());
            j.addProperty("bodyCandidateNativeJoined",candidateBodyJoined!=null&&candidateBodyJoined.isPrepared());
            j.addProperty("bodyHistoryRetained",inspectedBodyHistory!=null);j.addProperty("bodyHistoryPresentable",inspectedBodyHistory!=null&&inspectedBodyHistory.isPresentable());
            j.addProperty("bodyCandidateSnapshotCount",candidateBody==null?0:candidateBody.snapshots().size());
            j.addProperty("bodyPublishedSnapshotCount",publishedBody==null?0:publishedBody.snapshots().size());
            j.addProperty("bodyCandidateProvedComparisons",candidateBody==null?0:candidateBody.completedComparisons());
            j.addProperty("bodyPublishedProvedComparisons",publishedBody==null?0:publishedBody.completedComparisons());
            j.addProperty("bodyCandidateReusedSnapshots",candidateBody==null?0:candidateBody.reusedSnapshots());
            j.addProperty("bodyPublishedReusedSnapshots",publishedBody==null?0:publishedBody.reusedSnapshots());
            j.addProperty("bodyCandidateComparedCells",candidateBody==null?0:candidateBody.comparisons());
            j.addProperty("bodyPublishedComparedCells",publishedBody==null?0:publishedBody.comparisons());
            j.addProperty("distanceBodyModelCallbacks",meshWork.metrics().reportedCallbacks());
            j.addProperty("distanceBodyMeshOwners",owned.size());j.addProperty("distanceBodyMeshReservations",visualBodyReservations.size());
            j.addProperty("distanceBodyMeshVertexBytes",owned.stream().mapToLong(SceneMesh::bytes).sum());
            j.addProperty("bodyCleanupPending",closed&&!bodyAliasesReleased());
            j.addProperty("nativeSourceAliasesReleased",bodyAliasesReleased()&&visualStream==null&&proposedVisual==null&&publishedVisual==null&&preparedSource==null&&sourcePreparation==null);
            j.addProperty("ownerMetadataReservationsReleased",visualCpuReservations.isEmpty());
            j.addProperty("bodyMetadataSubBudgetBytes",RollingDistanceBodyBinding.MAX_METADATA_BYTES);
            j.addProperty("bodyMetadataBudgetScope","Logical sub-budget within existing16MiB OWNER_METADATA reservation, not an additive pool or measured JVM heap");
        }
        void reportRetainedNativeInputs(JsonObject j){
            var progress=distanceProgress==null?lastDistanceProgress:distanceProgress.checkpoint();
            if(progress!=null)j.add("nativeDistanceProgress",new com.google.gson.Gson().toJsonTree(progress));
            if(distanceMode()){
                reportBodyAuthority(j);
                j.addProperty("nativeBodyAuthorityPolicy","exact125-source-value-v1");j.addProperty("bodyEqualityCompletedComparisons",bodyEqualityCompletedComparisons);j.addProperty("bodyEqualityComparedCells",bodyEqualityComparedCells);j.addProperty("bodyEqualitySlices",bodyEqualitySlices);j.addProperty("bodyEqualitySoftBudgetNanos",2_000_000L);j.addProperty("bodyEqualityPollCells",64);j.addProperty("bodyEqualityHardComparisonLimit",262_144);j.addProperty("bodyEqualityHardPreemptible",false);j.addProperty("maxBodyEqualityOvershootNanos",maxBodyEqualityOvershootNanos);j.add("bodyEqualityTiming",bodyEqualityTiming.json());if(lastBodyEqualitySlice!=null)j.add("lastBodyEqualitySlice",new com.google.gson.Gson().toJsonTree(lastBodyEqualitySlice));j.addProperty("bodyAliasesReleased",bodyAliasesReleased());j.addProperty("publishedBody125",publishedBody!=null);j.addProperty("candidateBody125",candidateBody!=null);j.addProperty("nativeDistanceProgressSchema",NativeDistanceProgressPayload.WIRE_VERSION);
                j.addProperty("nativeDistancePolicy",NativeDistanceLimits.POLICY);j.addProperty("nativeDistanceSelected",Minecraft.getInstance().options.renderDistance().get());j.addProperty("nativeDistanceEffective",Minecraft.getInstance().options.getEffectiveRenderDistance());
                if(visualCapsule!=null)j.add("nativeDistanceCapsule",new com.google.gson.Gson().toJsonTree(visualCapsule.evidence()));
                if(visualStream!=null)j.add("nativeDistanceStream",new com.google.gson.Gson().toJsonTree(visualStream.evidence()));
                j.add("nativeDistanceGpu",new com.google.gson.Gson().toJsonTree(NativeVisualBudget.global().evidence()));j.add("nativeDistanceCpu",new com.google.gson.Gson().toJsonTree(NativeDistanceCpuBudget.global().evidence()));
                j.add("nativeDistancePublicationTiming",visualPublicationTiming.json());j.addProperty("nativeDistanceResumeBootstrapSnapshots",earthResumeBootstrap==null?0:earthResumeBootstrap.retained());j.addProperty("nativeDistanceOracleCases",visualOracleIndex);j.addProperty("nativeDistanceOracleComplete",visualOracleComplete);j.addProperty("nativeDistanceOracleReused",visualOracleReused);if(visualCapsule!=null)j.addProperty("nativeDistanceOracleIdentity",nativeOracleIdentity(visualCapsule.capsule().identity()));
                if(publishedVisual!=null&&publishedVisual.isCurrent()){j.add("nativeDistanceStamp",new com.google.gson.Gson().toJsonTree(publishedVisual.stamp()));j.addProperty("nativeDistanceCoverageIdentity",publishedVisual.coverage().identity());j.addProperty("nativeDistanceDrawPages",publishedVisual.coverage().eligiblePages().size());j.addProperty("nativeDistancePlanFaces",publishedVisual.plan().quads());j.add("nativeDistanceCameraDomain",new com.google.gson.Gson().toJsonTree(publishedVisual.cameraDomain()));j.add("nativeDistanceDrawBounds",new com.google.gson.Gson().toJsonTree(publishedVisual.drawnBounds()));var d=publishedVisual.checkedDepth();j.addProperty("nativeDistanceGuaranteedRadius",d.guaranteedNativeRadius());j.addProperty("nativeDistanceNativeNear",d.nativeNear());j.addProperty("nativeDistanceNativeFar",d.nativeFar());
                    var masks=new com.google.gson.JsonArray();for(var entry:publishedVisual.drawnMemberMasks().entrySet()){var item=new JsonObject();item.addProperty("pageX",entry.getKey().x());item.addProperty("pageY",entry.getKey().y());item.addProperty("pageZ",entry.getKey().z());item.addProperty("mask",entry.getValue());item.addProperty("maskHex",String.format(Locale.ROOT,"%016x",entry.getValue()));masks.add(item);}j.add("nativeDistanceMemberMasks",masks);
                }
                var calls=new JsonObject();for(var pass:DhShaderPassGate.Pass.values()){var item=new JsonObject();item.addProperty("drawCalls",visualDrawCalls[pass.ordinal()]);item.addProperty("fullDrawCalls",visualFullDrawCalls[pass.ordinal()]);item.addProperty("rangeDrawCalls",visualRangeDrawCalls[pass.ordinal()]);calls.add(pass.name(),item);}j.add("nativeDistanceActualDrawCalls",calls);
            }
            Set<EngineCubeSnapshot> snapshots=Collections.newSetFromMap(new IdentityHashMap<>());
            if(!disposed)for(var cube:replica.cubes().values())snapshots.add(cube.snapshot());
            if(bodyOwner!=null){snapshots.addAll(bodyOwner.snapshots());j.add("nativeBodyLogicalAccounting",new com.google.gson.Gson().toJsonTree(bodyOwner.accounting(earthResumeBootstrap==null?List.of():earthResumeBootstrap.cubes().values().stream().map(SceneCube::snapshot).toList())));}
            for(var mesh:owned)snapshots.addAll(mesh.retainedSnapshots());
            if(inspectedNativeBuild!=null)snapshots.addAll(inspectedNativeBuild.retainedSnapshots());
            if(inspectedNativePresentation!=null)snapshots.addAll(inspectedNativePresentation.snapshots());
            long bytes=snapshots.stream().mapToLong(EngineCubeSnapshot::sectionBytes).sum();
            long coordinates=snapshots.stream().map(EngineCubeSnapshot::position).distinct().count();
            if(coordinates>RollingCubePlan.MAX_STAGED_OWNERS||snapshots.size()>RollingCubePlan.MAX_STAGED_OWNERS+1)throw new IllegalStateException("Native immutable snapshot retention exceeded the union plus one edit revision");
            j.addProperty("retainedNativeSnapshotCoordinateLimit",RollingCubePlan.MAX_STAGED_OWNERS);j.addProperty("retainedNativeSnapshotIdentityLimit",RollingCubePlan.MAX_STAGED_OWNERS+1);
            peakObservedRetainedSnapshotIdentities=Math.max(peakObservedRetainedSnapshotIdentities,snapshots.size());
            peakObservedRetainedSnapshotBytes=Math.max(peakObservedRetainedSnapshotBytes,bytes);
            j.addProperty("retainedNativeSnapshotIdentities",snapshots.size());j.addProperty("retainedNativeSnapshotCoordinates",coordinates);j.addProperty("retainedNativeSnapshotSectionBytes",bytes);j.addProperty("peakObservedRetainedNativeSnapshotIdentities",peakObservedRetainedSnapshotIdentities);j.addProperty("peakObservedRetainedNativeSnapshotSectionBytes",peakObservedRetainedSnapshotBytes);
            j.addProperty("retainedNativeSnapshotAccountingScope","Identity-deduplicated replica + exact current/candidate/history body snapshots + physical legacy mesh bindings + pending reuse + active builder whole neighborhood + historical presentation. Encoded section bytes only, not JVM heap or global caches.");
            j.addProperty("activeNativeRecorderAccountedBytes",inspectedNativeBuild==null?0:inspectedNativeBuild.footprintMetadataBytes());
        }
        void report(String stage){
            if(profile.playerFacing()&&!io.earthplatform.compat.PlayableDiagnostics.enabled())return;
            if(reports++>=256&&!stage.equals("CLOSED")&&!stage.equals("INSTALLED_CARRIER_CLOSED"))return;var j=new JsonObject();reportRetainedNativeInputs(j);
            j.addProperty("arenaMode",profile.arenaMode());
            if(profile.alpineMode()){
                j.add("alpineSourceEvidence",preparedSource==null?com.google.gson.JsonNull.INSTANCE:preparedSource.alpine().json());
                j.add("earthBodyEnvelope",new com.google.gson.Gson().toJsonTree(profile.bodyEnvelope()));
                j.add("earthWindowEnvelope",new com.google.gson.Gson().toJsonTree(profile.windowEnvelope()));
                j.addProperty("earthNativeModelPolicy",io.earthplatform.minecraft.enginebridge.EarthNativeEditAdmission.MODEL_POLICY);
                j.addProperty("earthNativeFaceLimit",io.earthplatform.minecraft.enginebridge.EarthNativeEditAdmission.MAX_FACES);
                j.addProperty("earthReplacementMaterialPolicy",io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission.MATERIAL_POLICY);
                j.addProperty("earthReplacementDetailPolicy",io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission.DETAIL_POLICY);
                j.addProperty("earthReplacementFaceLimit",io.earthplatform.minecraft.enginebridge.EarthReplacementAdmission.MAX_FACES);
                j.addProperty("earthReplacementCaptureIdentity",replacementInput==null?"":replacementInput.data().capture().contentDigest());
                j.addProperty("earthCandidateCaptureIdentity",proposedEarthCapture==null?"":proposedEarthCapture.contentIdentity());
                j.addProperty("earthReplacementValidationReads",earthCaptureValidation==null?0:earthCaptureValidation.completedReads());
                j.addProperty("earthCaptureOwnershipScope","Client immutable replacement arrays; actor and packet buffers have separate charged ownership");
                j.addProperty("earthEditPolicy",profile.editPolicy().id());
            }
            if(profile.arenaMode()){
                j.addProperty("arenaDefinitionIdentity",profile.arenaDefinition().identity());j.addProperty("arenaSourceIdentity",profile.arenaSource().identity());
                j.addProperty("arenaIdentity",publishedArenaIdentity==null?"NONE":publishedArenaIdentity);j.addProperty("arenaCurrentAuthority",publishedArenaIdentity!=null&&arenaAuthority.isCurrent(publishedArenaIdentity));
                j.addProperty("arenaRetainedAuthorities",arenaAuthority.retainedSets());j.addProperty("arenaCommittedCaptureBytes",arenaInput==null?0:arenaInput.data().declaredBytes());j.addProperty("arenaPrivateCaptureBytes",proposedArenaInput==null||proposedArenaInput==arenaInput?0:proposedArenaInput.data().declaredBytes());
                j.addProperty("arenaNativeDependencies",disposed?0:profile.arenaDefinition().dependencies().stream().filter(replica.cubes()::containsKey).count());
                if(publishedArena!=null){j.add("arenaCertificate",new com.google.gson.Gson().toJsonTree(publishedArena));j.addProperty("arenaDurableIdentity",publishedArena.durableIdentity());var revisions=new com.google.gson.JsonArray();for(var pos:profile.arenaDefinition().positions()){var value=new JsonObject();value.addProperty("position",pos.toString());value.addProperty("revision",publishedArena.revision(pos));value.addProperty("resident",!disposed&&replica.cubes().containsKey(pos));revisions.add(value);}j.add("arenaRevisions",revisions);}
            }
            j.addProperty("twoWorkspaces",profile.twoWorkspaces());j.addProperty("adjacentWorkspaces",profile.adjacentWorkspaces());j.addProperty("indexedWorkspaces",profile.indexedWorkspaces());
            j.addProperty("activeFocus",disposed?"closed":replica.activeFocus().toString());
            j.addProperty("replacementSetIdentity",publishedSet==null?"NONE":publishedSetIdentity);
            if(profile.twoWorkspaces())j.addProperty("analyticFlatPolicyIdentity",profile.flatPolicy().identity());
            if(publishedSet!=null)j.add("replacementSetCertificate",new com.google.gson.Gson().toJsonTree(publishedSet));
            j.addProperty("replacementSetCurrentAuthority",publishedSet!=null&&setAuthority!=null&&setAuthority.isCurrent(publishedSet.identity()));
            j.addProperty("replacementSetRetainedAuthorities",setAuthority==null?0:setAuthority.retainedSets());
            j.addProperty("replacementSetCommittedCaptureBytes",setInput==null?0:setInput.data().declaredBytes());
            j.addProperty("replacementSetPrivateCaptureBytes",proposedSetInput==null||proposedSetInput==setInput?0:proposedSetInput.data().declaredBytes());
            if(publishedSet!=null){var regions=new com.google.gson.JsonArray();for(var region:publishedSet.regions()){var item=new JsonObject();item.addProperty("position",region.position().toString());item.addProperty("revision",region.revision());item.addProperty("captureIdentity",region.captureIdentity());item.addProperty("nativeCubeResident",!disposed&&replica.cubes().containsKey(region.position()));item.addProperty("nativeNeighborhoodResident",disposed?0:region.dependencies().stream().filter(d->replica.cubes().containsKey(d.position())).count());regions.add(item);}j.add("replacementSetRegions",regions);}
            j.addProperty("entryResourceSequence",entryResourceSequence);j.addProperty("currentResourceSequence",SCENARIO_RELOAD_SEQUENCE.get());j.addProperty("sessionDescriptor",profile.fingerprint());j.addProperty("sourceFingerprint",profile.sourceFingerprint());
            j.addProperty("sourceIdentityVerified",sourceVerified);j.addProperty("retainedClientEarthProvider",preparedSource!=null);j.addProperty("sourceWorker",sourceWorker);j.addProperty("sourcePreparationNanos",sourcePrepareNanos);
            j.addProperty("sourceUrl",sourceUrl);j.addProperty("verticalDatum",sourceDatum);j.addProperty("naturalRelief",profile.naturalRelief());j.addProperty("alpineMode",profile.alpineMode());j.addProperty("playerFacing",profile.playerFacing());
            j.add("bodyEnvelope",new com.google.gson.Gson().toJsonTree(profile.bodyEnvelope()));j.add("windowEnvelope",new com.google.gson.Gson().toJsonTree(profile.windowEnvelope()));
            j.addProperty("farCenterPolicy",RollingFarPublication.POLICY_VERSION);j.addProperty("farCenterSnapBlocks",profile.lodLayout().snapBlocks());j.addProperty("farCenterAlignmentBlocks",profile.lodLayout().centerAlignmentBlocks());j.addProperty("farLayout",profile.lodLayout().id());j.addProperty("farLayoutIdentity",profile.lodLayout().identity());j.addProperty("nativeManifestDigest",nativeManifestDigest);j.addProperty("pairPublicationDigest",digest);
            if(desiredFarCenter!=null)j.add("desiredFarCenter",new com.google.gson.Gson().toJsonTree(desiredFarCenter));if(preparedFarCenter!=null)j.add("pendingFarCenter",new com.google.gson.Gson().toJsonTree(preparedFarCenter));if(publishedFarCenter!=null)j.add("publishedFarCenter",new com.google.gson.Gson().toJsonTree(publishedFarCenter));
            j.addProperty("initialFarAnchorFieldsScope","requestedFarCenter and alignedFarCenter describe the immutable entry anchor, not the moving published center");
            j.addProperty("saveDirectory",profile.saveDirectory());j.addProperty("requestedFarCenterX",profile.fixedFarCenterX());j.addProperty("requestedFarCenterZ",profile.fixedFarCenterZ());j.addProperty("alignedFarCenterX",Math.floorDiv(profile.fixedFarCenterX(),profile.lodLayout().centerAlignmentBlocks())*profile.lodLayout().centerAlignmentBlocks());j.addProperty("alignedFarCenterZ",Math.floorDiv(profile.fixedFarCenterZ(),profile.lodLayout().centerAlignmentBlocks())*profile.lodLayout().centerAlignmentBlocks());
            j.addProperty("providerPreparationTicketState",sourcePreparation==null?"NONE":sourcePreparation.state().name());
            j.addProperty("providerPreparationPhysicallySettled",sourcePreparation==null||sourcePreparation.physicalSettled());
            j.addProperty("providerPreparationAcknowledged",sourcePreparation==null||sourcePreparation.completionAcknowledged());
            j.addProperty("closingSourcePreparationPending",closingSourcePreparationPending);
            j.add("processProviderPreparation",new com.google.gson.Gson().toJsonTree(BoundedProviderPreparation.processDiagnostics()));
            long footprintBytes=owned.stream().mapToLong(SceneMesh::footprintMetadataBytes).sum();peakNativeFootprintAccountedBytes=Math.max(peakNativeFootprintAccountedBytes,footprintBytes);
            j.addProperty("nativeFootprintObservations",owned.stream().mapToLong(SceneMesh::footprintReadCount).sum());j.addProperty("nativeFootprintUniquePositions",owned.stream().mapToLong(SceneMesh::footprintPositionCount).sum());j.addProperty("nativeFootprintAccountedBytes",footprintBytes);j.addProperty("peakNativeFootprintAccountedBytes",peakNativeFootprintAccountedBytes);j.addProperty("nativeFootprintAccountingIsMeasuredHeap",false);j.addProperty("nativeFootprintAccountingScope","Completed physical meshes only; one live recorder is independently capped at32768 observations. Accounting units are not JVM heap measurements.");j.addProperty("nativeFootprintNonRevalidatableMeshes",owned.stream().filter(m->!m.footprintRevalidatable()).count());j.addProperty("nativeReuseComparedValues",nativeReuseComparedValues);j.addProperty("nativeReuseValueComparisonLimitPerFrame",SceneMesh.MAX_REUSE_VALUE_COMPARISONS);j.addProperty("nativeFootprintObservationLimitPerMesh",SceneMesh.MAX_FOOTPRINT_OBSERVATIONS);
            j.addProperty("nativeReuseQueuedTargets",nativeReuseQueue.size());j.addProperty("peakNativeReuseQueuedTargets",peakNativeReuseQueue);j.addProperty("closingNativeReuseQueuedTargets",closingNativeReuseQueue);j.addProperty("nativeReuseMeshesPerFrameLimit",1);j.addProperty("nativePrivateReuseBindings",nativeReuses.size());j.addProperty("nativeStrictReused",nativeStrictReused);j.addProperty("nativeFootprintPrepared",nativeFootprintPrepared);j.addProperty("nativeFootprintRejected",nativeFootprintRejected);j.addProperty("nativeFootprintCommitted",nativeFootprintCommitted);j.addProperty("lastNativeStrictReused",lastNativeStrictReused);j.addProperty("lastNativeFootprintPrepared",lastNativeFootprintPrepared);j.addProperty("lastNativeRebuildTargets",lastNativeRebuildTargets);j.add("nativeReusePreparationTiming",nativeReusePreparation.json());j.add("nativeReuseCommitTiming",nativeReuseCommit.json());
            j.add("tempFileShim",tempFileShimEvidence());j.addProperty("installedShaderBackend",shaderMode);if(shaderMode)j.add("shaderTextureAliases",shaderTextureAliasEvidence());j.addProperty("shaderPreparedFrame",shaderPreparedFrame);j.addProperty("shaderDrawFrame",shaderDrawFrame);j.addProperty("shaderMainDrawMask",shaderMainDrawMask);j.addProperty("shaderFramePairDigest",shaderFrameDigest);j.addProperty("shaderFrameHistorical",shaderFrameHistorical);j.addProperty("shaderNativeSubmissions",shaderNativeSubmissions);j.addProperty("shaderFarSubmissions",shaderFarSubmissions);j.addProperty("shaderNativeStride",shaderNativeContract==null?0:shaderNativeContract.values().stride());j.addProperty("shaderFixtureLightingPolicy",shaderMode?"outdoor sky15 block0; no propagated lighting":"diagnostic fullbright");j.addProperty("shaderDualDepthComposition",false);j.addProperty("shaderNativeRestorationPending",shaderMode&&NativeIrisShaderStages.restorationPending());
            if(shaderCarrier!=null){j.add("shaderCarrier",new com.google.gson.Gson().toJsonTree(shaderCarrier.counters()));j.addProperty("shaderCarrierOwned",shaderCarrier.ownsCarrier());j.addProperty("shaderPipelineAcquired",shaderCarrier.pipelineAcquired());j.addProperty("shaderFailure",shaderCarrier.failure());j.addProperty("shaderDepthValidatedPasses",shaderCarrier.depthValidatedPasses());j.add("shaderDepthReceipt",new com.google.gson.Gson().toJsonTree(shaderCarrier.depthReceipt()));j.addProperty("shaderRequiredProjectionUniform","dhProjection");j.addProperty("shaderRequiredModelViewUniform","iris_ModelViewMatrix");var actualShaderCounts=new JsonObject();for(var pass:DhShaderPassGate.Pass.values())actualShaderCounts.addProperty(pass.name(),shaderCarrier.submissions(pass));j.add("shaderGatePassSubmissions",actualShaderCounts);}
            var shaderCounts=new JsonObject();for(var pass:DhShaderPassGate.Pass.values())shaderCounts.addProperty(pass.name(),shaderPassSubmissions[pass.ordinal()]);j.add("shaderPassSubmissions",shaderCounts);
            j.addProperty("installedDhCarrierDescriptor",profile.fingerprint());j.addProperty("installedDhBackend",true);j.addProperty("installedDhVersion",InstalledDhResolver.VERSION);j.addProperty("installedDhApiVersion",InstalledDhResolver.API);j.addProperty("installedDhArtifactSha256",InstalledDhResolver.SHA256);j.addProperty("installedDhFromNeoForgeModList",true);j.addProperty("installedDhCallbackFrames",installedCallbackFrames);j.addProperty("installedDhLastFrame",lastInstalledFrame);j.addProperty("installedDhCallbackFailure",installedCallbackFailure);j.addProperty("afterLevelSubmissions",0);if(installedGate!=null)j.add("installedDhCarrier",new com.google.gson.Gson().toJsonTree(installedGate.counters()));j.addProperty("installedDhEventBindings",installedEvents==null?0:installedEvents.retainedBindings());
            j.addProperty("stage",stage);j.addProperty("epoch",epoch);j.addProperty("profile",profile.id());j.addProperty("publication",publication);j.addProperty("coverageGeneration",coverageGeneration);j.addProperty("resourceGeneration",guard.resourceGeneration());j.addProperty("world",world.toString());j.addProperty("residentCubes",disposed?0:replica.cubes().size());j.addProperty("peakResidentCubes",peakResident);j.addProperty("publishedNativeMeshes",published.size());j.addProperty("candidateNativeMeshes",candidate.size());j.addProperty("ownedNativeMeshes",owned.size());j.addProperty("peakOwnedNativeMeshes",peakMeshes);j.addProperty("nativeVertexBytes",owned.stream().mapToLong(SceneMesh::bytes).sum());j.addProperty("nativeVertexByteCap",(long)RollingCubePlan.MAX_STAGED_MESH_GENERATIONS*SceneMesh.MAX_VERTEX_BYTES);j.addProperty("nativeSequentialIndexUpperBoundBytes",owned.stream().mapToLong(m->(long)m.vertices()/4*6*4).sum());j.addProperty("maxConcurrentCpuMeshBuilders",1);j.addProperty("ownedCpuMeshBuilders",meshWork.activeBuilds());j.addProperty("retainedCompletedMeshSlots",meshWork.completedSlots());j.addProperty("queuedNativeTargets",meshWork.queuedTargets());j.addProperty("lastNativeWorkPhase",lastWorkPhase.name());j.addProperty("closingQueuedNativeTargets",closingQueuedTargets);j.addProperty("closingActiveCpuBuilds",closingActiveBuilds);j.addProperty("closingRetainedMeshSlots",closingCompletedSlots);j.addProperty("closingPublicationPending",closingPublicationPending);j.addProperty("closingPreparedSent",closingPreparedSent);j.addProperty("peakNativeGenerationReservations",peakMeshReservations);j.addProperty("maxCpuBuilderLayerCapacityBytes",3L*SceneMesh.MAX_VERTEX_BYTES);j.addProperty("acceptedSnapshots",acceptedSnapshots);j.addProperty("targetRevision",disposed?-1:replica.revision());j.addProperty("publicationPending",pending);j.addProperty("pairedDrawVisible",visible);j.addProperty("frozenPresentationVisible",activePresentation().frozen());j.addProperty("frozenPresentationTicketOwners",activePresentation().retainedTicketOwners());j.addProperty("frozenPresentationCaptures",activePresentation().captures());j.addProperty("frozenPresentationDraws",activePresentation().draws());j.addProperty("frozenPresentationDrawFrames",frozenDrawFrames);j.addProperty("frozenPriorRevisionDrawFrames",frozenStaleRevisionFrames);j.addProperty("strictCurrentPublishedNativeMeshes",published.values().stream().filter(SceneMesh::isCurrent).count());j.addProperty("frozenPresentationProvidesCollisionOrReadiness",false);if(activePresentation().stamp()!=null)j.add("frozenPresentationStamp",new com.google.gson.Gson().toJsonTree(activePresentation().stamp()));j.addProperty("collisionOwners",collision==null?0:collision.residentOwners());j.addProperty("collisionReady",collision!=null&&collision.ready());j.addProperty("closed",closed);j.addProperty("disposed",disposed);j.addProperty("maxCpuUpdateNanos",maxUpdateNanos);j.addProperty("nativeCameraRecoveryCount",visualRecoveryCount);j.addProperty("nativeCameraRecoveryReason",visualRecoveryReason);j.addProperty("nativeCameraRecoveryQueued",visualRecoveryQueued);j.addProperty("nativeCameraHandoverPending",visualFrontier.awaiting());j.addProperty("nativeCameraX",camera.x);j.addProperty("nativeCameraY",camera.y);j.addProperty("nativeCameraZ",camera.z);j.addProperty("lastEditX",lastEditX);j.addProperty("lastEditY",lastEditY);j.addProperty("lastEditZ",lastEditZ);j.addProperty("editedCentralMeshPinned",!disposed&&replica.currentPinned());j.addProperty("candidatePinRequired",!disposed&&replica.candidatePinned());j.addProperty("replacementCaptures",replacementCaptures);j.addProperty("replacementCaptureReads",replacementCaptureReads);j.addProperty("replacementDeclines",replacementDeclines);j.addProperty("replacementCertificatesRetained",replacementAuthority.retainedCertificates());j.addProperty("replacementCaptureStagingBytes",replacementCapture==null?0:replacementCapture.retainedBytes());j.addProperty("replacementCommittedCaptureBytes",replacementInput==null?0:replacementInput.data().declaredBytes());j.addProperty("replacementPrivateCaptureBytes",proposedReplacement==null||proposedReplacement==replacementInput?0:proposedReplacement.data().declaredBytes());j.addProperty("replacementCaptureReadBudget",RollingReplacementCapture.READS_PER_STEP);j.addProperty("originalCubeResident",!disposed&&replica.cubes().containsKey(profile.target()));j.addProperty("originalNeighborhoodResidentCount",disposed?0:io.earthplatform.core.CubeTickets.box(profile.target(),1,1,27).stream().filter(replica.cubes()::containsKey).count());j.addProperty("nativePinMeshPublished",published.containsKey(profile.target()));j.addProperty("replacementCurrentAuthority",publishedCertificate!=null&&replacementAuthority.isCurrent(publishedCertificate.identity()));j.addProperty("replacementDurableRevision",publishedCertificate==null?-1:publishedCertificate.originalRevision());j.add("replacementCaptureTiming",replacementCaptureTiming.json());if(publishedCertificate!=null)j.add("replacementCertificate",new com.google.gson.Gson().toJsonTree(publishedCertificate));j.addProperty("immutableOneBlockEditShell",profile.editPolicy()==io.earthplatform.minecraft.enginebridge.RollingEditPolicy.INTERIOR_V1);j.addProperty("editAdmissionPolicy",profile.editPolicy().id());j.addProperty("hardwareGpuPerformanceClaim",false);if(!disposed&&replica.currentCenter()!=null)j.addProperty("currentCenter",replica.currentCenter().toString());j.addProperty("farSourceHeightCallbacks",sourceHeightCallbacks.get());j.addProperty("farSourceColorCallbacks",sourceColorCallbacks.get());j.addProperty("farSourceCounterScope","Atomic totals of actual concurrent pinned source sampler calls; not distinct cells, owner-frame work, or DEM I/O");j.addProperty("privateFarTicketRetained",preparedFar!=null);j.addProperty("farTicketPrepared",preparedFar!=null&&!closed&&far.isPrepared(preparedFar));j.addProperty("preparedCoverageGeneration",preparedCoverageGeneration);j.add("nativeWork",new com.google.gson.Gson().toJsonTree(meshWork.metrics()));j.addProperty("nativeCallbackBudgetNanos",RollingMeshWorkQueue.SOFT_SLICE_NANOS);j.addProperty("nativeOwnerSliceBudgetNanos",RollingMeshWorkQueue.SOFT_SLICE_NANOS);j.addProperty("nativeOwnerSliceOperationLimit",RollingMeshWorkQueue.MAX_OPERATIONS_PER_SLICE);j.addProperty("nativeOwnerSliceCallbackLimit",RollingMeshWorkQueue.MAX_CALLBACKS);j.addProperty("nativeOwnerSliceHardPreemptible",false);if(lastNativeOwnerSlice!=null)j.add("lastNativeOwnerSlice",new com.google.gson.Gson().toJsonTree(lastNativeOwnerSlice));j.addProperty("nativeCallbackHardLimit",RollingMeshWorkQueue.MAX_CALLBACKS);j.addProperty("nativeWorkSlicedOnOwnerThread",true);if(visualCaptureWork!=null)j.add("capsuleOwnerWork",new com.google.gson.Gson().toJsonTree(visualCaptureWork.metrics()));if(lastCapsuleOwnerSlice!=null)j.add("lastCapsuleOwnerSlice",new com.google.gson.Gson().toJsonTree(lastCapsuleOwnerSlice));j.addProperty("capsuleOwnerSliceBudgetNanos",NativeCapsuleWorkSlice.SOFT_SLICE_NANOS);j.addProperty("capsuleOwnerSliceStepLimit",NativeCapsuleWorkSlice.MAX_STEPS);j.addProperty("capsuleCasesPerStepLimit",NativeCapsuleWorkSlice.CASES_PER_STEP);j.addProperty("capsuleOwnerSliceHardPreemptible",false);j.add("capsuleCaptureAndSealTiming",capsuleCaptureAndSealTiming.json());j.add("capsuleSealTiming",capsuleSealTiming.json());j.addProperty("maxCapsuleOwnerOvershootNanos",maxCapsuleOwnerOvershootNanos);j.addProperty("timingClock","Legacy owner-thread elapsed timers; renderSubmissionProbe separately records paired current-thread CPU and wall boundaries, never GPU duration");j.addProperty("nativeSubmissionPhasesEnabled",nativePhaseAttribution);j.addProperty("publishedPairPublication",publishedPairPublication);j.addProperty("publishedPairDigest",publishedPairIdentity.digest());j.addProperty("nativeFinishUploadSliced",false);j.addProperty("dhCoveragePreparedBeforeServerFence",true);j.addProperty("dhFixedRadiusBlocks",profile.lodLayout().radiusBlocks());j.add("depthComposition",depthComposition.diagnostics());j.add("renderSubmissionProbe",submissionProbe.diagnostics());j.addProperty("dhCenterFixed",false);j.addProperty("dhCenterAuthoritative",true);j.add("farInitializationTiming",farInitialization.json());j.add("farPreparationSubmissionTiming",farPreparation.json());j.add("farPrivatePumpTiming",farPump.json());j.add("farAllowedCommitTiming",farCommit.json());j.add("pairInstallationTiming",pairInstallation.json());j.add("updateWorkTiming",updateWork.json());if(far!=null)j.add("distantHorizons",far.diagnostics());write(stage,j);EarthPlatformMod.LOGGER.info("ROLLING_{} epoch={} publication={} residents={} meshes={} collision={}",stage,epoch,publication,disposed?0:replica.cubes().size(),owned.size(),collision==null?0:collision.residentOwners());
        }
        void afterHud(){
            if(profile.playerFacing()&&!io.earthplatform.compat.PlayableDiagnostics.enabled())return;
            if(!scenarioCapture.isEmpty()&&drawn&&!pending){
                String label=scenarioCapture;var checkpoint=scenarioCheckpoint;
                if(checkpoint==null||!checkpoint.matches(epoch,publication,guard.resourceGeneration(),coverageGeneration,digest,label)||!visible||!publishedFarCoverageReady())throw new IllegalStateException("Scenario checkpoint authority changed before image capture");
                scenarioCapture="";scenarioCheckpoint=null;
                try(var image=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())){
                    Files.createDirectories(directory());image.writeToFile(directory().resolve("epoch-"+epoch+"-"+label+".png"));scenarioScreenshots++;
                    PacketDistributor.sendToServer(new RollingScenarioReceiptPayload(epoch,publication,guard.resourceGeneration(),coverageGeneration,SCENARIO_RELOAD_SEQUENCE.get(),digest,label));
                }catch(IOException failure){throw new IllegalStateException("Scenario screenshot checkpoint failed",failure);}
            }
            if(!frozenCapture.isEmpty()){
                String stage=frozenCapture;frozenCapture="";
                if(activePresentation().frozen()&&drawn&&screenshots<12)captureScreenshot(stage);
            }
            if(!capture.isEmpty()&&(pending||!capture.equals("READY_"+publication))){capture="";return;}
            if(capture.isEmpty()||!drawn||screenshots>=12)return;String stage=capture;capture="";captureScreenshot(stage);
        }
        void captureScreenshot(String stage){try(var image=Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())){Files.createDirectories(directory());image.writeToFile(directory().resolve("epoch-"+epoch+"-"+stage.toLowerCase(Locale.ROOT)+".png"));screenshots++;}catch(IOException failure){EarthPlatformMod.LOGGER.warn("Rolling screenshot failed",failure);}}
        void closeInstalledBridge(){
            invalidate();
            if(shaderCarrier!=null)shaderCarrier.close();
            if(installedEvents!=null)installedEvents.close();
            // Also covers failures before the gate accepted resource ownership.
            close();
            if(!installedClosureReported){installedClosureReported=true;report("INSTALLED_CARRIER_CLOSED");}
        }
        public void close(){synchronized(fence){
            if(disposed)return;if(!closed){closingNativeReuseQueue=nativeReuseQueue.size();closingQueuedTargets=meshWork.queuedTargets();closingActiveBuilds=meshWork.activeBuilds();closingCompletedSlots=meshWork.completedSlots();closingPublicationPending=pending;closingPreparedSent=preparedSent;}closed=true;publishedPairIdentity.revoke();if(bodyOwner!=null)bodyOwner.revoke();replacementAuthority.close();if(setAuthority!=null)setAuthority.close();if(arenaAuthority!=null)arenaAuthority.close();closeReplacementCapture();visible=false;activePresentation().invalidate();closingSourcePreparationPending=sourcePreparation!=null&&!sourcePreparation.physicalSettled();cancelSourcePreparation();if(earthResumeBootstrap!=null){earthResumeBootstrap.close();earthResumeBootstrap=null;}guard.close();fenceCollision();Throwable failure=null;
            if(shaderMode)try{NativeIrisShaderStages.restorePendingState();}catch(RuntimeException|Error e){failure=e;}
            try{activePresentation().close();}catch(RuntimeException|Error e){if(failure==null)failure=e;else if(failure!=e)failure.addSuppressed(e);}
            try{depthComposition.close();}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            try{meshWork.close();}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            try{closeNativeReuses();}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            try{closeExcept(Set.of());}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            try{closeBodyAliases();}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            try{if(far!=null)far.close();}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            if(distanceMode())try{
                visualBatch.close();if(proposedVisual!=null){proposedVisual.close();proposedVisual=null;}if(publishedVisual!=null){publishedVisual.close();publishedVisual=null;}
                if(visualOracle!=null){visualOracle.close();visualOracle=null;}
                if(failure==null&&visualStream!=null){if(!bodyAliasesReleased())throw new IllegalStateException("Distance body source aliases still owned");visualStream.close();if(!visualStream.closeComplete())throw new IllegalStateException("Native physical CPU writer is still draining");visualStream=null;}
                if(failure==null){if(sourcePreparation!=null&&!sourcePreparation.physicalSettled())throw new IllegalStateException("Native source preparation is still physically draining");
                    preparedSource=null;sourcePreparation=null;
                    if(visualCaptureOwner!=null){visualCaptureOwner.close();visualCaptureOwner=null;}visualCapsule=null;
                    for(var r:visualCpuReservations)r.releaseAfterPhysicalDrain();visualCpuReservations.clear();}
            }catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            try{if(light!=null){light.close();light=null;}}catch(RuntimeException|Error e){if(failure==null)failure=e;else failure.addSuppressed(e);}
            if(failure instanceof RuntimeException e)throw e;if(failure instanceof Error e)throw e;
            published.clear();candidate.clear();nativeReuseQueue.clear();nativeRebuildTargets.clear();preparedFar=null;preparedFarCenter=null;preparedSource=null;proposedReplacement=null;replacementInput=null;proposedCertificate=null;publishedCertificate=null;proposedSet=null;publishedSet=null;publishedSetIdentity=null;proposedSetInput=null;setInput=null;proposedArena=publishedArena=null;proposedArenaInput=arenaInput=null;publishedArenaIdentity=null;replica.close();disposed=true;report("CLOSED");
        }}
        void closeBodyAliases(){
            if(bodyOwner!=null){bodyOwner.revoke();lastBodyResourceGuardMetrics=bodyOwner.resourceGuardMetrics();bodyOwner.close();if(!bodyOwner.aliasesReleased())throw new IllegalStateException("Body cleanup has retained source aliases");}
            if(distancePresentation.retainsOwners()||inspectedBodyHistory!=null)throw new IllegalStateException("Body history cleanup remains owned");
            candidateBodyJoined=null;publishedBodyJoined=null;candidateBody=null;publishedBody=null;lastBodyEqualitySlice=null;bodyOwner=null;bodyPaletteRevoked=true;bodyPaletteStates=null;bodyPaletteIds=null;
        }
        boolean bodyAliasesReleased(){return bodyOwner==null&&bodyPaletteStates==null&&bodyPaletteIds==null&&candidateBody==null&&publishedBody==null&&candidateBodyJoined==null&&publishedBodyJoined==null&&inspectedBodyHistory==null&&!distancePresentation.retainsOwners();}
        private static final class WorkTiming {
            long calls,totalNanos,maxNanos;
            long record(long start){long elapsed=Math.max(0,System.nanoTime()-start);calls=calls==Long.MAX_VALUE?calls:calls+1;totalNanos=Long.MAX_VALUE-totalNanos<elapsed?Long.MAX_VALUE:totalNanos+elapsed;maxNanos=Math.max(maxNanos,elapsed);return elapsed;}
            JsonObject json(){var j=new JsonObject();j.addProperty("calls",calls);j.addProperty("totalNanos",totalNanos);j.addProperty("maxNanos",maxNanos);return j;}
        }

    }
}
