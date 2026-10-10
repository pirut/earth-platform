package io.earthplatform.minecraft.enginebridge;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeProvider;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeCubicHostTest {
    private static EnginePaletteContext palette;
    private static WorldIdentity identity;
    private static int stone, air;
    @TempDir Path temporary;
    private ThreadPoolExecutor bootstrap;
    private final List<NativeCubicHost> hosts = new ArrayList<>();
    private final List<CompletableFuture<ImmutableCube>> sources = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
        identity = new WorldIdentity(42, "host-fixture", "v1", "synthetic-stone-v1", NativeCubeCodec.paletteIdentity(palette));
        stone = Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());
        air = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
    }

    @BeforeEach void executor() {
        bootstrap = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy());
    }

    @AfterEach void cleanup() throws Exception {
        gates.forEach(CountDownLatch::countDown);
        sources.forEach(source -> source.completeExceptionally(new IOException("Test cleanup")));
        for (NativeCubicHost host : hosts) {
            host.beginClose();
            await(() -> host.pollClose().complete());
        }
        bootstrap.shutdown();
        assertTrue(bootstrap.awaitTermination(10, TimeUnit.SECONDS), "Caller-owned bootstrap must drain");
    }

    private NativeCubicHost open(CubeProvider provider, NativeCubicHost.Limits limits) throws Exception {
        return open(temporary.resolve("world"), identity, provider, limits);
    }
    private NativeCubicHost open(Path directory, WorldIdentity world, CubeProvider provider,
                                 NativeCubicHost.Limits limits) throws Exception {
        NativeCubicHost host = NativeCubicHost.openAsync(directory, world, provider, palette, limits, bootstrap)
                .get(10, TimeUnit.SECONDS);
        hosts.add(host);
        return host;
    }
    private static NativeCubicHost.Limits small() { return new NativeCubicHost.Limits(2, 2, 1, 4); }
    private static CubeProvider uniform(int state) { return (position, token) -> CompletableFuture.completedFuture(ImmutableCube.uniform(state)); }
    private CompletableFuture<ImmutableCube> source() {
        var result = new CompletableFuture<ImmutableCube>(); sources.add(result); return result;
    }
    private CountDownLatch gate() { var result = new CountDownLatch(1); gates.add(result); return result; }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("Timed out waiting for bounded asynchronous work");
            Thread.sleep(1);
        }
    }
    private static EngineCubeSnapshot ready(NativeCubicHost host, CubePos position) throws Exception {
        await(() -> host.pollSnapshot(position, 7, 11).status() != NativeCubicHost.Status.LOADING);
        var result = host.pollSnapshot(position, 7, 11);
        assertEquals(NativeCubicHost.Status.READY, result.status(), () -> "Snapshot failure: " + result.failure());
        return result.snapshot();
    }
    private static void close(NativeCubicHost host) throws Exception {
        var completed = host.beginClose();
        await(() -> host.pollClose().complete());
        completed.get(10, TimeUnit.SECONDS);
        assertEquals(NativeCubicHost.Lifecycle.CLOSED, host.lifecycle());
    }

    @Test void openDoesNotDoDiskSetupOnCallerAndCapturesThatOwner() throws Exception {
        CountDownLatch bootstrapGate = gate();
        bootstrap.execute(() -> {
            try { bootstrapGate.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        Path path = temporary.resolve("async-open");
        var opening = NativeCubicHost.openAsync(path, identity, uniform(stone), palette, small(), bootstrap);
        assertFalse(opening.isDone());
        assertFalse(opening.cancel(false), "An opening must not be abandoned by cancellation");
        assertFalse(opening.complete(null));
        assertFalse(opening.completeExceptionally(new IOException("External completion is forbidden")));
        assertThrows(UnsupportedOperationException.class, () -> opening.obtrudeValue(null));
        assertThrows(UnsupportedOperationException.class, () -> opening.obtrudeException(new IOException("External failure")));
        assertThrows(UnsupportedOperationException.class, () -> opening.completeAsync(() -> null, bootstrap));
        assertThrows(UnsupportedOperationException.class, () -> opening.orTimeout(1, TimeUnit.MILLISECONDS));
        assertThrows(UnsupportedOperationException.class, () -> opening.completeOnTimeout(null, 1, TimeUnit.MILLISECONDS));
        assertFalse(Files.exists(path), "Bootstrap setup has not run");
        bootstrapGate.countDown();
        NativeCubicHost host = opening.get(10, TimeUnit.SECONDS); hosts.add(host);
        assertEquals(NativeCubicHost.Lifecycle.OPEN, host.lifecycle());
        assertTrue(Files.exists(path.resolve("world.manifest")));
        bootstrap.submit(() -> {
            assertThrows(IllegalStateException.class, host::stats);
            assertThrows(IllegalStateException.class, host::beginClose);
            assertThrows(IllegalStateException.class, () -> host.updateTicket("wrong", new CubePos(0, 0, 0), 0, 0));
            assertThrows(IllegalStateException.class, () -> host.pollSnapshot(new CubePos(0, 0, 0), 7, 11));
        }).get(10, TimeUnit.SECONDS);
    }

    @Test void unknownLoadingAndExplicitAirAreDifferentStates() throws Exception {
        CompletableFuture<ImmutableCube> pending = source();
        CountDownLatch invoked = gate();
        Thread owner = Thread.currentThread();
        NativeCubicHost host = open((position, cancellation) -> {
            assertNotSame(owner, Thread.currentThread()); invoked.countDown(); return pending;
        }, small());
        CubePos position = new CubePos(-1, -688, 1);
        var unknown = host.pollSnapshot(position, 7, 11);
        assertEquals(NativeCubicHost.Status.UNKNOWN, unknown.status()); assertNull(unknown.snapshot()); assertNull(unknown.failure());
        host.updateTicket("camera", position, 0, 0);
        assertTrue(invoked.await(10, TimeUnit.SECONDS));
        var loading = host.pollSnapshot(position, 7, 11);
        assertEquals(NativeCubicHost.Status.LOADING, loading.status()); assertNull(loading.snapshot());
        pending.complete(ImmutableCube.uniform(air));
        EngineCubeSnapshot snapshot = ready(host, position);
        assertEquals(0, snapshot.revision()); assertTrue(snapshot.materialize(palette).isEmpty());
        assertEquals(NativeCubicHost.Status.UNKNOWN, host.pollSnapshot(position.offset(0, 1, 0), 7, 11).status());
        host.removeTicket("camera");
        assertEquals(NativeCubicHost.Status.UNKNOWN, host.pollSnapshot(position, 7, 11).status());
        assertTrue(snapshot.materialize(palette).isEmpty(), "Published immutable snapshot survives eviction");
    }

    @Test void movingFull3DBoxesKeepAtomicResidencyTicketAndLoaderBounds() throws Exception {
        NativeCubicHost host = open(uniform(stone), new NativeCubicHost.Limits(27, 2, 2, 4));
        CubePos center = null;
        for (int move = 0; move < 60; move++) {
            center = new CubePos(move * 3, (move & 1) == 0 ? -688 : 553, -move * 2);
            host.updateTicket("camera", center, 1, 1);
            assertEquals(CubeTickets.box(center, 1, 1, 27), host.residentPositions());
            var stats = host.stats();
            assertEquals(27, stats.residentCubes()); assertEquals(1, stats.tickets());
            assertTrue(stats.loader().running() <= 2); assertTrue(stats.loader().queued() <= 27);
        }
        final CubePos last = center;
        ready(host, last);
        Set<CubePos> before = host.residentPositions();
        assertThrows(IllegalArgumentException.class, () -> host.updateTicket("camera", last, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> host.updateTicket("camera", new CubePos(0, Integer.MAX_VALUE, 0), 0, 0));
        assertThrows(ArithmeticException.class, () -> host.updateTicket("camera", new CubePos(Integer.MAX_VALUE, 0, 0), 1, 0));
        assertEquals(before, host.residentPositions());
        host.updateTicket("overlap", last, 1, 1);
        assertEquals(27, host.stats().residentCubes()); assertEquals(2, host.stats().tickets());
        assertThrows(IllegalArgumentException.class, () -> host.updateTicket("third", last, 0, 0));
        host.removeTicket("camera"); assertEquals(before, host.residentPositions());
        host.removeTicket("overlap"); assertEquals(0, host.stats().residentCubes());
    }

    @Test void cancelledAsyncSourceRetainsItsSlotUntilItActuallySettles() throws Exception {
        var calls = new LinkedBlockingQueue<Call>();
        var first = source(); var second = source();
        AtomicInteger count = new AtomicInteger();
        NativeCubicHost host = open((position, token) -> {
            int number = count.getAndIncrement();
            calls.add(new Call(position, token, Thread.currentThread()));
            return number == 0 ? first : second;
        }, new NativeCubicHost.Limits(1, 1, 1, 2));
        CubePos old = new CubePos(0, -688, 0), current = new CubePos(0, 553, 0);
        host.updateTicket("camera", old, 0, 0);
        Call initial = calls.poll(10, TimeUnit.SECONDS); assertNotNull(initial);
        host.updateTicket("camera", current, 0, 0);
        assertTrue(initial.cancellation().isCancelled());
        assertEquals(1, count.get()); assertEquals(1, host.stats().loader().running()); assertEquals(1, host.stats().loader().queued());
        assertEquals(NativeCubicHost.Status.UNKNOWN, host.pollSnapshot(old, 7, 11).status());
        assertEquals(NativeCubicHost.Status.LOADING, host.pollSnapshot(current, 7, 11).status());
        first.complete(ImmutableCube.uniform(stone));
        Call next = calls.poll(10, TimeUnit.SECONDS); assertNotNull(next); assertEquals(current, next.position());
        assertNotSame(Thread.currentThread(), next.thread());
        second.complete(ImmutableCube.uniform(air));
        assertTrue(ready(host, current).materialize(palette).isEmpty());
        assertEquals(NativeCubicHost.Status.UNKNOWN, host.pollSnapshot(old, 7, 11).status());
    }

    @Test void closeRetainsOwnershipAfterExecutorTerminationUntilAsyncProviderSettles() throws Exception {
        var pending = source(); var calls = new LinkedBlockingQueue<CancellationToken>();
        NativeCubicHost host = open((position, token) -> { calls.add(token); return pending; }, small());
        CubePos position = new CubePos(0, -688, 0);
        host.updateTicket("camera", position, 0, 0);
        CancellationToken token = calls.poll(10, TimeUnit.SECONDS); assertNotNull(token);
        var completion = host.beginClose();
        assertTrue(token.isCancelled());
        await(() -> { var state = host.pollClose(); return state.storeSettled() && state.generationTerminated(); });
        var draining = host.pollClose();
        assertEquals(1, draining.runningProviders()); assertFalse(draining.complete()); assertFalse(completion.isDone());
        assertEquals(NativeCubicHost.Lifecycle.CLOSING, host.lifecycle());
        assertThrows(IllegalStateException.class, () -> host.updateTicket("camera", position, 0, 0));
        assertThrows(IllegalStateException.class, () -> host.pollSnapshot(position, 7, 11));
        assertThrows(IllegalStateException.class, () -> host.setBlockId(0, -11000, 0, air));
        ExecutionException reopen = assertThrows(ExecutionException.class,
                () -> NativeCubicHost.openAsync(temporary.resolve("world"), identity, uniform(stone), palette, small(), bootstrap).get(10, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, reopen.getCause());
        // Canonical aliases must not bypass the host-level provider-drain reservation either.
        Path alias = temporary.resolve("alias"); Files.createSymbolicLink(alias, host.directory());
        assertThrows(ExecutionException.class,
                () -> NativeCubicHost.openAsync(alias, identity, uniform(stone), palette, small(), bootstrap).get(10, TimeUnit.SECONDS));
        pending.complete(ImmutableCube.uniform(stone));
        await(() -> host.pollClose().complete()); completion.get(10, TimeUnit.SECONDS);
        NativeCubicHost reopened = open(uniform(stone), small());
        assertEquals(NativeCubicHost.Lifecycle.OPEN, reopened.lifecycle());
    }

    @Test void synchronousUncooperativeProviderAlsoKeepsClosingHostOwned() throws Exception {
        CountDownLatch started = gate(), release = gate();
        NativeCubicHost host = open((position, token) -> {
            started.countDown();
            boolean done = false;
            while (!done) {
                try { release.await(); done = true; }
                catch (InterruptedException ignored) { /* Deliberately uncooperative test source. */ }
            }
            return CompletableFuture.completedFuture(ImmutableCube.uniform(stone));
        }, small());
        host.updateTicket("camera", new CubePos(0, 0, 0), 0, 0);
        assertTrue(started.await(10, TimeUnit.SECONDS));
        var completion = host.beginClose();
        await(() -> host.pollClose().storeSettled());
        assertFalse(host.pollClose().generationTerminated());
        assertEquals(1, host.pollClose().runningProviders()); assertFalse(completion.isDone());
        assertThrows(ExecutionException.class,
                () -> NativeCubicHost.openAsync(host.directory(), identity, uniform(stone), palette, small(), bootstrap).get(10, TimeUnit.SECONDS));
        release.countDown();
        await(() -> host.pollClose().complete()); completion.get(10, TimeUnit.SECONDS);
    }

    @Test void exactDurableRevisionsExplicitAirAndStateVariantsSurviveEvictionAndReopen() throws Exception {
        NativeCubicHost host = open(uniform(stone), small());
        CubePos position = new CubePos(-1, 553, -2);
        host.updateTicket("camera", position, 0, 0);
        EngineCubeSnapshot generated = ready(host, position);
        assertSame(generated, host.pollSnapshot(position, 7, 11).snapshot());
        long x = position.minBlockX(), y = position.minBlockY(), z = position.minBlockZ();
        assertEquals(1L, host.setBlock(x + 1, y + 1, z + 1, Blocks.AIR.defaultBlockState()).get(10, TimeUnit.SECONDS));
        EngineCubeSnapshot edited = ready(host, position);
        assertEquals(1, edited.revision());
        assertEquals(air, new NativeCubeCodec(identity, palette).capture(edited).get(1, 1, 1));
        assertSame(Blocks.STONE.defaultBlockState(), generated.materialize(palette).get(1, 1, 1));
        var waterlogged = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        var accepted = host.setBlock(x + 2, y + 2, z + 2, waterlogged);
        host.removeTicket("camera");
        var closing = host.beginClose();
        assertEquals(2L, accepted.get(10, TimeUnit.SECONDS));
        await(() -> host.pollClose().complete()); closing.get(10, TimeUnit.SECONDS);
        NativeCubicHost reopened = open(uniform(stone), small());
        reopened.updateTicket("camera", position, 0, 0);
        EngineCubeSnapshot restored = ready(reopened, position);
        assertEquals(2, restored.revision()); assertEquals(position, restored.position());
        assertSame(Blocks.AIR.defaultBlockState(), restored.materialize(palette).get(1, 1, 1));
        assertSame(waterlogged, restored.materialize(palette).get(2, 2, 2));
        var stamped = reopened.pollSnapshot(position, 99, 123).snapshot();
        assertEquals(99, stamped.epoch()); assertEquals(123, stamped.leaseGeneration()); assertEquals(2, stamped.revision());
        assertEquals(new NativeCubeCodec(identity, palette).capture(restored), new NativeCubeCodec(identity, palette).capture(stamped));
    }

    @Test void editsRejectUnknownNativeStatesAndUnticketedOrLoadingCubes() throws Exception {
        var pending = source();
        NativeCubicHost host = open((position, token) -> pending, small());
        assertThrows(IllegalArgumentException.class, () -> host.setBlockId(0, 0, 0, stone));
        host.updateTicket("camera", new CubePos(0, 0, 0), 0, 0);
        assertThrows(IllegalStateException.class, () -> host.setBlockId(0, 0, 0, stone));
        pending.complete(ImmutableCube.uniform(stone)); ready(host, new CubePos(0, 0, 0));
        for (int id : new int[]{-1, Block.BLOCK_STATE_REGISTRY.size(), Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> host.setBlockId(0, 0, 0, id));
        // The native registry is frozen: do not try to construct/register a fake block here.
        assertThrows(NullPointerException.class, () -> host.setBlock(0, 0, 0, null));
        assertEquals(0, host.stats().pendingEdits());
        assertEquals(0, ready(host, new CubePos(0, 0, 0)).revision());
        assertThrows(IllegalArgumentException.class, () -> host.pollSnapshot(new CubePos(0, 0, 0), -1, 1));
        assertThrows(IllegalArgumentException.class, () -> host.pollSnapshot(new CubePos(0, 0, 0), 0, 0));
    }

    @Test void generationFailuresStayVisibleUntilExplicitRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        IOException original = new IOException("Fixture source unavailable");
        NativeCubicHost host = open((position, token) -> calls.getAndIncrement() == 0
                ? CompletableFuture.failedFuture(original) : CompletableFuture.completedFuture(ImmutableCube.uniform(stone)), small());
        CubePos position = new CubePos(0, 553, 0);
        host.updateTicket("camera", position, 0, 0);
        await(() -> host.pollSnapshot(position, 7, 11).status() == NativeCubicHost.Status.FAILED);
        var failed = host.pollSnapshot(position, 7, 11);
        assertSame(original, failed.failure()); assertNull(failed.snapshot()); assertEquals(1, calls.get());
        host.retry(position);
        assertSame(Blocks.STONE.defaultBlockState(), ready(host, position).materialize(palette).get(0, 0, 0));
        assertEquals(2, calls.get());
    }

    @Test void invalidProviderStateIsFailedRatherThanFabricatedAir() throws Exception {
        NativeCubicHost host = open(uniform(Integer.MAX_VALUE), small());
        CubePos position = new CubePos(0, 0, 0);
        host.updateTicket("camera", position, 0, 0);
        await(() -> host.pollSnapshot(position, 7, 11).status() != NativeCubicHost.Status.LOADING);
        var failed = host.pollSnapshot(position, 7, 11);
        assertEquals(NativeCubicHost.Status.FAILED, failed.status()); assertNull(failed.snapshot());
        assertInstanceOf(IllegalArgumentException.class, failed.failure());
        assertThrows(IllegalStateException.class, () -> host.retry(position));
    }

    @Test void corruptDurableRecordAndIncompatibleWorldFailVisiblyWithoutAdoptingData() throws Exception {
        CubePos position = new CubePos(0, -688, 0);
        NativeCubicHost host = open(uniform(stone), small());
        host.updateTicket("camera", position, 0, 0); ready(host, position);
        assertEquals(1L, host.setBlock(0, -11000, 0, Blocks.GOLD_BLOCK.defaultBlockState()).get(10, TimeUnit.SECONDS));
        close(host);
        WorldIdentity other = new WorldIdentity(43, identity.generatorId(), identity.generatorVersion(), identity.datasetVersion(), identity.blockPaletteVersion());
        ExecutionException mismatch = assertThrows(ExecutionException.class,
                () -> NativeCubicHost.openAsync(host.directory(), other, uniform(stone), palette, small(), bootstrap).get(10, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, mismatch.getCause());
        Files.write(host.directory().resolve("cubes/0_-688_0.cube"), new byte[]{1, 2, 3});
        NativeCubicHost reopened = open(uniform(stone), small());
        reopened.updateTicket("camera", position, 0, 0);
        await(() -> reopened.pollSnapshot(position, 7, 11).status() == NativeCubicHost.Status.FAILED);
        var failed = reopened.pollSnapshot(position, 7, 11);
        assertInstanceOf(IOException.class, failed.failure()); assertNull(failed.snapshot());
    }

    @Test void diskWriteFailureDoesNotPublishAnOptimisticRevision() throws Exception {
        NativeCubicHost host = open(uniform(stone), small());
        CubePos position = new CubePos(0, 0, 0);
        host.updateTicket("camera", position, 0, 0); EngineCubeSnapshot before = ready(host, position);
        // Test-only fault injection outside runtime methods: an invalid primary makes store.load fail.
        Files.write(host.directory().resolve("cubes/0_0_0.cube"), new byte[]{1});
        var rejected = host.setBlock(0, 0, 0, Blocks.GOLD_BLOCK.defaultBlockState());
        ExecutionException failure = assertThrows(ExecutionException.class, () -> rejected.get(10, TimeUnit.SECONDS));
        assertInstanceOf(IOException.class, failure.getCause());
        assertSame(before, ready(host, position)); assertEquals(0, ready(host, position).revision());
        assertSame(Blocks.STONE.defaultBlockState(), ready(host, position).materialize(palette).get(0, 0, 0));
    }

    @Test void bootstrapMustBeBoundedAndNeverRunRejectedSetupInline() {
        var unbounded = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        var inline = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            assertThrows(IllegalArgumentException.class, () -> NativeCubicHost.openAsync(temporary.resolve("a"), identity, uniform(stone), palette, small(), unbounded));
            assertThrows(IllegalArgumentException.class, () -> NativeCubicHost.openAsync(temporary.resolve("b"), identity, uniform(stone), palette, small(), inline));
            assertFalse(Files.exists(temporary.resolve("a"))); assertFalse(Files.exists(temporary.resolve("b")));
        } finally { unbounded.shutdown(); inline.shutdown(); }
    }

    @Test void failedStoreCloseIsVisibleAndRetainsDirectoryOwnership() throws Exception {
        NativeCubicHost host = open(uniform(stone), small());
        // Test-only fault injection into the real FileEditStore; no fake persistence implementation.
        var worldField = NativeCubicHost.class.getDeclaredField("world"); worldField.setAccessible(true);
        var storeField = io.earthplatform.core.CubicWorld.class.getDeclaredField("store"); storeField.setAccessible(true);
        var channelField = io.earthplatform.core.FileEditStore.class.getDeclaredField("lockChannel"); channelField.setAccessible(true);
        var store = storeField.get(worldField.get(host));
        ((java.nio.channels.FileChannel) channelField.get(store)).close();
        var closing = host.beginClose();
        await(() -> host.pollClose().complete());
        var status = host.pollClose();
        assertEquals(NativeCubicHost.Lifecycle.CLOSE_FAILED, status.lifecycle());
        assertInstanceOf(IOException.class, status.failure());
        assertThrows(ExecutionException.class, () -> closing.get(10, TimeUnit.SECONDS));
        ExecutionException replacement = assertThrows(ExecutionException.class,
                () -> NativeCubicHost.openAsync(host.directory(), identity, uniform(stone), palette, small(), bootstrap).get(10, TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class, replacement.getCause());
    }

    @Test void nativeHostLimitsHaveHardAllocationCaps() {
        assertThrows(IllegalArgumentException.class, () -> new NativeCubicHost.Limits(257, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new NativeCubicHost.Limits(1, 17, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new NativeCubicHost.Limits(8, 1, 3, 1));
        assertThrows(IllegalArgumentException.class, () -> new NativeCubicHost.Limits(1, 1, 1, 65));
        assertThrows(IllegalArgumentException.class, () -> new NativeCubicHost.Limits(Integer.MAX_VALUE, 1, 1, 1));
        assertDoesNotThrow(NativeCubicHost.Limits::defaults);
    }

    @Test void processHostBudgetRetainsDrainingSlotsUntilOwnerPollsSuccessfulClose() throws Exception {
        var pending = source(); CountDownLatch invoked = gate();
        NativeCubicHost draining = open((position, token) -> { invoked.countDown(); return pending; }, small());
        draining.updateTicket("camera", new CubePos(0, 0, 0), 0, 0);
        assertTrue(invoked.await(10, TimeUnit.SECONDS));
        draining.beginClose();
        await(() -> { var status = draining.pollClose(); return status.storeSettled() && status.generationTerminated(); });
        boolean rejected = false;
        for (int index = 0; index < NativeCubicHost.MAX_OWNED_HOSTS; index++) {
            try { open(temporary.resolve("fill-" + index), identity, uniform(stone), small()); }
            catch (ExecutionException limit) {
                assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, limit.getCause());
                rejected = true; break;
            }
        }
        assertTrue(rejected, "All process-wide ownership slots must be bounded, including draining sources");
        assertTrue(hosts.size() <= NativeCubicHost.MAX_OWNED_HOSTS);
        pending.complete(ImmutableCube.uniform(stone));
        await(() -> draining.stats().loader().running() == 0);
        // Worker settlement alone cannot retire owner-controlled closing ownership.
        ExecutionException beforePoll = assertThrows(ExecutionException.class,
                () -> NativeCubicHost.openAsync(temporary.resolve("replacement"), identity, uniform(stone), palette, small(), bootstrap).get(10, TimeUnit.SECONDS));
        assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, beforePoll.getCause());
        assertTrue(draining.pollClose().complete());
        NativeCubicHost replacement = open(temporary.resolve("replacement"), identity, uniform(stone), small());
        assertEquals(NativeCubicHost.Lifecycle.OPEN, replacement.lifecycle());
    }

    @Test void rejectedBootstrapFailsOpenWithoutCreatingDirectory() throws Exception {
        bootstrap.shutdown();
        Path path = temporary.resolve("rejected");
        var opening = NativeCubicHost.openAsync(path, identity, uniform(stone), palette, small(), bootstrap);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> opening.get(10, TimeUnit.SECONDS));
        assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, failure.getCause());
        assertFalse(Files.exists(path));
    }

    private record Call(CubePos position, CancellationToken cancellation, Thread thread) {}
}
