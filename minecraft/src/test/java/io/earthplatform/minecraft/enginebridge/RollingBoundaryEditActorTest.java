package io.earthplatform.minecraft.enginebridge;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.FileEditStore;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.lod.DhReplacementInput;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Direction;
import io.earthplatform.minecraft.lod.DhTerrainGeometry.Face;
import io.earthplatform.minecraft.lod.DhVoxelReplacementGeometry;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static io.earthplatform.minecraft.enginebridge.RollingCubeActor.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual actor/store integration; no actor, certificate authority, snapshot or geometry mocks. */
class RollingBoundaryEditActorTest {
    private static final ReplacementPolicy FULL = new ReplacementPolicy("analytic-flat-v1",
            DhReplacementInput.ANALYTIC_MATERIAL_POLICY, "exact-v1", 1, RollingEditPolicy.FULL_CUBE_V1);
    private static final ReplacementPolicy LEGACY = new ReplacementPolicy(FULL.providerIdentity(),
            FULL.materialPolicy(), FULL.detailPolicy(), FULL.resourceGeneration());
    private static final CubePos ORIGINAL = new CubePos(-2, -8, -3);
    private static EnginePaletteContext palette;
    private static int air, diorite;
    @TempDir Path temporary;
    private final List<RollingCubeActor> actors = new ArrayList<>();
    private final Map<RollingCubeActor, ArrayDeque<Event>> queues = new HashMap<>();

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
        air = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        diorite = Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState());
    }

    @AfterEach void close() throws Exception {
        actors.forEach(RollingCubeActor::close);
        for (var actor : actors) assertTrue(actor.closed().get(10, TimeUnit.SECONDS).successful());
    }

    private record Session(RollingCubeActor actor, CubePos original, WorldIdentity world, long epoch, Path path) { }
    private Session open(CubePos original, long epoch, ReplacementPolicy policy) {
        var provider = new AnalyticNativeCubeProvider(42,
                AnalyticNativeCubeProvider.Config.flat(original.minBlockY() + 16), palette);
        Path path = temporary.resolve("world");
        var actor = policy == null
                ? RollingCubeActor.open(path, provider.identity(), provider, palette, epoch, original)
                : RollingCubeActor.open(path, provider.identity(), provider, palette, epoch, original, policy);
        actors.add(actor); queues.put(actor, new ArrayDeque<>());
        return new Session(actor, original, provider.identity(), epoch, path);
    }

    private Event next(Session session) throws Exception {
        var actor = session.actor(); var queue = queues.get(actor);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (queue.isEmpty()) {
            queue.addAll(actor.pollEvents(MAX_EVENTS_PER_POLL));
            if (!queue.isEmpty()) break;
            if (System.nanoTime() > end) fail("Timed out: " + actor.diagnostics());
            Thread.sleep(1);
        }
        Event event = queue.removeFirst();
        if (event instanceof Failure failure) fail(failure.detail());
        assertBounds(actor);
        return event;
    }

    private record Prepared(StageMetadata metadata, StageReady ready, List<Snapshot> snapshots) {
        CubeVersion version(CubePos position) {
            return ready.versions().stream().filter(v -> v.position().equals(position)).findFirst().orElseThrow();
        }
    }
    private Prepared prepare(Session session) throws Exception {
        StageMetadata metadata = assertInstanceOf(StageMetadata.class, next(session));
        List<Snapshot> snapshots = new ArrayList<>();
        Event event;
        while ((event = next(session)) instanceof Snapshot snapshot) snapshots.add(snapshot);
        StageReady ready = assertInstanceOf(StageReady.class, event);
        assertEquals(metadata.generation(), ready.generation());
        return new Prepared(metadata, ready, List.copyOf(snapshots));
    }
    private void commit(Session session, Prepared stage) throws Exception {
        var certificate = stage.ready().replacementCertificate();
        assertEquals(Admission.QUEUED, session.actor().commitPrepared(stage.ready().generation(),
                certificate == null ? null : certificate.identity()));
        assertEquals(stage.ready().generation(), assertInstanceOf(Committed.class, next(session)).generation());
    }
    private Prepared move(Session session, CubePos target, Map<CubePos, EngineCubeSnapshot> field) throws Exception {
        assertEquals(Admission.QUEUED, session.actor().requestCenter(target));
        var stage = prepare(session); commit(session, stage);
        stage.metadata().retiredPositions().forEach(field::remove);
        stage.snapshots().forEach(value -> field.put(value.snapshot().position(), value.snapshot()));
        return stage;
    }
    private record Outcome(Snapshot snapshot, EditAck ack) { }
    private Outcome submit(Session session, EditRequest request) throws Exception {
        assertEquals(Admission.QUEUED, session.actor().tryEdit(request));
        Event first = next(session);
        if (first instanceof Snapshot snapshot) return new Outcome(snapshot, assertInstanceOf(EditAck.class, next(session)));
        return new Outcome(null, assertInstanceOf(EditAck.class, first));
    }
    private Outcome edit(Session session, CubeVersion version, int x, int y, int z, int state) throws Exception {
        var p = session.original();
        return submit(session, new EditRequest(session.world(), session.epoch(), p, version.leaseGeneration(),
                version.revision(), p.minBlockX() + x, p.minBlockY() + y, p.minBlockZ() + z, state));
    }
    private static CubeVersion updated(CubeVersion old, Outcome outcome) {
        return new CubeVersion(old.position(), old.leaseGeneration(), outcome.ack().revision());
    }
    private static Map<CubePos, EngineCubeSnapshot> field(Prepared stage) {
        var result = new HashMap<CubePos, EngineCubeSnapshot>();
        stage.snapshots().forEach(value -> result.put(value.snapshot().position(), value.snapshot()));
        return result;
    }
    private static Set<Face> faces(CubePos original, Map<CubePos, EngineCubeSnapshot> snapshots) {
        Map<CubePos, EngineCubeSection> sections = new HashMap<>();
        // Materialize only the original 27 dependencies, not the rolling 125-owner window.
        snapshots.forEach((p, s) -> { if (RollingCubePlan.adjacent(original, p)) sections.put(p, s.materialize(palette)); });
        var capture = DhVoxelReplacementGeometry.capture(original, (x, y, z) -> {
            var section = sections.get(CubePos.fromBlock(x, y, z));
            assertNotNull(section, "Every replacement read needs an exact native snapshot");
            var state = section.get(CubePos.local(x), CubePos.local(y), CubePos.local(z));
            if (state == Blocks.AIR.defaultBlockState()) return 0;
            if (state == Blocks.STONE.defaultBlockState()) return 0xff808080;
            assertSame(Blocks.DIORITE.defaultBlockState(), state); return 0xffdedede;
        }, () -> { });
        var mesh = DhVoxelReplacementGeometry.build(capture, DhVoxelReplacementGeometry.MAX_FACES,
                DhVoxelReplacementGeometry.MAX_VERTEX_BYTES, () -> { });
        var result = Set.copyOf(mesh.faces());
        assertEquals(mesh.faceCount(), result.size(), "No duplicate unit faces");
        return result;
    }
    private static Set<Face> caveFaces(long x, long y, long z) {
        // Independent six-neighbor oracle: normals point from each solid neighbor into the removed block.
        return Set.of(new Face(Direction.EAST, x, y, z, 1, 1, 0xff808080),
                new Face(Direction.WEST, x + 1, y, z, 1, 1, 0xff808080),
                new Face(Direction.UP, x, y, z, 1, 1, 0xff808080),
                new Face(Direction.DOWN, x, y + 1, z, 1, 1, 0xff808080),
                new Face(Direction.SOUTH, x, y, z, 1, 1, 0xff808080),
                new Face(Direction.NORTH, x, y, z + 1, 1, 1, 0xff808080));
    }
    private static void exactCertificate(Session session, Prepared initial, CubeVersion current, ReplacementCertificate certificate) {
        assertNotNull(certificate);
        assertEquals(session.world(), certificate.world()); assertEquals(session.epoch(), certificate.epoch());
        assertEquals(session.original(), certificate.originalCube());
        assertEquals(FULL.providerIdentity(), certificate.providerIdentity());
        assertEquals(FULL.materialPolicy(), certificate.materialPolicy()); assertEquals(FULL.detailPolicy(), certificate.detailPolicy());
        assertEquals(FULL.resourceGeneration(), certificate.resourceGeneration());
        assertEquals(27, certificate.dependencies().size());
        assertEquals(initial.ready().versions().stream().filter(v -> RollingCubePlan.adjacent(session.original(), v.position()))
                .map(v -> v.position().equals(current.position()) ? current : v).toList(), certificate.dependencies());
    }
    private static void assertBounds(RollingCubeActor actor) {
        var d = actor.diagnostics();
        assertTrue(d.residentCubes() <= 213); assertTrue(d.encodedSnapshotCaches() <= 213);
        assertTrue(d.retainedSnapshotReferenceSlots() <= MAX_SNAPSHOT_REFERENCE_SLOTS);
        assertTrue(d.retainedVersionSlots() <= MAX_VERSION_SLOTS);
        assertTrue(d.physicalProviders() <= 2); assertTrue(d.tickets() <= 3);
        assertTrue(d.queuedEvents() <= 4); assertTrue(d.queuedEdits() <= 1);
        assertTrue(d.queuedCommits() <= 1); assertTrue(d.queuedGoals() <= 1);
    }

    static Stream<CubePos> signedOriginals() { return Stream.of(new CubePos(2, 8, 3), ORIGINAL); }

    @ParameterizedTest @MethodSource("signedOriginals")
    void all26CategoriesDurablyRemoveAndAddWithExactNeighborOwnedFaces(CubePos original) throws Exception {
        var session = open(original, 7, FULL); var initial = prepare(session); commit(session, initial);
        assertNull(initial.ready().replacementCertificate());
        var snapshots = field(initial); var baseline = faces(original, snapshots);
        CubeVersion version = initial.version(original); int categories = 0;
        for (int x : new int[]{0, 7, 15}) for (int y : new int[]{0, 7, 15}) for (int z : new int[]{0, 7, 15}) {
            if (x == 7 && y == 7 && z == 7) continue;
            var removed = edit(session, version, x, y, z, air);
            assertEquals(EditStatus.APPLIED, removed.ack().status()); assertNotNull(removed.snapshot());
            assertEquals(version.revision() + 1, removed.ack().revision()); version = updated(version, removed);
            exactCertificate(session, initial, version, removed.ack().replacementCertificate());
            snapshots.put(original, removed.snapshot().snapshot());
            var expected = new HashSet<>(baseline);
            expected.addAll(caveFaces(original.minBlockX() + x, original.minBlockY() + y, original.minBlockZ() + z));
            assertEquals(expected, faces(original, snapshots), "Exact faces for boundary category " + x + "," + y + "," + z);
            var added = edit(session, version, x, y, z, diorite);
            assertEquals(EditStatus.APPLIED, added.ack().status()); version = updated(version, added);
            exactCertificate(session, initial, version, added.ack().replacementCertificate());
            assertNotEquals(removed.ack().replacementCertificate().identity(), added.ack().replacementCertificate().identity());
            snapshots.put(original, added.snapshot().snapshot());
            assertEquals(baseline, faces(original, snapshots), "Refilling occludes every neighbor-owned face");
            categories++;
        }
        assertEquals(26, categories); assertEquals(52, version.revision());
        session.actor().close(); assertTrue(session.actor().closed().get(10, TimeUnit.SECONDS).successful());
        try (var store = new FileEditStore(session.path(), session.world())) {
            var durable = store.load(original).snapshot();
            assertEquals(52, durable.revision()); assertEquals(26, durable.blocks().size());
            assertTrue(durable.blocks().values().stream().allMatch(state -> state == diorite));
        }
    }

    @Test void legacyActorAndFourArgumentReplacementPolicyKeepShellImmutableAndDurableIdentity() throws Exception {
        var legacy = open(ORIGINAL, 7, null); var first = prepare(legacy); commit(legacy, first);
        var rejected = edit(legacy, first.version(ORIGINAL), 0, 7, 7, air);
        assertEquals(EditStatus.IMMUTABLE_BOUNDARY, rejected.ack().status()); assertNull(rejected.snapshot());
        var saved = edit(legacy, first.version(ORIGINAL), 7, 7, 7, diorite);
        assertTrue(saved.ack().applied()); assertNull(saved.ack().replacementCertificate());
        legacy.actor().close(); assertTrue(legacy.actor().closed().get(10, TimeUnit.SECONDS).successful());
        var compatible = open(ORIGINAL, 8, LEGACY); var loaded = prepare(compatible); commit(compatible, loaded);
        assertEquals(legacy.world(), compatible.world()); assertEquals(1, loaded.version(ORIGINAL).revision());
        var before = loaded.ready().replacementCertificate(); assertNotNull(before);
        assertEquals(EditStatus.IMMUTABLE_BOUNDARY, edit(compatible, loaded.version(ORIGINAL), 15, 7, 7, air).ack().status());
        compatible.actor().close(); assertTrue(compatible.actor().closed().get(10, TimeUnit.SECONDS).successful());
        var widened = open(ORIGINAL, 9, FULL); var sameStore = prepare(widened); commit(widened, sameStore);
        assertEquals(legacy.world(), widened.world()); assertEquals(1, sameStore.version(ORIGINAL).revision());
        assertEquals(before.storeIdentity(), sameStore.ready().replacementCertificate().storeIdentity());
        assertEquals(before.providerIdentity(), sameStore.ready().replacementCertificate().providerIdentity());
        assertEquals(before.materialPolicy(), sameStore.ready().replacementCertificate().materialPolicy());
        assertEquals(before.detailPolicy(), sameStore.ready().replacementCertificate().detailPolicy());
        assertSame(Blocks.DIORITE.defaultBlockState(), field(sameStore).get(ORIGINAL).materialize(palette).get(7, 7, 7));
        assertTrue(edit(widened, sameStore.version(ORIGINAL), 15, 7, 7, air).ack().applied());
    }

    @Test void fullModeStillRejectsNeighborsOutsideDomainAndStaleAuthorityWithoutSaving() throws Exception {
        var session = open(ORIGINAL, 7, FULL); var initial = prepare(session); commit(session, initial);
        CubeVersion v = initial.version(ORIGINAL); long x = ORIGINAL.minBlockX(), y = ORIGINAL.minBlockY(), z = ORIGINAL.minBlockZ();
        assertEquals(EditStatus.POSITION_MISMATCH, edit(session, v, -1, 7, 7, air).ack().status());
        assertEquals(EditStatus.POSITION_MISMATCH, edit(session, v, 16, 7, 7, air).ack().status());
        var neighbor = initial.version(ORIGINAL.offset(1, 0, 0));
        assertEquals(EditStatus.UNKNOWN_CUBE, submit(session, new EditRequest(session.world(), 7, neighbor.position(),
                neighbor.leaseGeneration(), 0, x + 16, y + 7, z + 7, air)).ack().status());
        for (long outside : new long[]{RollingCubePlan.GAMEPLAY_MIN_Y - 1L, RollingCubePlan.GAMEPLAY_MAX_Y_EXCLUSIVE,
                Long.MIN_VALUE, Long.MAX_VALUE})
            assertEquals(EditStatus.POSITION_MISMATCH, submit(session, new EditRequest(session.world(), 7, ORIGINAL,
                    v.leaseGeneration(), 0, x, outside, z, air)).ack().status());
        assertEquals(EditStatus.STALE_EPOCH, submit(session, new EditRequest(session.world(), 6, ORIGINAL,
                v.leaseGeneration(), 0, x, y, z, air)).ack().status());
        assertEquals(EditStatus.STALE_LEASE, submit(session, new EditRequest(session.world(), 7, ORIGINAL,
                v.leaseGeneration() + 1000, 0, x, y, z, air)).ack().status());
        assertEquals(EditStatus.STALE_REVISION, submit(session, new EditRequest(session.world(), 7, ORIGINAL,
                v.leaseGeneration(), 1, x, y, z, air)).ack().status());
        assertEquals(EditStatus.INVALID_STATE, edit(session, v, 0, 0, 0, Integer.MAX_VALUE).ack().status());
        assertEquals(0, session.actor().diagnostics().admittedEdits());
        assertFalse(Files.exists(session.path().resolve("cubes/-2_-8_-3.cube")));
    }

    @Test void revokedBoundaryCertificateRegeneratesOnlyFromExactCurrentDependencyVector() throws Exception {
        var session = open(ORIGINAL, 7, FULL); var initial = prepare(session); commit(session, initial);
        var saved = edit(session, initial.version(ORIGINAL), 0, 0, 0, air); var certificate = saved.ack().replacementCertificate();
        CubeVersion current = updated(initial.version(ORIGINAL), saved);
        exactCertificate(session, initial, current, certificate);
        assertEquals(Admission.QUEUED, session.actor().authorizePinRetirement(initial.ready().generation(), certificate.identity()));
        var rejected = edit(session, current, 16, 0, 0, air);
        assertEquals(EditStatus.POSITION_MISMATCH, rejected.ack().status()); assertNull(rejected.ack().replacementCertificate());
        assertEquals(Admission.NOT_READY, session.actor().authorizePinRetirement(initial.ready().generation(), certificate.identity()));
        assertEquals(Admission.QUEUED, session.actor().requestCenter(ORIGINAL.offset(1, 0, 0)));
        var next = prepare(session); var fresh = next.ready().replacementCertificate();
        assertTrue(next.ready().pinned()); assertNotNull(fresh);
        assertNotEquals(certificate.identity(), fresh.identity());
        assertEquals(certificate.dependencyIdentity(), fresh.dependencyIdentity());
        assertEquals(certificate.dependencies(), fresh.dependencies());
        assertEquals(Admission.NOT_READY, session.actor().commitPrepared(next.ready().generation(), certificate.identity()));
        commit(session, next);
        assertEquals(Admission.NOT_READY, session.actor().authorizePinRetirement(next.ready().generation(), certificate.identity()));
        assertEquals(Admission.QUEUED, session.actor().authorizePinRetirement(next.ready().generation(), fresh.identity()));
    }

    @Test void failedBoundarySaveRevokesCertificateAndLaterStageCannotCertifyUncertainRevision() throws Exception {
        var session = open(ORIGINAL, 7, FULL); var initial = prepare(session); commit(session, initial);
        var saved = edit(session, initial.version(ORIGINAL), 0, 0, 0, air); var certificate = saved.ack().replacementCertificate();
        assertNotNull(certificate);
        Path primary = session.path().resolve("cubes/-2_-8_-3.cube");
        Files.delete(primary); Files.createDirectory(primary);
        var failed = edit(session, updated(initial.version(ORIGINAL), saved), 15, 15, 15, air);
        assertEquals(EditStatus.SAVE_FAILED, failed.ack().status());
        assertNull(failed.snapshot()); assertNull(failed.ack().replacementCertificate());
        assertEquals(Admission.NOT_READY, session.actor().authorizePinRetirement(initial.ready().generation(), certificate.identity()));
        var stage = move(session, ORIGINAL.offset(1, 0, 0), field(initial));
        assertTrue(stage.ready().pinned()); assertNull(stage.ready().replacementCertificate());
    }

    @Test void boundaryHoleSurvivesComplete27DependencyRetirementFreshLeaseAndRestart() throws Exception {
        var session = open(ORIGINAL, 7, FULL); var initial = prepare(session); commit(session, initial);
        var removed = edit(session, initial.version(ORIGINAL), 0, 0, 0, air);
        var certificate = removed.ack().replacementCertificate(); assertNotNull(certificate);
        var snapshots = field(initial); snapshots.put(ORIGINAL, removed.snapshot().snapshot());
        var exactFaces = faces(ORIGINAL, snapshots);
        assertEquals(Admission.QUEUED, session.actor().authorizePinRetirement(initial.ready().generation(), certificate.identity()));
        Prepared stage = null;
        for (int x = 1; x <= 5; x++) stage = move(session, ORIGINAL.offset(x, 0, 0), snapshots);
        assertFalse(stage.ready().pinned()); assertEquals(125, stage.ready().versions().size());
        assertEquals(certificate, stage.ready().replacementCertificate());
        assertTrue(certificate.dependencies().stream().noneMatch(v -> snapshots.containsKey(v.position())));
        assertEquals(Admission.NOT_READY, session.actor().tryEdit(new EditRequest(session.world(), 7, ORIGINAL,
                initial.version(ORIGINAL).leaseGeneration(), 1, ORIGINAL.minBlockX(), ORIGINAL.minBlockY(), ORIGINAL.minBlockZ(), diorite)));
        for (int x = 4; x >= 0; x--) stage = move(session, ORIGINAL.offset(x, 0, 0), snapshots);
        assertTrue(stage.ready().pinned());
        assertTrue(stage.version(ORIGINAL).leaseGeneration() > initial.version(ORIGINAL).leaseGeneration());
        assertEquals(1, stage.version(ORIGINAL).revision()); assertEquals(exactFaces, faces(ORIGINAL, snapshots));
        var stale = submit(session, new EditRequest(session.world(), 7, ORIGINAL, initial.version(ORIGINAL).leaseGeneration(),
                1, ORIGINAL.minBlockX(), ORIGINAL.minBlockY(), ORIGINAL.minBlockZ(), diorite));
        assertEquals(EditStatus.STALE_LEASE, stale.ack().status()); assertNull(stale.ack().replacementCertificate());
        session.actor().close(); assertTrue(session.actor().closed().get(10, TimeUnit.SECONDS).successful());
        var restarted = open(ORIGINAL, 8, FULL); var reloaded = prepare(restarted);
        var rebuilt = reloaded.ready().replacementCertificate(); assertNotNull(rebuilt);
        assertEquals(session.world(), restarted.world()); assertEquals(certificate.storeIdentity(), rebuilt.storeIdentity());
        assertEquals(1, reloaded.version(ORIGINAL).revision()); assertEquals(exactFaces, faces(ORIGINAL, field(reloaded)));
        assertNotEquals(certificate.identity(), rebuilt.identity()); commit(restarted, reloaded);
        assertEquals(Admission.NOT_READY, restarted.actor().authorizePinRetirement(reloaded.ready().generation(), certificate.identity()));
        assertEquals(Admission.QUEUED, restarted.actor().authorizePinRetirement(reloaded.ready().generation(), rebuilt.identity()));
    }
}
