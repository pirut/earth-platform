package io.earthplatform.compat.dh.shader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Adversarial executions of the actual strict boundary and binding-restoration helper. */
class GlErrorAttributionIndependentTest {
    private static final class Errors implements StrictGlErrors.Gl {
        final ArrayDeque<Integer> queue = new ArrayDeque<>(); int reads, contexts;
        Throwable contextFailure, readFailure; int failReadAt;
        int[] contextOwners;
        Errors(int... codes) { for (int code : codes) queue.add(code); }
        public int error() { reads++; if (failReadAt == 0 || reads == failReadAt) fail(readFailure); return queue.isEmpty() ? 0 : queue.removeFirst(); }
        public StrictGlErrors.Bindings bindings(int vao, int vbo, int ibo) {
            contexts++; contextOwners = new int[]{vao, vbo, ibo}; fail(contextFailure);
            return new StrictGlErrors.Bindings(vao, vbo, ibo, 101, 102, 103, 104, 105, 106, true, false, true, false);
        }
    }
    private static void fail(Throwable failure) {
        if (failure instanceof RuntimeException error) throw error;
        if (failure instanceof Error error) throw error;
    }
    private static StrictGlErrors.Failure check(Errors gl, String phase) {
        return assertThrows(StrictGlErrors.Failure.class, () -> StrictGlErrors.check(gl, phase, 31, 47, 59));
    }

    @Test void noErrorReadsOnceAndDoesNotPerformDiagnosticQueries() {
        var gl = new Errors(0, 0x502); StrictGlErrors.check(gl, "clean.entry", 31, 47, 59);
        assertEquals(1, gl.reads); assertEquals(0, gl.contexts); assertEquals(List.of(0x502), List.copyOf(gl.queue));
    }

    @Test void everyNumericErrorAndPhaseSurvivesWithExactOwnedAndObservedBindings() {
        for (int code : new int[]{0x500, 0x501, 0x502, 0x505, 0x506, 0x507, 0x7654, Integer.MIN_VALUE}) {
            var gl = new Errors(code, 0); String phase = "far.upload.ibo.allocate"; var error = check(gl, phase);
            assertEquals(code, error.errorCode()); assertEquals(phase, error.phase());
            assertTrue(error.getMessage().contains("0x" + Integer.toHexString(code))); assertTrue(error.getMessage().contains(phase));
            assertArrayEquals(new int[]{31, 47, 59}, gl.contextOwners);
            var context = error.bindings(); assertEquals(31, context.ownedVao()); assertEquals(47, context.ownedVbo()); assertEquals(59, context.ownedIbo());
            assertEquals(101, context.program()); assertEquals(102, context.drawFramebuffer()); assertEquals(103, context.readFramebuffer());
            assertEquals(104, context.vao()); assertEquals(105, context.arrayBuffer()); assertEquals(106, context.elementBuffer());
            assertTrue(context.programAlive()); assertFalse(context.vaoAlive()); assertTrue(context.arrayAlive()); assertFalse(context.elementAlive());
            assertEquals(List.of(code), error.errorCodes()); assertTrue(error.errorCollectionExhausted()); assertFalse(error.remainingErrorFlagsUnknown());
            assertEquals(2, gl.reads); assertEquals(1, gl.contexts); assertEquals(0, error.getSuppressed().length);
        }
    }

    @Test void allObservedQueuedErrorsArePreservedAndTheCheckpointStillAlwaysThrows() {
        var gl = new Errors(0x502, 0x501, 0);
        var failure = check(gl, "inherited.entry"); assertEquals(0x502, failure.errorCode());
        assertEquals(List.of(0x502, 0x501), failure.errorCodes()); assertTrue(failure.errorCollectionExhausted());
        assertEquals(3, gl.reads); assertTrue(gl.queue.isEmpty()); assertEquals(1, gl.contexts);
        assertThrows(UnsupportedOperationException.class, () -> failure.errorCodes().add(0x507));
        StrictGlErrors.check(gl, "clean.retry", 0, 0, 0); assertEquals(4, gl.reads); assertEquals(1, gl.contexts);
    }

