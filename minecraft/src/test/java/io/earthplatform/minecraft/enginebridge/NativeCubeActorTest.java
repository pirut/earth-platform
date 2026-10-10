package io.earthplatform.minecraft.enginebridge;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CancellationToken;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.CubeProvider;
import io.earthplatform.core.CubeTickets;
import io.earthplatform.core.FileEditStore;
import io.earthplatform.core.ImmutableCube;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static io.earthplatform.minecraft.enginebridge.NativeCubeActor.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeCubeActorTest {
    private static EnginePaletteContext palette;
    private static AnalyticNativeCubeProvider provider;
    private static WorldIdentity world;
    private static int air, stone, grass, diorite;
    private static final CubePos CENTER = new CubePos(0, 8, 0);
    @TempDir Path temporary;
    private final List<NativeCubeActor> actors = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();
    private final List<CompletableFuture<ImmutableCube>> providers = new ArrayList<>();

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
        provider = new AnalyticNativeCubeProvider(42, AnalyticNativeCubeProvider.Config.flat(128), palette);
        world = provider.identity();
        air = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        stone = Block.BLOCK_STATE_REGISTRY.getId(Blocks.STONE.defaultBlockState());
        grass = Block.BLOCK_STATE_REGISTRY.getId(Blocks.GRASS_BLOCK.defaultBlockState());
        diorite = Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState());
    }

    @AfterEach void cleanup() throws Exception {
        gates.forEach(CountDownLatch::countDown);
        providers.forEach(source -> source.completeExceptionally(new IOException("Test cleanup")));
        for (NativeCubeActor actor : actors) actor.close();
        for (NativeCubeActor actor : actors) actor.closed().get(10, TimeUnit.SECONDS);
    }

    private NativeCubeActor open() { return open(temporary.resolve("world"), provider, 7); }
    private NativeCubeActor open(Path directory, CubeProvider source, long epoch) {
        NativeCubeActor actor = NativeCubeActor.open(directory, world, source, palette, epoch, CENTER);
        actors.add(actor); return actor;
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("Timed out waiting for bounded actor work");
            Thread.sleep(1);
        }
    }
    private static List<Event> collect(NativeCubeActor actor, int snapshotCount) throws Exception {
        List<Event> events = new ArrayList<>();
        await(() -> {
            List<Event> batch = actor.pollEvents(Integer.MAX_VALUE);
            assertTrue(batch.size() <= MAX_EVENTS_PER_POLL);
            for (Event event : batch) {
                if (event instanceof Failure failed) fail(failed.detail());
                events.add(event);
            }
            return events.stream().filter(Snapshot.class::isInstance).count() >= snapshotCount;
        });
        return events;
    }
    private static EngineCubeSnapshot centerSnapshot(List<Event> events) {
        return events.stream().filter(Snapshot.class::isInstance).map(Snapshot.class::cast)
                .map(Snapshot::snapshot).filter(snapshot -> snapshot.position().equals(CENTER)).findFirst().orElseThrow();
    }
    private static EditRequest request(EngineCubeSnapshot snapshot, int state) {
        return new EditRequest(snapshot.world(), snapshot.epoch(), snapshot.position(), snapshot.leaseGeneration(),
                snapshot.revision(), 4, 129, 4, state);
    }
    private static EditAck ack(NativeCubeActor actor, List<Event> events) throws Exception {
        AtomicReference<EditAck> result = new AtomicReference<>();
        await(() -> {
            for (Event event : actor.pollEvents(MAX_EVENTS_PER_POLL)) {
                events.add(event);
                if (event instanceof Failure failed) fail(failed.detail());
                if (event instanceof EditAck found) result.set(found);
            }
            return result.get() != null;
        });
        return result.get();
    }
    private static EditAck reject(NativeCubeActor actor, EditRequest request, EditStatus expected) throws Exception {
        assertEquals(Admission.QUEUED, actor.tryEdit(request));
        EditAck result = ack(actor, new ArrayList<>());
        assertEquals(expected, result.status()); assertFalse(result.applied()); return result;
    }

    @Test void fixedInterestMetadataLeasesAndDeliveryAreBoundedAndImmutable() throws Exception {
        NativeCubeActor actor = open();
        await(() -> actor.diagnostics().queuedEvents() == MAX_RESULT_EVENTS);
        int published = actor.diagnostics().publishedSnapshots();
        assertEquals(3, published, "Metadata occupies one of the four result slots");
        Thread.sleep(50);
        assertEquals(published, actor.diagnostics().publishedSnapshots(), "Backpressure stops encoding");
        assertEquals(MAX_RESULT_EVENTS, actor.diagnostics().queuedEvents());
        assertEquals(0, actor.pollEvents(0).size());
        assertThrows(IllegalArgumentException.class, () -> actor.pollEvents(-1));
        List<Event> events = collect(actor, RESIDENT_CUBES);
        ReadyMetadata metadata = assertInstanceOf(ReadyMetadata.class, events.getFirst());
        assertEquals(world, metadata.world()); assertEquals(7, metadata.epoch()); assertEquals(CENTER, metadata.center());
        assertEquals(RESIDENT_CUBES, metadata.leases().size()); assertEquals(RENDER_TARGETS, metadata.renderTargets().size());
        assertEquals(CubeTickets.box(CENTER, 1, 1, RENDER_TARGETS).stream().sorted().toList(), metadata.renderTargets());
        List<CubePos> positions = CubeTickets.box(CENTER, 2, 2, RESIDENT_CUBES).stream().sorted().toList();
        for (int index = 0; index < RESIDENT_CUBES; index++) {
            assertEquals(positions.get(index), metadata.leases().get(index).position());
            assertEquals(index + 1L, metadata.leases().get(index).generation());
            EngineCubeSnapshot snapshot = ((Snapshot) events.get(index + 1)).snapshot();
            assertEquals(positions.get(index), snapshot.position());
            assertEquals(index + 1L, snapshot.leaseGeneration()); assertEquals(0, snapshot.revision());
        }
        assertThrows(UnsupportedOperationException.class, () -> metadata.leases().clear());
        assertThrows(UnsupportedOperationException.class, () -> metadata.renderTargets().clear());
        assertSame(Blocks.STONE.defaultBlockState(), centerSnapshot(events).materialize(palette).get(4, 0, 4));
        assertSame(Blocks.AIR.defaultBlockState(), centerSnapshot(events).materialize(palette).get(4, 1, 4));
        assertEquals(RESIDENT_CUBES, actor.diagnostics().publishedSnapshots());
    }

    @Test void invalidIdentityEpochLeaseRevisionCoordinatesAndStatesNeverReachTheStore() throws Exception {
        NativeCubeActor actor = open();
        EngineCubeSnapshot snapshot = centerSnapshot(collect(actor, RESIDENT_CUBES));
        long lease = snapshot.leaseGeneration();
        var other = new WorldIdentity(99, world.generatorId(), world.generatorVersion(), world.datasetVersion(), world.blockPaletteVersion());
        reject(actor, new EditRequest(other, 7, CENTER, lease, 0, 4, 129, 4, diorite), EditStatus.WORLD_MISMATCH);
        reject(actor, new EditRequest(world, 6, CENTER, lease, 0, 4, 129, 4, diorite), EditStatus.STALE_EPOCH);
        reject(actor, new EditRequest(world, 7, new CubePos(500, 8, 0), lease, 0, 8004, 129, 4, diorite), EditStatus.UNKNOWN_CUBE);
        reject(actor, new EditRequest(world, 7, CENTER, lease + 1, 0, 4, 129, 4, diorite), EditStatus.STALE_LEASE);
        reject(actor, new EditRequest(world, 7, CENTER, lease, 1, 4, 129, 4, diorite), EditStatus.STALE_REVISION);
        reject(actor, new EditRequest(world, 7, CENTER, lease, -1, 4, 129, 4, diorite), EditStatus.STALE_REVISION);
        reject(actor, new EditRequest(world, 7, CENTER, lease, 0, 20, 129, 4, diorite), EditStatus.POSITION_MISMATCH);
        reject(actor, new EditRequest(world, 7, CENTER, lease, 0, Long.MAX_VALUE, 129, 4, diorite), EditStatus.POSITION_MISMATCH);
        reject(actor, new EditRequest(world, 7, CENTER, lease, 0, 4, 129, 4, -1), EditStatus.INVALID_STATE);
        reject(actor, new EditRequest(world, 7, CENTER, lease, 0, 4, 129, 4, Integer.MAX_VALUE), EditStatus.INVALID_STATE);
        assertEquals(0, actor.diagnostics().admittedEdits());
        assertFalse(Files.exists(temporary.resolve("world/cubes/0_8_0.cube")));
    }

    @Test void onlyOneEditCanBePendingIncludingAnUnconsumedAcknowledgement() throws Exception {
        NativeCubeActor actor = open();
        EditRequest request = request(centerSnapshot(collect(actor, RESIDENT_CUBES)), diorite);
        assertEquals(Admission.QUEUED, actor.tryEdit(request));
        assertEquals(Admission.BUSY, actor.tryEdit(request));
        await(() -> actor.diagnostics().queuedEvents() == 2);
        assertTrue(actor.diagnostics().pendingEdit());
        assertEquals(Admission.BUSY, actor.tryEdit(request));
        List<Event> events = new ArrayList<>();
        EditAck applied = ack(actor, events);
        assertTrue(applied.applied()); assertEquals(1, applied.revision());
        assertInstanceOf(Snapshot.class, events.getFirst()); assertInstanceOf(EditAck.class, events.get(1));
        assertEquals(1, ((Snapshot) events.getFirst()).snapshot().revision());
        assertFalse(actor.diagnostics().pendingEdit());
        reject(actor, request, EditStatus.STALE_REVISION);
        assertEquals(1, actor.diagnostics().admittedEdits());
    }

    @Test void fullOutputBackpressuresTheSingleCommandSlotAndCloseStillCompletes() throws Exception {
        NativeCubeActor actor = open();
        await(() -> actor.diagnostics().queuedEvents() == MAX_RESULT_EVENTS);
        long centerLease = CubeTickets.box(CENTER, 2, 2, RESIDENT_CUBES).stream().sorted().toList().indexOf(CENTER) + 1L;
        EditRequest request = new EditRequest(world, 7, CENTER, centerLease, 0, 4, 129, 4, diorite);
        assertEquals(Admission.QUEUED, actor.tryEdit(request));
        assertEquals(Admission.BUSY, actor.tryEdit(request));
        Thread.sleep(30);
        assertEquals(1, actor.diagnostics().queuedCommands());
        assertEquals(MAX_RESULT_EVENTS, actor.diagnostics().queuedEvents());
        assertEquals(0, actor.diagnostics().admittedEdits());
        actor.close(); assertTrue(actor.closed().get(10, TimeUnit.SECONDS).successful());
        List<Event> terminal = actor.pollEvents(2);
        assertEquals(EditStatus.CLOSING, assertInstanceOf(EditAck.class, terminal.getFirst()).status());
        assertInstanceOf(Closed.class, terminal.get(1));
        try (FileEditStore store = new FileEditStore(temporary.resolve("world"), world)) {
            assertEquals(0, store.load(CENTER).snapshot().revision());
        }
    }

    @Test void durableExactStateAndRevisionReopenWithANewEpoch() throws Exception {
        NativeCubeActor actor = open();
        EngineCubeSnapshot initial = centerSnapshot(collect(actor, RESIDENT_CUBES));
        assertEquals(Admission.QUEUED, actor.tryEdit(request(initial, diorite)));
        List<Event> events = new ArrayList<>(); EditAck saved = ack(actor, events);
        assertEquals(1, saved.revision());
        EngineCubeSnapshot changed = ((Snapshot) events.getFirst()).snapshot();
        assertSame(Blocks.DIORITE.defaultBlockState(), changed.materialize(palette).get(4, 1, 4));
        assertSame(Blocks.AIR.defaultBlockState(), initial.materialize(palette).get(4, 1, 4));
        actor.close(); assertTrue(actor.closed().get(10, TimeUnit.SECONDS).successful());
        try (FileEditStore store = new FileEditStore(temporary.resolve("world"), world)) {
            var edits = store.load(CENTER).snapshot();
            assertEquals(1, edits.revision()); assertEquals(diorite, edits.blocks().get(CubePos.index(4, 1, 4)));
        }
        NativeCubeActor reopened = open(temporary.resolve("world"), provider, 88);
        EngineCubeSnapshot restored = centerSnapshot(collect(reopened, RESIDENT_CUBES));
        assertEquals(88, restored.epoch()); assertEquals(1, restored.revision());
        assertSame(Blocks.DIORITE.defaultBlockState(), restored.materialize(palette).get(4, 1, 4));
        assertEquals(Admission.QUEUED, reopened.tryEdit(new EditRequest(world, 88, CENTER, restored.leaseGeneration(),
                1, 4, 128, 4, grass)));
        List<Event> second = new ArrayList<>(); assertEquals(2, ack(reopened, second).revision());
        EngineCubeSnapshot grassed = ((Snapshot) second.getFirst()).snapshot();
        assertSame(Blocks.GRASS_BLOCK.defaultBlockState(), grassed.materialize(palette).get(4, 0, 4));
        assertSame(Blocks.DIORITE.defaultBlockState(), grassed.materialize(palette).get(4, 1, 4));
    }

    @Test void closeDuringAnAdmittedRealSaveDrainsWithoutAnyFurtherConsumerPolls() throws Exception {
        NativeCubeActor actor = open();
        EngineCubeSnapshot initial = centerSnapshot(collect(actor, RESIDENT_CUBES));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); gates.add(release);
        // Test-only gate on the actual core I/O executor. No replacement/fake EditStore is involved.
        Object host = field(actor, "host"); Object core = field(host, "world");
        ThreadPoolExecutor io = (ThreadPoolExecutor) field(core, "io");
        io.execute(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        assertEquals(Admission.QUEUED, actor.tryEdit(request(initial, diorite)));
        await(() -> actor.diagnostics().admittedEdits() == 1);
        actor.close();
        assertSame(actor.closed(), actor.closed(), "Repeated tick polling must not accumulate dependent futures");
        assertFalse(actor.closed().cancel(false));
        assertFalse(actor.closed().complete(new Closed(true, "External completion forbidden")));
        assertFalse(actor.closed().completeExceptionally(new IOException("External completion forbidden")));
        assertThrows(UnsupportedOperationException.class, () -> actor.closed().obtrudeValue(null));
        assertThrows(UnsupportedOperationException.class, () -> actor.closed().obtrudeException(new IOException("forbidden")));
        assertThrows(UnsupportedOperationException.class, () -> actor.closed().orTimeout(1, TimeUnit.MILLISECONDS));
        assertThrows(UnsupportedOperationException.class, () -> actor.closed().completeOnTimeout(null, 1, TimeUnit.MILLISECONDS));
        assertThrows(UnsupportedOperationException.class, () -> actor.closed().completeAsync(() -> null));
        assertThrows(UnsupportedOperationException.class, () -> actor.closed().completeAsync(() -> null, Runnable::run));
        var abandoned = actor.closed().copy(); assertTrue(abandoned.cancel(false));
        await(() -> actor.diagnostics().lifecycle() == NativeCubeActor.Lifecycle.CLOSING);
        assertFalse(actor.closed().isDone(), "Close must wait for the real admitted save");
        assertEquals(Admission.CLOSING, actor.tryEdit(request(initial, stone)));
        release.countDown();
        assertTrue(actor.closed().get(10, TimeUnit.SECONDS).successful(), "The actor must finish without server ticks");
        assertTrue(actor.diagnostics().queuedEvents() <= MAX_RESULT_EVENTS);
        List<Event> terminal = new ArrayList<>(actor.pollEvents(2)); terminal.addAll(actor.pollEvents(2));
        assertInstanceOf(Snapshot.class, terminal.getFirst());
        EditAck saved = assertInstanceOf(EditAck.class, terminal.get(1));
        assertEquals(EditStatus.APPLIED, saved.status()); assertEquals(1, saved.revision());
        assertInstanceOf(Closed.class, terminal.get(2));
        try (FileEditStore store = new FileEditStore(temporary.resolve("world"), world)) {
            var edits = store.load(CENTER).snapshot(); assertEquals(1, edits.revision());
            assertEquals(diorite, edits.blocks().get(CubePos.index(4, 1, 4)));
        }
    }

    @Test void closeKeepsProviderOwnershipAfterServerStopsAndOutputIsAbandoned() throws Exception {
        var pending = new CompletableFuture<ImmutableCube>(); providers.add(pending);
        var called = new CountDownLatch(1); var token = new AtomicReference<CancellationToken>();
        NativeCubeActor actor = open(temporary.resolve("world"), (position, cancellation) -> {
            token.set(cancellation); called.countDown(); return pending;
        }, 7);
        assertTrue(called.await(10, TimeUnit.SECONDS));
        actor.close();
        await(() -> token.get().isCancelled());
        assertFalse(actor.closed().isDone());
        NativeCubeActor conflicting = open(temporary.resolve("world"), provider, 8);
        assertFalse(conflicting.closed().get(10, TimeUnit.SECONDS).successful());
        pending.complete(ImmutableCube.uniform(air));
        assertTrue(actor.closed().get(10, TimeUnit.SECONDS).successful());
        NativeCubeActor reopened = open(temporary.resolve("world"), provider, 9);
        assertEquals(9, centerSnapshot(collect(reopened, RESIDENT_CUBES)).epoch());
    }

    @Test void stoppingBeforeOpenCompletesNeverAbandonsAWriter() throws Exception {
        NativeCubeActor actor = open(); actor.close();
        assertTrue(actor.closed().get(10, TimeUnit.SECONDS).successful());
        try (FileEditStore store = new FileEditStore(temporary.resolve("world"), world)) {
            assertEquals(world, store.identity());
        }
    }

    @Test void failedDurableSaveDoesNotEmitAnOptimisticSnapshotOrRevision() throws Exception {
        NativeCubeActor actor = open();
        EngineCubeSnapshot initial = centerSnapshot(collect(actor, RESIDENT_CUBES));
        Files.write(temporary.resolve("world/cubes/0_8_0.cube"), new byte[]{1});
        assertEquals(Admission.QUEUED, actor.tryEdit(request(initial, diorite)));
        List<Event> events = new ArrayList<>(); EditAck failed = ack(actor, events);
        assertEquals(EditStatus.SAVE_FAILED, failed.status()); assertEquals(-1, failed.revision());
        assertEquals(1, events.size()); assertEquals(RESIDENT_CUBES, actor.diagnostics().publishedSnapshots());
    }

    @Test void loadingCubeRejectsEditsInsteadOfInventingAir() throws Exception {
        var pending = new CompletableFuture<ImmutableCube>(); providers.add(pending);
        NativeCubeActor actor = open(temporary.resolve("world"), (position, token) -> pending, 7);
        AtomicReference<ReadyMetadata> metadata = new AtomicReference<>();
        await(() -> {
            for (Event event : actor.pollEvents(2)) if (event instanceof ReadyMetadata ready) metadata.set(ready);
            return metadata.get() != null;
        });
        long lease = metadata.get().leases().stream().filter(item -> item.position().equals(CENTER)).findFirst().orElseThrow().generation();
        reject(actor, new EditRequest(world, 7, CENTER, lease, 0, 4, 129, 4, diorite), EditStatus.NOT_READY);
        assertEquals(0, actor.diagnostics().admittedEdits());
    }

    @Test void generationFailureIsVisibleAndReleasesTheDirectoryForAReplacement() throws Exception {
        NativeCubeActor actor = open(temporary.resolve("world"), (position, cancellation) ->
                CompletableFuture.failedFuture(new IOException("Test generation failure")), 7);
        assertFalse(actor.closed().get(10, TimeUnit.SECONDS).successful());
        List<Event> events = new ArrayList<>(actor.pollEvents(2)); events.addAll(actor.pollEvents(2));
        assertTrue(events.stream().anyMatch(Failure.class::isInstance));
        assertInstanceOf(Closed.class, events.getLast());
        NativeCubeActor replacement = open(temporary.resolve("world"), provider, 8);
        assertEquals(8, centerSnapshot(collect(replacement, RESIDENT_CUBES)).epoch());
    }

    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
}
