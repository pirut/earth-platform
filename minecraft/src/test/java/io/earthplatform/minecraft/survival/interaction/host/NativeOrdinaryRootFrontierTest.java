package io.earthplatform.minecraft.survival.interaction.host;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class NativeOrdinaryRootFrontierTest {
    private final Object connection=new Object(), action=new Object(), channel=new Object(), promise=new Object();
    private NativeOrdinaryRootFrontier ledger(){return new NativeOrdinaryRootFrontier(8);}
    private NativeOrdinaryRootFrontier.Ticket queue(NativeOrdinaryRootFrontier ledger){return ledger.beforePublication(NativeOrdinaryRootFrontier.Kind.QUEUED_CONSUMER,connection,action,null);}
    private void callback(NativeOrdinaryRootFrontier l,NativeOrdinaryRootFrontier.Ticket t){l.callbackEntered(t,connection,action);l.callbackReturned(t,false);}
    private NativeOrdinaryRootFrontier.Ticket config(NativeOrdinaryRootFrontier l){return l.beforePublication(NativeOrdinaryRootFrontier.Kind.INBOUND_CONFIGURATION,connection,action,channel);}
    private void handler(NativeOrdinaryRootFrontier l,NativeOrdinaryRootFrontier.Ticket t){l.handlerEntered(t,channel,action,promise);callback(l,t);l.releaseReturned(t,action,false,false);l.promiseReturned(t,promise,promise,true,false);l.handlerReturned(t,false);}
    @Test void acceptCanReturnBeforeAddButCannotSettleIt(){var l=ledger();var t=queue(l);callback(l,t);l.sourceReturned(t,false);assertFalse(l.observedInvocationsReturned());l.queuedPublicationReturned(t,true,false);assertTrue(l.observedInvocationsReturned());}
    @Test void addThenThrowRetainsFailureEvenAfterCallback(){var l=ledger();var t=queue(l);callback(l,t);l.queuedPublicationReturned(t,true,true);l.sourceReturned(t,false);assertFalse(l.observedInvocationsReturned());assertEquals(NativeOrdinaryRootFrontier.Failure.PUBLICATION,l.failure());}
    @Test void falseAddIsNotACompletion(){var l=ledger();var t=queue(l);l.queuedPublicationReturned(t,false,false);l.sourceReturned(t,false);assertFalse(l.observedInvocationsReturned());assertEquals(1,l.pending());}
    @Test void inlineHasNoQueueMilestoneButStillNeedsSourceReturn(){var l=ledger();var t=l.beforePublication(NativeOrdinaryRootFrontier.Kind.INLINE_CONSUMER,connection,action,null);callback(l,t);assertFalse(l.observedInvocationsReturned());l.sourceReturned(t,false);assertTrue(l.observedInvocationsReturned());}
    @Test void overlappingSameConsumerIsAmbiguous(){var l=ledger();assertNotNull(queue(l));assertNull(queue(l));assertEquals(NativeOrdinaryRootFrontier.Failure.AMBIGUOUS,l.failure());}
    @Test void completedSameConsumerGetsFreshTicket(){var l=ledger();var first=queue(l);callback(l,first);l.queuedPublicationReturned(first,true,false);l.sourceReturned(first,false);var second=queue(l);assertNotSame(first,second);assertEquals(2,l.lineage());callback(l,second);l.queuedPublicationReturned(second,true,false);l.sourceReturned(second,false);assertTrue(l.observedInvocationsReturned());}
    @Test void collectedIdentityDoesNotInventAnEmptyFrontier(){var l=ledger();var t=queue(l);t.payload.clear();assertNull(l.find(connection,action));assertFalse(l.observedInvocationsReturned());assertEquals(1,l.pending());}
    @Test void duplicateAcceptPoisonsWithoutChangingOriginalCallPolicy(){var l=ledger();var t=queue(l);l.callbackEntered(t,connection,action);l.callbackEntered(t,connection,action);assertEquals(NativeOrdinaryRootFrontier.Failure.DUPLICATE,l.failure());assertFalse(l.observedInvocationsReturned());}
    @Test void futureReturnDoesNotSettleDelayedConfiguration(){var l=ledger();var t=config(l);l.configPublicationReturned(t,promise,false);l.sourceReturned(t,false);assertFalse(l.observedInvocationsReturned());handler(l,t);assertTrue(l.observedInvocationsReturned());}
    @Test void promiseSuccessDoesNotReplaceWholeHandlerReturn(){var l=ledger();var t=config(l);l.configPublicationReturned(t,promise,false);l.sourceReturned(t,false);l.handlerEntered(t,channel,action,promise);callback(l,t);l.releaseReturned(t,action,false,false);l.promiseReturned(t,promise,promise,true,false);assertFalse(l.observedInvocationsReturned());l.handlerReturned(t,false);assertTrue(l.observedInvocationsReturned());}
    @Test void configCallbackFailureSticksThroughOriginalCleanup(){var l=ledger();var t=config(l);l.handlerEntered(t,channel,action,promise);l.callbackEntered(t,connection,action);l.callbackReturned(t,true);l.releaseReturned(t,action,false,true);l.handlerReturned(t,true);l.configPublicationReturned(t,promise,false);l.sourceReturned(t,false);assertEquals(NativeOrdinaryRootFrontier.Failure.CALLBACK,l.failure());assertFalse(l.observedInvocationsReturned());}
    @Test void cleanupFailureIsSticky(){var l=ledger();var t=config(l);l.handlerEntered(t,channel,action,promise);callback(l,t);l.releaseReturned(t,action,false,true);assertEquals(NativeOrdinaryRootFrontier.Failure.RELEASE,l.failure());}
    @Test void mismatchedReturnedFutureIsNotConfigurationPromise(){var l=ledger();var t=config(l);handler(l,t);l.configPublicationReturned(t,new Object(),false);l.sourceReturned(t,false);assertEquals(NativeOrdinaryRootFrontier.Failure.PROMISE,l.failure());}
    @Test void preexistingListenersAreAnExplicitIncompleteObservation(){var l=ledger();var t=config(l);l.handlerEntered(t,channel,action,promise);callback(l,t);l.releaseReturned(t,action,false,false);l.promiseReturned(t,promise,promise,false,false);assertEquals(NativeOrdinaryRootFrontier.Failure.LISTENERS,l.failure());}
    @Test void realWorkerBlockedCallbackCannotSettleAfterSourceAndAddReturn() throws Throwable {
        var l=ledger();var t=queue(l);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var workerFailure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker=new Thread(()->{
            l.callbackEntered(t,connection,action);entered.countDown();boolean failed=true;
            try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("bounded worker timeout");failed=false;}
            catch(Throwable failure){workerFailure.set(failure);if(failure instanceof InterruptedException)Thread.currentThread().interrupt();}
            finally{l.callbackReturned(t,failed);}
        },"root-frontier-model");
        worker.setDaemon(true); // Last-resort fixture safety; successful test still requires actual joined return.
        Throwable primary=null;boolean restoreInterrupt=false;
        try{
            worker.start();assertTrue(entered.await(5,TimeUnit.SECONDS));
            l.queuedPublicationReturned(t,true,false);l.sourceReturned(t,false);assertFalse(l.observedInvocationsReturned());
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
                var notJoined=new AssertionError("worker did not return within the bounded fixture join");
                if(cleanup==null)cleanup=notJoined;else cleanup.addSuppressed(notJoined);
            }
            if(restoreInterrupt)Thread.currentThread().interrupt();
            if(cleanup!=null){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}
        }
        assertNull(workerFailure.get());assertFalse(worker.isAlive());assertTrue(l.observedInvocationsReturned());
    }
    @Test void capacitiesCannotBeRecycledByCompletingHistory(){var l=new NativeOrdinaryRootFrontier(1);var t=queue(l);callback(l,t);l.queuedPublicationReturned(t,true,false);l.sourceReturned(t,false);assertNull(queue(l));assertEquals(NativeOrdinaryRootFrontier.Failure.CAPACITY,l.failure());}
}
