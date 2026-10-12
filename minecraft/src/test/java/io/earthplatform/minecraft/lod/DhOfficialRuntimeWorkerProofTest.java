package io.earthplatform.minecraft.lod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual pinned release classes, isolated by DhOfficialRuntime; no Minecraft or GL boot. */
@Timeout(30)
class DhOfficialRuntimeWorkerProofTest {
    private static final int REPEATS = 64;

    private static DhOfficialRuntime runtime() throws Exception {
        String jar = System.getProperty("earthplatform.lodProbeJar", System.getenv("EARTH_DH_PROBE_JAR"));
        assumeTrue(jar != null && !jar.isBlank(), "Explicit pinned official artifact required");
        return DhOfficialRuntime.open(Path.of(jar));
    }

    /** Queue is bounded, and rejection never executes official work on the caller. */
    private static final class Worker implements AutoCloseable {
        final AtomicInteger created = new AtomicInteger();
        final AtomicReference<Thread> thread = new AtomicReference<>();
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(2), task -> {
                    Thread thread = new Thread(task, "official-dh-cpu-proof-" + created.incrementAndGet());
                    thread.setDaemon(true);
                    this.thread.set(thread);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        @Override public void close() throws Exception {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "bounded worker terminated");
            assertEquals(1, created.get(), "exactly one CPU worker");
        }
    }

