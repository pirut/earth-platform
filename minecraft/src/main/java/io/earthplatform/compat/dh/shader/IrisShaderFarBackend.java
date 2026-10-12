package io.earthplatform.compat.dh.shader;

import io.earthplatform.compat.dh.InstalledIrisRuntime;
import java.util.Objects;
import static org.lwjgl.opengl.GL33C.*;

/** Borrowed-pass backend: owns no Iris program, framebuffer, texture or pool.
 * Engine VAO/VBO/IBO owners remain in the existing bounded geographic driver. */
public final class IrisShaderFarBackend implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private final InstalledIrisRuntime runtime;
    private final io.earthplatform.minecraft.visual.NativeDistanceCpuBudget survivalBudget;
    private io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Reservation survivalCharge;
    private SurvivalDhPassWitness activeSurvival;
    private SurvivalDepthCheck survivalDepth;
    private DhPageUniformScope pendingUniform;
    private GlBindingRestore pendingBindings;
    private InstalledIrisRuntime.Lease activeLease;
    private InstalledIrisRuntime.BorrowedPass activePass;
    private double x,y,z;private boolean drawing,closed;
    private java.util.function.Supplier<io.earthplatform.minecraft.visual.NativeVisualPublication> distancePublication;
    private io.earthplatform.minecraft.visual.NativeVisualPublication activeVisual;
    private DhShaderDepthContract.Receipt depthReceipt;private long depthValidatedPasses;
    public IrisShaderFarBackend(InstalledIrisRuntime runtime){this(runtime,null);}
    IrisShaderFarBackend(InstalledIrisRuntime runtime,io.earthplatform.minecraft.visual.NativeDistanceCpuBudget survivalBudget){this.runtime=Objects.requireNonNull(runtime);this.survivalBudget=survivalBudget;if(!runtime.installationVerified())throw new IllegalArgumentException("Unverified installed shader runtime");}
    /** Opt-in once before any pass. Only an authenticated owner publication supplies the draw radius. */
    public void bindNativeDistance(java.util.function.Supplier<io.earthplatform.minecraft.visual.NativeVisualPublication> publication){
        owner();if(closed||drawing||distancePublication!=null||depthValidatedPasses!=0||pendingUniform!=null||pendingBindings!=null)
            throw new IllegalStateException("Native distance source must be installed once before borrowed passes");
        distancePublication=Objects.requireNonNull(publication);
    }
    private void requireVisual(){
        if(distancePublication==null)return;
        if(activeVisual==null||distancePublication.get()!=activeVisual)throw new IllegalStateException("Native visual publication changed during borrowed pass");
        activeVisual.requireCurrent();
    }
    /** Only invoked by the exact installed DH pass hook after all upstream setup. */
    public void inPass(InstalledIrisRuntime.Lease lease,boolean shadow,boolean opaque,com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam parameters,double x,double y,double z,Runnable draw){
        owner();Objects.requireNonNull(draw);if(closed||drawing||pendingUniform!=null||pendingBindings!=null)throw new IllegalStateException("Prior shader pass cleanup remains owned");
        if(!lease.current())throw new IllegalStateException("Stale installed shader lease");
        var pass=lease.dhPass(shadow,opaque);
        if(!opaque)throw new IllegalStateException("Opaque cubic content may not be submitted into a DH transparent pass");
        this.x=x;this.y=y;this.z=z;activeLease=lease;activePass=pass;drawing=true;Throwable failure=null;
        try{
            pendingUniform=new DhPageUniformScope(GL,pass.programId(),pass.modelOffsetLocation(),pass.framebufferId(),x,y,z,16384,32768);
            validateDepth(pass,parameters);
            requireVisual();draw.run();requireVisual();
            if(!lease.current())throw new IllegalStateException("Shader lease changed during owned far submission");
        }catch(RuntimeException|Error error){failure=error;}
        finally{
            try{restoreUniform();}catch(RuntimeException|Error error){failure=append(failure,error);}
            drawing=false;activeLease=null;activePass=null;activeVisual=null;
        }
        propagate(failure);
    }
    /** No caller callback, matrix, boolean, serial or string can authorize this typed draw. */
    void inSurvivalPass(SurvivalDhPassWitness witness){
        owner();Objects.requireNonNull(witness);witness.requireCurrent();
        if(survivalBudget==null||distancePublication!=null||closed||drawing||pendingUniform!=null||pendingBindings!=null||survivalCharge!=null)
            throw new IllegalStateException("Typed survival backend is unbound or retains a prior pass");
        if(!witness.opaque()){witness.complete();return;}
        survivalCharge=survivalBudget.reserve(io.earthplatform.minecraft.visual.NativeDistanceCpuBudget.Pool.OWNER_METADATA,16L<<10);
        Throwable failure=null;
        try{
            activeLease=witness.iris();activePass=activeLease.dhPass(false,true);
            x=witness.camera(0);y=witness.camera(1);z=witness.camera(2);activeSurvival=witness;drawing=true;
            witness.beginBackend(this);
            pendingUniform=new DhPageUniformScope(GL,activePass.programId(),activePass.modelOffsetLocation(),activePass.framebufferId(),x,y,z,16384,32768);
            survivalDepth=new SurvivalDepthCheck();survivalDepth.validate(witness);
            if(depthValidatedPasses<Long.MAX_VALUE)depthValidatedPasses++;
            requireSurvivalPass(witness);witness.drawCompletion();requireSurvivalPass(witness);
        }catch(RuntimeException|Error error){failure=error;}
        finally{
            try{restoreUniform();}catch(RuntimeException|Error error){failure=append(failure,error);}
            drawing=false;activeLease=null;activePass=null;activeSurvival=null;survivalDepth=null;
            if(pendingUniform==null&&pendingBindings==null&&survivalCharge!=null){survivalCharge.releaseAfterPhysicalDrain();survivalCharge=null;}
        }
        if(failure==null)witness.complete();propagate(failure);
    }
    void requireSurvivalPass(SurvivalDhPassWitness exact){
        owner();if(activeSurvival!=exact||survivalDepth==null)throw new IllegalStateException("Different actual DH borrowed pass");
        exact.requireCurrent();requirePass(exact.camera(0),exact.camera(1),exact.camera(2));
        // Exact DH 3.3.3 classic forward-Z setGLState: enabled depth, true mask, GL_LESS.
        if(!glIsEnabled(GL_DEPTH_TEST)||!glGetBoolean(GL_DEPTH_WRITEMASK)||glGetInteger(GL_DEPTH_FUNC)!=GL_LESS
                ||!glIsProgram(activePass.programId())||!glIsFramebuffer(activePass.framebufferId()))
            throw new IllegalStateException("Actual opaque DH depth/program/framebuffer state changed");
        survivalDepth.validate(exact);
        StrictGlErrors.check("survival-dh.pass.current");
    }
    void requireSurvivalPage(SurvivalDhPassWitness exact,io.earthplatform.minecraft.lod.DhGeographicLodData.TileKey key){
        if(activeSurvival!=exact||survivalDepth==null)throw new IllegalStateException("No actual DH page scope");
        glGetUniformfv(activePass.programId(),activePass.modelOffsetLocation(),survivalDepth.offset);
        float ox=(float)(key.originX()-x),oy=(float)(Math.multiplyExact((long)key.pageY(),io.earthplatform.minecraft.lod.DhTerrainGeometry.PAGE_HEIGHT)-y),oz=(float)(key.originZ()-z);
        if(Float.floatToRawIntBits(ox)!=Float.floatToRawIntBits(survivalDepth.offset[0])||Float.floatToRawIntBits(oy)!=Float.floatToRawIntBits(survivalDepth.offset[1])
                ||Float.floatToRawIntBits(oz)!=Float.floatToRawIntBits(survivalDepth.offset[2]))throw new IllegalStateException("Actual DH page offset changed");
        StrictGlErrors.check("survival-dh.page.offset");
    }
    private final class SurvivalDepthCheck {
        private final float[] projection=new float[16],view=new float[16],irisProjection=new float[16],irisView=new float[16];
        private final double[] range=new double[2];
        private final float[] offset=new float[3];
        void validate(SurvivalDhPassWitness witness){
            var module=activeLease.pipelineIdentity().getClass().getModule();
            if(net.irisshaders.iris.compat.dh.DHCompat.class.getModule()!=module||net.irisshaders.iris.uniforms.CapturedRenderingState.class.getModule()!=module)
                throw new IllegalStateException("Foreign installed Iris depth provider");
            glGetUniformfv(activePass.programId(),activePass.projectionLocation(),projection);glGetUniformfv(activePass.programId(),activePass.modelViewLocation(),view);glGetDoublev(GL_DEPTH_RANGE,range);
            net.irisshaders.iris.compat.dh.DHCompat.getProjection().get(irisProjection);
            net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.getGbufferModelView().get(irisView);
            depthReceipt=DhShaderDepthContract.verifyDistance(witness.selected(),witness.effective(),witness.radius(),witness.near(),witness.far(),
                    net.irisshaders.iris.compat.dh.DHCompat.getNearPlane(),net.irisshaders.iris.compat.dh.DHCompat.getFarPlane(),range,projection,irisProjection,view,irisView);
            witness.requireCurrent();StrictGlErrors.check("survival-dh.depth.validation");
        }
    }
    public void requirePass(double x,double y,double z){owner();if(!drawing||pendingUniform==null||activeLease==null||!activeLease.current()||Double.compare(this.x,x)!=0||Double.compare(this.y,y)!=0||Double.compare(this.z,z)!=0)
        throw new IllegalStateException("Far draw is outside its exact Iris pass/camera");
        requireVisual();
        if(glGetInteger(GL_CURRENT_PROGRAM)!=activePass.programId()||glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING)!=activePass.framebufferId())throw new IllegalStateException("Borrowed Iris program/target changed");
    }
    public void page(long originX,long originY,long originZ){owner();if(!drawing||pendingUniform==null)throw new IllegalStateException("No borrowed page scope");pendingUniform.page(originX,originY,originZ);}
    /** Upload/draw only changes these bindings. Never replay global blend/depth/texture
     * state over Iris's per-target configuration. Retain failed restore for close retry. */
    public Runnable captureBindings(){owner();if(closed||pendingBindings!=null)throw new IllegalStateException("Shader binding cleanup pending");pendingBindings=GlBindingRestore.capture();GlBindingRestore exact=pendingBindings;return()->{owner();exact.restore();if(pendingBindings==exact)pendingBindings=null;};}
    private void validateDepth(InstalledIrisRuntime.BorrowedPass pass,com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam parameters){
        var pipelineModule=activeLease.pipelineIdentity().getClass().getModule();
        if(net.irisshaders.iris.compat.dh.DHCompat.class.getModule()!=pipelineModule||net.irisshaders.iris.uniforms.CapturedRenderingState.class.getModule()!=pipelineModule)throw new IllegalStateException("Foreign Iris depth provider class");
        activeVisual=distancePublication==null?null:Objects.requireNonNull(distancePublication.get(),"Completed native distance publication required");
        if(activeVisual!=null)activeVisual.requireCurrent();
        float[] projection=new float[16],view=new float[16];double[] range=new double[2];
        glGetUniformfv(pass.programId(),pass.projectionLocation(),projection);glGetUniformfv(pass.programId(),pass.modelViewLocation(),view);glGetDoublev(GL_DEPTH_RANGE,range);
        float irisNear=net.irisshaders.iris.compat.dh.DHCompat.getNearPlane(),irisFar=net.irisshaders.iris.compat.dh.DHCompat.getFarPlane();
        float[] irisProjection=net.irisshaders.iris.compat.dh.DHCompat.getProjection().get(new float[16]);
        float[] irisView=net.irisshaders.iris.uniforms.CapturedRenderingState.INSTANCE.getGbufferModelView().get(new float[16]);
        if(activeVisual==null)depthReceipt=DhShaderDepthContract.verify(parameters.nearClipPlane,parameters.farClipPlane,
                irisNear,irisFar,range,projection,irisProjection,view,irisView);
        else {
            var depth=activeVisual.checkedDepth();
            depthReceipt=DhShaderDepthContract.verifyDistance(depth.selectedDistanceChunks(),depth.effectiveDistanceChunks(),depth.guaranteedNativeRadius(),
                    parameters.nearClipPlane,parameters.farClipPlane,irisNear,irisFar,range,projection,irisProjection,view,irisView);
            requireVisual();
        }
        StrictGlErrors.check("far.depth.uniform.validation");
        if(depthValidatedPasses<Long.MAX_VALUE)depthValidatedPasses++;
    }
    public DhShaderDepthContract.Receipt depthReceipt(){return depthReceipt;}
    public long depthValidatedPasses(){return depthValidatedPasses;}
    public boolean restorationPending(){return pendingUniform!=null||pendingBindings!=null||survivalCharge!=null;}
    /** Must finish before Iris may replace/delete any borrowed pass objects. */
    public void restorePendingState(){owner();if(drawing)throw new IllegalStateException("Cannot mutate active borrowed shader pass");Throwable failure=null;
        try{restoreUniform();}catch(RuntimeException|Error error){failure=error;}
        try{if(pendingBindings!=null){pendingBindings.restore();pendingBindings=null;}}catch(RuntimeException|Error error){failure=append(failure,error);}
        if(failure==null&&survivalCharge!=null){survivalCharge.releaseAfterPhysicalDrain();survivalCharge=null;}
        propagate(failure);
    }
    @Override public void close(){owner();closed=true;restorePendingState();}
    private void restoreUniform(){if(pendingUniform!=null){pendingUniform.close();if(pendingUniform.closed())pendingUniform=null;}}
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Shader far backend owner thread required");}
    private static final DhPageUniformScope.Gl GL=new DhPageUniformScope.Gl(){
        public int currentProgram(){return glGetInteger(GL_CURRENT_PROGRAM);}public int drawFramebuffer(){return glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);}public boolean programExists(int p){return glIsProgram(p);}
        public float[] readOffset(int p,int l){float[] value=new float[3];glGetUniformfv(p,l,value);return value;}
        public void requireNoError(String phase){StrictGlErrors.check(phase);}
        public void useProgram(int p){glUseProgram(p);}public void offset(int l,float x,float y,float z){glUniform3f(l,x,y,z);}
    };
    private static Throwable append(Throwable first,Throwable next){if(first==null)return next;if(first!=next)first.addSuppressed(next);return first;}
    private static void propagate(Throwable failure){if(failure instanceof Error error)throw error;if(failure instanceof RuntimeException error)throw error;}
}
