package io.earthplatform.minecraft.scene;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeShaderCleanupOwnerTest {
    @Test void failedCleanupRemainsOwnedAndBlocksAnotherCaptureUntilRetrySucceeds(){
        var owner=new NativeShaderCleanupOwner();var attempts=new AtomicInteger();
        Runnable restore=owner.capture(()->()->{if(attempts.incrementAndGet()==1)throw new IllegalStateException("partial cleanup");});
        assertTrue(owner.retained());assertThrows(IllegalStateException.class,restore::run);
        assertTrue(owner.retained());assertThrows(IllegalStateException.class,()->owner.capture(()->()->{}));
        owner.retry();assertFalse(owner.retained());assertEquals(2,attempts.get());
        restore.run();assertEquals(2,attempts.get());
    }
    @Test void oldIdempotentClosureCannotReleaseALaterCapture(){
        var owner=new NativeShaderCleanupOwner();var count=new AtomicInteger();
        Runnable first=owner.capture(()->count::incrementAndGet);first.run();
        Runnable second=owner.capture(()->count::incrementAndGet);first.run();
        assertTrue(owner.retained());assertEquals(1,count.get());second.run();assertEquals(2,count.get());
    }
    @Test void captureFailureAndNullRestorationNeverClaimOwnership(){
        var owner=new NativeShaderCleanupOwner();
        assertThrows(IllegalArgumentException.class,()->owner.capture(()->{throw new IllegalArgumentException();}));
        assertFalse(owner.retained());assertThrows(NullPointerException.class,()->owner.capture(()->null));assertFalse(owner.retained());
    }
    @Test void nestedCaptureCannotReplaceTheOriginalOwner(){
        var owner=new NativeShaderCleanupOwner();
        Runnable restore=owner.capture(()->{assertThrows(IllegalStateException.class,()->owner.capture(()->()->{}));return ()->{};});
        assertTrue(owner.retained());restore.run();assertFalse(owner.retained());
    }
    @Test void reentrantRestoreFailsWithoutDroppingPendingCleanup(){
        var owner=new NativeShaderCleanupOwner();var reenter=new AtomicInteger();
        Runnable restore=owner.capture(()->()->{if(reenter.getAndIncrement()==0)owner.retry();});
        assertThrows(IllegalStateException.class,restore::run);assertTrue(owner.retained());owner.retry();assertFalse(owner.retained());
    }
    @Test void wrongThreadCannotReleaseCapturedState()throws Exception{
        var owner=new NativeShaderCleanupOwner();var error=new AtomicReference<Throwable>();
        Runnable restore=owner.capture(()->()->{});
        var thread=new Thread(()->{try{restore.run();}catch(Throwable t){error.set(t);}});thread.start();thread.join();
        assertInstanceOf(IllegalStateException.class,error.get());assertTrue(owner.retained());restore.run();assertFalse(owner.retained());
    }
    @Test void cleanupReducerAttemptsEveryPhaseAndPreservesPrimaryFailure(){
        var cleanup=new NativeIrisState.Cleanup();var order=new StringBuilder();
        var first=new IllegalStateException("framebuffer");var second=new AssertionError("sampler");
        cleanup.run(()->{order.append('a');throw first;});cleanup.run(()->{order.append('b');throw second;});cleanup.run(()->order.append('c'));
        assertSame(first,assertThrows(IllegalStateException.class,cleanup::finish));assertEquals("abc",order.toString());assertArrayEquals(new Throwable[]{second},first.getSuppressed());
    }
    @Test void cleanupReducerAvoidsSelfSuppressionAndAcceptsAllSuccessfulPhases(){
        var error=new AssertionError("same");var cleanup=new NativeIrisState.Cleanup();
        cleanup.run(()->{throw error;});cleanup.run(()->{throw error;});assertSame(error,assertThrows(AssertionError.class,cleanup::finish));assertEquals(0,error.getSuppressed().length);
        var good=new NativeIrisState.Cleanup();good.run(()->{});assertDoesNotThrow(good::finish);
    }
    @Test void boundedErrorProbePreservesCodesAndDistinguishesPreExistingErrors(){
        int[] sequence={0x500,0x502,0};var next=new AtomicInteger();
        var report=NativeIrisState.GlErrors.read(()->sequence[next.getAndIncrement()]);
        assertTrue(report.drained());assertEquals(2,report.count());assertEquals(3,next.get());
        var error=assertThrows(IllegalStateException.class,()->report.requireClear("Pre-existing at capture"));
        assertTrue(error.getMessage().contains("Pre-existing at capture"));assertTrue(error.getMessage().contains("0x500 0x502"));
    }
    @Test void endlessOrContextLostErrorQueueHasAHardReadBound(){
        var calls=new AtomicInteger();
        var report=NativeIrisState.GlErrors.read(()->{calls.incrementAndGet();return 0x507;});
        assertFalse(report.drained());assertEquals(NativeIrisState.GlErrors.MAX_READS,calls.get());
        assertTrue(assertThrows(IllegalStateException.class,()->report.requireClear("restore")).getMessage().contains("remaining queue unknown"));
    }
    @Test void cleanErrorQueueIsAcceptedWithoutExtraReads(){
        var calls=new AtomicInteger();assertDoesNotThrow(()->NativeIrisState.GlErrors.check("capture",()->{calls.incrementAndGet();return 0;}));assertEquals(1,calls.get());
    }
    @Test void silentGlErrorKeepsCleanupOwnedUntilACleanRetry(){
        var owner=new NativeShaderCleanupOwner();var attempt=new AtomicInteger();
        Runnable restore=owner.capture(()->()->{
            var phase=new AtomicInteger();int error=attempt.getAndIncrement()==0?0x502:0;
            NativeIrisState.GlErrors.check("restoration",()->phase.getAndIncrement()==0?error:0);
        });
        assertThrows(IllegalStateException.class,restore::run);assertTrue(owner.retained());owner.retry();assertFalse(owner.retained());assertEquals(2,attempt.get());
    }
    @Test void restorationErrorIsSuppressedBehindTheEarlierCleanupFailure(){
        var cleanup=new NativeIrisState.Cleanup();var primary=new IllegalArgumentException("uniform Java failure");
        cleanup.run(()->{throw primary;});var index=new AtomicInteger();
        cleanup.run(()->NativeIrisState.GlErrors.check("GL errors after restoration",()->index.getAndIncrement()==0?0x501:0));
        assertSame(primary,assertThrows(IllegalArgumentException.class,cleanup::finish));assertEquals(1,primary.getSuppressed().length);
        assertTrue(primary.getSuppressed()[0].getMessage().contains("after restoration"));
    }
    @Test void exactSamplerRestorationPreservesAbsentNullAndObjectIdentityWithoutCloningUnrelatedKeys(){
        var map=new java.util.HashMap<String,Object>();Object original=new Object();map.put("Sampler0",original);map.put("Sampler1",null);map.put("unrelated","before");
        var snapshot=new NativeIrisState.SamplerValues(map);for(int i=0;i<12;i++)map.put("Sampler"+i,new Object());map.put("unrelated","after");
        var cleanup=new NativeIrisState.Cleanup();snapshot.restore(cleanup);cleanup.finish();snapshot.verify();
        assertSame(original,map.get("Sampler0"));assertTrue(map.containsKey("Sampler1"));assertNull(map.get("Sampler1"));
        for(int i=2;i<12;i++)assertFalse(map.containsKey("Sampler"+i));assertEquals("after",map.get("unrelated"));
    }
    @Test void partialSamplerMapFailureAttemptsOtherEntriesAndCanBeRetried(){
        var fail=new java.util.concurrent.atomic.AtomicBoolean(false);
        var map=new java.util.HashMap<String,Object>(){@Override public Object put(String k,Object v){if(k.equals("Sampler0")&&fail.getAndSet(false))throw new IllegalStateException("map restore");return super.put(k,v);}};
        Object marker=new Object();map.put("Sampler0",marker);var snapshot=new NativeIrisState.SamplerValues(map);map.put("Sampler0","changed");map.put("Sampler5","extra");fail.set(true);
        var owner=new NativeShaderCleanupOwner();Runnable restore=owner.capture(()->()->{var cleanup=new NativeIrisState.Cleanup();snapshot.restore(cleanup);cleanup.finish();snapshot.verify();});
        assertThrows(IllegalStateException.class,restore::run);assertTrue(owner.retained());assertFalse(map.containsKey("Sampler5"));owner.retry();assertSame(marker,map.get("Sampler0"));assertFalse(owner.retained());
    }
}
