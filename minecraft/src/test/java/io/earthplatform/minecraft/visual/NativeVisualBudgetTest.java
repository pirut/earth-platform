package io.earthplatform.minecraft.visual;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NativeVisualBudgetTest {
    @Test void exactAllocationsIncludeRoundingBeforeAdmission(){
        var b=new NativeVisualBudget();var r=b.reserve(5041*208,5041*24);
        assertEquals(1<<20,r.vertexAllocationBytes());assertEquals(128*1024,r.indexAllocationBytes());
        assertEquals((1<<20)+128*1024+4096,r.chargedBytes());assertEquals(r.chargedBytes(),b.evidence().bytes());
        r.releaseAfterPhysicalClose();r.releaseAfterPhysicalClose();assertEquals(0,b.evidence().bytes());assertEquals(0,b.evidence().pieces());
    }
    @Test void hardByteCeilingCountsEveryUnreleasedGeneration(){
        var b=new NativeVisualBudget();var held=new ArrayList<NativeVisualBudget.Reservation>();
        while(true){try{held.add(b.reserve(1<<20,128*1024));}catch(IllegalStateException full){break;}}
        assertTrue(b.evidence().bytes()<=1L<<30);long before=b.evidence().bytes();
        assertThrows(IllegalStateException.class,()->b.reserve(1<<20,128*1024));assertEquals(before,b.evidence().bytes());
        // A cancelled or failed-cleanup owner remains held until its real close finishes.
        assertEquals(held.size(),b.evidence().pieces());held.forEach(NativeVisualBudget.Reservation::releaseAfterPhysicalClose);
        assertEquals(0,b.evidence().bytes());
    }
    @Test void hardPieceCeilingAndThreadOwnershipAreIndependent() throws Exception {
        var b=new NativeVisualBudget();var held=new ArrayList<NativeVisualBudget.Reservation>();
        for(int i=0;i<NativeVisualBudget.MAX_PIECES;i++)held.add(b.reserve(208,24));
        assertTrue(b.evidence().bytes()<NativeVisualBudget.MAX_BYTES);
        assertThrows(IllegalStateException.class,()->b.reserve(208,24));
        Throwable[] failure={null};Thread t=new Thread(()->{try{held.getFirst().releaseAfterPhysicalClose();}catch(Throwable e){failure[0]=e;}});t.start();t.join();
        assertInstanceOf(IllegalStateException.class,failure[0]);assertEquals(1536,b.evidence().pieces());
        held.forEach(NativeVisualBudget.Reservation::releaseAfterPhysicalClose);
    }
}
