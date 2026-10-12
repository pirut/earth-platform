package io.earthplatform.minecraft.survival.render;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SurvivalPageTransferGateTest {
    @Test void transferRevokesCallerAndRetainedViewsIncludingCallerClose() {
        var gate = new SurvivalPageTransferGate();
        Runnable oldView = () -> gate.requireOpen(null);
        oldView.run();
        Object owner = new Object(); gate.transfer(owner);
        assertThrows(IllegalStateException.class, oldView::run);
        assertThrows(IllegalStateException.class, () -> gate.requireOwner(null));
        assertThrows(IllegalStateException.class, () -> gate.beginClose(null));
        assertThrows(IllegalStateException.class, gate::diagnosticBorrow);
        assertThrows(IllegalStateException.class, () -> gate.transfer(new Object()));
        assertThrows(IllegalStateException.class, () -> gate.requireOpen(new Object()));
        gate.requireOpen(owner); gate.beginClose(owner); gate.beginClose(owner);
        assertThrows(IllegalStateException.class, () -> gate.requireOpen(owner));
        gate.requireOwner(owner); // Failed-close retry and retained-byte accounting remain available.
    }
    @Test void rawDiagnosticBorrowPermanentlyExcludesTransferButKeepsOriginalCloseOwner() {
        var gate = new SurvivalPageTransferGate();
        gate.diagnosticBorrow(); gate.diagnosticBorrow();
        assertThrows(IllegalStateException.class, () -> gate.transfer(new Object()));
        gate.requireOpen(null); gate.beginClose(null);
        assertThrows(IllegalStateException.class, gate::diagnosticBorrow);
        assertThrows(IllegalStateException.class, () -> gate.transfer(new Object()));
    }
    @Test void scopedBorrowRejectsReentrantCloseAndNestedBorrowWithoutPoisoningLiveOwner() {
        var gate = new SurvivalPageTransferGate(); Object owner = new Object(); gate.transfer(owner);
        gate.beginBorrow(owner);
        assertThrows(IllegalStateException.class, () -> gate.beginClose(owner));
        assertThrows(IllegalStateException.class, () -> gate.beginBorrow(owner));
        gate.requireOpen(owner); gate.endBorrow(owner); gate.beginClose(owner);
    }
    @Test void throwingBorrowStillAllowsCleanupOnceItsFinallyEndsTheScope() {
        var gate = new SurvivalPageTransferGate(); Object owner = new Object(); gate.transfer(owner);
        assertThrows(IllegalStateException.class, () -> {
            gate.beginBorrow(owner);
            try { throw new IllegalStateException("upload failure"); }
            finally { gate.endBorrow(owner); }
        });
        gate.beginClose(owner);
    }
    @Test void differentThreadCannotBorrowTransferInspectOrClose() throws Exception {
        var gate = new SurvivalPageTransferGate(); Object owner = new Object(); gate.transfer(owner);
        for (Runnable action : new Runnable[]{() -> gate.requireOwner(owner), () -> gate.beginBorrow(owner),
                () -> gate.beginClose(owner), () -> gate.transfer(new Object())}) {
            var failure = new AtomicReference<Throwable>();
            var thread = new Thread(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
            thread.start(); thread.join(); assertInstanceOf(IllegalStateException.class, failure.get());
        }
        gate.beginClose(owner);
    }
    @Test void closedOwnerCannotTransferAndNullNeverMintsAnOwner() {
        var gate = new SurvivalPageTransferGate();
        assertThrows(IllegalStateException.class, () -> gate.transfer(null));
        gate.requireOpen(null); gate.beginClose(null);
        assertThrows(IllegalStateException.class, () -> gate.transfer(new Object()));
    }
}
