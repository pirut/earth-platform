package io.earthplatform.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32C;

/** Dependency-free integration/property tests. A failed assertion exits nonzero without -ea. */
public final class CoreTests {
    private static int assertions, groups;
    private static final WorldIdentity ID = new WorldIdentity(42, "fixture", "1", "frozen-fixture-1", "air-rock-water-v1");
    private CoreTests() {}
    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        run("signed coordinate properties and extrema", CoreTests::coordinates);
        run("immutable and compact cube representations", CoreTests::cubes);
        run("atomic multi-player residency budgets", CoreTests::tickets);
        run("deduplicated requests and independent cancellation", CoreTests::deduplication);
        run("bounded orphan work and executor/provider failures", CoreTests::loaderFailures);
        run("durable versioned overlays and registry identity", CoreTests::persistence);
        run("visible corruption recovery and malformed saves", CoreTests::corruption);
        run("two-player true-Y streaming and durable edits", CoreTests::worldLifecycle);
        run("stale teleport completions and cold-load recovery", CoreTests::teleportRaces);
        run("bounded edit queue and close durability", CoreTests::editBackpressure);
        run("randomized residency reference model", CoreTests::randomTickets);
        run("parallel subscribers and completion", CoreTests::parallelSubscribers);
        run("interrupted shutdown releases writer lock", CoreTests::interruptedShutdown);
        run("completion callback shutdown avoids self-deadlock", CoreTests::callbackShutdown);
        run("explicit failed-provider retry", CoreTests::providerRetry);
        run("teleports reclaim stale queued disk reads", CoreTests::queuedReadReclamation);
        run("mass executor rejection cannot strand requests", CoreTests::rejectionStorm);
        run("uncertain rename retry repairs directory durability", CoreTests::durabilityRetry);
        run("reentrant callbacks cannot create unticketed residents", CoreTests::reentrantTickets);
        run("all old subscriptions retire before reentrant admission", CoreTests::reentrantSubscriptions);
        run("unused-world async close never blocks its caller", CoreTests::unusedAsyncClose);
        run("shutdown cancellation callbacks can await closure", CoreTests::reentrantClose);
        run("edits survive negative and vertical section boundaries", CoreTests::boundaryEdits);
        run("failed durability never publishes successful edits", CoreTests::failedWrites);
        run("parallel ticket replacement stays within budget", CoreTests::parallelTickets);
        System.out.printf(Locale.ROOT, "CORE TESTS PASSED: %d groups, %d assertions, %.3f seconds%n", groups, assertions, (System.nanoTime() - start) / 1e9);
    }
    private static void coordinates() throws Exception {
        long[] positions = {Long.MIN_VALUE, Long.MAX_VALUE, -34359738369L, 34359738368L};
        for (long invalid : positions) expect(ArithmeticException.class, () -> CubePos.section(invalid));
        long[] edges = {-34359738368L, -11000, -17, -16, -15, -1, 0, 1, 15, 16, 17, 8849, 34359738367L};
        for (long block : edges) {
            int cube = CubePos.section(block), local = CubePos.local(block);
            equal(block, (long) cube * 16 + local); check(local >= 0 && local < 16);
        }
        equal(-688, CubePos.fromBlock(0, -11000, 0).y()); equal(553, CubePos.fromBlock(0, 8849, 0).y());
        Random random = new Random(13);
        for (int i = 0; i < 100_000; i++) {
            int section = random.nextInt(), local = random.nextInt(16); long block = 16L * section + local;
            equal(section, CubePos.section(block)); equal(local, CubePos.local(block));
        }
        Set<Integer> indices = new HashSet<>();
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) indices.add(CubePos.index(x, y, z));
        equal(4096, indices.size()); equal(4095, CubePos.indexOfBlock(-1, -1, -1));
        expect(IndexOutOfBoundsException.class, () -> CubePos.index(16, 0, 0));
        expect(IndexOutOfBoundsException.class, () -> CubePos.index(0, -1, 0));
        expect(ArithmeticException.class, () -> new CubePos(Integer.MAX_VALUE, 0, 0).offset(1, 0, 0));
    }
    private static void cubes() throws Exception {
        ImmutableCube solid = ImmutableCube.uniform(7); equal(4, solid.payloadBytes()); check(solid.isUniform());
        int[] source = solid.toArray(); source[0] = 3;
        ImmutableCube mixed = ImmutableCube.copyOf(source); source[0] = 9;
        equal(3, mixed.get(0)); equal(7, solid.get(0)); equal(16384, mixed.payloadBytes());
        int[] exported = mixed.toArray(); exported[1] = 9; equal(7, mixed.get(1));
        ImmutableCube edited = solid.withEdits(Map.of(0, 0, 4095, 2));
        equal(0, edited.get(0)); equal(2, edited.get(4095)); equal(7, solid.get(0));
        equal(solid, ImmutableCube.copyOf(solid.toArray())); equal(solid.hashCode(), ImmutableCube.copyOf(solid.toArray()).hashCode());
        expect(IllegalArgumentException.class, () -> ImmutableCube.uniform(-1));
        expect(IllegalArgumentException.class, () -> ImmutableCube.copyOf(new int[5]));
        expect(IndexOutOfBoundsException.class, () -> solid.get(4096));
        EditSnapshot edits = EditSnapshot.empty(new CubePos(0, 0, 0)).withBlock(0, 0);
        equal(0, edits.applyTo(ImmutableCube.uniform(99)).get(0)); // Air is a persistent explicit edit.
        expect(UnsupportedOperationException.class, () -> edits.blocks().put(1, 5));
    }
    private static void tickets() throws Exception {
        CubeTickets tickets = new CubeTickets(60, 3); CubePos origin = new CubePos(0, 0, 0);
        equal(27, tickets.update("a", origin, 1, 1).added().size());
        equal(0, tickets.update("b", origin, 1, 1).added().size()); equal(2, tickets.references(origin));
        equal(0, tickets.remove("a").removed().size()); equal(27, tickets.size());
        equal(27, tickets.update("c", new CubePos(0, -688, 0), 1, 1).added().size()); equal(54, tickets.size());
        Set<CubePos> before = tickets.positions();
        expect(CubeTickets.BudgetExceededException.class, () -> tickets.update("a", new CubePos(0, 552, 0), 1, 1));
        equal(before, tickets.positions()); equal(2, tickets.ticketCount());
        expect(CubeTickets.BudgetExceededException.class, () -> tickets.update("bad", origin, Integer.MAX_VALUE, Integer.MAX_VALUE));
        equal(before, tickets.positions());
        expect(ArithmeticException.class, () -> tickets.update("edge", new CubePos(Integer.MAX_VALUE, 0, 0), 1, 1));
        equal(1, CubeTickets.box(new CubePos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE), 0, 0, 1).size());
        equal(27, tickets.remove("b").removed().size()); equal(27, tickets.size());
        CubeTickets tiny = new CubeTickets(1, 1); tiny.update("a", origin, 0, 0);
        expect(CubeTickets.BudgetExceededException.class, () -> tiny.update("b", origin, 0, 0));
        equal(1, tiny.update("a", origin.offset(1, 0, 0), 0, 0).removed().size()); equal(1, tiny.size());
        expect(UnsupportedOperationException.class, () -> tickets.positions().clear());
    }
    private static void deduplication() throws Exception {
        ManualExecutor executor = new ManualExecutor(); FakeProvider provider = new FakeProvider();
        try (BoundedCubeLoader loader = new BoundedCubeLoader(provider, executor, 1, 2, 2)) {
            CubePos p = new CubePos(1, 2, 3);
            var first = loader.request(p); var second = loader.request(p); equal(1, executor.size());
            expect(RejectedExecutionException.class, () -> loader.request(p)); executor.runAll(); equal(1, provider.calls.size());
            first.close(); check(first.future().isCancelled()); check(!provider.calls.getFirst().token.isCancelled());
            provider.calls.getFirst().future.complete(ImmutableCube.uniform(12)); equal(12, await(second.future()).get(0));
            equal(1L, loader.stats().completed()); equal(0, loader.stats().running());
            second.close();
        }
    }
    private static void loaderFailures() throws Exception {
        ManualExecutor executor = new ManualExecutor(); FakeProvider provider = new FakeProvider();
        BoundedCubeLoader loader = new BoundedCubeLoader(provider, executor, 1, 1, 3);
        var a = loader.request(new CubePos(0, 0, 0)); executor.runAll();
        a.close(); check(provider.calls.getFirst().token.isCancelled()); equal(1, loader.stats().running());
        var b = loader.request(new CubePos(0, 0, 0)); equal(1, loader.stats().queued());
        expect(RejectedExecutionException.class, () -> loader.request(new CubePos(0, 1, 0)));
        // Cancelling an uncooperative provider cannot free its execution slot early.
        equal(1, provider.calls.size()); provider.calls.getFirst().future.complete(ImmutableCube.uniform(1)); executor.runAll();
        equal(2, provider.calls.size()); provider.calls.get(1).future.complete(ImmutableCube.uniform(2)); equal(2, await(b.future()).get(0));
        var c = loader.request(new CubePos(5, 5, 5)); var d = loader.request(new CubePos(6, 6, 6));
        d.future().cancel(false); equal(0, loader.stats().queued()); executor.runAll();
        loader.close(); check(c.future().isCancelled()); check(provider.calls.getLast().token.isCancelled());
        provider.calls.getLast().future.completeExceptionally(new CancellationException()); equal(0, loader.stats().running());
        expect(RejectedExecutionException.class, () -> loader.request(new CubePos(0, 0, 0)));
        try (BoundedCubeLoader rejected = new BoundedCubeLoader(provider, command -> { throw new RejectedExecutionException("fixture"); }, 1, 0, 1)) {
            var request = rejected.request(new CubePos(0, 0, 0)); expectFuture(RejectedExecutionException.class, request.future()); equal(0, rejected.stats().running());
        }
        try (BoundedCubeLoader invalid = new BoundedCubeLoader((p, token) -> null, Runnable::run, 1, 0, 1)) {
            expectFuture(NullPointerException.class, invalid.request(new CubePos(0, 0, 0)).future());
        }
        try (BoundedCubeLoader failing = new BoundedCubeLoader((p, token) -> { throw new IllegalStateException("fixture"); }, Runnable::run, 1, 0, 1)) {
            expectFuture(IllegalStateException.class, failing.request(new CubePos(0, 0, 0)).future()); equal(1L, failing.stats().failed());
        }
    }
    private static void persistence() throws Exception {
        Path directory = Files.createTempDirectory("earth-core-store-"); CubePos p = new CubePos(-1, -688, 552);
        EditSnapshot first = EditSnapshot.empty(p).withBlock(0, 0).withBlock(4095, 77);
        // Save requires a consecutive revision, making lost updates visible.
        try (FileEditStore store = new FileEditStore(directory, ID)) {
            equal(EditStore.Status.EMPTY, store.load(p).status());
            expect(IOException.class, () -> store.save(first));
            EditSnapshot revision1 = EditSnapshot.empty(p).withBlock(0, 0); store.save(revision1); store.save(revision1);
            store.save(first); equal(first, store.load(p).snapshot()); check(Files.exists(store.backupPath(p)));
            expect(IOException.class, () -> store.save(revision1));
            expect(IOException.class, () -> new FileEditStore(directory, ID));
        }
        try (FileEditStore reloaded = new FileEditStore(directory, ID)) {
            equal(first, reloaded.load(p).snapshot()); equal(0, reloaded.load(p).snapshot().applyTo(ImmutableCube.uniform(3)).get(0));
        }
        expect(FileEditStore.CorruptSaveException.class, () -> new FileEditStore(directory,
                new WorldIdentity(42, "fixture", "1", "changed-dataset", "air-rock-water-v1")));
        expect(FileEditStore.CorruptSaveException.class, () -> new FileEditStore(directory,
                new WorldIdentity(42, "fixture", "1", "frozen-fixture-1", "changed-palette")));
        // Failed opens release their lock, so the legitimate owner can immediately reopen.
        try (FileEditStore reloaded = new FileEditStore(directory, ID)) { equal(first, reloaded.load(p).snapshot()); }
    }
    private static void corruption() throws Exception {
        Path directory = Files.createTempDirectory("earth-core-corrupt-"); CubePos p = new CubePos(0, 552, 0);
        try (FileEditStore store = new FileEditStore(directory, ID)) {
            var one = EditSnapshot.empty(p).withBlock(5, 1); var two = one.withBlock(5, 2);
            store.save(one); store.save(two); byte[] bytes = Files.readAllBytes(store.primaryPath(p));
            bytes[20] ^= 32; Files.write(store.primaryPath(p), bytes);
            var recovered = store.load(p); equal(EditStore.Status.RECOVERED_BACKUP, recovered.status()); equal(one, recovered.snapshot());
            check(!recovered.warning().isBlank()); expect(IOException.class, () -> store.save(one.withBlock(4, 4)));
            equal(one, store.restoreBackup(p).snapshot());
            try (var files = Files.list(directory.resolve("cubes"))) { check(files.anyMatch(path -> path.getFileName().toString().contains(".corrupt-"))); }
            store.save(one.withBlock(4, 4));
            Files.delete(store.primaryPath(p)); equal(EditStore.Status.RECOVERED_BACKUP, store.load(p).status());
            store.restoreBackup(p);
            // Well-checksummed unknown versions still cannot be mistaken for supported data.
            byte[] unsupported = Files.readAllBytes(store.primaryPath(p)); ByteBuffer.wrap(unsupported).putInt(4, 99); fixCrc(unsupported);
            Files.write(store.primaryPath(p), unsupported); Files.delete(store.backupPath(p));
            expect(IOException.class, () -> store.load(p));
            Files.write(store.primaryPath(p), new byte[100_000]); expect(IOException.class, () -> store.load(p));
            Files.write(store.primaryPath(p), new byte[3]); expect(IOException.class, () -> store.load(p));
        }
        Path orphan = Files.createTempDirectory("earth-core-orphan-"); Files.createDirectories(orphan.resolve("cubes")); Files.writeString(orphan.resolve("cubes/unknown.cube"), "evidence");
        expect(IOException.class, () -> new FileEditStore(orphan, ID));
    }
    private static void worldLifecycle() throws Exception {
        Path directory = Files.createTempDirectory("earth-core-world-"); AtomicInteger calls = new AtomicInteger();
        ExecutorService generation = Executors.newFixedThreadPool(2);
        CubeProvider provider = (p, token) -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(ImmutableCube.uniform(p.y() < 0 ? 2 : 1)); };
        CubePos summit = CubePos.fromBlock(0, 8849, 0), trench = CubePos.fromBlock(0, -11000, 0);
        try {
            try (CubicWorld world = new CubicWorld(ID, provider, new FileEditStore(directory, ID), generation, 2, 3, 2, 16)) {
                world.updateTicket("summit", summit, 0, 0); world.updateTicket("trench", trench, 0, 0);
                await(world.ready(summit)); await(world.ready(trench)); equal(2, world.stats().residentCubes());
                equal(1, world.getBlock(0, 8849, 0)); equal(2, world.getBlock(0, -11000, 0));
                await(world.setBlock(0, 8849, 0, 77)); equal(77, world.getBlock(0, 8849, 0));
                world.updateTicket("friend", summit, 0, 0); equal(2, calls.get());
                equal(1L, await(world.setBlock(0, -11000, 0, 0))); equal(2L, await(world.setBlock(1, -11000, 0, 99)));
                equal(0, world.getBlock(0, -11000, 0)); equal(2, world.view(trench).orElseThrow().base().get(CubePos.indexOfBlock(0, -11000, 0)));
                world.removeTicket("trench"); equal(1, world.stats().residentCubes());
                expect(IllegalArgumentException.class, () -> world.getBlock(0, -11000, 0));
                world.updateTicket("trench", trench, 0, 0); await(world.ready(trench)); equal(0, world.getBlock(0, -11000, 0)); equal(99, world.getBlock(1, -11000, 0));
            }
            try (CubicWorld restarted = new CubicWorld(ID, provider, new FileEditStore(directory, ID), generation, 1, 1, 1, 4)) {
                restarted.updateTicket("trench", trench, 0, 0); await(restarted.ready(trench)); equal(99, restarted.getBlock(1, -11000, 0));
            }
        } finally { generation.shutdownNow(); }
    }
    private static void teleportRaces() throws Exception {
        ManualExecutor executor = new ManualExecutor(); FakeProvider provider = new FakeProvider();
        Path directory = Files.createTempDirectory("earth-core-teleports-");
        try (CubicWorld world = new CubicWorld(ID, provider, new FileEditStore(directory, ID), executor, 1, 1, 1, 8)) {
            CubePos old = new CubePos(0, 552, 0), latest = new CubePos(0, -688, 0);
            world.updateTicket("p", old, 0, 0); var oldReady = world.ready(old); executor.runAll();
            for (int i = 0; i < 200; i++) world.updateTicket("p", new CubePos(i, i, i), 0, 0);
            world.updateTicket("p", latest, 0, 0); equal(1, world.stats().residentCubes()); check(oldReady.isCompletedExceptionally());
            equal(1, world.stats().loader().running()); equal(1, world.stats().loader().queued());
            provider.calls.getFirst().future.complete(ImmutableCube.uniform(123)); executor.runAll();
            equal(2, provider.calls.size()); equal(latest, provider.calls.getLast().position);
            provider.calls.getLast().future.complete(ImmutableCube.uniform(4)); await(world.ready(latest));
            check(world.view(old).isEmpty()); equal(4, world.getBlock(0, -11000, 0));
        }
    }
    private static void editBackpressure() throws Exception {
        BlockingStore store = new BlockingStore(); ExecutorService generation = Executors.newSingleThreadExecutor();
        CubicWorld world = new CubicWorld(ID, (p, t) -> CompletableFuture.completedFuture(ImmutableCube.uniform(1)), store, generation, 1, 1, 1, 1);
        try {
            world.updateTicket("p", new CubePos(0, 0, 0), 0, 0); await(world.ready(new CubePos(0, 0, 0)));
            var accepted = world.setBlock(0, 0, 0, 9); check(store.entered.await(5, TimeUnit.SECONDS));
            expect(RejectedExecutionException.class, () -> world.setBlock(1, 0, 0, 10));
            equal(1, world.stats().pendingEdits()); store.proceed.countDown(); equal(1L, await(accepted));
            world.close(); check(store.closed); equal(9, store.snapshot.blocks().get(0));
        } finally { store.proceed.countDown(); world.close(); generation.shutdownNow(); }
    }
    private static void randomTickets() throws Exception {
        CubeTickets actual = new CubeTickets(110, 6);
        Map<String, Set<CubePos>> expected = new HashMap<>(); Random random = new Random(1729);
        for (int step = 0; step < 3000; step++) {
            String id = "p" + random.nextInt(6);
            if (random.nextInt(5) == 0) { actual.remove(id); expected.remove(id); }
            else {
                CubePos center = new CubePos(random.nextInt(7) - 3, random.nextInt(7) - 3, random.nextInt(7) - 3);
                int h = random.nextInt(2), v = random.nextInt(2);
                Set<CubePos> newBox = CubeTickets.box(center, h, v, 110);
                Map<String, Set<CubePos>> candidate = new HashMap<>(expected); candidate.put(id, newBox);
                Set<CubePos> union = new HashSet<>(); candidate.values().forEach(union::addAll);
                if (union.size() > 110) expect(CubeTickets.BudgetExceededException.class, () -> actual.update(id, center, h, v));
                else { actual.update(id, center, h, v); expected = candidate; }
            }
            Set<CubePos> union = new HashSet<>(); expected.values().forEach(union::addAll);
            equal(union, actual.positions()); equal(expected.size(), actual.ticketCount());
            for (CubePos p : union) {
                int count = 0; for (Set<CubePos> ticket : expected.values()) if (ticket.contains(p)) count++;
                equal(count, actual.references(p));
            }
        }
    }
    private static void parallelSubscribers() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(8), generation = Executors.newFixedThreadPool(2);
        AtomicInteger calls = new AtomicInteger(); CompletableFuture<ImmutableCube> source = new CompletableFuture<>();
        try (BoundedCubeLoader loader = new BoundedCubeLoader((p, token) -> { calls.incrementAndGet(); return source; }, generation, 2, 4, 512)) {
            List<Future<BoundedCubeLoader.Lease>> requests = new ArrayList<>(); CubePos p = new CubePos(7, 8, 9);
            for (int i = 0; i < 300; i++) requests.add(callers.submit(() -> loader.request(p)));
            List<BoundedCubeLoader.Lease> leases = new ArrayList<>();
            for (Future<BoundedCubeLoader.Lease> request : requests) leases.add(request.get(5, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) leases.get(i).close();
            source.complete(ImmutableCube.uniform(8));
            for (int i = 100; i < 300; i++) equal(8, await(leases.get(i).future()).get(0));
            equal(1, calls.get()); equal(0, loader.stats().running());
        } finally { callers.shutdownNow(); generation.shutdownNow(); }
    }
    private static void interruptedShutdown() throws Exception {
        Path directory = Files.createTempDirectory("earth-core-interrupted-close-");
        FileEditStore durable = new FileEditStore(directory, ID); AtomicBoolean closed = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        EditStore store = new EditStore() {
            @Override public LoadedEdits load(CubePos p) throws IOException { return durable.load(p); }
            @Override public void save(EditSnapshot edits) throws IOException {
                entered.countDown();
                try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture stalled"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                durable.save(edits);
            }
            @Override public void close() throws IOException { durable.close(); closed.set(true); }
        };
        ExecutorService generation = Executors.newSingleThreadExecutor();
        CubicWorld world = new CubicWorld(ID, (p, t) -> CompletableFuture.completedFuture(ImmutableCube.uniform(1)), store, generation, 1, 1, 1, 2);
        try {
            world.updateTicket("p", new CubePos(0, 0, 0), 0, 0); await(world.ready(new CubePos(0, 0, 0)));
            CompletableFuture<Long> edit = world.setBlock(0, 0, 0, 12); check(entered.await(5, TimeUnit.SECONDS));
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread closer = new Thread(() -> {
                Thread.currentThread().interrupt();
                try { world.close(); } catch (Throwable error) { failure.set(error); }
            });
            closer.start(); closer.join(5000); check(!closer.isAlive()); check(failure.get() instanceof IOException); check(!closed.get());
            proceed.countDown(); equal(1L, await(edit)); await(world.closeAsync()); check(closed.get()); world.close();
            try (FileEditStore reopened = new FileEditStore(directory, ID)) { equal(12, reopened.load(new CubePos(0, 0, 0)).snapshot().blocks().get(0)); }
        } finally { proceed.countDown(); world.close(); generation.shutdownNow(); }
    }
    private static void callbackShutdown() throws Exception {
        BlockingStore store = new BlockingStore(); ExecutorService generation = Executors.newSingleThreadExecutor();
        CubicWorld world = new CubicWorld(ID, (p, t) -> CompletableFuture.completedFuture(ImmutableCube.uniform(1)), store, generation, 1, 1, 1, 2);
        try {
            world.updateTicket("p", new CubePos(0, 0, 0), 0, 0); await(world.ready(new CubePos(0, 0, 0)));
            CompletableFuture<Long> edit = world.setBlock(0, 0, 0, 12); check(store.entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> callback = edit.thenRun(() -> {
                try { world.close(); throw new AssertionError("Blocking close should reject its own worker"); }
                catch (IllegalStateException expected) { world.closeAsync(); }
                catch (IOException failure) { throw new CompletionException(failure); }
            });
            store.proceed.countDown(); await(callback); await(world.closeAsync()); check(store.closed);
        } finally { store.proceed.countDown(); world.close(); generation.shutdownNow(); }
    }
    private static void providerRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger(); ExecutorService generation = Executors.newSingleThreadExecutor();
        CubeProvider provider = (p, token) -> calls.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new IOException("Temporary fixture outage"))
                : CompletableFuture.completedFuture(ImmutableCube.uniform(5));
        try (CubicWorld world = new CubicWorld(ID, provider, new FileEditStore(Files.createTempDirectory("earth-core-retry-"), ID), generation, 1, 1, 1, 2)) {
            CubePos p = new CubePos(0, 0, 0); world.updateTicket("p", p, 0, 0);
            expectFuture(IOException.class, world.ready(p)); equal(5, await(world.retry(p)).get(0));
            expect(IllegalStateException.class, () -> world.retry(p)); equal(1, world.stats().residentCubes());
        } finally { generation.shutdownNow(); }
    }
    private static void queuedReadReclamation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        EditStore store = new EditStore() {
            @Override public LoadedEdits load(CubePos p) throws IOException {
                if (loads.incrementAndGet() == 1) {
                    entered.countDown();
                    try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture stalled"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                }
                return new LoadedEdits(EditSnapshot.empty(p), Status.EMPTY, "");
            }
            @Override public void save(EditSnapshot snapshot) { throw new UnsupportedOperationException(); }
            @Override public void close() {}
        };
        try (CubicWorld world = new CubicWorld(ID, (p, t) -> CompletableFuture.completedFuture(ImmutableCube.uniform(7)), store, Runnable::run, 1, 1, 1, 1)) {
            world.updateTicket("p", new CubePos(0, 0, 0), 0, 0); check(entered.await(5, TimeUnit.SECONDS));
            for (int i = 1; i <= 200; i++) world.updateTicket("p", new CubePos(i, i, i), 0, 0);
            CompletableFuture<CubicWorld.CubeView> finalReady = world.ready(new CubePos(200, 200, 200));
            check(!finalReady.isDone()); proceed.countDown(); equal(7, await(finalReady).get(0));
            equal(2, loads.get()); equal(1, world.stats().residentCubes());
        } finally { proceed.countDown(); }
    }
    private static void rejectionStorm() throws Exception {
        AtomicInteger mode = new AtomicInteger(); CompletableFuture<ImmutableCube> first = new CompletableFuture<>();
        Executor executor = task -> { if (mode.get() == 0) task.run(); else throw new RejectedExecutionException("Shutdown fixture"); };
        try (BoundedCubeLoader loader = new BoundedCubeLoader((p, t) -> first, executor, 1, 10_000, 1)) {
            var initial = loader.request(new CubePos(0, 0, 0));
            List<BoundedCubeLoader.Lease> queued = new ArrayList<>();
            for (int i = 1; i <= 10_000; i++) queued.add(loader.request(new CubePos(i, 0, 0)));
            mode.set(1); first.complete(ImmutableCube.uniform(1)); equal(1, await(initial.future()).get(0));
            for (var lease : queued) expectFuture(RejectedExecutionException.class, lease.future());
            equal(0, loader.stats().running()); equal(0, loader.stats().queued()); equal(10_000L, loader.stats().failed());
        }
    }
    private static void durabilityRetry() throws Exception {
        AtomicInteger failures = new AtomicInteger(), syncs = new AtomicInteger();
        Path directory = Files.createTempDirectory("earth-core-sync-");
        FileEditStore.DirectorySync sync = path -> {
            syncs.incrementAndGet();
            if (failures.getAndDecrement() > 0) throw new IOException("Injected directory fsync failure after rename");
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) { channel.force(true); }
        };
        try (FileEditStore store = new FileEditStore(directory, ID, sync)) {
            var edit = EditSnapshot.empty(new CubePos(0, 0, 0)).withBlock(0, 5);
            failures.set(1); expect(IOException.class, () -> store.save(edit));
            equal(edit, store.load(edit.position()).snapshot());
            int before = syncs.get(); failures.set(1); expect(IOException.class, () -> store.save(edit));
            check(syncs.get() > before); failures.set(0); store.save(edit); equal(edit, store.load(edit.position()).snapshot());
        }
    }
    private static void reentrantTickets() throws Exception {
        ManualExecutor executor = new ManualExecutor(); FakeProvider provider = new FakeProvider();
        try (CubicWorld world = new CubicWorld(ID, provider, new FileEditStore(Files.createTempDirectory("earth-core-reentry-"), ID), executor, 1, 1, 1, 1)) {
            CubePos a = new CubePos(0, 0, 0), b = new CubePos(1, 0, 0), c = new CubePos(2, 0, 0);
            world.updateTicket("p", a, 0, 0);
            var callback = world.ready(a).whenComplete((value, error) -> world.updateTicket("p", c, 0, 0));
            world.updateTicket("p", b, 0, 0);
            check(callback.isCompletedExceptionally()); equal(1, world.stats().residentCubes());
            expect(IllegalArgumentException.class, () -> world.ready(b));
            check(!world.ready(c).isDone()); executor.runAll(); equal(1, provider.calls.size());
            equal(c, provider.calls.getFirst().position); provider.calls.getFirst().future.complete(ImmutableCube.uniform(3));
            equal(3, await(world.ready(c)).get(0));
        }
    }
    private static void reentrantSubscriptions() throws Exception {
        ManualExecutor executor = new ManualExecutor(); FakeProvider provider = new FakeProvider();
        try (CubicWorld world = new CubicWorld(ID, provider, new FileEditStore(Files.createTempDirectory("earth-core-subscription-reentry-"), ID), executor, 3, 1, 1, 1)) {
            CubePos a = new CubePos(0, 0, 0), b = new CubePos(100, 0, 0);
            world.updateTicket("p", a, 0, 1); AtomicBoolean once = new AtomicBoolean();
            for (CubePos p : CubeTickets.box(a, 0, 1, 3)) world.ready(p).whenComplete((value, error) -> {
                if (once.compareAndSet(false, true)) world.updateTicket("p", a, 0, 1);
            });
            world.updateTicket("p", b, 0, 1); equal(3, world.stats().residentCubes());
            for (CubePos p : CubeTickets.box(a, 0, 1, 3)) check(!world.ready(p).isCompletedExceptionally());
            for (int i = 0; i < 3; i++) { executor.runAll(); provider.calls.getLast().future.complete(ImmutableCube.uniform(6)); }
            for (CubePos p : CubeTickets.box(a, 0, 1, 3)) equal(6, await(world.ready(p)).get(0));
            equal(3, provider.calls.size());
        }
    }
    private static void unusedAsyncClose() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        AtomicReference<Thread> closingThread = new AtomicReference<>(); Thread caller = Thread.currentThread();
        EditStore store = new EditStore() {
            @Override public LoadedEdits load(CubePos p) { return new LoadedEdits(EditSnapshot.empty(p), Status.EMPTY, ""); }
            @Override public void save(EditSnapshot snapshot) { throw new UnsupportedOperationException(); }
            @Override public void close() throws IOException {
                closingThread.set(Thread.currentThread()); entered.countDown();
                try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new IOException("Close fixture stalled"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
        };
        CubicWorld world = new CubicWorld(ID, (p, t) -> CompletableFuture.completedFuture(ImmutableCube.uniform(0)), store, Runnable::run, 1, 1, 1, 1);
        try {
            CompletableFuture<Void> closed = world.closeAsync(); check(entered.await(5, TimeUnit.SECONDS));
            check(closingThread.get() != caller); check(!closed.isDone()); proceed.countDown(); await(closed);
        } finally { proceed.countDown(); world.close(); }
    }
    private static void reentrantClose() throws Exception {
        ManualExecutor executor = new ManualExecutor(); FakeProvider provider = new FakeProvider();
        CubicWorld world = new CubicWorld(ID, provider, new FileEditStore(Files.createTempDirectory("earth-core-close-reentry-"), ID), executor, 1, 1, 1, 1);
        try {
            CubePos p = new CubePos(0, 0, 0); world.updateTicket("p", p, 0, 0);
            CompletableFuture<Void> callback = new CompletableFuture<>();
            world.ready(p).whenComplete((value, error) -> {
                try { world.close(); callback.complete(null); } catch (Throwable failure) { callback.completeExceptionally(failure); }
            });
            await(world.closeAsync()); await(callback);
        } finally { world.close(); }
    }
    private static void boundaryEdits() throws Exception {
        Path directory = Files.createTempDirectory("earth-core-boundary-edits-");
        ExecutorService generation = Executors.newFixedThreadPool(2); long[] ys = {-1, 0, 15, 16};
        CubeProvider provider = (p, t) -> CompletableFuture.completedFuture(ImmutableCube.uniform(1));
        try {
            for (int pass = 0; pass < 2; pass++) {
                try (CubicWorld world = new CubicWorld(ID, provider, new FileEditStore(directory, ID), generation, 3, 1, 2, 8)) {
                    world.updateTicket("p", new CubePos(-1, 0, -1), 0, 1);
                    for (long y : ys) await(world.ready(CubePos.fromBlock(-1, y, -1)));
                    if (pass == 0) for (int i = 0; i < ys.length; i++) await(world.setBlock(-1, ys[i], -1, 10 + i));
                    for (int i = 0; i < ys.length; i++) equal(10 + i, world.getBlock(-1, ys[i], -1));
                }
            }
        } finally { generation.shutdownNow(); }
    }
    private static void failedWrites() throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        EditStore store = new EditStore() {
            EditSnapshot edits = EditSnapshot.empty(new CubePos(0, 0, 0));
            @Override public LoadedEdits load(CubePos p) { return new LoadedEdits(edits, edits.revision() == 0 ? Status.EMPTY : Status.PRIMARY, ""); }
            @Override public void save(EditSnapshot replacement) throws IOException { if (fail.getAndSet(false)) throw new IOException("Injected disk failure"); edits = replacement; }
            @Override public void close() {}
        };
        try (CubicWorld world = new CubicWorld(ID, (p, t) -> CompletableFuture.completedFuture(ImmutableCube.uniform(1)), store, Runnable::run, 1, 1, 1, 1)) {
            world.updateTicket("p", new CubePos(0, 0, 0), 0, 0); await(world.ready(new CubePos(0, 0, 0)));
            expectFuture(IOException.class, world.setBlock(0, 0, 0, 4)); equal(1, world.getBlock(0, 0, 0)); equal(0, world.stats().pendingEdits());
            equal(1L, await(world.setBlock(0, 0, 0, 4))); equal(4, world.getBlock(0, 0, 0));
        }
    }
    private static void parallelTickets() throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(8), generation = Executors.newFixedThreadPool(4);
        try (CubicWorld world = new CubicWorld(ID, (p, t) -> CompletableFuture.completedFuture(ImmutableCube.uniform(2)),
                new FileEditStore(Files.createTempDirectory("earth-core-parallel-tickets-"), ID), generation, 1, 1, 4, 2)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                int number = worker;
                tasks.add(callers.submit(() -> {
                    for (int i = 0; i < 100; i++) {
                        world.updateTicket("p", new CubePos(number * 100 + i, i, 0), 0, 0);
                        if (world.stats().residentCubes() != 1) throw new AssertionError("Residency exceeded budget");
                    }
                }));
            }
            for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
            CubePos last = new CubePos(-1, -688, -1); world.updateTicket("p", last, 0, 0);
            equal(2, await(world.ready(last)).get(0)); equal(1, world.stats().residentCubes()); equal(1, world.stats().readyCubes());
        } finally { callers.shutdownNow(); generation.shutdownNow(); }
    }
    private static void fixCrc(byte[] bytes) { CRC32C crc = new CRC32C(); crc.update(bytes, 0, bytes.length - 8); ByteBuffer.wrap(bytes).putLong(bytes.length - 8, crc.getValue()); }
    private static <T> T await(CompletableFuture<T> future) throws Exception { return future.get(5, TimeUnit.SECONDS); }
    private static void expectFuture(Class<? extends Throwable> type, CompletableFuture<?> future) throws Exception {
        try { await(future); throw new AssertionError("Expected future failure " + type); }
        catch (ExecutionException e) { check(type.isInstance(e.getCause())); }
    }
    private static void check(boolean condition) { assertions++; if (!condition) throw new AssertionError("Assertion " + assertions); }
    private static void equal(Object expected, Object actual) { assertions++; if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual); }
    private static void expect(Class<? extends Throwable> type, Checked action) throws Exception {
        assertions++;
        try { action.run(); } catch (Throwable failure) { if (type.isInstance(failure)) return; throw new AssertionError("Expected " + type + ", got " + failure, failure); }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    private static void run(String name, Checked action) throws Exception { action.run(); groups++; System.out.println("PASS " + name); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        @Override public void execute(Runnable task) { queue.addLast(task); }
        int size() { return queue.size(); }
        void runAll() { while (!queue.isEmpty()) queue.removeFirst().run(); }
    }
    private static final class FakeProvider implements CubeProvider {
        final List<Call> calls = new ArrayList<>();
        @Override public CompletableFuture<ImmutableCube> load(CubePos position, CancellationToken token) {
            Call call = new Call(position, token, new CompletableFuture<>()); calls.add(call); return call.future;
        }
    }
    private record Call(CubePos position, CancellationToken token, CompletableFuture<ImmutableCube> future) {}
    private static final class BlockingStore implements EditStore {
        final CountDownLatch entered = new CountDownLatch(1), proceed = new CountDownLatch(1);
        EditSnapshot snapshot = EditSnapshot.empty(new CubePos(0, 0, 0)); boolean closed;
        @Override public LoadedEdits load(CubePos position) { return new LoadedEdits(snapshot, snapshot.revision() == 0 ? Status.EMPTY : Status.PRIMARY, ""); }
        @Override public void save(EditSnapshot replacement) throws IOException {
            entered.countDown();
            try { if (!proceed.await(5, TimeUnit.SECONDS)) throw new IOException("Fixture blocked"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
            snapshot = replacement;
        }
        @Override public void close() { closed = true; }
    }
}
