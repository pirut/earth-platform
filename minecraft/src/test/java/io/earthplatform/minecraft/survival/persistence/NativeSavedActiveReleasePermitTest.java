package io.earthplatform.minecraft.survival.persistence;
import io.earthplatform.minecraft.survival.world.SurvivalRefusal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static io.earthplatform.minecraft.survival.persistence.NativeSurvivalCheckpoint.SessionPhase.ACTIVE;
/** Latch model only. Full native ownership/provenance is checked separately by Host before mint. */
class NativeSavedActiveReleasePermitTest {
    @Test void installedActiveProofCannotConsumeSavedActiveGrantEvenInSamePhase() {
        var latch=new NativeSessionReleasePermit();Object checkpoint=new Object(),receipt=new Object(),gate=new Object();
        Object installed=new Object(),saved=new Object();latch.mint(checkpoint,receipt,gate,ACTIVE,saved);
        assertThrows(SurvivalRefusal.class,()->latch.consume(checkpoint,receipt,gate,ACTIVE,installed));
        assertThrows(SurvivalRefusal.class,()->latch.consume(checkpoint,receipt,gate,ACTIVE,saved));
    }
    @Test void savedActiveGrantCannotBeReplayedOrReassignedToSuccessorGeneration() {
        var latch=new NativeSessionReleasePermit();Object checkpoint=new Object(),receipt=new Object(),gate=new Object(),saved=new Object();
        latch.mint(checkpoint,receipt,gate,ACTIVE,saved);latch.consume(checkpoint,receipt,gate,ACTIVE,saved);
        assertThrows(SurvivalRefusal.class,()->latch.consume(checkpoint,receipt,gate,ACTIVE,saved));
        latch.mint(checkpoint,receipt,gate,ACTIVE,saved);
        assertThrows(SurvivalRefusal.class,()->latch.consume(new Object(),receipt,gate,ACTIVE,saved));
    }
}
