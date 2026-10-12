package io.earthplatform.minecraft.coregate;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.minecraft.enginebridge.*;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.block.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static io.earthplatform.minecraft.enginebridge.RollingCubeActor.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent adversaries execute the actual host publication authorization with a real actor.
 * Public replacementCurrent means issued OR committed, so it cannot prove edit authority.
 * The reflective read-only probe below reads the actor's existing committed-authority predicate;
 * no test writes actor internals or duplicates the production publication algorithm.
 */
class RollingArenaPublicationAdversarialReviewTest {
    private static final long EPOCH = 71, RESOURCES = 1;
    private static final ArenaDefinition ARENA = ArenaDefinition.FIXED;
    private static EnginePaletteContext palette;
    private static NativeArenaCubeProvider provider;
    private static RollingSessionDescriptor descriptor;
    private static Method committedAuthority;
    private static int diorite, air;
    @TempDir Path directory;
    private final List<RollingCubeActor> actors = new ArrayList<>();
    private final Map<RollingCubeActor, ArrayDeque<Event>> queues = new IdentityHashMap<>();

    @BeforeAll static void initialize() throws Exception {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS, VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
        provider = new NativeArenaCubeProvider(ArenaTerrainSource.FIXED, palette);
        descriptor = RollingSessionDescriptor.arena(palette);
        diorite = Block.BLOCK_STATE_REGISTRY.getId(Blocks.DIORITE.defaultBlockState());
        air = Block.BLOCK_STATE_REGISTRY.getId(Blocks.AIR.defaultBlockState());
        committedAuthority = RollingCubeActor.class.getDeclaredMethod("currentReplacement", String.class);
        committedAuthority.setAccessible(true);
    }

    @AfterEach void closeActors() throws Exception {
        actors.forEach(RollingCubeActor::close);
        for (var actor : actors) assertTrue(actor.closed().get(15, TimeUnit.SECONDS).successful());
    }

    @Test void everyPrePairStateAndClosedHandshakeRejectsIssuedCertificateWithoutCommittingIt() throws Exception {
        var actor = open();
        var first = initial(actor);
        var edited = edit(actor, first, ARENA.origin(), diorite);
        assertIssuedButUncommitted(actor, edited);
        for (var state : List.of(RollingPublicationHandshake.State.IDLE,
                RollingPublicationHandshake.State.BUILDING, RollingPublicationHandshake.State.READY,
                RollingPublicationHandshake.State.PREPARED, RollingPublicationHandshake.State.GRANTED,
                RollingPublicationHandshake.State.CLOSED)) {
            var h = handshakeAt(edited, state);
            var error = assertThrows(IllegalStateException.class,
                    () -> RollingGateCommands.authorizePublication(actor, h, edited, true, true), state.name());
            assertTrue(error.getMessage().contains("Exact paired acknowledgement"));
            assertEquals(state, h.state());
            assertEquals(0, h.committedPublication());
            assertIssuedButUncommitted(actor, edited);
        }
        publish(actor, edited);
        assertCommitted(actor, edited, first);
        var next = edit(actor, edited, ARENA.origin(), air);
        publish(actor, next);
        assertCommitted(actor, next, edited);
    }

    @Test void arenaModeFailsClosedWhenEnabledFlagOrCompleteArenaCertificateIsMissing() throws Exception {
        var actor = open();
        var first = initial(actor);
        var edited = edit(actor, first, ARENA.origin(), diorite);
        var pair = handshakeAt(edited, RollingPublicationHandshake.State.PAIR_READY);
        var missingArena = new StageReady(edited.world(), edited.epoch(), edited.generation(),
                edited.candidateCenter(), edited.versions(), null, true);
        var disabled = assertThrows(IllegalStateException.class,
                () -> RollingGateCommands.authorizePublication(actor, pair, edited, false, true));
        assertTrue(disabled.getMessage().contains("Arena publication requires"));
        assertIssuedButUncommitted(actor, edited);
        for (var absent : Arrays.asList((StageReady) null, missingArena)) {
            var error = assertThrows(IllegalStateException.class,
                    () -> RollingGateCommands.authorizePublication(actor, pair, absent, true, true));
            assertTrue(error.getMessage().contains("Arena publication requires"));
            assertIssuedButUncommitted(actor, edited);
            assertEquals(0, pair.committedPublication());
        }
        RollingGateCommands.authorizePublication(actor, pair, edited, true, true); pair.committed();
        assertCommitted(actor, edited, first);
    }

