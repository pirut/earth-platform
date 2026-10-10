package io.earthplatform.minecraft.coregate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CoreCollisionHandshakeTest {
    private static CoreGateCollisionPayload frame(long epoch,long revision,long generation,boolean ready){return new CoreGateCollisionPayload(epoch,revision,generation,ready);}
    @Test void initialCurrentPairedFrameOpens(){var h=new CoreCollisionHandshake(1);assertFalse(h.ready());assertTrue(h.accept(frame(1,0,1,true),0,false));assertTrue(h.ready());}
    @Test void oldReadyCannotReopenAfterEditFence(){var h=new CoreCollisionHandshake(1);h.accept(frame(1,0,1,true),0,false);h.invalidate();assertFalse(h.accept(frame(1,0,1,true),0,false));assertFalse(h.ready());assertFalse(h.accept(frame(1,1,2,true),1,true));assertFalse(h.ready());assertTrue(h.accept(frame(1,1,2,true),1,false));assertTrue(h.ready());}
    @Test void oldEpochAndWrongRevisionCannotOpen(){var h=new CoreCollisionHandshake(2);assertFalse(h.accept(frame(1,0,1,true),0,false));assertFalse(h.accept(frame(2,1,1,true),0,false));assertFalse(h.ready());}
    @Test void reloadFencesSameGenerationAndRejectsReadyReplay(){var h=new CoreCollisionHandshake(1);h.accept(frame(1,2,3,true),2,false);assertTrue(h.accept(frame(1,2,3,false),2,false));assertFalse(h.ready());assertFalse(h.accept(frame(1,2,3,true),2,false));assertFalse(h.ready());}
    @Test void oldFalseCannotInvalidateNewCommittedFrame(){var h=new CoreCollisionHandshake(1);h.accept(frame(1,2,3,true),2,false);assertFalse(h.accept(frame(1,1,2,false),2,false));assertTrue(h.ready());}
    @Test void failedEditStillRequiresNewPublicationGeneration(){var h=new CoreCollisionHandshake(1);h.accept(frame(1,2,3,true),2,false);h.invalidate();assertFalse(h.accept(frame(1,2,3,true),2,false));assertTrue(h.accept(frame(1,2,4,true),2,false));assertTrue(h.ready());}
    @Test void invalidWireMetadataRejected(){assertThrows(IllegalArgumentException.class,()->frame(0,0,0,true));assertThrows(IllegalArgumentException.class,()->frame(1,-1,0,true));assertThrows(IllegalArgumentException.class,()->frame(1,0,-1,false));assertDoesNotThrow(()->frame(1,-1,0,false));}
}
