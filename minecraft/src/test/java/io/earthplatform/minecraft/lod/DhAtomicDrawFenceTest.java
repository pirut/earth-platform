package io.earthplatform.minecraft.lod;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
import io.earthplatform.streaming.CubeStreamAdmission;
import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.*;

/** Headless CPU command-submission linearization checks, not GPU completion synchronization. */
class DhAtomicDrawFenceTest {
    private static final WorldIdentity WORLD = new WorldIdentity(42, "earth", "surface-fill-v1", "noaa-etopo-2022", "materials-v1");
    private static final UUID SESSION = UUID.fromString("63f02eb7-8ceb-4e5f-a267-2607ebc42484");
    private static final long EPOCH = 7;
    private static final CubePos LOWER = new CubePos(0, -688, 0);
    private static final CubePos UPPER = new CubePos(0, 553, 0);
    private static final long TIMEOUT_SECONDS = 10;
    private static EnginePaletteContext palette;

    @BeforeAll static void registries() {
        var registry = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
        registry.register(Biomes.PLAINS,
                VanillaRegistries.createLookup().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(),
                RegistrationInfo.BUILT_IN);
        palette = EnginePaletteContext.create(registry.freeze());
    }

    private static DhPageOwnership.Namespace namespace() {
        return new DhPageOwnership.Namespace(WORLD, SESSION, "earth:overworld", "equirectangular", "v1", "EGM2008");
    }

    private static EngineCubeSnapshot snapshot(CubeStreamAdmission gate, CubeStreamAdmission.Lease lease, long revision) {
        var section = new EngineCubeSection(palette);
        section.set(8, 8, 8, Blocks.GOLD_BLOCK.defaultBlockState());
        return EngineCubeSnapshot.capture(lease.position(), gate.world(), gate.connectionEpoch(),
                lease.generation(), revision, section);
    }

    private static void commit(CubeStreamAdmission gate, EngineCubeSnapshot snapshot) {
        var admitted = gate.admit(new CubeStreamAdmission.Header(snapshot.world(), snapshot.epoch(), snapshot.position(),
                snapshot.leaseGeneration(), snapshot.revision()));
        assertTrue(admitted.accepted());
        assertEquals(CubeStreamAdmission.Rejection.NONE, gate.commit(admitted.token(), () -> {}));
    }

