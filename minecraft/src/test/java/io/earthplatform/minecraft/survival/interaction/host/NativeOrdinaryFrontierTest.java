package io.earthplatform.minecraft.survival.interaction.host;
import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Pure baseline metadata components. None of these model calls claims a native origin. */
class NativeOrdinaryFrontierTest {
    @Test void callbackReturnBeforeSubmissionDoesNotSettle(){
        var index=new NativeOrdinaryTaskFrontier(1);Object executor=new Object();var ticket=index.reserveBeforeFactory(executor);
        Runnable task=index.envelopeAtOriginalSubmission(ticket,executor,()->{});task.run();
        assertFalse(index.observedPayloadsSettled());index.originalSubmissionReturned(ticket,false);
        assertFalse(index.observedPayloadsSettled());index.originalOriginReturned(ticket);assertTrue(index.observedPayloadsSettled());
    }
    @Test void neverRunTaskNeverSettles(){
        var index=new NativeOrdinaryTaskFrontier(1);Object executor=new Object();var ticket=index.reserveBeforeFactory(executor);
        index.envelopeAtOriginalSubmission(ticket,executor,()->{});index.originalSubmissionReturned(ticket,false);index.originalOriginReturned(ticket);
        assertFalse(index.observedPayloadsSettled());
    }
    @Test void authenticatedNoSubmissionBranchCanSettle(){
        var index=new NativeOrdinaryTaskFrontier(1);var ticket=index.reserveBeforeFactory(new Object());index.originalOriginReturned(ticket);
        assertTrue(index.observedPayloadsSettled());assertEquals(1,index.lineage());
    }
    @Test void unexpectedOriginalFailurePoisonsNoSubmission(){
        var index=new NativeOrdinaryTaskFrontier(1);var ticket=index.reserveBeforeFactory(new Object());index.sourceLost();index.originalOriginReturned(ticket);
        assertFalse(index.observedPayloadsSettled());assertEquals(NativeOrdinaryTaskFrontier.Failure.SOURCE,index.failure());
    }
    @Test void enqueueThenThrowPreservesAcceptedCallback(){
        var index=new NativeOrdinaryTaskFrontier(1);Object executor=new Object();AtomicInteger calls=new AtomicInteger();var ticket=index.reserveBeforeFactory(executor);
        Runnable task=index.envelopeAtOriginalSubmission(ticket,executor,calls::incrementAndGet);index.originalSubmissionReturned(ticket,true);index.originalOriginReturned(ticket);
        task.run();assertEquals(1,calls.get());assertFalse(index.observedPayloadsSettled());
    }
    @Test void exactFailurePropagatesWithoutRetainingCallback()throws Exception{
        var index=new NativeOrdinaryTaskFrontier(1);Object executor=new Object();var ticket=index.reserveBeforeFactory(executor);RuntimeException original=new RuntimeException("native model failure");
        Runnable task=index.envelopeAtOriginalSubmission(ticket,executor,()->{throw original;});index.originalSubmissionReturned(ticket,false);index.originalOriginReturned(ticket);
        assertSame(original,assertThrows(RuntimeException.class,task::run));assertFalse(index.observedPayloadsSettled());
        for(String name:new String[]{"original","ticket"}){Field field=task.getClass().getDeclaredField(name);field.setAccessible(true);assertNull(field.get(task));}
    }
    @Test void acceptedSecondTaskRunsAfterEarlierFailure(){
        var index=new NativeOrdinaryTaskFrontier(2);Object executor=new Object();AtomicInteger calls=new AtomicInteger();
        var a=index.reserveBeforeFactory(executor);var b=index.reserveBeforeFactory(executor);
        Runnable first=index.envelopeAtOriginalSubmission(a,executor,()->{throw new IllegalStateException();});Runnable second=index.envelopeAtOriginalSubmission(b,executor,calls::incrementAndGet);
        index.originalSubmissionReturned(a,false);index.originalOriginReturned(a);index.originalSubmissionReturned(b,false);index.originalOriginReturned(b);
        assertThrows(IllegalStateException.class,first::run);second.run();assertEquals(1,calls.get());assertFalse(index.observedPayloadsSettled());
    }
    @Test void duplicateNeverReplays(){
        var index=new NativeOrdinaryTaskFrontier(1);Object executor=new Object();var ticket=index.reserveBeforeFactory(executor);AtomicInteger calls=new AtomicInteger();
        Runnable task=index.envelopeAtOriginalSubmission(ticket,executor,calls::incrementAndGet);index.originalSubmissionReturned(ticket,false);index.originalOriginReturned(ticket);task.run();
        assertThrows(RuntimeException.class,task::run);assertEquals(1,calls.get());assertFalse(index.observedPayloadsSettled());
    }
    @Test void capacityFailureDoesNotReplaceOrdinaryTask(){
        var index=new NativeOrdinaryTaskFrontier(1);Object executor=new Object();index.reserveBeforeFactory(executor);
        assertNull(index.reserveBeforeFactory(executor));Runnable original=()->{};assertSame(original,index.envelopeAtOriginalSubmission(null,executor,original));assertFalse(index.observedPayloadsSettled());
    }
    @Test void specializedTaskIsPreservedAndRefused(){
        var index=new NativeOrdinaryTaskFrontier(1);Object executor=new Object();var ticket=index.reserveBeforeFactory(executor);
        Runnable original=(Runnable)ForkJoinTask.adapt(()->{});assertSame(original,index.envelopeAtOriginalSubmission(ticket,executor,original));index.originalOriginReturned(ticket);assertFalse(index.observedPayloadsSettled());
    }
    @Test void boundedWorkerBlockedCallbackAndConcurrentDuplicate()throws Exception{
        var index=new NativeOrdinaryTaskFrontier(1);Object executor=new Object();var ticket=index.reserveBeforeFactory(executor);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();AtomicReference<Throwable> workerFailure=new AtomicReference<>();
        Runnable task=index.envelopeAtOriginalSubmission(ticket,executor,()->{calls.incrementAndGet();entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("bounded callback timeout");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new AssertionError(failure);}});
        Thread worker=new Thread(()->{try{task.run();}catch(Throwable failure){workerFailure.set(failure);}},"ordinary-frontier-component");
        Throwable primary=null;
        try{worker.start();assertTrue(entered.await(5,TimeUnit.SECONDS));index.originalSubmissionReturned(ticket,false);index.originalOriginReturned(ticket);
            assertFalse(index.observedPayloadsSettled());assertThrows(RuntimeException.class,task::run);assertEquals(1,calls.get());}
        catch(Throwable failure){primary=failure;throw failure;}
        finally{release.countDown();try{worker.join(5_000);if(worker.isAlive())throw new AssertionError("worker did not return");}catch(Throwable cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}}
        assertNull(workerFailure.get());assertFalse(index.observedPayloadsSettled());
    }
    @Test void immediatePromiseCompletionWaitsForRegistrationReturn(){
        var state=new NativeOrdinaryPromiseFrontier();assertTrue(state.beginCompletion());state.completionReturned(true);
        assertFalse(state.observedSettled());state.registrationReturned(true);assertTrue(state.observedSettled());
    }
    @Test void registrationEnqueueThenThrowStaysFailedAfterCompletion(){
        var state=new NativeOrdinaryPromiseFrontier();state.registrationReturned(false);assertTrue(state.beginCompletion());state.completionReturned(true);
        assertFalse(state.observedSettled());assertTrue(state.failed());
    }
    @Test void promiseHelperReturnIsRequired(){
        var state=new NativeOrdinaryPromiseFrontier();state.registrationReturned(true);assertTrue(state.beginCompletion());assertFalse(state.observedSettled());state.completionReturned(true);assertTrue(state.observedSettled());
    }
    @Test void duplicatePromiseCompletionPoisons(){
        var state=new NativeOrdinaryPromiseFrontier();state.registrationReturned(true);assertTrue(state.beginCompletion());state.completionReturned(true);assertFalse(state.beginCompletion());assertFalse(state.observedSettled());
    }
}
