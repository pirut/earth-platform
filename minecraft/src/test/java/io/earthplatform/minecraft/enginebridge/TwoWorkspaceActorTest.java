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

class TwoWorkspaceActorTest {
    static final CubePos A = new CubePos(0, 8, 0), B = A.offset(4, 0, 0);
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
        var actor = RollingCubeActor.open(temporary.resolve("world"), world, provider, palette, epoch, A, POLICY, flat);
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

    @Test void bootstrapRetiresBothSerialHalosAndAccountsExactInitialLeaseFloor() throws Exception {
        var actor = open(7); var first = prepare(actor);
        assertEquals(55, first.metadata.candidateLeases().getFirst().leaseGeneration());
        assertEquals(179, first.metadata.candidateLeases().getLast().leaseGeneration());
        assertEquals(125, first.ready.versions().size());
        assertFalse(first.ready.versions().stream().anyMatch(v -> v.position().equals(B)));
        assertEquals(0, first.ready.replacementSet().region(A).revision());
        assertEquals(0, first.ready.replacementSet().region(B).revision());
        assertEquals(54, first.ready.replacementSet().regions().stream().flatMap(r -> r.dependencies().stream())
                .mapToLong(CubeVersion::leaseGeneration).max().orElseThrow());
        assertEquals(Admission.NOT_READY, actor.commitPrepared(first.ready.generation(), null));
        commit(actor, first);
        assertTrue(actor.replacementCurrent(first.ready.replacementSet().identity()));
        assertEquals(Admission.NOT_READY, actor.retainPinWithoutReplacement(first.ready.generation()));
    }

    @Test void editsBothWorkspacesWithOneFocusAndRestoresBothAfterJvmStyleReopen() throws Exception {
        var actor = open(7); var first = prepare(actor); commit(actor, first);
        var editedA = edit(actor, 7, first.version(A), diorite); assertTrue(editedA.applied());
        assertEquals(1, editedA.replacementSet().region(A).revision());
        assertEquals(Admission.QUEUED, actor.authorizePinRetirement(first.ready.generation(), editedA.replacementSet().identity()));
        assertEquals(Admission.NOT_READY, actor.requestFocus(B, first.ready.generation(), editedA.replacementSet().identity()));
        Prepared atB = null; for (int x = 1; x <= 4; x++) atB = move(actor, A.offset(x, 0, 0));
        assertFalse(atB.ready.pinned());
        assertEquals(Admission.NOT_READY, actor.requestFocus(B, atB.ready.generation(), first.ready.replacementSet().identity()));
        assertEquals(Admission.QUEUED, actor.requestFocus(B, atB.ready.generation(), atB.ready.replacementSet().identity()));
        var focus = prepare(actor); assertEquals(A, actor.currentFocus()); assertEquals(A, focus.metadata.currentFocus());
        assertEquals(B, focus.metadata.candidateFocus()); assertTrue(focus.metadata.newPositions().isEmpty());
        assertFalse(focus.metadata.currentPinned()); assertTrue(focus.metadata.candidatePinned());
        commit(actor, focus); assertEquals(B, actor.currentFocus());
        var editedB = edit(actor, 7, focus.version(B), diorite); assertTrue(editedB.applied());
        assertEquals(1, editedB.replacementSet().region(A).revision()); assertEquals(1, editedB.replacementSet().region(B).revision());
        assertFalse(actor.replacementCurrent(editedA.replacementSet().identity()));
        assertEquals(Admission.QUEUED, actor.authorizePinRetirement(focus.ready.generation(), editedB.replacementSet().identity()));
        Prepared back = null; for (int x = 3; x >= 0; x--) back = move(actor, A.offset(x, 0, 0));
        assertFalse(back.ready.pinned());
        assertFalse(back.ready.versions().stream().anyMatch(v -> v.position().equals(B)));
        assertTrue(back.version(A).leaseGeneration() > first.version(A).leaseGeneration());
        assertEquals(1, back.version(A).revision());
        assertEquals(Admission.QUEUED, actor.requestFocus(A, back.ready.generation(), back.ready.replacementSet().identity()));
        var returnFocus = prepare(actor); commit(actor, returnFocus);
        assertEquals(EditStatus.STALE_LEASE, edit(actor, 7, first.version(A), diorite).status());
        var revisedA = edit(actor, 7, returnFocus.version(A), diorite); assertTrue(revisedA.applied());
        assertEquals(2, revisedA.replacementSet().region(A).revision());
        assertEquals(1, revisedA.replacementSet().region(B).revision());
        String durable = revisedA.replacementSet().durableIdentity();
        actor.close(); assertTrue(actor.closed().get(20, TimeUnit.SECONDS).successful());
        var reopened = open(8); var restored = prepare(reopened);
        assertEquals(durable, restored.ready.replacementSet().durableIdentity());
        assertFalse(reopened.replacementCurrent(editedB.replacementSet().identity()));
        assertEquals(1, restored.ready.replacementSet().region(B).revision());
        assertFalse(restored.ready.versions().stream().anyMatch(v -> v.position().equals(B)));
        assertEquals(NativeReplacementCapture.DIORITE, restored.ready.replacementCaptures().get(1).materialAt(68, 129, 4));
        commit(reopened, restored);
    }