    private static List<DhTerrainGeometry.PageFace> fixture(int page, int seed) {
        int y = page == 4 ? 512 : 1 + (seed * 71 % 1900);
        int c = 0xff000000 | (0x153759 + seed * 0x3157 & 0xffffff);
        var f = new ArrayList<DhTerrainGeometry.PageFace>();
        f.add(new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.UP, page, 16, y + 8, 32, 3, 5, c));
        f.add(new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.DOWN, page, 16, y, 32, 3, 3, c));
        f.add(new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.NORTH, page, 16, y, 32, 3, 8, c));
        f.add(new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.SOUTH, page, 16, y, 37, 3, 8, c));
        f.add(new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.WEST, page, 16, y, 32, 5, 8, c));
        f.add(new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.EAST, page, 19, y, 32, 5, 8, c));
        return List.copyOf(f);
    }

    private static List<DhOfficialRuntime.Face> unitFaces(int y) {
        return List.of(new DhOfficialRuntime.Face("UP", 0, y + 1, 0, 0xff123456),
                new DhOfficialRuntime.Face("DOWN", 0, y, 0, 0xff123456),
                new DhOfficialRuntime.Face("NORTH", 0, y, 0, 0xff123456),
                new DhOfficialRuntime.Face("SOUTH", 0, y, 1, 0xff123456),
                new DhOfficialRuntime.Face("WEST", 0, y, 0, 0xff123456),
                new DhOfficialRuntime.Face("EAST", 1, y, 0, 0xff123456));
    }

    @Test void repeatedWorkerOutputsMatchOwnerFixturesAcrossSameAndDifferentPages() throws Exception {
        var runtime = runtime();
        var inputs = List.of(fixture(-5, 1), fixture(-1, 2), fixture(0, 3), fixture(3, 4), fixture(4, 5),
                List.of(new DhTerrainGeometry.PageFace(DhTerrainGeometry.Direction.UP, 3, 4090, 2048, 4090, 6, 6, 0xffabcdef)));
        var expected = inputs.stream().map(runtime::buildRectangles).toList();
        assertArrayEquals(runtime.buildRectangles(fixture(-5, 3)), runtime.buildRectangles(fixture(3, 3)),
                "absolute page lives in the envelope; identical page-local geometry has identical official bytes");
        long owner = Thread.currentThread().threadId();
        try (var worker = new Worker()) {
            for (int i = 0; i < REPEATS; i++) {
                int index = i % inputs.size();
                byte[] result = worker.executor.submit(() -> {
                    assertNotEquals(owner, Thread.currentThread().threadId());
                    return runtime.buildRectangles(inputs.get(index));
                }).get(5, TimeUnit.SECONDS);
                assertArrayEquals(expected.get(index), result, "page fixture " + index + ", iteration " + i);
                assertNotSame(expected.get(index), result);
            }
            var maxFaces = new ArrayList<DhTerrainGeometry.PageFace>(4096);
            for (int i = 0; i < 4096; i++) maxFaces.add(new DhTerrainGeometry.PageFace(
                    DhTerrainGeometry.Direction.UP, 0, i % 64 * 2, 100, i / 64 * 2, 1, 1, 0xff010000 | i));
            byte[] maxExpected = runtime.buildRectangles(maxFaces);
            byte[] maxActual = worker.executor.submit(() -> runtime.buildRectangles(maxFaces)).get(5, TimeUnit.SECONDS);
            assertEquals(4096 * 64, maxActual.length);
            assertArrayEquals(maxExpected, maxActual);
        }
    }

    @Test void ownerDiagnosticsAndSingleWorkerCoexistWithoutCopiedOutputAlias() throws Exception {
        var runtime = runtime();
        var inputs = List.of(fixture(-5, 6), fixture(0, 7), fixture(4, 8));
        var expected = inputs.stream().map(runtime::buildRectangles).toList();
        var diagnosticInputs = List.of(unitFaces(1), unitFaces(1280), unitFaces(2047));
        var expectedOwner = diagnosticInputs.stream().map(runtime::build).toList();
        var start = new CountDownLatch(1);
        try (var worker = new Worker()) {
            Future<List<byte[]>> asynchronous = worker.executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                var retained = new ArrayList<byte[]>();
                for (int i = 0; i < REPEATS; i++) retained.add(runtime.buildRectangles(inputs.get(i % inputs.size())));
                return retained;
            });
            start.countDown();
            var retainedOwner = new ArrayList<byte[]>();
            for (int i = 0; i < REPEATS; i++) retainedOwner.add(runtime.build(diagnosticInputs.get(i % diagnosticInputs.size())));
            var retainedWorker = asynchronous.get(10, TimeUnit.SECONDS);
            for (int i = 0; i < REPEATS; i++) {
                assertArrayEquals(expectedOwner.get(i % diagnosticInputs.size()), retainedOwner.get(i));
                assertArrayEquals(expected.get(i % inputs.size()), retainedWorker.get(i));
                assertNotSame(retainedOwner.get(i), retainedWorker.get(i));
            }
            byte original = retainedWorker.get(0)[0];
            retainedOwner.get(0)[0] ^= 0x5a;
            assertEquals(original, retainedWorker.get(0)[0], "owner result cannot mutate worker result");
            assertArrayEquals(expectedOwner.get(0), retainedOwner.get(3), "retained same-page outputs are independent");
        }
    }

    @Test void simultaneouslyHeldOfficialCheckoutsHaveDifferentBuildersAndBackingMemory() throws Exception {
        var runtime = runtime();
        Class<?> builderClass = (Class<?>) field(runtime, "builderClass");
        assertNotEquals(DhOfficialRuntime.class.getClassLoader(), builderClass.getClassLoader());
        assertEquals(Path.of(builderClass.getProtectionDomain().getCodeSource().getLocation().toURI()),
                field(runtime, "jar"), "actual pinned jar defines the classes");
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var other = new AtomicReference<RawBuild>();
        try (var worker = new Worker(); var owner = rawBuild(runtime, 0xff123456)) {
            byte[] before = copy(owner.buffer);
            var future = worker.executor.submit(() -> {
                try (var raw = rawBuild(runtime, 0xffabcdef)) {
                    other.set(raw);
                    ready.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return copy(raw.buffer);
                }
            });
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
                RawBuild concurrent = other.get();
                assertNotSame(owner.builder, concurrent.builder, "thread-local builders are separate");
                assertNotSame(owner.checkout, concurrent.checkout, "active checkouts are exclusive");
                assertNotSame(owner.buffer, concurrent.buffer);
                assertArrayEquals(before, copy(owner.buffer), "worker writes cannot overwrite held owner buffer");
                byte[] workerBefore = copy(concurrent.buffer);
                owner.buffer.put(0, (byte) (owner.buffer.get(0) ^ 0x5a));
                assertArrayEquals(workerBefore, copy(concurrent.buffer), "even duplicate views cannot share active backing memory");
                release.countDown();
                assertArrayEquals(workerBefore, future.get(5, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
    }

    @Test void failedWorkerBuildClosesPartialStateBeforeNextTask() throws Exception {
        var runtime = runtime();
        var good = fixture(0, 9);
        byte[] expected = runtime.buildRectangles(good);
        var invalid = List.of(good.getFirst(), new DhTerrainGeometry.PageFace(
                DhTerrainGeometry.Direction.DOWN, 0, 0, 0, 0, 3, 5, 0xffffffff));
        try (var worker = new Worker()) {
            var failure = assertThrows(ExecutionException.class,
                    () -> worker.executor.submit(() -> runtime.buildRectangles(invalid)).get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
            for (int i = 0; i < 8; i++) assertArrayEquals(expected,
                    worker.executor.submit(() -> runtime.buildRectangles(good)).get(5, TimeUnit.SECONDS));
        }
    }

    @Test void processRuntimeAndRecyclerStaySingleAcrossWorkerOpenCalls() throws Exception {
        var runtime = runtime();
        runtime.buildRectangles(fixture(0, 10)); // publish lazy CPU buffer constants before worker startup
        Class<?> builderClass = (Class<?>) field(runtime, "builderClass");
        Method factory = (Method) field(runtime, "factory");
        Object ownerBuilder = factory.invoke(null, false, null);
        ((AutoCloseable) ownerBuilder).close();
        try (var worker = new Worker()) {
            Object first = worker.executor.submit(() -> {
                assertSame(runtime, runtime());
                Object b = factory.invoke(null, false, null);
                ((AutoCloseable) b).close();
                return b;
            }).get(5, TimeUnit.SECONDS);
            Object second = worker.executor.submit(() -> {
                assertSame(runtime, runtime());
                Object b = factory.invoke(null, false, null);
                ((AutoCloseable) b).close();
                return b;
            }).get(5, TimeUnit.SECONDS);
            assertSame(first, second, "same worker reuses bounded builder cache");
            assertNotSame(ownerBuilder, first);
            assertSame(ownerBuilder, factory.invoke(null, false, null));
            ((AutoCloseable) ownerBuilder).close();
            assertEquals(2 * 1024 * 1024, builderClass.getMethod("getMaxBufferByteSize").invoke(null));
            long recyclers = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.isAlive() && t.getName().contains("Phantom Array Recycler")).count();
            assertEquals(1, recyclers, "one official process-lifetime recycler; never shut it down per scene");
        }
    }

    @Test void cancellationAfterPartialAddsPackingAndCopyPreservesFailureAndAllowsReuse() throws Exception {
        var runtime = runtime();
        var good = fixture(0, 11);
        byte[] expected = runtime.buildRectangles(good);
        // Initial check=1, then one per face; N+2 precedes packing, N+3 follows packing,
        // N+4 precedes copying the sole output buffer, and N+5 follows that copy.
        try (var worker = new Worker()) {
            for (int cancelAt : new int[]{1, 4, good.size() + 3, good.size() + 5}) {
                var checks = new AtomicInteger();
                var sentinel = new CancellationException("cancel phase " + cancelAt);
                var failure = assertThrows(ExecutionException.class, () -> worker.executor.submit(() ->
                        runtime.buildRectangles(good, () -> {
                            if (checks.incrementAndGet() == cancelAt) throw sentinel;
                        })).get(5, TimeUnit.SECONDS));
                assertSame(sentinel, failure.getCause(), "primary cancellation is not replaced by cleanup");
                assertEquals(cancelAt, checks.get());
                assertArrayEquals(expected, worker.executor.submit(() -> runtime.buildRectangles(good)).get(5, TimeUnit.SECONDS),
                        "same thread-local builder and pool recover after phase " + cancelAt);
                assertArrayEquals(expected, runtime.buildRectangles(good), "owner diagnostic also recovers");
            }
        }
    }

    @Test void activeRecursiveCallbacksRejectBothEntrypointsWithoutCorruptingOuterOrNextBuild() throws Exception {
        var runtime = runtime();
        var good = fixture(0, 12);
        byte[] expected = runtime.buildRectangles(good);
        try (var worker = new Worker()) {
            for (int triggerAt : new int[]{1, 3}) {
                var checks = new AtomicInteger();
                byte[] outer = worker.executor.submit(() -> runtime.buildRectangles(good, () -> {
                    if (checks.incrementAndGet() == triggerAt) {
                        var first = assertThrows(IllegalStateException.class, () -> runtime.buildRectangles(good));
                        assertTrue(first.getMessage().contains("not reentrant"));
                        var second = assertThrows(IllegalStateException.class, () -> runtime.build(unitFaces(100)));
                        assertTrue(second.getMessage().contains("not reentrant"));
                    }
                })).get(5, TimeUnit.SECONDS);
                assertArrayEquals(expected, outer, "rejection leaves incumbent builder intact");
                checks.set(0);
                var uncaught = assertThrows(ExecutionException.class, () -> worker.executor.submit(() ->
                        runtime.buildRectangles(good, () -> {
                            if (checks.incrementAndGet() == triggerAt) runtime.buildRectangles(good);
                        })).get(5, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, uncaught.getCause());
                assertTrue(uncaught.getCause().getMessage().contains("not reentrant"));
                assertArrayEquals(expected, worker.executor.submit(() -> runtime.buildRectangles(good)).get(5, TimeUnit.SECONDS),
                        "uncaught recursive attempt also cleans incumbent state");
            }
        }
    }

    @Test void cancelledMonitorWaiterRemainsPendingUntilOwnerReleasesThenReturnsNoOutput() throws Exception {
        var runtime = runtime();
        var good = fixture(0, 13);
        byte[] expected = runtime.buildRectangles(good);
        var ownerChecks = new AtomicInteger();
        var waiterChecks = new AtomicInteger();
        var entered = new CountDownLatch(1);
        var cancelled = new AtomicBoolean();
        var output = new AtomicReference<byte[]>();
        var pending = new AtomicReference<Future<byte[]>>();
        var sentinel = new CancellationException("monitor waiter cancelled");
        try (var worker = new Worker()) {
            byte[] ownerOutput = runtime.buildRectangles(good, () -> {
                if (ownerChecks.incrementAndGet() != 3) return;
                pending.set(worker.executor.submit(() -> {
                    entered.countDown();
                    byte[] bytes = runtime.buildRectangles(good, () -> {
                        waiterChecks.incrementAndGet();
                        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw sentinel;
                    });
                    output.set(bytes);
                    return bytes;
                }));
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (worker.thread.get().getState() != Thread.State.BLOCKED && System.nanoTime() < deadline)
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    assertEquals(Thread.State.BLOCKED, worker.thread.get().getState(), "waiter is really blocked on incumbent monitor");
                    cancelled.set(true);
                    worker.thread.get().interrupt(); // Future.cancel would hide actual execution lifetime.
                    assertFalse(pending.get().isDone(), "cancellation request cannot terminate a monitor wait");
                    assertEquals(Thread.State.BLOCKED, worker.thread.get().getState());
                    assertEquals(0, waiterChecks.get(), "waiter has not reached first cancellation check");
                    assertNull(output.get());
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            });
            assertArrayEquals(expected, ownerOutput, "incumbent finishes uncorrupted");
            var failure = assertThrows(ExecutionException.class, () -> pending.get().get(5, TimeUnit.SECONDS));
            assertSame(sentinel, failure.getCause());
            assertEquals(1, waiterChecks.get(), "cancel immediately after acquiring monitor and before builder acquisition");
            assertNull(output.get(), "cancelled task never publishes bytes");
            assertArrayEquals(expected, worker.executor.submit(() -> runtime.buildRectangles(good)).get(5, TimeUnit.SECONDS),
                    "worker survives waiting cancellation and can compile next task");
        }
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    /** Raw references exist only in this test, so simultaneous active checkout ownership can be proved. */
    private record RawBuild(Object builder, Object checkout, ByteBuffer buffer) implements AutoCloseable {
        @Override public void close() throws Exception {
            try { ((AutoCloseable) checkout).close(); }
            finally { ((AutoCloseable) builder).close(); }
        }
    }

    @SuppressWarnings("unchecked")
    private static RawBuild rawBuild(DhOfficialRuntime runtime, int argb) throws Exception {
        Object builder = ((Method) field(runtime, "factory")).invoke(null, false, null);
        Object checkout = null;
        try {
            checkout = ((Method) field(runtime, "checkoutMethod")).invoke(field(runtime, "pool"), 1);
            ((Method) field(runtime, "addUp")).invoke(builder, (short) 1, (short) 100, (short) 2, (short) 3,
                    argb, (short) 0, (byte) 0, (byte) 15, (byte) 0);
            var buffers = (ArrayList<ByteBuffer>) ((Method) field(runtime, "opaque")).invoke(builder, checkout);
            assertEquals(1, buffers.size());
            assertEquals(64, buffers.getFirst().remaining());
            return new RawBuild(builder, checkout, buffers.getFirst());
        } catch (Throwable failure) {
            if (checkout != null) ((AutoCloseable) checkout).close();
            ((AutoCloseable) builder).close();
            throw failure;
        }
    }

    private static byte[] copy(ByteBuffer buffer) {
        var read = buffer.duplicate();
        byte[] bytes = new byte[read.remaining()];
        read.get(bytes);
        return bytes;
    }
}
