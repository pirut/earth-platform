package io.earthplatform.minecraft.scene;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.compat.dh.InstalledIrisRuntime;
import io.earthplatform.compat.dh.shader.StrictGlErrors;
import io.earthplatform.geography.WorldManifest;
import io.earthplatform.geography.survival.MarinSurvivalDefinition;
import io.earthplatform.minecraft.EarthPlatformMod;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.survival.render.*;
import io.earthplatform.minecraft.survival.source.MarinVisualCubeSource;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import io.earthplatform.minecraft.visual.NativeVisualBudget;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import static org.lwjgl.opengl.GL33C.*;

/**
 * INTERNAL finite, explicitly opt-in installed-client model/byte diagnostic. Never a gameplay
 * renderer, native-distance draw, complete-overlay certificate, or DH complement. No automatic
 * launch, settings mutation, world entry, or client exit. One synchronous internally sliced case
 * (or source page) per eligible rendered frame; wall time is recorded, NOT a frame-budget claim.
 * Every cleanup owner is retained before work. Property-off events and mixin hook are no-ops.
 */
@EventBusSubscriber(modid=EarthPlatformMod.MOD_ID,value=Dist.CLIENT)
public final class SurvivalNativePageParityClient {
    public static final String ENABLED="earthplatform.experimental.survivalNativePageParity";
    public static final String LAYOUT="earthplatform.experimental.survivalNativePageParity.layout";
    public static final String REPORT="earthplatform.experimental.survivalNativePageParity.report";
    public static final String SOURCE_ROOT="earthplatform.experimental.survivalNativePageParity.sourceRoot";
    public static final int SHARED_INDEX_BYTES=393_216, SHARED_INDEX_COUNT=98_304;
    public static final long SHARED_INDEX_CHARGE=462_848;
    static final int ELIGIBILITY_FRAMES=600;
    private static final AtomicLong RESOURCE_EPOCH=new AtomicLong(1);
    // This process-owned reservation is deliberately NEVER released while this GL context exists.
    // Minecraft owns the index buffer; there is no diagnostic delete or pretend-close of that buffer.
    private static SharedIndexBaseline sharedIndex;
    private static Session session;
    private static boolean attempted;
    private static long frame,eligibleFrame=Long.MIN_VALUE;
    private static EligibilityWatch eligibilityWatch;
    private SurvivalNativePageParityClient() {}

    enum Layout { DEFAULT32, IRIS52;
        static Layout parse(String value) {
            return switch(value) { case "default32" -> DEFAULT32; case "iris52" -> IRIS52;
                default -> throw new IllegalArgumentException("Explicit diagnostic layout must be default32 or iris52"); };
        }
    }
    enum Case { SPARSE_SLICED, SPARSE_LARGE, FACE_REMOVAL, FACE_REPLACEMENT, DIAGONAL_REMOVAL, FULL_216, CUTOUT_ROLLOVER }

