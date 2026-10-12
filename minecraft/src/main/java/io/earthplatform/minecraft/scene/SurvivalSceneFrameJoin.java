package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.compat.dh.InstalledIrisRuntime;
import io.earthplatform.minecraft.lod.SurvivalDhGpuPages;
import io.earthplatform.minecraft.survival.render.SurvivalCompleteOverlay;
import io.earthplatform.minecraft.survival.render.SurvivalDhCoverage;
import io.earthplatform.minecraft.survival.render.SurvivalSceneDepth;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.util.List;
import java.util.Objects;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/** One bounded exact native/DH candidate. This seam joins content and real main-frame resources;
 * its private per-pass tokens gate typed GL draws and grants no collision/readiness, historical next-frame, or shadow authority.
 * Native/DH physical owners remain retained by their parent through successful or failed cleanup.
 * No public scalar, matrix, string, callback or interface implementation can mint an AcceptedFrame. */
public final class SurvivalSceneFrameJoin implements AutoCloseable {
    private static SurvivalSceneFrameJoin attached;
    private final Thread owner=Thread.currentThread();
    private final NativeDistanceCpuBudget budget;
    private SurvivalCompleteOverlay.PlanInput plan;
    private SurvivalCompleteOverlay.CurrentLease current;
    private SurvivalDhCoverage coverage;
    private SurvivalNativeGpuPage.Completion[] nativePages;
    private SurvivalDhGpuPages.Completion dh;
    private SurvivalSceneResources resources;
    private LevelRenderer renderer;
    private ClientLevel level;
    private NativeDistanceCpuBudget.Reservation metadata,frameCharge;
    private AcceptedFrame accepted,proposal;
    private RealFrame pendingContentFrame;
    private boolean contentPending;
    private long suppressedContentFrames;
    private SurvivalCompleteOverlay.FrameLatch rebindValidation;
    private SurvivalCompleteOverlay.CurrentLease pendingRebind;
    private boolean frameOpen,failed,closed,closing,initialized,initializing;
    private String failure="";
    private int observedMainLayers;
    private long acceptedFrames,completedMainWitnesses,completedMainDraws;
    private boolean drawsRequired;

