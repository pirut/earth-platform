package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.minecraft.diagnostics.PerfFailures;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ServerCubeWorldResilienceTest {

    @Test
    void wrappedMissingTerrainExceptionClassifiedViaCauseChainWithCycleGuard() {
        MissingTerrainException root = new MissingTerrainException("tile unreachable");
        RuntimeException wrapped = new CompletionException(new RuntimeException("outer", root));
        assertTrue(ServerCubeWorld.FailureRetries.isTransientTerrainFailure(root));
        assertTrue(ServerCubeWorld.FailureRetries.isTransientTerrainFailure(wrapped));
        assertSame(root, ServerCubeWorld.FailureRetries.rootCause(wrapped));

        // Deterministic exception without MissingTerrainException in cause chain.
        IllegalStateException bug = new IllegalStateException("modded biome NPE");
        RuntimeException wrappedBug = new CompletionException(bug);
        assertFalse(ServerCubeWorld.FailureRetries.isTransientTerrainFailure(wrappedBug));
        assertSame(bug, ServerCubeWorld.FailureRetries.rootCause(wrappedBug));

        // Self-referential / cyclic cause chain must terminate safely without infinite loop.
        RuntimeException cycle = new RuntimeException("cycle") {
            @Override public synchronized Throwable getCause() { return this; }
        };
        assertFalse(ServerCubeWorld.FailureRetries.isTransientTerrainFailure(cycle));
        assertSame(cycle, ServerCubeWorld.FailureRetries.rootCause(cycle));
    }

    @Test
    void exponentialBackoffLruEvictionAndPerfFailuresRecording() {
        ServerCubeWorld.FailureRetries retries = new ServerCubeWorld.FailureRetries(4);
        long transientKey = CubeKey.pack(1, 2, 3);
        RuntimeException wrappedMissing = new RuntimeException(new MissingTerrainException("offline"));

        // Transient schedule: 5s -> 10s -> 20s -> 40s -> 60s -> 60s (100, 200, 400, 800, 1200, 1200 ticks).
        int[] expectedTransient = {100, 200, 400, 800, 1200, 1200};
        int tick = 10;
        for (int i = 0; i < expectedTransient.length; i++) {
            var state = retries.recordFailure(transientKey, tick, wrappedMissing);
            assertFalse(state.deterministic());
            assertEquals(i + 1, state.attempts());
            assertEquals(tick + expectedTransient[i], state.nextRetryTick());
            assertTrue(retries.isBlocked(transientKey, state.nextRetryTick() - 1));
            assertFalse(retries.isBlocked(transientKey, state.nextRetryTick()));
            tick = state.nextRetryTick();
        }
        retries.recordSuccess(transientKey);
        assertNull(retries.state(transientKey));

        // Deterministic schedule: 30s -> 120s -> 300s -> 300s (finite per-session backoff, never permanent).
        long detKey = CubeKey.pack(4, 5, 6);
        IllegalStateException detFailure = new IllegalStateException("deterministic generator bug");
        int[] expectedDet = {600, 2400, 6000, 6000};
        tick = 0;
        for (int i = 0; i < expectedDet.length; i++) {
            var state = retries.recordFailure(detKey, tick, detFailure);
            assertTrue(state.deterministic());
            assertEquals(i + 1, state.attempts());
            assertEquals(tick + expectedDet[i], state.nextRetryTick());
            assertTrue(state.nextRetryTick() < Integer.MAX_VALUE, "quarantine must remain finite per-session");
            tick = state.nextRetryTick();
        }

        // Bounded LRU evicts only the oldest entry instead of wiping the entire retry map.
        retries.recordFailure(CubeKey.pack(10, 0, 0), 0, wrappedMissing);
        retries.recordFailure(CubeKey.pack(11, 0, 0), 0, wrappedMissing);
        retries.recordFailure(CubeKey.pack(12, 0, 0), 0, wrappedMissing);
        assertEquals(4, retries.size());
        // Touch detKey so CubeKey(10,0,0) is least recently used.
        assertTrue(retries.isBlocked(detKey, 1));
        retries.recordFailure(CubeKey.pack(13, 0, 0), 0, wrappedMissing);
        assertEquals(4, retries.size(), "LRU stays bounded without clearing all entries");
        assertNull(retries.state(CubeKey.pack(10, 0, 0)), "oldest unvisited key evicted");
        assertNotNull(retries.state(detKey), "recently checked key retained");
        assertNotNull(retries.state(CubeKey.pack(13, 0, 0)));

        // Distinct failure signatures rate-limit independently; PerfFailures records unwrapped type.
        assertTrue(retries.shouldLog(100, wrappedMissing));
        assertFalse(retries.shouldLog(150, wrappedMissing), "same signature rate-limited");
        assertTrue(retries.shouldLog(150, detFailure), "distinct exception signature logs immediately");
        assertTrue(retries.shouldLog(300, wrappedMissing), "same signature logs again after rate-limit window");

        long before = PerfFailures.count(PerfFailures.Stage.FAR_GENERATION);
        PerfFailures.record(PerfFailures.Stage.FAR_GENERATION, ServerCubeWorld.FailureRetries.rootCause(detFailure));
        assertEquals(before + 1, PerfFailures.count(PerfFailures.Stage.FAR_GENERATION));
        assertEquals(IllegalStateException.class.getName(), PerfFailures.type(PerfFailures.Stage.FAR_GENERATION));
    }

    @Test
    void topPrimerCapsRetriesAndEvictsFailedFuturesSoPendingNeverLeaks() {
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean fail = new AtomicBoolean(true);
        ServerCubeWorld.TopPrimer primer = new ServerCubeWorld.TopPrimer(
                (cx, cz) -> {
                    calls.incrementAndGet();
                    if (fail.get()) throw new RuntimeException(new MissingTerrainException("transient"));
                    return new int[256];
                },
                Runnable::run,
                tries -> Runnable::run,
                () -> false,
                3);

        // Exhausts initial attempt + 3 retries = 4 calls, completes exceptionally, and evicts from primed!
        CompletableFuture<int[]> failedFuture = primer.prime(2, 3);
        assertTrue(failedFuture.isCompletedExceptionally());
        assertEquals(4, calls.get());
        assertEquals(0, primer.size(), "failed future must be evicted from primed so future visits retry");

        // Simulating ServerCubeWorld's pending tracking: whenComplete receives the failure and removes key.
        AtomicBoolean pendingRemoved = new AtomicBoolean();
        failedFuture.whenComplete((tops, err) -> {
            if (err != null) pendingRemoved.set(true);
        });
        assertTrue(pendingRemoved.get(), "pending slot must be released on primeTops failure");

        // Subsequent call after data returns succeeds cleanly.
        fail.set(false);
        CompletableFuture<int[]> okFuture = primer.prime(2, 3);
        assertTrue(okFuture.isDone() && !okFuture.isCompletedExceptionally());
        assertEquals(1, primer.size());
        assertNotNull(primer.getIfReady(CubeKey.column(2, 3)));

        // Deterministic non-transient runtime exception fails immediately on first attempt without retrying.
        AtomicInteger detCalls = new AtomicInteger();
        ServerCubeWorld.TopPrimer detPrimer = new ServerCubeWorld.TopPrimer(
                (cx, cz) -> {
                    detCalls.incrementAndGet();
                    throw new NullPointerException("bug");
                },
                Runnable::run,
                tries -> Runnable::run,
                () -> false,
                3);
        CompletableFuture<int[]> detFuture = detPrimer.prime(7, 8);
        assertTrue(detFuture.isCompletedExceptionally());
        assertEquals(1, detCalls.get(), "deterministic prime failure must not retry blindly");
        assertEquals(0, detPrimer.size());
        assertThrows(ExecutionException.class, detFuture::get);
    }

    @Test
    void untouchedStoreQueuePreservesDirtyUntilCommitAndDrainsOnSaveAndClose() {
        Map<Long, Cube> loaded = new HashMap<>();
        Map<Long, String> written = new HashMap<>();
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicBoolean metadataSame = new AtomicBoolean(true);
        List<Runnable> workerTasks = new ArrayList<>();
        List<Runnable> serverTasks = new ArrayList<>();

        var queue = new ServerCubeWorld.UntouchedStoreQueue<String>(
                closed::get,
                loaded::get,
                written::put,
                entry -> metadataSame.get(),
                (key, err) -> fail("Unexpected store error: " + err));

        Cube cube1 = new Cube(1, 2, 3, null);
        cube1.modified = true;
        cube1.dirty = true;
        loaded.put(cube1.key, cube1);

        // Enqueue async untouched write: cube.dirty MUST remain true until commit completes!
        queue.enqueue(cube1, true, List.of(), () -> "tag-1", workerTasks::add, serverTasks::add);
        assertTrue(cube1.dirty, "dirty must not be cleared before serialization commits");
        assertEquals(1, queue.pendingCount());

        // Suppose cube1 is unloaded (removed from loaded) BEFORE worker runs, then save()/close() drains.
        loaded.remove(cube1.key);
        queue.drainAll();
        assertEquals("tag-1", written.get(cube1.key), "drainAll must persist unloaded pending untouched cube");
        assertFalse(cube1.dirty, "dirty cleared once storage.put succeeded");
        assertEquals(0, queue.pendingCount());

        // Running the stale worker task afterwards is a no-op (already claimed by drainAll).
        workerTasks.forEach(Runnable::run);
        serverTasks.forEach(Runnable::run);
        assertEquals(1, written.size());
        workerTasks.clear();
        serverTasks.clear();

        // If cube metadata changes while encoding is in flight, dirty stays true so the change is not lost!
        Cube cube2 = new Cube(4, 5, 6, null);
        cube2.modified = true;
        cube2.dirty = true;
        loaded.put(cube2.key, cube2);
        metadataSame.set(false);
        queue.enqueue(cube2, true, List.of(), () -> "tag-2-old", workerTasks::add, serverTasks::add);
        workerTasks.forEach(Runnable::run);
        serverTasks.forEach(Runnable::run);
        assertEquals("tag-2-old", written.get(cube2.key));
        assertTrue(cube2.dirty, "cube dirtied with new metadata mid-flight must remain dirty");

        // If cube is edited mid-flight, stale untouched write must NOT overwrite storage!
        workerTasks.clear();
        serverTasks.clear();
        Cube cube3 = new Cube(7, 8, 9, null);
        cube3.modified = true;
        cube3.dirty = true;
        loaded.put(cube3.key, cube3);
        metadataSame.set(true);
        queue.enqueue(cube3, false, List.of(), () -> "tag-3-untouched", workerTasks::add, serverTasks::add);
        cube3.markModified(); // edited = true
        workerTasks.forEach(Runnable::run);
        serverTasks.forEach(Runnable::run);
        assertFalse(written.containsKey(cube3.key), "stale untouched write must not overwrite edited cube");
        assertTrue(cube3.dirty);
    }
}
