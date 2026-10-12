package io.earthplatform.minecraft.coregate;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import io.earthplatform.minecraft.lod.DhLodLayout;
import io.earthplatform.minecraft.lod.RenderSubmissionProbe;
import java.util.List;
import java.util.Objects;
import java.util.function.IntBinaryOperator;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL32.GL_DEPTH_CLAMP;
import static org.lwjgl.opengl.GL30.*;

/**
 * Ordered opaque/cutout depth partitions shared by both actual native and DH renderers.
 * Owns at most one retained state-restore target, and no additional GPU objects. All callbacks
 * belong inside the host's existing exact-pair fence; this helper grants no publication authority.
 */
public final class RollingDepthComposition implements AutoCloseable {
    public static final String VERSION="rolling-depth-halves-v1";
    public static final double NEAR=.05, SPLIT=16, FAR=8192, LEGACY_FAR=2048;
    public record Pass(String name,Matrix4f projection,double depthMin,double depthMax,double nearPlane,double farPlane) {
        public Pass { projection=new Matrix4f(Objects.requireNonNull(projection)); }
        @Override public Matrix4f projection(){return new Matrix4f(projection);}
    }
    @FunctionalInterface public interface PassRenderer { boolean draw(Pass pass); }
    record State(double depthMin,double depthMax,boolean depthWrite,double clearDepth,boolean scissor,
                 int scissorX,int scissorY,int scissorWidth,int scissorHeight,boolean depthClamp,int depthBits) {}
    interface Driver {
        void checkOwner(); State capture(); void clearDepth(); void apply(Pass pass); void restore(State state);
    }
    private final Driver driver;
    private RenderSubmissionProbe submissionProbe;
    private State restoreTarget;
    private boolean active,closed,failed;
    private long frames,aborted,passes,farPasses,nearPasses,legacyPasses,restoreAttempts,restoreFailures;
    private long farNanos,nearNanos,legacyNanos,maxFarNanos,maxNearNanos,maxLegacyNanos,captureClearNanos,restoreNanos,maxFrameNanos;
    private int depthBits;
    public RollingDepthComposition(){this(new GlDriver());}
    RollingDepthComposition(Driver driver){this.driver=Objects.requireNonNull(driver);driver.checkOwner();}

    /** Optional CPU/wall attribution only; install before any draw, without changing GL behavior. */
    public void setSubmissionProbe(RenderSubmissionProbe probe){
        driver.checkOwner();if(active||closed||failed||restoreTarget!=null||frames!=0||passes!=0)throw new IllegalStateException("Install submission probe before drawing");
        submissionProbe=Objects.requireNonNull(probe);
    }

