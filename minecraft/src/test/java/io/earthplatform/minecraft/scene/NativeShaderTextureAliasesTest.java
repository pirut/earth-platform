package io.earthplatform.minecraft.scene;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeShaderTextureAliasesTest {
    @AfterEach void noScopeLeaks() { assertFalse(NativeShaderTextureAliases.active()); }

    private static int[] padded(int... lowSlots) { return Arrays.copyOf(lowSlots, 128); }

    private static int[] slots(int value) {
        int[] slots = new int[NativeShaderTextureAliases.SLOT_COUNT];
        Arrays.fill(slots, value);
        return slots;
    }

    @Test void noScopeAndDifferentExactLevelLeaveAllAliasesAlone() {
        Object level = new Object();
        int[] slots = slots(13);
        NativeShaderTextureAliases.beforeDelete(level, slots, 13);
        assertArrayEquals(slots(13), slots);
        try (var scope = NativeShaderTextureAliases.begin(level, () -> {})) {
            NativeShaderTextureAliases.beforeDelete(new Object(), slots, 13);
            NativeShaderTextureAliases.beforeDelete(null, slots, new int[]{13});
            assertArrayEquals(slots(13), slots);
            assertEquals(0, NativeShaderTextureAliases.evidence().deletionCalls());
        }
    }

    @Test void positiveExactMatchesWithinPinnedDomainClearSynchronouslyAndOtherValuesSurvive() {
        Object level = new Object();
        int[] aliases = padded(23, 40, -1, 0, 41, 23, 42, 43, 44, 45, 46, 23);
        try (var scope = NativeShaderTextureAliases.begin(level, () -> {})) {
            NativeShaderTextureAliases.beforeDelete(level, aliases, 23);
            assertArrayEquals(padded(0, 40, -1, 0, 41, 0, 42, 43, 44, 45, 46, 0), aliases);
            var evidence = NativeShaderTextureAliases.evidence();
            assertTrue(evidence.active());
            assertEquals(1, evidence.deletionCalls());
            assertEquals(3, evidence.aliasesCleared());
            assertEquals(23, evidence.lastDeletedName());
            assertEquals((1 << 0) | (1 << 5) | (1 << 11), evidence.lastClearedSlotMaskLo());
        }
        assertFalse(NativeShaderTextureAliases.evidence().active());
        assertEquals(3, NativeShaderTextureAliases.evidence().aliasesCleared());
    }

    @Test void zeroAndAbsentNamesAreNotInvalidIdFilters() {
        Object level = new Object();
        int[] aliases = padded(0, -1, -42, 700, 701, 702, 703, 704, 705, 706, 707, 708);
        int[] original = aliases.clone();
        try (var scope = NativeShaderTextureAliases.begin(level, () -> {})) {
            for (int deleted : new int[]{0, -2, -43, Integer.MIN_VALUE, 987})
                NativeShaderTextureAliases.beforeDelete(level, aliases, deleted);
            assertArrayEquals(original, aliases);
            assertEquals(0, NativeShaderTextureAliases.evidence().aliasesCleared());
        }
    }

    @Test void arrayDeletionClearsOnlyExactNonzeroNamesAndDoesNotMutateItsInput() {
        Object level = new Object();
        int[] aliases = padded(6, 7, 8, -1, 0, 9, 6, 10, 11, 12, 13, 7);
        int[] names = {0, -1, 6, 6, 200, 7};
        int[] originalNames = names.clone();
        try (var scope = NativeShaderTextureAliases.begin(level, () -> {})) {
            NativeShaderTextureAliases.beforeDelete(level, aliases, names);
            assertArrayEquals(padded(0, 0, 8, 0, 0, 9, 0, 10, 11, 12, 13, 0), aliases);
            assertArrayEquals(originalNames, names);
            assertEquals(1, NativeShaderTextureAliases.evidence().deletionCalls());
            assertEquals(5, NativeShaderTextureAliases.evidence().aliasesCleared());
            assertEquals(7, NativeShaderTextureAliases.evidence().lastDeletedName());
            assertEquals((1 << 1) | (1 << 11), NativeShaderTextureAliases.evidence().lastClearedSlotMaskLo());
            NativeShaderTextureAliases.beforeDelete(level, aliases, new int[0]);
            assertEquals(2, NativeShaderTextureAliases.evidence().deletionCalls());
        }
    }

    @Test void unsignedGluintNamesPreserveTheirNegativeJavaBitPattern() {
        Object level = new Object();
        int[] aliases = padded(-1, -2, Integer.MIN_VALUE, 0, 42, -1, -3, 43, 44, 45, 46, 47);
        try (var scope = NativeShaderTextureAliases.begin(level, () -> {})) {
            NativeShaderTextureAliases.beforeDelete(level, aliases, -1);
            assertArrayEquals(padded(0, -2, Integer.MIN_VALUE, 0, 42, 0, -3, 43, 44, 45, 46, 47), aliases);
            assertEquals(-1, NativeShaderTextureAliases.evidence().lastClearedName());
            NativeShaderTextureAliases.beforeDelete(level, aliases, new int[]{Integer.MIN_VALUE, 0, -90});
            assertEquals(0, aliases[2]);
            assertEquals(-2, aliases[1]);
            assertEquals(-3, aliases[6]);
            assertEquals(Integer.MIN_VALUE, NativeShaderTextureAliases.evidence().lastClearedName());
            assertEquals(1 << 2, NativeShaderTextureAliases.evidence().lastClearedSlotMaskLo());
            assertEquals(-90, NativeShaderTextureAliases.evidence().lastDeletedName());
        }
    }

    @Test void unrelatedLaterDeletionsDoNotEraseTheLastActualClearReceipt() {
        Object level = new Object();
        int[] aliases = slots(26);
        try (var scope = NativeShaderTextureAliases.begin(level, () -> {})) {
            NativeShaderTextureAliases.beforeDelete(level, aliases, 26);
            NativeShaderTextureAliases.beforeDelete(level, aliases, 27);
            NativeShaderTextureAliases.beforeDelete(level, aliases, new int[]{0, 28, 29});
            assertEquals(26, NativeShaderTextureAliases.evidence().lastClearedName());
            assertEquals(-1L, NativeShaderTextureAliases.evidence().lastClearedSlotMaskLo());
            assertEquals(-1L, NativeShaderTextureAliases.evidence().lastClearedSlotMaskHi());
            assertEquals(29, NativeShaderTextureAliases.evidence().lastDeletedName());
            assertEquals(128, NativeShaderTextureAliases.evidence().aliasesCleared());
        }
        assertEquals(NativeShaderTextureAliases.Outcome.ABORTED, NativeShaderTextureAliases.evidence().outcome());
    }

    @Test void sameNumericNameReusedLaterSurvivesFutureCompletionAndSubsequentScopes() {
        Object level = new Object();
        int[] aliases = slots(19);
        var future = new CompletableFuture<Void>();
        var first = NativeShaderTextureAliases.begin(level, () -> {});
        first.retainUntil(future);
        NativeShaderTextureAliases.beforeDelete(level, aliases, 19);
        assertArrayEquals(slots(0), aliases);
        aliases[0] = 19; // A later live texture reused the deleted numeric name.
        future.complete(null);
        assertEquals(19, aliases[0]);
        long firstGeneration = NativeShaderTextureAliases.evidence().generation();
        try (var second = NativeShaderTextureAliases.begin(level, () -> {})) {
            first.close();
            assertTrue(NativeShaderTextureAliases.active());
            assertTrue(NativeShaderTextureAliases.evidence().generation() > firstGeneration);
            NativeShaderTextureAliases.beforeDelete(level, aliases, 20);
            assertEquals(19, aliases[0]);
        }
        assertEquals(19, aliases[0]);
    }

    @Test void changedRawSlotShapeFailsBeforeAnyMutation() {
        Object level = new Object();
        try (var scope = NativeShaderTextureAliases.begin(level, () -> {})) {
            for (int size : new int[]{0, 11, 12, 13, 32, 64, 127, 129, 256}) {
                int[] aliases = new int[size];
                Arrays.fill(aliases, 42);
                int[] before = aliases.clone();
                assertThrows(IllegalStateException.class,
                        () -> NativeShaderTextureAliases.beforeDelete(level, aliases, 42));
                assertArrayEquals(before, aliases);
                assertThrows(IllegalStateException.class,
                        () -> NativeShaderTextureAliases.beforeDelete(level, aliases, new int[]{42}));
                assertArrayEquals(before, aliases);
            }
            assertThrows(IllegalStateException.class,
                    () -> NativeShaderTextureAliases.beforeDelete(level, null, 42));
            assertEquals(0, NativeShaderTextureAliases.evidence().deletionCalls());
        }
    }

    @Test void aliasedDeletionArgumentIsRejectedBeforeChangingTheActualGlArgument() {
        Object level = new Object();
        int[] aliases = slots(42);
        try (var scope = NativeShaderTextureAliases.begin(level, () -> {})) {
            assertThrows(IllegalStateException.class,
                    () -> NativeShaderTextureAliases.beforeDelete(level, aliases, aliases));
            assertArrayEquals(slots(42), aliases);
        }
    }

    @Test void callbackMustFinishBeforeAnyAliasMutationAndReentryCannotRunItsVerifier() {
        Object level = new Object();
        int[] aliases = slots(42);
        var nestedVerifier = new AtomicInteger();
        try (var scope = NativeShaderTextureAliases.begin(level, () -> {
            assertFalse(NativeShaderTextureAliases.active());
            NativeShaderTextureAliases.beforeDelete(level, aliases, 42);
            assertThrows(IllegalStateException.class,
                    () -> NativeShaderTextureAliases.begin(level, nestedVerifier::incrementAndGet));
        })) {
            assertArrayEquals(slots(42), aliases);
            assertEquals(0, nestedVerifier.get());
            assertTrue(NativeShaderTextureAliases.active());
            NativeShaderTextureAliases.beforeDelete(level, aliases, 42);
            assertArrayEquals(slots(0), aliases);
        }
    }

    @Test void admissionRuntimeExceptionOrErrorPropagatesUnchangedAndReleasesReservation() {
        Object level = new Object();
        var runtimeFailure = new IllegalArgumentException("not the admitted carrier");
        assertSame(runtimeFailure, assertThrows(IllegalArgumentException.class,
                () -> NativeShaderTextureAliases.begin(level, () -> { throw runtimeFailure; })));
        var error = new AssertionError("verification failed");
        assertSame(error, assertThrows(AssertionError.class,
                () -> NativeShaderTextureAliases.begin(level, () -> { throw error; })));
        assertFalse(NativeShaderTextureAliases.active());
        try (var scope = NativeShaderTextureAliases.begin(level, () -> {})) {
            assertTrue(NativeShaderTextureAliases.active());
        }
    }

    @Test void activeScopeRejectsOverlapBeforeCallingOtherVerifier() {
        Object level = new Object();
        var calls = new AtomicInteger();
        try (var scope = NativeShaderTextureAliases.begin(level, () -> {})) {
            assertThrows(IllegalStateException.class,
                    () -> NativeShaderTextureAliases.begin(new Object(), calls::incrementAndGet));
            assertEquals(0, calls.get());
            assertTrue(NativeShaderTextureAliases.active());
        }
    }

    @Test void boundScopeCannotBeClosedByCarrierDisposalAndCompletionPreservesAliases() {
        Object level = new Object();
        int[] aliases = slots(71);
        var future = new CompletableFuture<Void>();
        var scope = NativeShaderTextureAliases.begin(level, () -> {});
        scope.retainUntil(future);
        assertThrows(IllegalStateException.class, scope::close);
        assertFalse(scope.closed());
        NativeShaderTextureAliases.beforeDelete(level, aliases, 71);
        assertArrayEquals(slots(0), aliases);
        aliases[0] = 71;
        future.complete(null);
        assertTrue(scope.closed());
        assertEquals(NativeShaderTextureAliases.Outcome.SUCCESS, NativeShaderTextureAliases.evidence().outcome());
        assertEquals(71, aliases[0]);
        scope.close();
    }

    @Test void exceptionalAndCancelledExactReloadFuturesCloseWithoutMaskingOutcome() {
        Object level = new Object();
        var failure = new IllegalStateException("resource prepare failed");
        var future = new CompletableFuture<Void>();
        var scope = NativeShaderTextureAliases.begin(level, () -> {});
        scope.retainUntil(future);
        future.completeExceptionally(failure);
        assertTrue(scope.closed());
        assertEquals(NativeShaderTextureAliases.Outcome.FAILED, NativeShaderTextureAliases.evidence().outcome());
        int[] lateAliases = slots(92);
        NativeShaderTextureAliases.beforeDelete(level, lateAliases, 92);
        assertArrayEquals(slots(92), lateAliases);
        assertSame(failure, assertThrows(CompletionException.class, future::join).getCause());
        var cancelled = new CompletableFuture<Void>();
        var next = NativeShaderTextureAliases.begin(level, () -> {});
        next.retainUntil(cancelled);
        assertTrue(cancelled.cancel(false));
        assertTrue(cancelled.isCancelled());
        assertTrue(next.closed());
        assertEquals(NativeShaderTextureAliases.Outcome.CANCELLED, NativeShaderTextureAliases.evidence().outcome());
        NativeShaderTextureAliases.beforeDelete(level, lateAliases, new int[]{92});
        assertArrayEquals(slots(92), lateAliases);
    }

    @Test void alreadyCompletedFutureClosesImmediatelyAndCannotBeBoundAgain() {
        var scope = NativeShaderTextureAliases.begin(new Object(), () -> {});
        scope.retainUntil(CompletableFuture.completedFuture(null));
        assertTrue(scope.closed());
        assertThrows(IllegalStateException.class, () -> scope.retainUntil(new CompletableFuture<>()));
        scope.close();
    }

    @Test void exactFutureCannotBeReplacedWithAnUnrelatedCompletion() {
        var first = new CompletableFuture<Void>();
        var other = new CompletableFuture<Void>();
        var scope = NativeShaderTextureAliases.begin(new Object(), () -> {});
        scope.retainUntil(first);
        assertThrows(IllegalStateException.class, () -> scope.retainUntil(other));
        other.complete(null);
        assertFalse(scope.closed());
        assertTrue(NativeShaderTextureAliases.active());
        first.complete(null);
        assertTrue(scope.closed());
    }

    @Test void completionCanReleaseOnWorkerThreadWithoutAnyAliasMutation() throws Exception {
        Object level = new Object();
        int[] aliases = slots(93);
        var future = new CompletableFuture<Void>();
        var scope = NativeShaderTextureAliases.begin(level, () -> {});
        scope.retainUntil(future);
        var worker = new Thread(() -> future.completeExceptionally(new IllegalStateException("reload failed")));
        worker.start();
        worker.join();
        assertTrue(scope.closed());
        assertArrayEquals(slots(93), aliases);
    }

    @Test void ownerThreadIsRequiredForDeletionBindingAndManualAbort() throws Exception {
        Object level = new Object();
        int[] aliases = slots(39);
        var failures = new AtomicReference<Throwable>();
        try (var scope = NativeShaderTextureAliases.begin(level, () -> {})) {
            var worker = new Thread(() -> {
                try {
                    assertThrows(IllegalStateException.class,
                            () -> NativeShaderTextureAliases.beforeDelete(level, aliases, 39));
                    assertThrows(IllegalStateException.class,
                            () -> NativeShaderTextureAliases.beforeDelete(level, aliases, new int[]{39}));
                    assertThrows(IllegalStateException.class, () -> scope.retainUntil(new CompletableFuture<>()));
                    assertThrows(IllegalStateException.class, scope::close);
                    assertDoesNotThrow(() -> NativeShaderTextureAliases.beforeDelete(new Object(), aliases, 39));
                } catch (Throwable failure) { failures.set(failure); }
            });
            worker.start();
            worker.join();
            if (failures.get() != null) throw new AssertionError("Worker assertion failed", failures.get());
            assertArrayEquals(slots(39), aliases);
            assertTrue(NativeShaderTextureAliases.active());
        }
    }

    @Test void registrationFailureReleasesScopeAndPropagatesExactFailure() {
        var failure = new IllegalStateException("callback registration failed");
        var future = new CompletableFuture<Void>() {
            @Override public CompletableFuture<Void> whenComplete(BiConsumer<? super Void, ? super Throwable> action) {
                throw failure;
            }
        };
        var scope = NativeShaderTextureAliases.begin(new Object(), () -> {});
        assertSame(failure, assertThrows(IllegalStateException.class, () -> scope.retainUntil(future)));
        assertTrue(scope.closed());
        assertFalse(future.isDone());
        scope.close();
    }

    @Test void nullArgumentsCannotClaimOrCorruptAnOwner() {
        assertThrows(NullPointerException.class, () -> NativeShaderTextureAliases.begin(null, () -> {}));
        assertThrows(NullPointerException.class, () -> NativeShaderTextureAliases.begin(new Object(), null));
        Object level = new Object();
        int[] aliases = slots(54);
        try (var scope = NativeShaderTextureAliases.begin(level, () -> {})) {
            assertThrows(NullPointerException.class, () -> scope.retainUntil(null));
            assertThrows(NullPointerException.class,
                    () -> NativeShaderTextureAliases.beforeDelete(level, aliases, (int[]) null));
            assertArrayEquals(slots(54), aliases);
            assertTrue(NativeShaderTextureAliases.active());
        }
    }
}
