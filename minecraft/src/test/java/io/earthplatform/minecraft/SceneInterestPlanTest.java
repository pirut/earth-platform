package io.earthplatform.minecraft;

import com.mojang.serialization.Lifecycle;
import io.earthplatform.core.CubePos;
import io.earthplatform.core.WorldIdentity;
import io.earthplatform.geography.MissingTerrainException;
import io.earthplatform.minecraft.network.EngineCubeSection;
import io.earthplatform.minecraft.network.EngineCubeSnapshot;
import io.earthplatform.minecraft.network.EnginePaletteContext;
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

    private static void await(CountDownLatch latch, String message) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), message);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, interrupted);
        }
    }
}
