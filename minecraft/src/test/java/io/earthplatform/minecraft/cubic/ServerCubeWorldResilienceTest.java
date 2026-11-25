package io.earthplatform.minecraft.cubic;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.geography.globe.CopernicusSource;
import io.earthplatform.geography.globe.HostBackoff;
import io.earthplatform.minecraft.diagnostics.PerfFailures;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

            // Immediate reload through request()'s real submitReadRequest path MUST drain key1 before reading storage!
            LongOpenHashSet pending = new LongOpenHashSet();
            AtomicReference<CompoundTag> reloadedViaRequest = new AtomicReference<>();
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

        var queue = new ServerCubeWorld.UntouchedStoreQueue<String>(
                closed::get,
                loaded::get,
                (key, tag) -> {
                    if (failWriteOnce.compareAndSet(true, false)) throw new IOException("transient disk failure");
                    written.put(key, tag);
                },
                entry -> metadataSame.get(),
                (key, err) -> errors.add(err),
                80L);

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

        // Next save() retries writing the cached encoded tag and succeeds without re-running encoder!
        queue.commitReady();
        assertEquals("tag-evicted", written.get(evictedCube.key), "evicted cube succeeds on next save()");
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
}