    /** Empty non-owning shell. Retain before initialize; every partial setup remains reachable. */
    public SurvivalSceneFrameJoin(NativeDistanceCpuBudget budget) {
        RenderSystem.assertOnRenderThread();this.budget=Objects.requireNonNull(budget);
    }
    public void initialize(SurvivalCompleteOverlay.PlanInput exactPlan,
            SurvivalCompleteOverlay.CurrentLease current,SurvivalDhCoverage exactCoverage,
            List<SurvivalNativeGpuPage.Completion> exactPages,SurvivalDhGpuPages.Completion exactDh,
            SurvivalSceneResources exactResources) {
        checkOwner();
        if(closed||closing||failed||initialized||initializing)throw new IllegalStateException("Frame join initialization is single-use");
        initializing=true;
        try {
            Objects.requireNonNull(exactPlan).requireBudget(budget);Objects.requireNonNull(exactPages);
            Objects.requireNonNull(exactResources).requireBudget(budget);
            int count=exactPlan.pageCount();
            if(count<1||exactPages.size()!=count)throw new IllegalArgumentException("Exactly one completed native owner per planned page required");
            metadata=budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,Math.addExact(4096L,32L*count));
            plan=exactPlan.retain();coverage=Objects.requireNonNull(exactCoverage).retain();
            this.current=Objects.requireNonNull(current);dh=Objects.requireNonNull(exactDh);resources=exactResources.retain();
            nativePages=new SurvivalNativeGpuPage.Completion[count];
            for(int i=0;i<count;i++)nativePages[i]=Objects.requireNonNull(exactPages.get(i));
            if(exactPages.size()!=count)throw new IllegalArgumentException("Native page list changed during bounded copy");
            validateCandidate();initialized=true;
        } catch(RuntimeException|Error error){
            failed=true;
            String text="Frame join initialization rejected: "+error.getMessage();failure=text.substring(0,Math.min(256,text.length()));
            // Parent retains this shell. No constructor catch can lose a cleanup-failed alias.
            throw error;
        } finally {initializing=false;}
    }
    /** Binding chooses no camera or frame. The real transformed renderer is the sole token producer. */
    public void attach() {
        checkOpen();if(attached!=null)throw new IllegalStateException("Prior survival frame owner remains attached");
        var game=Minecraft.getInstance();resources.requireCurrent();
        if(!((Object)game.levelRenderer instanceof SurvivalRenderHookAccess))
            throw new IllegalStateException("Actual installed LevelRenderer survival scopes are absent");
        renderer=game.levelRenderer;level=Objects.requireNonNull(game.level);attached=this;
    }
    /** Typed carrier binding enables mandatory draw completion; it never mints frame authority. */
    public void requireDrawAdapter(SurvivalSceneResources exact,NativeDistanceCpuBudget exactBudget){
        checkOpen();if(frameOpen||drawsRequired||resources!=exact||budget!=exactBudget||attached!=this)
            throw new IllegalStateException("Exact attached candidate/resources/budget required before drawing");
        drawsRequired=true;
    }
    /**
     * Exact content-only replacement, strictly between actual frames. Caller retains both lease
     * owners until success; the returned previous lease remains caller-owned. Null means only a
     * known exact-generation revocation before the swap. This creates no draw
     * permit and cannot revive a failed candidate, resource Capture, physical page or configuration.
     */
    public SurvivalCompleteOverlay.CurrentLease rebindCurrent(SurvivalCompleteOverlay.CurrentLease fresh){
        checkOpen();Objects.requireNonNull(fresh);
        if(frameOpen||accepted!=null||proposal!=null||pendingContentFrame!=null||frameCharge!=null||rebindValidation!=null||pendingRebind!=null
                ||!drawsRequired||attached!=this||fresh==current)
            throw new IllegalStateException("Exact quiescent attached content replacement required");
        io.earthplatform.compat.dh.shader.ShaderCarrierHooks.requireSurvivalRebindQuiescent(this);
        validateCandidate();
        pendingRebind=fresh;
        try {
            // The lease itself proves exact PlanData and source-owner currentness. Hashes and caller
            // scalars cannot replace this CAS. A mutation afterwards simply revokes future latches.
            rebindValidation=fresh.tryLatchCurrent(plan);
            if(rebindValidation==null){pendingRebind=null;return null;}
            validateCandidate();
            io.earthplatform.compat.dh.shader.ShaderCarrierHooks.requireSurvivalRebindQuiescent(this);
            rebindValidation.close();rebindValidation=null;
            var previous=current;current=fresh;pendingRebind=null;contentPending=false;return previous;
        } catch(RuntimeException|Error error) {
            if(rebindValidation!=null)try{rebindValidation.close();rebindValidation=null;}
                catch(RuntimeException|Error cleanup){error.addSuppressed(cleanup);}
            if(rebindValidation==null)pendingRebind=null;
            throw error;
        }
    }
    public void requireHookReservation(){checkOpen();if(!frameOpen||(frameCharge==null&&accepted==null))throw new IllegalStateException("No charged actual survival frame scope");}
    public boolean ownsLevel(Object exact){checkOwner();return !closed&&attached==this&&level==exact;}
    /** Used only to decide whether the additive mixin scope is needed. This does not mint authority. */
    public static SurvivalSceneFrameJoin attachedTo(LevelRenderer exact) {
        RenderSystem.assertOnRenderThread();var value=attached;
        return value!=null&&!value.closed&&value.renderer==exact?value:null;
    }
    /** Reservation-only ingress. Calling it outside renderLevel cannot populate renderer-private state. */
    public void beginActualFrame() {
        checkOpen();if(frameOpen||accepted!=null||proposal!=null||pendingContentFrame!=null||frameCharge!=null||pendingRebind!=null||rebindValidation!=null){refuse("Previous or nested actual frame remains owned");throw new IllegalStateException(failure);}
        frameCharge=budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,Math.addExact(8192L,8L*nativePages.length));
        frameOpen=true;observedMainLayers=0;
    }
    /** Exact main solid call-site ingress precedes DH's callee HEAD; no external input is admitted. */
    public static void prepareActualMainInvocation(){
        var value=attached;if(value==null)return;value.checkOpen();
        try{if(value.accepted!=null||value.proposal!=null||value.pendingContentFrame!=null)throw new IllegalStateException("Same-frame content already prepared");value.acceptMainFrame();}
        catch(RuntimeException|Error error){value.refuse("Actual main preparation rejected: "+error.getMessage());throw error;}
    }
    /** No-argument ingress reads only the actual Minecraft LevelRenderer, never a caller implementation. */
    public static void observeActualNativeAnchor() {
        var value=attached;if(value==null)return;value.observeNativeAnchor();
    }
    private void observeNativeAnchor() {
        checkOpen();var access=actualAccess();access.earthplatform$survivalInvocation();
        if(ShadowRenderingState.areShadowsCurrentlyBeingRendered())return; // Earlier shadows have no main witness.
        int bit=layerBit(access.earthplatform$survivalLayer());if(bit==0)return;
        try {
            if((observedMainLayers&bit)!=0)throw new IllegalStateException("Repeated main survival layer");
            observedMainLayers|=bit;
            if(accepted==null){
                if(skipPendingContentFrame()){requireMainInvocation(pendingContentFrame);return;}
                throw new IllegalStateException("Native layer lacks this actual frame's earlier main call-site witness");
            }
            accepted.requireNativeInvocation();
        }catch(RuntimeException|Error error){refuse("Actual native frame rejected: "+error.getMessage());throw error;}
    }
    private void acceptMainFrame() {
        if(!frameOpen||frameCharge==null)throw new IllegalStateException("No precharged actual frame scope");
        var witness=captureRealMainFrame(); // All copied matrices and masks are covered by the frame precharge.
        proposal=new AcceptedFrame(this,witness,new long[nativePages.length],frameCharge);
        frameCharge=null;
        try {
            validateCandidate();witness.requireFrame(this);requireMainPreparation(witness);
            var distance=plan.distance();var lease=resources.iris();
            if(distance.selectedChunks()!=lease.selectedRenderDistance()||distance.effectiveChunks()!=lease.effectiveRenderDistance()
                    ||distance.effectiveCapChunks()!=Math.max(0,lease.serverRenderDistanceLimit()))
                throw new IllegalStateException("Exact geometry plan differs from actual selected/effective/server distances");
            proposal.depth=SurvivalSceneDepth.inspect(distance,plan.bounds(),witness.projection,witness.view,witness.x,witness.y,witness.z);
            for(int i=0;i<nativePages.length;i++){
                long mask=SurvivalSceneDepth.eligibleMask(plan.page(i),witness.view,witness.x,witness.y,witness.z,proposal.depth);
                if(mask!=coverage.selectedMask(i))throw new IllegalStateException("Actual full-cube native depth mask differs from the exact DH complement; rebuild required");
                proposal.masks[i]=mask;
            }
            // Native/DH authentication, resources/configuration and all allocations precede this CPU-only CAS.
            validateCandidate();witness.requireFrame(this);requireMainPreparation(witness);
            proposal.content=current.tryLatchCurrent(plan);
            if(proposal.content==null){
                // Known exact content revocation earns no AcceptedFrame or drawing. Keep the real
                // frame witness/charge for both skipped native and DH invocation scopes until end.
                validateCandidate();witness.requireFrame(this);requireMainPreparation(witness);
                pendingContentFrame=witness;contentPending=true;
                frameCharge=proposal.charge;proposal.charge=null;
                proposal.close();proposal=null;return;
            }
            // A later content mutation makes this same-frame receipt historical. Resource retirement forbids it.
            validateCandidate();coverage.requireExactPlan(proposal.content);dh.requireAuthenticated(coverage,proposal.content);
            witness.requireFrame(this);requireMainPreparation(witness);
            long nextAccepted=Math.incrementExact(acceptedFrames);
            accepted=proposal;proposal=null;acceptedFrames=nextAccepted;
        }catch(RuntimeException|Error error){
            if(proposal!=null)try{proposal.close();proposal=null;}
                catch(RuntimeException|Error cleanup){error.addSuppressed(cleanup);}
            throw error;
        }
    }
    /** Only a private actual-frame witness plus known owner CAS revocation can suppress a pass. */
    public boolean skipPendingContentFrame(){
        checkOpen();if(pendingContentFrame==null)return false;
        if(accepted!=null||frameCharge==null)throw new IllegalStateException("Pending content frame ownership changed");
        pendingContentFrame.requireFrame(this);validateCandidate();return true;
    }
    private void validateCandidate() {
        resources.requireCurrent();coverage.requireExactPlan(plan);
        var capture=resources.nativeCapture();
        for(int i=0;i<nativePages.length;i++){
            var completion=nativePages[i];
            if(completion.page()!=plan.page(i))throw new IllegalStateException("Native completion is missing, repeated, reordered or from another exact page");
            completion.requireAuthenticated(plan,capture,SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE,
                    capture.values().disableDirectionalShading());
        }
        dh.requireAuthenticated(coverage,plan);dh.requireCurrentConfiguration();resources.requireCurrent();
    }
    private SurvivalRenderHookAccess actualAccess() {
        var game=Minecraft.getInstance();
        if(attached!=this||renderer==null||game.levelRenderer!=renderer||game.level!=level||!frameOpen)
            throw new IllegalStateException("Actual survival renderer/level/frame owner changed");
        if(!((Object)game.levelRenderer instanceof SurvivalRenderHookAccess access))
            throw new IllegalStateException("Actual survival render scopes unavailable");
        if(access.earthplatform$survivalLevel()!=level)throw new IllegalStateException("Hook level differs from exact survival level");
        return access;
    }
    private RealFrame captureRealMainFrame() {
        var access=actualAccess();Object frame=access.earthplatform$survivalFrame(),preparation=access.earthplatform$survivalPreparation();
        if(ShadowRenderingState.areShadowsCurrentlyBeingRendered())throw new IllegalStateException("Earlier shadow passes cannot mint main content");
        resources.requireCurrent();var lease=resources.iris();
        if(!(lease.pipelineIdentity() instanceof IrisRenderingPipeline pipeline)||Iris.getPipelineManager().getPipelineNullable()!=pipeline)
            throw new IllegalStateException("Actual current Iris pipeline is absent before main solid");
        // The native TERRAIN_SOLID phase is established later inside the callee; do not assume it here.
        Matrix4f view=new Matrix4f(),projection=new Matrix4f();
        for(int column=0;column<4;column++)for(int row=0;row<4;row++){
            view.set(column,row,access.earthplatform$survivalPreparationMatrix(false,column,row));
            projection.set(column,row,access.earthplatform$survivalPreparationMatrix(true,column,row));
        }
        double x=access.earthplatform$survivalPreparationCamera(0),y=access.earthplatform$survivalPreparationCamera(1),z=access.earthplatform$survivalPreparationCamera(2);
        if(frame!=access.earthplatform$survivalFrame()||preparation!=access.earthplatform$survivalPreparation())
            throw new IllegalStateException("Actual main call site changed during scalar copy");
        var result=new RealFrame(frame,preparation,view,projection,x,y,z);requireMainPreparation(result);return result;
    }
    private void requireMainPreparation(RealFrame witness){
        witness.requireFrame(this);var access=actualAccess();Object token=access.earthplatform$survivalPreparation();
        if(token!=witness.preparation||ShadowRenderingState.areShadowsCurrentlyBeingRendered())throw new IllegalStateException("Actual main preparation retired");
        if(Double.compare(witness.x,access.earthplatform$survivalPreparationCamera(0))!=0||Double.compare(witness.y,access.earthplatform$survivalPreparationCamera(1))!=0
                ||Double.compare(witness.z,access.earthplatform$survivalPreparationCamera(2))!=0)throw new IllegalStateException("Actual main preparation camera changed");
        for(int c=0;c<4;c++)for(int r=0;r<4;r++)if(Float.floatToRawIntBits(witness.view.get(c,r))!=Float.floatToRawIntBits(access.earthplatform$survivalPreparationMatrix(false,c,r))
                ||Float.floatToRawIntBits(witness.projection.get(c,r))!=Float.floatToRawIntBits(access.earthplatform$survivalPreparationMatrix(true,c,r)))
            throw new IllegalStateException("Actual main preparation matrices changed");
        if(token!=access.earthplatform$survivalPreparation())throw new IllegalStateException("Actual main preparation retired during validation");witness.requireFrame(this);
    }
    private void requireMainInvocation(RealFrame witness) {
        witness.requireFrame(this);var access=actualAccess();Object invocation=access.earthplatform$survivalInvocation();
        if(ShadowRenderingState.areShadowsCurrentlyBeingRendered()||layerBit(access.earthplatform$survivalLayer())==0)
            throw new IllegalStateException("A main-scene witness cannot authorize shadow or unrelated terrain");
        var lease=resources.iris();
        if(!(lease.pipelineIdentity() instanceof IrisRenderingPipeline pipeline)
                ||Iris.getPipelineManager().getPipelineNullable()!=pipeline
                ||pipeline.getPhase()!=WorldRenderingPhase.fromTerrainRenderType(access.earthplatform$survivalLayer()))
            throw new IllegalStateException("Actual native Iris phase changed");
        if(Double.compare(witness.x,access.earthplatform$survivalCamera(0))!=0
                ||Double.compare(witness.y,access.earthplatform$survivalCamera(1))!=0
                ||Double.compare(witness.z,access.earthplatform$survivalCamera(2))!=0)
            throw new IllegalStateException("Actual native camera changed within the joined frame");
        for(int column=0;column<4;column++)for(int row=0;row<4;row++)
            if(Float.floatToRawIntBits(witness.view.get(column,row))!=Float.floatToRawIntBits(access.earthplatform$survivalMatrix(false,column,row))
                    ||Float.floatToRawIntBits(witness.projection.get(column,row))!=Float.floatToRawIntBits(access.earthplatform$survivalMatrix(true,column,row)))
                throw new IllegalStateException("Actual native matrices changed within the joined frame");
        if(invocation!=access.earthplatform$survivalInvocation())throw new IllegalStateException("Native invocation retired during validation");
        witness.requireFrame(this);
    }
    private static int layerBit(RenderType layer){return layer==RenderType.solid()?1:layer==RenderType.cutoutMipped()?2:layer==RenderType.cutout()?4:layer==RenderType.translucent()?8:0;}
    private static final class RealFrame {
        final Object frame,preparation;final Matrix4f view,projection;final double x,y,z;
        private RealFrame(Object frame,Object preparation,Matrix4f view,Matrix4f projection,double x,double y,double z){this.frame=frame;this.preparation=preparation;this.view=view;this.projection=projection;this.x=x;this.y=y;this.z=z;}
        void requireFrame(SurvivalSceneFrameJoin join){join.checkOpen();join.resources.requireCurrent();if(join.actualAccess().earthplatform$survivalFrame()!=frame)
            throw new IllegalStateException("Joined survival frame is retired; no historical next-frame reuse");}
    }
    /** The only constructor is private and called after actual hook capture. No raw matrices escape. */
    public static final class AcceptedFrame implements AutoCloseable {
        private SurvivalSceneFrameJoin owner;
        private RealFrame witness;
        private SurvivalSceneDepth.Depth depth;
        private long[] masks;
        private SurvivalCompleteOverlay.FrameLatch content;
        private NativeDistanceCpuBudget.Reservation charge;
        private int begunNative,completedNative;
        private io.earthplatform.compat.dh.shader.SurvivalDhPassWitness dhOpaque,dhTransparent;
        private boolean completedDh;
        private AcceptedFrame(SurvivalSceneFrameJoin owner,RealFrame witness,long[] masks,NativeDistanceCpuBudget.Reservation charge){
            this.owner=owner;this.witness=witness;this.masks=masks;this.charge=charge;
        }
        private void requireOpen(){if(owner==null||content==null)throw new IllegalStateException("Joined frame is unaccepted or retired");owner.checkOpen();}
        public void requireSameFrameResources(){requireOpen();witness.requireFrame(owner);owner.validateCandidate();}
        public void requireNativeInvocation(){requireSameFrameResources();owner.requireMainInvocation(witness);}
        public int selectedDistanceChunks(){requireSameFrameResources();return owner.plan.distance().selectedChunks();}
        public int effectiveDistanceChunks(){requireSameFrameResources();return owner.plan.distance().effectiveChunks();}
        public double guaranteedNativeRadius(){requireSameFrameResources();return depth.guaranteedRadius();}
        public long selectedMask(int page){requireSameFrameResources();return masks[page];}
        /** Last-moment resource check, deliberately without entering any physical owner. */
        public void requireDrawResources(){requireOpen();witness.requireFrame(owner);owner.resources.requireCurrent();}
        public InstalledIrisRuntime.Lease drawIrisLease(){requireDrawResources();return owner.resources.iris();}
        void requireBudget(NativeDistanceCpuBudget budget){requireOpen();if(owner.budget!=budget)throw new IllegalArgumentException("Foreign draw budget");}
        NativeDraw beginNative(){
            requireNativeInvocation();if(!owner.drawsRequired)throw new IllegalStateException("No typed draw adapter bound");
            var context=NativeIrisShaderStages.actualContext();requireNativeContext(context);
            int bit=layerBit(context.layer());if((begunNative&bit)!=0)throw new IllegalStateException("Native draw slot already consumed");begunNative|=bit;
            if(bit==8){completedNative|=bit;return null;}
            return new NativeDraw(this,context,bit,NativeIrisShaderStages.shaderPass(context,owner.resources.nativeCapture()));
        }
        private void requireNativeContext(NativeIrisShaderStages.Context context){
            owner.requireMainInvocation(witness);
            if(!context.isCurrent()||context.shadow()||context.level()!=owner.level||context.layer()!=owner.actualAccess().earthplatform$survivalLayer()
                    ||Double.compare(context.cameraX(),witness.x)!=0||Double.compare(context.cameraY(),witness.y)!=0||Double.compare(context.cameraZ(),witness.z)!=0)
                throw new IllegalStateException("Public native dispatch differs from the actual private invocation");
            for(int c=0;c<4;c++)for(int r=0;r<4;r++)if(Float.floatToRawIntBits(context.scalar(false,c,r))!=Float.floatToRawIntBits(witness.view.get(c,r))
                    ||Float.floatToRawIntBits(context.scalar(true,c,r))!=Float.floatToRawIntBits(witness.projection.get(c,r)))
                throw new IllegalStateException("Native dispatch matrices differ from actual private invocation");
        }
        /** Only a private actual-DH-hook witness can claim this slot; no event/matrix argument exists. */
        public SurvivalDhGpuPages.Completion claimDhPass(io.earthplatform.compat.dh.shader.SurvivalDhPassWitness pass){
            requireSameFrameResources();if(!owner.drawsRequired)throw new IllegalStateException("No typed draw adapter bound");
            pass.requireCurrent();
            for(int axis=0;axis<3;axis++)if(Double.compare(pass.camera(axis),axis==0?witness.x:axis==1?witness.y:witness.z)!=0)
                throw new IllegalStateException("Actual DH camera differs from joined native frame");
            for(int c=0;c<4;c++)for(int r=0;r<4;r++)if(Float.floatToRawIntBits(pass.matrix(false,c,r))!=Float.floatToRawIntBits(witness.view.get(c,r))
                    ||Float.floatToRawIntBits(pass.matrix(true,c,r))!=Float.floatToRawIntBits(witness.projection.get(c,r)))
                throw new IllegalStateException("Actual DH Minecraft matrices differ from joined native frame");
            if(pass.opaque()){if(dhOpaque!=null)throw new IllegalStateException("Duplicate actual opaque DH pass");dhOpaque=pass;}
            else {if(dhTransparent!=null)throw new IllegalStateException("Duplicate actual transparent DH pass");dhTransparent=pass;}
            return owner.dh;
        }
        public void requireDhPass(io.earthplatform.compat.dh.shader.SurvivalDhPassWitness pass,SurvivalDhGpuPages.Completion completion){
            requireDrawResources();if(completion!=owner.dh||(pass.opaque()?dhOpaque:dhTransparent)!=pass)
                throw new IllegalStateException("Different private DH pass or physical owner");
        }
        public void completedDhPass(io.earthplatform.compat.dh.shader.SurvivalDhPassWitness pass){
            requireDrawResources();pass.requireCompletionReady();if(pass.opaque()){if(dhOpaque!=pass||completedDh)throw new IllegalStateException("DH draw completion changed");completedDh=true;}
            else if(dhTransparent!=pass)throw new IllegalStateException("DH empty pass changed");
        }
        public void requireDhConfigurationOwner(SurvivalDhGpuPages.Completion completion){requireDrawResources();if(completion!=owner.dh)throw new IllegalStateException("Different DH generation owner");}
        public int drawSelectedDistance(){requireDrawResources();return owner.plan.distance().selectedChunks();}
        public int drawEffectiveDistance(){requireDrawResources();return owner.plan.distance().effectiveChunks();}
        public double drawGuaranteedRadius(){requireDrawResources();return depth.guaranteedRadius();}
        @Override public void close(){if(owner==null)return;owner.checkOwner();if(content!=null){content.close();content=null;}
            witness=null;depth=null;masks=null;dhOpaque=null;dhTransparent=null;if(charge!=null){charge.releaseAfterPhysicalDrain();charge=null;}owner=null;}
    }
    /** Package-only typed invocation; caller matrices/masks/layers never construct it. */
    static final class NativeDraw {
        final AcceptedFrame frame;
        final NativeIrisShaderStages.Context context;
        final SceneNativeShaderContract.ShaderPass host;
        final int bit;
        private int nextPage;
        private final float[] matrixScratch=new float[16];
        private final double[] depthScratch=new double[2];
        private Matrix4f pageView;
        private boolean inPage,finished;
        private NativeDraw(AcceptedFrame frame,NativeIrisShaderStages.Context context,int bit,SceneNativeShaderContract.ShaderPass host){this.frame=frame;this.context=context;this.bit=bit;this.host=host;}
        void requireCurrent(){
            if(finished)throw new IllegalStateException("Native draw scope retired");frame.requireDrawResources();frame.requireNativeContext(context);
            frame.owner.dh.requireDrawConfiguration(frame);if(!host.isCurrent())throw new IllegalStateException("Native borrowed shader retired");
        }
        int pageCount(){return frame.owner.nativePages.length;}
        SurvivalNativeGpuPage.Completion page(int index){requireCurrent();return frame.owner.nativePages[index];}
        long mask(int index){return frame.masks[index];}
        io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler.Layer layer(){return switch(bit){case 1->io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler.Layer.SOLID;case 2->io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler.Layer.CUTOUT_MIPPED;case 4->io.earthplatform.minecraft.survival.render.SurvivalNativePageCompiler.Layer.CUTOUT;default->throw new IllegalStateException("No compiled native layer");};}
        Matrix4f translatedView(int index){
            var minimum=frame.owner.plan.page(index).address().minimum();
            return new Matrix4f(frame.witness.view).translate(offset(minimum.minBlockX(),frame.witness.x),offset(minimum.minBlockY(),frame.witness.y),offset(minimum.minBlockZ(),frame.witness.z));
        }
        Matrix4f projection(){return new Matrix4f(frame.witness.projection);}
        void beginPage(SurvivalNativeGpuPage.Completion page,int index){
            requireCurrent();if(inPage||index!=nextPage||index<0||index>=pageCount()||frame.owner.nativePages[index]!=page)
                throw new IllegalStateException("Missing/repeated/foreign native physical page");
            var capture=frame.owner.resources.nativeCapture();page.requireAuthenticated(frame.owner.plan,capture,SceneNeighborhood.LightingPolicy.SHADER_OUTDOOR_NON_EMISSIVE,capture.values().disableDirectionalShading());pageView=translatedView(index);inPage=true;
        }
        void endPage(boolean success){if(!inPage)throw new IllegalStateException("No native physical operation");inPage=false;pageView=null;if(success)nextPage++;}
        void beforeRange(){requireCurrent();if(!inPage)throw new IllegalStateException("Native range outside exact physical operation");NativeIrisShaderStages.requireSurvivalBoundState(context,host.shader(),depthScratch);
            requireUploaded(host.shader().MODEL_VIEW_MATRIX,pageView);requireUploaded(host.shader().PROJECTION_MATRIX,frame.witness.projection);
            io.earthplatform.compat.dh.shader.StrictGlErrors.check("survival-native.pass.matrices");}
        private void requireUploaded(com.mojang.blaze3d.shaders.Uniform uniform,Matrix4f expected){
            if(uniform==null||uniform.getLocation()<0)throw new IllegalStateException("Actual native matrix uniform missing");
            org.lwjgl.opengl.GL33C.glGetUniformfv(host.shader().getId(),uniform.getLocation(),matrixScratch);
            for(int c=0;c<4;c++)for(int r=0;r<4;r++)if(Float.floatToRawIntBits(matrixScratch[c*4+r])!=Float.floatToRawIntBits(expected.get(c,r)))
                throw new IllegalStateException("Actual uploaded native matrix changed");
        }
        void complete(){requireCurrent();if(inPage||nextPage!=pageCount())throw new IllegalStateException("Incomplete native page roster");frame.completedNative|=bit;finished=true;}
        private static float offset(long origin,double camera){double d=origin-camera;if(!Double.isFinite(d)||Math.abs(d)>16384)throw new IllegalStateException("Native page exceeds precision envelope");return (float)d;}
    }
    public AcceptedFrame acceptedFrame(){checkOpen();if(accepted==null)throw new IllegalStateException("No genuine main frame was accepted");accepted.requireSameFrameResources();return accepted;}
    /** Called by the real wrapper's finally after it clears renderer-private state. */
    public void endActualFrame(){
        checkOwner();if(!frameOpen)return;
        try{
            if(accepted!=null){if((observedMainLayers&7)!=7)refuse("Actual frame omitted a required native main layer");
                else if(!failed)completedMainWitnesses=Math.incrementExact(completedMainWitnesses);
                if(drawsRequired&&!failed){if((accepted.completedNative&7)!=7||!accepted.completedDh)refuse("Actual frame omitted required native/DH drawing");
                    else completedMainDraws=Math.incrementExact(completedMainDraws);}
                accepted.close();accepted=null;}
            else if(pendingContentFrame!=null){
                if((observedMainLayers&7)!=7)refuse("Pending content frame omitted required native layers");
                else if(!failed)suppressedContentFrames=Math.incrementExact(suppressedContentFrames);
            }
        }finally{pendingContentFrame=null;frameOpen=false;if(frameCharge!=null){frameCharge.releaseAfterPhysicalDrain();frameCharge=null;}}
    }
    /** Restore every exact physical binding owner before upstream may delete borrowed objects. */
    public void restorePhysicalBindings(){
        checkOwner();Throwable failure=null;
        if(nativePages!=null)for(var completion:nativePages)if(completion!=null)try{completion.restorePendingBindings();}catch(RuntimeException|Error error){failure=SceneBuildStepper.append(failure,error);}
        if(dh!=null)try{dh.restorePendingBindings();}catch(RuntimeException|Error error){failure=SceneBuildStepper.append(failure,error);}
        SceneBuildStepper.rethrow(failure);
    }
    /** Revocation only. This cannot create, revive or replace any proof. */
    public void refuse(String reason){checkOwner();failed=true;if(failure.isEmpty()){String value=String.valueOf(reason);failure=value.substring(0,Math.min(256,value.length()));}}
    public record Evidence(long acceptedFrames,long completedMainWitnesses,long completedMainDraws,
            long suppressedContentFrames,boolean contentPending,boolean failed,String failure){}
    public Evidence evidence(){checkOwner();return new Evidence(acceptedFrames,completedMainWitnesses,completedMainDraws,
            suppressedContentFrames,contentPending,failed,failure);}
    private void checkOwner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Survival frame join requires its exact render owner");RenderSystem.assertOnRenderThread();}
    private void checkOpen(){checkOwner();if(closed||closing||failed||!initialized)throw new IllegalStateException("Survival frame candidate is closed or refused: "+failure);}
    @Override public void close(){checkOwner();if(closed)return;
        if(initializing)throw new IllegalStateException("Frame join initialization has not returned");
        if(frameOpen||io.earthplatform.compat.dh.shader.ShaderCarrierHooks.survivalRetains(this))
            throw new IllegalStateException("Close the typed carrier after the actual frame before releasing its joined owners");
        closing=true; // A failed close stays cleanup-only; closed means every retained alias drained.
        restorePhysicalBindings();
        if(proposal!=null){proposal.close();proposal=null;}
        if(rebindValidation!=null){rebindValidation.close();rebindValidation=null;}
        if(accepted!=null){accepted.close();accepted=null;}
        pendingRebind=null;pendingContentFrame=null;current=null;nativePages=null;dh=null;
        if(resources!=null){resources.release();resources=null;}
        if(coverage!=null){coverage.close();coverage=null;}
        if(plan!=null){plan.close();plan=null;}
        if(frameCharge!=null){frameCharge.releaseAfterPhysicalDrain();frameCharge=null;}
        if(metadata!=null){metadata.releaseAfterPhysicalDrain();metadata=null;}
        renderer=null;level=null;if(attached==this)attached=null;
        closed=true;closing=false;
    }
}
