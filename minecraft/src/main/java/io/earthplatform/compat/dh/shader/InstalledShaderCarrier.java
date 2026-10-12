package io.earthplatform.compat.dh.shader;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import io.earthplatform.compat.dh.InstalledIrisRuntime;
import java.util.Objects;

/** Exact carrier ownership shared by native and DH hooks. Upstream rendering is borrowed,
 * and ordinary worlds never enter this bridge. Failure stays contained in our session. */
public final class InstalledShaderCarrier implements AutoCloseable {
    public record Frame(long serial,long resourceGeneration,String pairDigest,boolean historical){}
    public interface Host {
        Frame frame();
        boolean presentationCurrent(String pairDigest,boolean historical);
        void renderFar(DhApiRenderParam context,double cameraX,double cameraY,double cameraZ);
        void renderNative(DhShaderPassGate.Pass pass,Object transientContext);
        default void observeNative(DhShaderPassGate.Pass pass,Object transientContext){}
        void closeOwned();
        void failed(Throwable failure);
        /** Called synchronously before an upstream lifecycle/settings mutation continues. */
        default void invalidate(String reason){}
    }
    private record Far(DhApiRenderParam parameters,double x,double y,double z,boolean opaque,boolean shadow){}
    private final Object level;private final String world;private final long epoch,resources;
    private final Thread owner=Thread.currentThread();private final InstalledIrisRuntime runtime;private final IrisShaderFarBackend backend;private final Host host;
    private final DhShaderPassGate gate=new DhShaderPassGate();
    private final io.earthplatform.compat.dh.InstalledDhCarrierPolicy dhPolicy;
    private final io.earthplatform.compat.dh.InstalledDhResolver.Runtime installedDh;
    private io.earthplatform.compat.dh.InstalledIrisEventPolicy listeners;
    private InstalledIrisRuntime.Lease pipeline;private DhShaderPassGate.Lease lease;
    private final boolean nativeDistance;
    private boolean started,closed,resourcesClosed,pairPresent;private volatile String failure="";
    private final ShaderLifecycleBarrier lifecycle=new ShaderLifecycleBarrier();
    public InstalledShaderCarrier(Object exactCarrier,String world,long epoch,long resourceGeneration,io.earthplatform.compat.dh.InstalledDhResolver.Runtime installedDh,InstalledIrisRuntime runtime,IrisShaderFarBackend backend,Host host){
        this(exactCarrier,world,epoch,resourceGeneration,installedDh,runtime,backend,host,false);
    }
    public InstalledShaderCarrier(Object exactCarrier,String world,long epoch,long resourceGeneration,io.earthplatform.compat.dh.InstalledDhResolver.Runtime installedDh,InstalledIrisRuntime runtime,IrisShaderFarBackend backend,Host host,boolean nativeDistance){
        this.nativeDistance=nativeDistance;
        level=Objects.requireNonNull(exactCarrier);this.world=Objects.requireNonNull(world);this.epoch=epoch;resources=resourceGeneration;
        this.installedDh=Objects.requireNonNull(installedDh);
        dhPolicy=new io.earthplatform.compat.dh.InstalledDhCarrierPolicy(installedDh);
        this.runtime=Objects.requireNonNull(runtime);this.backend=Objects.requireNonNull(backend);this.host=Objects.requireNonNull(host);
        if(world.isBlank()||epoch<0||resources<1||!runtime.installationVerified())throw new IllegalArgumentException("Validated exact shader carrier required");
    }
    public void start(){owner();if(started||closed)throw new IllegalStateException("Shader carrier already started/closed");ShaderCarrierHooks.claim(this);started=true;}
    private boolean suspended;
    /** Stops callback admission before an exact settings transition, retaining all cleanup owners. */
    public void suspend(String reason){owner();suspended=true;invalidate(reason);}
    boolean owns(Object exactLevel){return started&&!closed&&!suspended&&level==exactLevel;}
    /** Called by both actual hooks. No preparation or pipeline creation is invoked here. */
    private Frame prepare(){
        owner();if(closed||!failure.isEmpty())return null;
        if(!dhPolicy.allows(level))throw new IllegalStateException("Installed DH ignored-dimension policy excludes this carrier");
        if(pipeline==null){
            pipeline=nativeDistance?runtime.leaseDistance():runtime.lease();
            listeners=new io.earthplatform.compat.dh.InstalledIrisEventPolicy(installedDh,pipeline);
            int mask=0;for(var pass:DhShaderPassGate.Pass.values())if(pass!=DhShaderPassGate.Pass.SHADOW_DH_OPAQUE&&pass!=DhShaderPassGate.Pass.SHADOW_DH_TRANSLUCENT)mask|=pass.bit();
            lease=gate.acquire(level,pipeline.pipelineIdentity(),world,epoch,resources,mask,new DhShaderPassGate.Resources(){
                public boolean presentationCurrent(String pair,boolean historical){return pipeline.current()&&host.presentationCurrent(pair,historical);}
                public void draw(DhShaderPassGate.Pass pass,Object context){
                    if(context instanceof Far far){
                        // Initial content policy is opaque AIR/STONE/DIORITE. A real deferred
                        // pass is consumed with zero geometry, never redrawn as opaque.
                        if(far.opaque())backend.inPass(pipeline,far.shadow(),true,far.parameters(),far.x(),far.y(),far.z(),()->host.renderFar(far.parameters(),far.x(),far.y(),far.z()));
                    }else host.renderNative(pass,context);
                }
                public void close(){closeResources();}
            });
        }
        if(!pipeline.current())throw new IllegalStateException("Installed shader pipeline lease was revoked");
        listeners.requireCurrent();
        Frame frame=Objects.requireNonNull(host.frame());
        if(frame.resourceGeneration()!=resources)throw new IllegalStateException("Shader resource generation changed");
        pairPresent=frame.pairDigest()!=null;
        if(!gate.beginFrame(lease,frame.serial(),pipeline.pipelineIdentity(),resources,frame.pairDigest(),frame.historical()))throw new IllegalStateException("Shader frame authority rejected: "+gate.counters().failure());
        return frame;
    }
    void terrain(Object exactLevel,DhApiRenderParam parameters,boolean opaque,boolean shadow,double x,double y,double z){
        if(!owns(exactLevel))return;
        try{
            var expected=opaque?com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass.OPAQUE:com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass.TRANSPARENT;
            if(parameters.renderPass!=expected){
                if(parameters.renderPass==com.seibel.distanthorizons.api.enums.rendering.EDhApiRenderPass.OPAQUE_AND_TRANSPARENT&&!pairPresent)return;
                throw new IllegalStateException("Unsupported combined or mismatched DH shader pass");
            }
            Frame frame=prepare();if(frame==null)return;
            var pass=shadow?(opaque?DhShaderPassGate.Pass.SHADOW_DH_OPAQUE:DhShaderPassGate.Pass.SHADOW_DH_TRANSLUCENT):(opaque?DhShaderPassGate.Pass.MAIN_DH_OPAQUE:DhShaderPassGate.Pass.MAIN_DH_TRANSLUCENT);
            requireSubmission(gate.submit(lease,level,frame.serial(),pipeline.pipelineIdentity(),resources,pass,new Far(parameters,x,y,z,opaque,shadow)));
        }catch(RuntimeException|Error problem){contain(problem);}
    }
    void nativeLayer(Object exactLevel,DhShaderPassGate.Pass pass,Object context){
        if(!owns(exactLevel))return;
        try{if(pass.name().contains("_DH_"))throw new IllegalArgumentException("Native hook supplied a DH pass");host.observeNative(pass,context);Frame frame=prepare();if(frame==null)return;
            requireSubmission(gate.submit(lease,level,frame.serial(),pipeline.pipelineIdentity(),resources,pass,context));
        }catch(RuntimeException|Error problem){contain(problem);}
    }
    private void requireSubmission(DhShaderPassGate.Result result){if(result==DhShaderPassGate.Result.FENCED||result==DhShaderPassGate.Result.STALE_LEASE)throw new IllegalStateException("Shader submission fenced: "+gate.counters().failure());}
    /** Lifecycle HEAD callers must propagate failure: upstream objects cannot be
     * destroyed until every restoration owner has completed. */
    public void invalidate(String reason){
        gate.invalidate(reason);if(failure.isEmpty())failure=bounded(reason);
        owner();lifecycle.restore(()->host.invalidate(reason),backend::restorePendingState);
    }
    /** Renderer callbacks contain failures, while retaining the fenced registration
     * and all failed cleanup owners for the next explicit cleanup attempt. */
    void contain(Throwable problem){
        try{invalidate(problem.getClass().getSimpleName()+": "+problem.getMessage());}
        catch(RuntimeException|Error cleanup){ShaderLifecycleBarrier.append(problem,cleanup);}
        try{host.failed(problem);}catch(RuntimeException|Error reporting){ShaderLifecycleBarrier.append(problem,reporting);}
    }
    public DhShaderPassGate.Counters counters(){return gate.counters();}
    public long submissions(DhShaderPassGate.Pass pass){return gate.submissions(pass);}
    public DhShaderDepthContract.Receipt depthReceipt(){return backend.depthReceipt();}
    public long depthValidatedPasses(){return backend.depthValidatedPasses();}
    public String failure(){return failure;}public boolean ownsCarrier(){return started&&!closed;}public boolean pipelineAcquired(){return pipeline!=null;}
    /** Never admits or hashes a pipeline. */
    public boolean pipelineCurrent(){return pipeline!=null&&pipeline.current()&&failure.isEmpty()&&!closed;}
    public InstalledIrisRuntime.Lease admittedPipeline(){if(!pipelineCurrent())throw new IllegalStateException("No current admitted shader pipeline");return pipeline;}
    private void closeResources(){Throwable primary=null;
        try{host.closeOwned();resourcesClosed=true;}catch(RuntimeException|Error problem){primary=problem;}
        try{backend.close();}catch(RuntimeException|Error problem){if(primary==null)primary=problem;else if(primary!=problem)primary.addSuppressed(problem);}
        if(primary instanceof Error error)throw error;if(primary instanceof RuntimeException error)throw error;
    }
    @Override public void close(){owner();if(closed)return;invalidate("Shader carrier closing");
        if(lease!=null)gate.close();else closeResources();
        if(!resourcesClosed||backend.restorationPending())throw new IllegalStateException("Shader cleanup remains owned");
        if(pipeline!=null)runtime.release(pipeline);else runtime.releaseAllReferences();pipeline=null;listeners=null;
        ShaderCarrierHooks.release(this);closed=true;
    }
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Shader carrier owner thread required");}
    private static String bounded(String value){String text=String.valueOf(value);return text.substring(0,Math.min(256,text.length()));}
}
