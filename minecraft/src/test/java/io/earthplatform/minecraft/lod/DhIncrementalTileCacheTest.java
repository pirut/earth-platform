package io.earthplatform.minecraft.lod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

import static io.earthplatform.minecraft.lod.DhIncrementalTileCache.Blocked.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure ownership tests. They intentionally do not claim to exercise any graphics driver. */
class DhIncrementalTileCacheTest {
    private static final long UNLIMITED = Long.MAX_VALUE;

    private static final class Resource {
        final Object key;
        final List<Integer> faces;
        long bytes;
        boolean deleted, silentClose, deleteBeforeThrow, failDeletedCheck;
        int closeCalls, failCloses;
        Resource(Object key, List<Integer> faces, long bytes) {
            this.key = key;
            this.faces = faces;
            this.bytes = bytes;
        }
        // Deliberately value-equal: only identity can safely track ownership.
        @Override public boolean equals(Object other) { return other instanceof Resource; }
        @Override public int hashCode() { return 1; }
    }

    private static class Ops<K> implements DhIncrementalTileCache.ResourceOps<K, Integer, Resource> {
        final List<Resource> allocated = new ArrayList<>();
        int uploads, estimates, failUpload = -1, failBytes = -1, peakLive;
        long estimateOverride = -1, actualExtra;
        RuntimeException uploadFailure = new IllegalArgumentException("injected upload");
        RuntimeException estimateFailure;
        Resource alias;
        Runnable onUpload = () -> {}, onEstimate = () -> {};
        @Override public long estimateBytes(List<Integer> faces) {
            estimates++;
            if (estimateFailure != null) throw estimateFailure;
            onEstimate.run();
            return estimateOverride < 0 ? faces.size() * 10L : estimateOverride;
        }
        @Override public Resource upload(K key, List<Integer> faces) {
            uploads++;
            onUpload.run();
            if (alias != null) return alias;
            var resource = new Resource(key, faces, faces.size() * 10L + actualExtra);
            allocated.add(resource);
            peakLive = Math.max(peakLive, live());
            if (uploads == failUpload) {
                // A partial upload remains factory-owned until successful return.
                resource.deleted = true;
                throw uploadFailure;
            }
            return resource;
        }
        @Override public long bytes(Resource resource) {
            if (uploads == failBytes) throw new IllegalStateException("injected bytes");
            return resource.bytes;
        }
        @Override public void close(Resource resource) {
            resource.closeCalls++;
            if (resource.failCloses-- > 0) {
                if (resource.deleteBeforeThrow) resource.deleted = true;
                throw new IllegalStateException("injected close " + resource.key);
            }
            if (!resource.silentClose) resource.deleted = true;
        }
        @Override public boolean deleted(Resource resource) {
            if (resource.failDeletedCheck) throw new IllegalStateException("injected deletion check");
            return resource.deleted;
        }
        int live() { return (int) allocated.stream().filter(resource -> !resource.deleted).count(); }
    }

    private static DhIncrementalTileCache<String, Integer, Resource> cache(Ops<String> ops) {
        return new DhIncrementalTileCache<>(ops, 10_000);
    }

    private static <K> void publish(DhIncrementalTileCache<K, Integer, Resource> cache, long id,
                                    Map<K, List<Integer>> faces) {
        assertTrue(cache.stage(id, faces));
        assertTrue(cache.pump(UNLIMITED, Integer.MAX_VALUE, UNLIMITED).ready());
        assertTrue(cache.ready(id));
        assertTrue(cache.commit(id));
    }

