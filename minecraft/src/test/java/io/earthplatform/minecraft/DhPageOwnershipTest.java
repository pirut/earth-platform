package io.earthplatform.minecraft;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.lod.DhPageOwnership;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import static org.junit.jupiter.api.Assertions.*;

/** Headless checks with actual admission/native-registry snapshots; these do not claim GPU execution. */
class DhPageOwnershipTest {
    private static final WorldIdentity WORLD = new WorldIdentity(42, "earth", "surface-fill-v1", "noaa-etopo-2022", "materials-v1");
    private static final UUID SESSION = UUID.fromString("63f02eb7-8ceb-4e5f-a267-2607ebc42484");
    private static final long EPOCH = 7;
    private static final CubePos LOWER = CubePos.fromBlock(20_037_508, -11000, -10_018_754);
    private static final CubePos UPPER = CubePos.fromBlock(20_037_508, 8849, -10_018_754);
    private static EnginePaletteContext palette;

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS,
                VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),
                RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
    }
    private static DhPageOwnership.Namespace namespace() {
        return new DhPageOwnership.Namespace(WORLD, SESSION, "earth:overworld", "equirectangular", "v1", "EGM2008", 1, 2048);
    }
    private static EngineCubeSnapshot snapshot(CubePos cube, WorldIdentity world, long epoch, long generation, long revision, BlockState marker) {
        var section = new EngineCubeSection(palette);
        section.set(8, 8, 8, marker);
        return EngineCubeSnapshot.capture(cube, world, epoch, generation, revision, section);
    }
    private static EngineCubeSnapshot snapshot(CubeStreamAdmission gate, CubeStreamAdmission.Lease lease, long revision) {
        return snapshot(lease.position(), gate.world(), gate.connectionEpoch(), lease.generation(), revision, Blocks.GOLD_BLOCK.defaultBlockState());
    }
    private static void commit(CubeStreamAdmission gate, EngineCubeSnapshot snapshot) {
        var admitted = gate.admit(new CubeStreamAdmission.Header(snapshot.world(), snapshot.epoch(), snapshot.position(), snapshot.leaseGeneration(), snapshot.revision()));
        assertTrue(admitted.accepted());
        assertEquals(CubeStreamAdmission.Rejection.NONE, gate.commit(admitted.token(), () -> {}));
    }
    private static DhPageOwnership.Job publish(DhPageOwnership owner, EngineCubeSnapshot snapshot) {
        var job = owner.prepare(snapshot);
        assertTrue(owner.publish(job, () -> {}));
        return job;
    }
    private static WorldIdentity otherWorld() {
        return new WorldIdentity(43, "earth", "surface-fill-v1", "noaa-etopo-2022", "materials-v1");
    }

    @Test void namespacePinsEveryGenerationAndCoordinateSystemInput() {
        var base = namespace();
        assertEquals(base, new DhPageOwnership.Namespace(WORLD, SESSION, "earth:overworld", "equirectangular", "v1", "EGM2008"));
        var variants = List.of(
                new DhPageOwnership.Namespace(otherWorld(), SESSION, base.dimension(), base.projection(), base.projectionVersion(), base.verticalDatum()),
                new DhPageOwnership.Namespace(WORLD, new UUID(0, 1), base.dimension(), base.projection(), base.projectionVersion(), base.verticalDatum()),
                new DhPageOwnership.Namespace(WORLD, SESSION, "earth:other", base.projection(), base.projectionVersion(), base.verticalDatum()),
                new DhPageOwnership.Namespace(WORLD, SESSION, base.dimension(), "mercator", base.projectionVersion(), base.verticalDatum()),
                new DhPageOwnership.Namespace(WORLD, SESSION, base.dimension(), base.projection(), "v2", base.verticalDatum()),
                new DhPageOwnership.Namespace(WORLD, SESSION, base.dimension(), base.projection(), base.projectionVersion(), "WGS84"));
        var key = new DhPageOwnership.PageKey(base, -6, LOWER.x(), LOWER.z(), 0);
        for (var variant : variants) {
            assertNotEquals(base, variant);
            assertNotEquals(key, new DhPageOwnership.PageKey(variant, -6, LOWER.x(), LOWER.z(), 0));
        }
        assertThrows(IllegalArgumentException.class, () -> new DhPageOwnership.Namespace(WORLD, SESSION, "dim", "proj", "v1", "datum", 2, 2048));
        assertThrows(IllegalArgumentException.class, () -> new DhPageOwnership.Namespace(WORLD, SESSION, "dim", "proj", "v1", "datum", 1, 4096));
        assertThrows(IllegalArgumentException.class, () -> new DhPageOwnership.Namespace(WORLD, SESSION, " ", "proj", "v1", "datum"));
        assertThrows(IllegalArgumentException.class, () -> new DhPageOwnership.PageKey(base, -6, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DhPageOwnership(new CubeStreamAdmission(otherWorld(), EPOCH, 2), base));
    }

    @Test void signedPagesFloorDivideNegativeBoundariesAndNativeExtrema() {
        for (int y : new int[]{-4097, -4096, -2049, -2048, -1, 0, 2047, 2048, -11000, 8849}) {
            var cube = CubePos.fromBlock(20_037_508, y, -10_018_754);
            var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
            var lease = gate.replaceInterest(List.of(cube)).requested().getFirst();
            var snapshot = snapshot(gate, lease, 3);
            commit(gate, snapshot);
            try (var owner = new DhPageOwnership(gate, namespace())) {
                var job = owner.prepare(snapshot);
                assertSame(snapshot, job.snapshot());
                assertEquals(cube, job.cube());
                assertEquals(Math.floorDiv(cube.minBlockY(), 2048), job.key().pageY());
                assertEquals(cube.x(), job.key().tileX());
                assertEquals(cube.z(), job.key().tileZ());
                assertEquals(0, job.key().detail());
                assertEquals(namespace(), job.key().namespace());
                if (y == -11000) assertEquals(-6, job.key().pageY());
                if (y == 8849) assertEquals(4, job.key().pageY());
            }
        }
    }

    @Test void onlyCommittedThenSuccessfullyPublishedJobsMayDraw() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        try (var owner = new DhPageOwnership(gate, namespace())) {
            assertThrows(IllegalStateException.class, () -> owner.prepare(snapshot));
            var pending = gate.admit(new CubeStreamAdmission.Header(WORLD, EPOCH, LOWER, lease.generation(), 3));
            assertTrue(pending.accepted());
            assertThrows(IllegalStateException.class, () -> owner.prepare(snapshot));
            assertEquals(CubeStreamAdmission.Rejection.NONE, gate.commit(pending.token(), () -> {}));
            var job = owner.prepare(snapshot);
            assertTrue(owner.isCurrent(job));
            assertFalse(owner.shouldDrawLod(job, List.of()));
            assertTrue(owner.publish(job, () -> {
                assertTrue(Thread.holdsLock(gate));
                assertFalse(owner.shouldDrawLod(job, List.of()), "Candidate is private during installation");
            }));
            assertTrue(owner.shouldDrawLod(job, List.of()));
            assertEquals(1, owner.published());
            assertEquals(0, owner.rejected());
            var replacement = owner.prepare(snapshot);
            assertTrue(owner.shouldDrawLod(job, List.of()));
            assertTrue(owner.publish(replacement, () -> assertTrue(owner.shouldDrawLod(job, List.of()))));
            assertFalse(owner.shouldDrawLod(job, List.of()), "Superseded GL candidate must not draw");
            assertTrue(owner.shouldDrawLod(replacement, List.of()));
        }
    }

    @Test void nativeReadinessMasksOnlyTheFullCubeIncludingYEvenInsideOnePage() {
        for (var cubes : List.of(List.of(LOWER, UPPER), List.of(new CubePos(0, 0, 0), new CubePos(0, 1, 0)))) {
            var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
            var leases = gate.replaceInterest(cubes).requested();
            var lower = snapshot(gate, leases.get(0), 3);
            var upper = snapshot(gate, leases.get(1), 3);
            commit(gate, lower); commit(gate, upper);
            try (var owner = new DhPageOwnership(gate, namespace())) {
                var lowJob = publish(owner, lower);
                var highJob = publish(owner, upper);
                assertFalse(owner.shouldDrawLod(lowJob, List.of(lower)));
                assertTrue(owner.shouldDrawLod(highJob, List.of(lower)));
                assertTrue(owner.shouldDrawLod(lowJob, List.of(upper)));
                assertFalse(owner.shouldDrawLod(highJob, List.of(upper)));
                assertFalse(owner.shouldDrawLod(lowJob, List.of(lower, upper)));
                assertFalse(owner.shouldDrawLod(highJob, List.of(lower, upper)));
                assertTrue(owner.shouldDrawLod(lowJob, List.of()), "Retiring a native mesh restores LOD immediately");
                if (cubes.getFirst().y() == 0) assertEquals(lowJob.key(), highJob.key(), "Shared page is not shared coverage");
                else assertNotEquals(lowJob.key(), highJob.key());
            }
        }
    }

    @Test void staleOrForeignCoverageCannotSuppressCurrentLodOrPrepare() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var current = snapshot(gate, lease, 3);
        commit(gate, current);
        var marker = Blocks.GOLD_BLOCK.defaultBlockState();
        var invalid = List.of(
                snapshot(LOWER, otherWorld(), EPOCH, lease.generation(), 3, marker),
                snapshot(LOWER, WORLD, EPOCH + 1, lease.generation(), 3, marker),
                snapshot(UPPER, WORLD, EPOCH, lease.generation(), 3, marker),
                snapshot(new CubePos(LOWER.x() + 1, LOWER.y(), LOWER.z()), WORLD, EPOCH, lease.generation(), 3, marker),
                snapshot(new CubePos(LOWER.x(), LOWER.y(), LOWER.z() + 1), WORLD, EPOCH, lease.generation(), 3, marker),
                snapshot(LOWER, WORLD, EPOCH, lease.generation() + 1, 3, marker),
                snapshot(LOWER, WORLD, EPOCH, lease.generation(), 2, marker),
                snapshot(LOWER, WORLD, EPOCH, lease.generation(), 4, marker));
        try (var owner = new DhPageOwnership(gate, namespace())) {
            var job = publish(owner, current);
            for (var stale : invalid) {
                assertTrue(owner.shouldDrawLod(job, List.of(stale)));
                assertThrows(IllegalStateException.class, () -> owner.prepare(stale));
            }
        }
    }

    @Test void pendingRevisionPreservesOldCoverageButCommittedRevisionInvalidatesIt() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var original = snapshot(gate, lease, 3);
        commit(gate, original);
        try (var owner = new DhPageOwnership(gate, namespace())) {
            var old = publish(owner, original);
            var newer = snapshot(gate, lease, 4);
            var pending = gate.admit(new CubeStreamAdmission.Header(WORLD, EPOCH, LOWER, lease.generation(), 4));
            assertTrue(pending.accepted());
            assertFalse(owner.shouldDrawLod(old, List.of(original)));
            assertTrue(owner.shouldDrawLod(old, List.of()));
            assertEquals(CubeStreamAdmission.Rejection.NONE, gate.commit(pending.token(), () -> {}));
            assertFalse(owner.isCurrent(old));
            assertFalse(owner.shouldDrawLod(old, List.of()));
            assertFalse(owner.publish(old, () -> fail("Old revision installed")));
            var next = publish(owner, newer);
            assertTrue(owner.shouldDrawLod(next, List.of(original)));
            assertFalse(owner.shouldDrawLod(next, List.of(newer)));
        }
    }

    @Test void sameEpochRetireReaddAbaCannotReuseOldJobOrNearCoverage() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var leases = gate.replaceInterest(List.of(LOWER, UPPER)).requested();
        var lower = snapshot(gate, leases.get(0), 3);
        var upper = snapshot(gate, leases.get(1), 3);
        commit(gate, lower); commit(gate, upper);
        try (var owner = new DhPageOwnership(gate, namespace())) {
            var old = publish(owner, lower);
            var retained = publish(owner, upper);
            gate.replaceInterest(List.of(UPPER));
            assertFalse(owner.isCurrent(old));
            assertFalse(owner.shouldDrawLod(old, List.of(lower)));
            var renewed = gate.replaceInterest(List.of(LOWER, UPPER)).requested().getFirst();
            assertTrue(renewed.generation() > lower.leaseGeneration());
            var replacement = snapshot(gate, renewed, 3);
            commit(gate, replacement);
            assertFalse(owner.publish(old, () -> fail("ABA publication installed")));
            var next = publish(owner, replacement);
            assertTrue(owner.shouldDrawLod(next, List.of(lower)));
            assertFalse(owner.shouldDrawLod(next, List.of(replacement)));
            assertTrue(owner.shouldDrawLod(retained, List.of()));
            assertEquals(2, gate.view().metrics().loadedLeases());
        }
    }

    @Test void jobsCannotTransferBetweenOwnersWithIdenticalGateOrDifferentNamespace() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        commit(gate, snapshot);
        var foreignNamespace = new DhPageOwnership.Namespace(WORLD, new UUID(0, 1), "earth:other", "equirectangular", "v1", "EGM2008");
        try (var first = new DhPageOwnership(gate, namespace());
             var identical = new DhPageOwnership(gate, namespace());
             var foreign = new DhPageOwnership(gate, foreignNamespace)) {
            var job = publish(first, snapshot);
            for (var other : List.of(identical, foreign)) {
                assertFalse(other.isCurrent(job));
                assertFalse(other.shouldDrawLod(job, List.of()));
                assertFalse(other.publish(job, () -> fail("Foreign job installed")));
                assertEquals(1, other.rejected());
                assertTrue(other.shouldDrawLod(publish(other, snapshot), List.of()));
            }
            assertTrue(first.shouldDrawLod(job, List.of()));
        }
    }

    @Test void resourceGenerationRejectsOldJobsAndRequiresFreshPublication() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        commit(gate, snapshot);
        try (var owner = new DhPageOwnership(gate, namespace())) {
            var old = publish(owner, snapshot);
            long resources = owner.resourceGeneration();
            owner.invalidateResources();
            assertEquals(resources + 1, owner.resourceGeneration());
            assertFalse(owner.isCurrent(old));
            assertFalse(owner.shouldDrawLod(old, List.of()));
            assertFalse(owner.publish(old, () -> fail("Old resource installed")));
            var replacement = owner.prepare(snapshot);
            assertFalse(owner.shouldDrawLod(replacement, List.of()));
            assertTrue(owner.publish(replacement, () -> {}));
            assertTrue(owner.shouldDrawLod(replacement, List.of()));
            assertEquals(2, owner.published());
            assertEquals(1, owner.rejected());
        }
    }

    @Test void offThreadReloadFencesPublicationBeforeOwnerCleanup() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        commit(gate, snapshot);
        try (var owner = new DhPageOwnership(gate, namespace())) {
            var job = publish(owner, snapshot);
            CompletableFuture.runAsync(owner::requestInvalidation).join();
            assertFalse(owner.isCurrent(job));
            assertFalse(owner.shouldDrawLod(job, List.of()));
            assertFalse(owner.publish(job, () -> fail("Reload-fenced job installed")));
            assertThrows(IllegalStateException.class, () -> owner.prepare(snapshot));
            assertEquals(1, owner.resourceGeneration());
        }
    }

    @Test void allOperationsExceptReloadRequestRequireOwnerThread() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        commit(gate, snapshot);
        try (var owner = new DhPageOwnership(gate, namespace())) {
            var job = publish(owner, snapshot);
            var installs = new AtomicInteger();
            List<Executable> operations = List.of(owner::namespace, () -> owner.prepare(snapshot),
                    () -> owner.publish(job, installs::incrementAndGet), () -> owner.isCurrent(job),
                    () -> owner.shouldDrawLod(job, List.of()), owner::invalidateResources, owner::close,
                    owner::resourceGeneration, owner::rejected, owner::published);
            CompletableFuture.runAsync(() -> {
                for (var operation : operations) {
                    var failure = assertThrows(IllegalStateException.class, operation);
                    assertTrue(failure.getMessage().contains("owner thread"));
                }
            }).join();
            assertEquals(0, installs.get());
            assertTrue(owner.shouldDrawLod(job, List.of()));
            assertEquals(1, owner.resourceGeneration());
        }
    }

    @Test void budgetsAndReadyCollectionsAreBoundedAndNullChecked() {
        for (int budget : new int[]{1, 2}) {
            try (var owner = new DhPageOwnership(new CubeStreamAdmission(WORLD, EPOCH, budget), namespace())) {
                assertEquals(1, owner.resourceGeneration());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new DhPageOwnership(new CubeStreamAdmission(WORLD, EPOCH, 3), namespace()));
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        commit(gate, snapshot);
        try (var owner = new DhPageOwnership(gate, namespace())) {
            var job = publish(owner, snapshot);
            assertThrows(IllegalArgumentException.class, () -> owner.shouldDrawLod(job, List.of(snapshot, snapshot, snapshot)));
            var withNull = new ArrayList<EngineCubeSnapshot>(); withNull.add(null);
            assertThrows(NullPointerException.class, () -> owner.shouldDrawLod(job, withNull));
            assertThrows(NullPointerException.class, () -> owner.shouldDrawLod(job, null));
            assertThrows(NullPointerException.class, () -> owner.shouldDrawLod(null, List.of()));
            assertThrows(IllegalArgumentException.class, () -> gate.replaceInterest(List.of(LOWER, UPPER, new CubePos(1, 0, 0))));
            assertTrue(owner.shouldDrawLod(job, List.of()));
        }
    }

    @Test void loadedAirIsKnownButRequestedOrMissingCubesNeverBecomeAir() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var leases = gate.replaceInterest(List.of(LOWER, UPPER)).requested();
        var air = snapshot(LOWER, WORLD, EPOCH, leases.get(0).generation(), 3, Blocks.AIR.defaultBlockState());
        var requested = snapshot(gate, leases.get(1), 3);
        commit(gate, air);
        try (var owner = new DhPageOwnership(gate, namespace())) {
            var knownAir = publish(owner, air);
            assertTrue(owner.shouldDrawLod(knownAir, List.of()));
            assertFalse(owner.shouldDrawLod(knownAir, List.of(air)), "Successfully published empty native mesh covers known air");
            assertThrows(IllegalStateException.class, () -> owner.prepare(requested));
            assertTrue(owner.shouldDrawLod(knownAir, List.of(requested)));
            var missing = snapshot(new CubePos(0, 0, 0), WORLD, EPOCH, 99, 3, Blocks.AIR.defaultBlockState());
            assertThrows(IllegalStateException.class, () -> owner.prepare(missing));
        }
    }

    @Test void closureAndInstallerExceptionsOrErrorsCannotLeakDrawablePages() {
        for (int mode : new int[]{0, 1, 2, 3}) {
            var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
            var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
            var snapshot = snapshot(gate, lease, 3);
            commit(gate, snapshot);
            try (var owner = new DhPageOwnership(gate, namespace())) {
                var job = publish(owner, snapshot);
                if (mode == 0) owner.close();
                else if (mode == 1) gate.close();
                else if (mode == 2) assertThrows(IllegalStateException.class,
                        () -> owner.publish(owner.prepare(snapshot), () -> { throw new IllegalStateException("upload failed"); }));
                else assertThrows(AssertionError.class,
                        () -> owner.publish(owner.prepare(snapshot), () -> { throw new AssertionError("upload failed"); }));
                assertFalse(owner.isCurrent(job));
                assertFalse(owner.shouldDrawLod(job, List.of()));
                assertFalse(owner.publish(job, () -> fail("Closed or failed owner installed")));
                assertThrows(IllegalStateException.class, () -> owner.prepare(snapshot));
                assertEquals(1, owner.published());
                assertDoesNotThrow(owner::close);
            }
        }
    }

    @Test void failedAdmissionInvalidatesEvenItsStillLoadedOldRevision() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        commit(gate, snapshot);
        try (var owner = new DhPageOwnership(gate, namespace())) {
            var job = publish(owner, snapshot);
            var next = gate.admit(new CubeStreamAdmission.Header(WORLD, EPOCH, LOWER, lease.generation(), 4));
            assertThrows(IllegalStateException.class, () -> gate.commit(next.token(), () -> {
                throw new IllegalStateException("native owner installation failed");
            }));
            assertEquals(CubeStreamAdmission.State.FAILED, gate.view().state());
            assertEquals(3, gate.view().leases().getFirst().appliedRevision());
            assertFalse(owner.isCurrent(job));
            assertFalse(owner.shouldDrawLod(job, List.of()));
            assertFalse(owner.publish(job, () -> fail("Failed admission installed a DH candidate")));
            assertThrows(IllegalStateException.class, () -> owner.prepare(snapshot));
        }
    }

    @Test void reentrantLeaseRetirementCannotPublish() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        commit(gate, snapshot);
        try (var owner = new DhPageOwnership(gate, namespace())) {
            var job = owner.prepare(snapshot);
            assertThrows(IllegalStateException.class, () -> owner.publish(job, () -> gate.replaceInterest(List.of())));
            assertFalse(owner.isCurrent(job));
            assertFalse(owner.shouldDrawLod(job, List.of()));
            assertEquals(0, owner.published());
        }
    }
}
