package io.earthplatform.minecraft;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Independent metadata/capability review. Reuse permission never grants old Jobs current authority. */
class RenderReuseGuardReviewTest {
    private static final WorldIdentity WORLD = new WorldIdentity(82, "reuse-review", "1", "terrain", "native");
    private static final long EPOCH = 11;
    private static EnginePaletteContext palette;

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS,
                VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),
                RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
    }

    private static final class Fixture implements AutoCloseable {
        final WorldIdentity world;
        final long epoch;
        final CubeStreamAdmission admission;
        final RenderPublicationGuard guard;
        final List<CubePos> positions = List.of(new CubePos(5, -688, 7), new CubePos(5, -687, 7));
        final List<CubeStreamAdmission.Lease> leases;
        final List<RenderPublicationGuard.Job> original = new ArrayList<>();
        Fixture() { this(WORLD, EPOCH); }
        Fixture(WorldIdentity world, long epoch) {
            this.world = world; this.epoch = epoch;
            admission = new CubeStreamAdmission(world, epoch, 2);
            guard = new RenderPublicationGuard(admission, 2);
            leases = admission.replaceInterest(positions).requested();
            for (var lease : leases) {
                var job = advance(lease, 3);
                assertTrue(guard.publish(job, () -> {}));
                original.add(job);
            }
        }
        CubeStreamAdmission.Header header(CubeStreamAdmission.Lease lease, long revision) {
            return new CubeStreamAdmission.Header(world, epoch, lease.position(), lease.generation(), revision);
        }
        RenderPublicationGuard.Job advance(CubeStreamAdmission.Lease lease, long revision) {
            var header = header(lease, revision);
            var token = admission.admit(header);
            assertTrue(token.accepted());
            assertEquals(CubeStreamAdmission.Rejection.NONE, admission.commit(token.token(), () -> {}));
            return guard.prepare(EngineCubeSnapshot.capture(lease.position(), world, epoch, lease.generation(), revision,
                    new EngineCubeSection(palette)));
        }
        RenderPublicationGuard.PresentationSource source(List<RenderPublicationGuard.Job> jobs) {
            return guard.presentationSource(jobs.getFirst(), jobs, () -> true);
        }
        @Override public void close() { guard.close(); admission.close(); }
    }

    @Test void newRevisionCanBeSameLiveLeaseWithoutRevivingOldCurrentOrPublishAuthority() {
        try (var f = new Fixture()) {
            var prior = f.original.get(1);
            var replacement = f.advance(f.leases.get(1), 4);
            assertTrue(f.guard.isSameLiveLease(prior, replacement));
            assertFalse(f.guard.isCurrent(prior));
            assertTrue(f.guard.isCurrent(replacement));
            assertFalse(f.guard.publish(prior, () -> fail("Old revision installed")));
            assertFalse(f.guard.drawIfCurrent(List.of(prior), () -> fail("Old revision submitted")));
            assertFalse(f.guard.isSameLiveLease(replacement, prior), "Replacement must be strictly current");
        }
    }

    @Test void replacementMustStillBeCurrentAtEachValidation() {
        try (var f = new Fixture()) {
            var replacement = f.advance(f.leases.get(1), 4);
            assertTrue(f.guard.isSameLiveLease(f.original.get(1), replacement));
            var newest = f.advance(f.leases.get(1), 5);
            assertFalse(f.guard.isSameLiveLease(f.original.get(1), replacement));
            assertTrue(f.guard.isSameLiveLease(f.original.get(1), newest));
            assertFalse(f.guard.isCurrent(replacement));
            assertFalse(f.guard.isCurrent(f.original.get(1)));
        }
    }

    @Test void fullXyzWorldEpochAndGuardIdentityAreNeverInterchangeable() {
        var otherWorld = new WorldIdentity(83, "reuse-review", "1", "terrain", "native");
        try (var f = new Fixture(); var same = new Fixture();
             var alien = new Fixture(otherWorld, EPOCH); var epoch = new Fixture(WORLD, EPOCH + 1)) {
            assertTrue(f.guard.isSameLiveLease(f.original.getFirst(), f.original.getFirst()));
            assertFalse(f.guard.isSameLiveLease(f.original.getFirst(), f.original.get(1)), "Y owner mismatch");
            for (var other : List.of(same, alien, epoch)) {
                assertFalse(f.guard.isSameLiveLease(f.original.getFirst(), other.original.getFirst()));
                assertFalse(f.guard.isSameLiveLease(other.original.getFirst(), f.original.getFirst()));
            }
        }
    }

    @Test void retiredLeaseReentryWithIdenticalValuesAndRevisionCannotPassAba() {
        try (var f = new Fixture()) {
            f.admission.replaceInterest(List.of(f.positions.getFirst()));
            var renewed = f.admission.replaceInterest(f.positions).requested().getFirst();
            assertNotEquals(f.leases.get(1).generation(), renewed.generation());
            var replacement = f.advance(renewed, 3);
            assertFalse(f.guard.isSameLiveLease(f.original.get(1), replacement));
            assertFalse(f.guard.isCurrent(f.original.get(1)));
            assertTrue(f.guard.isCurrent(replacement));
        }
    }

    @Test void resourceGenerationAndReloadRequestCannotBeReboundAway() {
        try (var f = new Fixture()) {
            f.guard.invalidateResources();
            var freshResources = f.guard.prepare(f.original.getFirst().snapshot());
            assertTrue(f.guard.isCurrent(freshResources));
            assertFalse(f.guard.isSameLiveLease(f.original.getFirst(), freshResources));
            assertFalse(f.guard.isSameLiveLease(f.original.getFirst(), f.original.getFirst()));
            f.guard.requestInvalidation();
            assertFalse(f.guard.isSameLiveLease(freshResources, freshResources));
        }
    }

    @Test void guardFailureAndAdmissionFailurePermanentlyRevokeReusePermission() {
        for (boolean guardFailure : new boolean[]{false, true}) {
            try (var f = new Fixture()) {
                var prior = f.original.getFirst();
                if (guardFailure) {
                    assertThrows(IllegalArgumentException.class, () -> f.guard.publish(prior,
                            () -> { throw new IllegalArgumentException("upload"); }));
                } else {
                    var token = f.admission.admit(f.header(f.leases.getFirst(), 4));
                    assertThrows(IllegalArgumentException.class, () -> f.admission.commit(token.token(),
                            () -> { throw new IllegalArgumentException("source install"); }));
                }
                assertFalse(f.guard.isSameLiveLease(prior, prior));
                assertFalse(f.guard.isCurrent(prior));
            }
        }
    }

    @Test void oldAndPreparedNewSourcesCoexistWithImmutableHistoricalSnapshots() {
        try (var f = new Fixture()) {
            var oldSource = f.source(f.original);
            try (var history = f.guard.retainPresentation(List.of(oldSource))) {
                var token = history.tokenFor(oldSource);
                var priorSnapshot = history.snapshots().get(1);
                var replacement = f.advance(f.leases.get(1), 4);
                var freshOwner = f.guard.prepare(f.original.getFirst().snapshot());
                assertTrue(f.guard.publish(freshOwner, () -> {}));
                var preparedSource = f.source(List.of(freshOwner, replacement));
                assertTrue(f.guard.isRetained(oldSource));
                assertFalse(f.guard.isRetained(preparedSource));
                assertFalse(f.guard.isPresented(token, preparedSource));
                assertTrue(f.guard.isPresented(token, oldSource));
                assertSame(priorSnapshot, history.snapshots().get(1));
                assertEquals(3, priorSnapshot.revision());
                preparedSource.close(); // Cancelling a candidate must not revoke the original physical binding.
                assertTrue(f.guard.drawIfPresented(token, oldSource, () -> true));
                assertFalse(f.guard.isCurrent(f.original.get(1)));
            }
            assertFalse(f.guard.isRetained(oldSource));
        }
    }

    @Test void invalidatedPresentationStillOwnsItsSourceUntilExplicitRelease() {
        try (var f = new Fixture()) {
            var source = f.source(f.original);
            var history = f.guard.retainPresentation(List.of(source));
            assertTrue(f.guard.isRetained(source));
            f.guard.requestInvalidation();
            assertFalse(history.isPresentable());
            assertTrue(f.guard.isRetained(source), "Liveness failure cannot silently drop retained ownership");
            assertEquals(2, history.snapshots().size());
            history.close();
            assertFalse(f.guard.isRetained(source));
            assertTrue(history.snapshots().isEmpty());
        }
    }

    @Test void sourceRevocationIsScopedAndRetainedLookupRejectsForeignSources() {
        try (var f = new Fixture(); var other = new Fixture()) {
            var source = f.source(f.original);
            var alien = other.source(other.original);
            try (var history = f.guard.retainPresentation(List.of(source))) {
                assertFalse(other.guard.isRetained(source));
                assertFalse(f.guard.isRetained(alien));
                source.close();
                assertFalse(history.isPresentable());
                assertTrue(f.guard.isRetained(source), "Retained capability remains owned after source revocation");
            }
        }
    }

    @Test void metadataHelpersRemainOwnerAffine() {
        try (var f = new Fixture()) {
            var source = f.source(f.original);
            CompletableFuture.runAsync(() -> {
                assertThrows(IllegalStateException.class,
                        () -> f.guard.isSameLiveLease(f.original.getFirst(), f.original.getFirst()));
                assertThrows(IllegalStateException.class, () -> f.guard.isRetained(source));
            }).join();
            assertTrue(f.guard.isSameLiveLease(f.original.getFirst(), f.original.getFirst()));
        }
    }

    @Test void readValidationAndCurrentSubmissionShareTheAdmissionRetirementLock() throws Exception {
        var entered = new CountDownLatch(1);
        var attempted = new CountDownLatch(1);
        var completed = new AtomicBoolean();
        var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try (var f = new Fixture()) {
            var prior = f.original.get(1);
            var replacement = f.advance(f.leases.get(1), 4);
            var retired = executor.submit(() -> {
                await(entered); attempted.countDown();
                f.admission.replaceInterest(List.of(f.positions.getFirst()));
                assertTrue(completed.get(), "Retirement crossed synchronous validation/submission");
            });
            assertTrue(f.guard.drawIfCurrent(List.of(f.original.getFirst(), replacement), () -> {
                assertTrue(Thread.holdsLock(f.admission));
                assertTrue(f.guard.isSameLiveLease(prior, replacement));
                entered.countDown(); await(attempted);
                assertThrows(TimeoutException.class, () -> retired.get(100, TimeUnit.MILLISECONDS));
                assertTrue(f.guard.isSameLiveLease(prior, replacement));
                completed.set(true); return true;
            }));
            retired.get(10, TimeUnit.SECONDS);
            assertFalse(f.guard.isSameLiveLease(prior, replacement));
        } finally {
            entered.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
}
