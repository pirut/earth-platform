package io.earthplatform.minecraft.survival.persistence;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SurvivalCheckpointOwnershipTest {
    @Test void clonedImageAndOldReadPeaksFitExistingTwelveMiBEnvelope(){
        assertEquals(12L<<20,SurvivalCheckpointOwnership.CAPACITY);
        for(int old=0;old<=4*1024*1024;old+=512*1024){
            var lease=new SurvivalCheckpointOwnership(512*1024,1024*1024,old,4*1024*1024);
            assertTrue(lease.maximumEncoded()<=4*1024*1024);
            assertTrue(lease.charged()<=SurvivalCheckpointOwnership.CAPACITY);
            assertTrue(lease.charged()>=1536*1024L+SurvivalCheckpointOwnership.CODEC_SCRATCH+lease.maximumEncoded()+2L*old);
        }
    }
    @Test void oldDurableAndNewFrozenPayloadsCannotBorrowWorldOwnership(){
        assertThrows(SurvivalRefusal.class,()->new SurvivalCheckpointOwnership(6L<<20,6L<<20,0,4*1024*1024));
        var small=new SurvivalCheckpointOwnership(1,1,0,4*1024*1024);
        var detached=new SurvivalCheckpointOwnership(1L<<20,1L<<20,0,4*1024*1024);
        assertTrue(detached.maximumEncoded()<small.maximumEncoded());
    }
    @Test void failedCleanupAndRetryKeepTheSameChargeUntilExactAck(){
        var lease=new SurvivalCheckpointOwnership(100000,200000,10000,4*1024*1024);long charged=lease.charged();
        assertThrows(SurvivalRefusal.class,lease::requireRetirable);assertEquals(charged,lease.charged());
        lease.acknowledge();assertEquals(charged,lease.charged());assertDoesNotThrow(lease::requireRetirable);
        assertThrows(SurvivalRefusal.class,lease::acknowledge);
        lease.retire();assertEquals(0,lease.charged());assertThrows(SurvivalRefusal.class,lease::requireRetirable);
    }
    @Test void drainedWriterCompactionKeepsOwnGraphsAndPrechargesExternalTransitionGraphs(){
        var first=new SurvivalCheckpointOwnership(100000,200000,0,4*1024*1024);
        first.acknowledge();first.retireDrainedWriter();assertEquals(300000,first.charged());
        var second=new SurvivalCheckpointOwnership(200000,300000,10000,4*1024*1024,0,first.charged());
        assertTrue(second.charged()<=SurvivalCheckpointOwnership.CAPACITY);
        second.acknowledge();second.retireDrainedWriter();assertEquals(800000,first.charged()+second.charged());
        assertThrows(SurvivalRefusal.class,()->new SurvivalCheckpointOwnership(100000,200000,0,4*1024*1024,0,12L<<20));
        first.requireRetirable();second.requireRetirable();first.retire();second.retire();assertEquals(0,first.charged()+second.charged());
    }

}