    @Test void wrongCertificateResourcesAndRollingGenerationCannotCommitEvenWithPairReady() throws Exception {
        var actor = open();
        var first = initial(actor);
        var edited = edit(actor, first, ARENA.origin(), diorite);
        var cert = edited.arenaCertificate();
        var candidates = List.of(
                first,
                replace(edited, edited.generation() + 1, cert),
                replace(edited, edited.generation(), mutate(cert, cert.generation() + 1, cert.resourceGeneration())),
                replace(edited, edited.generation(), mutate(cert, cert.generation(), cert.resourceGeneration() + 1)));
        for (var wrong : candidates) {
            var h = handshakeAt(wrong, RollingPublicationHandshake.State.PAIR_READY);
            var error = assertThrows(IllegalStateException.class,
                    () -> RollingGateCommands.authorizePublication(actor, h, wrong, true, true));
            assertTrue(error.getMessage().contains("rejected:"), error.getMessage());
            assertEquals(RollingPublicationHandshake.State.PAIR_READY, h.state());
            assertEquals(0, h.committedPublication());
            assertIssuedButUncommitted(actor, edited);
            assertFalse(current(actor, first.arenaCertificate().identity()));
        }
        publish(actor, edited);
        assertCommitted(actor, edited, first);
        var otherCube = edit(actor, edited, ARENA.positions().getLast(), diorite);
        publish(actor, otherCube);
        assertCommitted(actor, otherCube, edited);
    }

    @Test void replayedPairAckWrongEpochDigestPublicationAndResourcesStayFencedUntilExactAck() throws Exception {
        var actor = open();
        var first = initial(actor);
        var edited = edit(actor, first, ARENA.origin(), diorite);
        var h = new RollingPublicationHandshake(EPOCH);
        arm(h, first); acknowledge(h); h.committed();
        arm(h, edited);
        assertEquals(2, h.publication());
        String digest = h.digest();
        assertFalse(h.prepared(EPOCH, 1, RESOURCES, digest));
        assertFalse(h.prepared(EPOCH + 1, 2, RESOURCES, digest));
        assertFalse(h.prepared(EPOCH, 2, RESOURCES + 1, digest));
        assertFalse(h.prepared(EPOCH, 2, RESOURCES, "0".repeat(64)));
        assertEquals(RollingPublicationHandshake.State.READY, h.state());
        assertThrows(IllegalStateException.class,
                () -> RollingGateCommands.authorizePublication(actor, h, edited, true, true));
        assertTrue(h.prepared(EPOCH, 2, RESOURCES, digest)); h.grant();
        assertFalse(h.pairReady(EPOCH, 1, RESOURCES, digest));
        assertFalse(h.pairReady(EPOCH + 1, 2, RESOURCES, digest));
        assertFalse(h.pairReady(EPOCH, 2, RESOURCES + 1, digest));
        assertFalse(h.pairReady(EPOCH, 2, RESOURCES, "0".repeat(64)));
        assertThrows(IllegalStateException.class,
                () -> RollingGateCommands.authorizePublication(actor, h, edited, true, true));
        assertIssuedButUncommitted(actor, edited);
        assertTrue(h.pairReady(EPOCH, 2, RESOURCES, digest));
        RollingGateCommands.authorizePublication(actor, h, edited, true, true);
        assertFalse(h.pairReady(EPOCH, 2, RESOURCES, digest));
        h.committed();
        assertTrue(h.acceptsMouse(EPOCH, 2, RESOURCES));
        assertFalse(h.acceptsMouse(EPOCH, 1, RESOURCES));
        assertCommitted(actor, edited, first);
    }