    private static DhPageOwnership.Job publish(DhPageOwnership owner, EngineCubeSnapshot snapshot) {
        var job = owner.prepare(snapshot);
        assertTrue(owner.publish(job, () -> {}));
        return job;
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, interrupted);
        }
    }

    /** The latches order the attempt; the monitor assertion proves exclusion without polling. */
    private static void assertDrawExcludesMutation(CubeStreamAdmission gate, DhPageOwnership owner,
                                                   DhPageOwnership.Job job, Runnable mutation) throws Exception {
        var drawEntered = new CountDownLatch(1);
        var mutationAttempted = new CountDownLatch(1);
        var callbackFinished = new AtomicBoolean();
        var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
        try {
            var pending = executor.submit(() -> {
                await(drawEntered, "Draw callback did not start");
                mutationAttempted.countDown();
                mutation.run();
                assertTrue(callbackFinished.get(), "Mutation returned before the entered draw callback finished");
            });
            assertTrue(owner.drawIfCurrent(job, List.of(), () -> {
                assertTrue(Thread.holdsLock(gate), "Draw must retain the authoritative admission monitor");
                drawEntered.countDown();
                await(mutationAttempted, "Concurrent mutation did not attempt entry");
                // A bounded Future wait lets the contender run; there are no sleeps or spin loops.
                assertThrows(TimeoutException.class, () -> pending.get(100, TimeUnit.MILLISECONDS),
                        "Invalidation/retirement must remain pending while command submission holds the lock");
                assertFalse(pending.isDone());
                assertTrue(owner.isCurrent(job));
                callbackFinished.set(true);
            }));
            pending.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertFalse(owner.drawIfCurrent(job, List.of(), () -> fail("Draw entered after invalidation returned")));
            assertEquals(1, owner.drawn());
            assertEquals(1, owner.rejectedDraws());
            assertEquals(1, owner.published(), "Drawing must not be counted as another upload");
            assertEquals(0, owner.rejected(), "Draw rejection must not change upload rejection counts");
        } finally {
            drawEntered.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Contender did not terminate");
        }
    }

    @Test void offThreadReloadCannotReturnUntilEnteredDrawFinishes() throws Exception {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        commit(gate, snapshot);
        try (var owner = new DhPageOwnership(gate, namespace())) {
            var job = publish(owner, snapshot);
            assertDrawExcludesMutation(gate, owner, job, owner::requestInvalidation);
            assertEquals(1, owner.resourceGeneration(), "Off-thread reload fencing must not rebuild resources");
        }
    }

    @Test void offThreadLeaseRetirementCannotReturnUntilEnteredDrawFinishes() throws Exception {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        commit(gate, snapshot);
        try (var owner = new DhPageOwnership(gate, namespace())) {
            var job = publish(owner, snapshot);
            assertDrawExcludesMutation(gate, owner, job, () -> gate.replaceInterest(List.of()));
            assertEquals(0, gate.view().metrics().activeLeases());
        }
    }

    @Test void onlySuccessfullyPublishedCurrentCandidateDrawsAndCountersStaySeparate() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        commit(gate, snapshot);
        try (var owner = new DhPageOwnership(gate, namespace())) {
            var job = owner.prepare(snapshot);
            var callbacks = new AtomicInteger();
            assertFalse(owner.drawIfCurrent(job, List.of(), callbacks::incrementAndGet));
            assertTrue(owner.publish(job, () ->
                    assertFalse(owner.drawIfCurrent(job, List.of(), callbacks::incrementAndGet))));
            assertTrue(owner.drawIfCurrent(job, List.of(), callbacks::incrementAndGet));
            assertTrue(owner.drawIfCurrent(job, List.of(), callbacks::incrementAndGet));
            var replacement = publish(owner, snapshot);
            assertFalse(owner.drawIfCurrent(job, List.of(), callbacks::incrementAndGet));
            assertTrue(owner.drawIfCurrent(replacement, List.of(), callbacks::incrementAndGet));
            assertEquals(3, callbacks.get());
            assertEquals(3, owner.drawn());
            assertEquals(3, owner.rejectedDraws());
            assertEquals(2, owner.published());
            assertEquals(0, owner.rejected());
        }
    }

    @Test void nativeReadyMaskUsesFullXyzAndIsTraversedUnderTheDrawMonitor() {
        // Both adjacent cubes within one page and the two native-height extremes must remain distinct.
        for (var cubes : List.of(List.of(LOWER, UPPER), List.of(new CubePos(0, 0, 0), new CubePos(0, 1, 0)))) {
            var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
            var leases = gate.replaceInterest(cubes).requested();
            var lower = snapshot(gate, leases.get(0), 3);
            var upper = snapshot(gate, leases.get(1), 3);
            commit(gate, lower);
            commit(gate, upper);
            try (var owner = new DhPageOwnership(gate, namespace())) {
                var lowerJob = publish(owner, lower);
                var upperJob = publish(owner, upper);
                var callbacks = new AtomicInteger();
                assertFalse(owner.drawIfCurrent(lowerJob, List.of(lower), callbacks::incrementAndGet));
                assertFalse(owner.drawIfCurrent(upperJob, List.of(upper), callbacks::incrementAndGet));
                var traversals = new AtomicInteger();
                var readyOtherY = new AbstractCollection<EngineCubeSnapshot>() {
                    @Override public int size() { return 1; }
                    @Override public Iterator<EngineCubeSnapshot> iterator() {
                        assertTrue(Thread.holdsLock(gate), "Ready-mask collection must be read under the admission monitor");
                        traversals.incrementAndGet();
                        return List.of(upper).iterator();
                    }
                };
                assertTrue(owner.drawIfCurrent(lowerJob, readyOtherY, () -> {
                    assertTrue(Thread.holdsLock(gate));
                    assertEquals(1, traversals.get(), "The current ready mask must be checked before submission");
                    callbacks.incrementAndGet();
                }));
                assertTrue(owner.drawIfCurrent(upperJob, List.of(lower), callbacks::incrementAndGet));
                assertEquals(2, callbacks.get());
                assertEquals(2, owner.drawn());
                assertEquals(2, owner.rejectedDraws());
            }
        }
    }

    @Test void retiredLeaseCannotDrawAfterSamePositionAndRevisionAreReadded() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var original = snapshot(gate, lease, 3);
        commit(gate, original);
        try (var owner = new DhPageOwnership(gate, namespace())) {
            var old = publish(owner, original);
            gate.replaceInterest(List.of());
            var renewed = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
            var replacement = snapshot(gate, renewed, original.revision());
            assertNotEquals(original.leaseGeneration(), replacement.leaseGeneration());
            commit(gate, replacement);
            var next = publish(owner, replacement);
            assertFalse(owner.drawIfCurrent(old, List.of(), () -> fail("Retired lease reached submission")));
            assertTrue(owner.drawIfCurrent(next, List.of(original), () -> {}), "Old native lease must not mask the replacement");
            assertFalse(owner.drawIfCurrent(next, List.of(replacement), () -> fail("Matching native mesh was not masked")));
            assertEquals(1, owner.drawn());
            assertEquals(2, owner.rejectedDraws());
        }
    }

    @Test void oldResourceGenerationCannotDrawAndNewGenerationRequiresPublication() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        commit(gate, snapshot);
        try (var owner = new DhPageOwnership(gate, namespace())) {
            var old = publish(owner, snapshot);
            owner.invalidateResources();
            assertFalse(owner.drawIfCurrent(old, List.of(), () -> fail("Old resources reached submission")));
            var replacement = owner.prepare(snapshot);
            assertFalse(owner.drawIfCurrent(replacement, List.of(), () -> fail("Unpublished resources reached submission")));
            assertTrue(owner.publish(replacement, () -> {}));
            assertTrue(owner.drawIfCurrent(replacement, List.of(), () -> {}));
            assertEquals(1, owner.drawn());
            assertEquals(2, owner.rejectedDraws());
            assertEquals(2, owner.published());
        }
    }

    @Test void callbackCannotSilentlyInvalidateItsOwnSubmittedJob() {
        for (int mode = 0; mode < 3; mode++) {
            var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
            var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
            var snapshot = snapshot(gate, lease, 3);
            commit(gate, snapshot);
            try (var owner = new DhPageOwnership(gate, namespace())) {
                var job = publish(owner, snapshot);
                Runnable invalidation = switch (mode) {
                    case 0 -> owner::requestInvalidation;
                    case 1 -> owner::invalidateResources;
                    default -> () -> gate.replaceInterest(List.of());
                };
                var callbacks = new AtomicInteger();
                assertThrows(IllegalStateException.class, () -> owner.drawIfCurrent(job, List.of(), () -> {
                    assertTrue(Thread.holdsLock(gate));
                    callbacks.incrementAndGet();
                    invalidation.run();
                }));
                assertEquals(1, callbacks.get(), "Submitted CPU work is not rolled back by the validation failure");
                assertFalse(owner.drawIfCurrent(job, List.of(), () -> fail("Self-invalidated job drew again")));
                assertEquals(0, owner.drawn());
                assertEquals(1, owner.published());
            }
        }
    }

    @Test void drawAndItsCountersRequireTheOwnerThread() throws Exception {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        commit(gate, snapshot);
        try (var owner = new DhPageOwnership(gate, namespace())) {
            var job = publish(owner, snapshot);
            var callbacks = new AtomicInteger();
            var executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
            try {
                executor.submit(() -> {
                    List<Executable> operations = List.of(
                            () -> owner.drawIfCurrent(job, List.of(), callbacks::incrementAndGet),
                            owner::drawn, owner::rejectedDraws);
                    for (var operation : operations) {
                        var failure = assertThrows(IllegalStateException.class, operation);
                        assertTrue(failure.getMessage().contains("owner thread"));
                    }
                }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            assertEquals(0, callbacks.get());
            assertEquals(0, owner.drawn());
            assertEquals(0, owner.rejectedDraws());
            assertTrue(owner.drawIfCurrent(job, List.of(), callbacks::incrementAndGet));
            assertEquals(1, callbacks.get());
        }
    }

    @Test void invalidReadyInputsNeverInvokeSubmissionEvenForStaleJobs() {
        var gate = new CubeStreamAdmission(WORLD, EPOCH, 2);
        var lease = gate.replaceInterest(List.of(LOWER)).requested().getFirst();
        var snapshot = snapshot(gate, lease, 3);
        commit(gate, snapshot);
        try (var owner = new DhPageOwnership(gate, namespace())) {
            var job = publish(owner, snapshot);
            var callbacks = new AtomicInteger();
            for (boolean invalidate : List.of(false, true)) {
                if (invalidate) owner.invalidateResources();
                assertThrows(IllegalArgumentException.class,
                        () -> owner.drawIfCurrent(job, List.of(snapshot, snapshot, snapshot), callbacks::incrementAndGet));
                assertThrows(NullPointerException.class,
                        () -> owner.drawIfCurrent(job, Arrays.asList(snapshot, null), callbacks::incrementAndGet));
                assertThrows(NullPointerException.class, () -> owner.drawIfCurrent(job, null, callbacks::incrementAndGet));
                assertThrows(NullPointerException.class, () -> owner.drawIfCurrent(null, List.of(), callbacks::incrementAndGet));
                assertThrows(NullPointerException.class, () -> owner.drawIfCurrent(job, List.of(), null));
            }
            assertEquals(0, callbacks.get());
            assertEquals(0, owner.drawn());
        }
    }
}
