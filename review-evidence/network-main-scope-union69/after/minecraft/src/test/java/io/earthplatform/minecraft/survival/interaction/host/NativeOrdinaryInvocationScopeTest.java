package io.earthplatform.minecraft.survival.interaction.host;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class NativeOrdinaryInvocationScopeTest {
    @Test void originalScopeReturnsOnce(){var a=new NativeOrdinaryInvocationScope(null);assertTrue(a.returnedFrom(a));assertFalse(a.returnedFrom(a));}
    @Test void nestedReturnRestoresExactOuter(){var a=new NativeOrdinaryInvocationScope(null);var b=new NativeOrdinaryInvocationScope(a);assertEquals(2,b.depth);assertSame(a,b.previous);assertTrue(b.returnedFrom(b));assertTrue(a.returnedFrom(b.previous));}
    @Test void outerCannotReturnThroughLiveInner(){var a=new NativeOrdinaryInvocationScope(null);var b=new NativeOrdinaryInvocationScope(a);assertFalse(a.returnedFrom(b));assertTrue(b.returnedFrom(b));assertTrue(a.returnedFrom(a));}
    @Test void depthIsBoundedBeforeConstruction(){NativeOrdinaryInvocationScope a=null;for(int i=0;i<16;i++)a=new NativeOrdinaryInvocationScope(a);var top=a;assertFalse(NativeOrdinaryInvocationScope.canEnter(top));assertThrows(IllegalStateException.class,()->new NativeOrdinaryInvocationScope(top));assertEquals(16,top.depth);}
    @Test void returnedParentCannotBeReentered(){var a=new NativeOrdinaryInvocationScope(null);assertTrue(a.returnedFrom(a));assertFalse(NativeOrdinaryInvocationScope.canEnter(a));assertThrows(IllegalStateException.class,()->new NativeOrdinaryInvocationScope(a));}
    @Test void staleScopeCannotRetireNewInvocation(){var a=new NativeOrdinaryInvocationScope(null);assertTrue(a.returnedFrom(a));var b=new NativeOrdinaryInvocationScope(null);assertFalse(a.returnedFrom(b));assertTrue(b.returnedFrom(b));}
    @Test void failureFinallyStillRestoresOuter(){var a=new NativeOrdinaryInvocationScope(null);var b=new NativeOrdinaryInvocationScope(a);RuntimeException original=new RuntimeException("original");try{throw original;}catch(RuntimeException observed){assertSame(original,observed);}finally{assertTrue(b.returnedFrom(b));}assertTrue(a.returnedFrom(b.previous));}
    @Test void overflowDoesNotMutateAdmittedStack(){NativeOrdinaryInvocationScope a=null;for(int i=0;i<16;i++)a=new NativeOrdinaryInvocationScope(a);assertFalse(NativeOrdinaryInvocationScope.canEnter(a));int returns=0;while(a!=null){assertTrue(a.returnedFrom(a));a=a.previous;returns++;}assertEquals(16,returns);}
    @Test void unrelatedCurrentDoesNotMutateScope(){var a=new NativeOrdinaryInvocationScope(null);var b=new NativeOrdinaryInvocationScope(null);assertFalse(a.returnedFrom(b));assertTrue(a.returnedFrom(a));assertTrue(b.returnedFrom(b));}
    @Test void nullCurrentCannotRetireScope(){var a=new NativeOrdinaryInvocationScope(null);assertFalse(a.returnedFrom(null));assertTrue(a.returnedFrom(a));}
    @Test void crossThreadCannotEnterOrReturn() throws Throwable {
        var scope=new NativeOrdinaryInvocationScope(null);var failure=new AtomicReference<Throwable>();
        Thread worker=new Thread(()->{try{assertFalse(NativeOrdinaryInvocationScope.canEnter(scope));assertFalse(scope.returnedFrom(scope));}catch(Throwable caught){failure.set(caught);}},"scope-observation-model");
        worker.setDaemon(true);Throwable primary=null;boolean started=false,interrupted=false;
        try{worker.start();started=true;worker.join(2000);assertFalse(worker.isAlive(),"Bounded scope worker did not return");if(failure.get()!=null)throw failure.get();}
        catch(Throwable caught){primary=caught;throw caught;}
        finally{
            if(started){long deadline=System.nanoTime()+2_000_000_000L;while(worker.isAlive()&&System.nanoTime()<deadline){try{worker.join(20);}catch(InterruptedException ignored){interrupted=true;}}}
            try{if(worker.isAlive()){var cleanup=new AssertionError("Scope worker remained alive");if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}}
            finally{if(interrupted||primary instanceof InterruptedException)Thread.currentThread().interrupt();}
        }
        assertTrue(scope.returnedFrom(scope));
    }
}