    @Test void failedDiagnosticQueriesCannotReplaceNumericPrimaryObservedCodesOrExactOwners() {
        for (Throwable detail : List.of(new IllegalArgumentException("query failed"), new AssertionError("query interrupted"))) {
            var gl = new Errors(0x505, 0x507); gl.contextFailure = detail; var error = check(gl, "allocation.failure");
            assertEquals(0x505, error.errorCode()); assertEquals("allocation.failure", error.phase()); assertNull(error.bindings());
            assertArrayEquals(new Throwable[]{detail}, error.getSuppressed()); assertEquals(3, gl.reads); assertEquals(1, gl.contexts);
            assertArrayEquals(new int[]{31, 47, 59}, gl.contextOwners); assertEquals(List.of(0x505, 0x507), error.errorCodes());
            assertEquals(31, error.ownedVao()); assertEquals(47, error.ownedVbo()); assertEquals(59, error.ownedIbo());
            assertTrue(error.errorCollectionExhausted()); assertTrue(error.getMessage().contains("ownedVao=31"));
            assertTrue(error.getMessage().contains("ownedVbo=47")); assertTrue(error.getMessage().contains("ownedIbo=59"));
        }
    }

    @Test void sixteenReadBudgetDistinguishesObservedZeroFromAnUnknownRemainingQueue() {
        for (int nonzero : new int[]{15, 16, 17, 100}) {
            int[] codes = new int[nonzero + 1]; java.util.Arrays.fill(codes, 0, nonzero, 0x507);
            var gl = new Errors(codes); var error = check(gl, "context.lost");
            assertEquals(16, gl.reads); assertEquals(Math.min(nonzero, 16), error.errorCodes().size());
            assertEquals(nonzero == 15, error.errorCollectionExhausted()); assertEquals(nonzero != 15, error.remainingErrorFlagsUnknown());
            assertEquals(nonzero + 1 - 16, gl.queue.size()); assertEquals(0x507, error.errorCode()); assertEquals(1, gl.contexts);
        }
    }

    @Test void interruptedCollectionPreservesTheOriginalGlFailureAndBothSecondaryFailuresInOrder() {
        var gl = new Errors(0x502, 0x501); gl.failReadAt = 2;
        gl.readFailure = new AssertionError("collection failed"); gl.contextFailure = new IllegalArgumentException("context failed");
        var error = check(gl, "interrupted.error.collection");
        assertEquals(0x502, error.errorCode()); assertEquals(List.of(0x502), error.errorCodes());
        assertFalse(error.errorCollectionExhausted()); assertTrue(error.remainingErrorFlagsUnknown());
        assertArrayEquals(new Throwable[]{gl.readFailure, gl.contextFailure}, error.getSuppressed());
        assertEquals(2, gl.reads); assertEquals(List.of(0x501), List.copyOf(gl.queue));
    }

    @Test void oneSecondaryThrowableSeenTwiceIsRetainedOnceWithoutSelfSuppression() {
        var gl = new Errors(0x501); gl.failReadAt = 2; gl.readFailure = new AssertionError("same secondary"); gl.contextFailure = gl.readFailure;
        var error = check(gl, "repeated.secondary"); assertArrayEquals(new Throwable[]{gl.readFailure}, error.getSuppressed());
        assertEquals(0x501, error.errorCode()); assertEquals(2, gl.reads);
    }

    @Test void failedErrorReadPropagatesExactlyAndNeverPretendsThatTheQueueWasClean() {
        for (Throwable problem : List.of(new IllegalStateException("lost context"), new AssertionError("read interrupted"))) {
            var gl = new Errors(0); gl.readFailure = problem;
            assertSame(problem, assertThrows(Throwable.class, () -> StrictGlErrors.check(gl, "entry", 0, 0, 0)));
            assertEquals(1, gl.reads); assertEquals(0, gl.contexts); assertEquals(List.of(0), List.copyOf(gl.queue));
        }
    }

