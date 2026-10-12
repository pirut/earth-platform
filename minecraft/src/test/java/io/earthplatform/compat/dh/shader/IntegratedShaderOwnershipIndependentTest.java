package io.earthplatform.compat.dh.shader;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent adversaries for the actual shared gate; no copied renderer model or GL context. */
class IntegratedShaderOwnershipIndependentTest {
    private static final String PAIR = "d".repeat(64);
    private static final DhShaderPassGate.Pass NATIVE = DhShaderPassGate.Pass.MAIN_NATIVE_SOLID;
    private static final DhShaderPassGate.Pass SHADOW = DhShaderPassGate.Pass.SHADOW_NATIVE_SOLID;
    private static final DhShaderPassGate.Pass FAR = DhShaderPassGate.Pass.MAIN_DH_OPAQUE;
    private static final class Fixture implements DhShaderPassGate.Resources {
        final DhShaderPassGate gate = new DhShaderPassGate();
        final Object level = new Object(), pipeline = new Object();
        final List<DhShaderPassGate.Pass> draws = new ArrayList<>();
        final DhShaderPassGate.Lease lease = gate.acquire(level, pipeline, "world", 1, 1,
                NATIVE.bit() | SHADOW.bit() | FAR.bit(), this);
        Runnable validate = () -> {}, draw = () -> {}, close = () -> {};
        int validations, closes;
        public boolean presentationCurrent(String pair, boolean historical) {
            assertEquals(PAIR, pair); validations++; validate.run(); return true;
        }
        public void draw(DhShaderPassGate.Pass pass, Object context) { draws.add(pass); draw.run(); }
        public void close() { closes++; close.run(); }
        boolean frame(long frame, String pair, boolean historical) {
            return gate.beginFrame(lease, frame, pipeline, 1, pair, historical);
        }
        DhShaderPassGate.Result submit(long frame, DhShaderPassGate.Pass pass) {
            return gate.submit(lease, level, frame, pipeline, 1, pass, new Object());
        }
    }

    @Test void allNativeShadowFarOrdersUseOnePairAndDistinctSlots() {
        for (var first : List.of(NATIVE, SHADOW, FAR)) for (var second : List.of(NATIVE, SHADOW, FAR)) {
            if (first == second) continue;
            var f = new Fixture(); assertTrue(f.frame(37, PAIR, false));
            var third = List.of(NATIVE, SHADOW, FAR).stream().filter(p -> p != first && p != second).findFirst().orElseThrow();
            for (var pass : List.of(first, second, third)) {
                assertTrue(f.frame(37, PAIR, false));
                assertEquals(DhShaderPassGate.Result.SUBMITTED, f.submit(37, pass));
                assertEquals(DhShaderPassGate.Result.DUPLICATE, f.submit(37, pass));
            }
            assertEquals(List.of(first, second, third), f.draws); assertEquals(3, f.validations);
            assertEquals(NATIVE.bit() | SHADOW.bit() | FAR.bit(), f.gate.counters().submittedMask());
            f.gate.close(); assertFalse(f.gate.replaceStockBuffers(f.level));
        }
    }

    @Test void anInitiallyEmptyFrameCannotBecomeReadyBetweenShadowAndMain() {
        for (var first : List.of(NATIVE, SHADOW, FAR)) {
            var f = new Fixture(); assertTrue(f.frame(1, null, false));
            assertEquals(DhShaderPassGate.Result.NO_PRESENTATION, f.submit(1, first));
            assertFalse(f.frame(1, PAIR, false));
            assertEquals(DhShaderPassGate.Result.FENCED, f.submit(1, FAR));
            assertTrue(f.draws.isEmpty()); assertEquals(0, f.validations); f.gate.close();
        }
    }

    @Test void sameDigestMayChangeHistoricalAuthorityOnlyAtANewFrameBoundary() {
        var f = new Fixture(); assertTrue(f.frame(1, PAIR, true));
        assertEquals(DhShaderPassGate.Result.SUBMITTED, f.submit(1, SHADOW));
        assertTrue(f.frame(2, PAIR, false));
        assertEquals(DhShaderPassGate.Result.SUBMITTED, f.submit(2, SHADOW));
        assertFalse(f.frame(2, PAIR, true));
        assertFalse(f.frame(3, PAIR, false), "A later valid frame cannot revive a fenced owner");
        assertEquals(List.of(SHADOW, SHADOW), f.draws); f.gate.close();
    }

    @Test void reentrantFrameAdvanceAndReentrantPassAreBothFailClosed() {
        for (boolean advance : List.of(false, true)) {
            var f = new Fixture(); f.frame(1, PAIR, false);
            f.draw = () -> {
                if (advance) assertFalse(f.frame(2, PAIR, false));
                else assertEquals(DhShaderPassGate.Result.FENCED, f.submit(1, FAR));
            };
            assertEquals(DhShaderPassGate.Result.FENCED, f.submit(1, NATIVE));
            assertEquals(List.of(NATIVE), f.draws); assertEquals(NATIVE.bit(), f.gate.counters().submittedMask());
            assertTrue(f.gate.replaceStockBuffers(f.level)); f.gate.close();
        }
    }

    @Test void validationExceptionConsumesNoPassButCannotBeRetried() {
        var f = new Fixture(); f.frame(1, PAIR, false); var fault = new AssertionError("validation interrupted");
        f.validate = () -> { throw fault; };
        assertSame(fault, assertThrows(AssertionError.class, () -> f.submit(1, NATIVE)));
        assertEquals(0, f.gate.counters().submittedMask()); assertTrue(f.draws.isEmpty());
        f.validate = () -> {};
        assertEquals(DhShaderPassGate.Result.FENCED, f.submit(1, NATIVE)); f.gate.close();
    }

    @Test void asynchronousRevocationIsAllowedButSubmissionAndCleanupRemainOwnerThreadOnly() throws Exception {
        var f = new Fixture(); f.frame(1, PAIR, false); var fault = new AtomicReference<Throwable>();
        var worker = new Thread(() -> {
            f.gate.invalidate("resource thread revoked");
            try { f.gate.close(); } catch (Throwable failure) { fault.set(failure); }
        });
        worker.start(); worker.join(); assertInstanceOf(IllegalStateException.class, fault.get());
        assertEquals(0, f.closes); assertTrue(f.gate.replaceStockBuffers(f.level));
        assertEquals(DhShaderPassGate.Result.FENCED, f.submit(1, FAR)); f.gate.close(); assertEquals(1, f.closes);
    }

    @Test void repeatedCleanupFaultKeepsExactCarrierSuppressedUntilSuccessfulRetry() {
        var f = new Fixture(); f.frame(1, PAIR, false);
        f.close = () -> { if (f.closes < 3) throw new IllegalStateException("retained cleanup"); };
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThrows(IllegalStateException.class, f.gate::close);
            assertTrue(f.gate.replaceStockBuffers(f.level)); assertFalse(f.gate.replaceStockBuffers(new Object()));
            assertTrue(f.gate.counters().cleanupPending()); assertFalse(f.gate.current(f.lease));
            assertEquals(DhShaderPassGate.Result.FENCED, f.submit(1, FAR));
        }
        f.gate.close(); assertEquals(3, f.closes); assertFalse(f.gate.replaceStockBuffers(f.level));
        assertFalse(f.gate.counters().cleanupPending()); f.gate.close(); assertEquals(3, f.closes);
    }
}
