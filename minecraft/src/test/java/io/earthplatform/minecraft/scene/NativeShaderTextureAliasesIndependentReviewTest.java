package io.earthplatform.minecraft.scene;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Adversarial CPU lifecycle tests. Actual GL deletion and transformed hooks require separate proof. */
class NativeShaderTextureAliasesIndependentReviewTest {
    private NativeShaderTextureAliases.Scope owned;
    private final CompletableFuture<Void> reload = new CompletableFuture<>();

    @AfterEach void releaseReviewScope() {
        reload.complete(null);
        if (owned != null && !owned.closed()) owned.close();
        assertFalse(NativeShaderTextureAliases.active(), "Review must leave no admission behind");
    }

    private NativeShaderTextureAliases.Scope begin(Object level) {
        return owned = NativeShaderTextureAliases.begin(level, () -> {});
    }

    private static int[] slots() { return Arrays.copyOf(new int[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42}, 128); }

    @Test void ordinaryAndUnshadedDeletionNeverLooksAtOrRepairsCpuAliases() {
        int[] raw = slots(), before = raw.clone();
        NativeShaderTextureAliases.beforeDelete(new Object(), raw, 31);
        NativeShaderTextureAliases.beforeDelete(null, raw, new int[]{31, 42});
        NativeShaderTextureAliases.beforeDelete(null, null, 31);
        assertArrayEquals(before, raw);
        assertFalse(NativeShaderTextureAliases.active());
    }

    @Test void noAliasCanBeClearedDuringAdmissionAndFailedAdmissionDoesNotOwnAnything() {
        Object level = new Object(); int[] raw = slots(), before = raw.clone();
        var failure = new AssertionError("carrier verification");
        assertSame(failure, assertThrows(AssertionError.class, () -> NativeShaderTextureAliases.begin(level, () -> {
            assertFalse(NativeShaderTextureAliases.active());
            NativeShaderTextureAliases.beforeDelete(level, raw, 31);
            throw failure;
        })));
        assertArrayEquals(before, raw); assertFalse(NativeShaderTextureAliases.active());
        begin(level); assertTrue(NativeShaderTextureAliases.active());
    }

    @Test void reservationRejectsNestedAdmissionBeforeItsVerifierCanRun() {
        var callbacks = new AtomicInteger(); Object level = new Object();
        owned = NativeShaderTextureAliases.begin(level, () -> {
            assertThrows(IllegalStateException.class,
                    () -> NativeShaderTextureAliases.begin(level, callbacks::incrementAndGet));
            assertFalse(NativeShaderTextureAliases.active());
        });
        assertEquals(0, callbacks.get()); assertTrue(NativeShaderTextureAliases.active());
    }

    @Test void activeScopeRejectsSameAndDifferentLevelOverlapWithoutCallingVerifier() {
        Object level = new Object(); begin(level); var callbacks = new AtomicInteger();
        for (Object other : new Object[]{level, new Object()})
            assertThrows(IllegalStateException.class,
                    () -> NativeShaderTextureAliases.begin(other, callbacks::incrementAndGet));
        assertEquals(0, callbacks.get()); assertFalse(owned.closed());
        int[] raw = slots(); NativeShaderTextureAliases.beforeDelete(level, raw, 31); assertEquals(0, raw[0]);
    }

    @Test void exactIdentityIsRequiredEvenWhenLevelsCompareEqual() {
        record Level(String key) {} Object level = new Level("same"), other = new Level("same");
        assertEquals(level, other); assertNotSame(level, other); begin(level);
        int[] raw = slots(), before = raw.clone();
        NativeShaderTextureAliases.beforeDelete(other, raw, 31);
        NativeShaderTextureAliases.beforeDelete(null, raw, new int[]{31, 42});
        assertArrayEquals(before, raw);
        NativeShaderTextureAliases.beforeDelete(level, raw, 31); assertEquals(0, raw[0]);
    }

    @Test void exactScalarNameClearsEveryAliasIncludingLastSlotAndPreservesAllOthers() {
        Object level = new Object(); begin(level); int[] raw = slots(); raw[0] = raw[5] = raw[11] = 77;
        int[] expected = raw.clone(); expected[0] = expected[5] = expected[11] = 0;
        NativeShaderTextureAliases.beforeDelete(level, raw, 77); assertArrayEquals(expected, raw);
        NativeShaderTextureAliases.beforeDelete(level, raw, 123456); assertArrayEquals(expected, raw);
    }

