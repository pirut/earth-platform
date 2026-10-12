package io.earthplatform.minecraft.survival.interaction.host;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class NativePhysicalOutputLedgerTest {
    private static NativePhysicalOutputLedger.Cell queued(NativePhysicalOutputLedger ledger,Object packet,Object promise){
        var cell=ledger.reserve(NativePhysicalOutputLedger.Lane.ACTION,packet,new Object());
        ledger.writeEntered(cell,promise,0);ledger.accepted(cell,promise);return cell;
    }
    @Test void originalEnqueuePromiseAndConsumptionAreThreeDifferentStates(){
        var ledger=new NativePhysicalOutputLedger();var packet=new Object();var promise=new Object();var cell=queued(ledger,packet,promise);
        assertFalse(ledger.settled());assertNull(ledger.nextForConsumption());
        ledger.enterFlush(1);ledger.promiseCompleted(cell,promise,true,null);
        assertEquals(NativePhysicalOutputLedger.State.ACCEPTED_ENQUEUE,cell.state());assertNull(ledger.nextForConsumption());
        ledger.exitFlush(1);assertSame(cell,ledger.nextForConsumption());assertFalse(ledger.settled());
        ledger.consumed(cell,packet,promise);assertFalse(ledger.settled());ledger.release(cell);assertTrue(ledger.settled());
    }
    @Test void nativeBoundaryReturnAloneCannotFakePromiseCompletion(){
        var ledger=new NativePhysicalOutputLedger();var promise=new Object();var cell=queued(ledger,new Object(),promise);
        ledger.enterFlush(1);ledger.exitFlush(1);assertNull(ledger.nextForConsumption());assertFalse(ledger.settled());
        ledger.promiseCompleted(cell,promise,true,null);assertSame(cell,ledger.nextForConsumption());
    }
    @Test void actualWriteReturnMustBeTheOriginalUnsafePromise(){
        var ledger=new NativePhysicalOutputLedger();var cell=ledger.reserve(NativePhysicalOutputLedger.Lane.ACTION,new Object(),new Object());
        ledger.writeEntered(cell,new Object(),0);
        assertThrows(SurvivalRefusal.class,()->ledger.accepted(cell,new Object()));assertEquals(1,ledger.pending());
    }
    @Test void immediateWriteAndFlushStillRetainsActualReturnedPromise(){
        var ledger=new NativePhysicalOutputLedger();var packet=new Object();var promise=new Object();var cell=ledger.reserve(NativePhysicalOutputLedger.Lane.ACTION,packet,new Object());
        ledger.enterFlush(1);ledger.writeEntered(cell,promise,1);ledger.promiseCompleted(cell,promise,true,null);
        assertEquals(NativePhysicalOutputLedger.State.RESERVED,cell.state());
        ledger.accepted(cell,promise);ledger.exitFlush(1);assertSame(cell,ledger.nextForConsumption());
    }
    @Test void completionWithoutOriginalFlushProvenanceSticks(){
        var ledger=new NativePhysicalOutputLedger();var promise=new Object();var cell=queued(ledger,new Object(),promise);
        var failure=assertThrows(SurvivalRefusal.class,()->ledger.promiseCompleted(cell,promise,true,null));
        assertSame(failure,assertThrows(SurvivalRefusal.class,ledger::settled));assertEquals(1,ledger.pending());
    }
    @Test void nativeFailureSurvivesCaughtListenerAndCleanup(){
        var ledger=new NativePhysicalOutputLedger();var packet=new Object();var promise=new Object();var cell=queued(ledger,packet,promise);
        var original=new IllegalStateException("original native flush failure");var cleanup=new IllegalStateException("cleanup");
        ledger.enterFlush(1);ledger.promiseCompleted(cell,promise,false,original);ledger.fail(cleanup);
        assertSame(original,ledger.failure());assertArrayEquals(new Throwable[]{cleanup},original.getSuppressed());
        assertSame(original,assertThrows(IllegalStateException.class,()->ledger.release(cell)));assertEquals(1,ledger.pending());
    }
    @Test void independentRootsDoNotConsumeActionCapacity(){
        var ledger=new NativePhysicalOutputLedger();
        for(int i=0;i<8;i++)queued(ledger,new Object(),new Object());
        assertThrows(SurvivalRefusal.class,()->ledger.requireCapacity(NativePhysicalOutputLedger.Lane.ACTION,1));
        ledger.requireCapacity(NativePhysicalOutputLedger.Lane.TIME_SYNC,2);ledger.requireCapacity(NativePhysicalOutputLedger.Lane.KEEPALIVE,2);
        assertThrows(SurvivalRefusal.class,()->ledger.requireCapacity(NativePhysicalOutputLedger.Lane.CHUNK_BATCH,1));
        assertNull(ledger.failure()); // Capacity refusal precedes acceptance and does not destroy existing genuine completion.
    }
    @Test void outOfOrderOrForeignConsumptionCannotReleaseAnything(){
        var ledger=new NativePhysicalOutputLedger();var p1=new Object();var p2=new Object();var f1=new Object();var f2=new Object();
        var one=queued(ledger,p1,f1);var two=queued(ledger,p2,f2);ledger.enterFlush(1);
        ledger.promiseCompleted(one,f1,true,null);ledger.promiseCompleted(two,f2,true,null);ledger.exitFlush(1);
        assertThrows(SurvivalRefusal.class,()->ledger.consumed(two,p2,f2));assertEquals(2,ledger.pending());
    }
    @Test void genuineDrainMakesSameBoundedOwnerReusableForSixtyFourActions(){
        var ledger=new NativePhysicalOutputLedger();
        for(int i=1;i<=64;i++){
            var packet=new Object();var promise=new Object();var cell=queued(ledger,packet,promise);
            ledger.enterFlush(i);ledger.promiseCompleted(cell,promise,true,null);ledger.exitFlush(i);
            ledger.consumed(cell,packet,promise);ledger.release(cell);assertTrue(ledger.settled());
            ledger.requireCapacity(NativePhysicalOutputLedger.Lane.ACTION,8);
        }
    }
    @Test void retiredHandleCannotReleaseLaterConsumedPacketInSameIndex(){
        var ledger=new NativePhysicalOutputLedger();Object oldPacket=new Object(),oldPromise=new Object();
        var old=queued(ledger,oldPacket,oldPromise);ledger.enterFlush(1);ledger.promiseCompleted(old,oldPromise,true,null);
        ledger.exitFlush(1);ledger.consumed(old,oldPacket,oldPromise);ledger.release(old);
        Object packet=new Object(),promise=new Object();var current=queued(ledger,packet,promise);
        assertNotSame(old,current);ledger.enterFlush(2);ledger.promiseCompleted(current,promise,true,null);ledger.exitFlush(2);
        ledger.consumed(current,packet,promise);assertThrows(SurvivalRefusal.class,()->ledger.release(old));
        assertEquals(NativePhysicalOutputLedger.State.CONSUMED,current.state());assertSame(packet,current.packet());assertEquals(1,ledger.pending());
    }

    @Test void zeroFlushReturnCannotPreObserveLaterGenuineBoundary(){
        var ledger=new NativePhysicalOutputLedger();var promise=new Object();var cell=queued(ledger,new Object(),promise);
        var first=assertThrows(SurvivalRefusal.class,()->ledger.exitFlush(0));
        assertEquals(NativePhysicalOutputLedger.State.ACCEPTED_ENQUEUE,cell.state());
        assertSame(first,assertThrows(SurvivalRefusal.class,()->ledger.enterFlush(1)));
        ledger.promiseCompleted(cell,promise,true,null);
        assertEquals(NativePhysicalOutputLedger.State.ACCEPTED_ENQUEUE,cell.state());assertEquals(1,ledger.pending());
        assertSame(first,assertThrows(SurvivalRefusal.class,ledger::settled));
    }

    @Test void fullyDrainedIndependentFlightStillChangesTheHeldLineage(){
        var ledger=new NativePhysicalOutputLedger();long before=ledger.lineage();assertEquals(0,before);
        Object packet=new Object(),promise=new Object();
        var cell=ledger.reserve(NativePhysicalOutputLedger.Lane.TIME_SYNC,packet,new Object());
        assertEquals(before+1,ledger.lineage());ledger.writeEntered(cell,promise,0);ledger.accepted(cell,promise);
        ledger.enterFlush(1);ledger.promiseCompleted(cell,promise,true,null);ledger.exitFlush(1);
        ledger.consumed(cell,packet,promise);ledger.release(cell);
        assertTrue(ledger.settled());assertEquals(before+1,ledger.lineage());
    }

}