    /** Executable scheduling/cleanup policy, independent of Minecraft/GL initialization. */
    static final class Control {
        final int total;
        int completed;
        long lastFrame=Long.MIN_VALUE;
        boolean active, terminal, cleanupPending, finalAnchorComplete, finalFrameComplete;
        long completionFrame=Long.MIN_VALUE;
        Throwable failure;
        Control(int total) { if(total<1||total>Case.values().length+224)throw new IllegalArgumentException("Finite diagnostic required");this.total=total; }
        boolean attempt(long frame,Runnable work,Runnable cleanup) {
            if(terminal||active||cleanupPending||frame<=lastFrame)return false;
            lastFrame=frame;active=true;cleanupPending=true;
            try { work.run(); } catch(RuntimeException|Error error) { fail(error); }
            finally {
                try { cleanup.run();cleanupPending=false; } catch(RuntimeException|Error error) { fail(error); }
                active=false;
            }
            if(failure==null){completed++;terminal=completed==total;if(terminal)completionFrame=frame;}
            return true;
        }
        boolean successAwaitingFrame(){return terminal&&failure==null&&!finalFrameComplete;}
        void anchorExit(long frame) {
            if(!successAwaitingFrame())return;
            if(frame!=completionFrame)throw new IllegalStateException("Final parity anchor frame changed before restoration completed");
            finalAnchorComplete=true;
        }
        void framePost(long frame) {
            if(!successAwaitingFrame())return;
            if(frame!=completionFrame||!finalAnchorComplete)throw new IllegalStateException("Final parity frame lacks its exact successful anchor exit");
            finalFrameComplete=true;
        }
        void fail(Throwable error) { failure=append(failure,Objects.requireNonNull(error));terminal=true; }
        void retryCleanup(Runnable cleanup) {
            if(active||!cleanupPending)return;
            try{cleanup.run();cleanupPending=false;}catch(RuntimeException|Error error){fail(error);}
        }
    }
    /** Finite diagnostic eligibility only; this never changes gameplay or its watchdog authority. */
    static final class EligibilityWatch {
        final int limit;
        int missed;
        long lastFrame=Long.MIN_VALUE;
        boolean expired;
        EligibilityWatch(int limit){if(limit<1||limit>ELIGIBILITY_FRAMES)throw new IllegalArgumentException("Bounded eligibility wait required");this.limit=limit;}
        boolean observe(long frame,boolean loadedWorld,boolean eligible) {
            if(expired||frame<=lastFrame)return false;
            lastFrame=frame;
            if(!loadedWorld)return false;
            if(eligible){missed=0;return false;}
            if(++missed>=limit){expired=true;return true;}
            return false;
        }
    }
    /** Identity-based fence; reload sequence is bumped from the reload listener before its barrier. */
    record Fence(Object level,Object connection,Object models,Object atlas,long resourceEpoch) {
        Fence { Objects.requireNonNull(level);Objects.requireNonNull(connection);Objects.requireNonNull(models);Objects.requireNonNull(atlas);if(resourceEpoch<1)throw new IllegalArgumentException("Positive resource epoch required"); }
        boolean same(Fence other) { return other!=null&&level==other.level&&connection==other.connection
                &&models==other.models&&atlas==other.atlas&&resourceEpoch==other.resourceEpoch; }
    }
    @SubscribeEvent public static void frame(RenderFrameEvent.Pre event) {
        if(!Boolean.getBoolean(ENABLED))return;
        frame=Math.incrementExact(frame);
        var current=session;
        if(current!=null) {
            if(current.control.successAwaitingFrame())current.control.fail(new IllegalStateException("Final parity frame Post was not observed before the next frame"));
            if(!current.control.terminal)current.checkGlBoundary("survival-parity.frame.pre");
            if(!current.control.terminal&&!current.fence.same(currentFence())) {
                current.lifecycle.invalidate();
                current.control.fail(new IllegalStateException("Diagnostic level, connection, models or resource generation changed"));
            }
            if(current.control.terminal)current.finishTerminal();
        }
    }
    @SubscribeEvent public static void watchdog(RenderFrameEvent.Post event) {
        if(!Boolean.getBoolean(ENABLED)||(attempted&&session==null))return;
        var current=session;
        if(current!=null) {
            if(!current.checkGlBoundary("survival-parity.frame.post"))return;
            if(current.control.terminal){if(current.control.failure!=null)current.finishTerminal();else current.completeFrame();return;}
        }
        var game=Minecraft.getInstance();
        boolean loaded=game.level!=null&&game.player!=null&&game.getConnection()!=null;
        if(!loaded)return;
        if(eligibilityWatch==null)eligibilityWatch=new EligibilityWatch(ELIGIBILITY_FRAMES);
        if(!eligibilityWatch.observe(frame,true,eligibleFrame==frame))return;
        if(!attempted) {
            attempted=true;session=new Session();
            try{session.initializeReport();}catch(RuntimeException|Error error){session.control.fail(error);}
        }
        session.lifecycle.invalidate();
        session.control.fail(new IllegalStateException("Required diagnostic "+System.getProperty(LAYOUT,"unspecified")
                +" render anchor was not eligible during "+ELIGIBILITY_FRAMES+" loaded-world frames; no gameplay action was taken"));
        session.finishTerminal();
    }
    @SubscribeEvent public static void render(RenderLevelStageEvent event) {
        if(!Boolean.getBoolean(ENABLED)||event.getStage()!=RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS)return;
        if("iris52".equals(System.getProperty(LAYOUT)))return;
        runEligible(Minecraft.getInstance().level,false);
    }
    /** Called only at the existing pinned post-Sodium/pre-Iris-RETURN terrain anchor. */
    public static void onIrisTerrain(ClientLevel level,RenderType layer) {
        if(!Boolean.getBoolean(ENABLED)||!"iris52".equals(System.getProperty(LAYOUT)))return;
        if(layer!=RenderType.solid()||!IrisHost.eligible(level))return;
        var game=Minecraft.getInstance();
        if(waitingForInitialServerDistance(attempted,game.hasSingleplayerServer(),
                game.options.renderDistance().get(),game.options.getEffectiveRenderDistance()))return;
        runEligible(level,true);
    }
    /** Non-authorizing startup wait only; the full installed lease validates again once eligible. */
    static boolean waitingForInitialServerDistance(boolean started,boolean singleplayer,int selected,int effective) {
        return !started&&singleplayer&&selected>=2&&selected<=32&&effective>=2&&effective<selected;
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        if(!Boolean.getBoolean(ENABLED))return;
        RESOURCE_EPOCH.incrementAndGet();
        if(session!=null){session.lifecycle.invalidate();session.control.fail(new IllegalStateException("Diagnostic disconnected"));session.finishTerminal();}
    }
    @EventBusSubscriber(modid=EarthPlatformMod.MOD_ID,value=Dist.CLIENT,bus=EventBusSubscriber.Bus.MOD)
    public static final class Resources {
        @SubscribeEvent public static void register(RegisterClientReloadListenersEvent event) {
            if(!Boolean.getBoolean(ENABLED))return;
            event.registerReloadListener((barrier,manager,prepare,apply,background,game)-> {
                RESOURCE_EPOCH.incrementAndGet(); // Thread-safe invalidation before resources can be replaced.
                return barrier.wait(null).thenRunAsync(()-> {
                    if(session!=null){session.lifecycle.invalidate();session.control.fail(new IllegalStateException("Diagnostic resource reload"));session.finishTerminal();}
                },game);
            });
        }
    }
    private static Fence currentFence() {
        var game=Minecraft.getInstance();
        if(game.level==null||game.getConnection()==null||game.getModelManager()==null)return null;
        return new Fence(game.level,game.getConnection(),game.getModelManager(),
                game.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS),RESOURCE_EPOCH.get());
    }
    private static void runEligible(ClientLevel level,boolean iris) {
        RenderSystem.assertOnRenderThread();
        var game=Minecraft.getInstance();
        if(level==null||game.level!=level||game.player==null||game.getConnection()==null)return;
        eligibleFrame=frame;
        if(!attempted) {
            attempted=true;
            // Retain before initialize allocates files, CPU reservations, source assets or native owners.
            session=new Session();
            try{session.initialize(iris);}catch(RuntimeException|Error error){session.control.fail(error);}
        }
        if(session==null)return;
        if(session.control.terminal){
            if(session.control.failure!=null)session.finishTerminal();
            else session.checkGlBoundary("survival-parity.anchor.repeat");
            return;
        }
        if(!session.checkGlBoundary("survival-parity.anchor.entry"))return;
        session.step(iris);
        if(session!=null)session.anchorExit();
    }
    private static final class Session {
        final NativeDistanceCpuBudget cpu=NativeDistanceCpuBudget.global();
        final NativeVisualBudget gpu=NativeVisualBudget.global();
        final SceneNativeShaderContract.Lifecycle lifecycle=new SceneNativeShaderContract.Lifecycle();
        Control control=new Control(Case.values().length);
        Fence fence;
        Layout layout;
        Path reportPath;
        EnginePaletteContext palette;
        NativeDistanceCpuBudget.Reservation metadata,sourceAssets,planBytes;
        MarinVisualCubeSource source;
        SurvivalDistancePagePlan sourcePlan;
        SurvivalNativePageParityFixture fixture;
        SurvivalNativePageParityProbe probe;
        SurvivalNativePageCompiler.Build staleBuild;
        SurvivalNativePageCompiler.CompiledPage stalePage;
        SceneNativeShaderContract.Capture capture;
        InstalledIrisRuntime irisRuntime;
        InstalledIrisRuntime.Lease irisLease;
        String sparseDigest, sparseFaceDigest;
        JsonObject result;
        NativeDistanceCpuBudget.Evidence caseCpuBaseline;
        NativeVisualBudget.Evidence caseGpuBaseline;
        boolean terminalReported, ownersClosed;
        long started=System.nanoTime();
        void initializeReport() {
            metadata=cpu.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,4L<<20);
            layout=Layout.parse(System.getProperty(LAYOUT,""));
            String output=System.getProperty(REPORT,"");
            if(output.isBlank())throw new IllegalArgumentException("Explicit new JSONL report path required");
            reportPath=Path.of(output).toAbsolutePath().normalize();
            try{Files.writeString(reportPath,"",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);}
            catch(IOException error){reportPath=null;throw new UncheckedIOException(error);}
            fence=Objects.requireNonNull(currentFence(),"Loaded model manager, atlas, level and connection required");
        }
        void initialize(boolean iris) {
            initializeReport();
            if((layout==Layout.IRIS52)!=iris)throw new IllegalStateException("Diagnostic entered wrong native layout stage");
            var game=Minecraft.getInstance();
            if(game.getModelManager().getMissingModel()==null||game.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getId()<=0)
                throw new IllegalStateException("Loaded model manager and block atlas required");
            if(ItemBlockRenderTypes.getChunkRenderType(Blocks.OAK_LEAVES.defaultBlockState())!=RenderType.cutoutMipped())
                throw new IllegalStateException("Fancy leaves must actually use cutout_mipped before qualification");
            palette=EnginePaletteContext.create(game.level.registryAccess().registryOrThrow(Registries.BIOME));
            String root=System.getProperty(SOURCE_ROOT,"");
            if(!root.isBlank()) {
                sourceAssets=cpu.reserve(NativeDistanceCpuBudget.Pool.SOURCE_ASSETS,64L<<20);
                planBytes=cpu.reserve(NativeDistanceCpuBudget.Pool.PLAN,16L<<20);
                Path path=Path.of(root).toAbsolutePath().normalize();
                MarinSurvivalDefinition definition;
                try{definition=MarinSurvivalDefinition.loadPaths(path.resolve("terrain/world-global.properties"),
                        path.resolve("cover/landcover.properties"),path.resolve("cover/marin-headlands/landcover.elc"));}
                catch(IOException error){throw new UncheckedIOException(error);}
                source=new MarinVisualCubeSource(definition,new SurvivalRenderPalette(palette));
                var limits=new SurvivalDistancePagePlan.Limits(1_100_000,4_225,4096,262_144,884_736,884_736,0,16L<<20);
                var empty=SurvivalDistancePagePlan.CompleteEditSet.copyOf(0,hash("explicit diagnostic generated source; no live edit authority"),Set.of(),limits);
                sourcePlan=SurvivalDistancePagePlan.build(definition,definition.anchor().center(),24,24,empty,limits);
                if(sourcePlan.pages().size()!=224)throw new IllegalStateException("Pinned anchor24 diagnostic census is not exactly 224 pages");
                control=new Control(Case.values().length+sourcePlan.pages().size());
            }
            var start=new JsonObject();start.addProperty("event","started");start.addProperty("layout",layout.name());
            start.addProperty("cases",control.total);start.addProperty("authority","native-model-byte-parity-only");
            start.addProperty("frameBudgetClaim",false);start.addProperty("sourcePageCensus",sourcePlan!=null);
            if(source!=null){start.addProperty("sourceIdentity",source.identity());start.addProperty("visualIdentity",source.definition().visualIdentity());start.addProperty("planIdentity",sourcePlan.identity());start.addProperty("overlayScope","generated source only; no live/durable edit completeness claim");}
            write(start);
        }
        boolean checkGlBoundary(String phase) {
            try{StrictGlErrors.check(phase);return true;}
            catch(RuntimeException|Error error){control.fail(error);finishTerminal();return false;}
        }
        void anchorExit() {
            if(!checkGlBoundary("survival-parity.anchor.exit"))return;
            try{control.anchorExit(frame);}catch(RuntimeException|Error error){control.fail(error);finishTerminal();}
        }
        void completeFrame() {
            try{requireFence();control.framePost(frame);}catch(RuntimeException|Error error){control.fail(error);}
            finishTerminal();
        }
        void step(boolean iris) {
            if((layout==Layout.IRIS52)!=iris)return;
            int index=control.completed;
            long before=System.nanoTime();
            if(!control.attempt(frame,()->runCase(index,iris),this::closeCase))return;
            if(control.failure==null) {
                result.addProperty("wallNanos",Math.max(0,System.nanoTime()-before));
                result.addProperty("frameBudgetClaim",false);
                try{write(result);}catch(RuntimeException|Error error){control.fail(error);}
                result=null;
            }
            if(control.terminal&&control.failure!=null)finishTerminal();
        }
        void runCase(int index,boolean iris) {
            requireFence();lifecycle.invalidate();
            if(sharedIndex==null)sharedIndex=new SharedIndexBaseline(gpu);
            sharedIndex.establishOrVerify();
            capture=freshCapture(iris);
            caseCpuBaseline=cpu.evidence();caseGpuBaseline=gpu.evidence();
            if(index>=Case.values().length) {
                var page=sourcePlan.pages().get(index-Case.values().length);
                fixture=SurvivalNativePageParityFixture.sourcePage(page,source,Map.of(),cpu);
            } else {
                var kind=Case.values()[index];
                fixture=kind==Case.CUTOUT_ROLLOVER?SurvivalNativePageParityFixture.rollover(palette,cpu)
                        :SurvivalNativePageParityFixture.states(palette,cpu,kind==Case.FULL_216);
                if(kind==Case.FULL_216&&fixture.cubes().size()!=216)throw new IllegalStateException("Full page must have exactly 216 dependencies");
                if(kind==Case.FACE_REPLACEMENT)fixture.removeFaceOccluder();
                if(kind==Case.FACE_REMOVAL||kind==Case.FACE_REPLACEMENT||kind==Case.DIAGONAL_REMOVAL)proveOpenPageStale(kind);
            }
            probe=new SurvivalNativePageParityProbe(fixture,capture,cpu,gpu);
            int blocks=index==Case.SPARSE_LARGE.ordinal()?Integer.MAX_VALUE:512;
            long nanos=index==Case.SPARSE_LARGE.ordinal()?Long.MAX_VALUE:2_000_000L;
            var report=probe.run(blocks,nanos);
            String digest=semanticDigest(report),face=faceDigest(report);
            if(index==Case.SPARSE_SLICED.ordinal()){sparseDigest=digest;sparseFaceDigest=face;}
            if(index==Case.SPARSE_LARGE.ordinal()||index==Case.FACE_REPLACEMENT.ordinal())
                if(!Objects.equals(sparseDigest,digest))throw new IllegalStateException("Sliced/large or actual face replacement canonical digests differ");
            if(index==Case.FACE_REMOVAL.ordinal()&&Objects.equals(sparseFaceDigest,face))
                throw new IllegalStateException("Removing actual face occluder did not change member0 solid native geometry");
            requireFence();capture.requireCurrent();
            result=new Gson().toJsonTree(report).getAsJsonObject();
            result.addProperty("event","case_pass");result.addProperty("ordinal",index);
            result.addProperty("case",index<Case.values().length?Case.values()[index].name():"SOURCE_PAGE");
            result.addProperty("semanticSha256",digest);result.addProperty("sharedIndexStorageBytes",sharedIndex.storageBytes);
            result.addProperty("sharedIndexRetainedCharge",SHARED_INDEX_CHARGE);
            result.addProperty("openPageStaleProof",index==Case.FACE_REMOVAL.ordinal()||index==Case.FACE_REPLACEMENT.ordinal()||index==Case.DIAGONAL_REMOVAL.ordinal());
            if(index>=Case.values().length){result.addProperty("sourcePageOrdinal",index-Case.values().length);result.addProperty("sourceIdentity",source.identity());result.addProperty("pageMinimum",fixture.page().address().minimum().toString());}
        }
        void proveOpenPageStale(Case kind) {
            staleBuild=SurvivalNativePageCompiler.begin(fixture.page(),fixture.cubes(),fixture.guard(),
                    SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE,capture.values().disableDirectionalShading(),capture,cpu);
            while(!staleBuild.complete()){staleBuild.step(512,2_000_000L);StrictGlErrors.check("survival-parity.stale.cpu.slice");}
            stalePage=staleBuild.finish();staleBuild.close();staleBuild=null;
            stalePage.requireCurrent();
            switch(kind){case FACE_REMOVAL->fixture.removeFaceOccluder();case FACE_REPLACEMENT->fixture.replaceFaceOccluder();
                case DIAGONAL_REMOVAL->fixture.removeDiagonalOccluder();default->throw new IllegalArgumentException("Mutation case required");}
            boolean refused=false;
            // The retained CPU page has NOT been closed: this must prove staleness, not closed-owner refusal.
            if(stalePage.closeComplete())throw new IllegalStateException("Stale proof page was already closed");
            try{stalePage.requireCurrent();}catch(IllegalStateException expected){refused=true;}
            if(!refused)throw new IllegalStateException("Old open CPU page accepted changed halo dependency");
            stalePage.close();if(!stalePage.closeComplete())throw new IllegalStateException("Stale page cleanup did not finish");stalePage=null;
        }
        SceneNativeShaderContract.Capture freshCapture(boolean iris) {
            if(!iris&&ModList.get().isLoaded("iris")&&IrisHost.packActive())throw new IllegalStateException("default32 requires shaders disabled before world entry");
            SceneNativeShaderContract.Capture base;
            if(iris) {
                irisRuntime=InstalledIrisRuntime.resolveInstalled();irisLease=irisRuntime.leaseDistance();
                if(!IrisHost.eligible(Minecraft.getInstance().level))throw new IllegalStateException("Iris native terrain phase expired");
                base=SceneNativeShaderContract.captureIris(lifecycle);
                if(base.values().pipeline()!=irisLease.pipelineIdentity())throw new IllegalStateException("Captured Iris pipeline differs from verified installed lease");
            } else base=SceneNativeShaderContract.defaultPath();
            long epoch=lifecycle.epoch();
            var v=base.values();
            var values=new SceneNativeShaderContract.Values(v.shaders(),v.pipeline(),v.pack(),v.stateIds(),v.typeIds(),v.format(),v.stride(),epoch,v.ambientOcclusionBits(),v.separateAo(),v.disableDirectionalShading());
            return new SceneNativeShaderContract.Capture(values,()-> {
                requireFence();base.requireCurrent();
                if(lifecycle.epoch()!=epoch)throw new IllegalStateException("Diagnostic capture generation expired");
                if(iris&&(irisLease==null||!irisLease.current()||!IrisHost.eligible(Minecraft.getInstance().level)))throw new IllegalStateException("Installed Iris lifecycle or native terrain phase expired");
                return values;
            },new SceneNativeShaderContract.MaterialContext() {
                public void begin(com.mojang.blaze3d.vertex.BufferBuilder builder,net.minecraft.world.level.block.state.BlockState state,int x,int y,int z){base.beginBlock(builder,state,x,y,z);}
                public void end(com.mojang.blaze3d.vertex.BufferBuilder builder){base.endBlock(builder);}
                public boolean isCurrent(){return base.isCurrent();}
                public long metadataBytes(){return base.metadataBytes();}
            });
        }
        void requireFence() { if(!fence.same(currentFence()))throw new IllegalStateException("Diagnostic resource/level fence changed"); }
        void closeCase() {
            // Ordered: do not drain backing fixture/source while a prior cleanup owner remains live.
            if(sharedIndex!=null)sharedIndex.closeTemporary();
            if(probe!=null){probe.close();if(!probe.closeComplete())throw new IllegalStateException("Parity cleanup remains owned");probe=null;}
            if(staleBuild!=null){staleBuild.close();staleBuild=null;}
            if(stalePage!=null){stalePage.close();if(!stalePage.closeComplete())throw new IllegalStateException("Stale CPU cleanup remains owned");stalePage=null;}
            if(fixture!=null){fixture.close();fixture=null;}
            capture=null;lifecycle.invalidate();
            if(irisRuntime!=null){irisRuntime.releaseAllReferences();StrictGlErrors.check("survival-parity.case.lease.release");irisRuntime=null;irisLease=null;}
            if(caseCpuBaseline!=null) {
                var c=cpu.evidence();var g=gpu.evidence();
                if(c.bytes()!=caseCpuBaseline.bytes()||c.reservations()!=caseCpuBaseline.reservations()
                        ||!c.pools().equals(caseCpuBaseline.pools())||g.bytes()!=caseGpuBaseline.bytes()||g.pieces()!=caseGpuBaseline.pieces())
                    throw new IllegalStateException("Case fixture/native owners did not return to the charged process baseline");
                caseCpuBaseline=null;caseGpuBaseline=null;
            }
            StrictGlErrors.check("survival-parity.case.close.exit");
        }
        void finishTerminal() {
            if(!control.terminal||control.successAwaitingFrame())return;
            control.retryCleanup(this::closeCase);
            if(!control.cleanupPending&&!ownersClosed) {
                try {
                    closeCase();sourcePlan=null;source=null;palette=null;
                    if(planBytes!=null){planBytes.releaseAfterPhysicalDrain();planBytes=null;}
                    if(sourceAssets!=null){sourceAssets.releaseAfterPhysicalDrain();sourceAssets=null;}
                    result=null;ownersClosed=true;
                } catch(RuntimeException|Error error){control.fail(error);control.cleanupPending=true;}
            }
            if(!terminalReported) {
                terminalReported=true;
                var terminal=new JsonObject();terminal.addProperty("event","terminal");terminal.addProperty("status",control.failure==null?"PASS":"FAIL");
                terminal.addProperty("completed",control.completed);terminal.addProperty("planned",control.total);
                terminal.addProperty("wallNanos",Math.max(0,System.nanoTime()-started));terminal.addProperty("cleanupPending",control.cleanupPending);
                terminal.addProperty("sharedIndexReservationRetainedUntilContextShutdown",sharedIndex!=null&&sharedIndex.reservation!=null);
                terminal.addProperty("authority","native-model-byte-parity-only; no native-distance draw or DH complement");
                terminal.addProperty("cpuBytesBeforeReportDrain",cpu.evidence().bytes());terminal.addProperty("reportOwnerBytes",metadata==null?0:metadata.bytes());terminal.addProperty("gpuBytes",gpu.evidence().bytes());
                if(control.failure!=null)terminal.addProperty("failure",control.failure.toString());
                try{if(reportPath!=null)write(terminal);}catch(RuntimeException|Error error){control.fail(error);terminal.addProperty("status","FAIL");terminal.addProperty("failure",control.failure.toString());EarthPlatformMod.LOGGER.error("SURVIVAL_NATIVE_PARITY_REPORT_FAILURE",error);}
                EarthPlatformMod.LOGGER.info("SURVIVAL_NATIVE_PARITY_TERMINAL {}",terminal);
                if(control.failure!=null)EarthPlatformMod.LOGGER.error("SURVIVAL_NATIVE_PARITY_FAIL",control.failure);
            }
            if(ownersClosed&&!control.cleanupPending) {
                if(metadata!=null){metadata.releaseAfterPhysicalDrain();metadata=null;}
                session=null; // The once-per-process attempted flag and separately charged index baseline remain.
            }
        }
        void write(JsonObject value) {
            try{Files.writeString(reportPath,new Gson().toJson(value)+"\n",StandardCharsets.UTF_8,StandardOpenOption.APPEND,StandardOpenOption.WRITE);}
            catch(IOException error){throw new UncheckedIOException(error);}
        }
    }
    private static final class IrisHost {
        static boolean packActive(){return net.irisshaders.iris.Iris.isPackInUseQuick();}
        static boolean eligible(ClientLevel level) {
            if(level==null||Minecraft.getInstance().level!=level||!packActive()
                    ||net.irisshaders.iris.shadows.ShadowRenderingState.areShadowsCurrentlyBeingRendered()
                    ||!net.irisshaders.iris.vertices.ImmediateState.isRenderingLevel)return false;
            var pipeline=net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
            return pipeline instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline iris
                    &&iris.isBeforeTranslucent&&iris.getPhase()==net.irisshaders.iris.pipeline.WorldRenderingPhase.fromTerrainRenderType(RenderType.solid());
        }
    }
    /** One retained process baseline, plus a retryable temporary VAO/restoration owner. */
    private static final class SharedIndexBaseline {
        final NativeVisualBudget gpu;
        NativeVisualBudget.Reservation reservation;
        SceneGlRestoreOwner restore;
        int temporaryVao,buffer,storageBytes;
        boolean established;
        SharedIndexBaseline(NativeVisualBudget gpu){this.gpu=gpu;}
        void establishOrVerify() {
            if(temporaryVao!=0||restore!=null)throw new IllegalStateException("Shared index cleanup remains owned");
            var sequential=RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            if(sequential.hasStorage(SHARED_INDEX_COUNT+1))throw new IllegalStateException("Preexisting QUADS buffer exceeds bounded diagnostic baseline");
            if(established&&!sequential.hasStorage(SceneMesh.MAX_QUADS*6))throw new IllegalStateException("Previously established shared QUADS capacity disappeared");
            if(reservation==null){reservation=gpu.reserve(1,SHARED_INDEX_BYTES);if(reservation.chargedBytes()!=SHARED_INDEX_CHARGE)throw new IllegalStateException("Shared index charge policy changed");}
            StrictGlErrors.check("survival-parity.index.entry");
            var bindings=new SceneMesh.BufferBindings();
            restore=new SceneGlRestoreOwner(()-> {
                bindings.restore();
                if(glGetInteger(GL_VERTEX_ARRAY_BINDING)!=bindings.vao||glGetInteger(GL_ARRAY_BUFFER_BINDING)!=bindings.arrayBuffer)
                    throw new IllegalStateException("Shared index bindings did not restore exactly");
            },()->StrictGlErrors.check("survival-parity.index.restore.before"),()->StrictGlErrors.check("survival-parity.index.restore.after"));
            Throwable failure=null;
            try {
                temporaryVao=GlStateManager._glGenVertexArrays();if(temporaryVao<=0)throw new IllegalStateException("Temporary index VAO allocation failed");
                GlStateManager._glBindVertexArray(temporaryVao);
                sequential.bind(SceneMesh.MAX_QUADS*6); // Official owner alone allocates/grows its own index storage.
                int actualBuffer=glGetInteger(GL_ELEMENT_ARRAY_BUFFER_BINDING);
                int actualBytes=glGetBufferParameteri(GL_ELEMENT_ARRAY_BUFFER,GL_BUFFER_SIZE);
                validateStorage(actualBuffer,actualBytes,sequential.type().bytes,sequential.hasStorage(SceneMesh.MAX_QUADS*6));
                if(established&&(buffer!=actualBuffer||storageBytes!=actualBytes))throw new IllegalStateException("Shared QUADS owner/storage changed after baseline");
                buffer=actualBuffer;storageBytes=actualBytes;established=true;
                StrictGlErrors.check("survival-parity.index.storage");
            } catch(RuntimeException|Error error){failure=error;}
            finally{try{closeTemporary();}catch(RuntimeException|Error error){failure=append(failure,error);}}
            rethrow(failure);
        }
        void closeTemporary() {
            if(restore!=null){restore.restore();if(!restore.pending())restore=null;}
            if(temporaryVao!=0){GlStateManager._glDeleteVertexArrays(temporaryVao);StrictGlErrors.check("survival-parity.index.delete");
                if(glIsVertexArray(temporaryVao))throw new IllegalStateException("Temporary baseline VAO deletion not confirmed");StrictGlErrors.check("survival-parity.index.delete.verify");temporaryVao=0;}
        }
    }
    static void validateStorage(int buffer,int bytes,int indexBytes,boolean capacity) {
        if(buffer<=0||!capacity||(indexBytes!=2&&indexBytes!=4)||bytes<SceneMesh.MAX_QUADS*6*indexBytes||bytes>SHARED_INDEX_BYTES)
            throw new IllegalStateException("Unverifiable or excessive process-owned QUADS index storage");
    }
    static String semanticDigest(SurvivalNativePageParityProbe.Report report) {
        var canonical=new StringBuilder().append(report.schema()).append('\n');
        for(var c:report.comparisons())canonical.append(c.member()).append(':').append(c.layer()).append(':').append(c.vertices()).append(':').append(c.pageAttributeSha256()).append('\n');
        return hash(canonical.toString());
    }
    private static String faceDigest(SurvivalNativePageParityProbe.Report report) {
        return report.comparisons().stream().filter(c->c.member()==0&&c.layer()==SurvivalNativePageCompiler.Layer.SOLID)
                .map(c->c.vertices()+":"+c.pageAttributeSha256()).findFirst().orElse("");
    }
    private static String hash(String value){return WorldManifest.sha256(value.getBytes(StandardCharsets.UTF_8));}
    private static Throwable append(Throwable prior,Throwable next){if(prior==null)return next;if(prior!=next&&prior.getSuppressed().length<8)prior.addSuppressed(next);return prior;}
    private static void rethrow(Throwable failure){if(failure instanceof RuntimeException e)throw e;if(failure instanceof Error e)throw e;}
}
