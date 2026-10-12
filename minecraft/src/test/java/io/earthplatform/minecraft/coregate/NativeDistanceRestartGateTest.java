package io.earthplatform.minecraft.coregate;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeDistanceRestartGateTest {
    private static NativeDistanceChangePayload request(long serial,int distance){return NativeDistanceChangePayload.request(7,serial,distance,distance,32);}
    private static NativeDistanceChangePayload receipt(NativeDistanceChangePayload request){return NativeDistanceChangePayload.drained(request.epoch(),request.serial(),request.selected(),request.effective(),request.serverLimit());}
    @Test void everyIncreaseDecreaseAndAbaNeedsLatestPhysicalReceipt(){
        var first=request(1,2);var gate=new NativeDistanceRestartGate(first);assertFalse(gate.ready());
        gate.serverDrained(true);assertFalse(gate.ready());assertTrue(gate.clientDrained(receipt(first)));assertTrue(gate.ready());
        for(int distance:new int[]{12,24,32,12,2,24,12}){
            var old=gate.latest();var next=request(old.serial()+1,distance);
            assertTrue(gate.request(next));assertFalse(gate.ready());assertFalse(gate.clientDrained(receipt(old)));assertFalse(gate.ready());
            assertTrue(gate.clientDrained(receipt(next)));assertTrue(gate.ready());
        }
    }
    @Test void neitherLogicalClosureNorWrongEpochOrTupleReleasesOwnership(){
        var request=request(1,24);var gate=new NativeDistanceRestartGate(request);
        assertTrue(gate.clientDrained(receipt(request)));assertFalse(gate.ready());
        assertFalse(gate.clientDrained(NativeDistanceChangePayload.drained(8,1,24,24,32)));
        assertFalse(gate.clientDrained(NativeDistanceChangePayload.drained(7,1,12,12,32)));
        gate.serverDrained(false);assertFalse(gate.ready());assertTrue(gate.failed());
        gate.serverDrained(true);assertFalse(gate.ready());assertFalse(gate.request(request(2,12)));
    }
    @Test void effectivePendingNeverShrinksSelectedAndCoalescingStaysBounded(){
        var pending=NativeDistanceChangePayload.request(7,1,24,12,12);var gate=new NativeDistanceRestartGate(pending);
        gate.serverDrained(true);gate.clientDrained(receipt(pending));assertFalse(gate.ready());
        for(int i=2;i<1000;i++)assertTrue(gate.request(request(i,2+i%31)));
        var latest=gate.latest();assertEquals(999,latest.serial());assertTrue(gate.clientDrained(receipt(latest)));assertTrue(gate.ready());
    }
    @Test void lateOpeningMutationUsesOnlyExactPredecessorUntilFirstCommit(){
        var fresh=request(8,24);
        assertTrue(NativeDistanceRestartGate.openingEpoch(9,7,7,true,fresh));
        assertFalse(NativeDistanceRestartGate.openingEpoch(9,7,7,false,fresh));
        assertFalse(NativeDistanceRestartGate.openingEpoch(9,7,8,true,fresh));
        assertFalse(NativeDistanceRestartGate.openingEpoch(9,6,7,true,fresh));
        assertFalse(NativeDistanceRestartGate.openingEpoch(9,7,7,true,receipt(fresh)));
        assertTrue(NativeDistanceRestartGate.openingEpoch(7,0,0,false,fresh));
    }
    @Test void invalidRangeAndUnsolicitedKindsCannotStartRestart(){
        assertThrows(IllegalArgumentException.class,()->request(1,1));assertThrows(IllegalArgumentException.class,()->request(1,33));
        assertThrows(IllegalArgumentException.class,()->new NativeDistanceRestartGate(request(1,12).drain()));
        var g=new NativeDistanceRestartGate(request(3,12));assertFalse(g.request(request(3,24)));assertFalse(g.request(request(2,24)));
    }
}
