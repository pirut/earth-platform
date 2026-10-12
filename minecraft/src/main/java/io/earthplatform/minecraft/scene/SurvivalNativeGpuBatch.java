package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.earthplatform.compat.dh.shader.StrictGlErrors;
import io.earthplatform.minecraft.visual.NativeDistanceCpuBudget;
import java.util.Objects;
import net.minecraft.client.Minecraft;

/** Typed actual-hook native submission. Snapshot cleanup is retained and charged before setup;
 * physical pages remain independently owned by their parent even after this adapter is closed. */
public final class SurvivalNativeGpuBatch implements AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private final NativeDistanceCpuBudget budget;
    private NativeDistanceCpuBudget.Reservation charge;
    private Runnable capturedRestore;
    private SceneGlRestoreOwner pending;
    private boolean drawing,failed,closed;
    private long passes,drawCalls,quads;
    public SurvivalNativeGpuBatch(NativeDistanceCpuBudget budget){this.budget=Objects.requireNonNull(budget);}
    public void draw(SurvivalSceneFrameJoin.AcceptedFrame frame){
        owner();if(closed||failed||drawing||pending!=null||charge!=null)throw new IllegalStateException("Native survival adapter is fenced or retains cleanup");
        frame.requireBudget(budget);
        // Existing NativeIrisState bounds: 64 texture/image units, 16 MRT targets, 18x16 uniforms, 12 samplers.
        charge=budget.reserve(NativeDistanceCpuBudget.Pool.OWNER_METADATA,64L<<10);
        drawing=true;Throwable failure=null;SurvivalSceneFrameJoin.NativeDraw invocation=null;
        try{
            invocation=frame.beginNative();
            if(invocation!=null){
                invocation.requireCurrent();StrictGlErrors.check("survival-native.batch.entry");
                capturedRestore=Objects.requireNonNull(invocation.host.captureState());
                pending=new SceneGlRestoreOwner(capturedRestore,()->StrictGlErrors.check("survival-native.restore.before"),()->StrictGlErrors.check("survival-native.restore.after"));
                var shader=invocation.host.shader();invocation.host.setup();RenderSystem.setShader(()->shader);
                if(shader.CHUNK_OFFSET!=null)shader.CHUNK_OFFSET.set(0.0f,0.0f,0.0f);
                StrictGlErrors.check("survival-native.batch.setup");
                for(int i=0;i<invocation.pageCount();i++){
                    invocation.requireCurrent();var page=invocation.page(i);
                    shader.setDefaultUniforms(VertexFormat.Mode.QUADS,invocation.translatedView(i),invocation.projection(),Minecraft.getInstance().getWindow());
                    shader.apply();StrictGlErrors.check("survival-native.batch.apply");
                    var stats=page.drawJoined(invocation,i);drawCalls=Math.addExact(drawCalls,stats.calls());quads=Math.addExact(quads,stats.quads());
                    shader.clear();StrictGlErrors.check("survival-native.batch.clear");
                }
                invocation.requireCurrent();
            }
        }catch(RuntimeException|Error error){failure=error;failed=true;}
        finally{drawing=false;}
        try{restorePendingState();}catch(RuntimeException|Error error){failure=SceneBuildStepper.append(failure,error);failed=true;}
        if(failure==null&&invocation!=null){try{invocation.complete();passes=Math.incrementExact(passes);}catch(RuntimeException|Error error){failure=error;failed=true;}}
        SceneBuildStepper.rethrow(failure);
    }
    public void restorePendingState(){
        owner();if(drawing)throw new IllegalStateException("Cannot retire an operating native survival pass");
        if(pending!=null){pending.restore();if(!pending.pending()){pending=null;capturedRestore=null;}}
        else if(capturedRestore!=null){capturedRestore.run();capturedRestore=null;}
        NativeIrisShaderStages.restorePendingState();
        if(NativeIrisShaderStages.restorationPending())throw new IllegalStateException("Native Iris cleanup is still retained");
        if(charge!=null){charge.releaseAfterPhysicalDrain();charge=null;}
    }
    public boolean restorationPending(){owner();return drawing||pending!=null||capturedRestore!=null||charge!=null||NativeIrisShaderStages.restorationPending();}
    public record Evidence(long passes,long drawCalls,long quads,boolean failed,boolean restorationPending){}
    public Evidence evidence(){owner();return new Evidence(passes,drawCalls,quads,failed,restorationPending());}
    @Override public void close(){owner();closed=true;restorePendingState();}
    private void owner(){if(Thread.currentThread()!=owner)throw new IllegalStateException("Native survival batch requires render owner");RenderSystem.assertOnRenderThread();}
}