    @Test void unsupportedWrongFocusAndStaleEpochNeverChangeDurableIndexOrAdmitSave() throws Exception {
        var actor = open(7); var first = prepare(actor); commit(actor, first);
        Path index = temporary.resolve("world").resolve(ReplacementStoreIdentity.TWO_FILE); byte[] before = Files.readAllBytes(index);
        assertEquals(EditStatus.UNSUPPORTED_REPLACEMENT, edit(actor, 7, first.version(A), glass).status());
        assertEquals(EditStatus.STALE_EPOCH, edit(actor, 6, first.version(A), diorite).status());
        var unknown = new CubeVersion(B, first.version(A).leaseGeneration(), 0);
        assertEquals(EditStatus.UNKNOWN_CUBE, edit(actor, 7, unknown, diorite).status());
        assertArrayEquals(before, Files.readAllBytes(index)); assertEquals(0, actor.diagnostics().admittedEdits());
        assertTrue(actor.replacementCurrent(first.ready.replacementSet().identity()));
        try (var files = Files.list(temporary.resolve("world/cubes"))) { assertEquals(0, files.count()); }
    }

    @Test void failedCubeSaveLeavesPendingJournalAndRejectsAllOldReplacementAuthority() throws Exception {
        var actor = open(7); var first = prepare(actor); commit(actor, first);
        var applied = edit(actor, 7, first.version(A), diorite); assertTrue(applied.applied());
        assertEquals(Admission.QUEUED, actor.authorizePinRetirement(first.ready.generation(), applied.replacementSet().identity()));
        Path primary = temporary.resolve("world/cubes/0_8_0.cube"); Files.delete(primary); Files.createDirectory(primary);
        var current = new CubeVersion(A, first.version(A).leaseGeneration(), applied.revision());
        assertEquals(EditStatus.SAVE_FAILED, edit(actor, 7, current, diorite).status());
        assertFalse(actor.replacementCurrent(applied.replacementSet().identity()));
        assertEquals(Admission.NOT_READY, actor.requestCenter(A.offset(1, 0, 0)));
        assertEquals(Admission.NOT_READY, actor.authorizePinRetirement(first.ready.generation(), applied.replacementSet().identity()));
        actor.close(); actor.closed().get(20, TimeUnit.SECONDS);
        assertThrows(java.io.IOException.class, () -> ReplacementRevisionLedger.open(temporary.resolve("world"), ReplacementWorkspaces.two(A)));
    }

    @Test void missingEditedSecondRegionFencesStartupBeforeAnyInitialPair() throws Exception {
        Path directory = temporary.resolve("world");
        try (var store = new io.earthplatform.core.FileEditStore(directory, world)) {
            var ledger = ReplacementRevisionLedger.open(directory, ReplacementWorkspaces.two(A));
            ledger.begin(B, 0);
            store.save(io.earthplatform.core.EditSnapshot.empty(B).withBlock(CubePos.index(4, 1, 4), diorite));
            ledger.confirm(B, 1);
        }
        Files.delete(directory.resolve("cubes/4_8_0.cube"));
        var actor = open(8); var closed = actor.closed().get(20, TimeUnit.SECONDS);
        assertFalse(closed.successful()); assertTrue(closed.detail().contains("expected 1, observed 0"));
        var events = actor.pollEvents(2);
        assertTrue(events.stream().anyMatch(event -> event instanceof Failure));
        assertTrue(events.stream().noneMatch(event -> event instanceof StageReady || event instanceof StageMetadata));
    }

    @Test void cancelDuringPartialSerialBootstrapDoesNotPersistRuntimeAuthority() throws Exception {
        var actor = open(7); long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (actor.diagnostics().readyCubes() == 0) {
            assertTrue(System.nanoTime() < end); Thread.sleep(1);
        }
        actor.close(); assertTrue(actor.closed().get(20, TimeUnit.SECONDS).successful());
        var reopened = open(8); var initial = prepare(reopened);
        assertEquals(55, initial.metadata.candidateLeases().getFirst().leaseGeneration());
        assertEquals(0, initial.ready.replacementSet().region(A).revision());
        assertEquals(0, initial.ready.replacementSet().region(B).revision());
        commit(reopened, initial);
    }

