package io.earthplatform.minecraft.enginebridge;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
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

class NativeReplacementActorTest {
    static final CubePos ORIGINAL = new CubePos(0, 8, 0);
    static EnginePaletteContext palette;
    static AnalyticNativeCubeProvider provider;
    static WorldIdentity world;
    static int diorite;
    static final ReplacementPolicy POLICY = new ReplacementPolicy("analytic-flat-v1", "opaque-unit-v1", "exact-v1", 1);
    @TempDir Path temporary;
    final List<RollingCubeActor> actors = new ArrayList<>();
    final Map<RollingCubeActor, ArrayDeque<Event>> events = new HashMap<>();
    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
        provider = new AnalyticNativeCubeProvider(42, AnalyticNativeCubeProvider.Config.flat(128), palette);
        world = provider.identity(); diorite = Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState());
    }
    @AfterEach void close() throws Exception {
        actors.forEach(RollingCubeActor::close);
        for (var actor : actors) assertTrue(actor.closed().get(10, TimeUnit.SECONDS).successful());
    }
    RollingCubeActor open(long epoch) {
        var actor = RollingCubeActor.open(temporary.resolve("world"), world, provider, palette, epoch, ORIGINAL, POLICY);
        actors.add(actor); events.put(actor, new ArrayDeque<>()); return actor;
    }
    Event next(RollingCubeActor actor) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); var queue = events.get(actor);
        while (queue.isEmpty()) {
            queue.addAll(actor.pollEvents(2)); if (!queue.isEmpty()) break;
            if (System.nanoTime() > end) fail("Actor timed out: " + actor.diagnostics()); Thread.sleep(1);
        }
        Event event = queue.removeFirst(); if (event instanceof Failure f) fail(f.detail()); return event;
    }
    record Prepared(StageMetadata metadata, StageReady ready, List<Snapshot> snapshots) {
        CubeVersion version(CubePos pos) { return ready.versions().stream().filter(v -> v.position().equals(pos)).findFirst().orElseThrow(); }
    }
    Prepared prepare(RollingCubeActor actor) throws Exception {
        var metadata = assertInstanceOf(StageMetadata.class, next(actor)); var snapshots = new ArrayList<Snapshot>();
        Event e; while ((e = next(actor)) instanceof Snapshot snapshot) snapshots.add(snapshot);
        var ready = assertInstanceOf(StageReady.class, e); assertEquals(metadata.candidatePinned(), ready.pinned());
        assertTrue(actor.diagnostics().residentCubes() <= 213); assertTrue(metadata.candidateLeases().size() <= 152);
        return new Prepared(metadata, ready, List.copyOf(snapshots));
    }
    void commit(RollingCubeActor actor, Prepared prepared) throws Exception {
        String certificate = prepared.ready().replacementCertificate() == null ? null : prepared.ready().replacementCertificate().identity();
        assertEquals(Admission.QUEUED, actor.commitPrepared(prepared.ready().generation(), certificate));
        var committed = assertInstanceOf(Committed.class, next(actor)); assertEquals(prepared.ready().generation(), committed.generation());
    }
    Prepared move(RollingCubeActor actor, CubePos next) throws Exception {
        assertEquals(Admission.QUEUED, actor.requestCenter(next)); var prepared = prepare(actor); commit(actor, prepared); return prepared;
    }
    EditAck edit(RollingCubeActor actor, CubeVersion version, long epoch, long x) throws Exception {
        assertEquals(Admission.QUEUED, actor.tryEdit(new EditRequest(world, epoch, ORIGINAL, version.leaseGeneration(),
                version.revision(), x, 129, 4, diorite)));
        Event event; do { event = next(actor); } while (event instanceof Snapshot);
        return assertInstanceOf(EditAck.class, event);
    }
    @Test void exactDurablePairRetiresOriginalSourcesAndReturnReacquiresFreshLeases() throws Exception {
        var actor = open(7); var initial = prepare(actor); assertNull(initial.ready().replacementCertificate()); commit(actor, initial);
        var edited = edit(actor, initial.version(ORIGINAL), 7, 4); assertTrue(edited.applied());
        var certificate = edited.replacementCertificate(); assertNotNull(certificate); assertEquals(1, certificate.originalRevision());
        assertEquals(Admission.NOT_READY, actor.authorizePinRetirement(initial.ready().generation(), "0".repeat(64)));
        assertEquals(Admission.QUEUED, actor.authorizePinRetirement(initial.ready().generation(), certificate.identity()));
        Prepared far = null;
        for (int x = 1; x <= 5; x++) {
            far = move(actor, ORIGINAL.offset(x, 0, 0));
            assertEquals(x == 1, far.metadata().candidatePinned());
            assertEquals(certificate, far.ready().replacementCertificate());
        }
        assertEquals(125, far.ready().versions().size());
        assertFalse(far.ready().versions().stream().anyMatch(v -> v.position().equals(ORIGINAL)));
        assertEquals(Admission.NOT_READY, actor.tryEdit(new EditRequest(world, 7, ORIGINAL, initial.version(ORIGINAL).leaseGeneration(), 1, 4, 129, 4, diorite)));
        Prepared back = null;
        for (int x = 4; x >= 0; x--) back = move(actor, ORIGINAL.offset(x, 0, 0));
        assertTrue(back.metadata().candidatePinned()); assertEquals(certificate, back.ready().replacementCertificate());
        assertTrue(back.version(ORIGINAL).leaseGeneration() > initial.version(ORIGINAL).leaseGeneration());
        assertEquals(1, back.version(ORIGINAL).revision());
        var fresh = edit(actor, back.version(ORIGINAL), 7, 5); assertTrue(fresh.applied());
        assertEquals(2, fresh.replacementCertificate().originalRevision());
        assertTrue(fresh.replacementCertificate().generation() > certificate.generation());
        assertEquals(Admission.NOT_READY, actor.authorizePinRetirement(back.ready().generation(), certificate.identity()));
    }
    @Test void rejectedEditNeverReoffersHistoricalIdAndKeepsPin() throws Exception {
        var actor = open(7); var first = prepare(actor); commit(actor, first);
        var applied = edit(actor, first.version(ORIGINAL), 7, 4); var old = applied.replacementCertificate();
        assertEquals(Admission.QUEUED, actor.authorizePinRetirement(first.ready().generation(), old.identity()));
        var unchanged = new CubeVersion(ORIGINAL, first.version(ORIGINAL).leaseGeneration(), applied.revision());
        var rejected = edit(actor, unchanged, 7, 0); assertEquals(EditStatus.IMMUTABLE_BOUNDARY, rejected.status());
        assertNull(rejected.replacementCertificate()); assertEquals(Admission.NOT_READY, actor.authorizePinRetirement(first.ready().generation(), old.identity()));
        var stage = move(actor, ORIGINAL.offset(1, 0, 0));
        assertTrue(stage.metadata().candidatePinned()); assertNotNull(stage.ready().replacementCertificate());
        assertNotEquals(old.identity(), stage.ready().replacementCertificate().identity());
        assertEquals(old.dependencyIdentity(), stage.ready().replacementCertificate().dependencyIdentity());
    }
    @Test void declinedPairConsumesCertificateAndFreshStageNeverReplaysIt() throws Exception {
        var actor = open(7); var first = prepare(actor); commit(actor, first);
        var applied = edit(actor, first.version(ORIGINAL), 7, 4); var declined = applied.replacementCertificate();
        assertEquals(Admission.QUEUED, actor.retainPinWithoutReplacement(first.ready().generation()));
        assertEquals(Admission.NOT_READY, actor.authorizePinRetirement(first.ready().generation(), declined.identity()));
        assertEquals(Admission.QUEUED, actor.requestCenter(ORIGINAL.offset(1, 0, 0)));
        var next = prepare(actor); var second = next.ready().replacementCertificate();
        assertNotEquals(declined.identity(), second.identity());
        assertEquals(Admission.QUEUED, actor.commitPrepared(next.ready().generation(), null));
        assertInstanceOf(Committed.class, next(actor));
        assertEquals(Admission.NOT_READY, actor.authorizePinRetirement(next.ready().generation(), second.identity()));
        var third = move(actor, ORIGINAL.offset(2, 0, 0));
        assertTrue(third.ready().pinned()); assertNotEquals(second.identity(), third.ready().replacementCertificate().identity());
    }
    @Test void failedSaveCannotOfferCertificateOnAckOrLaterStage() throws Exception {
        var actor = open(7); var first = prepare(actor); commit(actor, first);
        var applied = edit(actor, first.version(ORIGINAL), 7, 4); var old = applied.replacementCertificate();
        var primary = temporary.resolve("world/cubes/0_8_0.cube"); Files.delete(primary); Files.createDirectory(primary);
        var unchanged = new CubeVersion(ORIGINAL, first.version(ORIGINAL).leaseGeneration(), applied.revision());
        var failed = edit(actor, unchanged, 7, 5); assertEquals(EditStatus.SAVE_FAILED, failed.status());
        assertNull(failed.replacementCertificate());
        assertEquals(Admission.NOT_READY, actor.authorizePinRetirement(first.ready().generation(), old.identity()));
        var stage = move(actor, ORIGINAL.offset(1, 0, 0));
        assertTrue(stage.ready().pinned()); assertNull(stage.ready().replacementCertificate());
    }
    @Test void restartReconstructsCertificateFromExactDurableSnapshotsAndPortableIndex() throws Exception {
        var actor = open(7); var first = prepare(actor); commit(actor, first);
        var edited = edit(actor, first.version(ORIGINAL), 7, 4); var old = edited.replacementCertificate();
        actor.close(); assertTrue(actor.closed().get(10, TimeUnit.SECONDS).successful());
        var reopened = open(8); var loaded = prepare(reopened); var rebuilt = loaded.ready().replacementCertificate();
        assertNotNull(rebuilt); assertEquals(1, loaded.version(ORIGINAL).revision());
        assertEquals(old.storeIdentity(), rebuilt.storeIdentity()); assertEquals(old.originalRevision(), rebuilt.originalRevision());
        assertNotEquals(old.identity(), rebuilt.identity());
        assertEquals(Admission.STALE_GENERATION, reopened.authorizePinRetirement(1, rebuilt.identity()));
        commit(reopened, loaded);
        assertEquals(Admission.NOT_READY, reopened.authorizePinRetirement(loaded.ready().generation(), old.identity()));
        assertEquals(Admission.QUEUED, reopened.authorizePinRetirement(loaded.ready().generation(), rebuilt.identity()));
    }
}
