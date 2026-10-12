package io.earthplatform.minecraft.scene;

import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Independent literal-128 oracle. These are CPU-domain tests, not transformed-client or GL proof. */
class NativeShaderTextureAliasDomainIndependentTest {
    private final List<NativeShaderTextureAliases.Scope> scopes = new ArrayList<>();
    private final List<CompletableFuture<?>> completions = new ArrayList<>();

    @AfterEach void releaseOwnedScopes() {
        completions.forEach(future -> future.cancel(false));
        for (var scope : scopes) if (!scope.closed()) scope.close();
        assertFalse(NativeShaderTextureAliases.active());
    }

    private NativeShaderTextureAliases.Scope begin(Object level) {
        var scope = NativeShaderTextureAliases.begin(level, () -> {});
        scopes.add(scope);
        return scope;
    }

    private <T extends CompletableFuture<?>> T retain(NativeShaderTextureAliases.Scope scope, T completion) {
        completions.add(completion);
        scope.retainUntil(completion);
        return completion;
    }

    // Deliberately independent of the production SLOT_COUNT: the exact pinned artifact proves 128.
    private static int[] slots() {
        int[] result = new int[128];
        for (int i = 0; i < result.length; i++) result[i] = 10000 + i;
        return result;
    }

    private static BigInteger mask(NativeShaderTextureAliases.Evidence evidence) {
        BigInteger low = new BigInteger(Long.toUnsignedString(evidence.lastClearedSlotMaskLo()));
        BigInteger high = new BigInteger(Long.toUnsignedString(evidence.lastClearedSlotMaskHi()));
        return high.shiftLeft(64).or(low);
    }

    private static BigInteger bits(int... indices) {
        BigInteger result = BigInteger.ZERO;
        for (int index : indices) result = result.setBit(index);
        return result;
    }

    @Test void theExactPinnedDomainIs128AndEverySingleSlotHasDistinctNonWrappingProvenance() {
        assertEquals(128, NativeShaderTextureAliases.SLOT_COUNT);
        Object level = new Object();
        begin(level);
        for (int index = 0; index < 128; index++) {
            int[] raw = slots(), expected = raw.clone();
            raw[index] = -77;
            expected[index] = 0;
            NativeShaderTextureAliases.beforeDelete(level, raw, -77);
            assertArrayEquals(expected, raw, "Exact scalar deletion at index " + index);
            var evidence = NativeShaderTextureAliases.evidence();
            assertEquals(BigInteger.ONE.shiftLeft(index), mask(evidence), "Unique slot bit " + index);
            assertEquals(index + 1L, evidence.deletionCalls());
            assertEquals(index + 1L, evidence.aliasesCleared());
        }
    }