    /** Matrices preserve native x/y/w rows, including affine bob/hurt/nausea effects. Only the depth row changes. */
    public static List<Pass> passes(Matrix4f original,DhLodLayout layout){
        Objects.requireNonNull(original);Objects.requireNonNull(layout);
        float[] values=new float[16];original.get(values);for(float value:values)if(!Float.isFinite(value))throw new IllegalArgumentException("Finite perspective matrix required");
        // Minecraft post-multiplies its perspective by affine bob/hurt/nausea effects.
        // Such matrices need not have w=(0,0,-1,0). Their spatial z row remains a
        // scalar multiple of w; reconstructing from w preserves the complete effect.
        double[] w={original.m03(),original.m13(),original.m23()};
        double[] z={original.m02(),original.m12(),original.m22()};
        int pivot=0;for(int i=1;i<3;i++)if(Math.abs(w[i])>Math.abs(w[pivot]))pivot=i;
        if(Math.abs(w[pivot])<1e-6)throw new IllegalArgumentException("Perspective camera-forward row required");
        double alpha=z[pivot]/w[pivot],offset=original.m32()-alpha*original.m33();
        if(!(alpha>0)||!(offset<0))throw new IllegalArgumentException("Forward native perspective depth required");
        for(int i=0;i<3;i++)if(Math.abs(z[i]-alpha*w[i])>1e-4*(1+Math.abs(z[i])))
            throw new IllegalArgumentException("Oblique-depth projection is unsupported");
        if(layout==DhLodLayout.FIXED_512)return List.of(pass("legacy",original,NEAR,LEGACY_FAR,0,1));
        return List.of(pass("far",original,SPLIT,FAR,.5,1),pass("near",original,NEAR,SPLIT,0,.5));
    }
    private static Pass pass(String name,Matrix4f original,double near,double far,double min,double max){
        double a=(far+near)/(far-near),b=-2*far*near/(far-near);
        var projection=new Matrix4f(original).m02((float)(a*original.m03())).m12((float)(a*original.m13()))
                .m22((float)(a*original.m23())).m32((float)(a*original.m33()+b));
        return new Pass(name,projection,min,max,near,far);
    }
    /**
     * Clears once, draws all geometry in each partition, and restores before returning success.
     * A restore failure retains its exact target for close retry and fences further drawing.
     * Ordinary floating clipping tolerance at the shared16m plane remains a tested limit.
     */
    public boolean draw(Matrix4f original,DhLodLayout layout,PassRenderer renderer){
        driver.checkOwner();Objects.requireNonNull(renderer);
        if(active||closed||failed||restoreTarget!=null)throw new IllegalStateException("Depth composition unavailable or cleanup pending");
        var plan=passes(original,layout);active=true;long frameStart=System.nanoTime();Throwable failure=null;boolean complete=true;
        try{
            long setup=System.nanoTime();long setupProbe=submissionProbe==null?0:submissionProbe.start(RenderSubmissionProbe.Stage.DEPTH_SETUP);
            RollingSubmissionSpan.run(()->{restoreTarget=driver.capture();depthBits=restoreTarget.depthBits();driver.clearDepth();return true;},
                    ()->{captureClearNanos+=System.nanoTime()-setup;if(submissionProbe!=null)submissionProbe.finish(RenderSubmissionProbe.Stage.DEPTH_SETUP,setupProbe);});
            for(var pass:plan){
                driver.apply(pass);long begin=System.nanoTime();boolean accepted;
                try{accepted=renderer.draw(pass);}finally{
                    long elapsed=System.nanoTime()-begin;passes++;
                    switch(pass.name()){
                        case "far"->{farPasses++;farNanos+=elapsed;maxFarNanos=Math.max(maxFarNanos,elapsed);}
                        case "near"->{nearPasses++;nearNanos+=elapsed;maxNearNanos=Math.max(maxNearNanos,elapsed);}
                        default->{legacyPasses++;legacyNanos+=elapsed;maxLegacyNanos=Math.max(maxLegacyNanos,elapsed);}
                    }
                }
                if(!accepted){complete=false;break;}
            }
        }catch(RuntimeException|Error error){failure=error;failed=true;complete=false;}
        finally{
            try{
                try{restore();}catch(RuntimeException|Error error){failed=true;complete=false;if(failure==null)failure=error;else if(failure!=error)failure.addSuppressed(error);}
            }finally{active=false;maxFrameNanos=Math.max(maxFrameNanos,System.nanoTime()-frameStart);}
        }
        if(complete)frames++;else aborted++;
        if(failure instanceof RuntimeException error)throw error;if(failure instanceof Error error)throw error;
        return complete;
    }
    private void restore(){
        if(restoreTarget==null)return;restoreAttempts++;long start=System.nanoTime();
        long token=submissionProbe!=null&&active?submissionProbe.start(RenderSubmissionProbe.Stage.DEPTH_RESTORE):0;
        RollingSubmissionSpan.run(()->{try{driver.restore(restoreTarget);restoreTarget=null;return true;}catch(RuntimeException|Error error){restoreFailures++;throw error;}},
                ()->{restoreNanos+=System.nanoTime()-start;if(submissionProbe!=null&&active)submissionProbe.finish(RenderSubmissionProbe.Stage.DEPTH_RESTORE,token);});
    }
    public JsonObject diagnostics(){
        var j=new JsonObject();j.addProperty("policy",VERSION);j.addProperty("frames",frames);j.addProperty("abortedFrames",aborted);j.addProperty("rendererPasses",passes);
        j.addProperty("farPasses",farPasses);j.addProperty("nearPasses",nearPasses);j.addProperty("legacyPasses",legacyPasses);j.addProperty("depthBits",depthBits);
        j.addProperty("farRendererElapsedNanos",farNanos);j.addProperty("nearRendererElapsedNanos",nearNanos);j.addProperty("legacyRendererElapsedNanos",legacyNanos);
        j.addProperty("maxFarRendererElapsedNanos",maxFarNanos);j.addProperty("maxNearRendererElapsedNanos",maxNearNanos);j.addProperty("maxLegacyRendererElapsedNanos",maxLegacyNanos);
        j.addProperty("captureAndClearElapsedNanos",captureClearNanos);j.addProperty("restoreElapsedNanos",restoreNanos);j.addProperty("maxWholeFrameElapsedNanos",maxFrameNanos);
        j.addProperty("restoreAttempts",restoreAttempts);j.addProperty("restoreFailures",restoreFailures);j.addProperty("restorePending",restoreTarget!=null);j.addProperty("closed",closed);j.addProperty("failed",failed);
        j.addProperty("scope","Owner elapsed submission/state work, not GPU duration. Long layout submits both native and DH twice; unchanged GPU owner count.");return j;
    }
    @Override public void close(){driver.checkOwner();if(active)throw new IllegalStateException("Cannot close during depth callback");failed=true;restore();closed=true;}

