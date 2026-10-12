package io.earthplatform.minecraft.survival.gametest;
import io.earthplatform.minecraft.survival.interaction.host.NativeEmbeddedTransformMarker;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import java.util.Collection;
import java.util.List;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
/** Small actual class-transform/load-order test; does not qualify a claimed transport or enable ingress. */
@GameTestHolder("earthplatform_output_probe")
public final class NativeEmbeddedTransformGameTests {
    @GameTestGenerator
    public static Collection<TestFunction> tests(){
        if(!Boolean.getBoolean("earthplatform.nativeOutputTransformProbe")||!Boolean.getBoolean("neoforge.gameTestServer"))return List.of();
        return List.of(new TestFunction("nativeOutputTransformProbe","nativeembeddedtransformgametests.original_embedded_task_boundary_is_actually_transformed",
                "earthplatform_output_probe:native_survival_empty",100,0,true,helper->{
                    try{originalEmbeddedTaskBoundaryIsActuallyTransformed(helper);}
                    catch(AssertionError failure){throw new IllegalStateException("Original embedded transform probe failed",failure);}
                }));
    }
    public static void originalEmbeddedTaskBoundaryIsActuallyTransformed(GameTestHelper helper){
        var channel=new EmbeddedChannel();
        Throwable primary=null;
        try {
        if(!(channel instanceof NativeEmbeddedTransformMarker marker))throw new AssertionError("EmbeddedChannel loaded without inert native transform marker");
        long before=marker.earthplatform$originalTaskBoundaryEntries();
        Object message=new Object();var future=channel.write(message);
        if(future.isDone())throw new AssertionError("Original unflushed write unexpectedly completed");
        if(marker.earthplatform$originalTaskBoundaryEntries()<=before)throw new AssertionError("Original write missed its real task boundary");
        long afterWrite=marker.earthplatform$originalTaskBoundaryEntries();channel.flush();
        if(!future.isSuccess()||marker.earthplatform$originalTaskBoundaryEntries()<=afterWrite)throw new AssertionError("Original flush or task boundary missing");
        if(channel.readOutbound()!=message||channel.readOutbound()!=null)throw new AssertionError("Original packet identity/order changed");
        int[] executions={0};channel.eventLoop().execute(()->executions[0]++);channel.runPendingTasks();
        if(executions[0]!=1)throw new AssertionError("Inert marker changed original task execution");
        } catch(RuntimeException|Error failure){primary=failure;throw failure;}
        finally {
            try {channel.finishAndReleaseAll();}
            catch(RuntimeException|Error cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}
        }
        helper.succeed();
    }
}