    @Test void queuedFarGoalsDuringFocusAndRefusedEditWaitForExactRetirementAuthorization() throws Exception {
        var actor = open(7); var first = prepare(actor); commit(actor, first);
        assertEquals(Admission.QUEUED, actor.authorizePinRetirement(first.ready.generation(), first.ready.replacementSet().identity()));
        Prepared nearB = null; for (int x = 1; x <= 4; x++) nearB = move(actor, A.offset(x, 0, 0));
        assertEquals(Admission.QUEUED, actor.requestFocus(B, nearB.ready.generation(), nearB.ready.replacementSet().identity()));
        var focus = prepare(actor);
        CubePos goal = B.offset(5, 0, 0);
        assertEquals(Admission.QUEUED, actor.requestCenter(goal), "A goal may be coalesced while focus waits for paired commit");
        commit(actor, focus);
        var adjacent = prepare(actor); commit(actor, adjacent);
        assertEquals(B.offset(1, 0, 0), adjacent.ready.candidateCenter());
        assertPinnedGoalStalled(actor, adjacent);
        assertEquals(Admission.NOT_READY, actor.authorizePinRetirement(adjacent.ready.generation(), "0".repeat(64)));
        assertPinnedGoalStalled(actor, adjacent);
        assertEquals(Admission.QUEUED, actor.authorizePinRetirement(adjacent.ready.generation(), adjacent.ready.replacementSet().identity()));
        Prepared far = adjacent;
        for (int x = 2; x <= 5; x++) {
            far = prepare(actor); assertEquals(B.offset(x, 0, 0), far.ready.candidateCenter());
            assertFalse(far.ready.pinned()); assertEquals(125, far.ready.versions().size()); commit(actor, far);
        }
        assertEquals(Admission.QUEUED, actor.requestCenter(B));
        Prepared returned = far;
        for (int x = 4; x >= 0; x--) { returned = prepare(actor); commit(actor, returned); }
        assertEquals(B, returned.ready.candidateCenter()); assertTrue(returned.ready.pinned());
        var version = returned.version(B);
        var request = new EditRequest(world, 7, B, version.leaseGeneration(), version.revision(), 68, 129, 4, glass);
        assertEquals(Admission.QUEUED, actor.tryEdit(request));
        assertEquals(Admission.QUEUED, actor.requestCenter(goal), "Queued edit cannot consume a later movement goal");
        var refused = assertInstanceOf(EditAck.class, next(actor));
        assertEquals(EditStatus.UNSUPPORTED_REPLACEMENT, refused.status());
        adjacent = prepare(actor); commit(actor, adjacent);
        assertPinnedGoalStalled(actor, adjacent);
        assertTrue(actor.replacementCurrent(returned.ready.replacementSet().identity()));
        assertEquals(Admission.QUEUED, actor.authorizePinRetirement(adjacent.ready.generation(), adjacent.ready.replacementSet().identity()));
        for (int x = 2; x <= 5; x++) {
            far = prepare(actor); assertEquals(B.offset(x, 0, 0), far.ready.candidateCenter());
            assertFalse(far.ready.pinned()); assertEquals(125, far.ready.versions().size()); commit(actor, far);
        }
        assertEquals(goal, far.ready.candidateCenter()); assertEquals(0, actor.diagnostics().admittedEdits());
    }

    private void assertPinnedGoalStalled(RollingCubeActor actor, Prepared adjacent) throws Exception {
        assertTrue(queues.get(actor).isEmpty(), "No unconsumed distant stage may be hidden in the caller's batch");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        for (;;) {
            assertTrue(actor.pollEvents(2).isEmpty(), "Unauthorized distant goal must not publish candidate work");
            var diagnostics = actor.diagnostics();
            if (diagnostics.committedGeneration() == adjacent.ready.generation() && diagnostics.stagedGeneration() == 0) break;
            assertTrue(System.nanoTime() < deadline, "Actor did not settle at its authorized adjacent window"); Thread.sleep(1);
        }
        long observeUntil = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        while (System.nanoTime() < observeUntil) {
            assertTrue(actor.pollEvents(2).isEmpty(), "An old certificate alone cannot retire a newly retained pin");
            var diagnostics = actor.diagnostics();
            assertEquals(adjacent.ready.candidateCenter(), diagnostics.currentCenter());
            assertEquals(0, diagnostics.stagedGeneration()); assertEquals(1, diagnostics.queuedGoals());
            assertEquals(125, diagnostics.residentCubes()); assertTrue(diagnostics.currentPinned());
            Thread.sleep(2);
        }
    }
}
