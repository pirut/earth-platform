package io.earthplatform.compat.dh.shader;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ShaderLifecycleBarrierTest {
    @Test void bothRestorationsAttemptAndFailuresPropagateBeforeMutation(){
        var gate=new ShaderLifecycleBarrier();var a=new IllegalStateException("native");var b=new AssertionError("far");var calls=new AtomicInteger();
        assertSame(a,assertThrows(IllegalStateException.class,()->gate.restore(()->{calls.incrementAndGet();throw a;},()->{calls.incrementAndGet();throw b;})));
        assertEquals(2,calls.get());assertArrayEquals(new Throwable[]{b},a.getSuppressed());assertFalse(gate.complete());
        gate.restore(calls::incrementAndGet,calls::incrementAndGet);assertTrue(gate.complete());assertEquals(4,calls.get());
        gate.restore(()->fail("completed host replayed"),()->fail("completed far replayed"));
    }
    @Test void successfulOwnerIsNotReplayedWhileOtherOwnerRemains(){
        for(boolean hostFails:new boolean[]{false,true}){
            var gate=new ShaderLifecycleBarrier();var host=new AtomicInteger();var far=new AtomicInteger();
            assertThrows(AssertionError.class,()->gate.restore(()->{host.incrementAndGet();if(hostFails)throw new AssertionError();},()->{far.incrementAndGet();if(!hostFails)throw new AssertionError();}));
            gate.restore(host::incrementAndGet,far::incrementAndGet);assertTrue(gate.complete());assertEquals(hostFails?2:1,host.get());assertEquals(hostFails?1:2,far.get());
        }
    }
    @Test void reentrantMutationIsAbortedAndOwnerRetainedForRetry(){
        var gate=new ShaderLifecycleBarrier();assertThrows(IllegalStateException.class,()->gate.restore(()->gate.restore(()->{},()->{}),()->{}));
        assertFalse(gate.complete());gate.restore(()->{},()->fail("far branch already restored"));assertTrue(gate.complete());
    }
    @Test void repeatedSameThrowableNeverSelfSuppresses(){
        var gate=new ShaderLifecycleBarrier();var error=new AssertionError("one");assertSame(error,assertThrows(AssertionError.class,()->gate.restore(()->{throw error;},()->{throw error;})));assertEquals(0,error.getSuppressed().length);
    }
}
