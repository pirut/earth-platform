package io.earthplatform.minecraft.survival.render;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurvivalSceneCleanupOwnershipTest {
    @Test void rejectedTransferPreservesCallerAndSuccessfulTransferNeverReturnsCleanup() {
        var rejected=new SurvivalPageTransferGate();rejected.diagnosticBorrow();
        assertThrows(IllegalStateException.class,()->rejected.transfer(new Object()));
        assertTrue(rejected.callerOwnsCleanup());rejected.beginClose(null);assertTrue(rejected.callerOwnsCleanup());
        var accepted=new SurvivalPageTransferGate();Object uploader=new Object();
        assertTrue(accepted.callerOwnsCleanup());accepted.transfer(uploader);assertFalse(accepted.callerOwnsCleanup());
        accepted.beginClose(uploader);assertFalse(accepted.callerOwnsCleanup());
        assertThrows(IllegalStateException.class,()->accepted.beginClose(null));
        accepted.beginClose(uploader);assertFalse(accepted.callerOwnsCleanup());
    }
    @Test void nullOrClosedTransferRefusalCannotChangeCleanupOwner() {
        var gate=new SurvivalPageTransferGate();
        assertThrows(IllegalStateException.class,()->gate.transfer(null));assertTrue(gate.callerOwnsCleanup());
        gate.beginClose(null);assertThrows(IllegalStateException.class,()->gate.transfer(new Object()));
        assertTrue(gate.callerOwnsCleanup());
    }
}