    @Test void canceledPairAndActorCloseCannotResurrectDurableButUnpublishedAuthority() throws Exception {
        var actor = open();
        var first = initial(actor);
        var edited = edit(actor, first, ARENA.origin(), diorite);
        var canceled = handshakeAt(edited, RollingPublicationHandshake.State.PAIR_READY);
        canceled.close();
        assertThrows(IllegalStateException.class,
                () -> RollingGateCommands.authorizePublication(actor, canceled, edited, true, true));
        assertFalse(canceled.acceptsMouse(EPOCH, canceled.publication(), RESOURCES));
        assertIssuedButUncommitted(actor, edited);
        var pair = handshakeAt(edited, RollingPublicationHandshake.State.PAIR_READY);
        actor.close();
        var error = assertThrows(IllegalStateException.class,
                () -> RollingGateCommands.authorizePublication(actor, pair, edited, true, true));
        assertTrue(error.getMessage().contains("CLOSING"));
        assertFalse(actor.replacementCurrent(edited.arenaCertificate().identity()));
        assertEquals(0, pair.committedPublication());
        assertFalse(pair.acceptsMouse(EPOCH, pair.publication(), RESOURCES));
        assertTrue(actor.closed().get(15, TimeUnit.SECONDS).successful());
        assertThrows(IllegalStateException.class,
                () -> RollingGateCommands.authorizePublication(actor, pair, edited, true, true));
    }

    @Test void initialAndMovementStillRequireActorCommittedBeforeHostPublicationAndNeverAcquirePin() throws Exception {
        var actor = open();
        var first = prepared(actor);
        var h = handshakeAt(first, RollingPublicationHandshake.State.PAIR_READY);
        assertThrows(IllegalStateException.class,
                () -> RollingGateCommands.authorizePublication(actor, h, first, true, true));
        assertEquals(0, actor.diagnostics().committedGeneration());
        assertEquals(Admission.QUEUED, actor.commitPrepared(first.generation(), first.arenaCertificate().identity()));
        assertInstanceOf(Committed.class, next(actor));
        RollingGateCommands.authorizePublication(actor, h, first, true, true); h.committed();
        assertCommitted(actor, first);
        assertEquals(Admission.QUEUED, actor.requestCenter(first.candidateCenter().offset(1, 0, 0)));
        var moved = prepared(actor);
        var moving = handshakeAt(moved, RollingPublicationHandshake.State.PAIR_READY);
        assertThrows(IllegalStateException.class,
                () -> RollingGateCommands.authorizePublication(actor, moving, moved, true, true));
        assertEquals(first.generation(), actor.diagnostics().committedGeneration());
        assertEquals(Admission.QUEUED, actor.commitPrepared(moved.generation(), moved.arenaCertificate().identity()));
        assertInstanceOf(Committed.class, next(actor));
        RollingGateCommands.authorizePublication(actor, moving, moved, true, true); moving.committed();
        assertCommitted(actor, moved);
        var stale = handshakeAt(first, RollingPublicationHandshake.State.PAIR_READY);
        var staleError = assertThrows(IllegalStateException.class,
                () -> RollingGateCommands.authorizePublication(actor, stale, first, true, true));
        assertTrue(staleError.getMessage().contains("STALE_GENERATION"));
        assertCommitted(actor, moved);
        assertEquals(first.arenaCertificate().durableIdentity(), moved.arenaCertificate().durableIdentity());
        var edited = edit(actor, moved, ARENA.positions().getLast(), diorite);
        publish(actor, edited); assertCommitted(actor, edited, moved);
    }