    @Test void bulkNamesAreReadOnlyAndOnlyExactAliasesAreCleared() {
        Object level = new Object(); begin(level); int[] raw = slots(); raw[2] = 42;
        int[] names = {42, 0, 99999, 31, 42}, originalNames = names.clone(), expected = raw.clone();
        expected[0] = expected[2] = expected[11] = 0;
        NativeShaderTextureAliases.beforeDelete(level, raw, names);
        assertArrayEquals(expected, raw); assertArrayEquals(originalNames, names);
        NativeShaderTextureAliases.beforeDelete(level, raw, new int[0]); assertArrayEquals(expected, raw);
    }

    @Test void zeroAndUnrelatedStaleLookingNamesNeverTriggerSanitization() {
        Object level = new Object(); begin(level);
        int[] raw = Arrays.copyOf(new int[]{0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE, 31, 31, 0, 0, 0, 0, 0, 0}, 128);
        int[] before = raw.clone(); NativeShaderTextureAliases.beforeDelete(level, raw, 0);
        NativeShaderTextureAliases.beforeDelete(level, raw, new int[]{0, 1, 2}); assertArrayEquals(before, raw);
    }

    @Test void GLuintSignBitDoesNotHideAnExactDeletionAndUnrelatedNegativeSlotsArePreserved() {
        Object level = new Object(); begin(level); int[] raw = slots(); raw[2] = raw[11] = Integer.MIN_VALUE;
        raw[5] = -1; int[] expected = raw.clone(); expected[2] = expected[11] = 0;
        NativeShaderTextureAliases.beforeDelete(level, raw, Integer.MIN_VALUE); assertArrayEquals(expected, raw);
        expected[5] = 0; NativeShaderTextureAliases.beforeDelete(level, raw, new int[]{Integer.MIN_VALUE, -1}); assertArrayEquals(expected, raw);
    }

    @Test void noDelayedDeletedNameSetCanClearReusedIdsOnLaterDeleteOrCompletion() {
        Object level = new Object(); begin(level); owned.retainUntil(reload);
        int[] raw = slots(); raw[0] = 77; NativeShaderTextureAliases.beforeDelete(level, raw, 77); assertEquals(0, raw[0]);
        raw[0] = 77; raw[4] = 77; // Reallocated same integer names after the earlier real deletion.
        NativeShaderTextureAliases.beforeDelete(level, raw, 99999);
        assertEquals(77, raw[0]); assertEquals(77, raw[4]);
        reload.complete(null); assertTrue(owned.closed()); assertEquals(77, raw[0]); assertEquals(77, raw[4]);
        NativeShaderTextureAliases.beforeDelete(level, raw, 77); assertEquals(77, raw[0]); assertEquals(77, raw[4]);
    }

    @Test void carrierDisposalDoesNotEndTheAlreadyAdmittedActualReload() {
        Object level = new Object(); var admitted = new AtomicInteger(1); var verifies = new AtomicInteger();
        owned = NativeShaderTextureAliases.begin(level, () -> { assertEquals(1, admitted.get()); verifies.incrementAndGet(); });
        owned.retainUntil(reload); admitted.set(0);
        int[] raw = slots(); NativeShaderTextureAliases.beforeDelete(level, raw, 42);
        assertEquals(0, raw[11]); assertEquals(1, verifies.get()); assertTrue(NativeShaderTextureAliases.active());
    }

    @Test void invalidRawArrayLengthsFailBeforeAnyAliasIsChanged() {
        Object level = new Object(); begin(level);
        for (int length : new int[]{0, 1, 11, 12, 13, 32, 64, 127, 129, 256}) {
            int[] raw = new int[length]; Arrays.fill(raw, 77); int[] before = raw.clone();
            assertThrows(RuntimeException.class, () -> NativeShaderTextureAliases.beforeDelete(level, raw, 77));
            assertArrayEquals(before, raw);
            assertThrows(RuntimeException.class, () -> NativeShaderTextureAliases.beforeDelete(level, raw, new int[]{77}));
            assertArrayEquals(before, raw);
        }
        assertThrows(RuntimeException.class, () -> NativeShaderTextureAliases.beforeDelete(level, null, 77));
    }