    /** Core-profile query: default framebuffer and named FBOs use different attachment enums. */
    static int framebufferDepthBits(int drawFramebuffer,IntBinaryOperator attachmentParameter){
        Objects.requireNonNull(attachmentParameter);
        int attachment=drawFramebuffer==0?GL_DEPTH:GL_DEPTH_ATTACHMENT;
        // A framebuffer without a depth attachment has zero depth bits. Do not issue an
        // attachment-size query for GL_NONE, whose support is otherwise undefined/error-prone.
        if(attachmentParameter.applyAsInt(attachment,GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)==GL_NONE)return 0;
        return attachmentParameter.applyAsInt(attachment,GL_FRAMEBUFFER_ATTACHMENT_DEPTH_SIZE);
    }
    private static final class GlDriver implements Driver {
        public void checkOwner(){RenderSystem.assertOnRenderThread();}
        public State capture(){
            double[] range=new double[2];int[] box=new int[4];glGetDoublev(GL_DEPTH_RANGE,range);glGetIntegerv(GL_SCISSOR_BOX,box);
            return new State(range[0],range[1],glGetBoolean(GL_DEPTH_WRITEMASK),glGetDouble(GL_DEPTH_CLEAR_VALUE),glIsEnabled(GL_SCISSOR_TEST),box[0],box[1],box[2],box[3],glIsEnabled(GL_DEPTH_CLAMP),framebufferDepthBits(glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING),
                    (attachment,pname)->glGetFramebufferAttachmentParameteri(GL_DRAW_FRAMEBUFFER,attachment,pname)));
        }
        public void clearDepth(){
            // Keep Minecraft's state cache and actual GL in agreement, including an inherited
            // raw-driver state change. Depth clamp must be off for projection partition clipping.
            RenderSystem.depthMask(true);glDepthMask(true);RenderSystem.disableScissor();glDisable(GL_SCISSOR_TEST);glDisable(GL_DEPTH_CLAMP);
            RenderSystem.clearDepth(1);glClearDepth(1);RenderSystem.clear(GL_DEPTH_BUFFER_BIT,Minecraft.ON_OSX);
        }
        public void apply(Pass pass){glDepthRange(pass.depthMin(),pass.depthMax());}
        public void restore(State s){
            glDepthRange(s.depthMin(),s.depthMax());RenderSystem.depthMask(s.depthWrite());glDepthMask(s.depthWrite());RenderSystem.clearDepth(s.clearDepth());glClearDepth(s.clearDepth());
            if(s.scissor())RenderSystem.enableScissor(s.scissorX(),s.scissorY(),s.scissorWidth(),s.scissorHeight());else RenderSystem.disableScissor();
            glScissor(s.scissorX(),s.scissorY(),s.scissorWidth(),s.scissorHeight());if(s.scissor())glEnable(GL_SCISSOR_TEST);else glDisable(GL_SCISSOR_TEST);
            if(s.depthClamp())glEnable(GL_DEPTH_CLAMP);else glDisable(GL_DEPTH_CLAMP);
            if(!s.equals(capture()))throw new IllegalStateException("Depth composition state restoration mismatch");
        }
    }
}