    @Test void oneScalarClearsAll128AliasesExactlyOnceIncludingBothSignedMaskBits() {
        Object level = new Object(); begin(level);
        int[] raw = new int[128]; Arrays.fill(raw, Integer.MIN_VALUE);
        NativeShaderTextureAliases.beforeDelete(level, raw, Integer.MIN_VALUE);
        assertArrayEquals(new int[128], raw);
        var full = NativeShaderTextureAliases.evidence();
        assertEquals(128, full.aliasesCleared());
        assertEquals(-1L, full.lastClearedSlotMaskLo());
        assertEquals(-1L, full.lastClearedSlotMaskHi());
        assertEquals(BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE), mask(full));
        NativeShaderTextureAliases.beforeDelete(level, raw, Integer.MIN_VALUE);
        assertEquals(2, NativeShaderTextureAliases.evidence().deletionCalls());
        assertEquals(128, NativeShaderTextureAliases.evidence().aliasesCleared());
        assertEquals(mask(full), mask(NativeShaderTextureAliases.evidence()));
    }

    @Test void explicitWordAndOldIntBoundariesPreserveEveryOtherExactValue() {
        Object level = new Object(); begin(level);
        int[] boundaries = {0, 11, 12, 31, 32, 63, 64, 95, 96, 127};
        int[] raw = slots(); raw[1] = -1; raw[62] = Integer.MIN_VALUE; raw[126] = 0;
        for (int index : boundaries) raw[index] = 711;
        int[] expected = raw.clone();
        for (int index : boundaries) expected[index] = 0;
        NativeShaderTextureAliases.beforeDelete(level, raw, 711);
        assertArrayEquals(expected, raw);
        assertEquals(bits(boundaries), mask(NativeShaderTextureAliases.evidence()));
        assertEquals(10, NativeShaderTextureAliases.evidence().aliasesCleared());
    }

    @Test void nonzeroSignedGLuintBitPatternsAreExactNamesInBothHalvesAndBothOverloads() {
        Object level = new Object(); begin(level);
        int[] raw = slots();
        raw[31] = raw[64] = -1; raw[32] = raw[127] = Integer.MIN_VALUE;
        raw[63] = -2; raw[96] = Integer.MAX_VALUE;
        int[] expected = raw.clone(); expected[31] = expected[64] = 0;
        NativeShaderTextureAliases.beforeDelete(level, raw, -1);
        assertArrayEquals(expected, raw);
        assertEquals(bits(31, 64), mask(NativeShaderTextureAliases.evidence()));
        expected[32] = expected[127] = 0;
        int[] names = {Integer.MIN_VALUE, 0, -99999};
        NativeShaderTextureAliases.beforeDelete(level, raw, names);
        assertArrayEquals(expected, raw);
        assertArrayEquals(new int[]{Integer.MIN_VALUE, 0, -99999}, names);
        assertEquals(bits(32, 127), mask(NativeShaderTextureAliases.evidence()));
        assertEquals(Integer.MIN_VALUE, NativeShaderTextureAliases.evidence().lastClearedName());
        assertEquals(-99999, NativeShaderTextureAliases.evidence().lastDeletedName());
        assertEquals(4, NativeShaderTextureAliases.evidence().aliasesCleared());
    }

    @Test void zeroAbsentAndStaleLookingValuesNeverTriggerGeneralSanitization() {
        Object level = new Object(); begin(level);
        int[] raw = slots(); raw[0] = 0; raw[31] = -1; raw[32] = -2;
        raw[63] = Integer.MIN_VALUE; raw[64] = 0; raw[127] = Integer.MAX_VALUE;
        int[] expected = raw.clone();
        NativeShaderTextureAliases.beforeDelete(level, raw, 0);
        NativeShaderTextureAliases.beforeDelete(level, raw, new int[]{0, -3, 777777, 0});
        assertArrayEquals(expected, raw);
        assertEquals(0, NativeShaderTextureAliases.evidence().aliasesCleared());
        assertEquals(BigInteger.ZERO, mask(NativeShaderTextureAliases.evidence()));
    }

    @Test void bulkDuplicatesKeepOriginalDeletionArgumentAndCountEachPhysicalAliasOnlyOnce() {
        Object level = new Object(); begin(level);
        int[] raw = slots(); raw[0] = raw[32] = raw[64] = 55;
        raw[31] = raw[63] = raw[127] = -9;
        int[] expected = raw.clone();
        for (int index : new int[]{0, 31, 32, 63, 64, 127}) expected[index] = 0;
        int[] names = {55, 55, 0, -9, -9, 1234567}, originalNames = names.clone();
        NativeShaderTextureAliases.beforeDelete(level, raw, names);
        assertArrayEquals(originalNames, names);
        assertArrayEquals(expected, raw);
        var evidence = NativeShaderTextureAliases.evidence();
        assertEquals(1, evidence.deletionCalls()); assertEquals(6, evidence.aliasesCleared());
        assertEquals(-9, evidence.lastClearedName()); assertEquals(1234567, evidence.lastDeletedName());
        assertEquals(bits(31, 63, 127), mask(evidence));
        NativeShaderTextureAliases.beforeDelete(level, raw, new int[0]);
        assertEquals(2, NativeShaderTextureAliases.evidence().deletionCalls());
        assertEquals(0, NativeShaderTextureAliases.evidence().lastDeletedName());
        assertEquals(mask(evidence), mask(NativeShaderTextureAliases.evidence()));
        assertEquals(6, NativeShaderTextureAliases.evidence().aliasesCleared());
    }

    @Test void sameNumericNameReusedAtHighSlotsNeedsAnotherExactContemporaneousDeletion() {
        Object level = new Object(); begin(level);
        int[] raw = slots(); raw[64] = raw[127] = 71;
        NativeShaderTextureAliases.beforeDelete(level, raw, 71);
        raw[32] = raw[64] = raw[127] = 71;
        NativeShaderTextureAliases.beforeDelete(level, raw, new int[]{0, 99999});
        assertEquals(71, raw[32]); assertEquals(71, raw[64]); assertEquals(71, raw[127]);
        assertEquals(2, NativeShaderTextureAliases.evidence().aliasesCleared());
        NativeShaderTextureAliases.beforeDelete(level, raw, 71);
        assertEquals(0, raw[32]); assertEquals(0, raw[64]); assertEquals(0, raw[127]);
        assertEquals(5, NativeShaderTextureAliases.evidence().aliasesCleared());
        assertEquals(bits(32, 64, 127), mask(NativeShaderTextureAliases.evidence()));
    }

    @Test void retainedReceiptIsImmutableAndNeverBecomesDelayedClearingAuthority() {
        Object level = new Object(); var scope = begin(level);
        int[] raw = slots(); raw[63] = raw[127] = 66;
        NativeShaderTextureAliases.beforeDelete(level, raw, 66);
        var first = NativeShaderTextureAliases.evidence();
        raw[63] = raw[127] = 66; raw[31] = 72;
        NativeShaderTextureAliases.beforeDelete(level, raw, 72);
        assertEquals(bits(63, 127), mask(first)); assertEquals(2, first.aliasesCleared());
        assertEquals(bits(31), mask(NativeShaderTextureAliases.evidence()));
        assertEquals(66, raw[63]); assertEquals(66, raw[127]);
        scope.close();
        assertEquals(66, raw[63]); assertEquals(66, raw[127]);
        assertEquals(NativeShaderTextureAliases.Outcome.ABORTED, NativeShaderTextureAliases.evidence().outcome());
    }

    @Test void deterministicReferenceModelCoversMixedScalarBulkDuplicateAndReuseEvents() {
        Object level = new Object(); begin(level);
        Random random = new Random(0x128_6332L);
        int[] names = {0, 1, -1, 43, -51, Integer.MIN_VALUE, Integer.MAX_VALUE};
        int[] raw = slots(), expected = raw.clone();
        long clearCount = 0; int lastClearedName = 0; BigInteger lastMask = BigInteger.ZERO;
        for (int operation = 0; operation < 512; operation++) {
            // Reassignment deliberately reuses names deleted by previous events.
            for (int assignment = 0; assignment < 17; assignment++) {
                int index = random.nextInt(128), name = names[random.nextInt(names.length)];
                raw[index] = expected[index] = name;
            }
            boolean scalar = (operation & 1) == 0;
            int[] deleted = scalar ? new int[]{names[random.nextInt(names.length)]}
                    : new int[]{names[random.nextInt(names.length)], names[random.nextInt(names.length)], 0, 900000};
            int[] originalDeleted = deleted.clone();
            for (int name : deleted) {
                BigInteger cleared = BigInteger.ZERO;
                if (name != 0) for (int i = 0; i < 128; i++) if (expected[i] == name) {
                    expected[i] = 0; cleared = cleared.setBit(i); clearCount++;
                }
                if (cleared.signum() != 0) { lastMask = cleared; lastClearedName = name; }
            }
            if (scalar) NativeShaderTextureAliases.beforeDelete(level, raw, deleted[0]);
            else NativeShaderTextureAliases.beforeDelete(level, raw, deleted);
            assertArrayEquals(originalDeleted, deleted);
            assertArrayEquals(expected, raw, "Reference operation " + operation);
            var evidence = NativeShaderTextureAliases.evidence();
            assertEquals(operation + 1L, evidence.deletionCalls());
            assertEquals(clearCount, evidence.aliasesCleared());
            assertEquals(lastMask, mask(evidence));
            assertEquals(lastClearedName, evidence.lastClearedName());
            assertEquals(deleted[deleted.length - 1], evidence.lastDeletedName());
        }
    }

    @Test void strictWrongShapeNullAndAliasedArgumentsFailBeforeAnyDataOrReceiptMutation() {
        Object level = new Object(); begin(level);
        var initial = NativeShaderTextureAliases.evidence();
        for (int length : new int[]{0, 1, 11, 12, 13, 31, 32, 63, 64, 127, 129, 256}) {
            int[] raw = new int[length]; Arrays.fill(raw, 57); int[] original = raw.clone();
            var failure = assertThrows(IllegalStateException.class,
                    () -> NativeShaderTextureAliases.beforeDelete(level, raw, 57));
            assertTrue(failure.getMessage().contains("128"));
            assertTrue(failure.getMessage().contains("actual=" + length));
            assertThrows(IllegalStateException.class,
                    () -> NativeShaderTextureAliases.beforeDelete(level, raw, new int[]{57}));
            assertArrayEquals(original, raw); assertEquals(initial, NativeShaderTextureAliases.evidence());
        }
        assertThrows(IllegalStateException.class, () -> NativeShaderTextureAliases.beforeDelete(level, null, 57));
        assertThrows(IllegalStateException.class, () -> NativeShaderTextureAliases.beforeDelete(level, null, new int[]{57}));
        int[] raw = slots(), original = raw.clone();
        assertThrows(NullPointerException.class, () -> NativeShaderTextureAliases.beforeDelete(level, raw, (int[]) null));
        assertThrows(IllegalStateException.class, () -> NativeShaderTextureAliases.beforeDelete(level, raw, raw));
        assertArrayEquals(original, raw); assertEquals(initial, NativeShaderTextureAliases.evidence());
    }

    @Test void wrongScopeAndEqualButDifferentLevelNeverInspectOrChangeTheLogicalDomain() {
        record Level(String key) {}
        Object level = new Level("carrier"), other = new Level("carrier");
        assertEquals(level, other); assertNotSame(level, other);
        int[] raw = slots(); raw[127] = 37; int[] expected = raw.clone();
        NativeShaderTextureAliases.beforeDelete(level, raw, 37);
        assertDoesNotThrow(() -> NativeShaderTextureAliases.beforeDelete(level, null, (int[]) null));
        begin(level);
        NativeShaderTextureAliases.beforeDelete(other, raw, 37);
        NativeShaderTextureAliases.beforeDelete(null, raw, new int[]{37});
        assertDoesNotThrow(() -> NativeShaderTextureAliases.beforeDelete(other, new int[12], (int[]) null));
        assertArrayEquals(expected, raw); assertEquals(0, NativeShaderTextureAliases.evidence().deletionCalls());
        NativeShaderTextureAliases.beforeDelete(level, raw, 37); assertEquals(0, raw[127]);
    }

    @Test void admissionCannotClearHighSlotsOrOverlapAndFailedAdmissionReleasesItsReservation() {
        Object level = new Object(); int[] raw = slots(); raw[127] = 37; int[] expected = raw.clone();
        AssertionError failure = new AssertionError("admission rejection");
        assertSame(failure, assertThrows(AssertionError.class, () -> NativeShaderTextureAliases.begin(level, () -> {
            assertFalse(NativeShaderTextureAliases.active());
            NativeShaderTextureAliases.beforeDelete(level, raw, 37);
            assertThrows(IllegalStateException.class, () -> NativeShaderTextureAliases.begin(level,
                    () -> fail("Nested verifier must never execute")));
            throw failure;
        })));
        assertArrayEquals(expected, raw); assertFalse(NativeShaderTextureAliases.active());
        begin(level); NativeShaderTextureAliases.beforeDelete(level, raw, 37); assertEquals(0, raw[127]);
    }

    @Test void failedExactFuturePreservesOriginalFailureAndWithdrawsAllLateHighSlotAuthority() {
        Object level = new Object(); var scope = begin(level);
        var future = retain(scope, new CompletableFuture<Void>());
        int[] raw = slots(); raw[64] = raw[127] = 61;
        NativeShaderTextureAliases.beforeDelete(level, raw, 61);
        raw[64] = raw[127] = 61; int[] expected = raw.clone();
        var failure = new IllegalStateException("original reload failure");
        assertTrue(future.completeExceptionally(failure));
        assertSame(failure, assertThrows(CompletionException.class, future::join).getCause());
        assertTrue(scope.closed()); assertFalse(NativeShaderTextureAliases.active());
        assertEquals(NativeShaderTextureAliases.Outcome.FAILED, NativeShaderTextureAliases.evidence().outcome());
        NativeShaderTextureAliases.beforeDelete(level, raw, new int[]{61});
        assertArrayEquals(expected, raw);
        assertThrows(IllegalStateException.class, () -> scope.retainUntil(new CompletableFuture<>()));
    }

    @Test void successCancellationAndAlreadyFailedCompletionDoNotSanitizeReusedHighAliases() {
        Object level = new Object();
        for (int result = 0; result < 3; result++) {
            var scope = begin(level); int[] raw = slots(); raw[127] = -71;
            NativeShaderTextureAliases.beforeDelete(level, raw, -71); raw[127] = -71;
            var future = result == 2 ? CompletableFuture.<Void>failedFuture(new IllegalArgumentException("already failed"))
                    : new CompletableFuture<Void>();
            retain(scope, future);
            if (result == 0) future.complete(null);
            else if (result == 1) future.cancel(false);
            assertTrue(scope.closed()); assertEquals(-71, raw[127]);
            NativeShaderTextureAliases.beforeDelete(level, raw, -71); assertEquals(-71, raw[127]);
            assertEquals(result == 0 ? NativeShaderTextureAliases.Outcome.SUCCESS
                    : result == 1 ? NativeShaderTextureAliases.Outcome.CANCELLED : NativeShaderTextureAliases.Outcome.FAILED,
                    NativeShaderTextureAliases.evidence().outcome());
        }
    }

    @Test void oldCallbackReplayAndOldScopeCloseCannotReleaseLaterSameLevelGeneration() {
        Object level = new Object(); var first = begin(level);
        var firstFuture = retain(first, new ReplayFuture());
        int[] raw = slots(); raw[127] = 88;
        NativeShaderTextureAliases.beforeDelete(level, raw, 88);
        firstFuture.complete(null); long generation = NativeShaderTextureAliases.evidence().generation();
        raw[127] = 88; var second = begin(level);
        first.close(); firstFuture.replay();
        assertFalse(second.closed()); assertTrue(NativeShaderTextureAliases.active());
        assertTrue(NativeShaderTextureAliases.evidence().generation() > generation);
        assertEquals(0, NativeShaderTextureAliases.evidence().deletionCalls());
        assertEquals(BigInteger.ZERO, mask(NativeShaderTextureAliases.evidence()));
        assertEquals(88, raw[127]);
        NativeShaderTextureAliases.beforeDelete(level, raw, 88);
        assertEquals(0, raw[127]); assertEquals(bits(127), mask(NativeShaderTextureAliases.evidence()));
    }

    @Test void exactPendingFutureCannotBeReboundOrDisposedAndWorkerCompletionOnlyReleasesMetadata() throws Exception {
        Object level = new Object(); var scope = begin(level);
        var future = retain(scope, new CompletableFuture<Void>());
        assertThrows(IllegalStateException.class, scope::close);
        assertThrows(IllegalStateException.class, () -> scope.retainUntil(new CompletableFuture<>()));
        int[] raw = slots(); raw[127] = -71; int[] expected = raw.clone();
        var otherFailure = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try {
                assertThrows(IllegalStateException.class, () -> NativeShaderTextureAliases.beforeDelete(level, raw, -71));
                assertThrows(IllegalStateException.class, () -> NativeShaderTextureAliases.beforeDelete(level, raw, new int[]{-71}));
                future.completeExceptionally(new IllegalStateException("worker completion"));
            } catch (Throwable failure) { otherFailure.set(failure); }
        });
        worker.start(); worker.join();
        if (otherFailure.get() != null) throw new AssertionError("Worker assertions", otherFailure.get());
        assertTrue(scope.closed()); assertArrayEquals(expected, raw);
        assertEquals(0, NativeShaderTextureAliases.evidence().deletionCalls());
    }

    @Test void failedCallbackRegistrationCannotRetainHighSlotMutationAuthority() {
        Object level = new Object(); var scope = begin(level);
        var failure = new AssertionError("registration failed");
        var future = new CompletableFuture<Void>() {
            @Override public CompletableFuture<Void> whenComplete(BiConsumer<? super Void, ? super Throwable> action) {
                throw failure;
            }
        };
        assertSame(failure, assertThrows(AssertionError.class, () -> scope.retainUntil(future)));
        assertTrue(scope.closed()); assertFalse(future.isDone());
        assertEquals(NativeShaderTextureAliases.Outcome.ABORTED, NativeShaderTextureAliases.evidence().outcome());
        int[] raw = slots(); raw[127] = 71;
        NativeShaderTextureAliases.beforeDelete(level, raw, 71); assertEquals(71, raw[127]);
    }

    @Test void diagnosticsAreFixedImmutableScalarsWithExactlyTwoLongSlotWordsAndNoRetainedArray() {
        var components = Arrays.stream(NativeShaderTextureAliases.Evidence.class.getRecordComponents()).toList();
        assertTrue(NativeShaderTextureAliases.Evidence.class.isRecord());
        assertEquals(List.of("lastClearedSlotMaskLo", "lastClearedSlotMaskHi"),
                components.stream().filter(component -> component.getName().startsWith("lastClearedSlotMask"))
                        .map(component -> component.getName()).toList());
        assertTrue(components.stream().filter(component -> component.getName().startsWith("lastClearedSlotMask"))
                .allMatch(component -> component.getType() == long.class));
        for (Class<?> type : List.of(NativeShaderTextureAliases.class, NativeShaderTextureAliases.Scope.class,
                NativeShaderTextureAliases.Evidence.class)) for (var field : type.getDeclaredFields()) {
            assertFalse(field.getType().isArray(), type.getName() + "." + field.getName());
            assertFalse(java.util.Collection.class.isAssignableFrom(field.getType()));
            assertFalse(java.util.Map.class.isAssignableFrom(field.getType()));
            assertNotEquals(BigInteger.class, field.getType());
            if (type == NativeShaderTextureAliases.Evidence.class && !Modifier.isStatic(field.getModifiers()))
                assertTrue(Modifier.isFinal(field.getModifiers()));
        }
    }

    @Test void actualHostSerializerPublishesExactFixedHexWordsIncludingBits63And127() throws Exception {
        var serializer = Class.forName("io.earthplatform.minecraft.coregate.RollingGateClient")
                .getDeclaredMethod("shaderTextureAliasEvidence");
        serializer.setAccessible(true);
        Object level = new Object(); begin(level);
        int[][] patterns = {{0, 63, 64, 127}, {127}, {0, 31, 32, 63}, {}};
        String[][] expected = {{"8000000000000001", "8000000000000001"}, {"0000000000000000", "8000000000000000"},
                {"8000000180000001", "0000000000000000"}, {"ffffffffffffffff", "ffffffffffffffff"}};
        for (int pattern = 0; pattern < patterns.length; pattern++) {
            int[] raw = slots();
            if (patterns[pattern].length == 0) Arrays.fill(raw, 57);
            else for (int slot : patterns[pattern]) raw[slot] = 57;
            NativeShaderTextureAliases.beforeDelete(level, raw, 57);
            var evidence = NativeShaderTextureAliases.evidence();
            // Invoke the production serializer, then parse an actual JSON round trip.
            var json = com.google.gson.JsonParser.parseString(serializer.invoke(null).toString()).getAsJsonObject();
            assertEquals(128, json.get("logicalSlotCount").getAsInt());
            assertEquals(expected[pattern][0], json.get("lastClearedSlotMaskLoHex").getAsString());
            assertEquals(expected[pattern][1], json.get("lastClearedSlotMaskHiHex").getAsString());
            for (String key : List.of("lastClearedSlotMaskLoHex", "lastClearedSlotMaskHiHex")) {
                assertTrue(json.getAsJsonPrimitive(key).isString());
                assertTrue(json.get(key).getAsString().matches("[0-9a-f]{16}"));
            }
            assertEquals(evidence.lastClearedSlotMaskLo(), json.get("lastClearedSlotMaskLo").getAsLong());
            assertEquals(evidence.lastClearedSlotMaskHi(), json.get("lastClearedSlotMaskHi").getAsLong());
            BigInteger reconstructed = new BigInteger(json.get("lastClearedSlotMaskHiHex").getAsString(), 16)
                    .shiftLeft(64).or(new BigInteger(json.get("lastClearedSlotMaskLoHex").getAsString(), 16));
            assertEquals(mask(evidence), reconstructed);
        }
    }

    private static final class ReplayFuture extends CompletableFuture<Void> {
        private BiConsumer<? super Void, ? super Throwable> observer;
        @Override public CompletableFuture<Void> whenComplete(BiConsumer<? super Void, ? super Throwable> action) {
            observer = action; return super.whenComplete(action);
        }
        void replay() { observer.accept(null, null); }
    }
}