    @Test void legacyNoneFallbackRetainsPinAndExactReplacementAuthorizationDoesNotRetireItEagerly() throws Exception {
        var legacyDescriptor = RollingSessionDescriptor.analytic(CoreGateProfile.BASE, palette);
        var legacyProvider = RollingGateFixture.provider(palette, CoreGateProfile.BASE);
        var policy = new ReplacementPolicy("legacy-review-provider", "opaque-unit-v1", "exact-v1", RESOURCES);
        var actor = RollingCubeActor.open(directory.resolve("legacy"), legacyProvider.identity(), legacyProvider,
                palette, EPOCH, legacyDescriptor.initialCenter(), policy);
        actors.add(actor); queues.put(actor, new ArrayDeque<>());
        assertInstanceOf(StageMetadata.class, next(actor));
        Event event; while ((event = next(actor)) instanceof Snapshot) { }
        var ready = assertInstanceOf(StageReady.class, event);
        assertTrue(ready.pinned()); assertNull(ready.replacementCertificate());
        assertEquals(Admission.QUEUED, actor.commitPrepared(ready.generation(), null));
        assertInstanceOf(Committed.class, next(actor));
        var none = new RollingPublicationHandshake(EPOCH); none.begin();
        none.ready(RollingFarPublication.digest(legacyDescriptor, ready)); acknowledge(none);
        RollingGateCommands.authorizePublication(actor, none, ready, true, false); none.committed();
        assertTrue(actor.diagnostics().currentPinned());
        int owners = actor.diagnostics().residentCubes();
        var target = legacyDescriptor.pinCenter();
        var version = ready.versions().stream().filter(v -> v.position().equals(target)).findFirst().orElseThrow();
        assertEquals(Admission.QUEUED, actor.tryEdit(new EditRequest(legacyProvider.identity(), EPOCH, target,
                version.leaseGeneration(), version.revision(), 4, 129, 4, diorite)));
        Snapshot replacement = null;
        while (!((event = next(actor)) instanceof EditAck)) replacement = assertInstanceOf(Snapshot.class, event);
        var ack = (EditAck) event; assertTrue(ack.applied(), ack.detail()); assertNotNull(replacement);
        var updated = new ArrayList<>(ready.versions());
        for (int i = 0; i < updated.size(); i++) if (updated.get(i).position().equals(target))
            updated.set(i, new CubeVersion(target, replacement.snapshot().leaseGeneration(), replacement.snapshot().revision()));
        var edited = RollingEditAcknowledgement.ready(ready, target, updated, ack);
        var exact = new RollingPublicationHandshake(EPOCH); exact.begin();
        exact.ready(RollingFarPublication.digest(legacyDescriptor, edited)); acknowledge(exact);
        RollingGateCommands.authorizePublication(actor, exact, edited, true, false); exact.committed();
        assertTrue(current(actor, edited.replacementCertificate().identity()));
        assertTrue(actor.diagnostics().currentPinned(), "Authorization alone must not retire the physical native pin");
        assertEquals(owners, actor.diagnostics().residentCubes());
        assertEquals(ready.generation(), actor.diagnostics().committedGeneration());
        assertEquals(0, actor.diagnostics().stagedGeneration());
        assertEquals(0, actor.diagnostics().queuedGoals());
    }

    private RollingCubeActor open() {
        var policy = RollingArenaQueryGuard.replacementPolicy(descriptor);
        String layout = descriptor.lodLayout().identity();
        ArenaAdmission admission = c -> new ArenaAdmission.Receipt(true, "", ARENA.identity(),
                c.contentIdentity(), provider.source().identity(), layout);
        var actor = RollingCubeActor.openArena(directory.resolve("world-" + actors.size()), provider.identity(),
                provider, palette, EPOCH, descriptor.initialCenter(), policy, ARENA,
                provider.source().identity(), layout, admission);
        actors.add(actor); queues.put(actor, new ArrayDeque<>()); return actor;
    }

