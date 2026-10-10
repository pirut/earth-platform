package io.earthplatform.minecraft;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.minecraft.renderprobe.RenderPublicationGuard;
import io.earthplatform.minecraft.scene.SceneInterestPlan;
import io.earthplatform.minecraft.scene.SceneReadyCube;
import io.earthplatform.minecraft.scene.SceneSurfaceSampler;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.AbstractCollection;
import java.util.AbstractList;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongBinaryOperator;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Sparse planning and authoritative readiness checks; these do not claim GPU execution. */
class SceneInterestPlanTest {
    private static final WorldIdentity WORLD = new WorldIdentity(42, "scene", "1", "pinned-height", "native-palette");
    private static final long EPOCH = 7;
    private static final CubePos POSITION = new CubePos(0, 0, 0);
    private static final int COLUMN_SAMPLES = 16 * 16;
    private static final int PLAN_SAMPLES = 9 * COLUMN_SAMPLES;
    private static EnginePaletteContext palette;

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS,
                VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),
                RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
    }

    private static SceneSurfaceSampler source(LongBinaryOperator heights) {
        return new SceneSurfaceSampler() {
            @Override public long surfaceY(long x, long z) { return heights.applyAsLong(x, z); }
            @Override public int argb(long x, long z, long y) {
                throw new AssertionError("Interest planning must not need appearance or invent terrain");
            }
            @Override public String identity() { return "fixed-test-source"; }
        };
    }

    private static SceneSurfaceSampler flat(long height) { return source((x, z) -> height); }

    private static Set<CubePos> targets(int centerX, int y, int centerZ) {
        Set<CubePos> result = new HashSet<>();
        for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
            result.add(new CubePos(centerX + x, y, centerZ + z));
        }
        return result;
    }

    private static Set<CubePos> knownHalo(Set<CubePos> targets) {
        Set<CubePos> result = new HashSet<>();
        for (CubePos target : targets) {
            for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
                CubePos neighbor = target.offset(x, y, z);
                if (neighbor.y() >= -688 && neighbor.y() <= 553) result.add(neighbor);
            }
        }
        return result;
    }

    private static void assertExactHalo(SceneInterestPlan plan, Set<CubePos> expectedTargets) {
        assertEquals(expectedTargets, plan.renderTargets());
        assertEquals(knownHalo(expectedTargets), new HashSet<>(plan.owners()));
        assertEquals(new HashSet<>(plan.owners()).size(), plan.owners().size(), "Each native owner is unique");
        assertTrue(plan.owners().size() <= SceneInterestPlan.MAX_OWNERS);
    }

    @Test void flatSurfaceOwnsNineTargetsAndExplicitTwentySixNeighborHalos() {
        Set<String> sampled = new HashSet<>();
        var plan = SceneInterestPlan.around(source((x, z) -> {
            assertTrue(sampled.add(x + "," + z), "Each target column sample is read once");
            return 160;
        }), 8, 168, 8);
        assertEquals(0, plan.centerX());
        assertEquals(0, plan.centerZ());
        assertExactHalo(plan, targets(0, 10, 0));
        assertEquals(9, plan.renderTargets().size());
        assertEquals(75, plan.owners().size(), "Shared 3D halos occupy 5 x 3 x 5 cubes, not full-height columns");
        assertEquals(PLAN_SAMPLES, sampled.size());
        assertTrue(plan.owners().contains(new CubePos(-2, 9, -2)), "Diagonal halo owner is explicit");
        assertTrue(plan.owners().contains(new CubePos(2, 11, 2)), "Above-surface halo owner is explicit");
        assertFalse(plan.renderTargets().contains(new CubePos(2, 11, 2)), "Halo readiness is distinct from render targets");
    }

    @Test void steppedSurfaceTracksIndependentNativeYOwnersAcrossHorizontalColumns() {
        var plan = SceneInterestPlan.around(source((x, z) -> CubePos.section(x) * 16L), 8, 8, 8);
        Set<CubePos> expected = new HashSet<>();
        for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) expected.add(new CubePos(x, x, z));
        assertExactHalo(plan, expected);
        assertEquals(Set.of(-1, 0, 1), plan.renderTargets().stream().map(CubePos::y).collect(java.util.stream.Collectors.toSet()));
        assertFalse(plan.owners().contains(new CubePos(0, 100, 0)));
    }

    @Test void withinColumnSurfaceRangeIncludesEveryIntersectingNativeCube() {
        var plan = SceneInterestPlan.around(source((x, z) -> CubePos.local(x) < 8 ? 15 : 16), 8, 16, 8);
        Set<CubePos> expected = targets(0, 0, 0);
        expected.addAll(targets(0, 1, 0));
        assertExactHalo(plan, expected);
        assertEquals(18, plan.renderTargets().size());
        assertEquals(100, plan.owners().size());
    }

    @Test void nativeEarthExtremaHaveClippedExplicitHalosWithoutWrappedYAliases() {
        for (long height : new long[]{-11000, 8849}) {
            int y = CubePos.section(height);
            var plan = SceneInterestPlan.around(flat(height), 8, height, 8);
            assertExactHalo(plan, targets(0, y, 0));
            assertEquals(50, plan.owners().size(), "Envelope edge clips exactly one halo layer");
            assertTrue(plan.owners().stream().allMatch(p -> p.minBlockY() >= -11008 && p.minBlockY() + 16 <= 8864));
            assertFalse(plan.owners().contains(new CubePos(0, y + 256, 0)), "A 4096-block packed BlockPos alias is not an owner");
            assertFalse(plan.owners().contains(new CubePos(0, y - 256, 0)));
        }
    }

    @Test void earthScaleXZExtentsAndNegativeFractionsPreserveSignedCubeCoordinates() {
        for (double x : new double[]{-20_037_508, 20_037_508, -0.01}) {
            for (double z : new double[]{-10_018_754, 10_018_754, -0.01}) {
                int cx = CubePos.section((long) Math.floor(x));
                int cz = CubePos.section((long) Math.floor(z));
                var plan = SceneInterestPlan.around(flat(-1), x, -1, z);
                assertEquals(cx, plan.centerX());
                assertEquals(cz, plan.centerZ());
                assertExactHalo(plan, targets(cx, -1, cz));
                assertEquals(75, plan.owners().size());
            }
        }
    }

    @Test void nearDistanceIncludesVerticalSeparationAndHasAnInclusiveBoundary() {
        assertEquals(128.0, SceneInterestPlan.MAX_NEAR_DISTANCE);
        for (double cameraY : new double[]{136, -120}) {
            var atBoundary = SceneInterestPlan.around(flat(0), 8, cameraY, 8);
            assertExactHalo(atBoundary, Set.of(POSITION));
            assertEquals(27, atBoundary.owners().size());
        }
        for (double cameraY : new double[]{136.001, -120.001, 8849, -11000}) {
            var tooFar = SceneInterestPlan.around(flat(0), 8, cameraY, 8);
            assertTrue(tooFar.renderTargets().isEmpty(), "No targets when surface is vertically outside the near sphere");
            assertTrue(tooFar.owners().isEmpty(), "No dense bridge from camera height to surface");
        }
    }

    @Test void repeatedPlansAreImmutableDeterministicAndDistanceOrderedWithStableTies() {
        var source = flat(0);
        var plan = SceneInterestPlan.around(source, 8, 8, 8);
        assertEquals(plan, SceneInterestPlan.around(source, 8, 8, 8));
        var sorted = new ArrayList<>(plan.owners());
        sorted.sort(Comparator.comparingDouble((CubePos p) -> {
            double x = p.minBlockX(), y = p.minBlockY(), z = p.minBlockZ();
            return x * x + y * y + z * z;
        }).thenComparing(Comparator.naturalOrder()));
        assertEquals(sorted, plan.owners());
        assertEquals(POSITION, plan.owners().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> plan.owners().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.renderTargets().clear());
    }

    @Test void missingCoveragePropagatesRatherThanReturningPartialOwnersOrInventingAir() {
        var missing = new MissingTerrainException("Pinned source has no coverage");
        AtomicInteger samples = new AtomicInteger();
        var source = source((x, z) -> {
            if (samples.incrementAndGet() == COLUMN_SAMPLES + 1) throw missing;
            return 0;
        });
        assertSame(missing, assertThrows(MissingTerrainException.class, () -> SceneInterestPlan.around(source, 8, 8, 8)));
        assertEquals(COLUMN_SAMPLES + 1, samples.get());
    }

    @Test void steepSurfaceFailsTheOwnerBudgetInsteadOfTruncatingOrAllocatingFullHeightColumns() {
        AtomicInteger samples = new AtomicInteger();
        var steep = source((x, z) -> {
            assertTrue(samples.incrementAndGet() <= PLAN_SAMPLES, "Source work is bounded to the fixed local footprint");
            return CubePos.local(x) < 8 ? -11000 : 8849;
        });
        var failure = assertThrows(IllegalArgumentException.class, () -> SceneInterestPlan.around(steep, 8, 8, 8));
        assertTrue(failure.getMessage().contains("256"));
        assertTrue(samples.get() > 0 && samples.get() <= PLAN_SAMPLES);
        assertEquals(256, SceneInterestPlan.MAX_OWNERS);
    }

    @Test void unsupportedAndArbitrarilyLargeHeightsFailBeforeAnyVerticalRangeTraversal() {
        for (long height : new long[]{-11001, 8850, Long.MIN_VALUE, Long.MAX_VALUE}) {
            AtomicInteger samples = new AtomicInteger();
            var invalid = source((x, z) -> {
                assertTrue(samples.incrementAndGet() <= COLUMN_SAMPLES);
                return height;
            });
            assertThrows(IllegalArgumentException.class, () -> SceneInterestPlan.around(invalid, 8, 8, 8));
            assertEquals(COLUMN_SAMPLES, samples.get());
        }
    }

    @Test void nonFiniteCameraInputsFailBeforeSampling() {
        var unread = source((x, z) -> { throw new AssertionError("Invalid camera reached terrain sampling"); });
        for (double value : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> SceneInterestPlan.around(unread, value, 8, 8));
            assertThrows(IllegalArgumentException.class, () -> SceneInterestPlan.around(unread, 8, value, 8));
            assertThrows(IllegalArgumentException.class, () -> SceneInterestPlan.around(unread, 8, 8, value));
        }
    }

    @Test void publicPlanConstructorAcceptsExactly256OwnersAndCopiesBothCollections() {
        var owners = new ArrayList<CubePos>();
        for (int x = 0; x < 256; x++) owners.add(new CubePos(x, 0, 0));
        var renderTargets = new HashSet<>(owners);
        var plan = new SceneInterestPlan(owners, renderTargets, 0, 0);
        assertEquals(256, plan.owners().size());
        assertEquals(256, plan.renderTargets().size());
        owners.clear();
        renderTargets.clear();
        assertEquals(256, plan.owners().size());
        assertEquals(256, plan.renderTargets().size());
        assertThrows(UnsupportedOperationException.class, () -> plan.owners().add(POSITION));
        assertThrows(UnsupportedOperationException.class, () -> plan.renderTargets().add(POSITION));
        var empty = new SceneInterestPlan(List.of(), Set.of(), 0, 0);
        assertTrue(empty.owners().isEmpty());
        assertTrue(empty.renderTargets().isEmpty());
    }

    @Test void publicPlanConstructorRejectsOversizedOwnersAfterAtMostOneExtraElement() {
        for (int size : new int[]{257, Integer.MAX_VALUE}) {
            AtomicInteger reads = new AtomicInteger();
            var oversized = new AbstractList<CubePos>() {
                @Override public int size() { return size; }
                @Override public CubePos get(int index) {
                    assertTrue(reads.incrementAndGet() <= 257, "Never traverse an arbitrary oversized list");
                    return new CubePos(index, 0, 0);
                }
            };
            assertThrows(IllegalArgumentException.class, () -> new SceneInterestPlan(oversized, Set.of(), 0, 0));
            assertTrue(reads.get() <= 257);
        }
        var targets = new HashSet<CubePos>();
        for (int x = 0; x < 257; x++) targets.add(new CubePos(x, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SceneInterestPlan(List.of(POSITION), targets, 0, 0));
    }

    @Test void publicPlanConstructorRejectsDuplicateUnknownAndOutOfEnvelopeOwners() {
        assertThrows(IllegalArgumentException.class, () -> new SceneInterestPlan(List.of(POSITION, POSITION), Set.of(POSITION), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SceneInterestPlan(List.of(POSITION), Set.of(POSITION.offset(1, 0, 0)), 0, 0));
        for (int y : new int[]{-689, 554, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            var unsupported = new CubePos(0, y, 0);
            assertThrows(IllegalArgumentException.class, () -> new SceneInterestPlan(List.of(unsupported), Set.of(unsupported), 0, 0));
        }
        var nullableOwners = new ArrayList<CubePos>();
        nullableOwners.add(null);
        assertThrows(NullPointerException.class, () -> new SceneInterestPlan(nullableOwners, Set.of(), 0, 0));
        var nullableTargets = new HashSet<CubePos>();
        nullableTargets.add(null);
        assertThrows(NullPointerException.class, () -> new SceneInterestPlan(List.of(POSITION), nullableTargets, 0, 0));
        assertThrows(NullPointerException.class, () -> new SceneInterestPlan(null, Set.of(), 0, 0));
        assertThrows(NullPointerException.class, () -> new SceneInterestPlan(List.of(), null, 0, 0));
    }

    @Test void publicPlanConstructorDoesNotTrustASetWhoseIteratorRepeatsForever() {
        AtomicInteger reads = new AtomicInteger();
        var repeating = new AbstractSet<CubePos>() {
            @Override public int size() { return 1; }
            @Override public Iterator<CubePos> iterator() {
                return new Iterator<>() {
                    @Override public boolean hasNext() { return true; }
                    @Override public CubePos next() {
                        assertTrue(reads.incrementAndGet() <= 257, "Do not loop forever on a repeated target");
                        return POSITION;
                    }
                };
            }
        };
        assertThrows(IllegalArgumentException.class, () -> new SceneInterestPlan(List.of(POSITION), repeating, 0, 0));
        assertTrue(reads.get() <= 257);
    }

    private static CubeStreamAdmission.Header header(CubeStreamAdmission.Lease lease, long revision) {
        return new CubeStreamAdmission.Header(WORLD, EPOCH, lease.position(), lease.generation(), revision);
    }

    private static void commit(CubeStreamAdmission gate, CubeStreamAdmission.Header header) {
        var admitted = gate.admit(header);
        assertTrue(admitted.accepted());
        assertEquals(CubeStreamAdmission.Rejection.NONE, gate.commit(admitted.token(), () -> {}));
    }

    private static EngineCubeSnapshot snapshot(CubeStreamAdmission.Header header) {
        return EngineCubeSnapshot.capture(header.position(), header.world(), header.connectionEpoch(),
                header.leaseGeneration(), header.revision(), EngineCubeSection.uniform(palette, Blocks.STONE.defaultBlockState()));
    }

    @Test void requestedAndPendingOnlyOwnersAreNeverCurrentLoaded() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 256);
        var lease = gate.replaceInterest(List.of(POSITION)).requested().getFirst();
        var first = header(lease, 0);
        assertFalse(gate.isCurrentLoaded(first));
        var pending = gate.admit(first);
        assertTrue(pending.accepted());
        assertFalse(gate.isCurrentLoaded(first));
        assertEquals(CubeStreamAdmission.Rejection.NONE, gate.commit(pending.token(), () -> assertFalse(gate.isCurrentLoaded(first))));
        assertTrue(gate.isCurrentLoaded(first));

        var second = header(lease, 1);
        var next = gate.admit(second);
        assertTrue(next.accepted());
        assertTrue(gate.isCurrentLoaded(first), "Pending work does not erase the committed native owner");
        assertFalse(gate.isCurrentLoaded(second));
        assertEquals(CubeStreamAdmission.Rejection.NONE, gate.commit(next.token(), () -> {}));
        assertFalse(gate.isCurrentLoaded(first));
        assertTrue(gate.isCurrentLoaded(second));
    }

    @Test void currentLoadedRequiresExactWorldEpochPositionLeaseAndAppliedRevision() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 256);
        var lease = gate.replaceInterest(List.of(POSITION)).requested().getFirst();
        var valid = header(lease, 3);
        commit(gate, valid);
        var otherWorld = new WorldIdentity(43, "scene", "1", "pinned-height", "native-palette");
        var before = gate.view();
        for (var candidate : List.of(
                new CubeStreamAdmission.Header(otherWorld, EPOCH, POSITION, lease.generation(), 3),
                new CubeStreamAdmission.Header(WORLD, EPOCH + 1, POSITION, lease.generation(), 3),
                new CubeStreamAdmission.Header(WORLD, EPOCH, POSITION.offset(1, 0, 0), lease.generation(), 3),
                new CubeStreamAdmission.Header(WORLD, EPOCH, POSITION, lease.generation() + 1, 3),
                header(lease, 2), header(lease, 4))) {
            assertFalse(gate.isCurrentLoaded(candidate));
        }
        assertTrue(gate.isCurrentLoaded(valid));
        assertEquals(before, gate.view(), "Readiness checks do not mutate admission or counters");
        assertThrows(NullPointerException.class, () -> gate.isCurrentLoaded(null));
    }

    @Test void retirementReadditionAndCloseCannotReviveAnOldReadinessStamp() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 256);
        var firstLease = gate.replaceInterest(List.of(POSITION)).requested().getFirst();
        var original = header(firstLease, 3);
        commit(gate, original);
        assertTrue(gate.isCurrentLoaded(original));
        gate.replaceInterest(List.of());
        assertFalse(gate.isCurrentLoaded(original));
        var nextLease = gate.replaceInterest(List.of(POSITION)).requested().getFirst();
        assertTrue(nextLease.generation() > firstLease.generation());
        var replacement = header(nextLease, 3);
        assertFalse(gate.isCurrentLoaded(replacement));
        commit(gate, replacement);
        assertFalse(gate.isCurrentLoaded(original));
        assertTrue(gate.isCurrentLoaded(replacement));
        gate.close();
        assertFalse(gate.isCurrentLoaded(original));
        assertFalse(gate.isCurrentLoaded(replacement));
    }

    @Test void failedAdmissionInvalidatesEvenItsPreviouslyAppliedRevision() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 256);
        var lease = gate.replaceInterest(List.of(POSITION)).requested().getFirst();
        var valid = header(lease, 3);
        commit(gate, valid);
        var next = gate.admit(header(lease, 4));
        assertTrue(next.accepted());
        assertThrows(IllegalStateException.class, () -> gate.commit(next.token(), () -> {
            throw new IllegalStateException("Native owner installation failed");
        }));
        assertEquals(CubeStreamAdmission.State.FAILED, gate.view().state());
        assertFalse(gate.isCurrentLoaded(valid));
    }

    @Test void explicitSceneBudgetAcceptsExactly256WhileDefaultGuardKeepsTheTwoOwnerLimit() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 256);
        try (var guard = new RenderPublicationGuard(gate, 256)) {
            assertEquals(1, guard.resourceGeneration());
        }
        for (int budget : new int[]{1, 2}) {
            try (var guard = new RenderPublicationGuard(new CubeStreamAdmission(WORLD, EPOCH, budget))) {
                assertEquals(1, guard.resourceGeneration());
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new RenderPublicationGuard(new CubeStreamAdmission(WORLD, EPOCH, 3)));
        assertThrows(IllegalArgumentException.class, () -> new RenderPublicationGuard(gate));
        assertThrows(IllegalArgumentException.class, () -> new RenderPublicationGuard(gate, 255));
        assertThrows(IllegalArgumentException.class, () -> new RenderPublicationGuard(new CubeStreamAdmission(WORLD, EPOCH, 257), 256));
        for (int budget : new int[]{-1, 0, 257, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new RenderPublicationGuard(gate, budget));
        }
    }

    @Test void boundedSceneAdmissionAccepts256AndStopsUnboundedInputAfter256Elements() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 256);
        var positions = new ArrayList<CubePos>();
        for (int x = 0; x < 256; x++) positions.add(new CubePos(x, 0, 0));
        assertEquals(256, gate.replaceInterest(positions).requested().size());
        assertEquals(256, gate.view().metrics().activeLeases());
        var before = gate.view();
        AtomicInteger reads = new AtomicInteger(), checks = new AtomicInteger();
        var unbounded = new AbstractCollection<CubePos>() {
            @Override public int size() { throw new AssertionError("Must not trust or require caller size"); }
            @Override public Iterator<CubePos> iterator() {
                return new Iterator<>() {
                    @Override public boolean hasNext() {
                        assertTrue(checks.incrementAndGet() <= 257, "Do not loop over arbitrary input");
                        return true;
                    }
                    @Override public CubePos next() {
                        int x = reads.getAndIncrement();
                        assertTrue(x < 256, "Do not allocate an over-budget owner");
                        return new CubePos(x, 0, 0);
                    }
                };
            }
        };
        assertThrows(IllegalArgumentException.class, () -> gate.replaceInterest(unbounded));
        assertEquals(256, reads.get());
        assertEquals(257, checks.get());
        assertEquals(before, gate.view());
    }

    @Test void resourceGenerationInvalidationAndCloseRejectPreparedSceneJobs() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 256);
        var lease = gate.replaceInterest(List.of(POSITION)).requested().getFirst();
        var loaded = header(lease, 0);
        commit(gate, loaded);
        var snapshot = snapshot(loaded);
        try (var guard = new RenderPublicationGuard(gate, 256)) {
            var old = guard.prepare(snapshot);
            var stamp = new SceneReadyCube(POSITION, WORLD, EPOCH, lease.generation(), 0, guard.resourceGeneration());
            assertTrue(guard.isCurrent(old));
            guard.invalidateResources();
            assertEquals(stamp.resourceGeneration() + 1, guard.resourceGeneration());
            assertFalse(guard.isCurrent(old));
            assertFalse(guard.publish(old, () -> fail("Old resource generation published")));
            var fresh = guard.prepare(snapshot);
            assertTrue(guard.isCurrent(fresh));
            assertTrue(guard.publish(fresh, () -> assertTrue(Thread.holdsLock(gate))));
            guard.close();
            assertFalse(guard.isCurrent(fresh));
            assertFalse(guard.publish(fresh, () -> fail("Closed renderer published")));
            assertThrows(IllegalStateException.class, () -> guard.prepare(snapshot));
        }
        try (var guard = new RenderPublicationGuard(gate, 256)) {
            var job = guard.prepare(snapshot);
            guard.requestInvalidation();
            assertTrue(guard.invalidationRequested());
            assertTrue(gate.isCurrentLoaded(loaded), "Resource reload cannot invent an admission retirement");
            assertFalse(guard.isCurrent(job));
            assertFalse(guard.publish(job, () -> fail("Reload-pending renderer published")));
            assertThrows(IllegalStateException.class, () -> guard.prepare(snapshot));
        }
    }

    @Test void drawChecksEveryHaloDependencyUnderTheAdmissionMonitor() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 256);
        var neighbor = POSITION.offset(1, 1, 1);
        var leases = gate.replaceInterest(List.of(POSITION, neighbor)).requested();
        var ownerHeader = header(leases.get(0), 0);
        var haloHeader = header(leases.get(1), 0);
        commit(gate, ownerHeader);
        commit(gate, haloHeader);
        try (var guard = new RenderPublicationGuard(gate, 256)) {
            var owner = guard.prepare(snapshot(ownerHeader));
            var halo = guard.prepare(snapshot(haloHeader));
            AtomicInteger traversals = new AtomicInteger(), draws = new AtomicInteger();
            var dependencies = new AbstractCollection<RenderPublicationGuard.Job>() {
                @Override public int size() { return 2; }
                @Override public Iterator<RenderPublicationGuard.Job> iterator() {
                    assertTrue(Thread.holdsLock(gate), "Dependency traversal must share the retirement monitor");
                    traversals.incrementAndGet();
                    return List.of(owner, halo).iterator();
                }
            };
            assertTrue(guard.drawIfCurrent(dependencies, () -> {
                assertTrue(Thread.holdsLock(gate), "Native submission must remain inside the same fence");
                assertEquals(1, traversals.get());
                draws.incrementAndGet();
                return true;
            }));
            assertFalse(guard.drawIfCurrent(dependencies, () -> false), "Native submission result must be preserved");
            gate.replaceInterest(List.of(POSITION));
            assertTrue(guard.isCurrent(owner));
            assertFalse(guard.drawIfCurrent(dependencies, () -> {
                fail("Missing diagonal halo must prevent the draw even when its target remains current");
                return true;
            }));
            var replacementLease = gate.replaceInterest(List.of(POSITION, neighbor)).requested().getFirst();
            commit(gate, header(replacementLease, 0));
            assertFalse(guard.drawIfCurrent(dependencies, () -> {
                fail("Same-position halo readdition must not revive stale dependency generation");
                return true;
            }));
            assertEquals(1, draws.get());
        }
    }

    @Test void drawDependencyBudgetAccepts256AndRejectsEmptyOrUnboundedInput() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 256);
        var positions = new ArrayList<CubePos>();
        for (int x = 0; x < 256; x++) positions.add(new CubePos(x, 0, 0));
        var leases = gate.replaceInterest(positions).requested();
        try (var guard = new RenderPublicationGuard(gate, 256)) {
            var jobs = new ArrayList<RenderPublicationGuard.Job>();
            for (var lease : leases) {
                var loaded = header(lease, 0);
                commit(gate, loaded);
                jobs.add(guard.prepare(snapshot(loaded)));
            }
            assertTrue(guard.drawIfCurrent(jobs, () -> true));
            assertThrows(IllegalArgumentException.class, () -> guard.drawIfCurrent(List.of(), () -> {
                fail("A draw without an owner or dependencies must not enter submission");
                return true;
            }));
            AtomicInteger reads = new AtomicInteger();
            var unbounded = new AbstractCollection<RenderPublicationGuard.Job>() {
                @Override public int size() { return 1; }
                @Override public Iterator<RenderPublicationGuard.Job> iterator() {
                    return new Iterator<>() {
                        @Override public boolean hasNext() { return true; }
                        @Override public RenderPublicationGuard.Job next() {
                            assertTrue(reads.incrementAndGet() <= 257, "Draw dependency traversal must have a hard stop");
                            return jobs.getFirst();
                        }
                    };
                }
            };
            assertThrows(IllegalArgumentException.class, () -> guard.drawIfCurrent(unbounded, () -> {
                fail("Over-budget dependencies reached native submission");
                return true;
            }));
            assertTrue(reads.get() <= 257);
        }
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, interrupted);
        }
    }

    @Test void concurrentReloadAndRetirementCannotCrossAnEnteredDrawFence() throws Exception {
        for (boolean reload : new boolean[]{false, true}) {
            var gate = new CubeStreamAdmission(WORLD, EPOCH, 256);
            var lease = gate.replaceInterest(List.of(POSITION)).requested().getFirst();
            var loaded = header(lease, 0);
            commit(gate, loaded);
            var entered = new CountDownLatch(1);
            var attempted = new CountDownLatch(1);
            var callbackFinished = new AtomicBoolean();
            var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
            try (var guard = new RenderPublicationGuard(gate, 256)) {
                var job = guard.prepare(snapshot(loaded));
                var mutation = executor.submit(() -> {
                    await(entered, "Draw did not enter");
                    attempted.countDown();
                    if (reload) guard.requestInvalidation();
                    else gate.replaceInterest(List.of());
                    assertTrue(callbackFinished.get(), "Invalidation returned before the fenced draw finished");
                });
                assertTrue(guard.drawIfCurrent(List.of(job), () -> {
                    assertTrue(Thread.holdsLock(gate));
                    entered.countDown();
                    await(attempted, "Mutation did not attempt entry");
                    assertThrows(TimeoutException.class, () -> mutation.get(100, TimeUnit.MILLISECONDS));
                    assertFalse(mutation.isDone());
                    assertTrue(guard.isCurrent(job));
                    callbackFinished.set(true);
                    return true;
                }));
                mutation.get(10, TimeUnit.SECONDS);
                assertFalse(guard.drawIfCurrent(List.of(job), () -> {
                    fail("Draw entered after invalidation completed");
                    return true;
                }));
            } finally {
                entered.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test void readinessStampsPreserveExactIdentityAndRejectInvalidGenerations() {
        var valid = new SceneReadyCube(POSITION, WORLD, 0, 1, 0, 1);
        assertEquals(POSITION, valid.position());
        assertEquals(WORLD, valid.world());
        assertEquals(0, valid.epoch());
        assertEquals(1, valid.leaseGeneration());
        assertEquals(0, valid.revision());
        assertEquals(1, valid.resourceGeneration());
        assertNotEquals(valid, new SceneReadyCube(POSITION, WORLD, 0, 1, 0, 2));
        assertThrows(NullPointerException.class, () -> new SceneReadyCube(null, WORLD, 0, 1, 0, 1));
        assertThrows(NullPointerException.class, () -> new SceneReadyCube(POSITION, null, 0, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SceneReadyCube(POSITION, WORLD, -1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SceneReadyCube(POSITION, WORLD, 0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new SceneReadyCube(POSITION, WORLD, 0, 1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new SceneReadyCube(POSITION, WORLD, 0, 1, 0, 0));
    }
}
