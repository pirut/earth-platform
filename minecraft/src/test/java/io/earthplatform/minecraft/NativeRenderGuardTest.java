package io.earthplatform.minecraft;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.renderprobe.CameraRelativeOrigin;
import io.earthplatform.minecraft.renderprobe.CubeRenderNeighborhood;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.minecraft.residency.SparseFacadeException;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.*;

/** Headless publication/neighborhood checks; these do not claim GPU execution or working world lighting. */
class NativeRenderGuardTest {
    private static final WorldIdentity WORLD = new WorldIdentity(42, "earth", "surface-fill-v1", "noaa-etopo-2022", "materials-v1");
    private static final long EPOCH = 7;
    private static final CubePos LOWER = new CubePos(0, -688, 0);
    private static final CubePos UPPER = new CubePos(0, 553, 0);
    private static EnginePaletteContext palette;

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS,
                VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),
                RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
    }

    private static EngineCubeSnapshot snapshot(CubePos position, WorldIdentity world, long epoch,
                                               long generation, long revision, BlockState marker) {
        var section = new EngineCubeSection(palette);
        section.set(8, 8, 8, marker);
        return EngineCubeSnapshot.capture(position, world, epoch, generation, revision, section);
    }

    private static EngineCubeSnapshot snapshot(CubeStreamAdmission gate, CubeStreamAdmission.Lease lease, long revision) {
        return snapshot(lease.position(), gate.world(), gate.connectionEpoch(), lease.generation(), revision,
                Blocks.GOLD_BLOCK.defaultBlockState());
    }

    private static void commit(CubeStreamAdmission gate, EngineCubeSnapshot snapshot) {
        var admission = gate.admit(new CubeStreamAdmission.Header(snapshot.world(), snapshot.epoch(), snapshot.position(),
                snapshot.leaseGeneration(), snapshot.revision()));
        assertTrue(admission.accepted());
        // Only admission metadata is under test; no mutable native owner or GPU resource is created here.
        assertEquals(CubeStreamAdmission.Rejection.NONE, gate.commit(admission.token(), () -> {}));
    }

    private static BlockPos block(CubePos cube, int x, int y, int z) {
        return new BlockPos(Math.toIntExact(cube.minBlockX()) + x, Math.toIntExact(cube.minBlockY()) + y,
                Math.toIntExact(cube.minBlockZ()) + z);
    }

    @Test void publicationRequiresCommittedLoadedRevisionAndSharesRetirementLock() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        try (var guard = new RenderPublicationGuard(gate)) {
            assertThrows(IllegalStateException.class, () -> guard.prepare(snapshot));
            var pending = gate.admit(new CubeStreamAdmission.Header(WORLD, EPOCH, LOWER, lease.generation(), 3));
            assertTrue(pending.accepted());
            assertThrows(IllegalStateException.class, () -> guard.prepare(snapshot));
            assertEquals(CubeStreamAdmission.Rejection.NONE, gate.commit(pending.token(), () -> {}));

            var job = guard.prepare(snapshot);
            assertSame(snapshot, job.snapshot());
            assertEquals(LOWER, job.position());
            assertTrue(guard.isCurrent(job));
            var installs = new AtomicInteger();
            assertTrue(guard.publish(job, () -> {
                assertTrue(Thread.holdsLock(gate), "Installation must exclude concurrent lease retirement");
                installs.incrementAndGet();
            }));
            assertEquals(1, installs.get());
            assertEquals(1, guard.published());
            assertEquals(0, guard.rejected());
        }
    }

    @Test void preparationRejectsWrongWorldEpochPositionLeaseAndRevision() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var current = snapshot(gate, lease, 3);
        commit(gate, current);
        var otherWorld = new WorldIdentity(43, "earth", "surface-fill-v1", "noaa-etopo-2022", "materials-v1");
        var gold = Blocks.GOLD_BLOCK.defaultBlockState();
        var invalid = List.of(
                snapshot(LOWER, otherWorld, EPOCH, lease.generation(), 3, gold),
                snapshot(LOWER, WORLD, EPOCH + 1, lease.generation(), 3, gold),
                snapshot(UPPER, WORLD, EPOCH, lease.generation(), 3, gold),
                snapshot(LOWER, WORLD, EPOCH, lease.generation() + 1, 3, gold),
                snapshot(LOWER, WORLD, EPOCH, lease.generation(), 2, gold),
                snapshot(LOWER, WORLD, EPOCH, lease.generation(), 4, gold));
        try (var guard = new RenderPublicationGuard(gate)) {
            for (var candidate : invalid) assertThrows(IllegalStateException.class, () -> guard.prepare(candidate));
            assertTrue(guard.isCurrent(guard.prepare(current)));
            assertEquals(0, guard.published());
        }
    }

    @Test void identicalMetadataCannotTransferAJobBetweenGuardIdentities() {
        var firstGate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var firstLease = firstGate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var firstSnapshot = snapshot(firstGate, firstLease, 3);
        commit(firstGate, firstSnapshot);
        var secondGate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var secondLease = secondGate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var secondSnapshot = snapshot(secondGate, secondLease, 3);
        commit(secondGate, secondSnapshot);
        assertEquals(firstLease, secondLease);
        try (var first = new RenderPublicationGuard(firstGate);
             var sameGate = new RenderPublicationGuard(firstGate);
             var second = new RenderPublicationGuard(secondGate)) {
            var job = first.prepare(firstSnapshot);
            for (var other : List.of(sameGate, second)) {
                assertFalse(other.isCurrent(job));
                assertFalse(other.publish(job, () -> fail("Foreign guard job was installed")));
                assertEquals(1, other.rejected());
                assertTrue(other.isCurrent(other.prepare(secondSnapshot)));
            }
            assertTrue(first.isCurrent(job));
        }
    }

    @Test void committingANewerRevisionInvalidatesAnAlreadyPreparedJob() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var original = snapshot(gate, lease, 3);
        commit(gate, original);
        try (var guard = new RenderPublicationGuard(gate)) {
            var old = guard.prepare(original);
            var next = snapshot(gate, lease, 4);
            commit(gate, next);
            assertFalse(guard.isCurrent(old));
            assertFalse(guard.publish(old, () -> fail("Superseded revision was installed")));
            assertThrows(IllegalStateException.class, () -> guard.prepare(original));
            assertTrue(guard.publish(guard.prepare(next), () -> {}));
            assertEquals(1, guard.rejected());
            assertEquals(1, guard.published());
        }
    }

    @Test void retiredJobStaysStaleAfterReaddingTheSamePositionAndRevision() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var leases = gate.replaceInterest(List.of(LOWER, UPPER)).requested();
        var lower = snapshot(gate, leases.get(0), 3);
        var upper = snapshot(gate, leases.get(1), 3);
        commit(gate, lower);
        commit(gate, upper);
        try (var guard = new RenderPublicationGuard(gate)) {
            var delayed = guard.prepare(lower);
            var retained = guard.prepare(upper);
            var retirement = gate.replaceInterest(List.of(UPPER));
            assertEquals(List.of(leases.get(0)), retirement.retired().stream().map(CubeStreamAdmission.LeaseView::lease).toList());
            assertFalse(guard.publish(delayed, () -> fail("Retired job was installed")));
            assertTrue(guard.isCurrent(retained));

            var renewed = gate.replaceInterest(List.of(LOWER, UPPER)).requested().getFirst();
            assertTrue(renewed.generation() > lower.leaseGeneration());
            var replacement = snapshot(gate, renewed, lower.revision());
            assertThrows(IllegalStateException.class, () -> guard.prepare(replacement));
            commit(gate, replacement);
            assertFalse(guard.publish(delayed, () -> fail("Retire/re-add ABA job was installed")));
            assertThrows(IllegalStateException.class, () -> guard.prepare(lower));
            assertTrue(guard.publish(guard.prepare(replacement), () -> {}));
            assertTrue(guard.isCurrent(retained));
            assertEquals(2, gate.view().metrics().loadedLeases());
            assertEquals(2, guard.rejected());
        }
    }

    @Test void resourceGenerationRejectsOldJobsAndAllowsFreshPreparation() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        commit(gate, snapshot);
        try (var guard = new RenderPublicationGuard(gate)) {
            var old = guard.prepare(snapshot);
            long resources = guard.resourceGeneration();
            guard.invalidateResources();
            assertEquals(resources + 1, guard.resourceGeneration());
            assertFalse(guard.isCurrent(old));
            assertFalse(guard.publish(old, () -> fail("Old resource-generation job was installed")));
            assertTrue(guard.publish(guard.prepare(snapshot), () -> {}));
            assertEquals(1, guard.published());
            assertEquals(1, guard.rejected());
        }
    }

    @Test void offThreadReloadRequestBlocksPublicationBeforeOwnerThreadCleanup() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        commit(gate, snapshot);
        try (var guard = new RenderPublicationGuard(gate)) {
            var old = guard.prepare(snapshot);
            CompletableFuture.runAsync(guard::requestInvalidation).join();
            assertFalse(guard.isCurrent(old));
            assertFalse(guard.publish(old, () -> fail("Job was installed during resource reload")));
            assertThrows(IllegalStateException.class, () -> guard.prepare(snapshot));
            assertEquals(1, guard.resourceGeneration(), "The off-thread request must not rebuild GPU resources");
        }
    }

    @Test void preparePublishResourceMutationCloseAndStatusRequireTheOwnerThread() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        commit(gate, snapshot);
        try (var guard = new RenderPublicationGuard(gate)) {
            var job = guard.prepare(snapshot);
            var installs = new AtomicInteger();
            List<Executable> operations = List.of(
                    () -> guard.prepare(snapshot),
                    () -> guard.publish(job, installs::incrementAndGet),
                    () -> guard.isCurrent(job),
                    guard::invalidateResources,
                    guard::close,
                    () -> guard.published(),
                    () -> guard.rejected(),
                    () -> guard.resourceGeneration());
            CompletableFuture.runAsync(() -> {
                for (var operation : operations) {
                    var failure = assertThrows(IllegalStateException.class, operation);
                    assertTrue(failure.getMessage().contains("owner thread"));
                }
            }).join();
            assertEquals(0, installs.get());
            assertEquals(1, guard.resourceGeneration());
            assertEquals(0, guard.published());
            assertEquals(0, guard.rejected());
            assertTrue(guard.publish(job, installs::incrementAndGet));
            assertEquals(1, installs.get());
        }
    }

    @Test void closingAdmissionOrGuardInvalidatesPreparedJobs() {
        for (boolean closeAdmission : List.of(false, true)) {
            var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
            var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
            var snapshot = snapshot(gate, lease, 3);
            commit(gate, snapshot);
            try (var guard = new RenderPublicationGuard(gate)) {
                var job = guard.prepare(snapshot);
                if (closeAdmission) gate.close(); else guard.close();
                assertFalse(guard.isCurrent(job));
                assertFalse(guard.publish(job, () -> fail("Closed owner was installed")));
                assertThrows(IllegalStateException.class, () -> guard.prepare(snapshot));
                if (!closeAdmission) assertThrows(IllegalStateException.class, guard::invalidateResources);
                assertDoesNotThrow(guard::close);
            }
        }
    }

    @Test void failedGpuInstallationPoisonsTheGuardForRuntimeExceptionsAndErrors() {
        for (boolean error : List.of(false, true)) {
            var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
            var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
            var snapshot = snapshot(gate, lease, 3);
            commit(gate, snapshot);
            try (var guard = new RenderPublicationGuard(gate)) {
                var job = guard.prepare(snapshot);
                var failure = error ? new AssertionError("upload failed") : new IllegalStateException("upload failed");
                var thrown = assertThrows(Throwable.class, () -> guard.publish(job, () -> {
                    if (failure instanceof Error fatal) throw fatal;
                    throw (RuntimeException) failure;
                }));
                assertSame(failure, thrown);
                assertFalse(guard.isCurrent(job));
                assertFalse(guard.publish(job, () -> fail("Failed guard accepted another install")));
                assertThrows(IllegalStateException.class, () -> guard.prepare(snapshot));
                assertThrows(IllegalStateException.class, guard::invalidateResources);
                assertEquals(0, guard.published());
                assertEquals(1, guard.rejected());
            }
        }
    }

    @Test void failedAdmissionInvalidatesJobsEvenWhenTheOldRevisionRemainsLoaded() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        commit(gate, snapshot);
        try (var guard = new RenderPublicationGuard(gate)) {
            var job = guard.prepare(snapshot);
            var next = gate.admit(new CubeStreamAdmission.Header(WORLD, EPOCH, LOWER, lease.generation(), 4));
            assertTrue(next.accepted());
            assertThrows(IllegalStateException.class, () -> gate.commit(next.token(), () -> { throw new IllegalStateException("owner install failed"); }));
            assertEquals(CubeStreamAdmission.State.FAILED, gate.view().state());
            assertEquals(3, gate.view().leases().getFirst().appliedRevision());
            assertFalse(guard.isCurrent(job));
            assertFalse(guard.publish(job, () -> fail("Failed admission allowed a GPU install")));
            assertThrows(IllegalStateException.class, () -> guard.prepare(snapshot));
        }
    }

    @Test void installerCannotPublishAfterReentrantlyRetiringItsOwnLease() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        commit(gate, snapshot);
        try (var guard = new RenderPublicationGuard(gate)) {
            var job = guard.prepare(snapshot);
            assertThrows(IllegalStateException.class, () -> guard.publish(job, () -> gate.replaceInterest(List.of())));
            assertFalse(guard.isCurrent(job));
            assertEquals(0, guard.published());
            var renewed = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
            var replacement = snapshot(gate, renewed, 3);
            commit(gate, replacement);
            assertThrows(IllegalStateException.class, () -> guard.prepare(replacement), "Reentrant invalidation must poison the guard");
            assertFalse(guard.publish(job, () -> fail("Poisoned guard installed another job")));
        }
    }

    @Test void rendererRejectsBudgetsAboveTwoAndTwoOwnerAdmissionCannotGrow() {
        for (int bound : new int[]{1, 2}) {
            try (var guard = new RenderPublicationGuard(new CubeStreamAdmission(WORLD, EPOCH, bound))) {
                assertEquals(1, guard.resourceGeneration());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new RenderPublicationGuard(new CubeStreamAdmission(WORLD, EPOCH, 3)));
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var leases = gate.replaceInterest(List.of(LOWER, UPPER)).requested();
        var lower = snapshot(gate, leases.get(0), 3);
        var upper = snapshot(gate, leases.get(1), 3);
        commit(gate, lower);
        commit(gate, upper);
        try (var guard = new RenderPublicationGuard(gate)) {
            var lowerJob = guard.prepare(lower);
            var upperJob = guard.prepare(upper);
            var before = gate.view();
            assertThrows(IllegalArgumentException.class, () -> gate.replaceInterest(List.of(LOWER, UPPER, new CubePos(1, 0, 0))));
            assertEquals(before, gate.view());
            assertTrue(guard.isCurrent(lowerJob));
            assertTrue(guard.isCurrent(upperJob));
        }
    }

    @Test void neighborhoodPreservesNativeYAtEarthXZExtentsWithoutPackedBlockAliases() {
        for (int x : new int[]{-20_037_508, 20_037_508}) {
            for (int z : new int[]{-10_018_754, 10_018_754}) {
                var lowCube = CubePos.fromBlock(x, -11000, z);
                var highCube = CubePos.fromBlock(x, 8849, z);
                var lowSection = new EngineCubeSection(palette);
                lowSection.set(CubePos.local(x), CubePos.local(-11000), CubePos.local(z), Blocks.GOLD_BLOCK.defaultBlockState());
                var highSection = new EngineCubeSection(palette);
                highSection.set(CubePos.local(x), CubePos.local(8849), CubePos.local(z), Blocks.DIAMOND_BLOCK.defaultBlockState());
                var low = EngineCubeSnapshot.capture(lowCube, WORLD, EPOCH, 1, 3, lowSection);
                var high = EngineCubeSnapshot.capture(highCube, WORLD, EPOCH, 2, 3, highSection);
                var neighborhood = new CubeRenderNeighborhood(List.of(low, high), palette);
                assertEquals(-11008, neighborhood.getMinBuildHeight());
                assertEquals(19872, neighborhood.getHeight());
                assertEquals(8864, neighborhood.getMaxBuildHeight());
                assertSame(Blocks.GOLD_BLOCK.defaultBlockState(), neighborhood.getBlockState(new BlockPos(x, -11000, z)));
                assertSame(Blocks.DIAMOND_BLOCK.defaultBlockState(), neighborhood.getBlockState(new BlockPos(x, 8849, z)));
                for (var cube : List.of(lowCube, highCube)) {
                    assertSame(Blocks.AIR.defaultBlockState(), neighborhood.getBlockState(block(cube, 0, 0, 0)));
                    assertSame(Blocks.AIR.defaultBlockState(), neighborhood.getBlockState(block(cube, 15, 15, 15)));
                    var center = block(cube, 8, 8, 8);
                    for (int shift : new int[]{-4096, 4096}) {
                        var alias = new BlockPos(center.getX(), center.getY() + shift, center.getZ());
                        assertEquals(center.asLong(), alias.asLong(), "Fixture must expose vanilla packed-Y aliasing");
                        assertThrows(SparseFacadeException.class, () -> neighborhood.getBlockState(alias));
                    }
                    for (var outside : List.of(block(cube, -1, 8, 8), block(cube, 16, 8, 8),
                            block(cube, 8, -1, 8), block(cube, 8, 16, 8),
                            block(cube, 8, 8, -1), block(cube, 8, 8, 16))) {
                        assertUnknown(neighborhood, outside);
                    }
                }
                assertUnknown(neighborhood, new BlockPos(x, -11009, z));
                assertUnknown(neighborhood, new BlockPos(x, 8864, z));
            }
        }
    }

    private static void assertUnknown(CubeRenderNeighborhood neighborhood, BlockPos position) {
        assertAll(
                () -> assertThrows(SparseFacadeException.class, () -> neighborhood.getBlockState(position)),
                () -> assertThrows(SparseFacadeException.class, () -> neighborhood.getFluidState(position)),
                () -> assertThrows(SparseFacadeException.class, () -> neighborhood.getBlockEntity(position)),
                () -> assertThrows(SparseFacadeException.class, () -> neighborhood.getBrightness(LightLayer.BLOCK, position)),
                () -> assertThrows(SparseFacadeException.class, () -> neighborhood.getBrightness(LightLayer.SKY, position)),
                () -> assertThrows(SparseFacadeException.class, () -> neighborhood.getRawBrightness(position, 0)),
                () -> assertThrows(SparseFacadeException.class, () -> neighborhood.getBlockTint(position, (biome, x, z) -> 0)));
    }

    @Test void neighborhoodOnlyExposesKnownFixtureStatesAndFixedIllumination() {
        var snapshot = snapshot(LOWER, WORLD, EPOCH, 1, 3, Blocks.GOLD_BLOCK.defaultBlockState());
        var neighborhood = new CubeRenderNeighborhood(List.of(snapshot), palette);
        var known = block(LOWER, 8, 8, 8);
        assertTrue(neighborhood.getFluidState(known).isEmpty());
        assertNull(neighborhood.getBlockEntity(known));
        assertEquals(15, neighborhood.getBrightness(LightLayer.BLOCK, known));
        assertEquals(15, neighborhood.getBrightness(LightLayer.SKY, known));
        assertEquals(12, neighborhood.getRawBrightness(known, 3));
        assertEquals(0, neighborhood.getRawBrightness(known, 16));
        assertEquals(0xFFFFFF, neighborhood.getBlockTint(known, (biome, x, z) -> 0));
        assertEquals(1.0f, neighborhood.getShade(Direction.DOWN, false));
        assertEquals(0.5f, neighborhood.getShade(Direction.DOWN, true));
        assertEquals(1.0f, neighborhood.getShade(Direction.UP, true));
        assertEquals(0.8f, neighborhood.getShade(Direction.NORTH, true));
        assertEquals(0.8f, neighborhood.getShade(Direction.SOUTH, true));
        assertEquals(0.6f, neighborhood.getShade(Direction.EAST, true));
        assertEquals(0.6f, neighborhood.getShade(Direction.WEST, true));
        assertThrows(SparseFacadeException.class, neighborhood::getLightEngine);
    }

    @Test void neighborhoodRejectsEmptyDuplicateOversizedAndUnsupportedStateInputs() {
        var lower = snapshot(LOWER, WORLD, EPOCH, 1, 3, Blocks.GOLD_BLOCK.defaultBlockState());
        var upper = snapshot(UPPER, WORLD, EPOCH, 2, 3, Blocks.DIAMOND_BLOCK.defaultBlockState());
        var third = snapshot(new CubePos(1, 0, 0), WORLD, EPOCH, 3, 3, Blocks.AIR.defaultBlockState());
        assertThrows(IllegalArgumentException.class, () -> new CubeRenderNeighborhood(List.of(), palette));
        assertThrows(IllegalArgumentException.class, () -> new CubeRenderNeighborhood(List.of(lower, lower), palette));
        assertThrows(IllegalArgumentException.class, () -> new CubeRenderNeighborhood(List.of(lower, upper, third), palette));
        assertDoesNotThrow(() -> new CubeRenderNeighborhood(List.of(lower), palette));
        assertDoesNotThrow(() -> new CubeRenderNeighborhood(List.of(lower, upper), palette));
        for (var state : List.of(Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState(), Blocks.CAVE_AIR.defaultBlockState())) {
            var unsupported = snapshot(LOWER, WORLD, EPOCH, 1, 3, state);
            assertThrows(SparseFacadeException.class, () -> new CubeRenderNeighborhood(List.of(unsupported), palette));
        }
    }

    @Test void cameraRelativeOriginKeepsHalfBlockPrecisionAtAllEarthXZCornersAndNativeYExtrema() {
        for (int x : new int[]{-20_037_508, 20_037_508}) {
            for (int z : new int[]{-10_018_754, 10_018_754}) {
                for (int y : new int[]{-11008, -11000, 8849, 8863}) {
                    var cube = CubePos.fromBlock(x, y, z);
                    double cameraX = x + 0.5, cameraY = y + 0.5, cameraZ = z + 0.5;
                    var origin = CameraRelativeOrigin.of(cube, cameraX, cameraY, cameraZ);
                    assertEquals(-(CubePos.local(x) + 0.5f), origin.x());
                    assertEquals(-(CubePos.local(y) + 0.5f), origin.y());
                    assertEquals(-(CubePos.local(z) + 0.5f), origin.z());
                    assertEquals(-0.5f, origin.x() + CubePos.local(x));
                    assertEquals(-0.5f, origin.y() + CubePos.local(y));
                    assertEquals(-0.5f, origin.z() + CubePos.local(z));
                    assertNotEquals(origin.x(), (float) cube.minBlockX() - (float) cameraX,
                            "Converting absolute Earth X to float first loses the half-block offset");
                    assertNotEquals(origin.z(), (float) cube.minBlockZ() - (float) cameraZ,
                            "Converting absolute Earth Z to float first loses the half-block offset");
                }
            }
        }
    }

    @Test void cameraRelativeOriginAcceptsExactNearBoundAndRejectsFarCameraOnEveryAxis() {
        var cube = CubePos.fromBlock(20_037_508, -11000, -10_018_754);
        double x = cube.minBlockX(), y = cube.minBlockY(), z = cube.minBlockZ();
        for (double offset : new double[]{-4096.0, -15.5, -0.5, 0, 0.5, 15.5, 4096.0}) {
            var origin = CameraRelativeOrigin.of(cube, x + offset, y + offset, z + offset);
            float expected = (float) -offset;
            // Equal origins yield +0.0, independently of the sign used to express the camera offset.
            if (offset == 0) expected = 0;
            assertEquals(new CameraRelativeOrigin(expected, expected, expected), origin);
        }
        for (double offset : new double[]{-4096.5, 4096.5, -Double.MAX_VALUE, Double.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> CameraRelativeOrigin.of(cube, x + offset, y, z));
            assertThrows(IllegalArgumentException.class, () -> CameraRelativeOrigin.of(cube, x, y + offset, z));
            assertThrows(IllegalArgumentException.class, () -> CameraRelativeOrigin.of(cube, x, y, z + offset));
        }
    }

    @Test void cameraRelativeOriginRejectsNonFiniteCoordinatesOnEveryAxis() {
        var cube = CubePos.fromBlock(-20_037_508, 8849, 10_018_754);
        double x = cube.minBlockX(), y = cube.minBlockY(), z = cube.minBlockZ();
        for (double invalid : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> CameraRelativeOrigin.of(cube, invalid, y, z));
            assertThrows(IllegalArgumentException.class, () -> CameraRelativeOrigin.of(cube, x, invalid, z));
            assertThrows(IllegalArgumentException.class, () -> CameraRelativeOrigin.of(cube, x, y, invalid));
        }
    }
}