    @Test void invalidPhaseIsRejectedBeforeConsumingAnyGlError() {
        var gl = new Errors(0x502);
        assertThrows(NullPointerException.class, () -> StrictGlErrors.check(gl, null, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> StrictGlErrors.check(gl, "", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> StrictGlErrors.check(gl, "x".repeat(97), 0, 0, 0));
        assertEquals(0, gl.reads); assertEquals(0, gl.contexts);
    }

    private static final class Bindings implements GlBindingRestore.Gl {
        final List<String> events = new ArrayList<>(); int vao = 71, array = 73;
        String failAt; Throwable failure; boolean ignoreVao, ignoreArray;
        void event(String event) { events.add(event); if (event.equals(failAt)) fail(failure); }
        public int vao() { event("read.vao"); return vao; }
        public int array() { event("read.array"); return array; }
        public void bindVao(int id) { if (!ignoreVao) vao = id; event("bind.vao:" + id); }
        public void bindArray(int id) { if (!ignoreArray) array = id; event("bind.array:" + id); }
        public void check(String phase, int capturedVao, int capturedArray) {
            if (phase.startsWith("far.bindings.restore.")) { assertEquals(71, capturedVao); assertEquals(73, capturedArray); }
            event(phase);
        }
    }

    @Test void inheritedCaptureErrorIsRejectedBeforeAnyStateQueryOrMutation() {
        var gl = new Bindings(); gl.failAt = "far.bindings.capture.entry"; gl.failure = new IllegalStateException("inherited 0x502");
        assertSame(gl.failure, assertThrows(IllegalStateException.class, () -> GlBindingRestore.capture(gl)));
        assertEquals(List.of("far.bindings.capture.entry"), gl.events); assertEquals(71, gl.vao); assertEquals(73, gl.array);
    }

    @Test void captureCheckpointsRejectErrorsImmediatelyAndNeverChangeBindings() {
        for (String phase : List.of("far.bindings.capture.vao", "far.bindings.capture.array")) {
            var gl = new Bindings(); gl.failAt = phase; gl.failure = new AssertionError(phase);
            assertSame(gl.failure, assertThrows(AssertionError.class, () -> GlBindingRestore.capture(gl)));
            assertEquals(phase, gl.events.getLast()); assertFalse(gl.events.stream().anyMatch(e -> e.startsWith("bind.")));
            assertEquals(71, gl.vao); assertEquals(73, gl.array);
        }
    }

    @Test void everyRestoreCheckpointAndPartialWriteFailureKeepsTheExactCapturedIdsRetryable() {
        for (boolean asError : List.of(false, true)) for (String fault : List.of(
                "far.bindings.restore.entry", "bind.vao:71", "far.bindings.restore.vao",
                "bind.array:73", "far.bindings.restore.array", "read.vao", "read.array", "far.bindings.restore.verify")) {
            var gl = new Bindings(); var restore = GlBindingRestore.capture(gl); gl.vao = 91; gl.array = 97; gl.events.clear();
            gl.failAt = fault; gl.failure = asError ? new AssertionError(fault) : new IllegalStateException(fault);
            assertSame(gl.failure, assertThrows(Throwable.class, restore::restore)); assertFalse(restore.restored());
            assertEquals(fault, gl.events.getLast());
            gl.failAt = null; gl.vao = 191; gl.array = 197; gl.events.clear(); restore.restore();
            assertEquals(71, gl.vao); assertEquals(73, gl.array); assertTrue(restore.restored());
            assertEquals(List.of("far.bindings.restore.entry", "bind.vao:71", "far.bindings.restore.vao", "bind.array:73",
                    "far.bindings.restore.array", "read.vao", "read.array", "far.bindings.restore.verify"), gl.events);
            int calls = gl.events.size(); restore.restore(); assertEquals(calls, gl.events.size());
        }
    }

    @Test void successfulGlCallsWithMismatchedReadbackStillRetainRestorationOwnership() {
        for (boolean wrongVao : List.of(false, true)) {
            var gl = new Bindings(); var restore = GlBindingRestore.capture(gl); gl.vao = 99; gl.array = 101;
            gl.ignoreVao = wrongVao; gl.ignoreArray = !wrongVao;
            assertThrows(IllegalStateException.class, restore::restore); assertFalse(restore.restored());
            gl.ignoreVao = false; gl.ignoreArray = false; restore.restore();
            assertTrue(restore.restored()); assertEquals(71, gl.vao); assertEquals(73, gl.array);
        }
    }
}