    @Test void deletionArgumentCannotBeMutatedEvenIfCallerAliasesTheRawSlotArray() {
        Object level = new Object(); begin(level); int[] raw = slots(), before = raw.clone();
        assertThrows(IllegalStateException.class, () -> NativeShaderTextureAliases.beforeDelete(level, raw, raw));
        assertArrayEquals(before, raw);
        assertThrows(NullPointerException.class, () -> NativeShaderTextureAliases.beforeDelete(level, raw, (int[]) null));
        assertArrayEquals(before, raw);
    }

    @Test void wrongThreadDeletionCannotMutateOrReleaseOwner() throws Exception {
        Object level = new Object(); begin(level); int[] raw = slots(), before = raw.clone();
        for (boolean bulk : new boolean[]{false, true}) {
            var failure = new AtomicReference<Throwable>(); Thread other = new Thread(() -> {
                try {
                    if (bulk) NativeShaderTextureAliases.beforeDelete(level, raw, new int[]{31, 42});
                    else NativeShaderTextureAliases.beforeDelete(level, raw, 31);
                } catch (Throwable problem) { failure.set(problem); }
            }); other.start(); other.join();
            assertInstanceOf(IllegalStateException.class, failure.get()); assertArrayEquals(before, raw);
        }
        assertTrue(NativeShaderTextureAliases.active());
        NativeShaderTextureAliases.beforeDelete(level, raw, 31); assertEquals(0, raw[0]);
    }

    @Test void wrongThreadAbortCannotReleaseTheReservedOwner() throws Exception {
        Object level = new Object(); begin(level); var failure = new AtomicReference<Throwable>();
        Thread other = new Thread(() -> { try { owned.close(); } catch (Throwable problem) { failure.set(problem); } });
        other.start(); other.join(); assertInstanceOf(IllegalStateException.class, failure.get());
        assertFalse(owned.closed()); assertTrue(NativeShaderTextureAliases.active());
    }

    @Test void incompleteExactFutureCannotBeReplacedOrClosedEarly() {
        Object level = new Object(); begin(level); owned.retainUntil(reload);
        assertThrows(IllegalStateException.class, owned::close);
        assertThrows(IllegalStateException.class, () -> owned.retainUntil(new CompletableFuture<>()));
        assertTrue(NativeShaderTextureAliases.active()); assertFalse(reload.isDone());
        reload.complete(null); assertTrue(owned.closed()); assertFalse(NativeShaderTextureAliases.active());
    }

