package io.earthplatform.minecraft;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real publication/admission capabilities; headless, without claiming native GL execution. */
class RetainedNativePresentationTest {
    private static final WorldIdentity WORLD = new WorldIdentity(42, "retained", "1", "terrain", "native");
    private static final long EPOCH = 7;
    private static EnginePaletteContext palette;

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS,
                VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),
                RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
    }

    private static EngineCubeSnapshot snapshot(CubeStreamAdmission.Header header) {
        return EngineCubeSnapshot.capture(header.position(), header.world(), header.connectionEpoch(),
                header.leaseGeneration(), header.revision(), new EngineCubeSection(palette));
    }
    private static CubeStreamAdmission.Header header(CubeStreamAdmission.Lease lease, long revision) {
        return new CubeStreamAdmission.Header(WORLD, EPOCH, lease.position(), lease.generation(), revision);
    }
    private static void commit(CubeStreamAdmission gate, CubeStreamAdmission.Header header) {
        var admitted = gate.admit(header);
        assertTrue(admitted.accepted());
        assertEquals(CubeStreamAdmission.Rejection.NONE, gate.commit(admitted.token(), () -> {}));
    }
    private static final class Fixture implements AutoCloseable {
        final CubeStreamAdmission gate;
        final RenderPublicationGuard guard;
        final List<CubeStreamAdmission.Lease> leases;
        final List<RenderPublicationGuard.Job> jobs = new ArrayList<>();
        Fixture(int count) {
            gate = new CubeStreamAdmission(WORLD, EPOCH, Math.max(1, count));
            guard = new RenderPublicationGuard(gate, Math.max(1, count));
            var positions = new ArrayList<CubePos>();
            // Same XZ, independent Y, so accidentally column-based provenance cannot pass.
            for (int i = 0; i < count; i++) positions.add(new CubePos(2, i - 688, 3));
            leases = gate.replaceInterest(positions).requested();
            for (var lease : leases) {
                var header = header(lease, 3);
                commit(gate, header);
                var job = guard.prepare(snapshot(header));
                assertTrue(guard.publish(job, () -> {}));
                jobs.add(job);
            }
        }
        RenderPublicationGuard.PresentationSource source(int index) {
            return source(index, List.of(jobs.get(index)), () -> true);
        }
        RenderPublicationGuard.PresentationSource source(int index, List<RenderPublicationGuard.Job> dependencies,
                                                       BooleanSupplier usable) {
            return guard.presentationSource(jobs.get(index), dependencies, usable);
        }
        @Override public void close() { guard.close();gate.close(); }
    }

    @Test void exactOldSnapshotsPresentAfterReplacementButRemainStrictlyNotCurrent() {
        try (var f = new Fixture(2)) {
            var source = f.source(0, f.jobs, () -> true);
            try (var retained = f.guard.retainPresentation(List.of(source))) {
                var token = retained.tokenFor(source);
                assertEquals(1, retained.meshCount());
                assertEquals(2, retained.dependencyCount());
                assertSame(f.jobs.getFirst().snapshot(), retained.snapshots().getFirst());
                assertThrows(UnsupportedOperationException.class, () -> retained.snapshots().clear());
                commit(f.gate, header(f.leases.get(1), 4));
                assertFalse(f.guard.isCurrent(f.jobs.get(1)));
                assertFalse(f.guard.drawIfCurrent(f.jobs, () -> fail("Stale native current draw entered")));
                assertTrue(retained.isCurrent());
                assertTrue(f.guard.drawIfPresented(token, source, () -> {
                    assertTrue(Thread.holdsLock(f.gate));
                    assertSame(f.jobs.get(1).snapshot(), retained.snapshots().get(1));
                    assertEquals(3, retained.snapshots().get(1).revision());
                    return true;
                }));
                assertFalse(f.guard.drawIfPresented(token, source, () -> false));
                assertThrows(IllegalStateException.class, () -> f.guard.prepare(f.jobs.get(1).snapshot()));
            }
        }
    }

    @Test void captureMustPrecedeReplacementAndRequiresPublishedExactOwner() {
        try (var f = new Fixture(2)) {
            var uninstalled = f.guard.prepare(f.jobs.getFirst().snapshot());
            assertThrows(IllegalArgumentException.class,
                    () -> f.guard.presentationSource(uninstalled, List.of(uninstalled), () -> true));
            assertThrows(IllegalArgumentException.class, () -> f.source(0, List.of(f.jobs.get(1)), () -> true));
            assertThrows(IllegalArgumentException.class, () -> f.source(0, List.of(f.jobs.get(0), f.jobs.get(0)), () -> true));
            var source = f.source(0, f.jobs, () -> true);
            commit(f.gate, header(f.leases.get(1), 4));
            assertThrows(IllegalStateException.class, () -> f.guard.retainPresentation(List.of(source)));
            try (var retained = f.guard.retainPresentation(List.of(f.source(0)))) {
                assertTrue(retained.isPresentable(), "Failed acquisition must release its temporary capture slot");
            }
        }
    }

    @Test void tokenRejectsAlienMeshOtherTicketAndOtherGuard() {
        try (var f = new Fixture(2); var other = new Fixture(2)) {
            var first = f.source(0);
            var second = f.source(1);
            var alien = other.source(0);
            var retained = f.guard.retainPresentation(List.of(first));
            var token = retained.tokenFor(first);
            assertFalse(f.guard.isPresented(token, second));
            assertFalse(f.guard.drawIfPresented(token, second, () -> fail("Alien mesh submitted")));
            assertFalse(other.guard.drawIfPresented(token, first, () -> fail("Alien guard submitted")));
            assertThrows(IllegalArgumentException.class, () -> retained.tokenFor(second));
            assertThrows(IllegalStateException.class, () -> other.guard.retainPresentation(List.of(first)));
            try (var own = other.guard.retainPresentation(List.of(alien))) { assertTrue(own.isCurrent()); }
            retained.close();
            try (var next = f.guard.retainPresentation(List.of(first))) {
                assertFalse(f.guard.isPresented(token, first));
                assertTrue(f.guard.isPresented(next.tokenFor(first), first));
            }
        }
    }

    @Test void unionRequiresExactImmutableSnapshotIdentityEvenWithEqualMetadata() {
        try (var f = new Fixture(2)) {
            var sameHeaderDifferentPayloadIdentity = f.guard.prepare(snapshot(header(f.leases.get(1), 3)));
            var first = f.source(0, f.jobs, () -> true);
            var second = f.source(1, List.of(f.jobs.get(1), f.jobs.get(0)), () -> true);
            var mismatched = f.source(0, List.of(f.jobs.get(0), sameHeaderDifferentPayloadIdentity), () -> true);
            assertThrows(IllegalArgumentException.class, () -> f.guard.retainPresentation(List.of(second, mismatched)));
            try (var retained = f.guard.retainPresentation(List.of(first, second))) {
                assertEquals(2, retained.dependencyCount());
                assertEquals(2, retained.meshCount());
            }
        }
    }

    @Test void oneTicketPerGuardAndDuplicateSourcesCannotBypassBudget() {
        try (var f = new Fixture(1)) {
            var source = f.source(0);
            assertThrows(IllegalArgumentException.class, () -> f.guard.retainPresentation(List.of()));
            assertThrows(IllegalArgumentException.class, () -> f.guard.retainPresentation(List.of(source, source)));
            var retained = f.guard.retainPresentation(List.of(source));
            assertThrows(IllegalStateException.class, () -> f.guard.retainPresentation(List.of(source)));
            retained.close();retained.close();
            assertEquals(0, retained.meshCount());
            assertEquals(0, retained.dependencyCount());
            assertTrue(retained.snapshots().isEmpty());
            assertFalse(retained.isCurrent());
            assertThrows(IllegalStateException.class, () -> retained.tokenFor(source));
            try (var next = f.guard.retainPresentation(List.of(source))) { assertTrue(next.isCurrent()); }
        }
    }

    @Test void exact28MeshesAnd152CoordinatesAreAllowedButNextIsRejectedAtomically() {
        try (var f = new Fixture(153)) {
            var sources = new ArrayList<RenderPublicationGuard.PresentationSource>();
            for (int i = 0; i < 28; i++) sources.add(f.source(i, f.jobs.subList(0, 152), () -> true));
            var extraMesh = new ArrayList<>(sources);extraMesh.add(f.source(28));
            assertThrows(IllegalArgumentException.class, () -> f.guard.retainPresentation(extraMesh));
            assertThrows(IllegalArgumentException.class,
                    () -> f.guard.retainPresentation(List.of(f.source(0, f.jobs, () -> true))));
            try (var retained = f.guard.retainPresentation(sources)) {
                assertEquals(28, retained.meshCount());
                assertEquals(152, retained.dependencyCount());
            }
        }
    }

    @Test void boundedCallerTraversalDoesNotTrustCollectionSizeAndCannotReenterCapture() {
        try (var f = new Fixture(1)) {
            var reads = new AtomicInteger();
            var sources = new ArrayList<RenderPublicationGuard.PresentationSource>();
            for (int i = 0; i < 29; i++) sources.add(f.source(0));
            var unbounded = new AbstractCollection<RenderPublicationGuard.PresentationSource>() {
                @Override public int size() { return 1; }
                @Override public Iterator<RenderPublicationGuard.PresentationSource> iterator() {
                    assertTrue(Thread.holdsLock(f.gate));
                    assertThrows(IllegalStateException.class, () -> f.guard.retainPresentation(List.of(sources.getFirst())));
                    return new Iterator<>() {
                        @Override public boolean hasNext() { return true; }
                        @Override public RenderPublicationGuard.PresentationSource next() {
                            int index = reads.getAndIncrement();
                            assertTrue(index < 29);
                            return sources.get(index);
                        }
                    };
                }
            };
            assertThrows(IllegalArgumentException.class, () -> f.guard.retainPresentation(unbounded));
            assertEquals(29, reads.get());
            try (var retained = f.guard.retainPresentation(List.of(sources.getFirst()))) { assertTrue(retained.isCurrent()); }
        }
    }

    @Test void retiredHaloAndReentryABAPermanentlyRejectTheEntireTicket() {
        try (var f = new Fixture(2)) {
            var source = f.source(0, f.jobs, () -> true);
            try (var retained = f.guard.retainPresentation(List.of(source))) {
                var token = retained.tokenFor(source);
                f.gate.replaceInterest(List.of(f.leases.getFirst().position()));
                assertFalse(retained.isCurrent());
                var renewed = f.gate.replaceInterest(f.leases.stream().map(CubeStreamAdmission.Lease::position).toList()).requested().getFirst();
                assertNotEquals(f.leases.get(1).generation(), renewed.generation());
                commit(f.gate, header(renewed, 3));
                assertFalse(f.guard.drawIfPresented(token, source, () -> fail("Reentered lease revived frozen scene")));
            }
        }
    }

    @Test void appearanceChangeRevocationDoesNotReviveAfterOptionsReturnToTheirOldValue() {
        try (var f = new Fixture(1)) {
            var usable = new AtomicBoolean(true);
            var source = f.source(0, f.jobs, usable::get);
            try (var retained = f.guard.retainPresentation(List.of(source))) {
                usable.set(false);
                assertFalse(retained.isCurrent());
                usable.set(true);
                assertFalse(retained.isCurrent());
            }
            try (var retained = f.guard.retainPresentation(List.of(source))) {
                source.close();source.close();
                assertFalse(retained.isCurrent());
            }
        }
    }

    @Test void sourceReloadResourceGenerationGuardCloseAndFailureInvalidatePresentation() {
        for (int action = 0; action < 6; action++) {
            try (var f = new Fixture(1)) {
                var source = f.source(0);
                try (var retained = f.guard.retainPresentation(List.of(source))) {
                    var token = retained.tokenFor(source);
                    switch (action) {
                        case 0 -> f.guard.requestInvalidation();
                        case 1 -> f.guard.invalidateResources();
                        case 2 -> f.guard.close();
                        case 3 -> assertThrows(IllegalArgumentException.class,
                                () -> f.guard.publish(f.jobs.getFirst(), () -> { throw new IllegalArgumentException("upload"); }));
                        case 4 -> f.gate.close();
                        case 5 -> {
                            var admission = f.gate.admit(header(f.leases.getFirst(), 4));
                            assertThrows(IllegalArgumentException.class,
                                    () -> f.gate.commit(admission.token(), () -> { throw new IllegalArgumentException("install"); }));
                        }
                    }
                    assertFalse(retained.isCurrent());
                    assertFalse(f.guard.drawIfPresented(token, source, () -> fail("Revoked presentation submitted")));
                }
            }
        }
    }

    @Test void capturePredicateFailureRollsBackAndEverySourceIsRecheckedBeforePublication() {
        try (var f = new Fixture(2)) {
            var throwNow = new AtomicBoolean();
            var invalidates = new AtomicBoolean();
            var first = f.source(0);
            var second = f.source(1, List.of(f.jobs.get(1)), () -> {
                if (throwNow.get()) throw new IllegalArgumentException("appearance lookup");
                if (invalidates.get()) first.close();
                return true;
            });
            throwNow.set(true);
            assertThrows(IllegalArgumentException.class, () -> f.guard.retainPresentation(List.of(first, second)));
            throwNow.set(false);
            try (var retained = f.guard.retainPresentation(List.of(first, second))) { assertTrue(retained.isCurrent()); }
            invalidates.set(true);
            assertThrows(IllegalStateException.class, () -> f.guard.retainPresentation(List.of(first, second)));
            invalidates.set(false);
            try (var retained = f.guard.retainPresentation(List.of(second))) { assertTrue(retained.isCurrent()); }
        }
    }

    @Test void preAndPostReentrantFencesRejectMutationAndThrownDrawFailsClosed() {
        for (int action = 0; action < 4; action++) {
            try (var f = new Fixture(1)) {
                var source = f.source(0);
                try (var retained = f.guard.retainPresentation(List.of(source))) {
                    var token = retained.tokenFor(source);
                    int mutate = action;
                    assertFalse(f.guard.drawIfPresented(token, source, () -> {
                        switch (mutate) {
                            case 0 -> f.guard.requestInvalidation();
                            case 1 -> source.close();
                            case 2 -> f.gate.replaceInterest(List.of());
                            case 3 -> retained.close();
                        }
                        return true;
                    }));
                    assertFalse(f.guard.drawIfPresented(token, source, () -> fail("Second submission escaped revocation")));
                }
            }
        }
        try (var f = new Fixture(1)) {
            var source = f.source(0);
            try (var retained = f.guard.retainPresentation(List.of(source))) {
                assertThrows(IllegalArgumentException.class, () -> f.guard.drawIfPresented(retained.tokenFor(source), source,
                        () -> { throw new IllegalArgumentException("native draw"); }));
                assertFalse(retained.isCurrent());
                assertFalse(f.guard.isCurrent(f.jobs.getFirst()));
            }
        }
    }

    @Test void predicateReentrantInvalidationPreventsEntryAndLatchesEvenWhenPredicateThrows() {
        try (var f = new Fixture(1)) {
            var armed = new AtomicBoolean();
            var source = f.source(0, f.jobs, () -> {
                if (armed.get()) f.guard.requestInvalidation();
                return true;
            });
            try (var retained = f.guard.retainPresentation(List.of(source))) {
                armed.set(true);
                assertFalse(f.guard.drawIfPresented(retained.tokenFor(source), source, () -> fail("Pre-fence skipped predicate mutation")));
            }
        }
        try (var f = new Fixture(1)) {
            var armed = new AtomicBoolean();
            var source = f.source(0, f.jobs, () -> {
                if (armed.get()) throw new IllegalArgumentException("appearance");
                return true;
            });
            try (var retained = f.guard.retainPresentation(List.of(source))) {
                armed.set(true);
                assertThrows(IllegalArgumentException.class, retained::isCurrent);
                armed.set(false);
                assertFalse(retained.isCurrent());
            }
        }
    }

    @Test void capabilitiesAreOwnerThreadAffineButReloadRequestIsOffThreadSafe() {
        try (var f = new Fixture(1)) {
            var source = f.source(0);
            try (var retained = f.guard.retainPresentation(List.of(source))) {
                var token = retained.tokenFor(source);
                CompletableFuture.runAsync(() -> {
                    assertThrows(IllegalStateException.class, retained::close);
                    assertThrows(IllegalStateException.class, retained::isCurrent);
                    assertThrows(IllegalStateException.class, source::close);
                    assertThrows(IllegalStateException.class, () -> retained.tokenFor(source));
                    assertThrows(IllegalStateException.class, () -> f.guard.retainPresentation(List.of(source)));
                    assertThrows(IllegalStateException.class, () -> f.guard.drawIfPresented(token, source, () -> true));
                    f.guard.requestInvalidation();
                }).join();
                assertFalse(retained.isCurrent());
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt();throw new AssertionError(interrupted); }
    }

    @Test void concurrentRetirementAndReloadCannotCrossNativeSubmissionOrRestorationFence() throws Exception {
        for (boolean reload : new boolean[]{false, true}) {
            var entered = new CountDownLatch(1);
            var attempted = new CountDownLatch(1);
            var restored = new AtomicBoolean();
            var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
            try (var f = new Fixture(1)) {
                var source = f.source(0);
                try (var retained = f.guard.retainPresentation(List.of(source))) {
                    var token = retained.tokenFor(source);
                    var mutation = executor.submit(() -> {
                        await(entered);attempted.countDown();
                        if (reload) f.guard.requestInvalidation();else f.gate.replaceInterest(List.of());
                        assertTrue(restored.get(), "Mutation crossed the native restoration fence");
                    });
                    assertTrue(f.guard.drawIfPresented(token, source, () -> {
                        assertTrue(Thread.holdsLock(f.gate));
                        entered.countDown();await(attempted);
                        assertThrows(TimeoutException.class, () -> mutation.get(100, TimeUnit.MILLISECONDS));
                        assertTrue(f.guard.isPresented(token, source));
                        restored.set(true);
                        return true;
                    }));
                    mutation.get(10, TimeUnit.SECONDS);
                    assertFalse(f.guard.drawIfPresented(token, source, () -> fail("Invalidation had already completed")));
                }
            } finally {
                entered.countDown();executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test void allocationFreeLeaseIdentityCheckPreservesWorldEpochXYZGenerationAndLoadedState() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var position = new CubePos(1, -688, 1);
        var lease = gate.replaceInterest(List.of(position)).requested().getFirst();
        var original = header(lease, 3);
        assertFalse(gate.isCurrentLoadedLease(original));
        commit(gate, original);
        assertTrue(gate.isCurrentLoadedLease(original));
        commit(gate, header(lease, 4));
        assertTrue(gate.isCurrentLoadedLease(original));
        assertFalse(gate.isCurrentLoaded(original));
        assertFalse(gate.isCurrentLoadedLease(header(lease, 5)));
        var wrongWorld = new WorldIdentity(43, "retained", "1", "terrain", "native");
        assertFalse(gate.isCurrentLoadedLease(new CubeStreamAdmission.Header(wrongWorld, EPOCH, position, lease.generation(), 3)));
        assertFalse(gate.isCurrentLoadedLease(new CubeStreamAdmission.Header(WORLD, EPOCH + 1, position, lease.generation(), 3)));
        assertFalse(gate.isCurrentLoadedLease(new CubeStreamAdmission.Header(WORLD, EPOCH, position.offset(0, 1, 0), lease.generation(), 3)));
        assertFalse(gate.isCurrentLoadedLease(new CubeStreamAdmission.Header(WORLD, EPOCH, position, lease.generation() + 1, 3)));
        gate.replaceInterest(List.of());
        var replacement = gate.replaceInterest(List.of(position)).requested().getFirst();
        commit(gate, header(replacement, 4));
        assertFalse(gate.isCurrentLoadedLease(original));
        gate.close();
        assertFalse(gate.isCurrentLoadedLease(header(replacement, 4)));
    }
}
