package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.geography.globe.CopernicusSource;
import io.earthplatform.geography.globe.HostBackoff;
import io.earthplatform.minecraft.diagnostics.PerfFailures;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void exponentialBackoffLruEvictionAndNearGenerationPerfFailuresRecording() {
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

        // Bounded LRU (primitive Long2ObjectLinkedOpenHashMap) evicts only the oldest entry.
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

        // Distinct failure signatures rate-limit independently.
        assertTrue(retries.shouldLog(100, wrappedMissing));
        assertFalse(retries.shouldLog(150, wrappedMissing), "same signature rate-limited");
        assertTrue(retries.shouldLog(150, detFailure), "distinct exception signature logs immediately");
        assertTrue(retries.shouldLog(300, wrappedMissing), "same signature logs again after rate-limit window");

        // Production near-cube failure path (adoptReady -> recordNearFailure) records NEAR_GENERATION, NOT FAR_GENERATION!
        long nearBefore = PerfFailures.count(PerfFailures.Stage.NEAR_GENERATION);
        long farBefore = PerfFailures.count(PerfFailures.Stage.FAR_GENERATION);
        var recorded = ServerCubeWorld.recordNearFailure(
                new ServerCubeWorld.FailureRetries(8),
                CubeKey.pack(20, 1, 2),
                400,
                1L,
                new CompletionException(detFailure));
        assertTrue(recorded.deterministic());
        assertEquals(nearBefore + 1, PerfFailures.count(PerfFailures.Stage.NEAR_GENERATION), "near failure must record NEAR_GENERATION");
        assertEquals(IllegalStateException.class.getName(), PerfFailures.type(PerfFailures.Stage.NEAR_GENERATION));
        assertEquals(farBefore, PerfFailures.count(PerfFailures.Stage.FAR_GENERATION), "near failure must NOT increment FAR_GENERATION");
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
    void stalledHttpBodyReleasesTopPrimerAndServerCubeWorldPendingSlot(@TempDir Path cache) throws Exception {
        CountDownLatch releaseStall = new CountDownLatch(1);
        AtomicBoolean stall = new AtomicBoolean(true);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/", exchange -> {
            if (stall.get()) {
                exchange.getResponseHeaders().set("Content-Range", "bytes 0-16383/16384");
                exchange.sendResponseHeaders(206, 16384);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write('I');
                    out.flush();
                    releaseStall.await(15, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    // Expected once RangeFiles body-read deadline aborts the stream.
                }
                return;
            }
            // Open ocean 404 -> CopernicusSource returns covers=false cleanly.
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        ExecutorService ioPool = Executors.newFixedThreadPool(2);
        try (CopernicusSource source = new CopernicusSource(base, cache, true, 4, 1, Duration.ofMillis(180))) {
            long key = CubeKey.pack(3, 4, 5);
            LongOpenHashSet pending = new LongOpenHashSet();
            var untouched = new ServerCubeWorld.UntouchedStoreQueue<CompoundTag>(
                    () -> false, k -> null, (k, tag) -> {}, e -> true, (k, err) -> {});
            ServerCubeWorld.TopPrimer primer = new ServerCubeWorld.TopPrimer(
                    (cx, cz) -> {
                        source.covers(25.5, 35.5);
                        return new int[256];
                    },
                    ioPool,
                    tries -> Runnable::run,
                    () -> false,
                    0);
            CountDownLatch released = new CountDownLatch(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();

            long startNanos = System.nanoTime();
            assertTrue(ServerCubeWorld.submitReadRequest(key, pending, untouched, Runnable::run, () ->
                    primer.prime(3, 5).whenComplete((tops, err) -> {
                        observed.set(err);
                        pending.remove(key);
                        released.countDown();
                    })));
            assertTrue(released.await(6, TimeUnit.SECONDS), "stalled body must abort and release ServerCubeWorld pending slot");
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            assertTrue(elapsedMs < 6_000, "body deadline must prevent hang, took " + elapsedMs + " ms");
            assertNotNull(observed.get());
            assertFalse(pending.contains(key), "pending slot must be freed after stalled-body failure");
            assertEquals(0, primer.size(), "failed TopPrimer entry must be evicted");

            // Once server stops stalling and HostBackoff probes recovery, the pending slot and single transfer permit are both available again!
            stall.set(false);
            if (HostBackoff.anyDown()) {
                Thread.sleep(5_150L);
                try { source.covers(25.5, 35.5); } catch (MissingTerrainException ignored) { /* triggers background recovery probe */ }
                for (int i = 0; i < 100 && HostBackoff.anyDown(); i++) Thread.sleep(20);
            }
            CountDownLatch recovered = new CountDownLatch(1);
            AtomicReference<int[]> recoveredTops = new AtomicReference<>();
            assertTrue(ServerCubeWorld.submitReadRequest(key, pending, untouched, Runnable::run, () ->
                    primer.prime(3, 5).whenComplete((tops, err) -> {
                        recoveredTops.set(tops);
                        pending.remove(key);
                        recovered.countDown();
                    })));
            assertTrue(recovered.await(5, TimeUnit.SECONDS));
            assertNotNull(recoveredTops.get(), "pending slot and transfer permit must be reusable after stalled body recovery");
            assertFalse(pending.contains(key));
        } finally {
            releaseStall.countDown();
            ioPool.shutdownNow();
            server.stop(0);
            ((ExecutorService) server.getExecutor()).shutdownNow();
        }
    }

    @Test
    void unloadThenReloadBeforeAsyncCommitPreservesLatestUntouchedSnapshotThroughRequestAndLoadNow(@TempDir Path dir) throws Exception {
        CubeStorage storage = new CubeStorage(dir);
        try {
            long key1 = CubeKey.pack(1, 2, 3);
            long key2 = CubeKey.pack(4, 5, 6);

            // Seed older storage snapshots that still have UNPOPULATED=true and pending ENTITIES.
            CompoundTag stale1 = new CompoundTag();
            stale1.putBoolean(CubeCodec.UNPOPULATED, true);
            ListTag staleEntities = new ListTag();
            CompoundTag sheep = new CompoundTag();
            sheep.putString("id", "minecraft:sheep");
            staleEntities.add(sheep);
            stale1.put(CubeCodec.ENTITIES, staleEntities);
            storage.put(key1, stale1);
            storage.put(key2, stale1.copy());

            Map<Long, Cube> loaded = new HashMap<>();
            List<Runnable> workerTasks = new ArrayList<>();
            List<Runnable> serverTasks = new ArrayList<>();
            var queue = new ServerCubeWorld.UntouchedStoreQueue<CompoundTag>(
                    () -> false,
                    loaded::get,
                    storage::put,
                    entry -> true,
                    (k, err) -> fail("Unexpected store error: " + err));

            // 1. Unload cube1 with cleared UNPOPULATED and cleared ENTITIES, BEFORE async worker runs.
            Cube cube1 = new Cube(1, 2, 3, null);
            cube1.modified = true;
            cube1.dirty = true;
            loaded.put(key1, cube1);
            queue.enqueue(cube1, false, List.of(), () -> {
                CompoundTag latest = new CompoundTag();
                latest.putBoolean(CubeCodec.UNPOPULATED, false);
                return latest;
            }, workerTasks::add, serverTasks::add);
            // Evict cube1 immediately as unload() does, while worker has not run yet!
            loaded.remove(key1);
            assertEquals(1, queue.pendingCount());

            // While worker has not finished encoding cube1, request() MUST defer without blocking or reading stale storage!
            LongOpenHashSet pending = new LongOpenHashSet();
            AtomicReference<CompoundTag> reloadedViaRequest = new AtomicReference<>();
            assertFalse(ServerCubeWorld.submitReadRequest(key1, pending, queue, Runnable::run, () -> {
                fail("must not read stale storage while untouched encode is still pending");
            }));
            assertFalse(pending.contains(key1));
            assertEquals(1, queue.pendingCount());

            // Once worker finishes encoding (even before serverTasks runs), submitReadRequest commits non-blockingly and reads latest snapshot!
            workerTasks.remove(0).run();
            assertTrue(ServerCubeWorld.submitReadRequest(key1, pending, queue, Runnable::run, () -> {
                try { reloadedViaRequest.set(storage.read(key1)); }
                catch (IOException e) { throw new RuntimeException(e); }
            }));
            assertNotNull(reloadedViaRequest.get());
            assertFalse(reloadedViaRequest.get().getBoolean(CubeCodec.UNPOPULATED),
                    "request() reload must see latest untouched snapshot (cleared unpopulated), not stale storage");
            assertTrue(CubeCodec.tags(reloadedViaRequest.get(), CubeCodec.ENTITIES).isEmpty(),
                    "request() reload must see cleared entity state, not stale duplicate spawns");
            assertFalse(cube1.dirty);
            assertEquals(0, queue.pendingCount());

            // 2. Same unload -> immediate reload through loadNow()'s real loadNowUncached path!
            Cube cube2 = new Cube(4, 5, 6, null);
            cube2.modified = true;
            cube2.dirty = true;
            loaded.put(key2, cube2);
            queue.enqueue(cube2, false, List.of(), () -> {
                CompoundTag latest = new CompoundTag();
                latest.putBoolean(CubeCodec.UNPOPULATED, false);
                return latest;
            }, workerTasks::add, serverTasks::add);
            loaded.remove(key2);

            AtomicReference<CompoundTag> reloadedViaLoadNow = new AtomicReference<>();
            Cube adopted = ServerCubeWorld.loadNowUncached(
                    key2,
                    queue,
                    () -> {
                        try { return storage.read(key2); }
                        catch (IOException e) { throw new RuntimeException(e); }
                    },
                    tag -> {
                        reloadedViaLoadNow.set(tag);
                        loaded.put(key2, new Cube(4, 5, 6, null));
                    },
                    entry -> fail("successful drainKey must not need direct re-adoption"),
                    loaded::get);
            assertNotNull(adopted);
            assertNotNull(reloadedViaLoadNow.get());
            assertFalse(reloadedViaLoadNow.get().getBoolean(CubeCodec.UNPOPULATED),
                    "loadNow() must drain key before reading storage so latest untouched snapshot is preserved");
            assertTrue(CubeCodec.tags(reloadedViaLoadNow.get(), CubeCodec.ENTITIES).isEmpty());
            assertFalse(cube2.dirty);

            // Running stale worker/server callbacks afterwards is a safe no-op.
            workerTasks.forEach(Runnable::run);
            serverTasks.forEach(Runnable::run);
        } finally {
            storage.close();
        }
    }

    @Test
    void saveCommitsOnlyReadyEntriesCloseDrainsAllAndFailedEvictedCubeRetriesSafely() throws Exception {
        Map<Long, Cube> loaded = new HashMap<>();
        Map<Long, String> written = new HashMap<>();
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicBoolean metadataSame = new AtomicBoolean(true);
        AtomicBoolean failWriteOnce = new AtomicBoolean(false);
        List<Throwable> errors = new ArrayList<>();
        List<Runnable> workerTasks = new ArrayList<>();
        ConcurrentLinkedQueue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();

        AtomicLong clockNanos = new AtomicLong(1_000_000_000L);
        var queue = new ServerCubeWorld.UntouchedStoreQueue<String>(
                closed::get,
                loaded::get,
                (key, tag) -> {
                    if (failWriteOnce.compareAndSet(true, false)) throw new IOException("transient disk failure");
                    written.put(key, tag);
                },
                entry -> metadataSame.get(),
                (key, err) -> errors.add(err),
                80L,
                clockNanos::get);

        // 1. save() (commitReady) commits ONLY already-done entries without running unclaimed encoders on server thread!
        Cube readyCube = new Cube(1, 2, 3, null);
        readyCube.modified = true;
        readyCube.dirty = true;
        loaded.put(readyCube.key, readyCube);
        AtomicInteger readyEncodeCalls = new AtomicInteger();
        queue.enqueue(readyCube, true, List.of(), () -> {
            readyEncodeCalls.incrementAndGet();
            return "tag-ready";
        }, workerTasks::add, serverTasks::add);

        Cube unclaimedCube = new Cube(2, 3, 4, null);
        unclaimedCube.modified = true;
        unclaimedCube.dirty = true;
        loaded.put(unclaimedCube.key, unclaimedCube);
        AtomicInteger unclaimedEncodeCalls = new AtomicInteger();
        queue.enqueue(unclaimedCube, true, List.of(), () -> {
            unclaimedEncodeCalls.incrementAndGet();
            return "tag-unclaimed";
        }, workerTasks::add, serverTasks::add);

        // Worker encodes ONLY readyCube (server callback not run yet); unclaimedCube is still queued for worker.
        workerTasks.remove(0).run();
        queue.commitReady();
        assertEquals("tag-ready", written.get(readyCube.key), "save() commits already-encoded entry");
        assertFalse(readyCube.dirty);
        assertEquals(0, unclaimedEncodeCalls.get(), "save() must NOT synchronously encode unclaimed untouched cubes on server thread");
        assertTrue(unclaimedCube.dirty, "unclaimed cube stays dirty until worker or close() commits it");
        assertEquals(1, queue.pendingCount());

        // close() (drainAll) drains remaining unclaimed entry synchronously.
        queue.drainAll();
        assertEquals("tag-unclaimed", written.get(unclaimedCube.key), "close() drainAll finishes remaining pending cubes");
        assertEquals(1, unclaimedEncodeCalls.get());
        assertFalse(unclaimedCube.dirty);
        assertEquals(0, queue.pendingCount());
        workerTasks.clear();
        serverTasks.clear();

        // 2. Evicted cube whose first writer.write() throws retains its snapshot and succeeds on the next save() without re-encoding!
        Cube evictedCube = new Cube(3, 4, 5, null);
        evictedCube.modified = true;
        evictedCube.dirty = true;
        loaded.put(evictedCube.key, evictedCube);
        AtomicInteger evictedEncodeCalls = new AtomicInteger();
        queue.enqueue(evictedCube, false, List.of(), () -> {
            evictedEncodeCalls.incrementAndGet();
            return "tag-evicted";
        }, workerTasks::add, serverTasks::add);
        loaded.remove(evictedCube.key); // Evicted!
        failWriteOnce.set(true);
        workerTasks.remove(0).run();
        serverTasks.poll().run(); // First write fails!
        assertEquals(1, errors.size());
        assertFalse(written.containsKey(evictedCube.key));
        assertEquals(1, queue.pendingCount(), "evicted cube snapshot must be retained in pending after transient write failure");
        assertTrue(evictedCube.dirty);

        // Next save() respects backoff until retry delay elapses, then writes cached encoded tag without re-encoding!
        queue.commitReady();
        assertFalse(written.containsKey(evictedCube.key), "immediate save() inside backoff window must not hammer storage");
        clockNanos.addAndGet(ServerCubeWorld.UntouchedStoreQueue.INITIAL_RETRY_BACKOFF_NANOS);
        queue.commitReady();
        assertEquals("tag-evicted", written.get(evictedCube.key), "evicted cube succeeds on next save() after backoff");
        assertEquals(1, evictedEncodeCalls.get(), "write retry must reuse encoded tag without re-encoding");
        assertFalse(evictedCube.dirty);
        assertEquals(0, queue.pendingCount());

        // 3. supersede() cancels pending untouched write cleanly.
        Cube supersededCube = new Cube(4, 5, 6, null);
        supersededCube.modified = true;
        supersededCube.dirty = true;
        loaded.put(supersededCube.key, supersededCube);
        queue.enqueue(supersededCube, false, List.of(), () -> "tag-superseded", workerTasks::add, serverTasks::add);
        queue.supersede(supersededCube.key);
        assertEquals(0, queue.pendingCount());
        workerTasks.remove(0).run();
        serverTasks.poll().run();
        assertFalse(written.containsKey(supersededCube.key), "superseded entry must never write");

        // 4. Worker-claimed encoded.get() branch in drainKey() waits for worker and commits once without re-encoding.
        Cube workerClaimed = new Cube(5, 6, 7, null);
        workerClaimed.modified = true;
        workerClaimed.dirty = true;
        loaded.put(workerClaimed.key, workerClaimed);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerFinish = new CountDownLatch(1);
        AtomicInteger claimedEncodeCalls = new AtomicInteger();
        ExecutorService bg = Executors.newSingleThreadExecutor();
        try {
            queue.enqueue(workerClaimed, false, List.of(), () -> {
                claimedEncodeCalls.incrementAndGet();
                workerStarted.countDown();
                try { workerFinish.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "tag-worker-claimed";
            }, bg, serverTasks::add);
            assertTrue(workerStarted.await(5, TimeUnit.SECONDS));
            bg.submit(() -> {}); // ensure worker is mid-encode
            CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS).execute(workerFinish::countDown);
            queue.drainKey(workerClaimed.key);
            assertEquals("tag-worker-claimed", written.get(workerClaimed.key));
            assertEquals(1, claimedEncodeCalls.get(), "drainKey must await worker-claimed future without re-running encoder");
            assertFalse(workerClaimed.dirty);

            // 5. ExecutionException in worker passes cause straight to fail() without re-running encoder.
            Cube failCube = new Cube(6, 7, 8, null);
            failCube.modified = true;
            failCube.dirty = true;
            loaded.put(failCube.key, failCube);
            AtomicInteger failEncodeCalls = new AtomicInteger();
            IllegalStateException boom = new IllegalStateException("encode boom");
            CountDownLatch failDone = new CountDownLatch(1);
            errors.clear();
            queue.enqueue(failCube, false, List.of(), () -> {
                failEncodeCalls.incrementAndGet();
                try { throw boom; }
                finally { failDone.countDown(); }
            }, bg, serverTasks::add);
            assertTrue(failDone.await(5, TimeUnit.SECONDS));
            // Give worker thread a moment to complete exceptionally before drainAll.
            Thread.sleep(20);
            queue.drainAll();
            assertEquals(1, failEncodeCalls.get(), "ExecutionException must NOT re-run encoder");
            assertEquals(1, errors.size());
            assertSame(boom, errors.get(0));
            // Running queued worker failure callback afterwards must NOT double-log!
            for (Runnable r; (r = serverTasks.poll()) != null; ) r.run();
            assertEquals(1, errors.size(), "worker failure callback must not double-log after drainAll handled it");

            // 6. TimeoutException when worker is still encoding MUST NEVER invoke encoder concurrently!
            Cube slowCube = new Cube(7, 8, 9, null);
            slowCube.modified = true;
            slowCube.dirty = true;
            loaded.put(slowCube.key, slowCube);
            CountDownLatch slowEntered = new CountDownLatch(1);
            CountDownLatch releaseSlow = new CountDownLatch(1);
            AtomicInteger slowEncodeCalls = new AtomicInteger();
            errors.clear();
            queue.enqueue(slowCube, false, List.of(), () -> {
                slowEncodeCalls.incrementAndGet();
                slowEntered.countDown();
                try { releaseSlow.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "tag-slow";
            }, bg, serverTasks::add);
            assertTrue(slowEntered.await(5, TimeUnit.SECONDS));
            queue.drainAll(); // Exceeds 80ms test drainTimeoutMillis while worker holds encoder!
            assertEquals(1, slowEncodeCalls.get(), "TimeoutException must NEVER invoke encoder concurrently with worker");
            assertEquals(1, queue.pendingCount(), "timed-out entry stays pending so worker commit can finish safely");
            assertTrue(slowCube.dirty);
            assertEquals(1, errors.size());
            assertInstanceOf(java.util.concurrent.TimeoutException.class, errors.get(0));

            // Once worker finishes, its server-thread commit writes cleanly!
            releaseSlow.countDown();
            for (int i = 0; i < 100 && serverTasks.isEmpty(); i++) Thread.sleep(10);
            for (Runnable r; (r = serverTasks.poll()) != null; ) r.run();
            assertEquals("tag-slow", written.get(slowCube.key));
            assertFalse(slowCube.dirty);
            assertEquals(0, queue.pendingCount());
        } finally {
            bg.shutdownNow();
        }
    }

    @Test
    void unloadMaintenancePrunesFailedSurveysEvenWhenZeroCubesEvicted() {
        List<Runnable> queued = new ArrayList<>();
        AtomicBoolean failing = new AtomicBoolean(true);
        ColumnSurveys surveys = new ColumnSurveys(true, false, queued::add,
                (column, x, z) -> failing.get()
                        ? new long[] {column, Integer.MIN_VALUE, 0, 0}
                        : new long[] {column, 40, 63, 90});

        long col = CubeKey.column(5, -2);
        // Visit column while survey fails and exhausts all retries.
        surveys.lowest(5, -2, ColumnSurveys.ANY);
        for (Runnable task : new ArrayList<>(queued)) task.run();
        queued.clear();
        surveys.drain(0);
        int tick = 0;
        for (int retry = 1; retry <= ColumnSurveys.RETRIES; retry++) {
            tick += ColumnSurveys.RETRY_TICKS << (retry - 1);
            surveys.drain(tick);
            surveys.lowest(5, -2, ColumnSurveys.ANY);
            for (Runnable task : new ArrayList<>(queued)) task.run();
            queued.clear();
            surveys.drain(tick);
        }
        // Retries are exhausted while player stays nearby.
        surveys.drain(tick + 100_000);
        surveys.lowest(5, -2, ColumnSurveys.ANY);
        assertTrue(queued.isEmpty(), "survey retries exhausted while player never departed");

        // Player departs, with ZERO cubes eligible to evict!
        Long2ObjectOpenHashMap<int[]> tops = new Long2ObjectOpenHashMap<>();
        tops.put(col, new int[256]);
        AtomicBoolean flushed = new AtomicBoolean();
        ServerCubeWorld.unloadMaintenance(List.of(), cube -> fail("no cubes evicted"), tops, surveys,
                column -> column != col, () -> flushed.set(true));
        assertFalse(flushed.get(), "storage.flushLater not called when zero cubes evicted");
        assertFalse(tops.containsKey(col), "sky tops pruned even when zero cubes evicted");

        // Player returns after terrain data recovers: survey MUST retry afresh and succeed!
        failing.set(false);
        surveys.lowest(5, -2, ColumnSurveys.ANY);
        assertEquals(1, queued.size(), "failed survey must retry on return after zero-cube unload maintenance");
        for (Runnable task : new ArrayList<>(queued)) task.run();
        queued.clear();
        surveys.drain(tick + 100_001);
        assertEquals(SurfaceFlight.bounds(63, 90), surveys.surfaceBounds(5, -2));
    }

    @Test
    void asyncRequestDefersOnFailedOrInFlightDrainAndLoadNowReadoptsOrFailsWithoutStaleStorage() throws Exception {
        Map<Long, Cube> loaded = new HashMap<>();
        Map<Long, String> storage = new HashMap<>();
        AtomicBoolean failWrites = new AtomicBoolean(true);
        List<Throwable> errors = new ArrayList<>();
        List<Runnable> workerTasks = new ArrayList<>();
        ConcurrentLinkedQueue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();

        AtomicLong clockNanos = new AtomicLong(1_000_000_000L);
        var queue = new ServerCubeWorld.UntouchedStoreQueue<String>(
                () -> false,
                loaded::get,
                (key, tag) -> {
                    if (failWrites.get()) throw new IOException("transient storage write failure");
                    storage.put(key, tag);
                },
                entry -> true,
                (key, err) -> errors.add(err),
                80L,
                clockNanos::get);

        // 1. Async request() when pending write fails: defers without reading stale storage; succeeds once committed!
        Cube cube1 = new Cube(10, 20, 30, null);
        cube1.modified = true;
        cube1.dirty = true;
        storage.put(cube1.key, "stale-v1");
        loaded.put(cube1.key, cube1);
        queue.enqueue(cube1, false, List.of(), () -> "fresh-v2", workerTasks::add, serverTasks::add);
        loaded.remove(cube1.key); // Evicted
        workerTasks.remove(0).run(); // Worker finished encoding

        LongOpenHashSet pending = new LongOpenHashSet();
        AtomicInteger readDispatches = new AtomicInteger();
        assertFalse(ServerCubeWorld.submitReadRequest(cube1.key, pending, queue, Runnable::run, readDispatches::incrementAndGet),
                "async request() must defer when pending untouched write fails");
        assertEquals(0, readDispatches.get(), "must NEVER read stale storage after failed tryDrainKeyNow");
        assertFalse(pending.contains(cube1.key));
        assertEquals(1, queue.pendingCount());

        // Storage recovers: once retry backoff elapses, next async request() commits non-blockingly and reloads fresh-v2!
        failWrites.set(false);
        assertFalse(ServerCubeWorld.submitReadRequest(cube1.key, pending, queue, Runnable::run, readDispatches::incrementAndGet),
                "async request() inside backoff window must defer without hammering storage");
        clockNanos.addAndGet(ServerCubeWorld.UntouchedStoreQueue.INITIAL_RETRY_BACKOFF_NANOS);
        AtomicReference<String> reloaded = new AtomicReference<>();
        assertTrue(ServerCubeWorld.submitReadRequest(cube1.key, pending, queue, Runnable::run, () -> {
            readDispatches.incrementAndGet();
            reloaded.set(storage.get(cube1.key));
        }));
        assertEquals(1, readDispatches.get());
        assertEquals("fresh-v2", reloaded.get());
        assertFalse(cube1.dirty);
        assertEquals(0, queue.pendingCount());

        // 2. Sync loadNowUncached() when drainKey() fails on write error (encode finished, no worker in flight):
        // MUST re-adopt entry.cube directly with dirty=true and NEVER read stale storage!
        failWrites.set(true);
        Cube cube2 = new Cube(11, 21, 31, null);
        cube2.modified = true;
        cube2.dirty = true;
        storage.put(cube2.key, "stale-cube2");
        loaded.put(cube2.key, cube2);
        queue.enqueue(cube2, false, List.of(), () -> "fresh-cube2", workerTasks::add, serverTasks::add);
        loaded.remove(cube2.key);

        AtomicInteger producerCalls = new AtomicInteger();
        Cube readopted = ServerCubeWorld.loadNowUncached(
                cube2.key,
                queue,
                () -> {
                    producerCalls.incrementAndGet();
                    return storage.get(cube2.key);
                },
                tag -> fail("must not adopt stale storage when drainKey failed"),
                entry -> loaded.put(entry.key(), entry.cube()),
                loaded::get);
        assertSame(cube2, readopted, "loadNow must re-adopt authoritative pending cube directly when write fails");
        assertEquals(0, producerCalls.get(), "loadNow must NEVER read stale storage when drainKey fails");
        assertTrue(cube2.dirty, "re-adopted cube must stay dirty so it is saved later");
        assertEquals(0, queue.pendingCount(), "re-adopted entry must be removed from pending");

        // 3. Sync loadNowUncached() when worker encode is still in flight and times out:
        // MUST throw cleanly rather than touching PalettedContainer concurrently OR reading stale storage!
        Cube cube3 = new Cube(12, 22, 32, null);
        cube3.modified = true;
        cube3.dirty = true;
        storage.put(cube3.key, "stale-cube3");
        loaded.put(cube3.key, cube3);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService bg = Executors.newSingleThreadExecutor();
        try {
            queue.enqueue(cube3, false, List.of(), () -> {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "fresh-cube3";
            }, bg, serverTasks::add);
            loaded.remove(cube3.key);
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            // Async request() while worker is in flight returns false immediately without blocking!
            long t0 = System.nanoTime();
            assertFalse(ServerCubeWorld.submitReadRequest(cube3.key, pending, queue, Runnable::run,
                    () -> fail("must not read while worker encode is in flight")));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 50,
                    "submitReadRequest must not block server thread while worker encode is in flight");

            // Sync loadNowUncached() times out and throws without calling producer or re-adopting!
            assertThrows(IllegalStateException.class, () -> ServerCubeWorld.loadNowUncached(
                    cube3.key,
                    queue,
                    () -> {
                        fail("loadNow must NEVER read stale storage after drainKey timeout");
                        return null;
                    },
                    tag -> fail("must not adopt stale storage"),
                    entry -> fail("must not re-adopt cube while worker is still encoding its PalettedContainer"),
                    loaded::get));
            assertEquals(1, queue.pendingCount(), "timed-out entry remains safely in pending for worker");
        } finally {
            release.countDown();
            bg.shutdownNow();
        }
    }

    @Test
    void pregenerateNeverOverwritesLoadedPendingOrCommittedUntouchedSnapshot(@TempDir Path dir) throws Exception {
        CubeStorage storage = new CubeStorage(dir);
        try {
            long keyCommittedBetween = CubeKey.pack(2, 3, 4);
            long keyPending = CubeKey.pack(2, 4, 4);
            long keyLoaded = CubeKey.pack(2, 5, 4);
            long keyEmpty = CubeKey.pack(2, 6, 4);

            Map<Long, Cube> loaded = new HashMap<>();
            List<Runnable> workerTasks = new ArrayList<>();
            List<Runnable> serverTasks = new ArrayList<>();
            var queue = new ServerCubeWorld.UntouchedStoreQueue<CompoundTag>(
                    () -> false,
                    loaded::get,
                    storage::put,
                    entry -> true,
                    (k, err) -> fail("Unexpected store error: " + err));

            // 1. Pre-check filters out loaded keys and still-pending keys before off-thread storage read.
            Cube loadedCube = new Cube(2, 5, 4, null);
            loaded.put(keyLoaded, loadedCube);
            Cube pendingCube = new Cube(2, 4, 4, null);
            pendingCube.modified = true;
            pendingCube.dirty = true;
            queue.enqueue(pendingCube, false, List.of(), () -> {
                CompoundTag tag = new CompoundTag();
                tag.putString("origin", "pending-untouched");
                return tag;
            }, workerTasks::add, serverTasks::add);

            LongArrayList candidates = LongArrayList.of(keyCommittedBetween, keyPending, keyLoaded, keyEmpty);
            LongArrayList allowed = ServerCubeWorld.filterPregenerateKeys(candidates, () -> false, loaded::containsKey, queue);
            assertEquals(LongArrayList.of(keyCommittedBetween, keyEmpty), allowed,
                    "pregen server-thread pre-check must exclude loaded and still-pending keys");

            // 2. Simulate an untouched snapshot committing to storage BETWEEN pregen's off-thread read and server-thread write!
            CompoundTag authoritativeSnapshot = new CompoundTag();
            authoritativeSnapshot.putBoolean(CubeCodec.UNPOPULATED, false);
            authoritativeSnapshot.putString("origin", "untouched-snapshot");
            storage.put(keyCommittedBetween, authoritativeSnapshot);

            CompoundTag pregenTag1 = new CompoundTag();
            pregenTag1.putBoolean(CubeCodec.UNPOPULATED, true);
            pregenTag1.putString("origin", "pregen");
            CompoundTag pregenTag2 = new CompoundTag();
            pregenTag2.putString("origin", "pregen-empty");

            int stored = ServerCubeWorld.commitPregenerated(
                    LongArrayList.of(keyCommittedBetween, keyPending, keyLoaded, keyEmpty),
                    List.of(pregenTag1, pregenTag1.copy(), pregenTag1.copy(), pregenTag2),
                    () -> false,
                    loaded::containsKey,
                    queue,
                    storage);
            assertEquals(1, stored, "only genuinely absent key should be stored by pregen");
            assertEquals("untouched-snapshot", storage.read(keyCommittedBetween).getString("origin"),
                    "pregenerate() must NEVER overwrite an untouched snapshot committed between read and write");
            assertFalse(storage.read(keyCommittedBetween).getBoolean(CubeCodec.UNPOPULATED));
            assertNull(storage.read(keyPending), "still-pending key must not be overwritten by pregen");
            assertNull(storage.read(keyLoaded), "loaded key must not be overwritten by pregen");
            assertEquals("pregen-empty", storage.read(keyEmpty).getString("origin"));
        } finally {
            storage.close();
        }
    }

    @Test
    void saveResubmitsFailedAndRejectedEncodesOffThreadAndCloseRetriesOrSurfacesShutdownFailure() throws Exception {
        Map<Long, Cube> loaded = new HashMap<>();
        Map<Long, String> written = new HashMap<>();
        AtomicInteger remainingWriteFailures = new AtomicInteger(0);
        AtomicBoolean rejectWorker = new AtomicBoolean(false);
        List<Throwable> errors = new ArrayList<>();
        List<Runnable> workerTasks = new ArrayList<>();
        ConcurrentLinkedQueue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();

        AtomicLong clockNanos = new AtomicLong(1_000_000_000L);
        var queue = new ServerCubeWorld.UntouchedStoreQueue<String>(
                () -> false,
                loaded::get,
                (key, tag) -> {
                    if (remainingWriteFailures.getAndDecrement() > 0) throw new IOException("transient disk write error");
                    written.put(key, tag);
                },
                entry -> true,
                (key, err) -> errors.add(err),
                80L,
                clockNanos::get);

        // 1. save() (commitReady) resubmits encode-failed & rejected entries off-thread without calling encoder on server thread!
        Cube failEncodeCube = new Cube(20, 30, 40, null);
        failEncodeCube.modified = true;
        failEncodeCube.dirty = true;
        AtomicBoolean failFirstEncode = new AtomicBoolean(true);
        AtomicInteger failEncodeCalls = new AtomicInteger();
        queue.enqueue(failEncodeCube, false, List.of(), () -> {
            failEncodeCalls.incrementAndGet();
            if (failFirstEncode.compareAndSet(true, false)) throw new RuntimeException("transient encode error");
            return "recovered-encode";
        }, workerTasks::add, serverTasks::add);

        Cube rejectedCube = new Cube(21, 31, 41, null);
        rejectedCube.modified = true;
        rejectedCube.dirty = true;
        AtomicInteger rejectedEncodeCalls = new AtomicInteger();
        rejectWorker.set(true);
        queue.enqueue(rejectedCube, false, List.of(), () -> {
            rejectedEncodeCalls.incrementAndGet();
            return "recovered-rejected";
        }, task -> {
            if (rejectWorker.get()) throw new RejectedExecutionException("pool busy");
            workerTasks.add(task);
        }, serverTasks::add);

        // First worker attempt for failEncodeCube throws -> server callback retains it as encodeFailed (evicted cube).
        workerTasks.remove(0).run();
        serverTasks.poll().run();
        assertEquals(1, failEncodeCalls.get());
        assertEquals(0, rejectedEncodeCalls.get());

        // save() (commitReady) respects backoff, then MUST resubmit BOTH off-thread and NEVER invoke encoder.get() on server thread!
        rejectWorker.set(false);
        queue.commitReady();
        assertTrue(workerTasks.isEmpty(), "save() must not resubmit before retry backoff expires");
        clockNanos.addAndGet(ServerCubeWorld.UntouchedStoreQueue.INITIAL_RETRY_BACKOFF_NANOS);
        queue.commitReady();
        assertEquals(1, failEncodeCalls.get(), "save() must NEVER run encode-failed encoder synchronously on server thread");
        assertEquals(0, rejectedEncodeCalls.get(), "save() must NEVER run rejected encoder synchronously on server thread");
        assertEquals(2, workerTasks.size(), "save() must resubmit both encode-failed and rejected entries to worker pool");

        // Now workers run off-thread and commit cleanly on next save().
        for (Runnable task : new ArrayList<>(workerTasks)) task.run();
        workerTasks.clear();
        queue.commitReady();
        assertEquals("recovered-encode", written.get(failEncodeCube.key));
        assertEquals("recovered-rejected", written.get(rejectedCube.key));
        assertFalse(failEncodeCube.dirty);
        assertFalse(rejectedCube.dirty);
        assertEquals(0, queue.pendingCount());

        // 2. close() (finishShutdownDrain) executes remaining write retries BEFORE closing storage!
        Cube retryOnClose = new Cube(22, 32, 42, null);
        retryOnClose.modified = true;
        retryOnClose.dirty = true;
        queue.enqueue(retryOnClose, false, List.of(), () -> "saved-on-close-retry", workerTasks::add, serverTasks::add);
        remainingWriteFailures.set(2); // Fails twice transiently, succeeds on 3rd attempt!
        AtomicBoolean storageClosed = new AtomicBoolean();
        AtomicBoolean writtenBeforeClose = new AtomicBoolean();
        ServerCubeWorld.finishShutdownDrain(queue, () -> {
            writtenBeforeClose.set("saved-on-close-retry".equals(written.get(retryOnClose.key)));
            storageClosed.set(true);
        });
        assertTrue(storageClosed.get());
        assertTrue(writtenBeforeClose.get(), "close() must retry transient write failures and commit BEFORE closing storage");
        assertFalse(retryOnClose.dirty);
        assertEquals(0, queue.pendingCount());

        // 3. close() (finishShutdownDrain) on persistent write failure still closes storage AND surfaces IOException!
        Cube persistentFail = new Cube(23, 33, 43, null);
        persistentFail.modified = true;
        persistentFail.dirty = true;
        queue.enqueue(persistentFail, false, List.of(), () -> "never-writes", workerTasks::add, serverTasks::add);
        remainingWriteFailures.set(100);
        AtomicBoolean storageClosedOnFail = new AtomicBoolean();
        IOException surfaced = assertThrows(IOException.class,
                () -> ServerCubeWorld.finishShutdownDrain(queue, () -> storageClosedOnFail.set(true)));
        assertTrue(storageClosedOnFail.get(), "storage must still be closed even when shutdown drain fails");
        assertTrue(surfaced.getMessage().contains("1 pending untouched cube"), surfaced.getMessage());
        assertInstanceOf(IOException.class, surfaced.getCause());
    }

    @Test
    void evictedSnapshotRetainedPastExhaustedRetriesWithBoundedBackoffAndProtectedFromPregenAndStaleReload(@TempDir Path worldDir) throws Exception {
        Map<Long, Cube> loaded = new HashMap<>();
        AtomicInteger writeAttempts = new AtomicInteger();
        AtomicInteger encodeAttempts = new AtomicInteger();
        AtomicLong clockNanos = new AtomicLong(1_000_000_000L);
        List<Throwable> errors = new ArrayList<>();
        List<Runnable> workerTasks = new ArrayList<>();
        ConcurrentLinkedQueue<Runnable> serverTasks = new ConcurrentLinkedQueue<>();

        var queue = new ServerCubeWorld.UntouchedStoreQueue<String>(
                () -> false,
                loaded::get,
                (key, tag) -> {
                    writeAttempts.incrementAndGet();
                    throw new IOException("persistent disk error");
                },
                entry -> true,
                (key, err) -> errors.add(err),
                80L,
                clockNanos::get);

        // Cube A: evicted cube experiencing 6 consecutive write failures (> MAX_EVICTED_RETRIES = 3).
        Cube writeFailCube = new Cube(30, 40, 50, null);
        writeFailCube.modified = true;
        writeFailCube.dirty = true;
        queue.enqueue(writeFailCube, false, List.of(), () -> "fresh-write-fail", workerTasks::add, serverTasks::add);
        workerTasks.remove(0).run();
        serverTasks.poll().run(); // Failure #1 -> 1s backoff
        assertEquals(1, writeAttempts.get());
        assertTrue(queue.hasPending(writeFailCube.key));

        // Immediate repeated commitReady() & tryDrainKeyNow() calls inside backoff MUST NOT hammer storage!
        for (int i = 0; i < 10; i++) {
            queue.commitReady();
            assertFalse(queue.tryDrainKeyNow(writeFailCube.key));
        }
        assertEquals(1, writeAttempts.get(), "backoff must prevent per-tick write retry hammering");

        // Advance clock through exponential backoff steps (1s, 2s, 4s, 8s, 16s -> 6 total failures, past old 4-try drop limit).
        long[] expectedDelaysSec = {1, 2, 4, 8, 16};
        for (int i = 0; i < expectedDelaysSec.length; i++) {
            long delayNanos = TimeUnit.SECONDS.toNanos(expectedDelaysSec[i]);
            clockNanos.addAndGet(delayNanos - 1L);
            queue.commitReady();
            assertFalse(queue.tryDrainKeyNow(writeFailCube.key));
            assertEquals(i + 1, writeAttempts.get(), "must not retry 1ns before backoff step " + expectedDelaysSec[i] + "s expires");

            clockNanos.addAndGet(1L);
            assertFalse(queue.tryDrainKeyNow(writeFailCube.key));
            assertEquals(i + 2, writeAttempts.get(), "must retry once backoff step " + expectedDelaysSec[i] + "s expires");
        }
        assertTrue(queue.hasPending(writeFailCube.key), "evicted snapshot MUST remain retained in pending after >4 write failures");

        // Cube B: evicted cube experiencing 5 consecutive encode failures (> MAX_EVICTED_RETRIES = 3).
        Cube encodeFailCube = new Cube(31, 41, 51, null);
        encodeFailCube.modified = true;
        encodeFailCube.dirty = true;
        queue.enqueue(encodeFailCube, false, List.of(), () -> {
            encodeAttempts.incrementAndGet();
            throw new RuntimeException("persistent encode error");
        }, workerTasks::add, serverTasks::add);
        workerTasks.remove(0).run();
        serverTasks.poll().run(); // Encode failure #1 -> 1s backoff
        assertEquals(1, encodeAttempts.get());
        for (int i = 0; i < 4; i++) {
            clockNanos.addAndGet(TimeUnit.SECONDS.toNanos(1L << i));
            queue.commitReady();
            assertEquals(1, workerTasks.size(), "must resubmit exactly one worker task after backoff");
            workerTasks.remove(0).run();
            serverTasks.poll().run();
            assertEquals(i + 2, encodeAttempts.get());
            // Immediate calls inside backoff must NOT resubmit.
            queue.commitReady();
            assertFalse(queue.tryDrainKeyNow(encodeFailCube.key));
            assertTrue(workerTasks.isEmpty(), "backoff must prevent per-tick encode resubmission");
        }
        assertTrue(queue.hasPending(encodeFailCube.key), "evicted snapshot MUST remain retained in pending after >4 encode failures");
        assertEquals(2, queue.pendingCount());

        // Pregenerate MUST NOT overwrite either retained snapshot!
        LongArrayList pregenAllowed = ServerCubeWorld.filterPregenerateKeys(
                LongArrayList.of(writeFailCube.key, encodeFailCube.key),
                () -> false,
                loaded::containsKey,
                queue);
        assertTrue(pregenAllowed.isEmpty(), "pregenerate filter must skip snapshots retained after exhausted retries");
        CubeStorage diskStorage = new CubeStorage(worldDir);
        try {
            int stored = ServerCubeWorld.commitPregenerated(
                    LongArrayList.of(writeFailCube.key, encodeFailCube.key),
                    List.of(new CompoundTag(), new CompoundTag()),
                    () -> false,
                    loaded::containsKey,
                    queue,
                    diskStorage);
            assertEquals(0, stored, "pregenerate commit must never overwrite retained snapshots");
        } finally {
            diskStorage.close();
        }

        // Async reload MUST defer and never read stale storage!
        LongOpenHashSet asyncPending = new LongOpenHashSet();
        AtomicInteger staleReads = new AtomicInteger();
        assertFalse(ServerCubeWorld.submitReadRequest(writeFailCube.key, asyncPending, queue, Runnable::run, staleReads::incrementAndGet));
        assertFalse(ServerCubeWorld.submitReadRequest(encodeFailCube.key, asyncPending, queue, Runnable::run, staleReads::incrementAndGet));
        assertEquals(0, staleReads.get(), "async reload must never read stale storage for retained failed snapshots");

        // Sync loadNowUncached() cleanly re-adopts Cube A directly with modified=true, dirty=true!
        Cube readopted = ServerCubeWorld.loadNowUncached(
                writeFailCube.key,
                queue,
                () -> {
                    fail("sync loadNow must not read stale storage when retained snapshot cannot write");
                    return null;
                },
                tag -> fail("sync loadNow must not adopt stale storage"),
                entry -> loaded.put(entry.key(), entry.cube()),
                loaded::get);
        assertSame(writeFailCube, readopted);
        assertTrue(readopted.modified && readopted.dirty);
        assertFalse(queue.hasPending(writeFailCube.key));
        assertEquals(1, queue.pendingCount());

        // Shutdown finishShutdownDrain() ignores live backoff on Cube B, performs bounded MAX_EVICTED_RETRIES final attempts,
        // closes storage, and surfaces IOException reporting the persistent failure!
        int encodesBeforeClose = encodeAttempts.get();
        AtomicBoolean storageClosed = new AtomicBoolean();
        IOException shutdownFailure = assertThrows(IOException.class,
                () -> ServerCubeWorld.finishShutdownDrain(queue, () -> storageClosed.set(true)));
        assertTrue(storageClosed.get());
        assertEquals(encodesBeforeClose + ServerCubeWorld.UntouchedStoreQueue.MAX_EVICTED_RETRIES, encodeAttempts.get(),
                "shutdown drainAll must ignore live backoff and execute bounded MAX_EVICTED_RETRIES final attempts");
        assertTrue(shutdownFailure.getMessage().contains("1 pending untouched cube"), shutdownFailure.getMessage());
        assertTrue(queue.hasPending(encodeFailCube.key), "failed evicted snapshot remains retained");
    }

    @Test
    void pregenerateCoordinationTimeoutCancelsQueuedTaskAndThrowsInsteadOfSilentNoOp() {
        List<Runnable> serverQueue = new ArrayList<>();
        AtomicInteger isLoadedCalls = new AtomicInteger();
        var untouched = new ServerCubeWorld.UntouchedStoreQueue<String>(
                () -> false, k -> null, (k, tag) -> {}, entry -> true, (k, err) -> {});
        LongArrayList candidates = LongArrayList.of(CubeKey.pack(1, 2, 3), CubeKey.pack(1, 3, 3));

        IllegalStateException timedOut = assertThrows(IllegalStateException.class, () ->
                ServerCubeWorld.coordinatePregenerateKeys(
                        candidates,
                        false,
                        serverQueue::add,
                        () -> false,
                        key -> {
                            isLoadedCalls.incrementAndGet();
                            return false;
                        },
                        untouched,
                        25L));
        assertInstanceOf(TimeoutException.class, timedOut.getCause());
        assertEquals(1, serverQueue.size());

        // Running the stale queued server-thread task later must be a complete no-op because it was cancelled!
        serverQueue.remove(0).run();
        assertEquals(0, isLoadedCalls.get(), "cancelled pregenerate coordination task must not execute work later");

        // When world is already closing/closed on timeout, cancels task and returns empty cleanly.
        AtomicBoolean closing = new AtomicBoolean(true);
        LongArrayList closedResult = ServerCubeWorld.coordinatePregenerateKeys(
                candidates,
                false,
                serverQueue::add,
                closing::get,
                key -> {
                    isLoadedCalls.incrementAndGet();
                    return false;
                },
                untouched,
                25L);
        assertTrue(closedResult.isEmpty());
        serverQueue.remove(0).run();
        assertEquals(0, isLoadedCalls.get());
    }

    @Test
    void finishShutdownDrainClosesStorageInFinallyWhenDrainAllThrowsAndDrainAllNeverDoubleCounts() throws Exception {
        // Part 1: finishShutdownDrain() still closes storage in finally when drainAll() throws an unchecked exception,
        // attaching any storage close failure as suppressed.
        IllegalStateException drainBoom = new IllegalStateException("unchecked drain failure");
        IOException closeBoom = new IOException("storage close failure");
        var throwingQueue = new ServerCubeWorld.UntouchedStoreQueue<String>(
                () -> false,
                key -> { throw drainBoom; },
                (key, tag) -> {},
                entry -> true,
                (key, err) -> {});
        Cube cube = new Cube(40, 50, 60, null);
        cube.modified = true;
        cube.dirty = true;
        List<Runnable> workerTasks = new ArrayList<>();
        throwingQueue.enqueue(cube, false, List.of(), () -> "tag", workerTasks::add, Runnable::run);

        AtomicBoolean storageClosed = new AtomicBoolean();
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> ServerCubeWorld.finishShutdownDrain(throwingQueue, () -> {
                    storageClosed.set(true);
                    throw closeBoom;
                }));
        assertSame(drainBoom, thrown);
        assertTrue(storageClosed.get(), "storageCloser.close() must ALWAYS run inside finally even if drainAll() throws unchecked");
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(closeBoom, thrown.getSuppressed()[0]);

        // Part 2: drainAll() with one failing non-in-flight evicted entry + one failing in-flight evicted entry
        // must count each entry ONCE (failedCount == 2, never double-counted across firstPass and secondPass).
        AtomicInteger failAWriteAttempts = new AtomicInteger();
        var countQueue = new ServerCubeWorld.UntouchedStoreQueue<String>(
                () -> false,
                key -> null,
                (key, tag) -> {
                    if ("tag-a".equals(tag)) failAWriteAttempts.incrementAndGet();
                    throw new IOException("write failed for " + tag);
                },
                entry -> true,
                (key, err) -> {},
                80L);
        Cube cubeA = new Cube(41, 51, 61, null);
        Cube cubeB = new Cube(42, 52, 62, null);
        countQueue.enqueue(cubeA, false, List.of(), () -> "tag-a", workerTasks::add, r -> {});
        CountDownLatch bStarted = new CountDownLatch(1);
        CountDownLatch bRelease = new CountDownLatch(1);
        ExecutorService bg = Executors.newSingleThreadExecutor();
        try {
            countQueue.enqueue(cubeB, false, List.of(), () -> {
                bStarted.countDown();
                try { bRelease.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return "tag-b";
            }, bg, r -> {});
            assertTrue(bStarted.await(5, TimeUnit.SECONDS));
            CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS).execute(bRelease::countDown);

            var result = countQueue.drainAll();
            assertEquals(2, result.failedCount(), "drainAll must never double-process or double-count firstPass failures");
            assertEquals(1 + ServerCubeWorld.UntouchedStoreQueue.MAX_EVICTED_RETRIES, failAWriteAttempts.get(),
                    "firstPass entry must not be retried a second time during deferred secondPass");
        } finally {
            bRelease.countDown();
            bg.shutdownNow();
        }
    }

    @Test
    void finishShutdownDrainWrapsCloseOnlyRuntimeExceptionInIOException() {
        var emptyQueue = new ServerCubeWorld.UntouchedStoreQueue<String>(
                () -> false, key -> null, (key, tag) -> {}, entry -> true, (key, err) -> {});
        IllegalStateException closeRuntime = new IllegalStateException("storage close runtime failure");
        IOException wrapped = assertThrows(IOException.class,
                () -> ServerCubeWorld.finishShutdownDrain(emptyQueue, () -> { throw closeRuntime; }));
        assertSame(closeRuntime, wrapped.getCause());
    }
}