    private Event next(RollingCubeActor actor) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        var queue = queues.get(actor);
        while (queue.isEmpty()) {
            queue.addAll(actor.pollEvents(2));
            if (queue.isEmpty()) {
                if (System.nanoTime() > end) fail("Actor event timeout: " + actor.diagnostics());
                Thread.sleep(1);
            }
        }
        var event = queue.removeFirst();
        if (event instanceof Failure failure) fail(failure.detail());
        return event;
    }

    private StageReady prepared(RollingCubeActor actor) throws Exception {
        var metadata = assertInstanceOf(StageMetadata.class, next(actor));
        assertFalse(metadata.currentPinned()); assertFalse(metadata.candidatePinned());
        Event event;
        while ((event = next(actor)) instanceof Snapshot) { }
        var ready = assertInstanceOf(StageReady.class, event);
        assertNotNull(ready.arenaCertificate()); assertEquals(125, ready.versions().size());
        assertFalse(ready.pinned()); assertNull(ready.activeFocus());
        return ready;
    }

    private StageReady initial(RollingCubeActor actor) throws Exception {
        var ready = prepared(actor);
        assertEquals(Admission.QUEUED, actor.commitPrepared(ready.generation(), ready.arenaCertificate().identity()));
        assertInstanceOf(Committed.class, next(actor));
        publish(actor, ready); return ready;
    }

    private StageReady edit(RollingCubeActor actor, StageReady prior, CubePos target, int state) throws Exception {
        var version = prior.versions().stream().filter(v -> v.position().equals(target)).findFirst().orElseThrow();
        var request = new EditRequest(provider.identity(), EPOCH, target, version.leaseGeneration(),
                version.revision(), target.minBlockX() + 8, target.minBlockY() + 8, target.minBlockZ() + 8, state);
        assertEquals(Admission.QUEUED, actor.tryEdit(request));
        Event event; Snapshot replacement = null;
        while (!((event = next(actor)) instanceof EditAck)) replacement = assertInstanceOf(Snapshot.class, event);
        var ack = (EditAck) event;
        assertEquals(EditStatus.APPLIED, ack.status(), ack.detail());
        assertNotNull(replacement); assertEquals(version.revision() + 1, ack.revision());
        var updated = new ArrayList<>(prior.versions());
        for (int i = 0; i < updated.size(); i++) if (updated.get(i).position().equals(target))
            updated.set(i, new CubeVersion(target, replacement.snapshot().leaseGeneration(), replacement.snapshot().revision()));
        return RollingEditAcknowledgement.ready(prior, target, updated, ack);
    }

    private static void publish(RollingCubeActor actor, StageReady ready) {
        var h = handshakeAt(ready, RollingPublicationHandshake.State.PAIR_READY);
        RollingGateCommands.authorizePublication(actor, h, ready, true, true);
        assertEquals(RollingPublicationHandshake.State.PAIR_READY, h.state());
        h.committed(); assertTrue(h.acceptsMouse(EPOCH, h.publication(), RESOURCES));
    }

    private static void arm(RollingPublicationHandshake h, StageReady ready) {
        h.begin(); h.ready(RollingFarPublication.digest(descriptor, ready));
    }
    private static void acknowledge(RollingPublicationHandshake h) {
        assertTrue(h.prepared(EPOCH, h.publication(), RESOURCES, h.digest())); h.grant();
        assertTrue(h.pairReady(EPOCH, h.publication(), RESOURCES, h.digest()));
    }
    private static RollingPublicationHandshake handshakeAt(StageReady ready, RollingPublicationHandshake.State state) {
        var h = new RollingPublicationHandshake(EPOCH);
        if (state == RollingPublicationHandshake.State.IDLE) return h;
        if (state == RollingPublicationHandshake.State.CLOSED) { h.close(); return h; }
        h.begin(); if (state == RollingPublicationHandshake.State.BUILDING) return h;
        h.ready(RollingFarPublication.digest(descriptor, ready));
        if (state == RollingPublicationHandshake.State.READY) return h;
        assertTrue(h.prepared(EPOCH, h.publication(), RESOURCES, h.digest()));
        if (state == RollingPublicationHandshake.State.PREPARED) return h;
        h.grant(); if (state == RollingPublicationHandshake.State.GRANTED) return h;
        assertTrue(h.pairReady(EPOCH, h.publication(), RESOURCES, h.digest())); return h;
    }
    private static ArenaCertificate mutate(ArenaCertificate c, long generation, long resources) {
        return new ArenaCertificate(c.world(), c.epoch(), resources, c.storeIdentity(), c.indexIdentity(),
                c.providerIdentity(), c.paletteIdentity(), c.materialPolicy(), c.detailPolicy(), generation,
                c.definition(), c.dependencies(), c.captureIdentity(), c.queryAttestation());
    }
    private static StageReady replace(StageReady ready, long generation, ArenaCertificate c) {
        return new StageReady(ready.world(), ready.epoch(), generation, ready.candidateCenter(), ready.versions(),
                null, false, null, null, List.of(), c, ready.arenaCapture());
    }
    private static boolean current(RollingCubeActor actor, String identity) throws Exception {
        return (boolean) committedAuthority.invoke(actor, identity);
    }
    private static void assertIssuedButUncommitted(RollingCubeActor actor, StageReady ready) throws Exception {
        assertTrue(actor.replacementCurrent(ready.arenaCertificate().identity()), "Issued proof still validates");
        assertFalse(current(actor, ready.arenaCertificate().identity()), "Issued proof must not masquerade as committed edit authority");
    }
    private static void assertCommitted(RollingCubeActor actor, StageReady ready, StageReady... obsolete) throws Exception {
        assertTrue(current(actor, ready.arenaCertificate().identity()), "Published exact certificate must own subsequent edits");
        for (var old : obsolete) assertFalse(current(actor, old.arenaCertificate().identity()), "Historical proof cannot retain edit authority");
        assertFalse(actor.diagnostics().currentPinned()); assertFalse(actor.diagnostics().candidatePinned());
        assertEquals(125, actor.diagnostics().residentCubes()); assertEquals(1, actor.diagnostics().tickets());
    }
}