    private static Map<String, List<Integer>> tiles(int count, int value) {
        var result = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < count; i++) result.put("tile-" + i, List.of(value));
        return result;
    }

    @Test void unchangedFullBatchReusesEveryIdentityWithoutAnEstimateOrUpload() {
        var ops = new Ops<String>();
        try (var cache = cache(ops)) {
            publish(cache, 0, tiles(64, 1));
            Map<String, Resource> first = cache.currentResources();
            assertTrue(cache.stage(1, tiles(64, 1)));
            assertTrue(cache.ready(1));
            assertEquals(64, cache.lastStageReusedOwners());
            assertEquals(0, cache.lastStageChangedOwners());
            assertEquals(new DhIncrementalTileCache.PumpResult(0, 0, true, NONE), cache.pump(0, 0, 0));
            assertTrue(cache.commit(1));
            assertEquals(64, ops.uploads);
            assertEquals(64, ops.estimates);
            assertEquals(64, cache.residentOwners());
            assertEquals(640, cache.residentBytes());
            for (String key : first.keySet()) assertSame(first.get(key), cache.currentResources().get(key));
            assertTrue(ops.allocated.stream().allMatch(resource -> resource.closeCalls == 0));
        }
        assertEquals(0, ops.live());
    }

    @Test void partialChangeUploadsOnlyChangedAndNewKeysAndRetiresOnlyAfterCommit() {
        var ops = new Ops<String>();
        try (var cache = cache(ops)) {
            publish(cache, 0, Map.of("keep", List.of(1), "change", List.of(1), "remove", List.of(1)));
            var before = cache.currentResources();
            assertTrue(cache.stage(1, Map.of("keep", List.of(1), "change", List.of(2), "new", List.of(1))));
            assertEquals(1, cache.lastStageReusedOwners());
            assertEquals(2, cache.lastStageChangedOwners());
            var first = cache.pump(10, 1, UNLIMITED);
            assertFalse(first.ready());
            assertSame(before, cache.currentResources());
            assertFalse(cache.commit(1));
            assertTrue(cache.pump(10, 1, UNLIMITED).ready());
            assertTrue(before.values().stream().noneMatch(resource -> resource.deleted));
            assertEquals(50, cache.residentBytes());
            assertTrue(cache.commit(1));
            assertSame(before.get("keep"), cache.currentResources().get("keep"));
            assertNotSame(before.get("change"), cache.currentResources().get("change"));
            assertTrue(before.get("change").deleted);
            assertTrue(before.get("remove").deleted);
            assertFalse(before.get("keep").deleted);
            assertEquals(5, cache.uploadedOwners());
            assertEquals(50, cache.uploadedBytes());
            assertEquals(30, cache.currentBytes());
            assertEquals(30, cache.residentBytes());
        }
    }

    @Test void emptyAndRemovalOnlyStagesNeedNoUploadAndEmptyListsRemainValidPayloads() {
        var ops = new Ops<String>();
        try (var cache = cache(ops)) {
            publish(cache, 0, Map.of("empty", List.of(), "a", List.of(1)));
            Resource empty = cache.currentResources().get("empty");
            Resource a = cache.currentResources().get("a");
            assertTrue(cache.stage(1, Map.of("empty", List.of())));
            assertTrue(cache.ready(1));
            assertFalse(a.deleted);
            assertTrue(cache.commit(1));
            assertTrue(a.deleted);
            assertSame(empty, cache.currentResources().get("empty"));
            assertTrue(cache.stage(2, Map.of()));
            assertTrue(cache.ready(2));
            assertTrue(cache.commit(2));
            assertTrue(empty.deleted);
            assertTrue(cache.currentResources().isEmpty());
            assertEquals(0, cache.residentOwners());
            assertEquals(2, ops.uploads);
        }
    }

    @Test void inputAndPublishedMapsAndFaceListsAreImmutableSnapshots() {
        var ops = new Ops<String>();
        try (var cache = cache(ops)) {
            var faces = new ArrayList<>(List.of(1));
            var input = new LinkedHashMap<String, List<Integer>>();
            input.put("a", faces);
            assertTrue(cache.stage(0, input));
            faces.set(0, 99);
            input.clear();
            assertTrue(cache.pump(10, 1, UNLIMITED).ready());
            assertTrue(cache.commit(0));
            assertEquals(List.of(1), cache.currentPayloads().get("a"));
            assertThrows(UnsupportedOperationException.class, () -> cache.currentResources().clear());
            assertThrows(UnsupportedOperationException.class, () -> cache.currentPayloads().clear());
            assertThrows(UnsupportedOperationException.class, () -> cache.currentPayloads().get("a").add(2));
            assertThrows(UnsupportedOperationException.class, () -> ops.allocated.getFirst().faces.add(2));
        }
    }

    @Test void pumpUsesByteOwnerAndTimeChunksAndPublishesNothingUntilComplete() {
        var ops = new Ops<String>();
        var clock = new AtomicLong();
        ops.onUpload = () -> clock.addAndGet(4);
        try (var cache = new DhIncrementalTileCache<>(ops, 1000, 64, 128, clock::get)) {
            assertTrue(cache.stage(0, tiles(5, 1)));
            assertEquals(OWNER_BUDGET, cache.pump(UNLIMITED, 0, UNLIMITED).blocked());
            assertEquals(TIME_BUDGET, cache.pump(UNLIMITED, 10, 0).blocked());
            assertEquals(BYTE_BUDGET, cache.pump(9, 10, UNLIMITED).blocked());
            assertEquals(0, ops.uploads);
            var byteChunk = cache.pump(15, 10, UNLIMITED);
            assertEquals(BYTE_BUDGET, byteChunk.blocked());
            assertEquals(1, byteChunk.uploadedOwners());
            assertEquals(10, byteChunk.uploadedBytes());
            assertEquals(OWNER_BUDGET, cache.pump(100, 1, UNLIMITED).blocked());
            var timeChunk = cache.pump(100, 10, 5);
            assertEquals(TIME_BUDGET, timeChunk.blocked());
            assertEquals(2, timeChunk.uploadedOwners());
            assertTrue(cache.currentResources().isEmpty());
            assertTrue(cache.pump(10, 1, 5).ready());
            assertTrue(cache.currentResources().isEmpty());
            assertTrue(cache.commit(0));
            assertEquals(5, cache.currentResources().size());
        }
    }

    @Test void slowEstimateCannotAdmitAnUploadAfterTheTimeBudget() {
        var ops = new Ops<String>();
        var clock = new AtomicLong();
        ops.onEstimate = () -> clock.addAndGet(10);
        try (var cache = new DhIncrementalTileCache<>(ops, 100, 64, 128, clock::get)) {
            cache.stage(0, Map.of("a", List.of(1)));
            assertEquals(TIME_BUDGET, cache.pump(100, 1, 5).blocked());
            assertEquals(0, ops.uploads);
        }
    }

    @Test void residentBytesCountTheCurrentAndPrivateMapsBeforeCommit() {
        var ops = new Ops<String>();
        try (var cache = new DhIncrementalTileCache<>(ops, 30)) {
            publish(cache, 0, tiles(2, 1));
            var current = cache.currentResources();
            cache.stage(1, tiles(2, 2));
            var result = cache.pump(100, 100, UNLIMITED);
            assertEquals(RESIDENT_BYTES, result.blocked());
            assertEquals(1, result.uploadedOwners());
            assertEquals(30, cache.residentBytes());
            assertSame(current, cache.currentResources());
            assertFalse(cache.ready(1));
            assertFalse(cache.commit(1));
            cache.cancelStage();
            assertEquals(20, cache.residentBytes());
        }
    }

    @Test void hardBoundsAllow64CurrentPlus64PrivateAndReject65Keys() {
        var ops = new Ops<String>();
        try (var cache = cache(ops)) {
            publish(cache, 0, tiles(64, 1));
            cache.stage(1, tiles(64, 2));
            assertThrows(IllegalArgumentException.class, () -> cache.stage(2, tiles(65, 1)));
            assertTrue(cache.pump(UNLIMITED, 1000, UNLIMITED).ready());
            assertEquals(128, cache.residentOwners());
            assertEquals(128, ops.peakLive);
            assertTrue(cache.commit(1));
            assertEquals(64, cache.residentOwners());
        }
    }

    @Test void residentOwnerLimitStopsBeforeAllocatingEvenWithByteRoom() {
        var ops = new Ops<String>();
        try (var cache = new DhIncrementalTileCache<>(ops, 1000, 2, 3, System::nanoTime)) {
            publish(cache, 0, tiles(2, 1));
            cache.stage(1, tiles(2, 2));
            assertEquals(RESIDENT_OWNERS, cache.pump(1000, 100, UNLIMITED).blocked());
            assertEquals(3, cache.residentOwners());
            assertEquals(3, ops.uploads);
            assertEquals(30, cache.residentBytes());
        }
    }

    @Test void cancellationAndSupersessionDeleteOnlyPrivateOwnersNeverBorrowedOwners() {
        var ops = new Ops<String>();
        try (var cache = cache(ops)) {
            publish(cache, 0, Map.of("keep", List.of(1), "change", List.of(1)));
            var current = cache.currentResources();
            cache.stage(1, Map.of("keep", List.of(1), "change", List.of(2)));
            assertTrue(cache.pump(100, 100, UNLIMITED).ready());
            Resource cancelled = ops.allocated.getLast();
            cache.cancelStage();
            assertTrue(cancelled.deleted);
            assertTrue(current.values().stream().noneMatch(resource -> resource.deleted));
            assertSame(current, cache.currentResources());
            cache.stage(2, Map.of("keep", List.of(1), "change", List.of(3)));
            cache.pump(100, 100, UNLIMITED);
            Resource superseded = ops.allocated.getLast();
            assertTrue(cache.stage(3, Map.of("keep", List.of(1))));
            assertTrue(superseded.deleted);
            assertTrue(cache.ready(3));
            assertFalse(cache.ready(2));
            assertFalse(cache.commit(2));
            assertTrue(cache.commit(3));
            assertFalse(current.get("keep").deleted);
            assertTrue(current.get("change").deleted);
        }
    }

    @Test void consumedStageIdsCannotResurrectAfterCancelFailureOrCommit() {
        var ops = new Ops<String>();
        try (var cache = cache(ops)) {
            cache.stage(4, Map.of("a", List.of(1)));
            cache.cancelStage();
            assertFalse(cache.stage(4, Map.of("a", List.of(2))));
            assertFalse(cache.stage(3, Map.of()));
            cache.stage(5, Map.of("a", List.of(1)));
            ops.failUpload = 1;
            assertThrows(IllegalArgumentException.class, () -> cache.pump(100, 1, UNLIMITED));
            assertFalse(cache.stage(5, Map.of()));
            ops.failUpload = -1;
            publish(cache, 6, Map.of("a", List.of(1)));
            assertEquals(6, cache.committedTransactionId());
            assertFalse(cache.ready(6));
            assertFalse(cache.commit(6));
            assertFalse(cache.stage(6, Map.of("a", List.of(2))));
            assertThrows(IllegalArgumentException.class, () -> cache.stage(-1, Map.of()));
            assertTrue(cache.stage(Long.MAX_VALUE, Map.of()));
            assertFalse(cache.stage(Long.MAX_VALUE, Map.of()));
            assertFalse(cache.stage(0, Map.of()));
        }
    }

    @Test void keyGenerationStampsPreventCrossWorldEditJobAndResourceReuse() {
        record Key(String tile, long world, long edit, long job, long resourceGeneration) {}
        var ops = new Ops<Key>();
        try (var cache = new DhIncrementalTileCache<>(ops, 1000)) {
            var keys = List.of(new Key("a", 1, 1, 1, 1), new Key("a", 2, 1, 1, 1),
                    new Key("a", 2, 2, 1, 1), new Key("a", 2, 2, 2, 1), new Key("a", 2, 2, 2, 2));
            for (int i = 0; i < keys.size(); i++) {
                Resource previous = cache.currentResources().values().stream().findFirst().orElse(null);
                publish(cache, i, Map.of(keys.get(i), List.of(1)));
                assertEquals(0, cache.lastStageReusedOwners());
                if (previous != null) assertTrue(previous.deleted);
            }
            assertEquals(keys.size(), ops.uploads);
        }
    }

    @Test void sameFacesAtDifferentKeysNeverBorrowAnotherKeysOwner() {
        var ops = new Ops<String>();
        try (var cache = cache(ops)) {
            publish(cache, 0, Map.of("a", List.of(1)));
            Resource previous = cache.currentResources().get("a");
            cache.stage(1, Map.of("b", List.of(1), "c", List.of(1)));
            assertFalse(cache.ready(1));
            cache.pump(100, 10, UNLIMITED);
            assertEquals(3, cache.residentOwners());
            assertTrue(cache.commit(1));
            assertEquals(2, cache.residentOwners());
            assertNotSame(cache.currentResources().get("b"), cache.currentResources().get("c"));
            assertEquals(cache.currentResources().get("b"), cache.currentResources().get("c"));
            assertTrue(previous.deleted);
        }
    }

    @Test void anAliasedPublishedResourceIsRejectedWithoutDeletingIt() {
        var ops = new Ops<String>();
        try (var cache = cache(ops)) {
            publish(cache, 0, Map.of("a", List.of(1)));
            ops.alias = cache.currentResources().get("a");
            cache.stage(1, Map.of("a", List.of(1), "b", List.of(2)));
            assertThrows(IllegalStateException.class, () -> cache.pump(100, 10, UNLIMITED));
            assertFalse(ops.alias.deleted);
            assertEquals(0, ops.alias.closeCalls);
            assertEquals(1, cache.residentOwners());
            assertFalse(cache.ready(1));
            assertFalse(cache.fenced());
        }
    }

    @Test void anAliasedPrivateResourceIsClosedExactlyOnceOnRollback() {
        var ops = new Ops<String>();
        try (var cache = cache(ops)) {
            cache.stage(0, tiles(2, 1));
            cache.pump(10, 1, UNLIMITED);
            ops.alias = ops.allocated.getFirst();
            assertThrows(IllegalStateException.class, () -> cache.pump(10, 1, UNLIMITED));
            assertTrue(ops.alias.deleted);
            assertEquals(1, ops.alias.closeCalls);
            assertEquals(0, cache.residentOwners());
            assertTrue(cache.currentResources().isEmpty());
        }
    }

    @Test void firstMiddleAndLastUploadFailuresThrowAndRollbackEveryReturnedPrivateResource() {
        for (int failureOffset = 1; failureOffset <= 3; failureOffset++) {
            var ops = new Ops<String>();
            try (var cache = cache(ops)) {
                publish(cache, 0, tiles(3, 1));
                var published = cache.currentResources();
                cache.stage(1, tiles(3, 2));
                ops.failUpload = ops.uploads + failureOffset;
                RuntimeException failure = assertThrows(IllegalArgumentException.class,
                        () -> cache.pump(1000, 10, UNLIMITED));
                assertSame(ops.uploadFailure, failure);
                assertSame(published, cache.currentResources());
                assertTrue(published.values().stream().noneMatch(resource -> resource.deleted));
                assertTrue(ops.allocated.subList(3, ops.allocated.size()).stream().allMatch(resource -> resource.deleted));
                assertEquals(3, cache.residentOwners());
                assertFalse(cache.ready(1));
                assertFalse(cache.fenced());
                assertEquals(0, ops.allocated.getLast().closeCalls, "Factory cleans partial upload itself");
            }
        }
    }

    @Test void estimateAndByteMeasurementExceptionsAreOperationalFailuresRatherThanFalse() {
        var ops = new Ops<String>();
        try (var cache = cache(ops)) {
            publish(cache, 0, Map.of("a", List.of(1)));
            cache.stage(1, tiles(2, 2));
            ops.estimateFailure = new IllegalArgumentException("bad estimate operation");
            assertSame(ops.estimateFailure, assertThrows(IllegalArgumentException.class,
                    () -> cache.pump(100, 10, UNLIMITED)));
            assertFalse(cache.ready(1));
            assertEquals(1, cache.residentOwners());
            ops.estimateFailure = null;
            cache.stage(2, tiles(2, 2));
            ops.failBytes = ops.uploads + 1;
            assertThrows(IllegalStateException.class, () -> cache.pump(100, 10, UNLIMITED));
            assertTrue(ops.allocated.getLast().deleted);
            assertEquals(1, cache.residentOwners());
            assertFalse(cache.fenced());
        }
    }

    @Test void actualByteOverflowRollsBackAndRetainsItsOwnerIfDeletionFails() {
        var ops = new Ops<String>() {
            @Override public Resource upload(String key, List<Integer> faces) {
                Resource resource = super.upload(key, faces);
                if (actualExtra > 0) resource.failCloses = 5;
                return resource;
            }
        };
        var cache = new DhIncrementalTileCache<>(ops, 30);
        publish(cache, 0, Map.of("a", List.of(1)));
        cache.stage(1, Map.of("a", List.of(2)));
        ops.actualExtra = 100;
        var failure = assertThrows(IllegalStateException.class, () -> cache.pump(20, 1, UNLIMITED));
        assertTrue(failure.getMessage().contains("byte budget"));
        assertTrue(failure.getSuppressed().length > 0);
        assertEquals(120, cache.residentBytes(), "Actual failed-retirement bytes remain accounted even over cap");
        assertEquals(2, cache.residentOwners());
        assertEquals(1, cache.retirementPending());
        assertFalse(cache.fenced());
        ops.actualExtra = 0;
        cache.stage(2, Map.of("a", List.of(3)));
        assertEquals(RESIDENT_BYTES, cache.pump(100, 10, UNLIMITED).blocked());
        assertEquals(2, ops.uploads);
        ops.allocated.getLast().failCloses = 0;
        cache.retryRetirements();
        assertEquals(10, cache.residentBytes());
        assertTrue(cache.pump(100, 10, UNLIMITED).ready());
        cache.close();
        assertTrue(cache.deleted());
    }

    @Test void cancelledResourcesWithFailedDeletionConsumeByteAndOwnerCapacityUntilRetry() {
        for (boolean byteLimited : List.of(false, true)) {
            var ops = new Ops<String>();
            var cache = new DhIncrementalTileCache<>(ops, byteLimited ? 20 : 1000,
                    2, byteLimited ? 128 : 2, System::nanoTime);
            publish(cache, 0, Map.of("a", List.of(1)));
            cache.stage(1, Map.of("a", List.of(2)));
            cache.pump(100, 1, UNLIMITED);
            Resource privateOwner = ops.allocated.getLast();
            privateOwner.failCloses = 1;
            assertThrows(IllegalStateException.class, cache::cancelStage);
            assertEquals(20, cache.residentBytes());
            assertEquals(1, cache.retirementPending());
            assertTrue(cache.stage(2, Map.of("a", List.of(3))));
            assertEquals(byteLimited ? RESIDENT_BYTES : RESIDENT_OWNERS,
                    cache.pump(100, 1, UNLIMITED).blocked());
            cache.retryRetirements();
            assertTrue(privateOwner.deleted);
            assertTrue(cache.pump(100, 1, UNLIMITED).ready());
            assertTrue(cache.commit(2));
            cache.close();
        }
    }

    @Test void commitCleanupFailurePublishesThenFencesAttemptsAllAndRetainsOnlyUndeletedOwners() {
        var ops = new Ops<String>();
        var cache = cache(ops);
        publish(cache, 0, tiles(3, 1));
        var previous = cache.currentResources();
        previous.get("tile-0").failCloses = 1;
        previous.get("tile-1").failCloses = 1;
        cache.stage(1, tiles(3, 2));
        cache.pump(1000, 10, UNLIMITED);
        assertThrows(IllegalStateException.class, () -> cache.commit(1));
        assertEquals(1, cache.committedTransactionId());
        assertEquals(3, cache.currentResources().size());
        for (String key : previous.keySet()) {
            assertNotSame(previous.get(key), cache.currentResources().get(key));
            assertEquals(1, previous.get(key).closeCalls, "Every old owner must be attempted");
        }
        assertTrue(cache.fenced());
        assertFalse(cache.ready(1));
        assertEquals(2, cache.retirementPending());
        assertEquals(5, cache.residentOwners());
        assertEquals(50, cache.residentBytes());
        assertThrows(IllegalStateException.class, () -> cache.stage(2, Map.of()));
        assertThrows(IllegalStateException.class, () -> cache.pump(100, 10, UNLIMITED));
        assertThrows(IllegalStateException.class, () -> cache.commit(1));
        cache.retryRetirements();
        assertEquals(0, cache.retirementPending());
        assertTrue(cache.fenced(), "Cleanup cannot undo a post-commit draw fence");
        cache.close();
        assertEquals(0, ops.live());
    }

    @Test void closeRetriesFailuresAndDoesNotRecloseAlreadyDeletedResources() {
        var ops = new Ops<String>();
        var cache = cache(ops);
        publish(cache, 0, tiles(3, 1));
        cache.stage(1, tiles(3, 2));
        cache.pump(100, 2, UNLIMITED);
        Resource failingCurrent = ops.allocated.getFirst();
        Resource failingPrivate = ops.allocated.getLast();
        failingCurrent.failCloses = 1;
        failingPrivate.failCloses = 1;
        assertThrows(IllegalStateException.class, cache::close);
        assertTrue(cache.closed());
        assertTrue(cache.fenced());
        assertFalse(cache.deleted());
        assertTrue(cache.currentResources().isEmpty());
        assertEquals(2, cache.residentOwners());
        assertEquals(20, cache.residentBytes());
        assertTrue(ops.allocated.stream().allMatch(resource -> resource.closeCalls == 1));
        cache.close();
        assertTrue(cache.deleted());
        assertEquals(0, cache.residentBytes());
        assertEquals(2, failingCurrent.closeCalls);
        assertEquals(2, failingPrivate.closeCalls);
        int calls = ops.allocated.stream().mapToInt(resource -> resource.closeCalls).sum();
        cache.close();
        cache.retryRetirements();
        assertEquals(calls, ops.allocated.stream().mapToInt(resource -> resource.closeCalls).sum());
    }

    @Test void silentNonDeletionAndDeletionQueryFailuresKeepReferencesForRetry() {
        var ops = new Ops<String>();
        var cache = cache(ops);
        publish(cache, 0, tiles(3, 1));
        Resource silent = ops.allocated.get(0);
        Resource queryFailure = ops.allocated.get(1);
        silent.silentClose = true;
        queryFailure.failDeletedCheck = true;
        assertThrows(IllegalStateException.class, cache::close);
        assertEquals(2, cache.retirementPending());
        assertEquals(1, queryFailure.closeCalls, "A failed pre-close query must not skip the close attempt");
        assertTrue(ops.allocated.get(2).deleted);
        silent.silentClose = false;
        queryFailure.failDeletedCheck = false;
        cache.close();
        assertTrue(cache.deleted());
        assertEquals(1, queryFailure.closeCalls, "A resource now confirmed deleted must not be closed twice");
    }

    @Test void aCloseThatThrowsAfterDeletionStillThrowsButDoesNotRetainDeletedOwnership() {
        var ops = new Ops<String>();
        var cache = cache(ops);
        publish(cache, 0, Map.of("a", List.of(1)));
        Resource resource = ops.allocated.getFirst();
        resource.failCloses = 1;
        resource.deleteBeforeThrow = true;
        assertThrows(IllegalStateException.class, cache::close);
        assertTrue(cache.deleted());
        cache.close();
        assertEquals(1, resource.closeCalls);
    }

    @Test void malformedRequestsLeaveExistingStageAndPublishedStateAlone() {
        var ops = new Ops<String>();
        try (var cache = cache(ops)) {
            publish(cache, 0, Map.of("a", List.of(1)));
            cache.stage(1, Map.of("a", List.of(2)));
            assertThrows(IllegalArgumentException.class, () -> cache.stage(2, tiles(65, 1)));
            assertThrows(NullPointerException.class, () -> cache.stage(2, null));
            var nullKey = new LinkedHashMap<String, List<Integer>>();
            nullKey.put(null, List.of(1));
            assertThrows(NullPointerException.class, () -> cache.stage(2, nullKey));
            var nullFaces = new LinkedHashMap<String, List<Integer>>();
            nullFaces.put("a", null);
            assertThrows(NullPointerException.class, () -> cache.stage(2, nullFaces));
            assertThrows(NullPointerException.class, () -> cache.stage(2,
                    Map.of("a", java.util.Arrays.asList(1, null))));
            assertThrows(IllegalArgumentException.class, () -> cache.pump(-1, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> cache.pump(1, -1, 1));
            assertThrows(IllegalArgumentException.class, () -> cache.pump(1, 1, -1));
            assertTrue(cache.pump(100, 10, UNLIMITED).ready());
            assertTrue(cache.commit(1));
        }
    }

    @Test void allStateIsOwnerThreadOnlyAndReentrantMutationsCannotInvalidatePumpState() {
        var ops = new Ops<String>();
        try (var cache = cache(ops)) {
            CompletableFuture.runAsync(() -> {
                assertThrows(IllegalStateException.class, () -> cache.stage(0, Map.of()));
                assertThrows(IllegalStateException.class, () -> cache.ready(0));
                assertThrows(IllegalStateException.class, cache::currentResources);
                assertThrows(IllegalStateException.class, cache::residentBytes);
                assertThrows(IllegalStateException.class, cache::close);
            }).join();
            cache.stage(0, Map.of("a", List.of(1)));
            ops.onUpload = () -> assertThrows(IllegalStateException.class, cache::cancelStage);
            assertTrue(cache.pump(100, 1, UNLIMITED).ready());
            assertTrue(cache.commit(0));
        }
    }

    @Test void zeroByteOwnersAndLongMaxAccountingDoNotOverflowAdmission() {
        var zeroOps = new Ops<String>();
        try (var cache = new DhIncrementalTileCache<>(zeroOps, 0)) {
            cache.stage(0, Map.of("a", List.of()));
            assertTrue(cache.pump(0, 1, UNLIMITED).ready());
            assertTrue(cache.commit(0));
            assertEquals(0, cache.currentBytes());
        }
        var ops = new Ops<String>();
        ops.estimateOverride = Long.MAX_VALUE;
        ops.actualExtra = Long.MAX_VALUE - 10;
        try (var cache = new DhIncrementalTileCache<>(ops, Long.MAX_VALUE)) {
            publish(cache, 0, Map.of("a", List.of(1)));
            assertEquals(Long.MAX_VALUE, cache.currentBytes());
            cache.stage(1, Map.of("b", List.of(1)));
            assertEquals(RESIDENT_BYTES, cache.pump(UNLIMITED, 1, UNLIMITED).blocked());
            assertEquals(1, ops.uploads);
        }
    }
}
