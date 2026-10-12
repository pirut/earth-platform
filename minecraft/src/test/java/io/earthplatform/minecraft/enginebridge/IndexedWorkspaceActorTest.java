package io.earthplatform.minecraft.enginebridge;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.lod.DhAnalyticFlatPolicy;
import io.earthplatform.minecraft.lod.DhReplacementInput;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
import static io.earthplatform.minecraft.enginebridge.RollingCubeActor.*;
import static org.junit.jupiter.api.Assertions.*;

class IndexedWorkspaceActorTest {
    static final CubePos A = new CubePos(0, 8, 0), B = A.offset(1, 0, 0);
    static EnginePaletteContext palette;
    static AnalyticNativeCubeProvider provider;
    static WorldIdentity world;
    static DhAnalyticFlatPolicy flat;
    static int diorite, glass;
    static final ReplacementPolicy POLICY = new ReplacementPolicy("analytic-two-workspace-v1",
            DhReplacementInput.ANALYTIC_MATERIAL_POLICY, "exact-owned18-read20-unit-faces-v1", 1, RollingEditPolicy.FULL_CUBE_V1);
    @TempDir Path temporary;
    final List<RollingCubeActor> actors = new ArrayList<>();
    final Map<RollingCubeActor, ArrayDeque<Event>> queues = new HashMap<>();

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
        provider = new AnalyticNativeCubeProvider(0, AnalyticNativeCubeProvider.Config.boundedCollision(128), palette);
        var original = provider.identity();
        world = new WorldIdentity(original.seed(), original.generatorId(), original.generatorVersion() + "/rolling-interior-v1",
                original.datasetVersion(), original.blockPaletteVersion());
        flat = new DhAnalyticFlatPolicy(world.datasetVersion(), world, 128, 0xff808080);
        diorite = Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState());
        glass = Block.BLOCK_STATE_REGISTRY.getId(Blocks.GLASS.defaultBlockState());
    }
    @AfterEach void close() throws Exception {
        actors.forEach(RollingCubeActor::close);
        for (var actor : actors) actor.closed().get(20, TimeUnit.SECONDS);
    }
    RollingCubeActor open(long epoch) {
        var actor = RollingCubeActor.openIndexed(temporary.resolve("world"), world, provider, palette, epoch, A, POLICY, flat, ReplacementWorkspaces.adjacent(A));
        actors.add(actor); queues.put(actor, new ArrayDeque<>()); return actor;
    }
    Event next(RollingCubeActor actor) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20); var queue = queues.get(actor);
        while (queue.isEmpty()) {
            queue.addAll(actor.pollEvents(2)); if (!queue.isEmpty()) break;
            assertTrue(System.nanoTime() < end, "Actor timed out: " + actor.diagnostics()); Thread.sleep(1);
        }
        var event = queue.removeFirst(); if (event instanceof Failure failure) fail(failure.detail()); return event;
    }
    record Prepared(StageMetadata metadata, StageReady ready) {
        CubeVersion version(CubePos position) { return ready.versions().stream().filter(v -> v.position().equals(position)).findFirst().orElseThrow(); }
    }
    Prepared prepare(RollingCubeActor actor) throws Exception {
        var metadata = assertInstanceOf(StageMetadata.class, next(actor));
        Event event; while ((event = next(actor)) instanceof Snapshot) { }
        var ready = assertInstanceOf(StageReady.class, event);
        assertNotNull(ready.replacementSet()); assertEquals(2, ready.replacementCaptures().size());
        var query = ready.replacementSet().queryAttestation();
        assertNotNull(query); assertTrue(query.complete()); assertTrue(query.completeCatalogue());
        assertEquals(2, query.resultCount()); query.verifyRegions(ready.replacementSet().regions());
        assertEquals(metadata.candidateFocus(), ready.activeFocus());
        assertTrue(actor.diagnostics().residentCubes() <= 213); assertTrue(actor.diagnostics().tickets() <= 3);
        return new Prepared(metadata, ready);
    }
    void commit(RollingCubeActor actor, Prepared prepared) throws Exception {
        assertEquals(Admission.QUEUED, actor.commitPrepared(prepared.ready.generation(), prepared.ready.replacementSet().identity()));
        assertEquals(prepared.ready.generation(), assertInstanceOf(Committed.class, next(actor)).generation());
    }
    Prepared move(RollingCubeActor actor, CubePos center) throws Exception {
        assertEquals(Admission.QUEUED, actor.requestCenter(center)); var prepared = prepare(actor); commit(actor, prepared); return prepared;
    }
    EditAck edit(RollingCubeActor actor, long epoch, CubeVersion version, int state) throws Exception {
        CubePos position = version.position();
        assertEquals(Admission.QUEUED, actor.tryEdit(new EditRequest(world, epoch, position, version.leaseGeneration(),
                version.revision(), position.minBlockX() + 4, 129, 4, state)));
        Event event; while ((event = next(actor)) instanceof Snapshot) { }
        return assertInstanceOf(EditAck.class, event);
    }

    @Test void editRefreshesNeighborDependenciesEvenWhenNeighborHaloIsPartlyUnloaded() throws Exception {
        var actor = open(7); var first = prepare(actor); commit(actor, first);
        var edge = move(actor, A.offset(-1, 0, 0));
        assertFalse(edge.ready.versions().stream().anyMatch(v -> v.position().equals(B.offset(1, 0, 0))));
        var prior = edge.ready.replacementSet();
        var version = edge.version(A);
        assertEquals(Admission.QUEUED, actor.tryEdit(new EditRequest(world, 7, A, version.leaseGeneration(),
                version.revision(), 15, 129, 15, diorite)));
        Event event; while ((event = next(actor)) instanceof Snapshot) { }
        var edit = assertInstanceOf(EditAck.class, event); assertTrue(edit.applied());
        var fresh = edit.replacementSet();
        fresh.verifyCaptures(edit.replacementCaptures());
        assertEquals(1, fresh.region(A).revision()); assertEquals(0, fresh.region(B).revision());
        assertNotEquals(prior.region(A).captureIdentity(), fresh.region(A).captureIdentity());
        assertNotEquals(prior.region(B).captureIdentity(), fresh.region(B).captureIdentity());
        assertEquals(prior.indexIdentity(), fresh.indexIdentity());
        assertTrue(fresh.queryAttestation().snapshotGeneration() > prior.queryAttestation().snapshotGeneration());
        assertNotEquals(prior.queryAttestation().snapshotRootIdentity(), fresh.queryAttestation().snapshotRootIdentity());
        assertNotEquals(prior.queryAttestation().resultDigest(), fresh.queryAttestation().resultDigest());
        assertEquals(1, fresh.region(B).dependencies().stream().filter(v -> v.position().equals(A)).findFirst().orElseThrow().revision());
        assertFalse(actor.replacementCurrent(prior.identity()));
        assertEquals(Admission.QUEUED, actor.authorizePinRetirement(edge.ready.generation(), fresh.identity()));
        var back = move(actor, A); assertEquals(fresh.durableIdentity(), back.ready.replacementSet().durableIdentity());
        assertEquals(Admission.QUEUED, actor.requestFocus(B, back.ready.generation(), fresh.identity()));
        var focus = prepare(actor); assertTrue(focus.metadata.currentPinned()); assertTrue(focus.metadata.candidatePinned());
        assertEquals(focus.metadata.currentLeases(), focus.metadata.candidateLeases());
        assertTrue(focus.metadata.newPositions().isEmpty()); assertTrue(focus.metadata.retiredPositions().isEmpty());
        assertEquals(A, actor.activeFocus()); commit(actor, focus); assertEquals(B, actor.activeFocus());
        String durable = fresh.durableIdentity(); actor.close(); assertTrue(actor.closed().get(20, TimeUnit.SECONDS).successful());
        var reopened = open(8); var restored = prepare(reopened);
        assertEquals(durable, restored.ready.replacementSet().durableIdentity());
        assertEquals(fresh.queryAttestation(), restored.ready.replacementSet().queryAttestation());
        assertFalse(reopened.replacementCurrent(fresh.identity()));
        assertEquals(NativeReplacementCapture.DIORITE, restored.ready.replacementCaptures().get(1).materialAt(15, 129, 15));
        commit(reopened, restored);
    }

    @Test void failedSharedEditKeepsOldCapturesImmutableAndLeavesFailClosedIntent() throws Exception {
        var actor = open(7); var first = prepare(actor); commit(actor, first);
        var prior = first.ready.replacementSet(); var captures = first.ready.replacementCaptures();
        var bytesA = captures.get(0).clonedBytes(); var bytesB = captures.get(1).clonedBytes();
        Path primary = temporary.resolve("world/cubes/0_8_0.cube"); Files.createDirectory(primary);
        CubeVersion version = first.version(A);
        assertEquals(Admission.QUEUED, actor.tryEdit(new EditRequest(world, 7, A, version.leaseGeneration(),
                version.revision(), 15, 129, 0, diorite)));
        var refused = assertInstanceOf(EditAck.class, next(actor));
        assertEquals(EditStatus.SAVE_FAILED, refused.status());
        assertNull(refused.replacementSet()); assertTrue(refused.replacementCaptures().isEmpty());
        assertArrayEquals(bytesA, captures.get(0).clonedBytes()); assertArrayEquals(bytesB, captures.get(1).clonedBytes());
        assertFalse(actor.replacementCurrent(prior.identity()));
        assertEquals(Admission.NOT_READY, actor.requestFocus(B, first.ready.generation(), prior.identity()));
        assertEquals(Admission.NOT_READY, actor.tryEdit(new EditRequest(world, 7, A, version.leaseGeneration(), 0, 15, 129, 0, diorite)));
        actor.close(); actor.closed().get(20, TimeUnit.SECONDS);
        assertThrows(java.io.IOException.class, () -> ReplacementRevisionLedger.openIndexed(temporary.resolve("world"), ReplacementWorkspaces.adjacent(A),
                ReplacementQueryAttestation.policyIdentity(world, palette.fingerprint(), POLICY, flat, ReplacementWorkspaces.adjacent(A))));
    }

    @Test void invalidMaterialAndWrongFocusCannotWriteTheAdjacentLedger() throws Exception {
        var actor = open(7); var first = prepare(actor); commit(actor, first);
        Path index = temporary.resolve("world").resolve(PagedReplacementIndex.ROOT_FILE);
        byte[] before = Files.readAllBytes(index);
        assertEquals(EditStatus.UNSUPPORTED_REPLACEMENT, edit(actor, 7, first.version(A), glass).status());
        assertEquals(EditStatus.UNKNOWN_CUBE, edit(actor, 7, first.version(B), diorite).status());
        assertArrayEquals(before, Files.readAllBytes(index)); assertEquals(0, actor.diagnostics().admittedEdits());
        assertTrue(actor.replacementCurrent(first.ready.replacementSet().identity()));
        assertEquals(Admission.QUEUED, actor.requestFocus(B, first.ready.generation(), first.ready.replacementSet().identity()));
        var focus = prepare(actor); commit(actor, focus);
        var edited = edit(actor, 7, focus.version(B), diorite); assertTrue(edited.applied());
        assertEquals(0, edited.replacementSet().region(A).revision()); assertEquals(1, edited.replacementSet().region(B).revision());
    }
    @Test void failedIntentWriteCannotStartCubeMutationOrPublishHalfSet() throws Exception {
        var actor = open(7); var first = prepare(actor); commit(actor, first);
        var captures = first.ready.replacementCaptures();
        Path index = temporary.resolve("world").resolve(PagedReplacementIndex.ROOT_FILE);
        Files.delete(index); Files.createDirectory(index); Files.writeString(index.resolve("blocker"), "keep");
        var version = first.version(A);
        assertEquals(Admission.QUEUED, actor.tryEdit(new EditRequest(world, 7, A, version.leaseGeneration(), 0, 15, 129, 15, diorite)));
        var refused = assertInstanceOf(EditAck.class, next(actor));
        assertEquals(EditStatus.SAVE_FAILED, refused.status()); assertNull(refused.replacementSet());
        assertEquals(0, actor.diagnostics().admittedEdits());
        assertEquals(NativeReplacementCapture.AIR, captures.get(0).materialAt(15, 129, 15));
        assertEquals(NativeReplacementCapture.AIR, captures.get(1).materialAt(15, 129, 15));
        assertFalse(actor.replacementCurrent(first.ready.replacementSet().identity()));
        try (var files = Files.list(temporary.resolve("world/cubes"))) { assertEquals(0, files.count()); }
        actor.close(); actor.closed().get(20, TimeUnit.SECONDS);
        assertThrows(java.io.IOException.class, () -> ReplacementRevisionLedger.openIndexed(temporary.resolve("world"), ReplacementWorkspaces.adjacent(A),
                ReplacementQueryAttestation.policyIdentity(world, palette.fingerprint(), POLICY, flat, ReplacementWorkspaces.adjacent(A))));
    }

    @Test void closeAfterSaveAdmissionDrainsTheSameJournalBeforeReleasingStore() throws Exception {
        var actor = open(7); var first = prepare(actor); commit(actor, first);
        var version = first.version(A);
        assertEquals(Admission.QUEUED, actor.tryEdit(new EditRequest(world, 7, A, version.leaseGeneration(), 0, 15, 129, 15, diorite)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (actor.diagnostics().admittedEdits() == 0) {
            assertTrue(System.nanoTime() < deadline); Thread.sleep(1);
        }
        actor.close(); assertTrue(actor.closed().get(20, TimeUnit.SECONDS).successful());
        var reopened = open(8); var restored = prepare(reopened);
        assertEquals(1, restored.ready.replacementSet().region(A).revision());
        assertEquals(0, restored.ready.replacementSet().region(B).revision());
        restored.ready.replacementSet().verifyCaptures(restored.ready.replacementCaptures());
        assertEquals(NativeReplacementCapture.DIORITE, restored.ready.replacementCaptures().get(1).materialAt(15, 129, 15));
        commit(reopened, restored);
    }

    @Test void noOpEditChangesIndexGenerationButRetirementPreservesItsExactQuery() throws Exception {
        var actor = open(7); var first = prepare(actor); commit(actor, first);
        var before = first.ready.replacementSet();
        int air = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        var applied = edit(actor, 7, first.version(A), air); assertTrue(applied.applied());
        var after = applied.replacementSet();
        assertEquals(before.region(A).captureIdentity(), after.region(A).captureIdentity());
        assertEquals(before.region(B).captureIdentity(), after.region(B).captureIdentity());
        assertEquals(before.indexIdentity(), after.indexIdentity());
        assertNotEquals(before.queryAttestation().identity(), after.queryAttestation().identity());
        assertEquals(Admission.NOT_READY, actor.authorizePinRetirement(first.ready.generation(), before.identity()));
        assertEquals(Admission.QUEUED, actor.authorizePinRetirement(first.ready.generation(), after.identity()));
        Prepared far = first;
        for (int x = 1; x <= 5; x++) far = move(actor, A.offset(x, 0, 0));
        assertFalse(far.metadata.candidatePinned());
        assertFalse(far.ready.versions().stream().anyMatch(v -> v.position().equals(A) || v.position().equals(B)));
        assertEquals(after.queryAttestation(), far.ready.replacementSet().queryAttestation());
        assertTrue(actor.replacementCurrent(after.identity()));
        assertTrue(actor.diagnostics().residentCubes() <= 152); assertTrue(actor.diagnostics().tickets() <= 2);
    }

    @Test void missingIndexPageRefusesBeforeIntentOrCubeSave() throws Exception {
        var actor = open(7); var first = prepare(actor); commit(actor, first);
        Path directory = temporary.resolve("world"); byte[] root = Files.readAllBytes(directory.resolve(PagedReplacementIndex.ROOT_FILE));
        try (var files = Files.list(directory)) {
            Path page = files.filter(p -> p.getFileName().toString().startsWith("replacement-paged-v1-page-")).findFirst().orElseThrow();
            Files.delete(page);
        }
        var refused = edit(actor, 7, first.version(A), diorite);
        assertEquals(EditStatus.SAVE_FAILED, refused.status()); assertNull(refused.replacementSet());
        assertEquals(0, actor.diagnostics().admittedEdits());
        assertFalse(actor.replacementCurrent(first.ready.replacementSet().identity()));
        assertArrayEquals(root, Files.readAllBytes(directory.resolve(PagedReplacementIndex.ROOT_FILE)));
        assertFalse(Files.exists(directory.resolve(PagedReplacementIndex.INTENT_FILE)));
        try (var files = Files.list(directory.resolve("cubes"))) { assertEquals(0, files.count()); }
    }

    @Test void syntheticThirdCatalogueRegionCannotStartNativeBootstrapOrDurability() throws Exception {
        Path directory = Files.createDirectory(temporary.resolve("world"));
        String scope = ReplacementQueryAttestation.policyIdentity(world, palette.fingerprint(), POLICY, flat, ReplacementWorkspaces.adjacent(A));
        var index = PagedReplacementIndex.open(directory, scope);
        index.seedCatalogueForTests(List.of(A, B, B.offset(1, 0, 0)).stream()
                .map(p -> new PagedReplacementIndex.Entry(p, 0, scope, "a".repeat(64))).iterator());
        byte[] before = Files.readAllBytes(directory.resolve(PagedReplacementIndex.ROOT_FILE));
        var actor = open(7);
        assertFalse(actor.closed().get(20, TimeUnit.SECONDS).successful());
        assertEquals(0, actor.diagnostics().admittedEdits()); assertEquals(0, actor.diagnostics().publishedSnapshots());
        assertArrayEquals(before, Files.readAllBytes(directory.resolve(PagedReplacementIndex.ROOT_FILE)));
        assertFalse(Files.exists(directory.resolve(PagedReplacementIndex.INTENT_FILE)));
    }

}
