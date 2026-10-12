package io.earthplatform.minecraft.scene;

import com.mojang.blaze3d.vertex.BufferBuilder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** One adversarial contract batch; no Iris pipeline, model callback, GL context or native draw is started. */
class SceneNativeShaderContractTest {
    private final Object pipeline=new Object(),pack=new Object(),ids=new Object(),types=new Object(),format=new Object();
    private final SceneNativeShaderContract.MaterialContext none=new SceneNativeShaderContract.MaterialContext(){
        public void begin(BufferBuilder b,BlockState s,int x,int y,int z){}
        public void end(BufferBuilder b){}
    };
    private SceneNativeShaderContract.Values values(long epoch) {
        return new SceneNativeShaderContract.Values(true,pipeline,pack,ids,types,format,52,epoch,0,true,false);
    }
    private SceneNativeShaderContract.Capture capture(AtomicReference<SceneNativeShaderContract.Values> live) {
        return new SceneNativeShaderContract.Capture(live.get(),live::get,none);
    }
    @Test void exactPipelineFormatAndMaterialIdentitiesRemainRequired() {
        var original=values(1);
        Object[] same={pipeline,pack,ids,types,format};
        for(int changed=0;changed<same.length;changed++){
            var objects=same.clone();objects[changed]=new Object();
            var current=new SceneNativeShaderContract.Values(true,objects[0],objects[1],objects[2],objects[3],objects[4],52,1,0,true,false);
            var live=new AtomicReference<>(original);var c=capture(live);
            assertTrue(c.isCurrent());live.set(current);assertFalse(c.isCurrent());
            live.set(original);assertFalse(c.isCurrent(),"Observed identity ABA cannot revive old geometry");
        }
    }
    @Test void settingsStrideAndEpochChangesCannotReviveCapture() {
        for(var current:new SceneNativeShaderContract.Values[]{
                new SceneNativeShaderContract.Values(true,pipeline,pack,ids,types,format,32,1,0,true,false),
                new SceneNativeShaderContract.Values(true,pipeline,pack,ids,types,format,52,2,0,true,false),
                new SceneNativeShaderContract.Values(true,pipeline,pack,ids,types,format,52,1,1,true,false),
                new SceneNativeShaderContract.Values(true,pipeline,pack,ids,types,format,52,1,0,false,false),
                new SceneNativeShaderContract.Values(true,pipeline,pack,ids,types,format,52,1,0,true,true)}){
            var live=new AtomicReference<>(values(1));var c=capture(live);live.set(current);assertFalse(c.isCurrent());
            live.set(values(1));assertFalse(c.isCurrent());
        }
    }
    @Test void sameObjectMaterialMutationFailsAndLatches() {
        boolean[] matches={true};
        var material=new SceneNativeShaderContract.MaterialContext(){
            public void begin(BufferBuilder b,BlockState s,int x,int y,int z){}
            public void end(BufferBuilder b){}
            public boolean isCurrent(){return matches[0];}
        };
        var c=new SceneNativeShaderContract.Capture(values(1),()->values(1),material);
        assertTrue(c.isCurrent());matches[0]=false;assertFalse(c.isCurrent());matches[0]=true;assertFalse(c.isCurrent());
    }
    @Test void lifecycleAdvanceInvalidatesEvenSamePipelineAndMapObjects() {
        var lifecycle=new SceneNativeShaderContract.Lifecycle();
        var c=new SceneNativeShaderContract.Capture(values(lifecycle.epoch()),()->values(lifecycle.epoch()),none);
        lifecycle.invalidate();assertEquals(2,lifecycle.epoch());assertFalse(c.isCurrent());
        var next=new SceneNativeShaderContract.Capture(values(lifecycle.epoch()),()->values(lifecycle.epoch()),none);
        assertTrue(next.isCurrent());assertFalse(next.matches(c));
    }
    @Test void ownerCheckPrecedesLiveSourceAndMaterialCallbacks() throws Exception {
        var reads=new AtomicInteger();var failure=new AtomicReference<Throwable>();
        var c=new SceneNativeShaderContract.Capture(values(1),()->{reads.incrementAndGet();return values(1);},none);
        Thread worker=new Thread(()->{try{c.isCurrent();}catch(Throwable e){failure.set(e);}});
        worker.start();worker.join();assertInstanceOf(IllegalStateException.class,failure.get());assertEquals(0,reads.get());
        assertTrue(c.isCurrent());assertEquals(1,reads.get());
    }
    @Test void sourceFaultLatchesAndDoesNotRetryOrHideOriginalFailure() {
        var failure=new IllegalStateException("reload");var reads=new AtomicInteger();
        var c=new SceneNativeShaderContract.Capture(values(1),()->{reads.incrementAndGet();throw failure;},none);
        assertSame(failure,assertThrows(IllegalStateException.class,c::isCurrent));assertFalse(c.isCurrent());assertEquals(1,reads.get());
    }
    @Test void actualFormatAccessorMustAgreeBeforeEmission() {
        var live=new AtomicReference<>(values(1));var c=capture(live);
        assertDoesNotThrow(()->c.requireFormat(format,52));
        assertThrows(IllegalStateException.class,()->c.requireFormat(new Object(),52));
        assertThrows(IllegalStateException.class,()->c.requireFormat(format,32));
    }
    @Test void boundedByteBudgetPermits5041TerrainQuadsAndRejectsTheNextBeforeMutation() {
        var b=new SceneNativeShaderContract.Budget();
        for(int q=0;q<5041;q++){b.preflight(4,52);for(int v=0;v<4;v++)b.reserve(52);}
        assertEquals(20164,b.vertices);assertEquals(1048528,b.bytes);
        assertThrows(IllegalStateException.class,()->b.preflight(4,52));
        assertThrows(IllegalStateException.class,()->b.reserve(52));
        assertEquals(20164,b.vertices);assertEquals(1048528,b.bytes);
    }
    @Test void vanillaBudgetAndMixedLayerBudgetNeverIncrease() {
        var vanilla=new SceneNativeShaderContract.Budget();
        for(int i=0;i<SceneMesh.MAX_VERTICES;i++)vanilla.reserve(32);
        assertEquals(SceneMesh.MAX_VERTEX_BYTES,vanilla.bytes);assertThrows(IllegalStateException.class,()->vanilla.reserve(32));
        var shared=new SceneNativeShaderContract.Budget();
        for(int pass=0;pass<3;pass++)for(int q=0;q<1680;q++)for(int v=0;v<4;v++)shared.reserve(52);
        assertEquals(1048320,shared.bytes);shared.preflight(4,52);for(int v=0;v<4;v++)shared.reserve(52);
        assertThrows(IllegalStateException.class,()->shared.preflight(4,52));
    }
    @Test void malformedCountsAndOverflowCannotChangeAccounting() {
        for(int[] request:new int[][]{{-1,52},{4,0},{Integer.MAX_VALUE,52},{4,Integer.MAX_VALUE}}){
            var b=new SceneNativeShaderContract.Budget();assertThrows(IllegalStateException.class,()->b.preflight(request[0],request[1]));
            assertEquals(0,b.vertices);assertEquals(0,b.bytes);
        }
    }
}
