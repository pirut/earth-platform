package io.earthplatform.compat.dh.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.compat.dh.InstalledDhCarrierPolicy;
import io.earthplatform.compat.dh.InstalledDhResolver;
import io.earthplatform.compat.dh.InstalledIrisEventPolicy;
import io.earthplatform.compat.dh.InstalledIrisRuntime;
import io.earthplatform.minecraft.scene.SurvivalNativeGpuBatch;
import io.earthplatform.minecraft.scene.SurvivalSceneFrameJoin;
import io.earthplatform.minecraft.scene.SurvivalSceneResources;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.util.Objects;
import net.irisshaders.iris.shadows.ShadowRenderingState;

/** Explicit main-only installed simultaneous draw gate. It consumes private actual hook witnesses;
 * no caller Frame/Host/camera/matrix inputs exist. Parent owns scheduling, the exact content lease,
 * resource bundle and all physical pages until this adapter's restoration and their cleanup finish. */
public final class InstalledSurvivalShaderCarrier implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private SurvivalSceneFrameJoin join;
    private SurvivalSceneResources resources;
    private InstalledIrisRuntime.Lease pipeline;
    private InstalledDhCarrierPolicy dhPolicy;
    private InstalledIrisEventPolicy listeners;
    private SurvivalNativeGpuBatch nativeBatch;
    private IrisShaderFarBackend far;
    private NativeDistanceCpuBudget.Reservation metadata;
    private final ShaderLifecycleBarrier lifecycle=new ShaderLifecycleBarrier();
    private boolean started,closed,restorationFailureEscapes;
    private SurvivalDhPassWitness pendingPass;
    private String failure="";
    public InstalledSurvivalShaderCarrier(SurvivalSceneFrameJoin join,SurvivalSceneResources resources,
            InstalledDhResolver.Runtime installedDh,InstalledIrisRuntime iris,NativeDistanceCpuBudget budget){
        owner();Objects.requireNonNull(installedDh);Objects.requireNonNull(iris);Objects.requireNonNull(budget);
        resources.requireBudget(budget);resources.requireCurrent();
        // Fixed 8 KiB covers this adapter, batch/backend shells, policies, lifecycle barrier,
        // and ONE pending DH witness (two 16-float arrays, scalars, token/program/frame refs)
        // even after AcceptedFrame.close drains its own frame reservation. No pending-pass queue.
        metadata=budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,8192);
        try{
            this.join=Objects.requireNonNull(join);this.resources=resources;pipeline=resources.iris();
            dhPolicy=new InstalledDhCarrierPolicy(installedDh);listeners=new InstalledIrisEventPolicy(installedDh,pipeline);
            nativeBatch=new SurvivalNativeGpuBatch(budget);far=new IrisShaderFarBackend(iris,budget);
            join.requireDrawAdapter(resources,budget);
        }catch(RuntimeException|Error error){metadata.releaseAfterPhysicalDrain();metadata=null;throw error;}
    }
    public void start(){owner();if(started||closed)throw new IllegalStateException("Survival carrier already started/closed");prepare();ShaderCarrierHooks.claim(this);started=true;}
    boolean retainsJoin(SurvivalSceneFrameJoin exact){owner();return !closed&&join==exact;}
    boolean owns(Object exact){owner();return started&&!closed&&join.ownsLevel(exact);}
    private void prepare(){
        owner();if(closed||!failure.isEmpty())throw new IllegalStateException("Survival shader owner fenced: "+failure);
        resources.requireCurrent();if(!pipeline.current())throw new IllegalStateException("Genuine Iris lease changed");
        Object level=net.minecraft.client.Minecraft.getInstance().level;
        if(!join.ownsLevel(level)||!dhPolicy.allows(level))throw new IllegalStateException("Actual survival level is not admitted by installed DH");
        listeners.requireCurrent();
    }
    /** Content-only rebind must not cross a native/DH pass or borrowed-state cleanup. */
    void requireRebindQuiescent(){
        owner();prepare();
        if(!started||closed||restorationFailureEscapes||pendingPass!=null
                ||nativeBatch.restorationPending()||far.restorationPending())
            throw new IllegalStateException("Survival carrier has an active pass or pending restoration");
    }
    void requireHookReservation(){owner();join.requireHookReservation();}
    /** Public legacy dispatch arguments are deliberately not consumed by this branch. */
    void nativeLayer(){
        if(ShadowRenderingState.areShadowsCurrentlyBeingRendered())return;
        try{prepare();if(join.skipPendingContentFrame())return;nativeBatch.draw(join.acceptedFrame());}catch(RuntimeException|Error error){contain(error);}
    }
    void terrain(){
        if(ShadowRenderingState.areShadowsCurrentlyBeingRendered())return;
        Throwable problem=null;
        try{prepare();if(join.skipPendingContentFrame())return;if(pendingPass!=null)throw new IllegalStateException("Prior DH witness remains owned");pendingPass=SurvivalDhPassWitness.capture(join.acceptedFrame());far.inSurvivalPass(pendingPass);}
        catch(RuntimeException|Error error){problem=error;}
        if(pendingPass!=null)try{pendingPass.retire();pendingPass=null;}catch(RuntimeException|Error error){problem=ShaderLifecycleBarrier.append(problem,error);}
        if(problem!=null)contain(problem);
    }
    /** Failures propagate at upstream mutation boundaries until every borrowed restore succeeds. */
    public void invalidate(String reason){
        owner();if(closed)return;if(failure.isEmpty()){String text=String.valueOf(reason);failure=text.substring(0,Math.min(256,text.length()));}
        resources.retire();join.refuse(reason);
        lifecycle.restore(this::restoreNativeAndPhysical,this::restoreFarAndPass);
    }
    private void restoreNativeAndPhysical(){
        Throwable problem=null;try{nativeBatch.restorePendingState();}catch(RuntimeException|Error error){problem=error;}
        try{join.restorePhysicalBindings();}catch(RuntimeException|Error error){problem=ShaderLifecycleBarrier.append(problem,error);}
        ShaderLifecycleBarrier.propagate(problem);
    }
    private void restoreFarAndPass(){
        Throwable problem=null;try{far.restorePendingState();}catch(RuntimeException|Error error){problem=error;}
        if(pendingPass!=null)try{pendingPass.retire();pendingPass=null;}catch(RuntimeException|Error error){problem=ShaderLifecycleBarrier.append(problem,error);}
        ShaderLifecycleBarrier.propagate(problem);
    }
    void contain(Throwable problem){
        try{invalidate(problem.getClass().getSimpleName()+": "+problem.getMessage());}
        catch(RuntimeException|Error cleanup){restorationFailureEscapes=true;ShaderLifecycleBarrier.append(problem,cleanup);}
        // NativeIrisShaderStages catches once and reports through failed(). Sticky escape prevents
        // that outer diagnostic catch from silently swallowing an unrestored borrowed-state failure.
        if(restorationFailureEscapes)ShaderLifecycleBarrier.propagate(problem);
    }
    boolean pipelineAcquired(){return pipeline!=null;}
    /** Reload alias admission checks the genuine lease, not the already-revoked compilation token. */
    boolean pipelineCurrent(){owner();return !closed&&failure.isEmpty()&&pipeline!=null&&pipeline.current();}
    public String failure(){owner();return failure;}
    public SurvivalNativeGpuBatch.Evidence nativeEvidence(){owner();return nativeBatch.evidence();}
    public long depthValidatedPasses(){owner();return far.depthValidatedPasses();}
    @Override public void close(){
        owner();if(closed)return;invalidate("Survival shader carrier closing");
        nativeBatch.close();far.close();if(nativeBatch.restorationPending()||far.restorationPending())throw new IllegalStateException("Survival borrowed cleanup remains charged");
        if(started)ShaderCarrierHooks.release(this);closed=true;started=false;
        join=null;resources=null;pipeline=null;dhPolicy=null;listeners=null;nativeBatch=null;far=null;
        if(metadata!=null){metadata.releaseAfterPhysicalDrain();metadata=null;}
    }
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Survival shader carrier requires render owner");RenderSystem.assertOnRenderThread();}
}
