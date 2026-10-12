package io.earthplatform.minecraft.coregate;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeDistanceClientTransitionTest {
    private static NativeDistanceClientTransition.Selection value(int selected){return new NativeDistanceClientTransition.Selection(selected,selected,0);}
    @Test void everySettingAndIntermediateChangeRequiresLatestPhysicalDrain(){
        var gate=new NativeDistanceClientTransition(3);
        for(int selected=2;selected<=32;selected++){
            var request=gate.mutation(value(selected));
            assertFalse(gate.permitsPreflight());assertNull(gate.physicallyDrained());
            assertTrue(gate.drain(request.drain()));assertTrue(gate.waitingForDrain());
            var receipt=gate.physicallyDrained();assertEquals(NativeDistanceChangePayload.CLIENT_DRAINED,receipt.kind());
            assertTrue(request.sameSelection(receipt));assertTrue(gate.permitsPreflight());assertNull(gate.physicallyDrained());
        }
    }
    @Test void headAndStableTupleCoalesceButNeverReuseAnOldAbaReceipt(){
        var gate=new NativeDistanceClientTransition(7);
        var head=gate.mutation(value(12));var changed=gate.observe(value(24));
        assertTrue(changed.serial()>head.serial());assertFalse(gate.drain(head.drain()));
        assertTrue(gate.drain(changed.drain()));gate.physicallyDrained();assertTrue(gate.permitsPreflight());
        var aba=gate.mutation(value(24));var finalValue=gate.observe(value(12));
        assertTrue(finalValue.serial()>aba.serial());assertFalse(gate.permitsPreflight());assertFalse(gate.drain(head.drain()));
        assertFalse(gate.drain(changed.drain()));assertTrue(gate.drain(finalValue.drain()));assertNotNull(gate.physicallyDrained());
    }
    @Test void repeatedSetterWithSameObservedTupleStillAdvancesMutationSerial(){
        var gate=new NativeDistanceClientTransition(1);var first=gate.mutation(value(12));
        assertNull(gate.observe(value(12)));var next=gate.mutation(value(12));assertEquals(first.serial()+1,next.serial());
        assertFalse(gate.drain(first.drain()));assertFalse(gate.drain(NativeDistanceChangePayload.request(2,next.serial(),12,12,0).drain()));
    }
    @Test void noAcknowledgmentForWrongKindTupleOrEpoch(){
        var gate=new NativeDistanceClientTransition(10);var request=gate.mutation(value(24));
        assertFalse(gate.drain(request));assertFalse(gate.drain(NativeDistanceChangePayload.request(10,request.serial(),24,12,12).drain()));
        assertNull(gate.physicallyDrained());assertFalse(gate.permitsPreflight());gate.fail();
        assertFalse(gate.drain(request.drain()));assertNull(gate.physicallyDrained());assertThrows(IllegalStateException.class,()->gate.mutation(value(12)));
    }
    @Test void tupleBoundsDoNotInventUnsupportedOrShrunkValues(){
        assertThrows(IllegalArgumentException.class,()->value(1));assertThrows(IllegalArgumentException.class,()->value(33));
        var selected=new NativeDistanceClientTransition.Selection(24,12,12);var gate=new NativeDistanceClientTransition(2);
        var request=gate.mutation(selected);assertEquals(24,request.selected());assertEquals(12,request.effective());assertEquals(12,request.serverLimit());
    }
}
