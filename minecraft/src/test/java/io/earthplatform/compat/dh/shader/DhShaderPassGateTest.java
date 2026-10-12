package io.earthplatform.compat.dh.shader;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DhShaderPassGateTest {
    static final String PAIR="a".repeat(64),NEXT="b".repeat(64);
    static final class Owned implements DhShaderPassGate.Resources {
        boolean valid=true,failClose;int closes;String pair;boolean historical;Runnable onValidate=()->{},onDraw=()->{};
        final EnumSet<DhShaderPassGate.Pass> passes=EnumSet.noneOf(DhShaderPassGate.Pass.class);
        public boolean presentationCurrent(String pair,boolean historical){this.pair=pair;this.historical=historical;onValidate.run();return valid;}
        public void draw(DhShaderPassGate.Pass pass,Object transientContext){assertTrue(passes.add(pass));onDraw.run();}
        public void close(){closes++;if(failClose)throw new IllegalStateException("cleanup");}
    }
    @Test void allEightPassesAreDistinctButEachSubmitsOnceWithinOneActualFrame(){
        var gate=new DhShaderPassGate();var level=new Object();var pipeline=new Object();var owned=new Owned();var lease=gate.acquire(level,pipeline,"world",1,1,4095,owned);
        assertTrue(gate.beginFrame(lease,17,pipeline,1,PAIR,false));
        for(var pass:DhShaderPassGate.Pass.values()){
            assertEquals(DhShaderPassGate.Result.SUBMITTED,gate.submit(lease,level,17,pipeline,1,pass,new Object()));
            assertEquals(DhShaderPassGate.Result.DUPLICATE,gate.submit(lease,level,17,pipeline,1,pass,new Object()));
        }
        assertEquals(12,owned.passes.size());assertEquals(4095,gate.counters().submittedMask());assertEquals(12,gate.counters().duplicates());gate.close();
    }
    @Test void ordinaryLevelAndStaleLeaseDoNotInvalidateCurrentOwner(){
        var gate=new DhShaderPassGate();var level=new Object();var pipeline=new Object();var old=gate.acquire(level,pipeline,"world",1,1,4095,new Owned());gate.close();
        var owned=new Owned();var current=gate.acquire(level,pipeline,"world",2,2,4095,owned);gate.beginFrame(current,50,pipeline,2,PAIR,false);
        var pass=DhShaderPassGate.Pass.MAIN_DH_OPAQUE;
        assertEquals(DhShaderPassGate.Result.PASS_THROUGH,gate.submit(old,new Object(),50,pipeline,2,pass,null));
        assertEquals(DhShaderPassGate.Result.STALE_LEASE,gate.submit(old,level,50,pipeline,2,pass,null));
        assertTrue(gate.current(current));assertFalse(gate.replaceStockBuffers(new Object()));gate.close();
    }
    @Test void changingPairOrHistoricalAuthorityBetweenShadowAndMainFencesFrame(){
        for(boolean history:new boolean[]{false,true}){
            var gate=new DhShaderPassGate();var level=new Object();var pipeline=new Object();var owned=new Owned();var lease=gate.acquire(level,pipeline,"world",1,1,4095,owned);
            gate.beginFrame(lease,7,pipeline,1,PAIR,false);gate.submit(lease,level,7,pipeline,1,DhShaderPassGate.Pass.SHADOW_NATIVE_SOLID,null);
            assertFalse(gate.beginFrame(lease,7,pipeline,1,history?PAIR:NEXT,history));
            assertEquals(DhShaderPassGate.Result.FENCED,gate.submit(lease,level,7,pipeline,1,DhShaderPassGate.Pass.MAIN_DH_OPAQUE,null));assertEquals(1,owned.passes.size());gate.close();
        }
    }
    @Test void loadingFrameHasNoPresentationAndPackExcludedPassCannotSilentlyDraw(){
        var gate=new DhShaderPassGate();var level=new Object();var pipeline=new Object();var owned=new Owned();var lease=gate.acquire(level,pipeline,"world",1,1,DhShaderPassGate.Pass.MAIN_DH_OPAQUE.bit(),owned);
        gate.beginFrame(lease,0,pipeline,1,null,false);assertEquals(DhShaderPassGate.Result.NO_PRESENTATION,gate.submit(lease,level,0,pipeline,1,DhShaderPassGate.Pass.MAIN_DH_OPAQUE,null));
        gate.beginFrame(lease,1,pipeline,1,PAIR,true);assertEquals(DhShaderPassGate.Result.SUBMITTED,gate.submit(lease,level,1,pipeline,1,DhShaderPassGate.Pass.MAIN_DH_OPAQUE,null));assertTrue(owned.historical);
        assertEquals(DhShaderPassGate.Result.FENCED,gate.submit(lease,level,1,pipeline,1,DhShaderPassGate.Pass.SHADOW_DH_OPAQUE,null));gate.close();
    }
    @Test void changedPipelineOrResourceOrMissingFrameNeverDraws(){
        for(int change=0;change<3;change++){
            var gate=new DhShaderPassGate();var level=new Object();var pipeline=new Object();var owned=new Owned();var lease=gate.acquire(level,pipeline,"world",1,1,4095,owned);gate.beginFrame(lease,7,pipeline,1,PAIR,false);
            assertEquals(DhShaderPassGate.Result.FENCED,gate.submit(lease,level,change==0?8:7,change==1?new Object():pipeline,change==2?2:1,DhShaderPassGate.Pass.MAIN_DH_OPAQUE,null));
            assertTrue(owned.passes.isEmpty());assertTrue(gate.replaceStockBuffers(level));gate.close();
        }
    }
    @Test void reentrantInvalidationBeforeDrawAndFailedCleanupRetainSuppression(){
        var gate=new DhShaderPassGate();var level=new Object();var pipeline=new Object();var owned=new Owned();var lease=gate.acquire(level,pipeline,"world",1,1,4095,owned);gate.beginFrame(lease,7,pipeline,1,PAIR,false);
        owned.onValidate=()->gate.invalidate("revoked during validation");assertEquals(DhShaderPassGate.Result.FENCED,gate.submit(lease,level,7,pipeline,1,DhShaderPassGate.Pass.MAIN_DH_OPAQUE,null));assertTrue(owned.passes.isEmpty());
        owned.failClose=true;assertThrows(IllegalStateException.class,gate::close);assertTrue(gate.replaceStockBuffers(level));assertTrue(gate.counters().cleanupPending());
        assertThrows(IllegalStateException.class,()->gate.acquire(new Object(),pipeline,"world",2,2,4095,new Owned()));owned.failClose=false;gate.close();assertFalse(gate.replaceStockBuffers(level));assertEquals(2,owned.closes);
    }
    @Test void partialDrawConsumesStageAndFencesInsteadOfRetrying(){
        var gate=new DhShaderPassGate();var level=new Object();var pipeline=new Object();var owned=new Owned();var lease=gate.acquire(level,pipeline,"world",1,1,4095,owned);gate.beginFrame(lease,1,pipeline,1,PAIR,false);
        var signal=new IllegalStateException("partial draw");owned.onDraw=()->{throw signal;};var pass=DhShaderPassGate.Pass.MAIN_DH_OPAQUE;
        assertSame(signal,assertThrows(IllegalStateException.class,()->gate.submit(lease,level,1,pipeline,1,pass,null)));assertEquals(pass.bit(),gate.counters().submittedMask());
        assertEquals(DhShaderPassGate.Result.FENCED,gate.submit(lease,level,1,pipeline,1,pass,null));gate.close();
    }
}
