package io.earthplatform.minecraft.scene;

import io.earthplatform.minecraft.visual.NativeVisualBudget;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure cleanup/real-ledger tests. No genuine GL/model/whole-plan residency or drawing claim. */
class SurvivalSceneOwnerRosterTest {
    @Test void partialCleanupRetainsFailedOwnersAndContinuesIndependentDrains() {
        var roster=new SurvivalSceneOwnerRoster<String>(3);
        roster.add("first");roster.add("failed");roster.add("last");
        assertThrows(IllegalStateException.class,()->roster.drain(owner->{
            if(owner.equals("failed"))throw new IllegalStateException("cleanup pending");return true;
        }));
        assertEquals(1,roster.retained());assertNull(roster.get(0));assertEquals("failed",roster.get(1));assertNull(roster.get(2));
        assertThrows(IllegalStateException.class,roster::clearAfterDrain);
        roster.drain(owner->{assertEquals("failed",owner);return true;});roster.clearAfterDrain();assertEquals(0,roster.size());
    }
    @Test void unconfirmedOrAmbiguousCleanupNeverDropsStrongOwner() {
        var roster=new SurvivalSceneOwnerRoster<Object>(1);Object owner=new Object();roster.add(owner);
        assertThrows(IllegalStateException.class,()->roster.drain(value->false));assertSame(owner,roster.get(0));
        assertThrows(AssertionError.class,()->roster.drain(value->{throw new AssertionError("ambiguous deletion");}));
        assertSame(owner,roster.get(0));assertThrows(IllegalStateException.class,()->roster.add(new Object()));
    }
    @Test void currentStagingAndPriorFailuresShareOneRealBudgetUntilPhysicalDrain() {
        var gpu=new NativeVisualBudget();
        var prior=new SurvivalSceneOwnerRoster<NativeVisualBudget.Reservation>(1);
        var staging=new SurvivalSceneOwnerRoster<NativeVisualBudget.Reservation>(1536);
        prior.add(gpu.reserve(32,24));
        for(int i=0;i<1535;i++)staging.add(gpu.reserve(32,24));
        assertEquals(1536,gpu.evidence().pieces());assertThrows(IllegalStateException.class,()->gpu.reserve(32,24));
        assertThrows(IllegalStateException.class,()->prior.drain(value->false));
        staging.drain(value->{value.releaseAfterPhysicalClose();return true;});
        assertEquals(1,gpu.evidence().pieces());assertTrue(gpu.evidence().bytes()>0);assertEquals(1,prior.retained());
        prior.drain(value->{value.releaseAfterPhysicalClose();return true;});assertEquals(0,gpu.evidence().pieces());
    }
    @Test void cleanupRejectsReentrancyAndForeignThreads() throws Exception {
        var roster=new SurvivalSceneOwnerRoster<Object>(1);roster.add(new Object());
        roster.drain(value->{
            assertThrows(IllegalStateException.class,()->roster.drain(nested->true));
            assertThrows(IllegalStateException.class,()->roster.add(new Object()));return true;
        });
        var error=new AtomicReference<Throwable>();Thread worker=new Thread(()->{try{roster.clearAfterDrain();}catch(Throwable failure){error.set(failure);}});
        worker.start();worker.join();assertInstanceOf(IllegalStateException.class,error.get());
        roster.clearAfterDrain();
    }
}