    @Test void successExceptionalAndCancelledFuturesAllTerminateScopeWithoutChangingOutcome() {
        for (int outcome = 0; outcome < 3; outcome++) {
            var exact = new CompletableFuture<Void>(); var failure = new IllegalArgumentException("reload " + outcome);
            begin(new Object()); owned.retainUntil(exact);
            if (outcome == 0) exact.complete(null);
            else if (outcome == 1) exact.completeExceptionally(failure);
            else exact.cancel(false);
            assertTrue(owned.closed()); assertFalse(NativeShaderTextureAliases.active());
            assertEquals(outcome == 0 ? NativeShaderTextureAliases.Outcome.SUCCESS
                    : outcome == 1 ? NativeShaderTextureAliases.Outcome.FAILED : NativeShaderTextureAliases.Outcome.CANCELLED,
                    NativeShaderTextureAliases.evidence().outcome());
            if (outcome == 0) assertNull(exact.join());
            else if (outcome == 1) assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, exact::join).getCause());
            else assertThrows(java.util.concurrent.CancellationException.class, exact::join);
        }
    }

    @Test void alreadyCompletedFutureClosesAtBindingWithoutMakingAnyDeleteLaterEligible() {
        Object level = new Object(); begin(level); int[] raw = slots(), before = raw.clone();
        owned.retainUntil(CompletableFuture.failedFuture(new IllegalStateException("already failed")));
        assertTrue(owned.closed()); NativeShaderTextureAliases.beforeDelete(level, raw, 31); assertArrayEquals(before, raw);
    }

    @Test void failFastReloadWithdrawsAuthorityFromLateTasksAndStaleTokenCannotReviveIt() {
        Object level = new Object(); begin(level); owned.retainUntil(reload);
        int[] raw = slots(); NativeShaderTextureAliases.beforeDelete(level, raw, 31); assertEquals(0, raw[0]);
        var failure = new AssertionError("first resource reload task failed"); reload.completeExceptionally(failure);
        raw[0] = 31; int[] before = raw.clone();
        NativeShaderTextureAliases.beforeDelete(level, raw, new int[]{31, 42});
        assertArrayEquals(before, raw); assertFalse(NativeShaderTextureAliases.active());
        assertThrows(IllegalStateException.class, () -> owned.retainUntil(new CompletableFuture<>()));
        assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, reload::join).getCause());
    }

    @Test void callbackRegistrationFailurePropagatesAndReleasesUnusableScope() {
        Object level = new Object(); begin(level); var failure = new AssertionError("completion registration failed");
        var future = new CompletableFuture<Void>() {
            @Override public CompletableFuture<Void> whenComplete(java.util.function.BiConsumer<? super Void, ? super Throwable> action) {
                throw failure;
            }
        };
        assertSame(failure, assertThrows(AssertionError.class, () -> owned.retainUntil(future)));
        assertFalse(future.isDone()); assertTrue(owned.closed()); assertFalse(NativeShaderTextureAliases.active());
        int[] raw = slots(), before = raw.clone(); NativeShaderTextureAliases.beforeDelete(level, raw, 31); assertArrayEquals(before, raw);
    }

    @Test void completionOnWorkerThreadOnlyReleasesMetadataAndDoesNotRequireGlOwner() throws Exception {
        Object level = new Object(); begin(level); owned.retainUntil(reload); int[] raw = slots(), before = raw.clone();
        Thread other = new Thread(() -> reload.complete(null)); other.start(); other.join();
        assertTrue(owned.closed()); assertFalse(NativeShaderTextureAliases.active()); assertArrayEquals(before, raw);
    }

    @Test void staleScopeCloseAndOldFutureCannotReleaseNewSameLevelAdmission() {
        Object level = new Object(); var firstFuture = new CompletableFuture<Void>();
        var first = begin(level); first.retainUntil(firstFuture); firstFuture.complete(null);
        var second = begin(level); first.close(); firstFuture.complete(null);
        assertFalse(second.closed()); assertTrue(NativeShaderTextureAliases.active());
        int[] raw = slots(); NativeShaderTextureAliases.beforeDelete(level, raw, 42); assertEquals(0, raw[11]);
    }

    @Test void boundedReceiptKeepsExactLastClearedMaskAcrossUnrelatedDeletionWithoutUsingItAsAuthority() {
        Object level = new Object(); begin(level); int[] raw = slots(); raw[0] = raw[11] = -51;
        NativeShaderTextureAliases.beforeDelete(level, raw, -51);
        var clear = NativeShaderTextureAliases.evidence();
        assertTrue(clear.active()); assertEquals(NativeShaderTextureAliases.Outcome.ACTIVE, clear.outcome());
        assertEquals(1, clear.deletionCalls()); assertEquals(2, clear.aliasesCleared());
        assertEquals(-51, clear.lastDeletedName()); assertEquals(-51, clear.lastClearedName()); assertEquals(1 | 1 << 11, clear.lastClearedSlotMaskLo());
        raw[0] = -51; NativeShaderTextureAliases.beforeDelete(level, raw, 999);
        var unmatched = NativeShaderTextureAliases.evidence();
        assertEquals(-51, raw[0]); assertEquals(999, unmatched.lastDeletedName()); assertEquals(-51, unmatched.lastClearedName());
        assertEquals(2, unmatched.deletionCalls()); assertEquals(2, unmatched.aliasesCleared());
        owned.close(); var finished = NativeShaderTextureAliases.evidence();
        assertFalse(finished.active()); assertEquals(NativeShaderTextureAliases.Outcome.ABORTED, finished.outcome());
        assertEquals(unmatched.generation(), finished.generation()); assertEquals(unmatched.aliasesCleared(), finished.aliasesCleared());
    }
}
