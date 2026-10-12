package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.vertex.BufferBuilder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure currentness checks. No Iris initialization, resource bundle, GL or renderer is constructed. */
class SurvivalCaptureRetirementTest {
    private final SceneNativeShaderContract.Values values=new SceneNativeShaderContract.Values(false,null,null,null,null,new Object(),32,1,0,false,false);
    private final SceneNativeShaderContract.MaterialContext materials=new SceneNativeShaderContract.MaterialContext(){
        public void begin(BufferBuilder b,BlockState s,int x,int y,int z){}
        public void end(BufferBuilder b){}
        public long metadataBytes(){return 17;}
    };
    @Test void refusalOrGuardFailureStopsLiveReadsAndCannotRevive(){
        var reads=new AtomicInteger();var current=new AtomicBoolean(true);
        var capture=new SceneNativeShaderContract.Capture(values,()->{reads.incrementAndGet();return values;},materials,current::get);
        assertEquals(145,capture.metadataBytes());assertTrue(capture.isCurrent());assertEquals(1,reads.get());
        current.set(false);assertFalse(capture.isCurrent());assertEquals(1,reads.get());
        current.set(true);assertFalse(capture.isCurrent());assertEquals(1,reads.get());
        var error=new IllegalStateException("retirement check failed");
        var failed=new SceneNativeShaderContract.Capture(values,()->{reads.incrementAndGet();return values;},materials,()->{throw error;});
        assertSame(error,assertThrows(IllegalStateException.class,failed::isCurrent));assertFalse(failed.isCurrent());assertEquals(1,reads.get());
    }
    @Test void retirementDuringLiveReadIsNotAccepted(){
        var current=new AtomicBoolean(true);var reads=new AtomicInteger();
        var capture=new SceneNativeShaderContract.Capture(values,()->{reads.incrementAndGet();current.set(false);return values;},materials,current::get);
        assertFalse(capture.isCurrent());current.set(true);assertFalse(capture.isCurrent());assertEquals(1,reads.get());
    }
    @Test void actualEpochRevocationInvalidatesRetainedCaptureWithoutCheckingBundle()throws Exception{
        var epochField=SurvivalSceneResources.class.getDeclaredField("EPOCH");epochField.setAccessible(true);
        long epoch=((AtomicLong)epochField.get(null)).get();
        var tokenClass=Class.forName("io.earthplatform.minecraft.scene.SurvivalSceneResources$ResourceEpoch");
        var constructor=tokenClass.getDeclaredConstructor(long.class);constructor.setAccessible(true);Object token=constructor.newInstance(epoch);
        var check=tokenClass.getDeclaredMethod("current");check.setAccessible(true);
        BooleanSupplier current=()->{try{return (boolean)check.invoke(token);}catch(ReflectiveOperationException error){throw new AssertionError(error);}};
        var reads=new AtomicInteger();var capture=new SceneNativeShaderContract.Capture(values,()->{reads.incrementAndGet();return values;},materials,current);
        assertTrue(capture.isCurrent());assertEquals(1,reads.get());
        SurvivalSceneResources.invalidateBeforeMutation();
        assertFalse(capture.isCurrent());assertFalse(current.getAsBoolean());assertEquals(1,reads.get());
        SurvivalSceneResources.invalidateBeforeMutation();assertFalse(capture.isCurrent());assertEquals(1,reads.get());
    }
}
