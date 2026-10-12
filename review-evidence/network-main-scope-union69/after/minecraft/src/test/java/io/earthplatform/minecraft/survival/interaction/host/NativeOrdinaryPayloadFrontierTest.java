package io.earthplatform.minecraft.survival.interaction.host;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NativeOrdinaryPayloadFrontierTest {
    private final Object wrapper=new Object(),payload=new Object(),context=new Object();
    private final Runnable runnable=()->{};
    private final CompletableFuture<Void> future=new CompletableFuture<>();
    private NativeOrdinaryPayloadFrontier ledger(){return new NativeOrdinaryPayloadFrontier(8);}
    private NativeOrdinaryPayloadFrontier.Ticket source(NativeOrdinaryPayloadFrontier l){return l.beforeSource(wrapper,payload,context);}
    private void enqueue(NativeOrdinaryPayloadFrontier l,NativeOrdinaryPayloadFrontier.Ticket t){l.beforeEnqueue(t,context,runnable);}
    private void callback(NativeOrdinaryPayloadFrontier l,NativeOrdinaryPayloadFrontier.Ticket t){assertSame(t,l.callbackEntered(wrapper,payload,context));l.callbackReturned(t,false);}
    private void returns(NativeOrdinaryPayloadFrontier l,NativeOrdinaryPayloadFrontier.Ticket t){l.enqueueReturned(t,future,false);l.sourceReturned(t,false);}

    @Test void inlineCallbackStillNeedsEnqueueAndSourceReturn(){
        var l=ledger();var t=source(l);enqueue(l,t);callback(l,t);assertFalse(l.originalBodiesReturned());
        l.enqueueReturned(t,future,false);assertFalse(l.originalBodiesReturned());l.sourceReturned(t,false);assertTrue(l.originalBodiesReturned());
        assertFalse(future.isDone()); // Body milestones never require or assert future completion.
    }
    @Test void returnedFutureAndSourceDoNotSettleMissingCallback(){
        var l=ledger();var t=source(l);enqueue(l,t);returns(l,t);assertFalse(l.originalBodiesReturned());assertEquals(1,l.pending());
    }
    @Test void canceledFutureCannotStandInForCallback(){
        var l=ledger();var t=source(l);enqueue(l,t);future.cancel(false);returns(l,t);assertFalse(l.originalBodiesReturned());
    }
    @Test void callbackCanFinishBeforeAsyncEnqueueReturns(){
        var l=ledger();var t=source(l);enqueue(l,t);l.sourceReturned(t,false);callback(l,t);assertFalse(l.originalBodiesReturned());
        l.enqueueReturned(t,future,false);assertTrue(l.originalBodiesReturned());
    }
    @Test void enqueueThenThrowRemainsFailedAfterCallback(){
        var l=ledger();var t=source(l);enqueue(l,t);callback(l,t);l.enqueueReturned(t,null,true);l.sourceReturned(t,true);
        assertEquals(NativeOrdinaryPayloadFrontier.Failure.ENQUEUE,l.failure());assertFalse(l.originalBodiesReturned());
    }
    @Test void callbackFailureSurvivesRecoveredFuture(){
        var l=ledger();var t=source(l);enqueue(l,t);assertSame(t,l.callbackEntered(wrapper,payload,context));l.callbackReturned(t,true);
        future.completeExceptionally(new IllegalStateException("model native callback failure"));
        var recovered=future.exceptionally(failure->null);l.enqueueReturned(t,recovered,false);l.sourceReturned(t,false);
        assertNull(recovered.join());assertEquals(NativeOrdinaryPayloadFrontier.Failure.CALLBACK,l.failure());assertFalse(l.originalBodiesReturned());
    }
    @Test void sourceFailureBeforeEnqueueRemainsIncomplete(){
        var l=ledger();var t=source(l);l.sourceReturned(t,true);assertEquals(NativeOrdinaryPayloadFrontier.Failure.SOURCE,l.failure());assertEquals(1,l.pending());
    }
    @Test void overlappingSameTripleIsRefused(){
        var l=ledger();assertNotNull(source(l));assertNull(source(l));assertEquals(1,l.lineage());assertEquals(NativeOrdinaryPayloadFrontier.Failure.REUSED_TRIPLE,l.failure());
    }
    @Test void completedTripleCannotCreateAStaleCallbackAba(){
        var l=ledger();var old=source(l);enqueue(l,old);callback(l,old);returns(l,old);assertTrue(l.originalBodiesReturned());
        assertNull(source(l));assertEquals(1,l.lineage());assertEquals(NativeOrdinaryPayloadFrontier.Failure.REUSED_TRIPLE,l.failure());
        assertNull(l.callbackEntered(wrapper,payload,context));assertFalse(l.originalBodiesReturned());
    }
    @Test void freshContextCreatesDistinctAdmission(){
        var l=ledger();var first=source(l);enqueue(l,first);callback(l,first);returns(l,first);Object freshContext=new Object();
        var second=l.beforeSource(wrapper,payload,freshContext);assertNotSame(first,second);l.beforeEnqueue(second,freshContext,runnable);
        assertSame(second,l.callbackEntered(wrapper,payload,freshContext));l.callbackReturned(second,false);l.enqueueReturned(second,future,false);l.sourceReturned(second,false);
        assertTrue(l.originalBodiesReturned());assertEquals(2,l.lineage());
    }
    @Test void duplicateCallbackPoisonsWithoutGivingSecondScope(){
        var l=ledger();var t=source(l);enqueue(l,t);assertSame(t,l.callbackEntered(wrapper,payload,context));
        assertNull(l.callbackEntered(wrapper,payload,context));l.callbackReturned(t,false);returns(l,t);
        assertEquals(NativeOrdinaryPayloadFrontier.Failure.DUPLICATE,l.failure());assertFalse(l.originalBodiesReturned());
    }
    @Test void collectedPayloadNeverBecomesAnEmptyFrontier(){
        var l=ledger();var t=source(l);enqueue(l,t);returns(l,t);t.payload.clear();
        assertNull(l.callbackEntered(wrapper,payload,context));assertEquals(NativeOrdinaryPayloadFrontier.Failure.MISSING,l.failure());assertEquals(1,l.pending());
    }
    @Test void wrongContextAndForeignTicketDoNotMutateCurrentTicket(){
        var l=ledger();var t=source(l);l.beforeEnqueue(t,new Object(),runnable);assertFalse(t.enqueueEntered);
        var other=ledger();other.sourceReturned(t,false);assertFalse(t.sourceReturned);assertEquals(NativeOrdinaryPayloadFrontier.Failure.IDENTITY,other.failure());
    }
    @Test void completedHistoryDoesNotRecycleCapacity(){
        var l=new NativeOrdinaryPayloadFrontier(1);var t=source(l);enqueue(l,t);callback(l,t);returns(l,t);
        assertNull(l.beforeSource(wrapper,payload,new Object()));assertEquals(NativeOrdinaryPayloadFrontier.Failure.CAPACITY,l.failure());assertEquals(1,l.lineage());
    }
    @Test void failureDoesNotPreventExistingCallbackBookkeeping(){
        var l=ledger();var t=source(l);enqueue(l,t);l.fail(NativeOrdinaryPayloadFrontier.Failure.SOURCE);callback(l,t);returns(l,t);
        assertTrue(t.callbackReturned);assertEquals(NativeOrdinaryPayloadFrontier.Failure.SOURCE,l.failure());assertFalse(l.originalBodiesReturned());
    }
    @Test void duplicateEnqueueAndSourceReturnFailClosed(){
        var l=ledger();var t=source(l);enqueue(l,t);l.enqueueReturned(t,future,false);l.enqueueReturned(t,future,false);
        assertEquals(NativeOrdinaryPayloadFrontier.Failure.IDENTITY,l.failure());l.sourceReturned(t,false);l.sourceReturned(t,false);assertFalse(l.originalBodiesReturned());
    }
    @Test void realWorkerBlockedCallbackRemainsPendingAfterEnqueueAndSourceReturn() throws Throwable {
        var l=ledger();var t=source(l);enqueue(l,t);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var workerFailure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker=new Thread(()->{
            boolean failed=true;
            try{
                if(l.callbackEntered(wrapper,payload,context)!=t)throw new AssertionError("exact callback ticket missing");
                entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("bounded worker timeout");failed=false;
            }catch(Throwable failure){workerFailure.set(failure);if(failure instanceof InterruptedException)Thread.currentThread().interrupt();}
            finally{l.callbackReturned(t,failed);}
        },"payload-frontier-model");
        worker.setDaemon(true);
        Throwable primary=null;boolean restoreInterrupt=false;
        try{
            worker.start();assertTrue(entered.await(5,TimeUnit.SECONDS));returns(l,t);assertFalse(l.originalBodiesReturned());
        }catch(Throwable failure){primary=failure;restoreInterrupt=failure instanceof InterruptedException;throw failure;}
        finally{
            release.countDown();Throwable cleanup=null;restoreInterrupt|=Thread.interrupted();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(worker.isAlive()){
                long remaining=deadline-System.nanoTime();if(remaining<=0)break;
                try{worker.join(Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)));}
                catch(InterruptedException failure){restoreInterrupt=true;if(cleanup==null)cleanup=failure;}
            }
            if(worker.isAlive()){
                var notJoined=new AssertionError("worker did not return within bounded fixture join");
                if(cleanup==null)cleanup=notJoined;else cleanup.addSuppressed(notJoined);
            }
            if(restoreInterrupt)Thread.currentThread().interrupt();
            if(cleanup!=null){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}
        }
        assertNull(workerFailure.get());assertFalse(worker.isAlive());assertTrue(l.originalBodiesReturned());
    }
}
